package dev.autociv.simulation.model;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CitizenItemCargoTest {

    @Test
    void itemCargoIsBoundedAndPartialWarehouseAcceptanceKeepsRemainder() {
        Citizen citizen = new Citizen(UUID.randomUUID(), "Worker", UUID.randomUUID(), 24,
                Citizen.Profession.LUMBERJACK);

        assertEquals(4, citizen.carryItem("minecraft:spruce_log", 4));
        assertEquals(12, citizen.carryItem("minecraft:oak_log", 20));
        assertEquals(0, citizen.carryItem("minecraft:stone", 1));

        assertEquals(Map.of("minecraft:oak_log", 6, "minecraft:spruce_log", 2),
                citizen.unloadItemCargo((id, count) -> count / 2));
        assertEquals(Map.of("minecraft:oak_log", 6, "minecraft:spruce_log", 2), citizen.itemCargo());
    }
}
