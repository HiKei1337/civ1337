package dev.autociv.simulation.economy;

import dev.autociv.simulation.model.ResourceType;

import java.util.Objects;
import java.util.UUID;

/** Escrowed cargo moving between two cities on a persistent trade route. */
public record TradeShipment(UUID id, UUID routeId, UUID buyerCivilizationId, UUID sellerCivilizationId,
                            ResourceType resource, double quantity, double buyerUnitPrice,
                            double sellerUnitPrice, double departureDay, double arrivalDay) {
    public TradeShipment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(buyerCivilizationId, "buyerCivilizationId");
        Objects.requireNonNull(sellerCivilizationId, "sellerCivilizationId");
        Objects.requireNonNull(resource, "resource");
        if (!Double.isFinite(quantity) || quantity <= 0 || quantity > 1_000_000
                || !Double.isFinite(buyerUnitPrice) || buyerUnitPrice <= 0
                || !Double.isFinite(sellerUnitPrice) || sellerUnitPrice < 0
                || !Double.isFinite(departureDay) || departureDay < 0
                || !Double.isFinite(arrivalDay) || arrivalDay <= departureDay) {
            throw new IllegalArgumentException("Invalid trade shipment");
        }
    }

    public double escrowedAmount() { return quantity * buyerUnitPrice; }
}
