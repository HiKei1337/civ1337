package dev.autociv.simulation.model;

import java.util.Locale;

/** Long-term policy applied by the current city planner and profession allocator. */
public enum DevelopmentStrategy {
    GROWTH, RESERVES, FORTIFY;

    public static DevelopmentStrategy parse(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }
}
