package dev.autociv.simulation.world;

import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.debug.CitizenAiStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.extensions.IEntityExtension;

import java.util.EnumSet;
import java.util.UUID;

/** Concrete server-side work cycle for tagged farmer, miner and lumberjack Villagers. */
public final class CitizenWorkGoal extends Goal {

    private enum JobSite { FARM, MINE, WOODCUTTING }

    private final Villager villager;
    private final UUID citizenId;
    private final UUID settlementId;
    private Citizen.Profession profession;
    private BlockPos target;
    private boolean returningCargo;
    private long workStarted;
    private long nextSearchTick;

    public CitizenWorkGoal(Villager villager, UUID citizenId, UUID settlementId,
                           Citizen.Profession profession) {
        this.villager = villager;
        this.citizenId = citizenId;
        this.settlementId = settlementId;
        this.profession = profession;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || level.getGameTime() < nextSearchTick) {
            return false;
        }
        Citizen citizen = WorldSimulationManager.get(level.getServer()).simulation().citizen(citizenId).orElse(null);
        if (citizen == null || !isPhysicalWorkJob(citizen.profession())) {
            nextSearchTick = level.getGameTime() + 40;
            return false;
        }
        if (!isDaytime(level)) {
            nextSearchTick = level.getGameTime() + 40;
            return false;
        }
        profession = citizen.profession();
        if (profession == Citizen.Profession.MINER) {
            villager.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE));
        }
        Settlement settlement = settlement(level);
        if (settlement == null) {
            report("ПОСЕЛЕНИЕ НЕ НАЙДЕНО", null, level);
            return false;
        }
        returningCargo = citizen.inventoryWeight() >= citizen.inventoryCapacity() - 4.0
                || citizen.itemCargoCount() >= citizen.itemCargoCapacity() - 4;
        if (returningCargo) {
            target = TownHallStorage.nearestStoragePosition(level, settlement, villager.blockPosition());
            report("НЕСЁТ ГРУЗ В СКЛАДСКОЕ КРЫЛО", target, level);
            return true;
        }
        target = findWorkTarget(level, settlement);
        if (target == null) {
            if (!citizen.inventory().isEmpty() || citizen.itemCargoCount() > 0) {
                returningCargo = true;
                target = TownHallStorage.nearestStoragePosition(level, settlement, villager.blockPosition());
                report("НЕСЁТ ПОСЛЕДНИЙ ГРУЗ НА СКЛАД", target, level);
                return true;
            }
            nextSearchTick = level.getGameTime() + 80;
            report("ЖДЁТ: НЕТ ЦЕЛИ", null, level);
            return false;
        }
        report("ИДЁТ К РАБОТЕ", target, level);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!villager.isAlive() || target == null || !(villager.level() instanceof ServerLevel level)
                || level.getGameTime() - workStarted >= 1200) {
            return false;
        }
        return WorldSimulationManager.get(level.getServer()).simulation().citizen(citizenId)
                .map(citizen -> citizen.profession() == profession).orElse(false);
    }

    @Override
    public void start() {
        workStarted = villager.level().getGameTime();
        if (villager.level() instanceof ServerLevel level) {
            report(returningCargo ? "НЕСЁТ ГРУЗ НА СКЛАД" : "ИДЁТ К РАБОТЕ", target, level);
        }
        moveToWorksite();
    }

    @Override
    public void stop() {
        BlockPos stoppedTarget = target;
        villager.getNavigation().stop();
        target = null;
        if (villager.level() instanceof ServerLevel level) {
            nextSearchTick = level.getGameTime() + 80;
            if (stoppedTarget != null && villager.isAlive()) {
                report("ПАУЗА", stoppedTarget, level);
            }
        }
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level) || target == null) {
            return;
        }
        if (returningCargo) {
            deliverCargo(level);
            return;
        }
        if (!isDaytime(level)) {
            report("РАБОЧИЙ ДЕНЬ ОКОНЧЕН", target, level);
            target = null;
            return;
        }
        if (!level.hasChunkAt(target) || !isWorkTarget(level.getBlockState(target))) {
            target = findWorkTarget(level, settlement(level));
            if (target == null) {
                report("ЖДЁТ: НЕТ ЦЕЛИ", null, level);
                return;
            }
            moveToWorksite();
        }
        villager.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (villager.distanceToSqr(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 6.25) {
            if (villager.getNavigation().isDone()) {
                moveToWorksite();
            }
            return;
        }
        report("РАБОТАЕТ", target, level);
        report(performWork(level), target, level);
        target = null;
    }

    private void moveToWorksite() {
        if (target != null) {
            villager.getNavigation().moveTo(target.getX() + 1.5, target.getY(), target.getZ() + 0.5, 0.8);
        }
    }

    private BlockPos findWorkTarget(ServerLevel level, Settlement settlement) {
        if (settlement == null) {
            return null;
        }
        int centerX = settlement.x();
        int centerZ = settlement.z();
        return switch (profession) {
            case FARMER -> findBlock(level, centerX - 3, centerX + 1,
                    centerZ + 6, centerZ + 7, new BlockPos(centerX, settlement.y() + 1, centerZ),
                    JobSite.FARM);
            case MINER -> findBlock(level, centerX - 11, centerX + 1,
                    centerZ - 5, centerZ + 8, new BlockPos(centerX - 5, settlement.y() + 1, centerZ),
                    JobSite.MINE);
            case LUMBERJACK -> findBlock(level, centerX + 6, centerX + 6,
                    centerZ + 1, centerZ + 1, new BlockPos(centerX + 6, settlement.y() + 1, centerZ + 1),
                    JobSite.WOODCUTTING);
            default -> null;
        };
    }

    private BlockPos findBlock(ServerLevel level, int minX, int maxX, int minZ, int maxZ,
                               BlockPos preferred, JobSite site) {
        for (int yOffset = 0; yOffset < 4; yOffset++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos candidate = new BlockPos(x, preferred.getY() + yOffset, z);
                    if (level.hasChunkAt(candidate) && isWorkTarget(level.getBlockState(candidate), site)) {
                        return candidate;
                    }
                }
            }
        }
        if (level.hasChunkAt(preferred) && isWorkTarget(level.getBlockState(preferred), site)) {
            return preferred;
        }
        return null;
    }

    private boolean isWorkTarget(BlockState state) {
        return switch (profession) {
            case FARMER -> state.is(Blocks.WHEAT) && state.getValue(CropBlock.AGE) == CropBlock.MAX_AGE;
            case MINER -> isIronOre(state) || isCoalOre(state)
                    || state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.DEEPSLATE);
            case LUMBERJACK -> state.is(BlockTags.LOGS);
            default -> false;
        };
    }

    private boolean isWorkTarget(BlockState state, JobSite site) {
        return switch (site) {
            case FARM -> state.is(Blocks.WHEAT) && state.getValue(CropBlock.AGE) == CropBlock.MAX_AGE;
            case MINE -> isIronOre(state) || isCoalOre(state)
                    || state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.DEEPSLATE);
            case WOODCUTTING -> state.is(BlockTags.LOGS);
        };
    }

    private String performWork(ServerLevel level) {
        WorldSimulationManager manager = WorldSimulationManager.get(level.getServer());
        var simulation = manager.simulation();
        var citizen = simulation.citizen(citizenId).orElse(null);
        Settlement settlement = simulation.settlement(settlementId).orElse(null);
        if (citizen == null || settlement == null || !level.hasChunkAt(target)) return "ЦЕЛЬ ПОТЕРЯНА";
        ResourceType expectedResource = switch (profession) {
            case FARMER -> ResourceType.FOOD;
            case MINER -> isIronOre(level.getBlockState(target)) ? ResourceType.IRON
                    : isCoalOre(level.getBlockState(target)) ? ResourceType.COAL : ResourceType.STONE;
            case LUMBERJACK -> ResourceType.WOOD;
            default -> null;
        };
        if (expectedResource == null) return "НЕТ ЗАДАЧИ";
        if (citizen.inventoryCapacity() - citizen.inventoryWeight() < 4.0) return "РЮКЗАК ПОЛОН";
        ResourceType harvested;
        double yield;
        int physicalCount;
        Item physicalItem;
        if (profession == Citizen.Profession.FARMER) {
            physicalItem = Items.WHEAT;
            physicalCount = 3;
            if (citizen.itemCargoCapacity() - citizen.itemCargoCount() < physicalCount) {
                return "РЮКЗАК С ПРЕДМЕТАМИ ПОЛОН";
            }
            if (!level.setBlock(target, Blocks.WHEAT.defaultBlockState(), 3)) return "ЦЕЛЬ ПОТЕРЯНА";
            harvested = ResourceType.FOOD;
            yield = 4.0;
        } else if (profession == Citizen.Profession.MINER) {
            BlockState ore = level.getBlockState(target);
            boolean iron = isIronOre(ore);
            boolean coal = isCoalOre(ore);
            physicalItem = iron ? Items.RAW_IRON : coal ? Items.COAL : Items.COBBLESTONE;
            physicalCount = coal ? 2 : 1;
            if (citizen.itemCargoCapacity() - citizen.itemCargoCount() < physicalCount) {
                return "РЮКЗАК С ПРЕДМЕТАМИ ПОЛОН";
            }
            if (!level.destroyBlock(target, false, villager, 512)) return "ЦЕЛЬ ПОТЕРЯНА";
            harvested = iron ? ResourceType.IRON : coal ? ResourceType.COAL : ResourceType.STONE;
            yield = iron ? 3.0 : 4.0;
        } else {
            // The generated lumber yard grows the local palette tree at x+6,z+1.
            BlockPos ground = new BlockPos(settlement.x() + 6, settlement.y(), settlement.z() + 1);
            java.util.List<BlockPos> trunk = new java.util.ArrayList<>();
            physicalItem = null;
            for (int y = settlement.y() + 4; y >= settlement.y() + 1; y--) {
                BlockPos log = new BlockPos(ground.getX(), y, ground.getZ());
                BlockState state = level.getBlockState(log);
                if (state.is(BlockTags.LOGS)) {
                    trunk.add(log);
                    physicalItem = state.getBlock().asItem();
                }
            }
            if (trunk.isEmpty() || physicalItem == null) return "ЦЕЛЬ ПОТЕРЯНА";
            physicalCount = trunk.size();
            if (citizen.itemCargoCapacity() - citizen.itemCargoCount() < physicalCount) {
                return "РЮКЗАК С ПРЕДМЕТАМИ ПОЛОН";
            }
            int harvestedLogs = 0;
            for (BlockPos log : trunk) {
                if (level.destroyBlock(log, false, villager, 512)) harvestedLogs++;
            }
            if (harvestedLogs == 0) return "ЦЕЛЬ НЕ УДАЛОСЬ СРУБИТЬ";
            physicalCount = harvestedLogs;
            level.setBlock(ground, Blocks.DIRT.defaultBlockState(), 3);
            level.setBlock(ground.above(), saplingFor(physicalItem).defaultBlockState(), 3);
            harvested = ResourceType.WOOD;
            yield = physicalCount;
        }
        String itemId = BuiltInRegistries.ITEM.getKey(physicalItem).toString();
        int acceptedItems = citizen.carryItem(itemId, physicalCount);
        if (acceptedItems != physicalCount) {
            // Capacity was reserved before the world block changed; guard against corrupt cargo state.
            return "ОШИБКА РЮКЗАКА: ПРЕДМЕТ НЕ СОХРАНЁН";
        }
        double carried = citizen.carry(harvested, yield);
        manager.flushChange();
        return carried > 0 ? "ДОБЫЧА " + physicalCount + "×" + itemId + " · РЮКЗАК "
                + (int) citizen.inventoryWeight() + "/16" : "РЮКЗАК ПОЛОН";
    }

    private void deliverCargo(ServerLevel level) {
        Settlement settlement = settlement(level);
        Citizen citizen = WorldSimulationManager.get(level.getServer()).simulation()
                .citizen(citizenId).orElse(null);
        if (settlement == null || citizen == null || target == null || !level.hasChunkAt(target)) return;
        if (villager.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) > 9.0) {
            if (villager.getNavigation().isDone()) {
                report("НЕСЁТ ГРУЗ НА СКЛАД", target, level);
                villager.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.85);
            }
            return;
        }
        report("СДАЁТ ГРУЗ", target, level);
        var warehouse = TownHallStorage.containers(level, settlement, villager.blockPosition());
        if (warehouse.isEmpty()) {
            report("СКЛАД ГОРОДА НЕ ДОСТУПЕН", target, level);
            return;
        }
        java.util.Set<ResourceType> physicallyBacked = new java.util.HashSet<>();
        citizen.itemCargo().keySet().forEach(id -> cargoResource(id).ifPresent(physicallyBacked::add));
        var itemDeliveries = citizen.unloadItemCargo((itemId, amount) -> {
            ResourceLocation id = ResourceLocation.tryParse(itemId);
            Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
            if (item == Items.AIR) return 0;
            ResourceType linked = TownHallStorage.resourceFor(item);
            if (linked != null && citizen.inventory().containsKey(linked)
                    && settlement.stockpile().capacity(linked) - settlement.stockpile().get(linked)
                    < citizen.inventory().get(linked)) return 0;
            return TownHallStorage.storeItem(warehouse, new ItemStack(item, amount));
        });
        var capacity = new java.util.HashMap<ResourceType, Double>();
        citizen.inventory().keySet().forEach(resource -> capacity.put(resource,
                physicallyBacked.contains(resource)
                        ? hasCargoFor(citizen, resource) ? 0.0
                                : Math.max(0.0, settlement.stockpile().capacity(resource)
                                        - settlement.stockpile().get(resource))
                        : Math.min(TownHallStorage.freeSpace(TownHallStorage.containersFor(level, settlement,
                                villager.blockPosition(), resource), resource),
                                Math.max(0.0, settlement.stockpile().capacity(resource)
                                        - settlement.stockpile().get(resource)))));
        var delivered = citizen.unloadInventory(settlement.stockpile(), capacity);
        delivered.forEach((resource, amount) -> {
            if (!physicallyBacked.contains(resource)) TownHallStorage.store(
                    TownHallStorage.containersFor(level, settlement, villager.blockPosition(), resource),
                    resource, (int) Math.floor(amount));
        });
        delivered.forEach(settlement.stockpile()::recordDelta);
        WorldSimulationManager.get(level.getServer()).flushChange();
        returningCargo = citizen.inventoryWeight() > 0 || citizen.itemCargoCount() > 0;
        report(returningCargo ? "СКЛАД ЗАПОЛНЕН: ГРУЗ ОСТАЛСЯ"
                : itemDeliveries.isEmpty() && delivered.isEmpty() ? "ГРУЗ НЕ ПРИНЯТ СКЛАДОМ"
                : "ГРУЗ СДАН В ОБЩИЙ СКЛАД", target, level);
        target = null;
        nextSearchTick = level.getGameTime() + 60;
    }

    private static boolean isDaytime(ServerLevel level) {
        long dayTime = level.getDayTime() % 24000L;
        return dayTime < 12000L;
    }

    private Settlement settlement(ServerLevel level) {
        return WorldSimulationManager.get(level.getServer()).simulation()
                .settlement(settlementId).orElse(null);
    }

    private void report(String state, BlockPos position, ServerLevel level) {
        CitizenAiStatus.report(citizenId, profession.name(), state, position, level.getGameTime());
    }

    private static boolean isPhysicalWorkJob(Citizen.Profession profession) {
        return profession == Citizen.Profession.FARMER || profession == Citizen.Profession.MINER
                || profession == Citizen.Profession.LUMBERJACK;
    }

    private net.minecraft.world.level.block.Block saplingFor(Item harvestedLog) {
        if (harvestedLog == Blocks.SPRUCE_LOG.asItem()) return Blocks.SPRUCE_SAPLING;
        if (harvestedLog == Blocks.BIRCH_LOG.asItem()) return Blocks.BIRCH_SAPLING;
        if (harvestedLog == Blocks.JUNGLE_LOG.asItem()) return Blocks.JUNGLE_SAPLING;
        if (harvestedLog == Blocks.ACACIA_LOG.asItem()) return Blocks.ACACIA_SAPLING;
        if (harvestedLog == Blocks.DARK_OAK_LOG.asItem()) return Blocks.DARK_OAK_SAPLING;
        if (harvestedLog == Blocks.MANGROVE_LOG.asItem()) return Blocks.MANGROVE_PROPAGULE;
        if (harvestedLog == Blocks.CHERRY_LOG.asItem()) return Blocks.CHERRY_SAPLING;
        return Blocks.OAK_SAPLING;
    }

    private java.util.Optional<ResourceType> cargoResource(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) return java.util.Optional.empty();
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
        return java.util.Optional.ofNullable(item == Items.AIR ? null : TownHallStorage.resourceFor(item));
    }

    private static boolean isIronOre(BlockState state) {
        return state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE);
    }

    private static boolean isCoalOre(BlockState state) {
        return state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE);
    }

    private boolean hasCargoFor(Citizen citizen, ResourceType resource) {
        return citizen.itemCargo().keySet().stream().map(this::cargoResource)
                .flatMap(java.util.Optional::stream).anyMatch(resource::equals);
    }
}
