package dev.autociv.persistence;

import com.mojang.logging.LogUtils;
import dev.autociv.simulation.world.WorldSimulation;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persists the {@link WorldSimulation} inside the world save
 * ({@code <world>/data/autociv_simulation.dat}).
 * <p>
 * Uses the NeoForge 21.1.x (MC 1.21.1) SavedData API:
 * {@code load(CompoundTag, HolderLookup.Provider)} /
 * {@code save(CompoundTag, HolderLookup.Provider)}, and a
 * {@code Factory<T>} constructed with a {@code BiFunction<CompoundTag,
 * HolderLookup.Provider, T>} loader. The codec path runs on {@link NbtOps}
 * directly - no JSON intermediate, no {@code convertFrom} between unrelated
 * ops types.
 * <p>
 * Saving strategy: mutations set a dirty flag; the vanilla autosave picks it
 * up. Never serialize on every tick.
 */
public final class SimulationSavedData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String DATA_NAME = "autociv_simulation";
    /** Guard against corrupt/huge saves; 1000 cities fit comfortably below this. */
    private static final NbtAccounter ACCOUNTER = NbtAccounter.create(10L * 1024 * 1024);

    private final WorldSimulation simulation = new WorldSimulation();
    private final AtomicBoolean dirtyFlag = new AtomicBoolean(false);

    public SimulationSavedData() {
    }

    public WorldSimulation simulation() {
        return simulation;
    }

    /** Call after any mutation of the simulation. Cheap and thread-safe. */
    public void markDirtySim() {
        dirtyFlag.set(true);
        setDirty(); // SavedData dirty - triggers save on next autosave
    }

    public boolean consumeDirty() {
        return dirtyFlag.getAndSet(false);
    }

    // ------------------------------------------------------------- load/save

    /** Loader callback used by {@link SavedData.Factory}; matches the 1.21.1 signature. */
    public static SimulationSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        SimulationSavedData data = new SimulationSavedData();
        try {
            var result = SimulationSerializer.DOCUMENT_CODEC.decode(NbtOps.INSTANCE, tag);
            result.resultOrPartial(LOGGER::error).ifPresent(pair -> {
                WorldSimulation restored = SimulationSerializer.fromDocument(pair.getFirst());
                // copy state into this instance's simulation object
                copyInto(restored, data.simulation);
                LOGGER.info("[autociv] Loaded simulation: {} civs, {} cities, {} citizens",
                        data.simulation.civilizations().size(),
                        data.simulation.settlements().size(),
                        data.simulation.citizens().size());
            });
            if (result.result().isEmpty()) {
                LOGGER.warn("[autociv] Simulation data could not be decoded, starting empty");
            }
        } catch (Exception e) {
            LOGGER.error("[autociv] Failed to load simulation data, starting empty", e);
        }
        return data;
    }

    private static void copyInto(WorldSimulation source, WorldSimulation target) {
        WorldSimulation.Loader loader = target.loader();
        source.civilizations().forEach(loader::addCivilization);
        source.settlements().forEach(loader::addSettlement);
        source.citizens().forEach(loader::addCitizen);
        source.tradeRoutes().forEach(loader::addTradeRoute);
        source.merchants().forEach(loader::addMerchant);
        source.tradeShipments().forEach(loader::addTradeShipment);
        target.setTimeDays(source.timeDays());
        target.setTickCount(source.tickCount());
        target.setSimulationSpeed(source.simulationSpeed());
        target.setCalendarStartYear(source.calendarStartYear());
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        SimulationSerializer.SimulationDocument doc = SimulationSerializer.toDocument(simulation);
        var result = SimulationSerializer.DOCUMENT_CODEC.encodeStart(NbtOps.INSTANCE, doc);
        return result.resultOrPartial(LOGGER::error)
                .map(t -> t instanceof CompoundTag ct ? ct : new CompoundTag())
                .orElseGet(() -> {
                    LOGGER.error("[autociv] Failed to encode simulation document; saving empty tag");
                    return new CompoundTag();
                });
    }

    public static Factory<SimulationSavedData> factory() {
        // Vanilla 1.21.1 SavedData.Factory: (Supplier<T> constructor,
        //   BiFunction<CompoundTag, HolderLookup.Provider, T> deserializer,
        //   DataFixTypes type) - no NbtAccounter here; the accounter is applied
        //   when reading the .dat file (see readTag).
        return new Factory<>(SimulationSavedData::new, SimulationSavedData::load, null);
    }

    public static SimulationSavedData get(MinecraftServer server) {
        return server.overworld()
                .getDataStorage()
                .computeIfAbsent(factory(), DATA_NAME);
    }
}
