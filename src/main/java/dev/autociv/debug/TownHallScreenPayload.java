package dev.autociv.debug;

import dev.autociv.AutonomousCivilizations;
import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.world.TownHallInteraction;
import dev.autociv.simulation.world.WorldSimulation;
import dev.autociv.simulation.economy.Market;
import dev.autociv.simulation.economy.PriceSystem;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.Locale;

/** Server-authoritative town hall overview sent to the dedicated client screen. */
public record TownHallScreenPayload(String cityId, String contents) implements CustomPacketPayload {

    public static final Type<TownHallScreenPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AutonomousCivilizations.MODID, "town_hall_screen"));
    public static final StreamCodec<ByteBuf, TownHallScreenPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), TownHallScreenPayload::cityId,
            ByteBufCodecs.stringUtf8(8192), TownHallScreenPayload::contents,
            TownHallScreenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, STREAM_CODEC, TownHallScreenPayload::handle);
    }

    public static void openFor(ServerPlayer player, WorldSimulation simulation, Settlement city) {
        PacketDistributor.sendToPlayer(player, new TownHallScreenPayload(city.id().toString(),
                snapshot(player, simulation, city)));
    }

    private static void handle(TownHallScreenPayload payload, IPayloadContext context) {
        if (!FMLEnvironment.dist.isClient()) return;
        try {
            Class<?> screen = Class.forName("dev.autociv.client.TownHallScreen");
            screen.getMethod("open", String.class, String.class).invoke(null, payload.cityId(), payload.contents());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not open the town hall screen", exception);
        }
    }

    private static String snapshot(ServerPlayer player, WorldSimulation simulation, Settlement city) {
        Civilization civ = simulation.civilization(city.civilizationId()).orElse(null);
        List<Citizen> residents = city.citizenIds().stream().map(id -> simulation.citizen(id).orElse(null))
                .filter(java.util.Objects::nonNull).toList();
        double averageMood = average(residents, Citizen::mood);
        double averageIncome = average(residents, Citizen::income);
        double averageHealth = average(residents, Citizen::health);
        double averageNeed = residents.stream().flatMap(citizen -> citizen.needs().values().stream())
                .mapToDouble(Double::doubleValue).average().orElse(1.0);
        String project = city.activeBuildingPlan() == null ? "нет"
                : city.activeBuildingPlan().type().name() + " " + city.activeBuildingPlan().progressPercent() + "%";
        boolean bankPlanned = city.activeBuildingPlan() != null
                && city.activeBuildingPlan().type() == dev.autociv.simulation.model.BuildingType.BANK
                || city.pendingPhysicalBuildings().stream()
                        .anyMatch(plan -> plan.type() == dev.autociv.simulation.model.BuildingType.BANK);
        List<net.minecraft.world.Container> physicalStock = dev.autociv.simulation.world.TownHallStorage
                .containers(player.serverLevel(), city);
        String workshopStatus = "инженеров " + residents.stream()
                .filter(citizen -> citizen.profession() == Citizen.Profession.ENGINEER).count()
                + " · древесина " + dev.autociv.simulation.world.TownHallStorage.countResourceItems(physicalStock,
                        ResourceType.WOOD)
                + " · камень " + dev.autociv.simulation.world.TownHallStorage.countResourceItems(physicalStock,
                        ResourceType.STONE);
        return String.join("|", "CITY", clean(civ == null ? "Цивилизация" : civ.name()), clean(city.name()),
                String.format(Locale.ROOT, "%.1f", TownHallInteraction.favor(player, city)),
                Integer.toString(city.population()), Integer.toString(city.housingCapacity()),
                Integer.toString(city.materializedHomes()), Integer.toString(city.completedHomes()),
                String.format(Locale.ROOT, "%.2f", city.happiness()),
                String.format(Locale.ROOT, "%.2f", city.security()),
                String.format(Locale.ROOT, "%.2f", averageMood),
                String.format(Locale.ROOT, "%.2f", averageHealth),
                String.format(Locale.ROOT, "%.2f", averageNeed),
                String.format(Locale.ROOT, "%.0f", civ == null ? 0 : civ.treasury()),
                String.format(Locale.ROOT, "%.2f", averageIncome),
                Integer.toString(city.buildingCount(dev.autociv.simulation.model.BuildingType.BANK)),
                clean(project), Integer.toString(city.pendingPhysicalBuildings().size()), bankPlanned ? "1" : "0",
                amount(city, ResourceType.FOOD), delta(city, ResourceType.FOOD),
                amount(city, ResourceType.WOOD), delta(city, ResourceType.WOOD),
                amount(city, ResourceType.STONE), delta(city, ResourceType.STONE),
                amount(city, ResourceType.IRON), delta(city, ResourceType.IRON),
                amount(city, ResourceType.COAL), delta(city, ResourceType.COAL),
                city.ownerPlayerId() == null ? "-" : city.ownerPlayerId().toString(),
                city.priority().name().toLowerCase(Locale.ROOT), city.strategy().name().toLowerCase(Locale.ROOT),
                Long.toString(simulation.tradeRoutes().stream().filter(route -> route.active()
                        && (route.sourceSettlementId().equals(city.id()) || route.destinationSettlementId().equals(city.id()))).count()),
                residents.stream().limit(8).map(c -> clean(c.name()) + ":"
                        + c.profession().name().toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.joining(",")),
                civ == null || civ.history().isEmpty() ? "нет записей" : clean(civ.history().get(civ.history().size() - 1)),
                new Market(city, new PriceSystem()).offers().stream().limit(4)
                        .map(offer -> (offer.side() == dev.autociv.simulation.economy.TradeOffer.Side.BUY ? "купить " : "продать ")
                                + offer.resource().id() + " " + String.format(Locale.ROOT, "%.0f", offer.quantity())
                                + " @" + String.format(Locale.ROOT, "%.1f", offer.unitPrice()))
                        .collect(java.util.stream.Collectors.joining(", ")),
                city.buildingQueue().stream().map(type -> type.name().toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.joining(",")),
                city.craftingQueue().stream().map(order -> order.itemId() + "×" + order.batchesRemaining())
                        .collect(java.util.stream.Collectors.joining(",")), clean(workshopStatus),
                city.buildingMaterialDemand() == null ? "нет" : clean(city.buildingMaterialDemand()),
                shipmentSummary(simulation, city.id(), true), shipmentSummary(simulation, city.id(), false),
                city.role().name().toLowerCase(Locale.ROOT), city.tier().name().toLowerCase(Locale.ROOT),
                simulation.settlement(city.parentSettlementId()).map(Settlement::name)
                        .map(TownHallScreenPayload::clean).orElse(city.isCapital() ? "столица" : "нет"));
    }

    private static String shipmentSummary(WorldSimulation simulation, java.util.UUID cityId, boolean incoming) {
        return simulation.tradeShipments().stream().filter(shipment -> {
                    var route = simulation.tradeRoute(shipment.routeId()).orElse(null);
                    return route != null && (incoming ? route.destinationSettlementId() : route.sourceSettlementId())
                            .equals(cityId);
                }).limit(3).map(shipment -> russianResource(shipment.resource()) + " "
                        + String.format(Locale.ROOT, "%.0f", shipment.quantity()) + " · "
                        + Math.max(1, (int) Math.ceil(shipment.arrivalDay() - simulation.timeDays())) + "д")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String russianResource(ResourceType resource) {
        return switch (resource.id()) {
            case "food" -> "еда";
            case "wood" -> "дерево";
            case "stone" -> "камень";
            case "iron" -> "железо";
            case "coal" -> "уголь";
            case "tools" -> "инструменты";
            case "weapons" -> "оружие";
            case "building_materials" -> "стройматериалы";
            default -> resource.id();
        };
    }

    private static double average(List<Citizen> residents, java.util.function.ToDoubleFunction<Citizen> value) {
        return residents.stream().mapToDouble(value).average().orElse(0.0);
    }

    private static String amount(Settlement city, ResourceType resource) {
        return String.format(Locale.ROOT, "%.0f", city.stockpile().get(resource));
    }

    private static String delta(Settlement city, ResourceType resource) {
        return String.format(Locale.ROOT, "%+.1f", city.stockpile().deltaPerDay(resource));
    }

    private static String clean(String text) {
        return text.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }
}
