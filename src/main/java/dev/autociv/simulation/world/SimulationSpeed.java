package dev.autociv.simulation.world;

import java.util.Locale;
import java.util.Optional;

/** Number of Minecraft server ticks between abstract simulation days. */
public enum SimulationSpeed {
    REALISTIC(24_000),
    FAST(2_400),
    DEBUG(200),
    PAUSED(Integer.MAX_VALUE);

    private final int intervalTicks;

    SimulationSpeed(int intervalTicks) {
        this.intervalTicks = intervalTicks;
    }

    public int intervalTicks() {
        return intervalTicks;
    }

    public static Optional<SimulationSpeed> byId(String id) {
        try {
            return Optional.of(valueOf(id.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
