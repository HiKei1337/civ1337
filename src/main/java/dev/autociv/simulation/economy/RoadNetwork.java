package dev.autociv.simulation.economy;

import dev.autociv.simulation.model.BuildingType;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.world.WorldSimulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Derives the current inter-city road graph from saved settlement hierarchy
 * and completed road buildings. This keeps the graph authoritative without a
 * second, potentially stale copy of city ownership or coordinates.
 */
public final class RoadNetwork {
    private static final int MAX_PARENT_STEPS = 256;

    private RoadNetwork() { }

    /** Distance after road quality is applied, plus details used by diagnostics. */
    public record Route(double directDistance, double travelDistance, int roadLinks,
                        int roadLevel, boolean connected) {
        public double roadSavings() {
            return Math.max(0, directDistance - travelDistance);
        }
    }

    /** A completed physical-logistics link in a civilization's settlement tree. */
    public record Link(Settlement parent, Settlement child, double distance, int level) { }

    /**
     * Finds the unique parent-tree corridor between two cities. A corridor is
     * usable only when every city on it has completed roads; otherwise trade
     * falls back to direct overland travel.
     */
    public static Route route(WorldSimulation simulation, Settlement source, Settlement destination) {
        double direct = Math.sqrt(source.distanceSqTo(destination));
        if (!source.civilizationId().equals(destination.civilizationId())) {
            return new Route(direct, direct, 0, 0, false);
        }

        Map<UUID, Settlement> sourceAncestors = ancestors(simulation, source);
        List<Settlement> destinationPath = new ArrayList<>();
        Settlement cursor = destination;
        Settlement common = null;
        for (int steps = 0; cursor != null && steps < MAX_PARENT_STEPS; steps++) {
            Settlement sourceMatch = sourceAncestors.get(cursor.id());
            if (sourceMatch != null) {
                common = sourceMatch;
                break;
            }
            destinationPath.add(cursor);
            cursor = cursor.parentSettlementId() == null ? null
                    : simulation.settlement(cursor.parentSettlementId()).orElse(null);
        }
        if (common == null) return new Route(direct, direct, 0, 0, false);

        double travel = 0;
        int links = 0;
        int totalLevel = 0;
        cursor = source;
        for (int steps = 0; !cursor.id().equals(common.id()) && steps < MAX_PARENT_STEPS; steps++) {
            Settlement parent = cursor.parentSettlementId() == null ? null
                    : simulation.settlement(cursor.parentSettlementId()).orElse(null);
            int level = linkLevel(parent, cursor);
            if (level == 0) return new Route(direct, direct, 0, 0, false);
            travel += edgeDistance(parent, cursor) * qualityMultiplier(level);
            links++;
            totalLevel += level;
            cursor = parent;
        }
        for (Settlement child : destinationPath) {
            Settlement parent = parentOf(simulation, child);
            int level = linkLevel(parent, child);
            if (level == 0) return new Route(direct, direct, 0, 0, false);
            travel += edgeDistance(parent, child) * qualityMultiplier(level);
            links++;
            totalLevel += level;
        }
        if (links == 0) return new Route(direct, direct, 0, 0, false);
        // The player-facing road system must never force a longer trip than
        // the available direct overland fallback.
        if (travel >= direct) return new Route(direct, direct, 0, 0, false);
        return new Route(direct, travel, links, Math.max(1, totalLevel / links), true);
    }

    /** Lists direct parent-child links that currently have roads at both ends. */
    public static List<Link> links(WorldSimulation simulation, UUID civilizationId) {
        List<Link> result = new ArrayList<>();
        for (Settlement child : simulation.settlementsOf(civilizationId)) {
            Settlement parent = parentOf(simulation, child);
            int level = linkLevel(parent, child);
            if (level > 0) result.add(new Link(parent, child, edgeDistance(parent, child), level));
        }
        result.sort(java.util.Comparator.comparing(link -> link.child().name(), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    private static Map<UUID, Settlement> ancestors(WorldSimulation simulation, Settlement city) {
        Map<UUID, Settlement> result = new HashMap<>();
        Settlement cursor = city;
        for (int steps = 0; cursor != null && steps < MAX_PARENT_STEPS; steps++) {
            result.put(cursor.id(), cursor);
            cursor = parentOf(simulation, cursor);
        }
        return result;
    }

    private static Settlement parentOf(WorldSimulation simulation, Settlement city) {
        return city == null || city.parentSettlementId() == null ? null
                : simulation.settlement(city.parentSettlementId()).orElse(null);
    }

    private static int linkLevel(Settlement parent, Settlement child) {
        if (parent == null || child == null || !parent.civilizationId().equals(child.civilizationId())) return 0;
        return Math.min(3, Math.min(parent.buildingCount(BuildingType.ROAD),
                child.buildingCount(BuildingType.ROAD)));
    }

    private static double edgeDistance(Settlement first, Settlement second) {
        return Math.sqrt(first.distanceSqTo(second));
    }

    private static double qualityMultiplier(int level) {
        return switch (Math.clamp(level, 1, 3)) {
            case 1 -> 0.88;
            case 2 -> 0.76;
            default -> 0.64;
        };
    }
}
