package dev.autociv.simulation.world;

import dev.autociv.debug.CitizenAiStatus;
import dev.autociv.simulation.model.Citizen;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import dev.autociv.simulation.model.Settlement;

import java.util.EnumSet;
import java.util.Comparator;
import java.util.UUID;

/** Soldier citizens seek and melee-attack nearby hostile mobs while on defense duty. */
public final class CitizenGuardGoal extends Goal {

    private final Villager villager;
    private final UUID citizenId;
    private final UUID settlementId;
    private Monster target;
    private long nextAttackTick;
    private long nextPathTick;
    private long nextSearchTick;

    public CitizenGuardGoal(Villager villager, UUID citizenId, UUID settlementId) {
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
        if (!isGuard(level)) {
            nextSearchTick = level.getGameTime() + 40;
            return false;
        }
        if (!villager.getMainHandItem().is(net.minecraft.world.item.Items.IRON_SWORD)) {
            villager.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
        }
        report("НА ПОСТУ", null, level);
        Settlement settlement = settlement(level);
        if (settlement == null) {
            return false;
        }
        double x = settlement.x();
        double y = settlement.y();
        double z = settlement.z();
        double patrolRadius = 28.0 + Math.min(12.0, settlement.defense() * 8.0);
        target = level.getEntitiesOfClass(Monster.class,
                        new net.minecraft.world.phys.AABB(x - patrolRadius, y - 18.0, z - patrolRadius,
                                x + patrolRadius, y + 18.0, z + patrolRadius),
                        monster -> monster.isAlive() && isThreateningSettlement(monster, level, settlement))
                .stream().min(Comparator.comparingInt((Monster mob) -> isAttackingResident(mob) ? 0 : 1)
                        .thenComparingDouble(mob -> distanceToSettlement(mob, settlement)))
                .orElse(null);
        if (target != null) {
            report(isAttackingResident(target) ? "ЗАЩИЩАЕТ ЖИТЕЛЯ" : "ОБНАРУЖЕН ВРАГ", target.blockPosition(), level);
        } else {
            nextSearchTick = level.getGameTime() + 20;
        }
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (target == null || !target.isAlive() || !villager.isAlive()
                || !(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!isGuard(level)) {
            return false;
        }
        Settlement settlement = settlement(level);
        return settlement != null && distanceToSettlement(target, settlement) <= 32.0 * 32.0;
    }

    @Override
    public void start() {
        nextAttackTick = 0;
        nextPathTick = 0;
        if (target != null && villager.level() instanceof ServerLevel level) {
            report("ЗАЩИТА ПОСЕЛЕНИЯ", target.blockPosition(), level);
        }
    }

    @Override
    public void stop() {
        target = null;
        villager.getNavigation().stop();
        if (villager.level() instanceof ServerLevel level && isGuard(level)) {
            report("НА ПОСТУ", null, level);
        }
    }

    @Override
    public void tick() {
        if (target == null || !target.isAlive()) {
            return;
        }
        if (villager.level() instanceof ServerLevel level) {
            report(isAttackingResident(target) ? "ЗАЩИЩАЕТ ЖИТЕЛЯ" : "СРАЖАЕТСЯ", target.blockPosition(), level);
        }
        villager.getLookControl().setLookAt(target, 30.0F, 30.0F);
        long now = villager.level().getGameTime();
        if (villager.distanceToSqr(target) > 3.5 * 3.5) {
            if (now >= nextPathTick) {
                villager.getNavigation().moveTo(target, 1.05);
                nextPathTick = now + 10;
            }
        } else if (now >= nextAttackTick) {
            villager.doHurtTarget(target);
            nextAttackTick = now + 20;
        }
    }

    private boolean isThreateningSettlement(Monster monster, ServerLevel level, Settlement settlement) {
        double patrolRadius = 28.0 + Math.min(12.0, settlement.defense() * 8.0);
        return distanceToSettlement(monster, settlement) <= patrolRadius * patrolRadius
                && (isAttackingResident(monster) || monster.distanceToSqr(villager) <= 20.0 * 20.0);
    }

    private boolean isAttackingResident(Monster monster) {
        LivingEntity victim = monster.getTarget();
        return victim instanceof Villager resident && resident.isAlive()
                && resident.distanceToSqr(monster) <= 20.0 * 20.0;
    }

    private double distanceToSettlement(Monster monster, Settlement settlement) {
        double dx = monster.getX() - settlement.x();
        double dy = monster.getY() - settlement.y();
        double dz = monster.getZ() - settlement.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private Settlement settlement(ServerLevel level) {
        return WorldSimulationManager.get(level.getServer()).simulation()
                .settlement(settlementId).orElse(null);
    }

    private boolean isGuard(ServerLevel level) {
        return WorldSimulationManager.get(level.getServer()).simulation().citizen(citizenId)
                .map(citizen -> citizen.profession() == Citizen.Profession.GUARD
                        || citizen.profession() == Citizen.Profession.SOLDIER).orElse(false);
    }

    private void report(String state, net.minecraft.core.BlockPos target, ServerLevel level) {
        CitizenAiStatus.report(citizenId, Citizen.Profession.GUARD.name(), state, target, level.getGameTime());
    }
}
