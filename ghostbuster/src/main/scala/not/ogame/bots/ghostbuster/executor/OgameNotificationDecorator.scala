package not.ogame.bots.ghostbuster.executor

import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.subjects.ConcurrentSubject
import not.ogame.bots.{
  FacilityBuilding,
  FacilityPageData,
  Fleet,
  FleetId,
  FleetPageData,
  MyFleet,
  OgameDriver,
  PlanetId,
  PlayerPlanet,
  SendFleetRequest,
  ShipType,
  SuppliesBuilding,
  SuppliesPageData
}
import cats.implicits._
import monix.reactive.Observable

class OgameNotificationDecorator(driver: OgameDriver[Task])(implicit s: Scheduler) extends OgameDriver[Task] with NotificationAware {
  private val notifications = ConcurrentSubject.publish[Notification]

  override def login(): Task[Unit] = driver.login().flatTap(_ => notify(Notification.Login()))

  override def readSuppliesPage(planetId: PlanetId): Task[SuppliesPageData] =
    driver
      .readSuppliesPage(planetId)
      .flatTap(sp => notify(Notification.SuppliesPageDateRefreshed(sp, planetId)))
      .onError { case e => notify(Notification.Failure(e)) }

  override def buildSuppliesBuilding(planetId: PlanetId, suppliesBuilding: SuppliesBuilding): Task[Unit] =
    driver
      .buildSuppliesBuilding(planetId, suppliesBuilding)
      .flatTap(_ => notify(Notification.SupplyBuilt(suppliesBuilding)))
      .onError { case e => notify(Notification.Failure(e)) }

  override def readFacilityPage(planetId: PlanetId): Task[FacilityPageData] =
    driver
      .readFacilityPage(planetId)
      .flatTap(fp => notify(Notification.FacilityPageDataRefreshed(fp, planetId)))
      .onError { case e => notify(Notification.Failure(e)) }

  override def buildFacilityBuilding(planetId: PlanetId, facilityBuilding: FacilityBuilding): Task[Unit] =
    driver
      .buildFacilityBuilding(planetId, facilityBuilding)
      .flatTap(_ => notify(Notification.FacilityBuilt(facilityBuilding)))
      .onError { case e => notify(Notification.Failure(e)) }

  override def buildShips(planetId: PlanetId, shipType: ShipType, count: Int): Task[Unit] =
    driver
      .buildShips(planetId, shipType, count)
      .flatTap(_ => notify(Notification.ShipBuilt(shipType, count, planetId)))
      .onError { case e => notify(Notification.Failure(e)) }

  override def readFleetPage(planetId: PlanetId): Task[FleetPageData] =
    driver
      .readFleetPage(planetId)
      .flatTap(fp => notify(Notification.FleetOnPlanetRefreshed(fp, planetId)))
      .onError { case e => notify(Notification.Failure(e)) }

  override def readAllFleets(): Task[List[Fleet]] =
    driver
      .readAllFleets()
      .flatTap(f => notify(Notification.ReadAllFleets(f)))
      .onError { case e => notify(Notification.Failure(e)) }

  override def readMyFleets(): Task[List[MyFleet]] =
    driver
      .readMyFleets()
      .flatTap(f => notify(Notification.ReadMyFleetAction(f)))
      .onError { case e => notify(Notification.Failure(e)) }

  override def sendFleet(sendFleetRequest: SendFleetRequest): Task[Unit] =
    driver
      .sendFleet(sendFleetRequest)
      .flatTap(_ => notify(Notification.FleetSent(sendFleetRequest)))
      .onError { case e => notify(Notification.Failure(e)) }

  override def returnFleet(fleetId: FleetId): Task[Unit] =
    driver
      .returnFleet(fleetId)
      .flatTap(_ => notify(Notification.FleetReturned(fleetId)))
      .onError { case e => notify(Notification.Failure(e)) }

  override def readPlanets(): Task[List[PlayerPlanet]] =
    driver
      .readPlanets()
      .flatTap(p => notify(Notification.ReadPlanets(p)))
      .onError { case e => notify(Notification.Failure(e)) }

  override def checkIsLoggedIn(): Task[Boolean] = driver.checkIsLoggedIn()

  private def notify(refreshed: Notification) = {
    Task.fromFuture(notifications.onNext(refreshed)).void
  }

  override def subscribeToNotifications: Observable[Notification] = notifications
}

trait NotificationAware {
  def subscribeToNotifications: Observable[Notification]
}
