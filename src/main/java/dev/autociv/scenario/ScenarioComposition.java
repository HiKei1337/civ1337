package dev.autociv.scenario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Deterministic, unique civilization roster for a new world seed. */
public final class ScenarioComposition {

    private ScenarioComposition() {
    }

    public static List<CivilizationScenario> choose(long worldSeed, CivilizationScenario selected) {
        Random random = new Random(worldSeed);
        int count = 10 + random.nextInt(6);
        List<CivilizationScenario> available = new ArrayList<>(CivilizationScenario.all());
        List<CivilizationScenario> chosen = new ArrayList<>(count);
        if (selected != null && available.remove(selected)) {
            chosen.add(selected);
        }
        Collections.shuffle(available, random);
        for (CivilizationScenario scenario : available) {
            if (chosen.size() == count) {
                break;
            }
            chosen.add(scenario);
        }
        return List.copyOf(chosen);
    }
}
