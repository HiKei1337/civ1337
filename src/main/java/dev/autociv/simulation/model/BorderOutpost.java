package dev.autociv.simulation.model;

import java.util.Objects;
import java.util.UUID;

/** A saved forward camp established by a civilization on a tense border. */
public record BorderOutpost(UUID id, UUID civilizationId, UUID rivalCivilizationId,
                            int x, int y, int z, double foundedDay, double garrisonStrength) {
    public BorderOutpost {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(civilizationId, "civilizationId");
        Objects.requireNonNull(rivalCivilizationId, "rivalCivilizationId");
        if (civilizationId.equals(rivalCivilizationId)) throw new IllegalArgumentException("Outpost rival must differ");
        if (Math.abs((long) x) > 29_000_000 || Math.abs((long) z) > 29_000_000) {
            throw new IllegalArgumentException("Outpost is outside the world border");
        }
        if (!Double.isFinite(foundedDay) || foundedDay < 0) throw new IllegalArgumentException("Invalid outpost date");
        if (!Double.isFinite(garrisonStrength) || garrisonStrength < 0) {
            throw new IllegalArgumentException("Invalid outpost garrison");
        }
    }
}
