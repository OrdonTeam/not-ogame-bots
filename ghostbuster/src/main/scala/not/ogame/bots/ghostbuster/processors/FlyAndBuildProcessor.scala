package not.ogame.bots.ghostbuster.processors

import java.time.ZonedDateTime

import cats.implicits._
import io.chrisdavenport.log4cats.Logger
import monix.eval.Task
import not.ogame.bots._
import not.ogame.bots.ghostbuster.{BotConfig, FLogger, PlanetFleet}

import scala.concurrent.duration.{FiniteDuration, _}
import scala.jdk.DurationConverters._

class FlyAndBuildProcessor(taskExecutor: TaskExecutor, botConfig: BotConfig, clock: LocalClock) extends FLogger {
  private val builder = new Builder(taskExecutor, botConfig)

  def run(): Task[Unit] = {
    if (botConfig.fsConfig.isOn) {
      taskExecutor
        .readPlanets()
        .flatMap(lookAtInTheAir)
        .onErrorRestartIf(_ => true)
    } else {
      Task.never
    }
  }

  private def lookAtInTheAir(planets: List[PlayerPlanet]): Task[Unit] = {
    for {
      fleets <- taskExecutor.readAllFleets()
      possibleFsFleets = fleets.filter(f => isFsFleet(planets, f))
      _ <- possibleFsFleets match {
        case fleet :: Nil =>
          Logger[Task].info(s"Found our fleet in the air: ${pprint.apply(fleet)}").flatMap { _ =>
            taskExecutor.waitTo(fleet.arrivalTime).flatMap { _ =>
              if (fleet.isReturning) {
                val fromPlanet = planets.find(p => fleet.from == p.coordinates).get
                buildAndSend(fromPlanet, planets)
              } else {
                val toPlanet = planets.find(p => fleet.to == p.coordinates).get
                buildAndSend(toPlanet, planets)
              }
            }
          }
        case l @ _ :: _ =>
          Logger[Task].info("Too many fleets in the air. Waiting for the first one to reach its target.") >>
            taskExecutor.waitTo(l.map(_.arrivalTime).min) >> lookAtInTheAir(planets)
        case Nil => lookOnPlanets(planets)
      }
    } yield ()
  }

  private def isFsFleet(planets: List[PlayerPlanet], f: Fleet) = {
    f.fleetAttitude == FleetAttitude.Friendly && f.fleetMissionType == FleetMissionType.Deployment && planets
      .exists(p => p.coordinates == f.to) && planets.exists(p => p.coordinates == f.from)
  }

  private def lookOnPlanets(planets: List[PlayerPlanet]): Task[Unit] = {
    val planetWithFsFleet = planets
      .map { planet =>
        taskExecutor.getFleetOnPlanet(planet)
      }
      .sequence
      .map(planetFleets => planetFleets.find(isFsFleet))

    planetWithFsFleet.flatMap {
      case Some(planet) =>
        println(s"Planet with fs fleet ${pprint.apply(planet)}")
        buildAndSend(planet.playerPlanet, planets)
      case None =>
        println("Couldn't find fs fleet on any planet, retrying in 10 minutes")
        Task.sleep(10 minutes) >> lookOnPlanets(planets)
    }
  }

  private def isFsFleet(planetFleet: PlanetFleet): Boolean = {
    botConfig.fsConfig.ships.forall(fsShip => fsShip.amount <= planetFleet.fleet(fsShip.shipType))
  }

  private def buildAndSend(currentPlanet: PlayerPlanet, planets: List[PlayerPlanet]): Task[Unit] = {
    val targetPlanet = nextPlanet(currentPlanet, planets)
    for {
      _ <- buildAndContinue(currentPlanet, clock.now())
      _ <- sendFleet(from = currentPlanet, to = targetPlanet)
      _ <- buildAndSend(currentPlanet = targetPlanet, planets)
    } yield ()
  }

  private def nextPlanet(currentPlanet: PlayerPlanet, planets: List[PlayerPlanet]) = {
    val idx = (planets.indexOf(currentPlanet) + 1) % planets.size
    planets(idx)
  }

  private def sendFleet(from: PlayerPlanet, to: PlayerPlanet): Task[Unit] = { //TODO if couldn't take all resources then build mt
    for {
      _ <- Logger[Task].info("Sending fleet...")
      arrivalTime <- taskExecutor
        .sendFleet(
          SendFleetRequest(
            from,
            if (botConfig.fsConfig.gatherShips) {
              SendFleetRequestShips.AllShips
            } else {
              SendFleetRequestShips.Ships(botConfig.fsConfig.ships.map(s => s.shipType -> s.amount).toMap)
            },
            to.coordinates,
            FleetMissionType.Deployment,
            if (botConfig.fsConfig.takeResources) {
              FleetResources.Max
            } else {
              FleetResources.Given(Resources.Zero)
            },
            FleetSpeed.Percent50 //TODO add to config
          )
        )
      _ <- taskExecutor.waitTo(arrivalTime)
    } yield ()
  }

  private def buildAndContinue(planet: PlayerPlanet, startedBuildingAt: ZonedDateTime): Task[Unit] = { //TODO it should be inside smart builder not outside
    builder.buildNextThingFromWishList(planet).flatMap {
      case Some(finishTime)
          if timeDiff(clock.now(), finishTime) < (10 minutes) && timeDiff(startedBuildingAt, clock.now()) < (20 minutes) =>
        taskExecutor.waitTo(finishTime) >> buildAndContinue(planet, startedBuildingAt)
      case _ => Task.unit
    }
  }

  private def timeDiff(earlier: ZonedDateTime, later: ZonedDateTime): FiniteDuration = {
    java.time.Duration.between(earlier, later).toScala
  }
}
