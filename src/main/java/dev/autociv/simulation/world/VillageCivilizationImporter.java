package dev.autociv.simulation.world;

import dev.autociv.simulation.model.BuildingType;
import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.extensions.IEntityExtension;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.ArrayList;
import java.util.List;

/** Adopts pre-existing vanilla villages as mature, autonomous city-states. */
public final class VillageCivilizationImporter {

    private static final int CLUSTER_RADIUS = 48;
    private static final String CITIZEN_TAG = "autocivCitizen";
    private static final String SETTLEMENT_TAG = "autocivSettlement";
    private static final String VILLAGE_SCENARIO = "elder_village";

    private VillageCivilizationImporter() { }

    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Villager villager)
                || !(event.getLevel() instanceof ServerLevel level)
                || level != level.getServer().overworld()) return;
        var entityData = ((IEntityExtension) villager).getPersistentData();
        if (entityData.hasUUID(CITIZEN_TAG)) return;

        WorldSimulationManager manager = WorldSimulationManager.get(level.getServer());
        WorldSimulation simulation = manager.simulation();
        Settlement city = simulation.settlementsNear(villager.blockPosition().getX(),
                        villager.blockPosition().getZ(), CLUSTER_RADIUS * 2).stream()
                .filter(candidate -> simulation.civilization(candidate.civilizationId())
                        .map(civilization -> VILLAGE_SCENARIO.equals(civilization.scenarioId())).orElse(false))
                .min(java.util.Comparator.comparingDouble(candidate -> {
                    double dx = candidate.x() - villager.getX();
                    double dz = candidate.z() - villager.getZ();
                    return dx * dx + dz * dz;
                }))
                .orElse(null);
        List<Villager> cluster = nearbyUnclaimed(level, villager);
        if (city == null && cluster.size() < 2) return;
        if (city == null) {
            city = foundVillageCity(level, simulation, cluster, villager);
        }
        for (Villager resident : cluster) adopt(simulation, city, resident);
        adopt(simulation, city, villager);
        manager.flushChange();
    }

    private static List<Villager> nearbyUnclaimed(ServerLevel level, Villager origin) {
        var box = new AABB(origin.getX() - CLUSTER_RADIUS, origin.getY() - 16, origin.getZ() - CLUSTER_RADIUS,
                origin.getX() + CLUSTER_RADIUS, origin.getY() + 16, origin.getZ() + CLUSTER_RADIUS);
        List<Villager> cluster = new ArrayList<>(level.getEntitiesOfClass(Villager.class, box,
                candidate -> !((IEntityExtension) candidate).getPersistentData().hasUUID(CITIZEN_TAG)));
        if (!cluster.contains(origin)) cluster.add(origin);
        return cluster;
    }

    private static Settlement foundVillageCity(ServerLevel level, WorldSimulation simulation,
                                               List<Villager> residents, Villager origin) {
        BlockPos center = findVillageBell(level, origin.blockPosition());
        if (center == null) center = origin.blockPosition();
        Civilization civilization = simulation.createCivilization("Старожилы_" + center.getX() + "_" + center.getZ(),
                0x7B6D43);
        civilization.setScenario(VILLAGE_SCENARIO, "established", simulation.year(), "generic");
        civilization.addToTreasury(2_000.0 + residents.size() * 250.0);
        civilization.setMilitaryStrength(20.0 + residents.size() * 3.0);
        Settlement city = simulation.createSettlement(civilization.id(), "Старый_город_" + center.getX() + "_" + center.getZ(),
                center.getX(), center.getY(), center.getZ());
        int population = Math.max(2, residents.size());
        // Treat each established villager as an occupied home; future growth
        // must first be backed by another completed house.
        city.setHousingCapacity(population);
        city.setSecurity(0.8);
        city.setHappiness(0.8);
        city.stockpile().add(ResourceType.FOOD, 1_000 + population * 20.0);
        city.stockpile().add(ResourceType.WOOD, 1_000 + population * 10.0);
        city.stockpile().add(ResourceType.STONE, 1_000 + population * 8.0);
        city.stockpile().add(ResourceType.IRON, 250);
        city.addBuilding(BuildingType.FARM, 1);
        city.addBuilding(BuildingType.MINE, 1);
        city.addBuilding(BuildingType.MARKET, 1);
        city.addBuilding(BuildingType.WAREHOUSE, 1);
        city.addBuilding(BuildingType.FORTIFICATION, 1);
        city.setPhysicalSettlementGenerated(TownHallStorage.ensureChestAtTownHall(level, city));
        // Existing vanilla houses already serve these residents; represent their capacity in the model.
        city.setHomeBuildingState(population, population, 0);
        return city;
    }

    private static BlockPos findVillageBell(ServerLevel level, BlockPos origin) {
        BlockPos nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (int dx = -32; dx <= 32; dx++) {
            for (int dz = -32; dz <= 32; dz++) {
                for (int dy = -4; dy <= 8; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(Blocks.BELL)) continue;
                    long distance = (long) dx * dx + (long) dz * dz + (long) dy * dy;
                    if (distance < nearestDistance) {
                        nearest = pos;
                        nearestDistance = distance;
                    }
                }
            }
        }
        return nearest == null ? null : nearest.below();
    }

    private static void adopt(WorldSimulation simulation, Settlement city, Villager villager) {
        if (city == null || !villager.isAlive()) return;
        var data = ((IEntityExtension) villager).getPersistentData();
        if (data.hasUUID(CITIZEN_TAG)) return;
        Citizen citizen = simulation.addCitizen(city, villager.hasCustomName()
                ? villager.getCustomName().getString() : "Житель_" + villager.getUUID().toString().substring(0, 6),
                20, profession(villager));
        citizen.bindEntity(villager.getUUID());
        data.putUUID(CITIZEN_TAG, citizen.id());
        data.putUUID(SETTLEMENT_TAG, city.id());
        ResidentAi.attachResident(villager, (ServerLevel) villager.level(), citizen.id(), city.id());
    }

    private static Citizen.Profession profession(Villager villager) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        if (profession == VillagerProfession.FARMER) {
            return Citizen.Profession.FARMER;
        }
        if (profession == VillagerProfession.FISHERMAN) return Citizen.Profession.FISHERMAN;
        if (profession == VillagerProfession.SHEPHERD) return Citizen.Profession.SHEPHERD;
        if (profession == VillagerProfession.BUTCHER) return Citizen.Profession.HUNTER;
        if (profession == VillagerProfession.FLETCHER || profession == VillagerProfession.LEATHERWORKER) {
            return Citizen.Profession.LUMBERJACK;
        }
        if (profession == VillagerProfession.MASON) return Citizen.Profession.MINER;
        if (profession == VillagerProfession.TOOLSMITH) return Citizen.Profession.BLACKSMITH;
        if (profession == VillagerProfession.WEAPONSMITH) return Citizen.Profession.SOLDIER;
        if (profession == VillagerProfession.ARMORER) return Citizen.Profession.GUARD;
        if (profession == VillagerProfession.CLERIC) return Citizen.Profession.DOCTOR;
        if (profession == VillagerProfession.LIBRARIAN) return Citizen.Profession.RESEARCHER;
        if (profession == VillagerProfession.CARTOGRAPHER) return Citizen.Profession.MERCHANT;
        return Citizen.Profession.UNASSIGNED;
    }
}
