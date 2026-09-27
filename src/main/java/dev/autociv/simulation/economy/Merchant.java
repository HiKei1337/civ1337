package dev.autociv.simulation.economy;

import java.util.Objects;
import java.util.UUID;

/** Abstract carrier ledger associated with one persistent trade route. */
public final class Merchant {

    private final UUID id;
    private final UUID routeId;
    private long deliveries;
    private double lifetimeQuantity;
    private double transportRevenue;

    public Merchant(UUID id, UUID routeId, long deliveries, double lifetimeQuantity) {
        this(id, routeId, deliveries, lifetimeQuantity, 0.0);
    }

    public Merchant(UUID id, UUID routeId, long deliveries, double lifetimeQuantity, double transportRevenue) {
        this.id = Objects.requireNonNull(id, "id");
        this.routeId = Objects.requireNonNull(routeId, "routeId");
        this.deliveries = Math.max(0, deliveries);
        this.lifetimeQuantity = Math.max(0.0, lifetimeQuantity);
        this.transportRevenue = Math.max(0.0, transportRevenue);
    }

    public UUID id() { return id; }
    public UUID routeId() { return routeId; }
    public long deliveries() { return deliveries; }
    public double lifetimeQuantity() { return lifetimeQuantity; }
    public double transportRevenue() { return transportRevenue; }

    public void recordDelivery(double quantity) {
        recordDelivery(quantity, 0.0);
    }

    public void recordDelivery(double quantity, double fee) {
        deliveries++;
        lifetimeQuantity += quantity;
        transportRevenue += Math.max(0.0, fee);
    }
}
