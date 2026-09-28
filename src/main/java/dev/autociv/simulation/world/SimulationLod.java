package dev.autociv.simulation.world;

/** Distance tiers for the boundary between abstract citizens and Minecraft entities. */
public enum SimulationLod {
    NEAR(16, 2),
    MID(6, 1),
    FAR(0, 0),
    VERY_FAR(0, 0);

    private final int physicalCitizenBudget;
    private final int physicalGuardBudget;

    SimulationLod(int physicalCitizenBudget, int physicalGuardBudget) {
        this.physicalCitizenBudget = physicalCitizenBudget;
        this.physicalGuardBudget = physicalGuardBudget;
    }

    public int physicalCitizenBudget() { return physicalCitizenBudget; }
    public int physicalGuardBudget() { return physicalGuardBudget; }

    public static SimulationLod atDistanceSquared(double distanceSquared) {
        if (distanceSquared <= 64.0 * 64.0) return NEAR;
        if (distanceSquared <= 128.0 * 128.0) return MID;
        if (distanceSquared <= 4096.0 * 4096.0) return FAR;
        return VERY_FAR;
    }
}
