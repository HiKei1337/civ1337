package dev.autociv.simulation;

import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.world.WorldSimulation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SettlementOwnershipTest {
    @Test
    void onlyTheFirstPlayerCanClaimWithoutAnExplicitAdminTransfer() {
        WorldSimulation world = new WorldSimulation();
        Civilization civ = world.createCivilization("Test", 0x336699);
        Settlement city = world.createSettlement(civ.id(), "Test City", 0, 64, 0);
        UUID owner = UUID.randomUUID();
        UUID visitor = UUID.randomUUID();

        assertTrue(city.claim(owner));
        assertFalse(city.claim(visitor));
        assertEquals(owner, city.ownerPlayerId());
    }
}
