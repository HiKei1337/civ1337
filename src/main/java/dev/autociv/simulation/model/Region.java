package dev.autociv.simulation.model;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Abstract 128-block world region; it never implies loaded chunks or block ownership. */
public final class Region {

    public static final int SIZE = 128;

    private final UUID id;
    private final int gridX;
    private final int gridZ;
    private final ResourceType resource;
    private final double richness;
    private UUID ownerCivilizationId;
    private UUID nearestSettlementId;

    public Region(UUID id, int gridX, int gridZ, ResourceType resource, double richness,
                  UUID ownerCivilizationId, UUID nearestSettlementId) {
        this.id = java.util.Objects.requireNonNull(id, "id");
        this.gridX = gridX;
        this.gridZ = gridZ;
        this.resource = java.util.Objects.requireNonNull(resource, "resource");
        this.richness = Math.clamp(richness, 0.1, 3.0);
        this.ownerCivilizationId = ownerCivilizationId;
        this.nearestSettlementId = nearestSettlementId;
    }

    public static Region generate(int gridX, int gridZ) {
        String key = key(gridX, gridZ);
        long hash = mix(((long) gridX << 32) ^ (gridZ & 0xFFFFFFFFL) ^ 0x6A09E667F3BCC909L);
        String[] resources = {"food", "wood", "stone", "iron", "coal", "copper", "gold"};
        ResourceType resource = ResourceType.byIdOrRegister(resources[Math.floorMod((int) hash, resources.length)]);
        double richness = 0.5 + ((hash >>> 12) & 0xFFFF) / 65535.0 * 1.5;
        UUID id = UUID.nameUUIDFromBytes(("autociv:region:" + key).getBytes(StandardCharsets.UTF_8));
        return new Region(id, gridX, gridZ, resource, richness, null, null);
    }

    public static String key(int gridX, int gridZ) { return gridX + ":" + gridZ; }
    public static int centerX(int gridX) { return gridX * SIZE + SIZE / 2; }
    public static int centerZ(int gridZ) { return gridZ * SIZE + SIZE / 2; }

    public UUID id() { return id; }
    public int gridX() { return gridX; }
    public int gridZ() { return gridZ; }
    public int centerX() { return centerX(gridX); }
    public int centerZ() { return centerZ(gridZ); }
    public ResourceType resource() { return resource; }
    public double richness() { return richness; }
    public UUID ownerCivilizationId() { return ownerCivilizationId; }
    public UUID nearestSettlementId() { return nearestSettlementId; }

    public void claim(UUID ownerCivilizationId, UUID nearestSettlementId) {
        this.ownerCivilizationId = ownerCivilizationId;
        this.nearestSettlementId = nearestSettlementId;
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
