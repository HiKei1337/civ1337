package dev.autociv.simulation.model;

import dev.autociv.simulation.economy.Treasury;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A civilization - independent NPC state (design doc section 3.1).
 * <p>
 * Holds only aggregate data plus id-references to settlements; all lookups go
 * through {@code WorldSimulationManager}. Relation map keys are the OTHER
 * civilization's uuid.
 */
public final class Civilization {

    /** Diplomatic stance enum from design doc section 12 (Stage 9 activates transitions). */
    public enum Relation {
        FRIENDLY, NEUTRAL, TRADE_PARTNER, ALLIED, TENSE, HOSTILE, WAR
    }

    private final UUID id;
    private String name;
    private String scenarioId = "custom";
    private String historicalEra = "Unrecorded";
    private int historicalStartYear;
    private String architectureStyle = "generic";
    /** RGB color, 0xRRGGBB. */
    private int color;
    private UUID capitalSettlementId;
    private final List<UUID> settlementIds = new ArrayList<>();
    private final Treasury treasury = new Treasury(0);
    private double culture;        // abstract culture points
    private double militaryStrength; // aggregate, computed from units in Stage 11
    private final Map<UUID, Relation> relations = new HashMap<>();
    /** Symmetric diplomacy engine score in [-1, 1]; relation labels are derived from this score. */
    private final Map<UUID, Double> diplomaticOpinions = new HashMap<>();
    private final java.util.EnumSet<CivilizationTrait> traits =
            java.util.EnumSet.of(CivilizationTrait.TRADING, CivilizationTrait.DEFENSIVE);
    private final java.util.Set<UUID> knownSettlementIds = new java.util.LinkedHashSet<>();
    private final List<String> history = new ArrayList<>();

    public Civilization(UUID id, String name, int color) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.color = color & 0xFFFFFF;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String scenarioId() {
        return scenarioId;
    }

    public void setScenario(String scenarioId, String historicalEra, int historicalStartYear,
                            String architectureStyle) {
        this.scenarioId = Objects.requireNonNull(scenarioId, "scenarioId");
        this.historicalEra = Objects.requireNonNull(historicalEra, "historicalEra");
        this.historicalStartYear = historicalStartYear;
        this.architectureStyle = Objects.requireNonNull(architectureStyle, "architectureStyle");
    }

    public String historicalEra() {
        return historicalEra;
    }

    public int historicalStartYear() {
        return historicalStartYear;
    }

    public String architectureStyle() {
        return architectureStyle;
    }

    public java.util.Set<CivilizationTrait> traits() {
        return Collections.unmodifiableSet(traits);
    }

    public boolean hasTrait(CivilizationTrait trait) {
        return traits.contains(trait);
    }

    public void setTraits(java.util.Collection<CivilizationTrait> traits) {
        this.traits.clear();
        if (traits != null) {
            traits.stream().filter(Objects::nonNull).limit(2).forEach(this.traits::add);
        }
        if (this.traits.isEmpty()) this.traits.addAll(CivilizationTrait.forScenario(scenarioId));
    }

    public void setName(String name) {
        this.name = name;
    }

    public int color() {
        return color;
    }

    public void setColor(int color) {
        this.color = color & 0xFFFFFF;
    }

    public UUID capitalSettlementId() {
        return capitalSettlementId;
    }

    public void setCapitalSettlementId(UUID capitalSettlementId) {
        this.capitalSettlementId = capitalSettlementId;
    }

    public List<UUID> settlementIds() {
        return Collections.unmodifiableList(settlementIds);
    }

    public void addSettlementInternal(UUID settlementId) {
        settlementIds.add(settlementId);
    }

    public void removeSettlementInternal(UUID settlementId) {
        settlementIds.remove(settlementId);
    }

    public double treasury() {
        return treasury.balance();
    }

    public void addToTreasury(double delta) {
        if (!Double.isFinite(delta)) {
            throw new IllegalArgumentException("Treasury change must be finite");
        }
        if (delta >= 0) {
            treasury.deposit(delta);
        } else {
            treasury.withdraw(Math.min(treasury.balance(), -delta));
        }
    }

    public boolean withdrawFromTreasury(double amount) {
        return treasury.withdraw(amount);
    }

    public double culture() {
        return culture;
    }

    public void addToCulture(double delta) {
        this.culture = Math.max(0, culture + delta);
    }

    public double militaryStrength() {
        return militaryStrength;
    }

    public void setMilitaryStrength(double militaryStrength) {
        this.militaryStrength = Math.max(0, militaryStrength);
    }

    /** Never null: unknown counterparties default to {@link Relation#NEUTRAL}. */
    public Relation relationWith(UUID otherCivId) {
        if (otherCivId.equals(id)) {
            return Relation.ALLIED; // self
        }
        return relations.getOrDefault(otherCivId, Relation.NEUTRAL);
    }

    public void setRelationWith(UUID otherCivId, Relation relation) {
        if (!otherCivId.equals(id)) {
            relations.put(otherCivId, relation);
        }
    }

    /** Live unmodifiable view. Do NOT call removeIf on it; use {@link #relationsMutable()} internally. */
    public Map<UUID, Relation> relations() {
        return Collections.unmodifiableMap(relations);
    }

    /** Mutable access for the persistence layer / relation cleanup. */
    public Map<UUID, Relation> relationsMutable() {
        return relations;
    }

    public double diplomaticOpinionWith(UUID otherCivId) {
        return diplomaticOpinions.getOrDefault(otherCivId, 0.0);
    }

    public void setDiplomaticOpinionWith(UUID otherCivId, double opinion) {
        if (!otherCivId.equals(id)) {
            if (!Double.isFinite(opinion)) throw new IllegalArgumentException("Opinion must be finite");
            diplomaticOpinions.put(otherCivId, Math.clamp(opinion, -1.0, 1.0));
        }
    }

    public Map<UUID, Double> diplomaticOpinions() {
        return Collections.unmodifiableMap(diplomaticOpinions);
    }

    public Map<UUID, Double> diplomaticOpinionsMutable() {
        return diplomaticOpinions;
    }

    public void knowSettlement(UUID settlementId) {
        knownSettlementIds.add(Objects.requireNonNull(settlementId, "settlementId"));
    }

    public java.util.Set<UUID> knownSettlementIds() {
        return Collections.unmodifiableSet(knownSettlementIds);
    }

    /** Appends a line to the civilization history log (Stage 17 will date-stamp events). */
    public void recordEvent(String event) {
        history.add(event);
    }

    public List<String> history() {
        return Collections.unmodifiableList(history);
    }

    private static final String EVENT_STATE_PREFIX = "EVENTSTATE|";

    /** User-facing history with persisted event-state markers hidden. */
    public List<String> visibleHistory() {
        return history.stream().filter(event -> !event.startsWith(EVENT_STATE_PREFIX)).toList();
    }

    public boolean worldEventActive(String eventType, UUID settlementId) {
        String prefix = EVENT_STATE_PREFIX + eventType + "|" + settlementId + "|";
        for (int i = history.size() - 1; i >= 0; i--) {
            String entry = history.get(i);
            if (entry.startsWith(prefix)) return entry.startsWith(prefix + "true|");
        }
        return false;
    }

    /** Stores event lifecycle state inside the already persisted history field. */
    public boolean setWorldEventActive(String eventType, UUID settlementId, boolean active,
                                       long day, int year, String description) {
        boolean previous = worldEventActive(eventType, settlementId);
        if (previous == active) return false;
        history.add(EVENT_STATE_PREFIX + eventType + "|" + settlementId + "|" + active + "|" + day);
        recordEvent("year " + year + ": " + description);
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Civilization c && c.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
