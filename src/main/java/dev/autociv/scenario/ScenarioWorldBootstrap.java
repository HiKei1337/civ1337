package dev.autociv.scenario;

import com.mojang.logging.LogUtils;
import dev.autociv.simulation.world.WorldSimulation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.List;
import java.util.Random;

/** Creates abstract civilization starts without loading or generating their chunks. */
public final class ScenarioWorldBootstrap {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FIRST_CITY_DISTANCE = 640;
    private static final int DISTANCE_STEP = 760;
    private static final int DISTANCE_VARIANCE = 320;

    private ScenarioWorldBootstrap() {
    }

    public static boolean initialize(MinecraftServer server, WorldSimulation simulation) {
        if (!simulation.civilizations().isEmpty()) {
            return false;
        }

        BlockPos spawn = server.overworld().getSharedSpawnPos();
        BlockPos playerAnchor = server.getPlayerList().getPlayers().stream()
                .filter(player -> player.serverLevel() == server.overworld())
                .map(player -> player.blockPosition())
                .findFirst().orElse(spawn);
        int selection = server.overworld().getGameRules()
                .getRule(CivilizationScenarioGameRules.SCENARIO_ID).get();
        CivilizationScenario selected = CivilizationScenarioGameRules.bySelectionId(selection);
        List<CivilizationScenario> profiles = ScenarioComposition.choose(server.overworld().getSeed(), selected);
        Random placement = new Random(server.overworld().getSeed() ^ 0x6A09E667F3BCC909L);
        double phase = placement.nextDouble() * Math.PI * 2.0;

        for (int index = 0; index < profiles.size(); index++) {
            CivilizationScenario profile = profiles.get(index);
            double angle = phase + Math.PI * 2.0 * index / profiles.size();
            int distance = FIRST_CITY_DISTANCE + index * DISTANCE_STEP
                    + placement.nextInt(DISTANCE_VARIANCE);
            int x = spawn.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = spawn.getZ() + (int) Math.round(Math.sin(angle) * distance);
            double dx = x - playerAnchor.getX();
            double dz = z - playerAnchor.getZ();
            int distanceFromPlayer = (int) Math.sqrt(dx * dx + dz * dz);
            ScenarioInitializer.initialize(simulation, profile,
                    List.of(new ScenarioInitializer.SettlementSite(x, 0, z)), distanceFromPlayer);
        }

        ScenarioDiplomacy.establishMutualAwareness(simulation);
        LOGGER.info("[autociv-worldgen] Registered {} civilizations and abstract capitals at varied distances "
                + "({}-{} blocks from spawn); no terrain chunks were requested",
                profiles.size(), FIRST_CITY_DISTANCE, FIRST_CITY_DISTANCE
                        + (profiles.size() - 1) * DISTANCE_STEP + DISTANCE_VARIANCE - 1);
        return true;
    }

}
