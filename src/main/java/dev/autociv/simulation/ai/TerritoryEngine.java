package dev.autociv.simulation.ai;

import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.Region;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.world.WorldSimulation;

import java.util.UUID;

/** Builds a lightweight grid of claimed regions around cities; it never touches Minecraft chunks. */
public final class TerritoryEngine {

    private static final double INFLUENCE_BUFFER = 96.0;

    public int update(WorldSimulation simulation) {
        int regionsBefore = simulation.regions().size();
        for (Settlement settlement : simulation.settlements()) {
            double influence = settlement.radius() + INFLUENCE_BUFFER;
            int minX = Math.floorDiv((int) Math.floor(settlement.x() - influence - Region.SIZE / 2.0), Region.SIZE);
            int maxX = Math.floorDiv((int) Math.ceil(settlement.x() + influence - Region.SIZE / 2.0), Region.SIZE);
            int minZ = Math.floorDiv((int) Math.floor(settlement.z() - influence - Region.SIZE / 2.0), Region.SIZE);
            int maxZ = Math.floorDiv((int) Math.ceil(settlement.z() + influence - Region.SIZE / 2.0), Region.SIZE);
            for (int gridX = minX; gridX <= maxX; gridX++) {
                for (int gridZ = minZ; gridZ <= maxZ; gridZ++) {
                    double dx = Region.centerX(gridX) - (double) settlement.x();
                    double dz = Region.centerZ(gridZ) - (double) settlement.z();
                    if (dx * dx + dz * dz <= influence * influence) {
                        if (simulation.region(gridX, gridZ).isEmpty()) {
                            Region region = Region.generate(gridX, gridZ);
                            simulation.addRegion(region);
                        }
                    }
                }
            }
        }

        int ownershipChanges = simulation.regions().size() - regionsBefore;
        for (Region region : simulation.regions()) {
            Settlement nearest = null;
            double nearestDistanceSq = Double.POSITIVE_INFINITY;
            // Query the existing chunk index around this region instead of scanning every city.
            // Settlement radius is currently capped by the model at 64 blocks; with the
            // influence buffer this bounds candidates to 160 blocks from a region center.
            for (Settlement settlement : simulation.settlementsNear(
                    region.centerX(), region.centerZ(), 160)) {
                double dx = region.centerX() - (double) settlement.x();
                double dz = region.centerZ() - (double) settlement.z();
                double distanceSq = dx * dx + dz * dz;
                double influence = settlement.radius() + INFLUENCE_BUFFER;
                if (distanceSq <= influence * influence && (distanceSq < nearestDistanceSq
                        || distanceSq == nearestDistanceSq && nearest != null
                        && settlement.id().toString().compareTo(nearest.id().toString()) < 0)) {
                    nearest = settlement;
                    nearestDistanceSq = distanceSq;
                }
            }

            UUID nextOwner = nearest == null ? null : nearest.civilizationId();
            UUID oldOwner = region.ownerCivilizationId();
            region.claim(nextOwner, nearest == null ? null : nearest.id());
            if (!java.util.Objects.equals(oldOwner, nextOwner)) {
                ownershipChanges++;
                recordClaimHistory(simulation, region, oldOwner, nextOwner);
            }
        }
        return ownershipChanges;
    }

    private void recordClaimHistory(WorldSimulation simulation, Region region, UUID oldOwner, UUID nextOwner) {
        Civilization previous = oldOwner == null ? null : simulation.civilization(oldOwner).orElse(null);
        Civilization current = nextOwner == null ? null : simulation.civilization(nextOwner).orElse(null);
        if (previous != null) {
            previous.recordEvent("year " + simulation.year() + ": lost region " + region.gridX() + ","
                    + region.gridZ() + (current == null ? "." : " to " + current.name() + "."));
        }
        if (current != null) {
            current.recordEvent("year " + simulation.year() + ": claimed region " + region.gridX() + ","
                    + region.gridZ() + (previous == null ? "." : " from " + previous.name() + "."));
        }
    }
}
