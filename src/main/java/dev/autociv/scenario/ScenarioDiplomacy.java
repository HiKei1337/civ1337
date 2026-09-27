package dev.autociv.scenario;

import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.world.WorldSimulation;

import java.util.List;

/** Gives all starter civilizations a non-loading abstract map of one another. */
public final class ScenarioDiplomacy {

    private ScenarioDiplomacy() {
    }

    public static void establishMutualAwareness(WorldSimulation simulation) {
        List<Civilization> civilizations = List.copyOf(simulation.civilizations());
        for (Civilization observer : civilizations) {
            for (Civilization other : civilizations) {
                if (observer.id().equals(other.id())) {
                    continue;
                }
                observer.setRelationWith(other.id(), Civilization.Relation.NEUTRAL);
                for (Settlement settlement : simulation.settlementsOf(other.id())) {
                    observer.knowSettlement(settlement.id());
                }
            }
        }
    }
}