package dev.autociv.simulation.model;

import java.util.List;
import java.util.Locale;

/** Persistent strategic personality shared by the civilization's cities. */
public enum CivilizationTrait {
    INDUSTRIAL("Промышленная"),
    MILITARISTIC("Милитаристская"),
    TRADING("Торговая"),
    AGRICULTURAL("Земледельческая"),
    EXPANSIONIST("Экспансионистская"),
    DEFENSIVE("Оборонительная"),
    SCIENTIFIC("Научная"),
    ISOLATIONIST("Изоляционистская");

    private final String displayNameRu;

    CivilizationTrait(String displayNameRu) {
        this.displayNameRu = displayNameRu;
    }

    public String displayNameRu() {
        return displayNameRu;
    }

    /** Stable two-trait scenario identity; it does not depend on world load order. */
    public static List<CivilizationTrait> forScenario(String scenarioId) {
        if (scenarioId == null) return List.of(TRADING, DEFENSIVE);
        return switch (scenarioId.toLowerCase(Locale.ROOT)) {
            case "rome" -> List.of(MILITARISTIC, EXPANSIONIST);
            case "egypt" -> List.of(AGRICULTURAL, DEFENSIVE);
            case "greece" -> List.of(SCIENTIFIC, TRADING);
            case "maya" -> List.of(AGRICULTURAL, SCIENTIFIC);
            case "carthage", "phoenicia", "aksum" -> List.of(TRADING, EXPANSIONIST);
            case "persia", "assyria" -> List.of(MILITARISTIC, EXPANSIONIST);
            case "babylon" -> List.of(SCIENTIFIC, TRADING);
            case "han" -> List.of(SCIENTIFIC, AGRICULTURAL);
            case "maurya", "khmer" -> List.of(AGRICULTURAL, DEFENSIVE);
            case "inca" -> List.of(DEFENSIVE, EXPANSIONIST);
            case "olmec" -> List.of(AGRICULTURAL, ISOLATIONIST);
            default -> List.of(TRADING, DEFENSIVE);
        };
    }
}
