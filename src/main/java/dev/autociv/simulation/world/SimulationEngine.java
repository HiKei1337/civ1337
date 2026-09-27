package dev.autociv.simulation.world;

import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.scenario.CivilizationScenario;
import dev.autociv.simulation.economy.PriceSystem;
import dev.autociv.simulation.economy.TradeEngine;
import dev.autociv.simulation.ai.ProfessionAllocator;
import dev.autociv.simulation.ai.CityPlanner;
import dev.autociv.simulation.ai.ProfessionProduction;
import dev.autociv.simulation.ai.DiplomacyEngine;
import dev.autociv.simulation.ai.TerritoryEngine;
import dev.autociv.simulation.ai.SettlementExpansionEngine;
import dev.autociv.simulation.ai.ConflictEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Applies one abstract day of production, consumption and population change. */
public final class SimulationEngine {

    private static final double DAYS_PER_YEAR = 360.0;
    private static final double FOOD_PER_CITIZEN_PER_DAY = 1.0;
    /** Net civic revenue per resident and simulation day; funds imports and public works. */
    private static final double CIVIC_REVENUE_PER_CITIZEN_PER_DAY = 0.10;
    private static final double DAILY_POPULATION_GROWTH = 0.0012;
    private final TradeEngine tradeEngine = new TradeEngine(new PriceSystem());
    private final ProfessionAllocator professionAllocator = new ProfessionAllocator();
    private final CityPlanner cityPlanner = new CityPlanner();
    private final ProfessionProduction professionProduction = new ProfessionProduction();
    private final DiplomacyEngine diplomacyEngine = new DiplomacyEngine();
    private final TerritoryEngine territoryEngine = new TerritoryEngine();
    private final SettlementExpansionEngine settlementExpansionEngine = new SettlementExpansionEngine();
    private final ConflictEngine conflictEngine = new ConflictEngine();
    private final WorldEventEngine worldEventEngine = new WorldEventEngine();

    public void advanceDay(WorldSimulation simulation) {
        simulation.setTimeDays(simulation.timeDays() + 1.0);
        simulation.incrementTick();
        territoryEngine.update(simulation);

        Map<UUID, List<Citizen>> citizensBySettlement = new HashMap<>();
        for (Citizen citizen : simulation.citizens()) {
            citizensBySettlement.computeIfAbsent(citizen.homeSettlementId(), ignored -> new ArrayList<>())
                    .add(citizen);
        }

        for (Settlement settlement : simulation.settlements()) {
            settlement.stockpile().clearDeltas();
            List<Citizen> residents = citizensBySettlement.getOrDefault(settlement.id(), List.of());
            var civilization = simulation.civilization(settlement.civilizationId()).orElse(null);
            if (civilization != null) {
                civilization.addToTreasury(residents.size() * CIVIC_REVENUE_PER_CITIZEN_PER_DAY);
            }
            professionAllocator.rebalance(settlement, residents);
            double producedFood = professionProduction.produce(settlement, residents, civilization,
                    simulation.regionsAssignedToSettlement(settlement.id()));
            payResidentWages(residents, civilization, settlement.buildingCount(
                    dev.autociv.simulation.model.BuildingType.BANK) > 0);
            double requiredFood = residents.size() * FOOD_PER_CITIZEN_PER_DAY;
            double consumedFood = settlement.stockpile().remove(ResourceType.FOOD, requiredFood);
            settlement.stockpile().recordDelta(ResourceType.FOOD, producedFood - consumedFood);

            double foodFulfillment = requiredFood == 0 ? 1.0 : consumedFood / requiredFood;
            updateResidents(simulation, settlement, residents, foodFulfillment);
            growPopulation(simulation, settlement, residents, foodFulfillment);
            cityPlanner.advanceDay(settlement, residents, civilization);
        }
        worldEventEngine.advanceDay(simulation);
        applyHistoricalMilestones(simulation);
        tradeEngine.clearMarkets(simulation);
        diplomacyEngine.advanceDay(simulation);
        conflictEngine.advanceDay(simulation);
        settlementExpansionEngine.update(simulation);
    }

    /** Resident income is a real daily payroll backed by the civilization treasury. */
    private void payResidentWages(List<Citizen> residents,
                                  dev.autociv.simulation.model.Civilization civilization, boolean hasBank) {
        if (civilization == null) {
            residents.forEach(citizen -> citizen.setIncome(0));
            return;
        }
        Map<Citizen, Double> wages = new java.util.LinkedHashMap<>();
        for (Citizen citizen : residents) {
            double wage = citizen.isAdult() ? switch (citizen.profession()) {
                case FARMER, LUMBERJACK, MINER, FISHERMAN, HUNTER, SHEPHERD -> 0.05;
                case BUILDER, ENGINEER, BLACKSMITH, GUARD, SOLDIER -> 0.06;
                case MERCHANT -> 0.07;
                case DOCTOR, RESEARCHER -> 0.055;
                case UNASSIGNED -> 0.0;
            } : 0.0;
            if (hasBank) wage *= 1.10;
            wages.put(citizen, wage);
        }
        double requested = wages.values().stream().mapToDouble(Double::doubleValue).sum();
        double paid = Math.min(requested, civilization.treasury());
        if (paid > 0) civilization.withdrawFromTreasury(paid);
        double scale = requested <= 0 ? 0 : paid / requested;
        wages.forEach((citizen, wage) -> citizen.setIncome(wage * scale));
    }

    private void applyHistoricalMilestones(WorldSimulation simulation) {
        for (var civilization : simulation.civilizations()) {
            CivilizationScenario.byId(civilization.scenarioId()).ifPresent(scenario -> {
                for (CivilizationScenario.HistoricalMilestone milestone : scenario.milestones()) {
                    int eventYear = scenario.startYear() + milestone.yearsAfterStart();
                    if (simulation.year() < eventYear) {
                        continue;
                    }
                    String historyEntry = "year " + eventYear + ": " + milestone.event();
                    if (civilization.history().contains(historyEntry)) {
                        continue;
                    }
                    civilization.recordEvent(historyEntry);
                    civilization.addToTreasury(milestone.treasuryDelta());
                    civilization.addToCulture(milestone.cultureDelta());
                    civilization.setMilitaryStrength(civilization.militaryStrength()
                            + milestone.militaryDelta());
                    var settlements = simulation.settlementsOf(civilization.id());
                    if (!settlements.isEmpty()) {
                        milestone.resourceDeltas().forEach((resource, delta) -> {
                            double perSettlement = delta / settlements.size();
                            settlements.forEach(settlement -> settlement.stockpile().add(resource, perSettlement));
                        });
                    }
                }
            });
        }
    }

    private void updateResidents(WorldSimulation simulation, Settlement settlement,
                                 List<Citizen> residents, double foodFulfillment) {
        boolean overcrowded = residents.size() > settlement.housingCapacity();
        double housingComfort = settlement.housingCapacity() <= 0 ? 0.0
                : Math.clamp((double) settlement.housingCapacity() / Math.max(1, residents.size()), 0.0, 1.0);
        boolean hasCompany = residents.size() > 1;
        java.util.Set<UUID> residentIds = residents.stream().map(Citizen::id)
                .collect(java.util.stream.Collectors.toSet());
        double totalMood = 0.0;
        for (Citizen citizen : residents) {
            citizen.advanceAge(1.0 / DAYS_PER_YEAR);
            double healthChange = foodFulfillment < 1.0
                    ? -0.08 * (1.0 - foodFulfillment)
                    : 0.005;
            healthChange += settlement.buildingCount(dev.autociv.simulation.model.BuildingType.CLINIC) * 0.003;
            if (overcrowded) {
                healthChange -= 0.02;
            }
            // Residents get a small abstract overnight recovery when housed; physical
            // residents receive an additional boost from actually reaching a bed.
            double sleep = Math.clamp(citizen.need(Citizen.Need.SLEEP) - 0.15
                    + (overcrowded ? 0.02 : 0.22), 0.0, 1.0);
            if (sleep < 0.20) healthChange -= 0.015;
            if (settlement.security() < 0.2) healthChange -= 0.01;
            citizen.setHealth(citizen.health() + healthChange);
            citizen.setNeed(Citizen.Need.FOOD, foodFulfillment);
            citizen.setNeed(Citizen.Need.SAFETY, settlement.security());
            citizen.setNeed(Citizen.Need.SLEEP, sleep);
            boolean familyNearby = citizen.family().stream().anyMatch(residentIds::contains);
            citizen.setNeed(Citizen.Need.SOCIAL, hasCompany ? (familyNearby ? 0.9 : 0.7) : 0.35);
            citizen.setNeed(Citizen.Need.COMFORT, overcrowded ? housingComfort * 0.5 : 0.9);
            double learning = 0.0002
                    + settlement.buildingCount(dev.autociv.simulation.model.BuildingType.SCHOOL) * 0.004;
            citizen.setEducation(citizen.education() + learning);
            double needFulfillment = citizen.needs().values().stream().mapToDouble(Double::doubleValue).average()
                    .orElse(1.0);
            citizen.setMood((citizen.mood() + settlement.happiness() + needFulfillment) / 3.0);
            totalMood += citizen.mood();
            if (citizen.isDead()) {
                simulation.removeCitizen(citizen);
            }
        }
        if (!residents.isEmpty()) {
            double averageMood = totalMood / residents.size();
            settlement.setHappiness(settlement.happiness() * 0.8 + averageMood * 0.2);
        }
    }

    private void growPopulation(WorldSimulation simulation, Settlement settlement,
                                List<Citizen> residents, double foodFulfillment) {
        int population = settlement.population();
        double averageHealth = residents.stream().filter(citizen -> !citizen.isDead())
                .mapToDouble(Citizen::health).average().orElse(0.0);
        double averageNeeds = residents.stream().filter(citizen -> !citizen.isDead())
                .mapToDouble(citizen -> citizen.needs().values().stream().mapToDouble(Double::doubleValue)
                        .average().orElse(1.0)).average().orElse(0.0);
        if (population == 0 || population >= settlement.housingCapacity()
                || foodFulfillment < 1.0 || settlement.security() < 0.4
                || settlement.happiness() < 0.4 || averageHealth < 0.65 || averageNeeds < 0.55
                || settlement.stockpile().get(ResourceType.FOOD) < population) {
            return;
        }

        settlement.addPopulationGrowthProgress(population * DAILY_POPULATION_GROWTH * averageNeeds);
        while (settlement.populationGrowthProgress() >= 1.0
                && settlement.hasRoomForCitizen()) {
            String name = "Resident " + UUID.randomUUID().toString().substring(0, 8);
            simulation.addCitizen(settlement, name, 0, Citizen.Profession.UNASSIGNED);
            settlement.addPopulationGrowthProgress(-1.0);
        }
    }
}
