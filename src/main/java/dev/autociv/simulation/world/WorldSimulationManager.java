package dev.autociv.simulation.world;

import com.mojang.logging.LogUtils;
import dev.autociv.persistence.SimulationSavedData;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/**
 * Server-side bridge between Minecraft lifecycle and the abstract simulation.
 * <p>
 * Owns the current {@link WorldSimulation}, wires it to {@link SimulationSavedData},
 * and advances abstract simulation days on the server thread at a configured cadence.
 * <p>
 * Accessed only from the server thread - no locking needed (Minecraft server
 * is effectively single-threaded for world state).
 */
public final class WorldSimulationManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static WorldSimulationManager instance;

    private final MinecraftServer server;
    private final SimulationSavedData savedData;
    private final SimulationEngine simulationEngine = new SimulationEngine();
    private final SimulationClock simulationClock = new SimulationClock();
    private int entitySyncElapsedTicks;
    private int debugDaysRemaining;
    private int debugYearsRequested;

    private static final int DEBUG_DAYS_PER_SERVER_TICK = 5;

    private WorldSimulationManager(MinecraftServer server, SimulationSavedData savedData) {
        this.server = server;
        this.savedData = savedData;
    }

    /** Called once on server start; loads existing data or creates fresh. */
    public static void attach(MinecraftServer server) {
        if (instance != null) {
            detach();
        }
        SimulationSavedData data = SimulationSavedData.get(server);
        instance = new WorldSimulationManager(server, data);
        LOGGER.info("[autociv] Simulation attached: {} civs, {} cities loaded",
                instance.simulation().civilizations().size(),
                instance.simulation().settlements().size());
    }

    public static void detach() {
        instance = null;
    }

    public static WorldSimulationManager get(MinecraftServer server) {
        if (instance == null || instance.server != server) {
            attach(server);
        }
        return instance;
    }

    /** Called from the server tick event; expensive work only runs at the selected interval. */
    public static void onServerTick(MinecraftServer server) {
        WorldSimulationManager manager = get(server);
        if (++manager.entitySyncElapsedTicks >= 100) {
            manager.entitySyncElapsedTicks = 0;
            VillagePopulationSynchronizer.synchronize(server, manager.simulation());
            FrontierOutpostRenderer.materializeLoaded(server.overworld(), manager.simulation());
        }
        if (manager.debugDaysRemaining > 0) {
            int days = Math.min(DEBUG_DAYS_PER_SERVER_TICK, manager.debugDaysRemaining);
            for (int day = 0; day < days; day++) {
                manager.simulationEngine.advanceDay(manager.simulation());
            }
            manager.debugDaysRemaining -= days;
            manager.savedData.markDirtySim();
            if (manager.debugDaysRemaining == 0) {
                LOGGER.info("[autociv] Debug time skip finished: {} years, year {}",
                        manager.debugYearsRequested, manager.simulation().year());
                manager.debugYearsRequested = 0;
                manager.simulationClock.reset();
            }
        } else if (manager.simulation().simulationSpeed() != SimulationSpeed.PAUSED
                && manager.simulationClock.advance(manager.simulation().simulationSpeed())) {
            manager.simulationEngine.advanceDay(manager.simulation());
            manager.savedData.markDirtySim();
            LOGGER.debug("[autociv] Simulated day {} at speed {}", manager.simulation().tickCount(),
                    manager.simulation().simulationSpeed());
        }
    }

    public WorldSimulation simulation() {
        return savedData.simulation();
    }

    /** Mark simulation dirty after any mutation done through game logic/commands. */
    public void flushChange() {
        savedData.markDirtySim();
    }

    public void setSimulationSpeed(SimulationSpeed speed) {
        if (speed == SimulationSpeed.PAUSED) {
            debugDaysRemaining = 0;
            debugYearsRequested = 0;
        }
        simulation().setSimulationSpeed(speed);
        simulationClock.reset();
        flushChange();
    }

    public void stepSimulationDay() {
        simulationEngine.advanceDay(simulation());
        simulationClock.reset();
        flushChange();
    }

    /** Start a server-thread paced debug time skip so normal entity AI can keep running. */
    public boolean startDebugYearSkip(int years) {
        if (years < 1 || years > 100 || debugDaysRemaining > 0) return false;
        debugYearsRequested = years;
        debugDaysRemaining = Math.multiplyExact(years, 360);
        simulationClock.reset();
        flushChange();
        return true;
    }

    public void stopDebugYearSkip() {
        debugDaysRemaining = 0;
        debugYearsRequested = 0;
        simulationClock.reset();
        flushChange();
    }

    public int debugDaysRemaining() { return debugDaysRemaining; }

    public int debugYearsRequested() { return debugYearsRequested; }
}
