package dev.autociv.scenario;

import net.minecraft.world.level.GameRules;

import java.util.List;

/** Persists the Create World scenario selection in vanilla level.dat gamerules. */
public final class CivilizationScenarioGameRules {

    public static final GameRules.Key<GameRules.IntegerValue> SCENARIO_ID = GameRules.register(
            "autocivScenario", GameRules.Category.MISC, GameRules.IntegerValue.create(0));

    private CivilizationScenarioGameRules() {
    }

    public static void registered() {
        // Accessing this class initializes the rule before world creation/loading.
    }

    public static int selectionId(CivilizationScenario scenario) {
        int index = CivilizationScenario.all().indexOf(scenario);
        return index < 0 ? 0 : index + 1;
    }

    public static CivilizationScenario bySelectionId(int id) {
        List<CivilizationScenario> scenarios = CivilizationScenario.all();
        return id > 0 && id <= scenarios.size() ? scenarios.get(id - 1) : null;
    }
}