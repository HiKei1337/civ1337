package dev.autociv.simulation;

import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.world.WorldSimulation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cascade deletion and relation-cleanup tests. */
class DeletionTest {

    @Test
    void deletingCivilizationCascadesToCitiesAndCitizens() {
        WorldSimulation sim = new WorldSimulation();
        Civilization civ = sim.createCivilization("Doomed", 0x111111);
        Settlement city = sim.createSettlement(civ.id(), "LastTown", 0, 64, 0);
        sim.addCitizen(city, "Solo", 30, Citizen.Profession.SHEPHERD);

        assertTrue(sim.removeCivilization(civ.id()));
        assertEquals(0, sim.civilizations().size());
        assertEquals(0, sim.settlements().size());
        assertEquals(0, sim.totalPopulation());
    }

    @Test
    void relationsToDeletedCivAreCleanedUp() {
        WorldSimulation sim = new WorldSimulation();
        Civilization a = sim.createCivilization("Alpha", 0x010101);
        Civilization b = sim.createCivilization("Beta", 0x020202);
        a.setRelationWith(b.id(), Civilization.Relation.WAR);
        b.setRelationWith(a.id(), Civilization.Relation.WAR);

        sim.removeCivilization(b.id());

        assertEquals(0, a.relationsMutable().size(), "dangling relation must be removed");
        // default for any unknown id stays NEUTRAL
        assertEquals(Civilization.Relation.NEUTRAL, a.relationWith(b.id()));
    }

    @Test
    void capitalReassignsAfterDeletingCapitalCity() {
        WorldSimulation sim = new WorldSimulation();
        Civilization civ = sim.createCivilization("Realm", 0x333333);
        Settlement cap = sim.createSettlement(civ.id(), "FirstCity", 0, 64, 0);
        Settlement second = sim.createSettlement(civ.id(), "SecondCity", 100, 64, 100);

        sim.removeSettlement(cap.id());

        UUID newCap = civ.capitalSettlementId();
        assertEquals(second.id(), newCap);
        assertTrue(sim.settlement(newCap).isPresent());
    }

    @Test
    void distanceBetweenSettlementsIsSquaredHorizontal() {
        WorldSimulation sim = new WorldSimulation();
        Civilization civ = sim.createCivilization("Metrics", 0x444444);
        Settlement a = sim.createSettlement(civ.id(), "A", 0, 64, 0);
        Settlement b = sim.createSettlement(civ.id(), "B", 30, 90, 40);

        assertEquals(2500.0, a.distanceSqTo(b), 0.001); // y ignored
    }
}
