package dev.autociv.scenario;

import com.mojang.logging.LogUtils;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.world.WorldSimulation;
import dev.autociv.simulation.world.WorldSimulationManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.slf4j.Logger;

/** Builds a capital only after its chunk is naturally loaded, never by requesting a remote chunk. */
public final class SettlementChunkGenerator {

    private static final Logger LOGGER = LogUtils.getLogger();

    private SettlementChunkGenerator() {
    }

    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level != level.getServer().overworld()) {
            return;
        }
        MinecraftServer server = level.getServer();
        ChunkPos chunkPos = event.getChunk().getPos();
        server.tell(new TickTask(0, () -> generateChunkCapitals(server, level, chunkPos)));
    }

    public static void generateAlreadyLoadedCapitals(MinecraftServer server) {
        ServerLevel level = server.overworld();
        WorldSimulation simulation = WorldSimulationManager.get(server).simulation();
        for (Settlement settlement : simulation.settlements()) {
            if (level.hasChunkAt(settlement.x(), settlement.z())) {
                generate(server, level, simulation, settlement);
            }
        }
    }

    /** Materializes just one city when its chunk is already present, without touching other settlements. */
    public static boolean generateIfLoaded(MinecraftServer server, Settlement settlement) {
        ServerLevel level = server.overworld();
        WorldSimulation simulation = WorldSimulationManager.get(server).simulation();
        if (!level.hasChunkAt(settlement.x(), settlement.z())) return false;
        Civilization civilization = simulation.civilization(settlement.civilizationId()).orElse(null);
        if (civilization == null || !HistoricSettlementBuilder.generateAtStoredPosition(level, settlement,
                civilization.architectureStyle())) return false;
        WorldSimulationManager.get(server).flushChange();
        return settlement.physicalSettlementGenerated();
    }

    private static void generateChunkCapitals(MinecraftServer server, ServerLevel level, ChunkPos chunkPos) {
        WorldSimulation simulation = WorldSimulationManager.get(server).simulation();
        for (Settlement settlement : simulation.settlementsInChunk(chunkPos.x, chunkPos.z)) {
            generate(server, level, simulation, settlement);
        }
    }

    private static void generate(MinecraftServer server, ServerLevel level,
                                 WorldSimulation simulation, Settlement settlement) {
        Civilization civilization = simulation.civilization(settlement.civilizationId()).orElse(null);
        if (civilization == null || !level.hasChunkAt(settlement.x(), settlement.z())) {
            return;
        }
        if ("elder_village".equals(civilization.scenarioId()) && !settlement.physicalSettlementGenerated()) return;
        if (HistoricSettlementBuilder.generateInChunk(level, settlement, civilization.architectureStyle())) {
            WorldSimulationManager.get(server).flushChange();
            LOGGER.info("[autociv-worldgen] Generated grounded '{}' for {} in naturally loaded chunk at {},{},{}",
                    settlement.name(), civilization.name(), settlement.x(), settlement.y(), settlement.z());
        }
    }
}
