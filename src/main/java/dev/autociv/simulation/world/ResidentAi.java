package dev.autociv.simulation.world;

import dev.autociv.simulation.model.Citizen;
import dev.autociv.debug.CitizenAiStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.common.extensions.IEntityExtension;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.UUID;

/** Installs persistent job-specific AI when a civilization Villager enters a loaded level. */
public final class ResidentAi {

    private static final String CITIZEN_TAG = "autocivCitizen";
    private static final String SETTLEMENT_TAG = "autocivSettlement";
    /** Goal selectors are runtime-only; remember entity instances weakly, never in saved NBT. */
    private static final Map<Villager, Boolean> ATTACHED_ENTITIES = new WeakHashMap<>();

    private ResidentAi() {
    }

    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Villager villager)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        var data = ((IEntityExtension) villager).getPersistentData();
        if (!data.hasUUID(CITIZEN_TAG)) {
            return;
        }

        UUID citizenId = data.getUUID(CITIZEN_TAG);
        attachResident(villager, level, citizenId,
                data.hasUUID(SETTLEMENT_TAG) ? data.getUUID(SETTLEMENT_TAG) : null);
    }

    public static void attachResident(Villager villager, ServerLevel level, UUID citizenId, UUID requestedSettlementId) {
        WorldSimulation simulation = WorldSimulationManager.get(level.getServer()).simulation();
        Citizen citizen = simulation.citizen(citizenId).orElse(null);
        if (citizen == null) {
            villager.discard();
            return;
        }
        if (ATTACHED_ENTITIES.put(villager, Boolean.TRUE) != null) {
            return;
        }
        UUID settlementId = requestedSettlementId == null ? citizen.homeSettlementId() : requestedSettlementId;
        var civilization = simulation.settlement(settlementId)
                .flatMap(settlement -> simulation.civilization(settlement.civilizationId())).orElse(null);
        CitizenAiStatus.bind(citizenId, villager, citizen.profession().name(),
                civilization == null ? "" : civilization.name(),
                civilization == null ? 0xFFFFFF : civilization.color());
        villager.goalSelector.addGoal(1, new CitizenRestGoal(villager, citizenId, settlementId));
        villager.goalSelector.addGoal(2, new CitizenGuardGoal(villager, citizenId, settlementId));
        villager.goalSelector.addGoal(3, new CitizenBuildGoal(villager, citizenId, settlementId));
        villager.goalSelector.addGoal(4, new CitizenCraftGoal(villager, citizenId, settlementId));
        villager.goalSelector.addGoal(5, new CitizenWorkGoal(villager, citizenId,
                settlementId, citizen.profession()));
        villager.goalSelector.addGoal(6, new CitizenTorchGoal(villager, citizenId, settlementId));
    }
}
