package dev.autociv.simulation;

import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.ai.ProfessionAllocator;
import dev.autociv.simulation.economy.Market;
import dev.autociv.simulation.economy.PriceSystem;
import dev.autociv.simulation.world.SimulationEngine;
import dev.autociv.simulation.world.SimulationClock;
import dev.autociv.simulation.world.SimulationSpeed;
import dev.autociv.simulation.world.VillagePopulationSynchronizer;
import dev.autociv.simulation.world.WorldSimulation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stage 1 tests: creation of civilizations, cities, citizens and resource changes. */
class WorldSimulationTest {

    @Test
    void createsCivilization() {
        WorldSimulation sim = new WorldSimulation();
        Civilization civ = sim.createCivilization("Oakland", 0x2E8B57);

        assertEquals(1, sim.civilizations().size());
        assertEquals("Oakland", civ.name());
        assertEquals(0x2E8B57, civ.color());
        assertNotNull(civ.id());
        assertEquals(Civilization.Relation.NEUTRAL, civ.relationWith(java.util.UUID.randomUUID()));
        assertFalse(civ.history().isEmpty(), "founding must be recorded in history");
    }

    @Test
    void rejectsBlankNames() {
        WorldSimulation sim = new WorldSimulation();
        assertThrows(IllegalArgumentException.class, () -> sim.createCivilization("  ", 0));
    }

    @Test
    void findsCivilizationByNameAndUuidPrefix() {
        WorldSimulation sim = new WorldSimulation();
        Civilization civ = sim.createCivilization("Ironhill", 0xFF0000);

        assertTrue(sim.findCivilization("ironhill").isPresent(), "case-insensitive name lookup");
        assertTrue(sim.findCivilization(civ.id().toString().substring(0, 8)).isPresent(), "uuid prefix lookup");
        assertTrue(sim.findCivilization("Nope").isEmpty());
    }

    @Test
    void firstCityBecomesCapital() {
        WorldSimulation sim = new WorldSimulation();
        Civilization civ = sim.createCivilization("Oakland", 0x2E8B57);
        Settlement capital = sim.createSettlement(civ.id(), "Oaktown", 100, 64, -200);
        Settlement second = sim.createSettlement(civ.id(), "Laketown", 500, 64, 300);

        assertEquals(capital.id(), civ.capitalSettlementId());
        assertTrue(capital.isCapital());
        assertFalse(second.isCapital());
        assertEquals(2, civ.settlementIds().size());
    }

    @Test
    void creatingCityForUnknownCivFails() {
        WorldSimulation sim = new WorldSimulation();
        assertThrows(NullPointerException.class,
                () -> sim.createSettlement(java.util.UUID.randomUUID(), "Ghost", 0, 0, 0));
    }

    @Test
    void addsCitizenAndCountsPopulation() {
        WorldSimulation sim = new WorldSimulation();
        Civilization civ = sim.createCivilization("Oakland", 0x2E8B57);
        Settlement city = sim.createSettlement(civ.id(), "Oaktown", 0, 64, 0);

        Citizen c = sim.addCitizen(city, "Alla", 27, Citizen.Profession.FARMER);

        assertEquals(1, city.population());
        assertEquals(1, sim.totalPopulation());
        assertEquals(city.id(), c.homeSettlementId());
        assertTrue(c.isAdult());
        assertEquals(Citizen.Profession.FARMER, c.profession());
    }

    @Test
    void removesCitizenWhenCityDeleted() {
        WorldSimulation sim = new WorldSimulation();
        Civilization civ = sim.createCivilization("Oakland", 0x2E8B57);
        Settlement city = sim.createSettlement(civ.id(), "Oaktown", 0, 64, 0);
        sim.addCitizen(city, "Alla", 27, Citizen.Profession.FARMER);

        assertTrue(sim.removeSettlement(city.id()));
        assertEquals(0, sim.totalPopulation());
        assertTrue(civ.settlementIds().isEmpty());
    }

    @Test
    void resourceStockpileChangesClampAndReport() {
        WorldSimulation sim = new WorldSimulation();
        Civilization civ = sim.createCivilization("Oakland", 0x2E8B57);
        Settlement city = sim.createSettlement(civ.id(), "Oaktown", 0, 64, 0);

        city.stockpile().add(ResourceType.FOOD, 1000);
        assertEquals(1000, city.stockpile().get(ResourceType.FOOD), 0.001);

        double removed = city.stockpile().remove(ResourceType.FOOD, 350);
        assertEquals(350, removed, 0.001);
        assertEquals(650, city.stockpile().get(ResourceType.FOOD), 0.001);

        // cannot remove more than present
        double overRemoved = city.stockpile().remove(ResourceType.FOOD, 10_000);
        assertEquals(650, overRemoved, 0.001);
        assertEquals(0, city.stockpile().get(ResourceType.FOOD), 0.001);

        // capacity clamp
        city.stockpile().setCapacity(ResourceType.WOOD, 100);
        city.stockpile().add(ResourceType.WOOD, 500);
        assertEquals(100, city.stockpile().get(ResourceType.WOOD), 0.001);
    }

    @Test
    void atomicBundleRemoval() {
        WorldSimulation sim = new WorldSimulation();
        Civilization civ = sim.createCivilization("Oakland", 0x2E8B57);
        Settlement city = sim.createSettlement(civ.id(), "Oaktown", 0, 64, 0);
        city.stockpile().add(ResourceType.IRON, 10);

        assertFalse(city.stockpile().tryRemove(java.util.Map.of(
                ResourceType.IRON, 5.0, ResourceType.COAL, 5.0)), "missing coal must abort");
        assertEquals(10, city.stockpile().get(ResourceType.IRON), 0.001, "nothing should have been removed");

        city.stockpile().add(ResourceType.COAL, 7);
        assertTrue(city.stockpile().tryRemove(java.util.Map.of(
                ResourceType.IRON, 5.0, ResourceType.COAL, 5.0)));
        assertEquals(5, city.stockpile().get(ResourceType.IRON), 0.001);
    }

    @Test
    void customResourceIsLoadableById() {
        ResourceType horses = ResourceType.register("horses");
        assertEquals(horses, ResourceType.byIdOrRegister("horses"));
        assertEquals("horses", horses.id());
    }

    @Test
    void simulationDayProducesResourcesAndConsumesFood() {
        WorldSimulation simulation = new WorldSimulation();
        Civilization civilization = simulation.createCivilization("Oakland", 0x2E8B57);
        Settlement city = simulation.createSettlement(civilization.id(), "Oaktown", 0, 64, 0);
        simulation.addCitizen(city, "Farmer", 30, Citizen.Profession.FARMER);
        simulation.addCitizen(city, "Lumberjack", 30, Citizen.Profession.LUMBERJACK);
        simulation.addCitizen(city, "Miner", 30, Citizen.Profession.MINER);

        new SimulationEngine().advanceDay(simulation);

        assertEquals(1.0, city.stockpile().get(ResourceType.FOOD), 0.001);
        assertEquals(2.0, city.stockpile().get(ResourceType.WOOD), 0.001);
        assertEquals(2.0, city.stockpile().get(ResourceType.STONE), 0.001);
        assertEquals(1.0, city.stockpile().deltaPerDay(ResourceType.FOOD), 0.001);
        assertEquals(2.0, city.stockpile().deltaPerDay(ResourceType.WOOD), 0.001);
        assertEquals(1.0, simulation.timeDays(), 0.001);
        assertEquals(1, simulation.tickCount());
    }

    @Test
    void starvationReducesHealthAndFoodNeed() {
        WorldSimulation simulation = new WorldSimulation();
        Civilization civilization = simulation.createCivilization("Oakland", 0x2E8B57);
        Settlement city = simulation.createSettlement(civilization.id(), "Oaktown", 0, 64, 0);
        Citizen resident = simulation.addCitizen(city, "Resident", 30, Citizen.Profession.UNASSIGNED);

        new SimulationEngine().advanceDay(simulation);

        assertEquals(0.92, resident.health(), 0.001);
        assertEquals(0.0, resident.need(Citizen.Need.FOOD), 0.001);
    }

    @Test
    void healthyWellFedCityGrowsWithoutExceedingHousing() {
        WorldSimulation simulation = new WorldSimulation();
        Civilization civilization = simulation.createCivilization("Oakland", 0x2E8B57);
        Settlement city = simulation.createSettlement(civilization.id(), "Oaktown", 0, 64, 0);
        city.setHousingCapacity(51);
        for (int resident = 0; resident < 50; resident++) {
            simulation.addCitizen(city, "Farmer " + resident, 30, Citizen.Profession.FARMER);
        }

        SimulationEngine engine = new SimulationEngine();
        for (int day = 0; day < 110; day++) {
            engine.advanceDay(simulation);
        }

        assertEquals(51, city.population());
        assertEquals(51, city.housingCapacity());
    }

    @Test
    void unhealthyCityDoesNotGrow() {
        WorldSimulation simulation = new WorldSimulation();
        Civilization civilization = simulation.createCivilization("Oakland", 0x2E8B57);
        Settlement city = simulation.createSettlement(civilization.id(), "Oaktown", 0, 64, 0);
        city.setHousingCapacity(2);
        city.addPopulationGrowthProgress(0.9999);
        Citizen farmer = simulation.addCitizen(city, "Farmer", 30, Citizen.Profession.FARMER);
        farmer.setHealth(0.2);
        city.stockpile().add(ResourceType.FOOD, 10);

        new SimulationEngine().advanceDay(simulation);

        assertEquals(1, city.population());
    }

    @Test
    void simulationSpeedHasDistinctScheduledIntervals() {
        assertEquals(24_000, SimulationSpeed.REALISTIC.intervalTicks());
        assertEquals(2_400, SimulationSpeed.FAST.intervalTicks());
        assertEquals(200, SimulationSpeed.DEBUG.intervalTicks());
        assertEquals(SimulationSpeed.DEBUG, SimulationSpeed.byId("debug").orElseThrow());
        assertTrue(SimulationSpeed.byId("impossible").isEmpty());
    }

    @Test
    void simulationClockRunsOnlyAtConfiguredInterval() {
        SimulationClock clock = new SimulationClock();
        for (int tick = 0; tick < SimulationSpeed.DEBUG.intervalTicks() - 1; tick++) {
            assertFalse(clock.advance(SimulationSpeed.DEBUG));
        }
        assertTrue(clock.advance(SimulationSpeed.DEBUG));
        assertFalse(clock.advance(SimulationSpeed.DEBUG));

        clock.reset();
        for (int tick = 0; tick < SimulationSpeed.FAST.intervalTicks() - 1; tick++) {
            assertFalse(clock.advance(SimulationSpeed.FAST));
        }
        assertTrue(clock.advance(SimulationSpeed.FAST));
    }

    @Test
    void physicalVillagerProjectionIsCappedPerCity() {
        assertEquals(0, VillagePopulationSynchronizer.desiredVillagerCount(0));
        assertEquals(12, VillagePopulationSynchronizer.desiredVillagerCount(12));
        assertEquals(16, VillagePopulationSynchronizer.desiredVillagerCount(250));
        assertEquals(2, VillagePopulationSynchronizer.MAX_GUARD_GOLEMS_PER_CITY);
    }

    @Test
    void scarceStockAndNegativeProductionTrendRaiseMarketPrice() {
        WorldSimulation simulation = new WorldSimulation();
        Civilization civilization = simulation.createCivilization("Marketland", 0x345678);
        Settlement city = simulation.createSettlement(civilization.id(), "Market", 0, 64, 0);
        PriceSystem priceSystem = new PriceSystem();
        Market market = new Market(city, priceSystem);
        double priceWithNoStock = market.price(ResourceType.FOOD);

        city.stockpile().add(ResourceType.FOOD, 500);
        double priceWithSurplus = market.price(ResourceType.FOOD);
        city.stockpile().recordDelta(ResourceType.FOOD, -10);
        double priceWithWorseningTrend = market.price(ResourceType.FOOD);

        assertTrue(priceWithNoStock > priceWithSurplus);
        assertTrue(priceWithWorseningTrend > priceWithSurplus);
    }

    @Test
    void cityAllocatorAssignsFarmersMinersLumberjacksAndGuardsForDetectedNeeds() {
        WorldSimulation simulation = new WorldSimulation();
        Civilization civilization = simulation.createCivilization("Needs", 0x345678);
        Settlement city = simulation.createSettlement(civilization.id(), "Worktown", 0, 64, 0);
        List<Citizen> residents = new java.util.ArrayList<>();
        for (int index = 0; index < 20; index++) {
            residents.add(simulation.addCitizen(city, "Worker " + index, 30,
                    Citizen.Profession.UNASSIGNED));
        }
        city.setSecurity(0.2);
        ProfessionAllocator allocator = new ProfessionAllocator();

        ProfessionAllocator.AllocationResult result = allocator.rebalance(city, residents);

        assertTrue(result.farmers() >= 6);
        assertTrue(result.lumberjacks() >= 1);
        assertTrue(result.miners() >= 1);
        assertTrue(result.guards() >= 1);
        assertTrue(result.changed() >= result.farmers() + result.lumberjacks()
                + result.miners() + result.guards());
    }

}
