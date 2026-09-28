package dev.autociv.simulation;

import dev.autociv.simulation.ai.CityPlanner;
import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.world.WorldSimulation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CityPlannerTest {

    @Test
    void homeRequiresSurplusMaterialsAndPreservesTheReserve() {
        WorldSimulation simulation = new WorldSimulation();
        Civilization civilization = simulation.createCivilization("Hearth", 0x775533);
        Settlement settlement = simulation.createSettlement(civilization.id(), "Mudbank", 0, 64, 0);
        for (int index = 0; index < 24; index++) {
            simulation.addCitizen(settlement, "Resident " + index, 30, Citizen.Profession.UNASSIGNED);
        }
        settlement.setHousingCapacity(24);
        settlement.stockpile().add(ResourceType.WOOD, 167);
        settlement.stockpile().add(ResourceType.STONE, 104);
        CityPlanner planner = new CityPlanner();

        assertEquals(CityPlanner.Update.MATERIALS_SHORT, planner.advanceDay(settlement));
        assertEquals(167, settlement.stockpile().get(ResourceType.WOOD), 0.001);
        assertEquals(104, settlement.stockpile().get(ResourceType.STONE), 0.001);

        settlement.stockpile().add(ResourceType.WOOD, 81);
        assertEquals(CityPlanner.Update.IN_PROGRESS, planner.advanceDay(settlement));
        assertEquals(200, settlement.stockpile().get(ResourceType.WOOD), 0.001);
        assertEquals(80, settlement.stockpile().get(ResourceType.STONE), 0.001);
        for (int day = 1; day < CityPlanner.HOME_BUILD_DAYS; day++) {
            planner.advanceDay(settlement);
        }

        assertEquals(1, settlement.completedHomes());
        assertEquals(0, settlement.homeConstructionDays());
        assertEquals(32, settlement.housingCapacity());
        assertFalse(settlement.materializedHomes() > settlement.completedHomes());
    }
}
