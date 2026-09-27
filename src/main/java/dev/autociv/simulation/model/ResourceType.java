package dev.autociv.simulation.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Data-driven registry of resource types.
 * <p>
 * Resources are identified by a stable string id (never by ordinal!), so new
 * resources can be added in later stages (and by addons) without breaking
 * already-saved worlds. Stage 1 ships the core set from the design document;
 * {@link #register(String)} allows extending the set without touching the
 * economy code.
 */
public final class ResourceType {

    private static final Map<String, ResourceType> REGISTRY = new LinkedHashMap<>();

    // --- core resources (design doc section 3.4) ---
    public static final ResourceType FOOD = register("food");
    public static final ResourceType WOOD = register("wood");
    public static final ResourceType STONE = register("stone");
    public static final ResourceType COAL = register("coal");
    public static final ResourceType IRON = register("iron");
    public static final ResourceType COPPER = register("copper");
    public static final ResourceType GOLD = register("gold");
    public static final ResourceType TOOLS = register("tools");
    public static final ResourceType WEAPONS = register("weapons");
    public static final ResourceType ARMOR = register("armor");
    public static final ResourceType BUILDING_MATERIALS = register("building_materials");
    public static final ResourceType LUXURY_GOODS = register("luxury_goods");
    public static final ResourceType MONEY = register("money");

    private final String id;

    private ResourceType(String id) {
        this.id = id;
    }

    /** Registers a custom resource type. Ids must be lowercase snake_case and unique. */
    public static synchronized ResourceType register(String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Resource id must not be empty");
        }
        ResourceType existing = REGISTRY.get(normalized);
        if (existing != null) {
            return existing;
        }
        ResourceType type = new ResourceType(normalized);
        REGISTRY.put(normalized, type);
        return type;
    }

    /** Resolves a resource by its stable string id (used when loading saves). */
    public static Optional<ResourceType> byId(String id) {
        return Optional.ofNullable(REGISTRY.get(id.toLowerCase(Locale.ROOT)));
    }

    /** Resolves or lazily registers an unknown id - keeps old worlds loadable after addon removal. */
    public static ResourceType byIdOrRegister(String id) {
        return byId(id).orElseGet(() -> register(id));
    }

    public static Map<String, ResourceType> all() {
        return Collections.unmodifiableMap(REGISTRY);
    }

    public String id() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ResourceType other && other.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id;
    }
}
