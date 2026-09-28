package dev.autociv.simulation.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Resource storage of one settlement (design doc section 3.4).
 * <p>
 * Amounts are doubles so production/consumption fractions accumulate smoothly.
 * Capacity is uniform per resource type; overflow is clamped on add. The map
 * is keyed by stable {@link ResourceType}s, so adding a new resource never
 * requires changes here.
 */
public final class ResourceStockpile {

    /** Default cap per resource before a warehouse upgrades it (Stage 5+). */
    public static final double DEFAULT_CAPACITY = 100_000.0;

    private final Map<ResourceType, Double> amounts = new HashMap<>();
    private final Map<ResourceType, Double> capacities = new HashMap<>();
    /** Per-day deltas recorded during the last simulation tick (for debug UI in Stage 2+). */
    // NOTE: ResourceType is a registry-style final class (open for data-driven
    // extension), NOT an enum - so EnumMap cannot be used here. Plain HashMap
    // keyed by stable singletons with identity hashCode is O(1) and correct.
    private final Map<ResourceType, Double> lastDeltaPerDay = new HashMap<>();

    public double get(ResourceType type) {
        return amounts.getOrDefault(type, 0.0);
    }

    public double capacity(ResourceType type) {
        return capacities.getOrDefault(type, DEFAULT_CAPACITY);
    }

    public void setCapacity(ResourceType type, double capacity) {
        capacities.put(type, Math.max(0, capacity));
    }

    /** Adds an amount (may be negative). Returns the actually-applied delta after clamping. */
    public double add(ResourceType type, double amount) {
        double current = get(type);
        double target = Math.min(capacity(type), Math.max(0.0, current + amount));
        double applied = target - current;
        amounts.put(type, target);
        return applied;
    }

    /** Removes up to {@code amount}; returns what was actually removed. */
    public double remove(ResourceType type, double amount) {
        if (amount <= 0) {
            return 0;
        }
        double have = get(type);
        double taken = Math.min(have, amount);
        amounts.put(type, have - taken);
        return taken;
    }

    /** True if the stockpile holds at least {@code amount} of {@code type}. */
    public boolean has(ResourceType type, double amount) {
        return get(type) >= amount;
    }

    /** Atomically removes a bundle or nothing. Returns false if any component is missing. */
    public boolean tryRemove(Map<ResourceType, Double> bundle) {
        for (Map.Entry<ResourceType, Double> e : bundle.entrySet()) {
            if (!has(e.getKey(), e.getValue())) {
                return false;
            }
        }
        for (Map.Entry<ResourceType, Double> e : bundle.entrySet()) {
            remove(e.getKey(), e.getValue());
        }
        return true;
    }

    public void recordDelta(ResourceType type, double deltaPerDay) {
        lastDeltaPerDay.merge(type, deltaPerDay, Double::sum);
    }

    public double deltaPerDay(ResourceType type) {
        return lastDeltaPerDay.getOrDefault(type, 0.0);
    }

    public void clearDeltas() {
        lastDeltaPerDay.clear();
    }

    public Set<Map.Entry<ResourceType, Double>> entries() {
        return amounts.entrySet();
    }

    public Map<ResourceType, Double> snapshot() {
        return Map.copyOf(amounts);
    }
}
