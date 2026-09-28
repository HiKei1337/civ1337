package dev.autociv.simulation.economy;

import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;

import java.util.Map;

/** Deterministic market price from scarcity, consumption and recent production. */
public final class PriceSystem {

    private static final Map<String, Double> BASE_PRICES = Map.ofEntries(
            Map.entry("food", 2.0), Map.entry("wood", 1.0), Map.entry("stone", 1.5),
            Map.entry("coal", 3.0), Map.entry("iron", 5.0), Map.entry("copper", 4.0),
            Map.entry("gold", 12.0), Map.entry("tools", 4.0), Map.entry("weapons", 8.0),
            Map.entry("armor", 10.0), Map.entry("building_materials", 2.0),
            Map.entry("luxury_goods", 8.0), Map.entry("money", 1.0));

    public double targetStock(Settlement settlement, ResourceType resource) {
        if (resource.equals(ResourceType.FOOD)) {
            return Math.max(100.0, settlement.population() * 10.0);
        }
        return Math.max(50.0, settlement.population() * 0.5);
    }

    public double quote(Settlement settlement, ResourceType resource) {
        double target = targetStock(settlement, resource);
        double stock = settlement.stockpile().get(resource);
        double scarcity = Math.clamp(target / Math.max(target * 0.05, stock), 0.25, 4.0);
        double trend = 1.0 + Math.clamp(-settlement.stockpile().deltaPerDay(resource) / target,
                -0.5, 1.5);
        return Math.max(0.01, BASE_PRICES.getOrDefault(resource.id(), 1.0)
                * Math.sqrt(scarcity) * trend);
    }
}