package dev.autociv.debug;

import com.mojang.logging.LogUtils;
import dev.autociv.AutonomousCivilizations;
import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.world.WorldSimulation;
import dev.autociv.simulation.world.VillagePopulationSynchronizer;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/** Server snapshot used by the in-game civilization and worker diagnostics screen. */
public record CivDebugPayload(String contents) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final Type<CivDebugPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AutonomousCivilizations.MODID, "debug_snapshot"));
    public static final StreamCodec<ByteBuf, CivDebugPayload> STREAM_CODEC =
            ByteBufCodecs.stringUtf8(131_072).map(CivDebugPayload::new, CivDebugPayload::contents);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, STREAM_CODEC, CivDebugPayload::handle);
    }

    public static void openFor(ServerPlayer player, WorldSimulation simulation) {
        PacketDistributor.sendToPlayer(player, new CivDebugPayload(createSnapshot(player.getServer(), simulation)));
    }

    private static void handle(CivDebugPayload payload, IPayloadContext context) {
        if (!FMLEnvironment.dist.isClient()) {
            return;
        }
        try {
            Class<?> screen = Class.forName("dev.autociv.client.CivDebugScreen");
            screen.getMethod("open", String.class).invoke(null, payload.contents());
        } catch (ReflectiveOperationException exception) {
            LOGGER.error("Could not open the civilization diagnostics screen", exception);
        }
    }

    private static String createSnapshot(MinecraftServer server, WorldSimulation simulation) {
        List<String> lines = new ArrayList<>();
        lines.add(String.join("|", "META", simulation.simulationSpeed().name(),
                Long.toString(simulation.tickCount()), String.format(java.util.Locale.ROOT, "%.1f", simulation.timeDays()),
                Integer.toString(simulation.civilizations().size())));
        for (Civilization civilization : simulation.civilizations()) {
            String civId = shortId(civilization.id().toString());
            for (Settlement settlement : simulation.settlementsOf(civilization.id())) {
                String cityId = settlement.id().toString();
                lines.add(String.join("|", "CITY", civId, cityId, clean(civilization.name()), clean(settlement.name()),
                        Integer.toString(settlement.x()), Integer.toString(settlement.y()), Integer.toString(settlement.z()),
                        Integer.toString(settlement.population()), Integer.toString(settlement.housingCapacity()),
                        Integer.toString(settlement.completedHomes()), Integer.toString(settlement.materializedHomes()),
                        Integer.toString(settlement.homeBuildProgressPercent()),
                        String.format(java.util.Locale.ROOT, "%.0f", settlement.stockpile().get(ResourceType.FOOD)),
                        String.format(java.util.Locale.ROOT, "%.0f", settlement.stockpile().get(ResourceType.WOOD)),
                        String.format(java.util.Locale.ROOT, "%.0f", settlement.stockpile().get(ResourceType.STONE)),
                        String.format(java.util.Locale.ROOT, "%.0f", settlement.stockpile().get(ResourceType.IRON)),
                        String.format(java.util.Locale.ROOT, "%.0f", settlement.stockpile().get(ResourceType.COAL)),
                        clean(settlement.buildings().toString()),
                        settlement.activeBuildingPlan() == null ? "-" : clean(settlement.activeBuildingPlan().type()
                                + " " + settlement.activeBuildingPlan().progressPercent() + "%"),
                        Integer.toString(settlement.pendingPhysicalBuildings().size()),
                        settlement.role().name().toLowerCase(java.util.Locale.ROOT),
                        settlement.tier().name().toLowerCase(java.util.Locale.ROOT),
                        simulation.settlement(settlement.parentSettlementId()).map(Settlement::name)
                                .map(CivDebugPayload::clean).orElse(settlement.isCapital() ? "столица" : "нет")));
                int physicalCitizenLimit = VillagePopulationSynchronizer.desiredVillagerCount(settlement.population());
                for (int index = 0; index < physicalCitizenLimit; index++) {
                    var citizenId = settlement.citizenIds().get(index);
                    Citizen citizen = simulation.citizen(citizenId).orElse(null);
                    if (citizen == null) {
                        continue;
                    }
                    Entity entity = findLoadedEntity(server, citizen.entityId());
                    boolean loaded = entity instanceof Villager;
                    CitizenAiStatus.Snapshot activity = CitizenAiStatus.get(citizenId);
                    boolean currentActivity = activity != null
                            && activity.profession().equals(citizen.profession().name());
                    currentActivity = currentActivity || activity != null && activity.profession().equals("REST");
                    String state = !loaded ? "НЕ ЗАГРУЖЕН"
                            : currentActivity ? activity.state()
                            : citizen.profession() == Citizen.Profession.BUILDER
                            && settlement.materializedHomes() < settlement.completedHomes()
                            ? "СТРОИТЕЛЬ ГОТОВИТСЯ" : "ОЖИДАЕТ ЗАДАЧУ";
                    String target = currentActivity ? activity.target() : "-";
                    lines.add(String.join("|", "WORK", civId, cityId, clean(citizen.name()),
                            citizen.profession().name(), clean(state), clean(target), loaded ? "1" : "0",
                            shortId(citizen.id().toString()),
                            (int) citizen.inventoryWeight() + "/" + (int) citizen.inventoryCapacity(),
                            need(citizen, Citizen.Need.FOOD), need(citizen, Citizen.Need.SLEEP),
                            need(citizen, Citizen.Need.SAFETY), need(citizen, Citizen.Need.SOCIAL),
                            need(citizen, Citizen.Need.COMFORT), citizen.itemCargo().entrySet().stream()
                                    .sorted(java.util.Map.Entry.comparingByKey()).map(entry -> entry.getKey()
                                            .replace("minecraft:", "") + "×" + entry.getValue())
                                    .collect(java.util.stream.Collectors.joining(","))));
                }
            }
        }
        return String.join("\n", lines);
    }

    private static Entity findLoadedEntity(MinecraftServer server, java.util.UUID entityId) {
        if (entityId == null) {
            return null;
        }
        for (var level : server.getAllLevels()) {
            Entity entity = level.getEntity(entityId);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private static String shortId(String id) {
        return id.substring(0, Math.min(8, id.length()));
    }

    private static String need(Citizen citizen, Citizen.Need need) {
        return String.format(java.util.Locale.ROOT, "%.1f", citizen.need(need));
    }

    private static String clean(String value) {
        return value.replace('|', '/').replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
