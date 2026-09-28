package dev.autociv.simulation.model;

import java.util.Objects;
import java.util.UUID;

/** Timed bilateral treaty in the unloaded-world diplomacy simulation. */
public final class DiplomaticAgreement {

    public enum Type { TRADE_PACT, NON_AGGRESSION, ALLIANCE, PEACE }

    private final UUID id;
    private final UUID firstCivilizationId;
    private final UUID secondCivilizationId;
    private final Type type;
    private final int durationDays;
    private int remainingDays;

    public DiplomaticAgreement(UUID id, UUID firstCivilizationId, UUID secondCivilizationId, Type type,
                               int durationDays, int remainingDays) {
        this.id = Objects.requireNonNull(id, "id");
        this.firstCivilizationId = Objects.requireNonNull(firstCivilizationId, "firstCivilizationId");
        this.secondCivilizationId = Objects.requireNonNull(secondCivilizationId, "secondCivilizationId");
        if (firstCivilizationId.equals(secondCivilizationId)) {
            throw new IllegalArgumentException("A civilization cannot make a treaty with itself");
        }
        this.type = Objects.requireNonNull(type, "type");
        this.durationDays = Math.max(1, durationDays);
        this.remainingDays = Math.clamp(remainingDays, 0, this.durationDays);
    }

    public static DiplomaticAgreement create(UUID first, UUID second, Type type, int durationDays) {
        return new DiplomaticAgreement(UUID.randomUUID(), first, second, type, durationDays, durationDays);
    }

    public UUID id() { return id; }
    public UUID firstCivilizationId() { return firstCivilizationId; }
    public UUID secondCivilizationId() { return secondCivilizationId; }
    public Type type() { return type; }
    public int durationDays() { return durationDays; }
    public int remainingDays() { return remainingDays; }

    public boolean containsPair(UUID first, UUID second) {
        return firstCivilizationId.equals(first) && secondCivilizationId.equals(second)
                || firstCivilizationId.equals(second) && secondCivilizationId.equals(first);
    }

    /** Returns true on the day the agreement expires. */
    public boolean advanceDay() {
        if (remainingDays > 0) remainingDays--;
        return remainingDays == 0;
    }
}
