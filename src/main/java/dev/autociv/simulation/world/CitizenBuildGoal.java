package dev.autociv.simulation.world;

import dev.autociv.scenario.HistoricSettlementBuilder;
import dev.autociv.debug.CitizenAiStatus;
import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Builder job: claims one settlement project and places its blueprint one block at a time. */
public final class CitizenBuildGoal extends Goal {

    private static final Map<UUID, UUID> ACTIVE_BUILDERS = new HashMap<>();

    private final Villager villager;
    private final UUID citizenId;
    private final UUID settlementId;
    private long nextPlacementTick;
    private long nextSearchTick;

    public CitizenBuildGoal(Villager villager, UUID citizenId, UUID settlementId) {
        this.villager = villager;
        this.citizenId = citizenId;
        this.settlementId = settlementId;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || level.getGameTime() < nextSearchTick) {
            return false;
        }
        if (!isBuilder(level) || !hasPendingWork(level)) {
            nextSearchTick = level.getGameTime() + 40;
            return false;
        }
        if (ACTIVE_BUILDERS.containsKey(settlementId)) {
            nextSearchTick = level.getGameTime() + 20;
            return false;
        }
        ACTIVE_BUILDERS.put(settlementId, citizenId);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return villager.isAlive() && villager.level() instanceof ServerLevel level
                && citizenId.equals(ACTIVE_BUILDERS.get(settlementId))
                && isBuilder(level) && hasPendingWork(level);
    }

    @Override
    public void start() {
        nextPlacementTick = 0;
        report("ИДЁТ К ПЛОЩАДКЕ", null);
        moveToSite();
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        ACTIVE_BUILDERS.remove(settlementId, citizenId);
        if (villager.level() instanceof ServerLevel level) {
            report(hasPendingWork(level) ? "СТРОЙКА ПРИОСТАНОВЛЕНА" : "НЕТ АКТИВНОЙ СТРОЙКИ", null);
        }
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level)) {
            return;
        }
        Settlement settlement = settlement(level);
        if (settlement == null) {
            return;
        }
        if (level.getGameTime() < nextPlacementTick) return;
        boolean home = hasPendingHome(settlement);
        BlockPos site = workPosition(level, settlement);
        if (site == null) {
            report("ОЖИДАЕТ: НЕТ ЗАГРУЖЕННОЙ ПЛОЩАДКИ", null);
            nextPlacementTick = level.getGameTime() + 40;
            return;
        }
        villager.getLookControl().setLookAt(site.getX() + 0.5, site.getY() + 1.0, site.getZ() + 0.5);
        if (villager.distanceToSqr(site.getX() + 0.5, site.getY(), site.getZ() + 0.5) > 9.0) {
            if (villager.getNavigation().isDone()) {
                report("ИДЁТ К ПЛОЩАДКЕ", site);
                moveToSite();
            }
            return;
        }
        WorldSimulation simulation = WorldSimulationManager.get(level.getServer()).simulation();
        Civilization civilization = simulation.civilization(settlement.civilizationId()).orElse(null);
        if (civilization != null) {
            var warehouse = TownHallStorage.containers(level, settlement, site);
            if (warehouse.isEmpty()) {
                report("ЖДЁТ СКЛАД ГОРОДА", site);
                nextPlacementTick = level.getGameTime() + 100;
                return;
            }
            Citizen.Profession residentProfession = Citizen.Profession.UNASSIGNED;
            if (home && !settlement.citizenIds().isEmpty()) {
                int homeIndex = Math.min(settlement.materializedHomes(), settlement.citizenIds().size() - 1);
                Citizen resident = simulation.citizen(settlement.citizenIds().get(homeIndex)).orElse(null);
                if (resident != null) residentProfession = resident.profession();
            }
            Item required = home
                    ? HistoricSettlementBuilder.nextHomeMaterial(level, settlement, civilization.architectureStyle(),
                            residentProfession, civilization.historicalEra())
                    : HistoricSettlementBuilder.nextFacilityMaterial(level, settlement, civilization.architectureStyle());
            if (required != null && !TownHallStorage.hasItem(warehouse, required, 1)) {
                String demand = BuiltInRegistries.ITEM.getKey(required).toString();
                if (!demand.equals(settlement.buildingMaterialDemand())) {
                    settlement.setBuildingMaterialDemand(demand);
                    WorldSimulationManager.get(level.getServer()).flushChange();
                }
                report("ЖДЁТ БЛОК: " + required.getDescription().getString().toUpperCase(java.util.Locale.ROOT), site);
                nextPlacementTick = level.getGameTime() + 40;
                return;
            }
            if (settlement.buildingMaterialDemand() != null) {
                settlement.setBuildingMaterialDemand(null);
                WorldSimulationManager.get(level.getServer()).flushChange();
            }
            boolean placed = home
                    ? HistoricSettlementBuilder.buildNextHomeBlock(level, settlement, civilization.architectureStyle(),
                            residentProfession, civilization.historicalEra(), warehouse)
                    : HistoricSettlementBuilder.buildNextFacilityBlock(level, settlement,
                            civilization.architectureStyle(), warehouse);
            if (placed) {
                if (home) {
                    report(settlement.materializedHomes() < settlement.completedHomes()
                            ? "СТРОИТ ДОМ " + settlement.homeBuildProgressPercent() + "%" : "ДОМ ГОТОВ", site);
                } else {
                    report(settlement.nextPhysicalBuilding() == null ? "ОБЪЕКТ ГОТОВ"
                            : "СТРОИТ " + settlement.nextPhysicalBuilding().type() + " "
                                    + HistoricSettlementBuilder.facilityBuildProgressPercent(settlement) + "%", site);
                }
                WorldSimulationManager.get(level.getServer()).flushChange();
            } else {
                report("ОЖИДАЕТ: ПЛОЩАДКА ЗАНЯТА ИЛИ НЕ ЗАГРУЖЕНА", site);
            }
        }
        nextPlacementTick = level.getGameTime() + 5;
    }

    private void moveToSite() {
        if (!(villager.level() instanceof ServerLevel level)) {
            return;
        }
        Settlement settlement = settlement(level);
        if (settlement != null) {
            BlockPos site = workPosition(level, settlement);
            if (site == null) return;
            villager.getNavigation().moveTo(site.getX() + 0.5, site.getY(), site.getZ() + 0.5, 0.85);
        }
    }

    private boolean isBuilder(ServerLevel level) {
        return WorldSimulationManager.get(level.getServer()).simulation().citizen(citizenId)
                .map(citizen -> citizen.profession() == Citizen.Profession.BUILDER).orElse(false);
    }

    private boolean hasPendingHome(ServerLevel level) {
        Settlement settlement = settlement(level);
        return settlement != null && hasPendingHome(settlement);
    }

    private boolean hasPendingHome(Settlement settlement) {
        return settlement != null && settlement.physicalSettlementGenerated()
                && settlement.materializedHomes() < settlement.completedHomes();
    }

    private boolean hasPendingWork(ServerLevel level) {
        Settlement settlement = settlement(level);
        return settlement != null && settlement.physicalSettlementGenerated()
                && (hasPendingHome(settlement) || settlement.nextPhysicalBuilding() != null);
    }

    private BlockPos workPosition(ServerLevel level, Settlement settlement) {
        if (hasPendingHome(settlement)) return HistoricSettlementBuilder.nextHomeWorkPosition(settlement);
        return HistoricSettlementBuilder.nextFacilityWorkPosition(level, settlement);
    }

    private Settlement settlement(ServerLevel level) {
        return WorldSimulationManager.get(level.getServer()).simulation()
                .settlement(settlementId).orElse(null);
    }

    private void report(String state, BlockPos target) {
        if (villager.level() instanceof ServerLevel level) {
            CitizenAiStatus.report(citizenId, Citizen.Profession.BUILDER.name(), state, target,
                    level.getGameTime());
        }
    }
}
