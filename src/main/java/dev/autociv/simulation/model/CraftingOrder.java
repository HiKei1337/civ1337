package dev.autociv.simulation.model;

import java.util.Objects;

/** Persisted instruction for the city engineer to craft a datapack block recipe. */
public record CraftingOrder(String itemId, int batchesRemaining) {
    public CraftingOrder {
        itemId = Objects.requireNonNull(itemId, "itemId").trim();
        if (itemId.length() > 128 || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid namespaced item id");
        }
        if (batchesRemaining < 1 || batchesRemaining > 64) {
            throw new IllegalArgumentException("Craft batch count must be between 1 and 64");
        }
    }

    public CraftingOrder completeBatch() {
        return new CraftingOrder(itemId, batchesRemaining - 1);
    }
}
