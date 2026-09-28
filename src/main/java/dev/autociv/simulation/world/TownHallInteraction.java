package dev.autociv.simulation.world;

import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.extensions.IEntityExtension;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** The town-hall bell opens its shared chest and accepts resources held by the player. */
public final class TownHallInteraction {

    private static final String FAVOR_PREFIX = "autocivFavor_";

    private TownHallInteraction() { }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos clicked = event.getPos();
        boolean bellClick = level.getBlockState(clicked).is(Blocks.BELL);
        boolean chestClick = level.getBlockState(clicked).is(Blocks.CHEST);
        if (!bellClick && !chestClick) return;
        WorldSimulationManager manager = WorldSimulationManager.get(level.getServer());
        Settlement settlement = manager.simulation().settlementsInChunk(clicked.getX() >> 4, clicked.getZ() >> 4).stream()
                .filter(city -> city.physicalSettlementGenerated())
                .filter(city -> {
                    return bellClick ? clicked.distSqr(new BlockPos(city.x(), city.y() + 1, city.z())) <= 1.0
                            : TownHallStorage.isStoragePosition(city, clicked);
                })
                .min(java.util.Comparator.comparingDouble(city -> clicked.distSqr(bellClick
                        ? new BlockPos(city.x(), city.y() + 1, city.z())
                        : TownHallStorage.closestStoragePosition(city, clicked))))
                .orElse(null);
        if (settlement == null) return;
        event.setCanceled(true);

        // Clicking either the town-hall bell or its shared chest opens the same city panel.
        if (chestClick) {
            openWarehouse(player, level, settlement);
            return;
        }

        ItemStack held = player.getMainHandItem();
        ResourceType resource = TownHallStorage.resourceFor(held.getItem());
        if (!held.isEmpty() && held.is(net.minecraft.world.item.Items.TORCH)) {
            var storage = TownHallStorage.containers(level, settlement, clicked);
            int storedTorches = storage.isEmpty() ? 0 : TownHallStorage.storeItem(storage, held.copy());
            if (storedTorches <= 0) {
                player.sendSystemMessage(Component.literal("В общем сундуке нет места для факелов.")
                        .withStyle(ChatFormatting.RED));
                openWarehouse(player, level, settlement);
                return;
            }
            if (!player.getAbilities().instabuild) held.shrink(storedTorches);
            double standing = increaseFavor(player, settlement, storedTorches);
            manager.flushChange();
            player.sendSystemMessage(Component.literal("В ратушу передано факелов: " + storedTorches
                    + " | доверие жителей " + String.format(java.util.Locale.ROOT, "%.1f", standing) + "/100")
                    .withStyle(ChatFormatting.GREEN));
            openWarehouse(player, level, settlement);
            return;
        }
        if (held.isEmpty()) {
            player.sendSystemMessage(Component.literal("Общий склад города. Положи или забери предметы прямо в этом сундуке."
                    + " Чтобы пожертвовать ресурсы и повысить доверие, держи их в руке и нажми колокол.")
                    .withStyle(ChatFormatting.YELLOW));
            openWarehouse(player, level, settlement);
            return;
        }
        if (resource == null) {
            player.sendSystemMessage(Component.literal("Этот предмет нельзя пожертвовать. Открываю общий сундук;"
                    + " можно положить ресурсы вручную.").withStyle(ChatFormatting.YELLOW));
            openWarehouse(player, level, settlement);
            return;
        }
        var storage = TownHallStorage.containersFor(level, settlement, clicked, resource);
        if (storage.isEmpty()) {
            player.sendSystemMessage(Component.literal("Общий сундук ратуши не найден.")
                    .withStyle(ChatFormatting.RED));
            openWarehouse(player, level, settlement);
            return;
        }
        double stockpileRoom = settlement.stockpile().capacity(resource) - settlement.stockpile().get(resource);
        int offered = Math.min(held.getCount(), Math.max(0, (int) Math.floor(stockpileRoom)));
        int stored = TownHallStorage.store(storage, resource, offered);
        if (stored <= 0) {
            player.sendSystemMessage(Component.literal("В общем сундуке нет места для этого ресурса.")
                    .withStyle(ChatFormatting.RED));
            openWarehouse(player, level, settlement);
            return;
        }
        if (!player.getAbilities().instabuild) held.shrink(stored);
        double added = settlement.stockpile().add(resource, stored);
        settlement.stockpile().recordDelta(resource, added);
        double favor = increaseFavor(player, settlement, stored);

        Civilization civilization = manager.simulation().civilization(settlement.civilizationId()).orElse(null);
        if (civilization != null) {
            civilization.recordEvent("year " + manager.simulation().year() + ": " + player.getGameProfile().getName()
                    + " donated " + stored + " " + resource.id() + " to " + settlement.name() + ".");
        }
        manager.flushChange();
        int donated = stored;
        double standing = favor;
        player.sendSystemMessage(Component.literal("Сдано в общий склад " + settlement.name() + ": " + donated
                + " ед. " + resource.id() + " | доверие жителей "
                + String.format(java.util.Locale.ROOT, "%.1f", standing) + "/100")
                .withStyle(ChatFormatting.GREEN));
        openWarehouse(player, level, settlement);
    }

    private static void openWarehouse(ServerPlayer player, ServerLevel level, Settlement settlement) {
        dev.autociv.debug.TownHallScreenPayload.openFor(player,
                WorldSimulationManager.get(level.getServer()).simulation(), settlement);
    }

    /** Deposits the selected resource from inventory through the town hall screen. */
    public static int donateFromInventory(ServerPlayer player, Settlement settlement, ResourceType resource) {
        if (!(player.level() instanceof ServerLevel level) || settlement == null
                || !settlement.physicalSettlementGenerated()) return 0;
        BlockPos bell = new BlockPos(settlement.x(), settlement.y() + 1, settlement.z());
        if (!level.hasChunkAt(bell) || player.distanceToSqr(bell.getX() + 0.5, bell.getY(), bell.getZ() + 0.5) > 100.0) {
            player.sendSystemMessage(Component.literal("Подойди ближе к ратуше, чтобы передать ресурсы.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        var storage = TownHallStorage.containersFor(level, settlement, bell, resource);
        if (storage.isEmpty()) {
            player.sendSystemMessage(Component.literal("Склад города пока недоступен.").withStyle(ChatFormatting.RED));
            return 0;
        }
        int stockRoom = Math.max(0, (int) Math.floor(settlement.stockpile().capacity(resource)
                - settlement.stockpile().get(resource)));
        int offered = Math.min(stockRoom, TownHallStorage.freeSpace(storage, resource));
        int inInventory = player.getInventory().items.stream()
                .filter(stack -> !stack.isEmpty() && TownHallStorage.resourceFor(stack.getItem()) == resource)
                .mapToInt(ItemStack::getCount).sum();
        offered = Math.min(offered, inInventory);
        int stored = TownHallStorage.store(storage, resource, offered);
        if (stored <= 0) {
            player.sendSystemMessage(Component.literal("Нет подходящих ресурсов или места на складе.")
                    .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        int remaining = stored;
        for (ItemStack stack : player.getInventory().items) {
            if (remaining <= 0) break;
            if (stack.isEmpty() || TownHallStorage.resourceFor(stack.getItem()) != resource) continue;
            int removed = Math.min(remaining, stack.getCount());
            if (!player.getAbilities().instabuild) stack.shrink(removed);
            remaining -= removed;
        }
        double added = settlement.stockpile().add(resource, stored);
        settlement.stockpile().recordDelta(resource, added);
        double standing = increaseFavor(player, settlement, stored);
        WorldSimulationManager manager = WorldSimulationManager.get(level.getServer());
        manager.simulation().civilization(settlement.civilizationId()).ifPresent(civ -> civ.recordEvent(
                "year " + manager.simulation().year() + ": " + player.getGameProfile().getName()
                        + " donated " + stored + " " + resource.id() + " to " + settlement.name() + "."));
        manager.flushChange();
        player.sendSystemMessage(Component.literal("В общий склад передано: " + stored + " — доверие "
                + String.format(java.util.Locale.ROOT, "%.1f", standing) + "/100")
                .withStyle(ChatFormatting.GREEN));
        return stored;
    }

    public static double favor(ServerPlayer player, Settlement settlement) {
        return ((IEntityExtension) player).getPersistentData()
                .getDouble(FAVOR_PREFIX + settlement.civilizationId());
    }

    private static double increaseFavor(ServerPlayer player, Settlement settlement, int amount) {
        String key = FAVOR_PREFIX + settlement.civilizationId();
        var data = ((IEntityExtension) player).getPersistentData();
        double favor = Math.clamp(data.getDouble(key) + Math.min(2.0, amount * 0.05), 0.0, 100.0);
        data.putDouble(key, favor);
        return favor;
    }
}
