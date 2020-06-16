package not.ogame.bots.ghostbuster.processors

import java.time.ZonedDateTime

import cats.implicits._
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import monix.eval.Task
import not.ogame.bots._
import not.ogame.bots.ghostbuster.{ExpeditionConfig, FLogger, PlanetFleet}

import scala.concurrent.duration._

class ExpeditionProcessor(expeditionConfig: ExpeditionConfig, taskExecutor: TaskExecutor)(implicit clock: LocalClock) extends FLogger {
  def run(): Task[Unit] = {
    if (expeditionConfig.isOn) {
      taskExecutor
        .readPlanetsAndMoons()
        .map(planets => planets.filter(p => expeditionConfig.eligiblePlanets.contains(p.id)))
        .flatMap(lookForFleet)
        .onError(e => Logger[Task].error(s"restarting expedition processor ${e.getMessage}"))
        .onErrorRestartIf(_ => true)
    } else {
      Task.never
    }
  }

  private def lookForFleet(planets: List[PlayerPlanet]): Task[Unit] = {
    for {
      fleets <- taskExecutor.readAllFleets()
      expeditions = fleets.filter(fleet => isExpedition(fleet))
      returningExpeditions = expeditions.filter(_.isReturning)
      _ <- if (returningExpeditions.size < expeditionConfig.maxNumberOfExpeditions) {
        Logger[Task].info(
          s"Only ${returningExpeditions.size}/${expeditionConfig.maxNumberOfExpeditions} expeditions are in the air"
        ) >>
          lookForFleetOnPlanets(planets, fleets) >> lookForFleet(planets)
      } else {
        val min = expeditions.map(_.arrivalTime).min
        Logger[Task].info(s"All expeditions are in the air, waiting for first to reach its target - $min") >>
          taskExecutor.waitTo(min) >> lookForFleet(planets)
      }
    } yield ()
  }

  private def isExpedition(fleet: Fleet) = {
    fleet.fleetAttitude == FleetAttitude.Friendly && fleet.fleetMissionType == FleetMissionType.Expedition
  }

  private def sendExpedition(fromPlanet: PlayerPlanet): Task[Unit] = {
    taskExecutor
      .readSupplyPage(fromPlanet)
      .flatMap { suppliesPageData =>
        if (suppliesPageData.currentResources.deuterium >= expeditionConfig.deuterThreshold) {
          sendFleetImpl(fromPlanet).void
        } else {
          Logger[Task].info(s"There was not enough deuter, expedition won't be send this time")
        }
      }
  }

  private def sendFleetImpl(fromPlanet: PlayerPlanet) = {
    Logger[Task].info("Sending fleet...") >>
      taskExecutor
        .sendFleet(
          SendFleetRequest(
            fromPlanet,
            SendFleetRequestShips.Ships(expeditionConfig.ships.map(s => s.shipType -> s.amount).toMap),
            fromPlanet.coordinates.copy(position = 16, coordinatesType = CoordinatesType.Planet),
            FleetMissionType.Expedition,
            FleetResources.Given(Resources.Zero)
          )
        )
        .flatTap(_ => Logger[Task].info("Fleet sent"))
  }
  private def lookForFleetOnPlanets(planets: List[PlayerPlanet], allFleets: List[Fleet]) = {
    Stream
      .emits(planets)
      .evalMap(taskExecutor.getFleetOnPlanet)
      .collectFirst { case PlanetFleet(planet, fleet) if isExpeditionFleet(fleet) => planet }
      .evalMap(sendExpedition)
      .compile
      .last
      .flatMap {
        case Some(_) => ().pure[Task]
        case None    => waitToEarliestFleet(allFleets) // flatMap checkFleetOnGivenPlanet >> repairIfNeeded
      }
  }

  private def waitToEarliestFleet(allFleets: List[Fleet]) = {
    val tenMinutesFromNow = clock.now().plusMinutes(10)
    val minAnyFleetArrivalTime = minOr(allFleets.map(_.arrivalTime))(tenMinutesFromNow)
    val waitTo = List(minAnyFleetArrivalTime, tenMinutesFromNow).min
    Logger[Task].info(s"Could find expedition fleet on any planet. Waiting til $waitTo....") >> taskExecutor.waitTo(waitTo)
  }

  private def minOr[R: Ordering](l: List[R])(or: => R): R = {
    l match {
      case l if l.nonEmpty => l.min
      case _               => or
    }
  }

  private def isExpeditionFleet(fleet: Map[ShipType, Int]): Boolean = {
    expeditionConfig.ships.forall(ship => ship.amount <= fleet(ship.shipType))
  }
}
