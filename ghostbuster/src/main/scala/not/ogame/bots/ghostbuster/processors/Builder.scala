package not.ogame.bots.ghostbuster.processors

import java.time.ZonedDateTime

import not.ogame.bots.ghostbuster.executor._
import cats.implicits._
import eu.timepit.refined.numeric.Positive
import io.chrisdavenport.log4cats.Logger
import not.ogame.bots.facts.{FacilityBuildingCosts, ShipCosts, SuppliesBuildingCosts, TechnologyCosts}
import not.ogame.bots.ghostbuster.{FLogger, Wish}
import not.ogame.bots.selenium.refineVUnsafe
import not.ogame.bots._
import not.ogame.bots.ghostbuster.ogame.OgameAction

class Builder(ogameActionDriver: OgameDriver[OgameAction], wishlist: List[Wish])(
    implicit clock: LocalClock
) extends FLogger {
  def buildNextThingFromWishList(planet: PlayerPlanet): OgameAction[BuilderResult] = {
    val wishesForPlanet = wishlist.filter(_.planetId == planet.id)
    if (wishesForPlanet.nonEmpty) {
      for {
        sp <- ogameActionDriver.readSuppliesPage(planet.id)
        fp <- ogameActionDriver.readFacilityPage(planet.id)
        tp <- ogameActionDriver.readTechnologyPage(planet.id)
        mfp <- ogameActionDriver.readFleetPage(planet.id)
        time <- buildNextThingFromWishList(planet, sp, fp, tp, mfp.ships, wishesForPlanet)
      } yield time
    } else {
      BuilderResult.idle().pure[OgameAction]
    }
  }

  private def buildNextThingFromWishList(
      planet: PlayerPlanet,
      suppliesPageData: SuppliesPageData,
      facilityPageData: FacilityPageData,
      technologyPageData: TechnologyPageData,
      fleet: Map[ShipType, Int],
      wishesForPlanet: List[Wish]
  ): OgameAction[BuilderResult] = {
    wishesForPlanet
      .collectFirst {
        case w: Wish.BuildSupply if suppliesPageData.getLevel(w.suppliesBuilding).value < w.level.value =>
          buildSupplyBuildingOrNothing(w.suppliesBuilding, suppliesPageData, planet)
        case w: Wish.BuildFacility if facilityPageData.getLevel(w.facilityBuilding).value < w.level.value =>
          buildFacilityBuildingOrNothing(w.facilityBuilding, facilityPageData, suppliesPageData, planet)
        case w: Wish.SmartSupplyBuilder if isSmartBuilderApplicable(planet, suppliesPageData, w) =>
          smartBuilder(planet, suppliesPageData, w)
        case w: Wish.BuildShip if fleet(w.shipType) <= w.amount.value =>
          buildShips(planet, w, suppliesPageData)
        case w: Wish.Research if technologyPageData.getLevel(w.technology).value < w.level.value =>
          startResearch(planet, w.technology, technologyPageData)
      }
      .sequence
      .map(_.getOrElse(BuilderResult.Idle))
  }

  private def startResearch(planet: PlayerPlanet, technology: Technology, technologyPageData: TechnologyPageData) = {
    technologyPageData.currentResearchProgress match {
      case Some(value) =>
        Logger[OgameAction]
          .info(s"Wanted to build $technology but there were some other research ongoing")
          .map(_ => BuilderResult.building(value.finishTimestamp))
      case None =>
        val level = nextLevel(technologyPageData, technology)
        val requiredResources = TechnologyCosts.technologyCost(technology, level)
        if (technologyPageData.currentResources.gtEqTo(requiredResources)) {
          ogameActionDriver.startResearch(planet.id, technology) >> ogameActionDriver
            .readTechnologyPage(planet.id)
            .map(t => BuilderResult.building(t.currentResearchProgress.get.finishTimestamp))
        } else {
          val secondsToWait = calculateWaitingTime(
            requiredResources = requiredResources,
            production = technologyPageData.currentProduction,
            resources = technologyPageData.currentResources
          )
          Logger[OgameAction]
            .info(
              s"Wanted to build $technology $level but there were not enough resources on ${planet.coordinates} " +
                s"- ${technologyPageData.currentResources}/$requiredResources"
            )
            .map(_ => BuilderResult.building(clock.now().plusSeconds(secondsToWait)))
        }
    }
  }

  private def buildShips(planet: PlayerPlanet, w: Wish.BuildShip, suppliesPageData: SuppliesPageData) = { //TODO check not building and shipyard is not upgrading
    val requiredResourcesSingleShip = ShipCosts.shipCost(w.shipType)
    if (suppliesPageData.currentResources.gtEqTo(requiredResourcesSingleShip)) {
      val canBuildAmount = suppliesPageData.currentResources.div(requiredResourcesSingleShip).min
      val buildAmount = Math.min(canBuildAmount, w.amount.value).toInt
      ogameActionDriver
        .buildShipAndGetTime(planet.id, w.shipType, buildAmount)
        .map(s => BuilderResult.building(s.finishTimestamp))
    } else {
      val secondsToWait =
        calculateWaitingTime(requiredResourcesSingleShip, suppliesPageData.currentProduction, suppliesPageData.currentResources)
      Logger[OgameAction]
        .info(
          s"Wanted to build $w but there were not enough resources on ${planet.coordinates} " +
            s"- ${suppliesPageData.currentResources}/$requiredResourcesSingleShip"
        )
        .map(_ => BuilderResult.building(clock.now().plusSeconds(secondsToWait)))
    }
  }

  private def buildSupplyBuildingOrNothing(suppliesBuilding: SuppliesBuilding, suppliesPageData: SuppliesPageData, planet: PlayerPlanet) = {
    suppliesPageData.currentBuildingProgress match {
      case Some(value) =>
        Logger[OgameAction]
          .info(s"Wanted to build $suppliesBuilding but something was being built")
          .map(_ => BuilderResult.building(value.finishTimestamp))
      case None =>
        val level = nextLevel(suppliesPageData, suppliesBuilding)
        val requiredResources = SuppliesBuildingCosts.buildingCost(suppliesBuilding, level)
        if (suppliesPageData.currentResources.gtEqTo(requiredResources)) {
          ogameActionDriver
            .buildSupplyAndGetTime(planet.id, suppliesBuilding)
            .map(s => BuilderResult.building(s.finishTimestamp))
        } else {
          val secondsToWait = calculateWaitingTime(requiredResources, suppliesPageData.currentProduction, suppliesPageData.currentResources)
          Logger[OgameAction]
            .info(
              s"Wanted to build $suppliesBuilding $level but there were not enough resources on ${planet.coordinates} " +
                s"- ${suppliesPageData.currentResources}/$requiredResources"
            )
            .map { _ =>
              BuilderResult.building(clock.now().plusSeconds(secondsToWait))
            }
        }
    }
  }

  private def calculateWaitingTime(requiredResources: Resources, production: Resources, resources: Resources) = {
    val missingResources = requiredResources.difference(resources)
    val hoursToWait = missingResources.div(production).max
    (hoursToWait * 3600).toInt
  }

  private def buildFacilityBuildingOrNothing(
      facilityBuilding: FacilityBuilding,
      facilityPageData: FacilityPageData,
      suppliesPageData: SuppliesPageData,
      planet: PlayerPlanet
  ) = {
    suppliesPageData.currentShipyardProgress match {
      case Some(value) =>
        Logger[OgameAction]
          .info(s"Wanted to build $facilityBuilding but there were some ships building")
          .as(BuilderResult.building(value.finishTimestamp))
      case None =>
        val level = nextLevel(facilityPageData, facilityBuilding)
        val requiredResources = FacilityBuildingCosts.buildingCost(facilityBuilding, level)
        if (facilityPageData.currentResources.gtEqTo(requiredResources)) {
          ogameActionDriver
            .buildFacilityAndGetTime(planet.id, facilityBuilding)
            .map(f => BuilderResult.building(f.finishTimestamp))
        } else {
          val secondsToWait = calculateWaitingTime(requiredResources, suppliesPageData.currentProduction, suppliesPageData.currentResources)
          Logger[OgameAction]
            .info(
              s"Wanted to build $facilityBuilding $level but there were not enough resources on ${planet.coordinates}" +
                s"- ${suppliesPageData.currentResources}/$requiredResources"
            )
            .as(BuilderResult.building(clock.now().plusSeconds(secondsToWait)))
        }
    }
  }

  private def nextLevel(suppliesPage: SuppliesPageData, building: SuppliesBuilding) = {
    refineVUnsafe[Positive, Int](suppliesPage.suppliesLevels.values(building).value + 1)
  }

  private def nextLevel(technologyPageData: TechnologyPageData, technology: Technology) = {
    refineVUnsafe[Positive, Int](technologyPageData.technologyLevels.values(technology).value + 1)
  }

  private def nextLevel(facilityPageData: FacilityPageData, building: FacilityBuilding) = {
    refineVUnsafe[Positive, Int](facilityPageData.facilityLevels.values(building).value + 1)
  }

  private def smartBuilder(planet: PlayerPlanet, suppliesPageData: SuppliesPageData, w: Wish.SmartSupplyBuilder) = {
    suppliesPageData.currentBuildingProgress match {
      case Some(value) =>
        Logger[OgameAction]
          .info(s"Wanted to run smart builder but sth was being built")
          .map(_ => BuilderResult.building(value.finishTimestamp))
      case None =>
        if (suppliesPageData.currentResources.energy < 0) {
          suppliesPageData.currentShipyardProgress match {
            case Some(value) => BuilderResult.building(value.finishTimestamp).pure[OgameAction]
            case None =>
              ogameActionDriver.buildSolarSatellites(planet.id, 1) >> ogameActionDriver
                .readSuppliesPage(planet.id)
                .map(f => BuilderResult.building(f.currentShipyardProgress.get.finishTimestamp))
          }
        } else { //TODO can we get rid of hardcoded ratio?
          val shouldBuildDeuter = suppliesPageData.getLevel(SuppliesBuilding.MetalMine).value -
            suppliesPageData.getLevel(SuppliesBuilding.DeuteriumSynthesizer).value > 4 &&
            suppliesPageData.getLevel(SuppliesBuilding.DeuteriumSynthesizer).value < w.deuterLevel.value
          val shouldBuildCrystal = suppliesPageData.getLevel(SuppliesBuilding.MetalMine).value -
            suppliesPageData.getLevel(SuppliesBuilding.CrystalMine).value > 2 &&
            suppliesPageData.getLevel(SuppliesBuilding.CrystalMine).value < w.crystalLevel.value
          if (shouldBuildCrystal) {
            buildBuildingOrStorage(planet, suppliesPageData, SuppliesBuilding.CrystalMine)
          } else if (shouldBuildDeuter) {
            buildBuildingOrStorage(planet, suppliesPageData, SuppliesBuilding.DeuteriumSynthesizer)
          } else if (suppliesPageData.getLevel(SuppliesBuilding.MetalMine).value < w.metalLevel.value) {
            buildBuildingOrStorage(planet, suppliesPageData, SuppliesBuilding.MetalMine)
          } else {
            BuilderResult.idle().pure[OgameAction]
          }
        }
    }
  }

  private def buildBuildingOrStorage(planet: PlayerPlanet, suppliesPageData: SuppliesPageData, building: SuppliesBuilding) = {
    val level = nextLevel(suppliesPageData, building)
    val requiredResources = SuppliesBuildingCosts.buildingCost(building, level)
    if (suppliesPageData.currentCapacity.gtEqTo(requiredResources)) {
      buildSupplyBuildingOrNothing(building, suppliesPageData, planet)
    } else {
      buildStorage(suppliesPageData, requiredResources, planet)
    }
  }

  private def buildStorage(
      suppliesPage: SuppliesPageData,
      requiredResources: Resources,
      planet: PlayerPlanet
  ) = {
    requiredResources.difference(suppliesPage.currentCapacity) match {
      case Resources(m, _, _, _) if m > 0 =>
        buildSupplyBuildingOrNothing(SuppliesBuilding.MetalStorage, suppliesPage, planet)
      case Resources(_, c, _, _) if c > 0 =>
        buildSupplyBuildingOrNothing(SuppliesBuilding.CrystalStorage, suppliesPage, planet)
      case Resources(_, _, d, _) if d > 0 =>
        buildSupplyBuildingOrNothing(SuppliesBuilding.DeuteriumStorage, suppliesPage, planet)
    }
  }

  private def isSmartBuilderApplicable(planet: PlayerPlanet, suppliesPageData: SuppliesPageData, w: Wish.SmartSupplyBuilder) = {
    val correctPlanet = w.planetId == planet.id
    val metalMineUnderLevel = w.metalLevel.value > suppliesPageData.getLevel(SuppliesBuilding.MetalMine).value
    val crystalMineUnderLevel = w.crystalLevel.value > suppliesPageData.getLevel(SuppliesBuilding.CrystalMine).value
    val deuterMineUnderLevel = w.deuterLevel.value > suppliesPageData.getLevel(SuppliesBuilding.DeuteriumSynthesizer).value
    correctPlanet && (metalMineUnderLevel || crystalMineUnderLevel || deuterMineUnderLevel)
  }
}

sealed trait BuilderResult extends Product with Serializable
object BuilderResult {
  case class Building(finishTime: ZonedDateTime) extends BuilderResult
  case object Idle extends BuilderResult

  def building(finishTime: ZonedDateTime): BuilderResult = Building(finishTime)
  def idle(): BuilderResult = Idle
}
