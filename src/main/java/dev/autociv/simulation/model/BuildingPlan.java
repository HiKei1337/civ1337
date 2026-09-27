package dev.autociv.simulation.model;

import java.util.Objects;
import java.util.UUID;

/** Persistent abstract construction and physical blueprint progress for one facility. */
public final class BuildingPlan {
    private final UUID id;
    private final BuildingType type;
    private final int requiredDays;
    private int progressDays;
    private int siteX = Integer.MIN_VALUE;
    private int siteY = Integer.MIN_VALUE;
    private int siteZ = Integer.MIN_VALUE;
    private int blockCursor;
    private boolean materialsPaid;

    public BuildingPlan(UUID id, BuildingType type, int progressDays, int requiredDays) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.requiredDays = Math.max(1, requiredDays);
        this.progressDays = Math.clamp(progressDays, 0, this.requiredDays);
    }

    public BuildingPlan(UUID id, BuildingType type, int progressDays, int requiredDays,
                        int siteX, int siteY, int siteZ, int blockCursor) {
        this(id, type, progressDays, requiredDays);
        this.siteX = siteX;
        this.siteY = siteY;
        this.siteZ = siteZ;
        this.blockCursor = Math.max(0, blockCursor);
    }

    public static BuildingPlan start(BuildingType type) {
        return new BuildingPlan(UUID.randomUUID(), type, 0, type.constructionDays());
    }

    public UUID id() { return id; }
    public BuildingType type() { return type; }
    public int progressDays() { return progressDays; }
    public int requiredDays() { return requiredDays; }
    public int siteX() { return siteX; }
    public int siteY() { return siteY; }
    public int siteZ() { return siteZ; }
    public int blockCursor() { return blockCursor; }
    public boolean materialsPaid() { return materialsPaid; }
    public void setMaterialsPaid(boolean paid) { materialsPaid = paid; }
    public boolean hasSite() { return siteX != Integer.MIN_VALUE; }
    public void assignSite(int x, int y, int z) { siteX = x; siteY = y; siteZ = z; }
    public void advanceBlock() { blockCursor++; }
    public int progressPercent() { return Math.min(100, progressDays * 100 / requiredDays); }
    public boolean advanceDay() { return ++progressDays >= requiredDays; }
}
