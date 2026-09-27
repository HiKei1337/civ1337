package dev.autociv.simulation.world;

import dev.autociv.debug.CitizenAiStatus;
import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Residents gradually light their homes and nearby naturally loaded chunks using warehouse torches. */
public final class CitizenTorchGoal extends Goal {

    private static final Map<UUID, UUID> ACTIVE_WORKERS = new ConcurrentHashMap<>();

    private final Villager villager;
    private final UUID citizenId;
    private final UUID settlementId;
    private BlockPos target;
    private boolean carryingTorch;
    private long nextSearchTick;

    public CitizenTorchGoal(Villager villager, UUID citizenId, UUID settlementId) {
        this.villager = villager;
        this.citizenId = citizenId;
        this.settlementId = settlementId;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || level.getGameTime() < nextSearchTick || ACTIVE_WORKERS.containsKey(settlementId)) return false;
        var simulation = WorldSimulationManager.get(level.getServer()).simulation();
        Citizen citizen = simulation.citizen(citizenId).orElse(null);
        Settlement settlement = simulation.settlement(settlementId).orElse(null);
        if (citizen == null || settlement == null || !settlement.physicalSettlementGenerated()) return false;
        var warehouse = TownHallStorage.containers(level, settlement, villager.blockPosition());
        if (warehouse.isEmpty()) return false;
        TownHallStorage.craftTorches(warehouse);
        target = findTarget(level, settlement);
        if (target == null || !TownHallStorage.hasItem(warehouse, Items.TORCH, 1)) {
            target = null;
            nextSearchTick = level.getGameTime() + 200;
            return false;
        }
        ACTIVE_WORKERS.put(settlementId, citizenId);
        carryingTorch = false;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return villager.isAlive() && target != null && villager.level() instanceof ServerLevel level
                && citizenId.equals(ACTIVE_WORKERS.get(settlementId));
    }

    @Override
    public void start() {
        moveToWarehouse();
        report("БЕРЁТ ФАКЕЛ СО СКЛАДА", nearestWarehouse());
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        ACTIVE_WORKERS.remove(settlementId, citizenId);
        if (villager.level() instanceof ServerLevel level) nextSearchTick = level.getGameTime() + 100;
        if (target != null) report("ОТЛОЖИЛ ФАКЕЛЬНУЮ РАБОТУ", target);
        target = null;
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level) || target == null) return;
        Settlement settlement = settlement();
        if (settlement == null || !level.hasChunkAt(target)) return;
        if (!carryingTorch) {
            BlockPos warehousePos = TownHallStorage.nearestStoragePosition(level, settlement,
                    villager.blockPosition());
            if (villager.distanceToSqr(warehousePos.getX() + 0.5, warehousePos.getY(),
                    warehousePos.getZ() + 0.5) > 9.0) {
                if (villager.getNavigation().isDone()) moveToWarehouse();
                return;
            }
            var warehouse = TownHallStorage.containers(level, settlement, warehousePos);
            if (warehouse.isEmpty() || !TownHallStorage.consumeItem(warehouse, Items.TORCH, 1)) {
                target = null;
                report("НЕТ ФАКЕЛОВ НА СКЛАДЕ", warehousePos);
                return;
            }
            carryingTorch = true;
            villager.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                    new net.minecraft.world.item.ItemStack(Items.TORCH));
            report("НЕСЁТ ФАКЕЛ К ДОМУ", target);
            villager.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.8);
            return;
        }
        if (villager.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) > 6.25) {
            if (villager.getNavigation().isDone()) {
                villager.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.8);
            }
            return;
        }
        if (isSafeTorchSite(level, target) && level.setBlock(target, Blocks.TORCH.defaultBlockState(), 3)) {
            report("ПОСТАВИЛ ФАКЕЛ", target);
        } else {
            report("МЕСТО ДЛЯ ФАКЕЛА ЗАНЯТО", target);
        }
        target = null;
    }

    private BlockPos findTarget(ServerLevel level, Settlement settlement) {
        // Prioritize house doors, then fill the grid around the village through already-loaded chunks.
        for (int house = 0; house < settlement.materializedHomes(); house++) {
            BlockPos site = dev.autociv.scenario.HistoricSettlementBuilder.torchHouseSite(settlement, house);
            BlockPos surface = surface(level, site.getX(), site.getZ());
            if (surface != null && isSafeTorchSite(level, surface)) return surface;
        }
        for (int dx = -24; dx <= 24; dx += 12) {
            for (int dz = -24; dz <= 24; dz += 12) {
                BlockPos surface = surface(level, settlement.x() + dx, settlement.z() + dz);
                if (surface != null && isSafeTorchSite(level, surface)) return surface;
            }
        }
        return null;
    }

    private BlockPos surface(ServerLevel level, int x, int z) {
        BlockPos feet = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos(x, 0, z));
        return level.hasChunkAt(feet) ? feet : null;
    }

    private boolean isSafeTorchSite(ServerLevel level, BlockPos feet) {
        return level.getBlockState(feet).isAir() && level.getFluidState(feet).isEmpty()
                && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)
                && level.getBlockState(feet.above()).isAir()
                && level.getBrightness(LightLayer.BLOCK, feet) < 8;
    }

    private void moveToWarehouse() {
        BlockPos pos = nearestWarehouse();
        if (pos == null) return;
        villager.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.8);
    }

    private BlockPos nearestWarehouse() {
        if (!(villager.level() instanceof ServerLevel level)) return null;
        Settlement settlement = settlement();
        return settlement == null ? null : TownHallStorage.nearestStoragePosition(level, settlement,
                villager.blockPosition());
    }

    private Settlement settlement() {
        if (!(villager.level() instanceof ServerLevel level)) return null;
        return WorldSimulationManager.get(level.getServer()).simulation().settlement(settlementId).orElse(null);
    }

    private void report(String state, BlockPos position) {
        if (villager.level() instanceof ServerLevel level) {
            CitizenAiStatus.report(citizenId, "TORCHER", state, position, level.getGameTime());
        }
    }
}
