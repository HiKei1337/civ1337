package dev.autociv.simulation.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A settlement / city (design doc section 3.2).
 * <p>
 * Pure simulation object: position is stored as plain coordinates, no chunks
 * or block entities are required while the area is unloaded. Citizens live in
 * a separate global registry ({@code WorldSimulationManager}) and reference
 * this settlement by id; {@link #citizenIds} keeps the reverse index.
 */
public final class Settlement {

    /** Approximate progress denominator; templates now vary in block count. */
    public static final int PHYSICAL_HOME_BUILD_STEPS = 175;

    private final UUID id;
    private String name;
    private UUID civilizationId;
    /** Block coordinates of the settlement center (market square). */
    private int x;
    private int y;
    private int z;
    /** Radius in blocks of the abstract influence circle (Stage 10 replaces with regions). */
    private int radius = 64;
    private boolean capital;
    private UUID parentSettlementId;
    private SettlementRole role = SettlementRole.VILLAGE;
    private int foundedYear;
    /** Null means that the city is not player-claimed. */
    private UUID ownerPlayerId;
    private ColonyPriority priority = ColonyPriority.FOOD;
    private DevelopmentStrategy strategy = DevelopmentStrategy.GROWTH;

    /** Hard cap from housing; Stage 5 computes it from built houses. */
    private int housingCapacity = 50;
    private double happiness = 0.7;   // 0..1
    private double security = 0.7;    // 0..1
    private double defense = 0.0;     // abstract fortification strength
    private double populationGrowthProgress;
    private boolean physicalSettlementGenerated;
    /** Completed permanent homes, physical homes rendered in the capital chunk, and active build days. */
    private int completedHomes;
    private int materializedHomes;
    private int homeConstructionDays;
    /** Block placements completed by the physical builder on the current home. */
    private int homeBuildCursor;
    private boolean homeMaterialsPaid;
    private final java.util.EnumMap<BuildingType, Integer> buildings = new java.util.EnumMap<>(BuildingType.class);
    private BuildingPlan activeBuildingPlan;
    private final List<BuildingPlan> pendingPhysicalBuildings = new ArrayList<>();
    private final java.util.EnumMap<BuildingType, Integer> materializedBuildings = new java.util.EnumMap<>(BuildingType.class);
    private final List<BuildingType> buildingQueue = new ArrayList<>();
    private final List<CraftingOrder> craftingQueue = new ArrayList<>();
    /** Namespaced block item currently preventing the physical builder from continuing. */
    private String buildingMaterialDemand;

    private final ResourceStockpile stockpile = new ResourceStockpile();
    private final List<UUID> citizenIds = new ArrayList<>();

    public Settlement(UUID id, String name, UUID civilizationId, int x, int y, int z) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.civilizationId = Objects.requireNonNull(civilizationId, "civilizationId");
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID civilizationId() {
        return civilizationId;
    }

    public void setCivilizationId(UUID civilizationId) {
        this.civilizationId = civilizationId;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public void setPosition(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int radius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = Math.clamp(radius, 1, 64);
    }

    public boolean isCapital() {
        return capital;
    }

    public void setCapital(boolean capital) {
        this.capital = capital;
        if (capital) {
            parentSettlementId = null;
            role = SettlementRole.CAPITAL;
        } else if (role == SettlementRole.CAPITAL) {
            role = SettlementRole.VILLAGE;
        }
    }

    public UUID parentSettlementId() { return parentSettlementId; }
    public SettlementRole role() { return role; }
    public int foundedYear() { return foundedYear; }
    public SettlementTier tier() { return SettlementTier.from(population(), completedHomes); }

    public void setNetworkProfile(UUID parentId, SettlementRole role, int foundedYear) {
        this.parentSettlementId = parentId;
        this.role = role == null ? (capital ? SettlementRole.CAPITAL : SettlementRole.VILLAGE) : role;
        this.foundedYear = Math.max(-100_000, Math.min(100_000, foundedYear));
        if (capital) {
            this.parentSettlementId = null;
            this.role = SettlementRole.CAPITAL;
        }
    }

    public UUID ownerPlayerId() { return ownerPlayerId; }
    public boolean claim(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (ownerPlayerId != null && !ownerPlayerId.equals(playerId)) return false;
        ownerPlayerId = playerId;
        return true;
    }
    public void setOwnerPlayerId(UUID playerId) { ownerPlayerId = playerId; }
    public ColonyPriority priority() { return priority; }
    public void setPriority(ColonyPriority priority) { this.priority = Objects.requireNonNull(priority); }
    public DevelopmentStrategy strategy() { return strategy; }
    public void setStrategy(DevelopmentStrategy strategy) { this.strategy = Objects.requireNonNull(strategy); }

    public int population() {
        return citizenIds.size();
    }

    public int housingCapacity() {
        return housingCapacity;
    }

    public void setHousingCapacity(int housingCapacity) {
        this.housingCapacity = Math.max(0, housingCapacity);
    }

    public boolean hasRoomForCitizen() {
        return population() < housingCapacity;
    }

    public double happiness() {
        return happiness;
    }

    public void setHappiness(double happiness) {
        this.happiness = clamp(happiness, 0, 1);
    }

    public double security() {
        return security;
    }

    public void setSecurity(double security) {
        this.security = clamp(security, 0, 1);
    }

    public double defense() {
        return defense;
    }

    public void setDefense(double defense) {
        this.defense = Math.max(0, defense);
    }

    public double populationGrowthProgress() {
        return populationGrowthProgress;
    }

    public void addPopulationGrowthProgress(double amount) {
        populationGrowthProgress = Math.max(0, populationGrowthProgress + amount);
    }

    public boolean physicalSettlementGenerated() {
        return physicalSettlementGenerated;
    }

    public void setPhysicalSettlementGenerated(boolean physicalSettlementGenerated) {
        this.physicalSettlementGenerated = physicalSettlementGenerated;
    }

    public int completedHomes() {
        return completedHomes;
    }

    public int materializedHomes() {
        return materializedHomes;
    }

    public int homeConstructionDays() {
        return homeConstructionDays;
    }

    public int homeBuildCursor() {
        return homeBuildCursor;
    }

    public boolean homeMaterialsPaid() { return homeMaterialsPaid; }

    public void setHomeMaterialsPaid(boolean paid) { homeMaterialsPaid = paid; }

    public int homeBuildProgressPercent() {
        if (materializedHomes >= completedHomes) {
            return 100;
        }
        return Math.min(99, homeBuildCursor * 100 / PHYSICAL_HOME_BUILD_STEPS);
    }

    public void setHomeBuildingState(int completedHomes, int materializedHomes, int constructionDays) {
        this.completedHomes = Math.max(0, completedHomes);
        this.materializedHomes = Math.clamp(materializedHomes, 0, this.completedHomes);
        this.homeConstructionDays = Math.max(0, constructionDays);
        if (this.materializedHomes >= this.completedHomes) homeMaterialsPaid = false;
    }

    public void setHomeBuildCursor(int cursor) {
        homeMaterialsPaid = cursor < 0 || cursor > 0;
        homeBuildCursor = Math.max(0, cursor);
    }

    public void setHomeConstructionDays(int days) {
        homeConstructionDays = Math.max(0, days);
    }

    public void completeHomeConstruction() {
        completedHomes++;
        homeConstructionDays = 0;
        housingCapacity++;
        homeMaterialsPaid = false;
    }

    public void markNextHomeMaterialized() {
        if (materializedHomes < completedHomes) {
            materializedHomes++;
            homeBuildCursor = 0;
            homeMaterialsPaid = false;
        }
    }

    public int buildingCount(BuildingType type) {
        return buildings.getOrDefault(type, 0);
    }

    public java.util.Map<BuildingType, Integer> buildings() {
        return java.util.Collections.unmodifiableMap(buildings);
    }

    public void restoreBuildings(java.util.Map<BuildingType, Integer> restored) {
        buildings.clear();
        restored.forEach((type, count) -> {
            if (count != null && count > 0) buildings.put(type, Math.min(type.cityLimit(), count));
        });
        applyWarehouseCapacity();
    }

    public void addBuilding(BuildingType type, int amount) {
        if (amount <= 0) return;
        int previous = buildingCount(type);
        buildings.put(type, Math.min(type.cityLimit(), previous + amount));
        if (type == BuildingType.WAREHOUSE && buildingCount(type) != previous) applyWarehouseCapacity();
    }

    public void registerStartingFacility(BuildingType type) {
        int before = buildingCount(type);
        addBuilding(type, 1);
        if (buildingCount(type) > before) materializedBuildings.merge(type, 1, Integer::sum);
    }

    public BuildingPlan activeBuildingPlan() {
        return activeBuildingPlan;
    }

    public void setActiveBuildingPlan(BuildingPlan plan) {
        activeBuildingPlan = plan;
    }

    public void completeActiveBuildingPlan() {
        if (activeBuildingPlan == null) return;
        BuildingType type = activeBuildingPlan.type();
        addBuilding(type, 1);
        if (type == BuildingType.BARRACKS) {
            setSecurity(security + 0.15);
            setDefense(defense + 0.25);
        } else if (type == BuildingType.FORTIFICATION) {
            setSecurity(security + 0.10);
            setDefense(defense + 0.50);
        }
        pendingPhysicalBuildings.add(activeBuildingPlan);
        activeBuildingPlan = null;
    }

    public void restoreActiveBuildingPlan(BuildingPlan plan) {
        activeBuildingPlan = plan;
    }

    public List<BuildingType> buildingQueue() { return Collections.unmodifiableList(buildingQueue); }
    public void queueBuilding(BuildingType type, int index) {
        buildingQueue.add(Math.clamp(index, 0, buildingQueue.size()), Objects.requireNonNull(type));
    }
    public BuildingType dequeueBuilding() { return buildingQueue.isEmpty() ? null : buildingQueue.remove(0); }
    public boolean removeQueuedBuilding(int index) {
        if (index < 0 || index >= buildingQueue.size()) return false;
        buildingQueue.remove(index);
        return true;
    }
    public boolean moveQueuedBuilding(int index, int direction) {
        int target = index + Integer.signum(direction);
        if (index < 0 || index >= buildingQueue.size() || target < 0 || target >= buildingQueue.size()) return false;
        Collections.swap(buildingQueue, index, target);
        return true;
    }
    public void restoreBuildingQueue(List<BuildingType> restored) {
        buildingQueue.clear();
        if (restored != null) buildingQueue.addAll(restored);
    }

    public List<CraftingOrder> craftingQueue() { return Collections.unmodifiableList(craftingQueue); }
    public void queueCrafting(CraftingOrder order) {
        if (craftingQueue.size() >= 32) throw new IllegalStateException("Crafting queue is full");
        craftingQueue.add(Objects.requireNonNull(order));
    }
    public CraftingOrder nextCraftingOrder() { return craftingQueue.isEmpty() ? null : craftingQueue.get(0); }
    public boolean completeCraftingBatch() {
        if (craftingQueue.isEmpty()) return false;
        CraftingOrder current = craftingQueue.get(0);
        if (current.batchesRemaining() == 1) craftingQueue.remove(0);
        else craftingQueue.set(0, current.completeBatch());
        return true;
    }
    public boolean removeCraftingOrder(int index) {
        if (index < 0 || index >= craftingQueue.size()) return false;
        craftingQueue.remove(index);
        return true;
    }
    public boolean moveCraftingOrder(int index, int direction) {
        int target = index + Integer.signum(direction);
        if (index < 0 || index >= craftingQueue.size() || target < 0 || target >= craftingQueue.size()) return false;
        Collections.swap(craftingQueue, index, target);
        return true;
    }
    public void restoreCraftingQueue(List<CraftingOrder> restored) {
        craftingQueue.clear();
        if (restored != null) craftingQueue.addAll(restored.stream().limit(32).toList());
    }

    public String buildingMaterialDemand() { return buildingMaterialDemand; }
    public void setBuildingMaterialDemand(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            buildingMaterialDemand = null;
            return;
        }
        if (itemId.length() > 128 || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid construction material id");
        }
        buildingMaterialDemand = itemId;
    }

    public List<BuildingPlan> pendingPhysicalBuildings() {
        return Collections.unmodifiableList(pendingPhysicalBuildings);
    }

    public BuildingPlan nextPhysicalBuilding() {
        return pendingPhysicalBuildings.isEmpty() ? null : pendingPhysicalBuildings.get(0);
    }

    public void restorePendingPhysicalBuildings(List<BuildingPlan> plans) {
        pendingPhysicalBuildings.clear();
        pendingPhysicalBuildings.addAll(plans);
    }

    public void markPhysicalBuildingComplete(BuildingPlan plan) {
        if (pendingPhysicalBuildings.remove(plan)) {
            materializedBuildings.merge(plan.type(), 1, Integer::sum);
        }
    }

    public int materializedBuildingCount(BuildingType type) {
        return materializedBuildings.getOrDefault(type, 0);
    }

    public Map<BuildingType, Integer> materializedBuildings() {
        return Collections.unmodifiableMap(materializedBuildings);
    }

    public void restoreMaterializedBuildings(Map<BuildingType, Integer> buildings) {
        materializedBuildings.clear();
        buildings.forEach((type, count) -> {
            if (count != null && count > 0) materializedBuildings.put(type, Math.min(type.cityLimit(), count));
        });
    }

    private void applyWarehouseCapacity() {
        double capacity = ResourceStockpile.DEFAULT_CAPACITY + buildingCount(BuildingType.WAREHOUSE) * 50_000.0;
        for (ResourceType resource : ResourceType.all().values()) {
            stockpile.setCapacity(resource, capacity);
        }
    }

    public ResourceStockpile stockpile() {
        return stockpile;
    }

    /** Unmodifiable view of citizens living here. The list instance itself is mutable for the manager. */
    public List<UUID> citizenIds() {
        return Collections.unmodifiableList(citizenIds);
    }

    public void addCitizenInternal(UUID citizenId) {
        citizenIds.add(citizenId);
    }

    public void removeCitizenInternal(UUID citizenId) {
        citizenIds.remove(citizenId);
    }

    /** Squared horizontal distance to another settlement (cheap, no sqrt in hot loops). */
    public double distanceSqTo(Settlement other) {
        double dx = this.x - other.x;
        double dz = this.z - other.z;
        return dx * dx + dz * dz;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Settlement s && s.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
