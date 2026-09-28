package dev.autociv.scenario;

import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.world.SimulationEngine;
import dev.autociv.simulation.world.WorldSimulation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CivilizationScenarioTest {

    @Test
    void providesHistoricalStartingProfilesWithNamesResourcesAndProfessions() {
        CivilizationScenario rome = CivilizationScenario.byId("ROME").orElseThrow();

        assertEquals("Rome", rome.name());
        assertEquals(-753, rome.startYear());
        assertTrue(rome.era().contains("753 BCE"));
        assertEquals(CivilizationScenario.ArchitectureStyle.ROMAN, rome.architecture());
        assertTrue(rome.initialPopulation() > 0);
        assertTrue(rome.resources().get(ResourceType.FOOD) > 0);
        assertFalse(rome.initialProfessions().isEmpty());
        assertTrue(rome.cityNames().contains("Roma"));
    }

    @Test
    void offersMultiplePresetCivilizationsAndRejectsUnknownIds() {
        assertEquals(15, CivilizationScenario.all().size());
        assertEquals(15, CivilizationScenario.all().stream()
            .map(CivilizationScenario::id).distinct().count());
        assertTrue(CivilizationScenario.byId("egypt").isPresent());
        assertTrue(CivilizationScenario.byId("greece").isPresent());
        assertTrue(CivilizationScenario.byId("maya").isPresent());
        assertTrue(CivilizationScenario.byId("atlantis").isEmpty());
    }

    @Test
    void worldCompositionIsDeterministicAndContainsTenToFifteenUniqueCivilizations() {
        CivilizationScenario rome = CivilizationScenario.byId("rome").orElseThrow();
        for (long seed = -50; seed < 50; seed++) {
            List<CivilizationScenario> composition = ScenarioComposition.choose(seed, rome);
            assertTrue(composition.size() >= 10 && composition.size() <= 15);
            assertEquals(composition.size(), composition.stream()
                    .map(CivilizationScenario::id).distinct().count());
            assertTrue(composition.contains(rome));
            assertEquals(composition, ScenarioComposition.choose(seed, rome));
        }
    }

    @Test
    void initializesHistoricalCivilizationWithCitiesPopulationAndStartingResources() {
        WorldSimulation simulation = new WorldSimulation();
        CivilizationScenario scenario = CivilizationScenario.byId("rome").orElseThrow();
        Civilization civilization = ScenarioInitializer.initialize(simulation, scenario, List.of(
                new ScenarioInitializer.SettlementSite(0, 64, 0),
                new ScenarioInitializer.SettlementSite(48, 64, 0),
                new ScenarioInitializer.SettlementSite(0, 64, 48)));

        assertEquals("rome", civilization.scenarioId());
        assertEquals(scenario.era(), civilization.historicalEra());
        assertEquals(-753, civilization.historicalStartYear());
        assertEquals("roman", civilization.architectureStyle());
        assertEquals(-753, simulation.year());
        assertEquals(3, civilization.settlementIds().size());
        assertEquals(scenario.initialPopulation(), simulation.totalPopulation());
        assertTrue(simulation.settlements().stream()
                .allMatch(city -> city.stockpile().get(ResourceType.FOOD) > 0));
        assertEquals(Civilization.Relation.ALLIED, civilization.relationWith(civilization.id()));
    }

    @Test
    void historicalMilestonesChangeCivilizationOnceWhenTimelineReachesThem() {
        WorldSimulation simulation = new WorldSimulation();
        CivilizationScenario scenario = CivilizationScenario.byId("rome").orElseThrow();
        Civilization civilization = ScenarioInitializer.initialize(simulation, scenario, List.of(
                new ScenarioInitializer.SettlementSite(0, 64, 0),
                new ScenarioInitializer.SettlementSite(48, 64, 0),
                new ScenarioInitializer.SettlementSite(0, 64, 48)));
        simulation.setTimeDays(244.0 * 360.0 - 1.0);
        double treasuryBefore = civilization.treasury();
        SimulationEngine engine = new SimulationEngine();

        engine.advanceDay(simulation);
        engine.advanceDay(simulation);

        String republicEvent = "year -509: Roman Republic traditionally established";
        assertTrue(civilization.history().contains(republicEvent));
        assertEquals(treasuryBefore + 180, civilization.treasury(), 0.001);
        assertEquals(1, civilization.history().stream().filter(republicEvent::equals).count());
    }

    @Test
    void additionalCivilizationsDoNotResetTheWorldCalendarEpoch() {
        WorldSimulation simulation = new WorldSimulation();
        ScenarioInitializer.initialize(simulation, CivilizationScenario.byId("rome").orElseThrow(), List.of(
                new ScenarioInitializer.SettlementSite(0, 64, 0)));
        ScenarioInitializer.initialize(simulation, CivilizationScenario.byId("egypt").orElseThrow(), List.of(
                new ScenarioInitializer.SettlementSite(100, 64, 0)));

        assertEquals(-753, simulation.calendarStartYear());
        assertEquals(-753, simulation.year());
        assertEquals(-2686, simulation.civilizations().stream()
                .filter(civilization -> civilization.scenarioId().equals("egypt"))
                .findFirst().orElseThrow().historicalStartYear());
    }

    @Test
    void starterCivilizationsKnowOtherCapitalsWithoutLoadingTheirChunks() {
        WorldSimulation simulation = new WorldSimulation();
        Civilization west = simulation.createCivilization("West", 0x123456);
        Civilization east = simulation.createCivilization("East", 0x654321);
        var westCity = simulation.createSettlement(west.id(), "Westport", -9000, 64, 0);
        var eastCity = simulation.createSettlement(east.id(), "Eastport", 9000, 64, 0);

        ScenarioDiplomacy.establishMutualAwareness(simulation);

        assertEquals(Civilization.Relation.NEUTRAL, west.relationWith(east.id()));
        assertEquals(Civilization.Relation.NEUTRAL, east.relationWith(west.id()));
        assertTrue(west.knownSettlementIds().contains(eastCity.id()));
        assertTrue(east.knownSettlementIds().contains(westCity.id()));
        assertEquals(324_000_000.0, westCity.distanceSqTo(eastCity), 0.001);
    }
}