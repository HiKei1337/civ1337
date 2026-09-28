package dev.autociv.simulation.ai;

import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.CivilizationTrait;
import dev.autociv.simulation.model.BuildingPlan;
import dev.autociv.simulation.model.BuildingType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Chooses a city project from housing, resource, security, and logistics needs. */
public final class CityPlanner {

    public static final int HOME_BUILD_DAYS = 14;
    public static final int MAX_HOME_SITES = 128;
    public static final double WOOD_COST = 48.0;
    public static final double STONE_COST = 24.0;
    private static final double WOOD_RESERVE = 120.0;
    private static final double STONE_RESERVE = 80.0;

    public enum Update { WAITING, IN_PROGRESS, HOME_COMPLETED, MATERIALS_SHORT, SITES_FULL,
        BUILDING_PLAN_STARTED, BUILDING_COMPLETED }

    public Update advanceDay(Settlement settlement) {
        return advanceDay(settlement, List.of());
    }

    public Update advanceDay(Settlement settlement, List<Citizen> residents) {
        return advanceDay(settlement, residents, null);
    }

    public Update advanceDay(Settlement settlement, List<Citizen> residents, Civilization civilization) {
        BuildingPlan activePlan = settlement.activeBuildingPlan();
        if (activePlan != null) {
            if (activePlan.advanceDay()) {
                settlement.completeActiveBuildingPlan();
                return Update.BUILDING_COMPLETED;
            }
            return Update.IN_PROGRESS;
        }

        if (!settlement.buildingQueue().isEmpty()) {
            BuildingType queued = settlement.dequeueBuilding();
            if (queued != null) {
                settlement.setActiveBuildingPlan(BuildingPlan.start(queued));
                return Update.BUILDING_PLAN_STARTED;
            }
        }

        if (settlement.strategy() == dev.autociv.simulation.model.DevelopmentStrategy.RESERVES
                && settlement.stockpile().get(ResourceType.FOOD) < Math.max(300, settlement.population() * 12.0)) {
            return Update.WAITING;
        }

        int constructionDays = settlement.homeConstructionDays();
        if (constructionDays > 0) {
            constructionDays++;
            if (constructionDays >= HOME_BUILD_DAYS) {
                settlement.completeHomeConstruction();
                return Update.HOME_COMPLETED;
            }
            settlement.setHomeConstructionDays(constructionDays);
            return Update.IN_PROGRESS;
        }
        boolean canBuildHome = settlement.completedHomes() < MAX_HOME_SITES;
        if (canBuildHome) {
            int populationThreshold = Math.max(1, settlement.housingCapacity());
            if (settlement.population() >= populationThreshold) {
                double woodAvailableForBuilding = settlement.stockpile().get(ResourceType.WOOD)
                        - Math.max(WOOD_RESERVE, settlement.population() * 3.0);
                double stoneAvailableForBuilding = settlement.stockpile().get(ResourceType.STONE)
                        - Math.max(STONE_RESERVE, settlement.population() * 1.5);
                if (woodAvailableForBuilding >= WOOD_COST && stoneAvailableForBuilding >= STONE_COST
                        && settlement.stockpile().tryRemove(Map.of(
                        ResourceType.WOOD, WOOD_COST, ResourceType.STONE, STONE_COST))) {
                    settlement.stockpile().recordDelta(ResourceType.WOOD, -WOOD_COST);
                    settlement.stockpile().recordDelta(ResourceType.STONE, -STONE_COST);
                    settlement.setHomeConstructionDays(1);
                    return Update.IN_PROGRESS;
                }
                return Update.MATERIALS_SHORT;
            }
        }

        BuildingType next = chooseBuilding(settlement, residents, civilization);
        if (next == null) {
            return !canBuildHome ? Update.SITES_FULL : Update.WAITING;
        }
        Map<ResourceType, Double> cost = Map.of(ResourceType.WOOD, (double) next.woodCost(),
                ResourceType.STONE, (double) next.stoneCost());
        if (!settlement.stockpile().tryRemove(cost)) return Update.MATERIALS_SHORT;
        settlement.stockpile().recordDelta(ResourceType.WOOD, -next.woodCost());
        settlement.stockpile().recordDelta(ResourceType.STONE, -next.stoneCost());
        settlement.setActiveBuildingPlan(BuildingPlan.start(next));
        return Update.BUILDING_PLAN_STARTED;
    }

    private BuildingType chooseBuilding(Settlement city, List<Citizen> residents, Civilization civilization) {
        List<BuildingNeed> needs = new ArrayList<>();
        double foodTarget = Math.max(200, city.population() * 4.0);
        if (city.buildingCount(BuildingType.FARM) < BuildingType.FARM.cityLimit()
                && (city.stockpile().get(ResourceType.FOOD) < foodTarget
                || city.stockpile().deltaPerDay(ResourceType.FOOD) < 0)) {
            needs.add(new BuildingNeed(BuildingType.FARM, 100 + shortageRatio(city, ResourceType.FOOD, foodTarget)));
        }
        double mineralTarget = Math.max(100, city.population() * 2.0);
        if (city.buildingCount(BuildingType.MINE) < BuildingType.MINE.cityLimit()
                && (city.stockpile().get(ResourceType.IRON) < mineralTarget
                || city.stockpile().get(ResourceType.STONE) < mineralTarget)) {
            needs.add(new BuildingNeed(BuildingType.MINE, 80 + shortageRatio(city, ResourceType.IRON, mineralTarget)));
        }
        if (city.buildingCount(BuildingType.BARRACKS) == 0 && city.security() < 0.35) {
            needs.add(new BuildingNeed(BuildingType.BARRACKS, 120 + (0.35 - city.security()) * 100));
        }
        if (city.buildingCount(BuildingType.FORTIFICATION) < BuildingType.FORTIFICATION.cityLimit()
                && city.security() < 0.50 && city.defense() < 0.5) {
            needs.add(new BuildingNeed(BuildingType.FORTIFICATION,
                    105 + (0.50 - city.security()) * 80));
        }
        double averageHealth = residents.stream().mapToDouble(Citizen::health).average().orElse(1.0);
        if (city.population() >= 20 && city.buildingCount(BuildingType.CLINIC) == 0 && averageHealth < 0.85) {
            needs.add(new BuildingNeed(BuildingType.CLINIC, 95 + (0.85 - averageHealth) * 80));
        }
        double averageEducation = residents.stream().mapToDouble(Citizen::education).average().orElse(0.0);
        if (city.population() >= 20 && city.buildingCount(BuildingType.SCHOOL) == 0
                && averageEducation < 0.35) {
            needs.add(new BuildingNeed(BuildingType.SCHOOL, 45 + (0.35 - averageEducation) * 40));
        }
        if (city.buildingCount(BuildingType.WAREHOUSE) < BuildingType.WAREHOUSE.cityLimit()
                && ResourceType.all().values().stream().anyMatch(resource -> city.stockpile().get(resource)
                >= city.stockpile().capacity(resource) * 0.8)) {
            needs.add(new BuildingNeed(BuildingType.WAREHOUSE, 60));
        }
        if (city.buildingCount(BuildingType.MARKET) == 0 && city.population() >= 30) {
            needs.add(new BuildingNeed(BuildingType.MARKET, 30));
        }
        if (city.buildingCount(BuildingType.BANK) == 0 && city.population() >= 12
                && city.citizenIds().size() >= 12) {
            needs.add(new BuildingNeed(BuildingType.BANK, 72));
        }
        int desiredRoadSegments = Math.min(BuildingType.ROAD.cityLimit(), city.population() / 30);
        if (city.buildingCount(BuildingType.ROAD) < desiredRoadSegments) {
            needs.add(new BuildingNeed(BuildingType.ROAD, 20));
        }
        if (city.population() >= 45 && city.buildingCount(BuildingType.MARKET) > 0
                && city.buildingCount(BuildingType.ROAD) > 0
                && city.buildingCount(BuildingType.RAILWAY_STATION) == 0) {
            needs.add(new BuildingNeed(BuildingType.RAILWAY_STATION,
                    24 + (civilization != null && civilization.hasTrait(CivilizationTrait.TRADING) ? 38 : 0)
                            + (city.role() == dev.autociv.simulation.model.SettlementRole.TRADE ? 24 : 0)));
        }
        return needs.stream().max(Comparator.comparingDouble(need -> need.priority()
                        + priorityBonus(city, need.type(), civilization)))
                .map(BuildingNeed::type).orElse(null);
    }

    private double priorityBonus(Settlement city, BuildingType type, Civilization civilization) {
        double roleBonus = switch (city.role()) {
            case AGRICULTURE -> type == BuildingType.FARM ? 55 : 0;
            case MINING -> type == BuildingType.MINE ? 55 : 0;
            case TRADE -> type == BuildingType.MARKET || type == BuildingType.BANK ? 45 : 0;
            case FRONTIER -> type == BuildingType.BARRACKS || type == BuildingType.FORTIFICATION ? 75 : 0;
            case FORESTRY -> type == BuildingType.WAREHOUSE ? 25 : 0;
            case CAPITAL, VILLAGE -> 0;
        };
        double traitBonus = 0;
        if (civilization != null) {
            if (civilization.hasTrait(CivilizationTrait.AGRICULTURAL) && type == BuildingType.FARM) traitBonus += 40;
            if (civilization.hasTrait(CivilizationTrait.INDUSTRIAL)
                    && (type == BuildingType.MINE || type == BuildingType.WAREHOUSE)) traitBonus += 35;
            if (civilization.hasTrait(CivilizationTrait.TRADING)
                    && (type == BuildingType.MARKET || type == BuildingType.BANK)) traitBonus += 40;
            if (civilization.hasTrait(CivilizationTrait.DEFENSIVE)
                    && (type == BuildingType.FORTIFICATION || type == BuildingType.BARRACKS)) traitBonus += 40;
            if (civilization.hasTrait(CivilizationTrait.SCIENTIFIC) && type == BuildingType.SCHOOL) traitBonus += 35;
            if (civilization.hasTrait(CivilizationTrait.EXPANSIONIST) && type == BuildingType.ROAD) traitBonus += 30;
            if (civilization.hasTrait(CivilizationTrait.TRADING)
                    && type == BuildingType.RAILWAY_STATION) traitBonus += 35;
        }
        return roleBonus + traitBonus + switch (city.priority()) {
            case FOOD -> type == BuildingType.FARM ? 45 : 0;
            case CONSTRUCTION -> type == BuildingType.WAREHOUSE || type == BuildingType.ROAD ? 30 : 0;
            case EXTRACTION -> type == BuildingType.MINE ? 45 : 0;
            case TRADE -> type == BuildingType.MARKET || type == BuildingType.BANK ? 40 : 0;
            case DEFENSE -> type == BuildingType.FORTIFICATION || type == BuildingType.BARRACKS ? 45 : 0;
        } + (city.strategy() == dev.autociv.simulation.model.DevelopmentStrategy.FORTIFY
                && (type == BuildingType.FORTIFICATION || type == BuildingType.BARRACKS) ? 60 : 0);
    }

    private double shortageRatio(Settlement city, ResourceType resource, double target) {
        return Math.clamp((target - city.stockpile().get(resource)) / target, 0, 1) * 20;
    }

    private record BuildingNeed(BuildingType type, double priority) { }
}
