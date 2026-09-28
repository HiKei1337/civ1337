package dev.autociv.simulation.model;

import java.util.Locale;

/** Player-facing focus that nudges existing settlement systems. */
public enum ColonyPriority {
    FOOD, CONSTRUCTION, EXTRACTION, TRADE, DEFENSE;

    public static ColonyPriority parse(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }
}
