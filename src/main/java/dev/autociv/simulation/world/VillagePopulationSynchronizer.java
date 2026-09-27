package dev.autociv.simulation.world;

import com.mojang.logging.LogUtils;
import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.extensions.IEntityExtension;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Materializes a small, tagged Villager sample only around players. */
public final class VillagePopulationSynchronizer {

    public static final int MAX_PHYSICAL_VILLAGERS_PER_CITY = 16;
    public static final int MAX_GUARD_GOLEMS_PER_CITY = 2;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CITIZEN_TAG = "autocivCitizen";
    private static final String GUARD_CITY_TAG = "autocivGuardCity";
    // Include a cleanup buffer beyond MID so loaded city entities are returned to
    // their abstract records before the player leaves the settlement behind.
    private static final double ACTIVE_RADIUS_SQUARED = 192.0 * 192.0;
    private static final double ENTITY_SCAN_RADIUS = 36.0;

    private VillagePopulationSynchronizer() {
    }

    private record NearbySettlement(Settlement settlement, SimulationLod lod) { }

    public static int desiredVillagerCount(int abstractPopulation) {
        return Math.max(0, Math.min(abstractPopulation, MAX_PHYSICAL_VILLAGERS_PER_CITY));
    }

    public static void synchronize(MinecraftServer server, WorldSimulation simulation) {
        ServerLevel level = server.overworld();
        java.util.Map<UUID, NearbySettlement> nearby = new java.util.HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.serverLevel() != level) continue;
            for (Settlement settlement : simulation.settlementsNear(player.blockPosition().getX(),
                    player.blockPosition().getZ(), (int) Math.sqrt(ACTIVE_RADIUS_SQUARED))) {
                double dx = settlement.x() - player.getX();
                double dz = settlement.z() - player.getZ();
                SimulationLod lod = SimulationLod.atDistanceSquared(dx * dx + dz * dz);
                nearby.merge(settlement.id(), new NearbySettlement(settlement, lod), (first, second) ->
                        first.lod().physicalCitizenBudget() >= second.lod().physicalCitizenBudget()
                                ? first : second);
            }
        }
        for (NearbySettlement active : nearby.values()) {
            synchronizeSettlement(level, simulation, active.settlement(), active.lod());
            synchronizeGuards(level, active.settlement(), active.lod());
        }
    }

    private static void synchronizeSettlement(ServerLevel level, WorldSimulation simulation,
                                              Settlement settlement, SimulationLod lod) {
        AABB area = settlementArea(level, settlement);
        Set<UUID> representedCitizens = new HashSet<>();
        java.util.List<Villager> physicalCitizens = level.getEntitiesOfClass(Villager.class, area,
                entity -> {
                    var data = ((IEntityExtension) entity).getPersistentData();
                    return data.hasUUID(CITIZEN_TAG) && (!data.hasUUID("autocivSettlement")
                            || data.getUUID("autocivSettlement").equals(settlement.id()));
                });
        for (Villager villager : physicalCitizens) {
            UUID citizenId = ((IEntityExtension) villager).getPersistentData().getUUID(CITIZEN_TAG);
            if (simulation.citizen(citizenId).isEmpty()) {
                dev.autociv.debug.CitizenAiStatus.forget(citizenId);
                villager.discard();
            } else {
                representedCitizens.add(citizenId);
                simulation.citizen(citizenId).ifPresent(citizen -> {
                    citizen.bindEntity(villager.getUUID());
                    citizen.setWorkplaceSettlementId(settlement.id());
                    var persistent = ((IEntityExtension) villager).getPersistentData();
                    String currentProfession = professionKey(villager);
                    String previousProfession = persistent.getString("autocivVanillaProfession");
                    if (previousProfession.isEmpty()) {
                        persistent.putString("autocivVanillaProfession", currentProfession);
                    } else if (!previousProfession.equals(currentProfession)) {
                        Citizen.Profession job = customProfessionFor(villager.getVillagerData().getProfession());
                        if (job != null) {
                            citizen.setProfession(job);
                            persistent.putString("autocivProfession", job.name());
                            persistent.putString("autocivVanillaProfession", currentProfession);
                            dev.autociv.debug.CitizenAiStatus.bind(citizenId, villager, job.name());
                            WorldSimulationManager.get(level.getServer()).flushChange();
                        }
                    }
                    simulation.settlement(citizen.homeSettlementId())
                            .flatMap(home -> simulation.civilization(home.civilizationId()))
                            .ifPresent(civilization -> dev.autociv.debug.CitizenAiStatus.setCivilization(
                                    citizenId, civilization.name(), civilization.color()));
                });
            }
        }

        int desired = Math.min(desiredVillagerCount(settlement.population()), lod.physicalCitizenBudget());
        for (Villager villager : physicalCitizens) {
            if (representedCitizens.size() <= desired) break;
            UUID citizenId = ((IEntityExtension) villager).getPersistentData().getUUID(CITIZEN_TAG);
            if (!representedCitizens.remove(citizenId)) continue;
            simulation.citizen(citizenId).ifPresent(Citizen::unbindEntity);
            dev.autociv.debug.CitizenAiStatus.forget(citizenId);
            villager.discard();
        }
        int spawned = 0;
        int index = 0;
        for (UUID citizenId : settlement.citizenIds()) {
            if (representedCitizens.size() >= desired) {
                break;
            }
            Citizen citizen = simulation.citizen(citizenId).orElse(null);
            if (citizen == null || representedCitizens.contains(citizenId)) {
                continue;
            }
            Villager villager = EntityType.VILLAGER.create(level);
            if (villager == null) {
                continue;
            }
            BlockPos position = spawnPosition(level, settlement, index++);
            villager.moveTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5,
                    level.random.nextFloat() * 360.0F, 0.0F);
            villager.setVillagerData(new VillagerData(VillagerType.PLAINS,
                    professionFor(citizen.profession()), 1));
            villager.setCustomName(Component.literal(citizen.name()));
            var persistentData = ((IEntityExtension) villager).getPersistentData();
            persistentData.putUUID(CITIZEN_TAG, citizen.id());
            persistentData.putUUID("autocivSettlement", settlement.id());
            persistentData.putString("autocivProfession", citizen.profession().name());
            persistentData.putString("autocivVanillaProfession", professionKey(villager));
            simulation.civilization(settlement.civilizationId()).ifPresent(civilization ->
                    dev.autociv.debug.CitizenAiStatus.setCivilization(citizen.id(),
                            civilization.name(), civilization.color()));
            villager.setPersistenceRequired();
            if (level.addFreshEntity(villager)) {
                citizen.bindEntity(villager.getUUID());
                citizen.setWorkplaceSettlementId(settlement.id());
                representedCitizens.add(citizenId);
                spawned++;
            }
        }
        if (spawned > 0) {
            LOGGER.info("[autociv-entities] Materialized {}/{} Villagers for city '{}' (abstract population={})",
                    representedCitizens.size(), desired, settlement.name(), settlement.population());
        }
    }

    private static void synchronizeGuards(ServerLevel level, Settlement settlement, SimulationLod lod) {
        AABB area = settlementArea(level, settlement);
        java.util.List<IronGolem> golems = level.getEntitiesOfClass(IronGolem.class, area,
                golem -> ((IEntityExtension) golem).getPersistentData().hasUUID(GUARD_CITY_TAG)
                        && ((IEntityExtension) golem).getPersistentData().getUUID(GUARD_CITY_TAG)
                        .equals(settlement.id()));
        int guardBudget = Math.min(MAX_GUARD_GOLEMS_PER_CITY, lod.physicalGuardBudget());
        for (int index = guardBudget; index < golems.size(); index++) golems.get(index).discard();
        for (int guardIndex = golems.size(); guardIndex < guardBudget; guardIndex++) {
            IronGolem golem = EntityType.IRON_GOLEM.create(level);
            if (golem == null) {
                continue;
            }
            int x = settlement.x() + (guardIndex == 0 ? -11 : 11);
            int z = settlement.z() + 11;
            BlockPos position = safeSpawnPosition(level, x, z);
            golem.moveTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5, 0.0F, 0.0F);
            golem.setPlayerCreated(true);
            golem.setPersistenceRequired();
            ((IEntityExtension) golem).getPersistentData().putUUID(GUARD_CITY_TAG, settlement.id());
            if (level.addFreshEntity(golem)) {
                LOGGER.info("[autociv-entities] Spawned city guard golem for '{}' ({}/{})",
                        settlement.name(), guardIndex + 1, MAX_GUARD_GOLEMS_PER_CITY);
            }
        }
    }

    private static AABB settlementArea(ServerLevel level, Settlement settlement) {
        return new AABB(settlement.x() - ENTITY_SCAN_RADIUS, level.getMinBuildHeight(),
                settlement.z() - ENTITY_SCAN_RADIUS, settlement.x() + ENTITY_SCAN_RADIUS,
                level.getMaxBuildHeight(), settlement.z() + ENTITY_SCAN_RADIUS);
    }

    private static BlockPos spawnPosition(ServerLevel level, Settlement settlement, int index) {
        int[][] offsets = {{-4, -3}, {4, -3}, {-4, 3}, {4, 3}, {0, -5}, {0, 5},
                {-8, 0}, {8, 0}, {-8, -6}, {8, -6}, {-8, 6}, {8, 6},
                {-12, 0}, {12, 0}, {0, -12}, {0, 12}};
        int[] offset = offsets[index % offsets.length];
        return safeSpawnPosition(level, settlement.x() + offset[0], settlement.z() + offset[1]);
    }

    private static BlockPos safeSpawnPosition(ServerLevel level, int centerX, int centerZ) {
        int[][] directions = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, 1}, {-1, -1}, {1, -1}};
        for (int radius = 0; radius <= 16; radius += 2) {
            for (int[] direction : directions) {
                int x = centerX + direction[0] * radius;
                int z = centerZ + direction[1] * radius;
                BlockPos feet = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        new BlockPos(x, 0, z));
                if (!level.hasChunkAt(feet) || !level.getBlockState(feet).isAir()
                        || !level.getFluidState(feet).isEmpty()
                        || level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()
                        || !level.getBlockState(feet.above()).isAir()) continue;
                return feet;
            }
        }
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(centerX, 0, centerZ));
    }

    private static VillagerProfession professionFor(Citizen.Profession profession) {
        return switch (profession) {
            case FARMER -> VillagerProfession.FARMER;
            case SHEPHERD -> VillagerProfession.SHEPHERD;
            case FISHERMAN -> VillagerProfession.FISHERMAN;
            case HUNTER -> VillagerProfession.BUTCHER;
            case LUMBERJACK -> VillagerProfession.FLETCHER;
            case MINER, BUILDER, ENGINEER -> VillagerProfession.MASON;
            case BLACKSMITH -> VillagerProfession.TOOLSMITH;
            case MERCHANT -> VillagerProfession.CARTOGRAPHER;
            case SOLDIER, GUARD -> VillagerProfession.WEAPONSMITH;
            case RESEARCHER -> VillagerProfession.LIBRARIAN;
            case DOCTOR -> VillagerProfession.CLERIC;
            case UNASSIGNED -> VillagerProfession.NONE;
        };
    }

    private static String professionKey(Villager villager) {
        return String.valueOf(BuiltInRegistries.VILLAGER_PROFESSION.getKey(
                villager.getVillagerData().getProfession()));
    }

    private static Citizen.Profession customProfessionFor(VillagerProfession profession) {
        if (profession == VillagerProfession.FARMER) return Citizen.Profession.FARMER;
        if (profession == VillagerProfession.FISHERMAN) return Citizen.Profession.FISHERMAN;
        if (profession == VillagerProfession.SHEPHERD) return Citizen.Profession.SHEPHERD;
        if (profession == VillagerProfession.FLETCHER || profession == VillagerProfession.LEATHERWORKER) {
            return Citizen.Profession.LUMBERJACK;
        }
        if (profession == VillagerProfession.BUTCHER) return Citizen.Profession.HUNTER;
        if (profession == VillagerProfession.MASON) return Citizen.Profession.MINER;
        if (profession == VillagerProfession.TOOLSMITH) return Citizen.Profession.BLACKSMITH;
        if (profession == VillagerProfession.WEAPONSMITH) return Citizen.Profession.SOLDIER;
        if (profession == VillagerProfession.ARMORER) return Citizen.Profession.GUARD;
        if (profession == VillagerProfession.CLERIC) return Citizen.Profession.DOCTOR;
        if (profession == VillagerProfession.LIBRARIAN) return Citizen.Profession.RESEARCHER;
        if (profession == VillagerProfession.CARTOGRAPHER) return Citizen.Profession.MERCHANT;
        return null;
    }
}
