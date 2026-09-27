package dev.autociv.simulation.economy;

import dev.autociv.simulation.model.BuildingType;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.world.WorldSimulation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Abstract railway corridors on completed parent-city station pairs. */
public final class RailNetwork {
    private RailNetwork() { }

    public record Link(Settlement parent, Settlement child, double distance) { }
    public record Route(double directDistance, double travelDistance, int links, boolean connected) { }

    public static List<Link> links(WorldSimulation simulation, UUID civilizationId) {
        List<Link> result = new ArrayList<>();
        for (Settlement child : simulation.settlementsOf(civilizationId)) {
            Settlement parent = parentOf(simulation, child);
            if (isLink(parent, child)) result.add(new Link(parent, child, distance(parent, child)));
        }
        result.sort(java.util.Comparator.comparing(link -> link.child().name(), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    /** Uses rail only if every station pair on the parent-tree corridor is operational. */
    public static Route route(WorldSimulation simulation, Settlement source, Settlement destination) {
        double direct = Math.sqrt(source.distanceSqTo(destination));
        if (!source.civilizationId().equals(destination.civilizationId())) {
            return new Route(direct, direct, 0, false);
        }
        java.util.Map<UUID, Settlement> ancestors = new java.util.HashMap<>();
        Settlement cursor = source;
        for (int i = 0; cursor != null && i < 256; i++) {
            ancestors.put(cursor.id(), cursor);
            cursor = parentOf(simulation, cursor);
        }
        List<Settlement> destinationPath = new ArrayList<>();
        cursor = destination;
        Settlement common = null;
        for (int i = 0; cursor != null && i < 256; i++) {
            if ((common = ancestors.get(cursor.id())) != null) break;
            destinationPath.add(cursor);
            cursor = parentOf(simulation, cursor);
        }
        if (common == null) return new Route(direct, direct, 0, false);
        double railDistance = 0;
        int count = 0;
        cursor = source;
        while (!cursor.id().equals(common.id()) && count < 256) {
            Settlement parent = parentOf(simulation, cursor);
            if (!isLink(parent, cursor)) return new Route(direct, direct, 0, false);
            railDistance += distance(parent, cursor) * 0.42;
            count++;
            cursor = parent;
        }
        for (Settlement child : destinationPath) {
            Settlement parent = parentOf(simulation, child);
            if (!isLink(parent, child)) return new Route(direct, direct, 0, false);
            railDistance += distance(parent, child) * 0.42;
            count++;
        }
        if (count == 0 || railDistance >= direct) return new Route(direct, direct, 0, false);
        return new Route(direct, railDistance, count, true);
    }

    private static Settlement parentOf(WorldSimulation simulation, Settlement child) {
        return child == null || child.parentSettlementId() == null ? null
                : simulation.settlement(child.parentSettlementId()).orElse(null);
    }

    private static boolean isLink(Settlement parent, Settlement child) {
        return parent != null && child != null
                && parent.civilizationId().equals(child.civilizationId())
                && parent.buildingCount(BuildingType.RAILWAY_STATION) > 0
                && child.buildingCount(BuildingType.RAILWAY_STATION) > 0;
    }

    private static double distance(Settlement first, Settlement second) {
        return Math.sqrt(first.distanceSqTo(second));
    }
}
