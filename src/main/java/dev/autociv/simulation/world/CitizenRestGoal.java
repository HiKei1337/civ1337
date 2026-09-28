package dev.autociv.simulation.world;

import dev.autociv.debug.CitizenAiStatus;
import dev.autociv.scenario.HistoricSettlementBuilder;
import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;

import java.util.EnumSet;
import java.util.UUID;

/** Sends non-guard residents to a generated bed during the Minecraft night. */
public final class CitizenRestGoal extends Goal {

    private final Villager villager;
    private final UUID citizenId;
    private final UUID settlementId;
    private BlockPos bed;
    private long nextPathTick;
    private long nextSleepRecoveryTick;

    public CitizenRestGoal(Villager villager, UUID citizenId, UUID settlementId) {
        this.villager = villager;
        this.citizenId = citizenId;
        this.settlementId = settlementId;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive() || !isNight(level)) return false;
        Citizen citizen = WorldSimulationManager.get(level.getServer()).simulation()
                .citizen(citizenId).orElse(null);
        if (citizen == null || isGuard(citizen.profession())) return false;
        bed = HistoricSettlementBuilder.residentBed(level, settlement(level), citizenId);
        return bed != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!villager.isAlive() || !(villager.level() instanceof ServerLevel level) || bed == null || !isNight(level)) {
            return false;
        }
        return WorldSimulationManager.get(level.getServer()).simulation().citizen(citizenId)
                .map(citizen -> !isGuard(citizen.profession())).orElse(false);
    }

    @Override
    public void start() {
        nextPathTick = 0;
        nextSleepRecoveryTick = 0;
        moveToBed();
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        if (villager.level() instanceof ServerLevel level && villager.isAlive()) {
            report("ОТДЫХ ЗАВЕРШЁН", null, level);
        }
        bed = null;
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level) || bed == null || !level.hasChunkAt(bed)
                || !level.getBlockState(bed).is(net.minecraft.world.level.block.Blocks.RED_BED)) {
            return;
        }
        if (villager.distanceToSqr(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5) > 3.0) {
            if (level.getGameTime() >= nextPathTick && villager.getNavigation().isDone()) moveToBed();
            report("ИДЁТ ДОМОЙ", bed, level);
        } else {
            villager.getNavigation().stop();
            if (level.getGameTime() >= nextSleepRecoveryTick) {
                WorldSimulationManager manager = WorldSimulationManager.get(level.getServer());
                manager.simulation().citizen(citizenId).ifPresent(citizen ->
                        citizen.setNeed(Citizen.Need.SLEEP, citizen.need(Citizen.Need.SLEEP) + 0.10));
                manager.flushChange();
                nextSleepRecoveryTick = level.getGameTime() + 100;
            }
            report("ОТДЫХАЕТ ДО УТРА", bed, level);
        }
    }

    private void moveToBed() {
        if (bed != null) {
            villager.getNavigation().moveTo(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5, 0.8);
            nextPathTick = villager.level().getGameTime() + 40;
        }
    }

    private Settlement settlement(ServerLevel level) {
        return WorldSimulationManager.get(level.getServer()).simulation().settlement(settlementId).orElse(null);
    }

    private void report(String state, BlockPos position, ServerLevel level) {
        CitizenAiStatus.report(citizenId, "REST", state, position, level.getGameTime());
    }

    private static boolean isNight(ServerLevel level) {
        long time = level.getDayTime() % 24000L;
        return time >= 12000L && time < 23000L;
    }

    private static boolean isGuard(Citizen.Profession profession) {
        return profession == Citizen.Profession.GUARD || profession == Citizen.Profession.SOLDIER;
    }
}
