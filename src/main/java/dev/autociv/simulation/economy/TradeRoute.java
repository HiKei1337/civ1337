package dev.autociv.simulation.economy;

import dev.autociv.simulation.model.ResourceType;

import java.util.Objects;
import java.util.UUID;

/** Persistent route contract between a surplus city and a deficit city. */
public final class TradeRoute {

    private final UUID id;
    private final UUID merchantId;
    private final UUID sourceSettlementId;
    private final UUID destinationSettlementId;
    private final ResourceType resource;
    private final double distance;
    private long completedShipments;
    private double totalQuantity;
    private boolean active = true;

    public TradeRoute(UUID id, UUID merchantId, UUID sourceSettlementId, UUID destinationSettlementId,
                      ResourceType resource, double distance, long completedShipments,
                      double totalQuantity, boolean active) {
        this.id = Objects.requireNonNull(id, "id");
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.sourceSettlementId = Objects.requireNonNull(sourceSettlementId, "sourceSettlementId");
        this.destinationSettlementId = Objects.requireNonNull(destinationSettlementId, "destinationSettlementId");
        this.resource = Objects.requireNonNull(resource, "resource");
        this.distance = Math.max(0.0, distance);
        this.completedShipments = Math.max(0L, completedShipments);
        this.totalQuantity = Math.max(0.0, totalQuantity);
        this.active = active;
    }

    public UUID id() { return id; }
    public UUID merchantId() { return merchantId; }
    public UUID sourceSettlementId() { return sourceSettlementId; }
    public UUID destinationSettlementId() { return destinationSettlementId; }
    public ResourceType resource() { return resource; }
    public double distance() { return distance; }
    public long completedShipments() { return completedShipments; }
    public double totalQuantity() { return totalQuantity; }
    public boolean active() { return active; }

    public void recordShipment(double quantity) {
        completedShipments++;
        totalQuantity += quantity;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}