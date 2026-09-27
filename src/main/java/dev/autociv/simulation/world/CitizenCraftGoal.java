package dev.autociv.simulation.world;

import dev.autociv.debug.CitizenAiStatus;
import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.CraftingOrder;
import dev.autociv.simulation.model.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** Engineer work: turn gathered ingredients into useful construction blocks in the town warehouse. */
public final class CitizenCraftGoal extends Goal {

    private record Order(Item item, int reserve) { }

    private static final List<Order> ORDERS = List.of(
            new Order(Items.OAK_PLANKS, 32), new Order(Items.COBBLESTONE_SLAB, 12),
            new Order(Items.COBBLESTONE_STAIRS, 8), new Order(Items.OAK_SLAB, 12),
            new Order(Items.OAK_STAIRS, 8), new Order(Items.COMPOSTER, 2),
            new Order(Items.CRAFTING_TABLE, 2), new Order(Items.CHEST, 4),
            new Order(Items.OAK_DOOR, 4), new Order(Items.OAK_FENCE, 12));

    private final Villager villager;
    private final UUID citizenId;
    private final UUID settlementId;
    private long retryAt;
    private long nextCraftAt;
    private boolean recipeUnavailable;

    public CitizenCraftGoal(Villager villager, UUID citizenId, UUID settlementId) {
        this.villager = villager;
        this.citizenId = citizenId;
        this.settlementId = settlementId;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level) || level.getGameTime() < retryAt
                || !isEngineer(level)) return false;
        Settlement city = settlement(level);
        return city != null && city.physicalSettlementGenerated()
                && (city.nextCraftingOrder() != null || hasMissingDemand(level, city)
                    || hasConstruction(city) && nextOrder(level, city) != null)
                && !TownHallStorage.containers(level, city).isEmpty();
    }

    @Override
    public boolean canContinueToUse() {
        if (!(villager.level() instanceof ServerLevel level) || recipeUnavailable || !villager.isAlive()
                || !isEngineer(level)) return false;
        Settlement city = settlement(level);
        return city != null && (city.nextCraftingOrder() != null
                || hasMissingDemand(level, city)
                || hasConstruction(city) && nextOrder(level, city) != null);
    }

    @Override
    public void start() {
        recipeUnavailable = false;
        nextCraftAt = 0;
        report("ИДЁТ В МАСТЕРСКУЮ");
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        retryAt = villager.level().getGameTime() + (recipeUnavailable ? 200 : 40);
        report("МАСТЕРСКАЯ: ОЖИДАНИЕ РЕСУРСОВ");
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level) || level.getGameTime() < nextCraftAt) return;
        Settlement city = settlement(level);
        if (city == null) return;
        BlockPos store = TownHallStorage.nearestStoragePosition(level, city, villager.blockPosition());
        if (!level.hasChunkAt(store)) {
            report("ЖДЁТ ЗАГРУЖЕННЫЙ СКЛАД");
            nextCraftAt = level.getGameTime() + 100;
            return;
        }
        villager.getLookControl().setLookAt(store.getX() + .5, store.getY() + .5, store.getZ() + .5);
        if (villager.distanceToSqr(store.getX() + .5, store.getY(), store.getZ() + .5) > 9) {
            if (villager.getNavigation().isDone()) villager.getNavigation().moveTo(
                    store.getX() + .5, store.getY(), store.getZ() + .5, .8);
            report("НЕСЁТ РЕСУРСЫ В МАСТЕРСКУЮ");
            return;
        }
        List<Container> containers = TownHallStorage.containers(level, city, store);
        CraftingOrder requested = city.nextCraftingOrder();
        if (requested != null) {
            ResourceLocation id = ResourceLocation.tryParse(requested.itemId());
            Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (!(item instanceof net.minecraft.world.item.BlockItem)) {
                city.removeCraftingOrder(0);
                WorldSimulationManager.get(level.getServer()).flushChange();
                report("ЗАКАЗ УДАЛЁН: НЕИЗВЕСТНЫЙ БЛОК");
                nextCraftAt = level.getGameTime() + 20;
                return;
            }
            report("ВЫПОЛНЯЕТ ЗАКАЗ: " + item.getDescription().getString().toUpperCase(java.util.Locale.ROOT));
            if (!TownHallStorage.craftBlock(level, containers, item, constructionReserves(city))) {
                recipeUnavailable = true;
                report("ЗАКАЗ ЖДЁТ ИНГРЕДИЕНТЫ ИЛИ МЕСТО НА СКЛАДЕ");
                return;
            }
            city.completeCraftingBatch();
            WorldSimulationManager.get(level.getServer()).flushChange();
            report("ПАРТИЯ ГОТОВА: " + item.getDescription().getString());
            nextCraftAt = level.getGameTime() + 80;
            return;
        }
        Item demanded = demandedItem(level, city);
        if (demanded != null) {
            int available = TownHallStorage.countItem(containers, demanded);
            if (available > 0) {
                // The builder consumes this stock directly; do not turn it into surplus elsewhere.
                nextCraftAt = level.getGameTime() + 20;
                report("НУЖНЫЙ БЛОК УЖЕ НА СКЛАДЕ: " + demanded.getDescription().getString().toUpperCase(java.util.Locale.ROOT));
                return;
            }
            report("ИЗГОТАВЛИВАЕТ ДЛЯ СТРОИТЕЛЯ: "
                    + demanded.getDescription().getString().toUpperCase(java.util.Locale.ROOT));
            if (TownHallStorage.craftBlock(level, containers, demanded)) {
                WorldSimulationManager.get(level.getServer()).flushChange();
                report("БЛОК ДЛЯ СТРОЙКИ ГОТОВ: " + demanded.getDescription().getString());
                nextCraftAt = level.getGameTime() + 40;
            } else {
                recipeUnavailable = true;
                report("СТРОЙКА ЖДЁТ " + demanded.getDescription().getString().toUpperCase(java.util.Locale.ROOT)
                        + " · ДОБЫТЬ ИЛИ ПОЛОЖИТЬ НА СКЛАД");
            }
            return;
        }
        for (Order order : ORDERS) {
            if (TownHallStorage.countItem(containers, order.item()) >= order.reserve()) continue;
            report("ИЗГОТАВЛИВАЕТ " + order.item().getDescription().getString().toUpperCase(java.util.Locale.ROOT));
            if (!TownHallStorage.craftBlock(level, containers, order.item(), constructionReserves(city))) continue;
            WorldSimulationManager.get(level.getServer()).flushChange();
            report("ГОТОВО: " + order.item().getDescription().getString());
            nextCraftAt = level.getGameTime() + 80;
            return;
        }
        recipeUnavailable = true;
        report("НЕТ ИНГРЕДИЕНТОВ ИЛИ МЕСТА НА СКЛАДЕ");
    }

    private Order nextOrder(ServerLevel level, Settlement city) {
        List<Container> storage = TownHallStorage.containers(level, city);
        for (Order order : ORDERS) {
            if (TownHallStorage.countItem(storage, order.item()) < order.reserve()) return order;
        }
        return null;
    }

    private Item demandedItem(ServerLevel level, Settlement city) {
        String id = city.buildingMaterialDemand();
        if (id == null) return null;
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
    }

    private boolean hasMissingDemand(ServerLevel level, Settlement city) {
        Item demanded = demandedItem(level, city);
        return demanded != null && TownHallStorage.countItem(TownHallStorage.containers(level, city), demanded) == 0;
    }

    private boolean isEngineer(ServerLevel level) {
        return WorldSimulationManager.get(level.getServer()).simulation().citizen(citizenId)
                .map(citizen -> citizen.profession() == Citizen.Profession.ENGINEER).orElse(false);
    }

    private Settlement settlement(ServerLevel level) {
        return WorldSimulationManager.get(level.getServer()).simulation().settlement(settlementId).orElse(null);
    }

    private boolean hasConstruction(Settlement city) {
        return city.materializedHomes() < city.completedHomes() || city.activeBuildingPlan() != null
                || !city.pendingPhysicalBuildings().isEmpty() || !city.buildingQueue().isEmpty();
    }

    private java.util.Map<Item, Integer> constructionReserves(Settlement city) {
        // Physical blueprints now reserve blocks one placement at a time; project costs remain abstract.
        return java.util.Map.of();
    }

    private void report(String state) {
        CitizenAiStatus.report(citizenId, "ENGINEER", state, null, villager.level().getGameTime());
    }
}
