package dev.autociv.simulation.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Abstract citizen (design doc section 3.3).
 * <p>
 * A Citizen is a pure simulation record - it does NOT require a Minecraft
 * Entity to exist. When the player approaches a city (Stage 7), physical
 * entities are spawned and bound to citizens via {@link #entityId}; when the
 * player leaves, entity data is folded back into this record.
 */
public final class Citizen {

    /** Finite set of professions per design doc section 7. Stage 1: data only. */
    public enum Profession {
        UNASSIGNED, FARMER, LUMBERJACK, MINER, FISHERMAN, HUNTER, SHEPHERD,
        BUILDER, ENGINEER, BLACKSMITH, MERCHANT, GUARD, SOLDIER, DOCTOR, RESEARCHER
    }

    /** Basic needs tracked per citizen; values 0..1 (1 = fully satisfied). Stage 2 will tick them. */
    public enum Need {
        FOOD, SLEEP, SAFETY, SOCIAL, COMFORT
    }

    private static final int MAX_AGE_YEARS = 80;

    private final UUID id;
    private String name;
    private UUID homeSettlementId;
    private UUID workplaceSettlementId;
    private double ageYears;
    private Profession profession;
    private double health;      // 0..1
    private double education;   // 0..1
    private double income;      // money/day, filled by economy in Stage 3
    private double mood;        // 0..1
    private final Map<Need, Double> needs = new EnumMap<>(Need.class);
    private final Map<ResourceType, Double> inventory = new java.util.HashMap<>();
    /** Exact Minecraft items carried for physical stock; separate from abstract economic cargo. */
    private final Map<String, Integer> itemCargo = new java.util.HashMap<>();
    private static final double INVENTORY_CAPACITY = 16.0;
    private final Map<String, Double> skills = new java.util.HashMap<>();
    /** UUIDs of family members (spouse/children) - resolved lazily, may dangle safely. */
    private final java.util.Set<UUID> family = new java.util.HashSet<>();
    /** Bound Minecraft entity id while physically simulated; null when abstract. */
    private UUID entityId;

    public Citizen(UUID id, String name, UUID homeSettlementId, double ageYears, Profession profession) {
        this.id = java.util.Objects.requireNonNull(id, "id");
        this.name = java.util.Objects.requireNonNull(name, "name");
        this.homeSettlementId = java.util.Objects.requireNonNull(homeSettlementId, "homeSettlementId");
        this.workplaceSettlementId = homeSettlementId;
        this.ageYears = clamp(ageYears, 0, MAX_AGE_YEARS);
        this.profession = profession == null ? Profession.UNASSIGNED : profession;
        this.health = 1.0;
        this.education = 0.0;
        this.income = 0.0;
        this.mood = 0.7;
        for (Need n : Need.values()) {
            needs.put(n, 1.0);
        }
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

    public UUID homeSettlementId() {
        return homeSettlementId;
    }

    public void setHomeSettlementId(UUID homeSettlementId) {
        this.homeSettlementId = homeSettlementId;
    }

    public UUID workplaceSettlementId() { return workplaceSettlementId; }

    public void setWorkplaceSettlementId(UUID workplaceSettlementId) {
        this.workplaceSettlementId = workplaceSettlementId == null ? homeSettlementId : workplaceSettlementId;
    }

    public double ageYears() {
        return ageYears;
    }

    public void advanceAge(double years) {
        this.ageYears = clamp(ageYears + years, 0, MAX_AGE_YEARS);
    }

    public void setAgeYears(double ageYears) { this.ageYears = clamp(ageYears, 0, MAX_AGE_YEARS); }

    public boolean isAdult() {
        return ageYears >= 16;
    }

    public boolean isElderly() {
        return ageYears >= 65;
    }

    public Profession profession() {
        return profession;
    }

    public void setProfession(Profession profession) {
        this.profession = profession == null ? Profession.UNASSIGNED : profession;
    }

    public double health() {
        return health;
    }

    public void setHealth(double health) {
        this.health = clamp(health, 0, 1);
    }

    public double education() {
        return education;
    }

    public void setEducation(double education) {
        this.education = clamp(education, 0, 1);
    }

    public double income() {
        return income;
    }

    public void setIncome(double income) {
        this.income = Math.max(0, income);
    }

    public double mood() {
        return mood;
    }

    public void setMood(double mood) {
        this.mood = clamp(mood, 0, 1);
    }

    public double need(Need need) {
        return needs.getOrDefault(need, 1.0);
    }

    public void setNeed(Need need, double value) {
        needs.put(need, clamp(value, 0, 1));
    }

    public Map<Need, Double> needs() {
        return java.util.Collections.unmodifiableMap(needs);
    }

    public void restoreNeeds(Map<Need, Double> restored) {
        restored.forEach(this::setNeed);
    }

    public Map<ResourceType, Double> inventory() {
        return java.util.Collections.unmodifiableMap(inventory);
    }

    public double inventoryCapacity() { return INVENTORY_CAPACITY; }

    public double inventoryWeight() {
        return inventory.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    public Map<String, Integer> itemCargo() { return java.util.Collections.unmodifiableMap(itemCargo); }

    public int itemCargoCount() { return itemCargo.values().stream().mapToInt(Integer::intValue).sum(); }

    public int itemCargoCapacity() { return (int) INVENTORY_CAPACITY; }

    public int carryItem(String itemId, int count) {
        if (itemId == null || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || count <= 0) return 0;
        int accepted = Math.min(count, Math.max(0, itemCargoCapacity() - itemCargoCount()));
        if (accepted > 0) itemCargo.merge(itemId, accepted, Integer::sum);
        return accepted;
    }

    public void restoreItemCargo(Map<String, Integer> restored) {
        itemCargo.clear();
        if (restored == null) return;
        restored.entrySet().stream()
                .filter(entry -> entry.getKey().length() <= 128
                        && entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                        && entry.getValue() != null && entry.getValue() > 0)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> carryItem(entry.getKey(), entry.getValue()));
    }

    /** Transfer exact item stacks to storage, retaining anything the receiver cannot accept. */
    public Map<String, Integer> unloadItemCargo(java.util.function.BiFunction<String, Integer, Integer> receiver) {
        Map<String, Integer> delivered = new java.util.HashMap<>();
        for (Map.Entry<String, Integer> entry : new java.util.ArrayList<>(itemCargo.entrySet())) {
            int accepted = Math.clamp(receiver.apply(entry.getKey(), entry.getValue()), 0, entry.getValue());
            if (accepted <= 0) continue;
            delivered.put(entry.getKey(), accepted);
            int remaining = entry.getValue() - accepted;
            if (remaining == 0) itemCargo.remove(entry.getKey());
            else itemCargo.put(entry.getKey(), remaining);
        }
        return Map.copyOf(delivered);
    }

    public double carry(ResourceType resource, double amount) {
        double accepted = Math.min(Math.max(0, amount), Math.max(0, INVENTORY_CAPACITY - inventoryWeight()));
        if (accepted > 0) inventory.merge(resource, accepted, Double::sum);
        return accepted;
    }

    /** Moves carried resources into the city's stockpile and leaves overflow in the citizen's pack. */
    public Map<ResourceType, Double> unloadInventory(ResourceStockpile stockpile) {
        return unloadInventory(stockpile, null);
    }

    /** Moves only units for which there is room in both the abstract stockpile and physical warehouse. */
    public Map<ResourceType, Double> unloadInventory(ResourceStockpile stockpile,
                                                     Map<ResourceType, Double> physicalCapacity) {
        Map<ResourceType, Double> delivered = new java.util.HashMap<>();
        for (Map.Entry<ResourceType, Double> entry : new java.util.ArrayList<>(inventory.entrySet())) {
            double requested = physicalCapacity == null ? entry.getValue()
                    : Math.min(entry.getValue(), physicalCapacity.getOrDefault(entry.getKey(), 0.0));
            double applied = stockpile.add(entry.getKey(), requested);
            if (applied > 0) {
                delivered.put(entry.getKey(), applied);
                double remaining = entry.getValue() - applied;
                if (remaining <= 1.0e-9) inventory.remove(entry.getKey());
                else inventory.put(entry.getKey(), remaining);
            }
        }
        return Map.copyOf(delivered);
    }

    public double skill(String skillId) {
        return skills.getOrDefault(skillId, 0.0);
    }

    public void setSkill(String skillId, double value) {
        skills.put(skillId, clamp(value, 0, 1));
    }

    public Map<String, Double> skills() {
        return java.util.Collections.unmodifiableMap(skills);
    }

    public java.util.Set<UUID> family() {
        return family;
    }

    public UUID entityId() {
        return entityId;
    }

    public void bindEntity(UUID entityId) {
        this.entityId = entityId;
    }

    public void unbindEntity() {
        this.entityId = null;
    }

    /** Citizen dies when health reaches zero. */
    public boolean isDead() {
        return health <= 0.0;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
