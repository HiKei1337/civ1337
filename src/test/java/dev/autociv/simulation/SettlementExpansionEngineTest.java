package dev.autociv.simulation;

import dev.autociv.simulation.ai.SettlementExpansionEngine;
import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.model.SettlementRole;
import dev.autociv.simulation.world.WorldSimulation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementExpansionEngineTest {

    @Test
    void foundsLinkedSpecializedDaughterCityWithHousesAndRealMigrants() {
        WorldSimulation simulation = new WorldSimulation();
        Civilization civilization = simulation.createCivilization("Oakland", 0x336633);
        civilization.addToTreasury(500);
        Settlement capital = simulation.createSettlement(civilization.id(), "Oak Capital", 0, 64, 0);
        capital.setHousingCapacity(50);
        capital.setHomeBuildingState(28, 0, 0);
        capital.stockpile().add(ResourceType.FOOD, 800);
        capital.stockpile().add(ResourceType.WOOD, 600);
        capital.stockpile().add(ResourceType.STONE, 400);
        for (int index = 0; index < 28; index++) {
            simulation.addCitizen(capital, "Founder " + index, 20,
                    Citizen.Profession.UNASSIGNED);
        }

        int founded = new SettlementExpansionEngine().update(simulation);

        assertEquals(1, founded);
        Settlement daughter = simulation.settlementsOf(civilization.id()).stream()
                .filter(city -> !city.isCapital()).findFirst().orElseThrow();
        assertEquals(capital.id(), daughter.parentSettlementId());
        assertNotNull(daughter.role());
        assertFalse(daughter.role() == SettlementRole.CAPITAL);
        assertEquals(simulation.year(), daughter.foundedYear());
        assertEquals(4, daughter.population());
        assertEquals(4, daughter.housingCapacity());
        assertEquals(4, daughter.completedHomes());
        assertEquals(24, capital.population());
        assertTrue(civilization.treasury() < 500);
        assertTrue(simulation.regions().stream().anyMatch(region -> region.nearestSettlementId() == null));
    }

    @Test
    void settlementNetworkRejectsCrossCivilizationLinksAndCycles() {
        WorldSimulation simulation = new WorldSimulation();
        Civilization first = simulation.createCivilization("First", 0x336633);
        Civilization second = simulation.createCivilization("Second", 0x663333);
        Settlement capital = simulation.createSettlement(first.id(), "Capital", 0, 64, 0);
        Settlement daughter = simulation.createSettlement(first.id(), "Daughter", 300, 64, 0);
        Settlement grandchild = simulation.createSettlement(first.id(), "Grandchild", 600, 64, 0);
        Settlement foreignCity = simulation.createSettlement(second.id(), "Foreign", 900, 64, 0);

        assertTrue(simulation.linkSettlement(daughter, capital, SettlementRole.FORESTRY, 10));
        assertTrue(simulation.linkSettlement(grandchild, daughter, SettlementRole.MINING, 20));
        assertFalse(simulation.linkSettlement(capital, grandchild, SettlementRole.TRADE, 30));
        assertFalse(simulation.linkSettlement(foreignCity, capital, SettlementRole.TRADE, 30));
        assertEquals(capital.id(), daughter.parentSettlementId());
        assertEquals(daughter.id(), grandchild.parentSettlementId());
    }
}
