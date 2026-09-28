package dev.autociv.persistence;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.CivilizationTrait;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.model.SettlementRole;
import dev.autociv.simulation.model.ColonyPriority;
import dev.autociv.simulation.model.DevelopmentStrategy;
import dev.autociv.simulation.model.BuildingPlan;
import dev.autociv.simulation.model.BuildingType;
import dev.autociv.simulation.model.DiplomaticAgreement;
import dev.autociv.simulation.model.Region;
import dev.autociv.simulation.model.BorderOutpost;
import dev.autociv.simulation.economy.Merchant;
import dev.autociv.simulation.economy.TradeRoute;
import dev.autociv.simulation.economy.TradeShipment;
import dev.autociv.simulation.world.SimulationSpeed;
import dev.autociv.simulation.world.WorldSimulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Codec-based (de)serialization of {@link WorldSimulation}.
 * <p>
 * The simulation is flattened into a {@link SimulationDocument} of DTO
 * records, encoded with DFU {@code Codec}s. Because the document layer uses
 * only JDK types + our POJOs, unit tests can encode/decode against real JSON
 * ({@code JsonOps}) without a running Minecraft server.
 * <p>
 * Loading goes through {@link WorldSimulation.Loader}, which restores
 * entities by their original UUIDs - ids are stable across save/load, so all
 * cross-references remain valid.
 */
public final class SimulationSerializer {

    /** UUID codec tolerant to NBT string restrictions: '-' may be stored as ';'. */
    static final Codec<UUID> UUID_CODEC = Codec.STRING.xmap(
            s -> UUID.fromString(s.replace(';', '-')),
            UUID::toString);

    static final Codec<Citizen.Profession> PROFESSION_CODEC =
            Codec.stringResolver(p -> p.name().toLowerCase(Locale.ROOT),
                    s -> Citizen.Profession.valueOf(s.toUpperCase(Locale.ROOT)));

    static final Codec<Civilization.Relation> RELATION_CODEC =
            Codec.stringResolver(r -> r.name().toLowerCase(Locale.ROOT),
                    s -> Civilization.Relation.valueOf(s.toUpperCase(Locale.ROOT)));

    // ------------------------------------------------------------- DTO records

    record CitizenDto(UUID id, String name, UUID home, double age, Citizen.Profession profession,
                      double health, double education, double income, double mood,
                      Map<String, Double> skills, List<UUID> family, UUID workplace,
                      Map<String, Double> needs, Map<String, Double> inventory,
                      Map<String, Integer> itemCargo) {
    }

    record SettlementDto(UUID id, String name, UUID civId, int x, int y, int z, int radius, boolean capital,
                         int housingCapacity, double happiness, double security, double defense,
                         double populationGrowthProgress, boolean physicalSettlementGenerated,
                         Map<String, Double> resources, List<UUID> citizens) {
    }

    record SettlementNetworkDto(UUID settlementId, UUID parentSettlementId, SettlementRole role, int foundedYear) { }

    record ColonyManagementDto(UUID settlementId, UUID ownerPlayerId, ColonyPriority priority,
                               DevelopmentStrategy strategy, List<BuildingType> buildingQueue,
                               List<CraftingOrderDto> craftingQueue, String buildingMaterialDemand) { }
    record CraftingOrderDto(String itemId, int batchesRemaining) { }

    record CivilizationDto(UUID id, String name, int color, UUID capitalId, List<UUID> settlements,
                           double treasury, double culture, double military,
                           Map<String, Civilization.Relation> relations, List<String> history,
                           String scenarioId, String historicalEra, int historicalStartYear,
                           String architectureStyle, List<UUID> knownSettlements,
                           Map<String, Double> opinions) {
    }

    record TradeRouteDto(UUID id, UUID merchantId, UUID sourceSettlementId, UUID destinationSettlementId,
                         String resourceId, double distance, long shipments, double totalQuantity, boolean active) {
    }

    record MerchantDto(UUID id, UUID routeId, long deliveries, double lifetimeQuantity, double transportRevenue) {
    }

    record TradeShipmentDto(UUID id, UUID routeId, UUID buyerCivilizationId, UUID sellerCivilizationId,
                             String resourceId, double quantity, double buyerUnitPrice,
                             double sellerUnitPrice, double departureDay, double arrivalDay) { }

    record DiplomaticAgreementDto(UUID id, UUID firstCivilization, UUID secondCivilization,
                                  DiplomaticAgreement.Type type, int durationDays, int remainingDays) {
    }

    record RegionDto(UUID id, int gridX, int gridZ, String resource, double richness,
                     UUID ownerCivilizationId, UUID nearestSettlementId) {
    }

    record BorderOutpostDto(UUID id, UUID civilizationId, UUID rivalCivilizationId,
                            int x, int y, int z, double foundedDay, double garrisonStrength) { }

    record HomeConstructionDto(UUID settlementId, int completedHomes, int materializedHomes,
                               int constructionDays, int buildCursor,
                               Map<String, Integer> buildings,
                               Map<String, Integer> materializedBuildings,
                               String activeType, UUID activePlanId, int activeProgress, int activeRequired,
                               int activeSiteX, int activeSiteY, int activeSiteZ, int activeBlockCursor,
                               List<PhysicalPlanDto> pendingPhysicalBuildings) {
    }

    record PhysicalPlanDto(UUID id, String type, int progress, int required,
                           int x, int y, int z, int cursor, boolean materialsPaid) {
    }

    /** Top-level save document. */
    public record SimulationDocument(double timeDays, long tickCount,
                                     List<CivilizationDto> civilizations,
                                     List<SettlementDto> settlements,
                                     List<CitizenDto> citizens, SimulationSpeed simulationSpeed,
                                     int calendarStartYear, List<TradeRouteDto> tradeRoutes,
                                     List<MerchantDto> merchants, List<HomeConstructionDto> homeConstruction,
                                     List<DiplomaticAgreementDto> diplomaticAgreements, List<RegionDto> regions,
                                     List<ColonyManagementDto> colonyManagement,
                                     List<TradeShipmentDto> tradeShipments,
                                     List<SettlementNetworkDto> settlementNetwork,
                                     List<BorderOutpostDto> borderOutposts) {
        /** Compatibility with documents saved before frontier camps were introduced. */
        public SimulationDocument(double timeDays, long tickCount, List<CivilizationDto> civilizations,
                                  List<SettlementDto> settlements, List<CitizenDto> citizens,
                                  SimulationSpeed simulationSpeed, int calendarStartYear,
                                  List<TradeRouteDto> tradeRoutes, List<MerchantDto> merchants,
                                  List<HomeConstructionDto> homeConstruction,
                                  List<DiplomaticAgreementDto> diplomaticAgreements, List<RegionDto> regions,
                                  List<ColonyManagementDto> colonyManagement, List<TradeShipmentDto> tradeShipments,
                                  List<SettlementNetworkDto> settlementNetwork) {
            this(timeDays, tickCount, civilizations, settlements, citizens, simulationSpeed, calendarStartYear,
                    tradeRoutes, merchants, homeConstruction, diplomaticAgreements, regions, colonyManagement,
                    tradeShipments, settlementNetwork, List.of());
        }

        /** Compatibility for code constructing documents after shipments were introduced. */
        public SimulationDocument(double timeDays, long tickCount, List<CivilizationDto> civilizations,
                                  List<SettlementDto> settlements, List<CitizenDto> citizens,
                                  SimulationSpeed simulationSpeed, int calendarStartYear,
                                  List<TradeRouteDto> tradeRoutes, List<MerchantDto> merchants,
                                  List<HomeConstructionDto> homeConstruction,
                                  List<DiplomaticAgreementDto> diplomaticAgreements, List<RegionDto> regions,
                                  List<ColonyManagementDto> colonyManagement,
                                  List<TradeShipmentDto> tradeShipments) {
            this(timeDays, tickCount, civilizations, settlements, citizens, simulationSpeed, calendarStartYear,
                    tradeRoutes, merchants, homeConstruction, diplomaticAgreements, regions, colonyManagement,
                    tradeShipments, List.of(), List.of());
        }

        /** Source compatibility for callers constructing pre-shipment documents. */
        public SimulationDocument(double timeDays, long tickCount, List<CivilizationDto> civilizations,
                                  List<SettlementDto> settlements, List<CitizenDto> citizens,
                                  SimulationSpeed simulationSpeed, int calendarStartYear,
                                  List<TradeRouteDto> tradeRoutes, List<MerchantDto> merchants,
                                  List<HomeConstructionDto> homeConstruction,
                                  List<DiplomaticAgreementDto> diplomaticAgreements, List<RegionDto> regions,
                                  List<ColonyManagementDto> colonyManagement) {
            this(timeDays, tickCount, civilizations, settlements, citizens, simulationSpeed, calendarStartYear,
                    tradeRoutes, merchants, homeConstruction, diplomaticAgreements, regions, colonyManagement,
                    List.of(), List.of(), List.of());
        }
    }

    // ----------------------------------------------------------------- codecs

    private static final Codec<CitizenDto> CITIZEN_CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("id").forGetter(CitizenDto::id),
            Codec.STRING.fieldOf("name").forGetter(CitizenDto::name),
            UUID_CODEC.fieldOf("home").forGetter(CitizenDto::home),
            Codec.DOUBLE.fieldOf("age").forGetter(CitizenDto::age),
            PROFESSION_CODEC.fieldOf("profession").forGetter(CitizenDto::profession),
            Codec.DOUBLE.fieldOf("health").forGetter(CitizenDto::health),
            Codec.DOUBLE.fieldOf("education").forGetter(CitizenDto::education),
            Codec.DOUBLE.fieldOf("income").forGetter(CitizenDto::income),
            Codec.DOUBLE.fieldOf("mood").forGetter(CitizenDto::mood),
            Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("skills", Map.of())
                    .forGetter(CitizenDto::skills),
            UUID_CODEC.listOf().optionalFieldOf("family", List.of())
                    .forGetter(CitizenDto::family),
            UUID_CODEC.optionalFieldOf("workplace").forGetter(dto -> java.util.Optional.ofNullable(dto.workplace())),
            Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("needs", Map.of())
                    .forGetter(CitizenDto::needs),
            Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("inventory", Map.of())
                    .forGetter(CitizenDto::inventory),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("itemCargo", Map.of())
                    .forGetter(CitizenDto::itemCargo)
    ).apply(i, (id, name, home, age, profession, health, education, income, mood, skills, family,
                workplace, needs, inventory, itemCargo) -> new CitizenDto(id, name, home, age, profession,
                health, education, income, mood, skills, family, workplace.orElse(null), needs, inventory, itemCargo)));

    private static final Codec<SettlementDto> SETTLEMENT_CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("id").forGetter(SettlementDto::id),
            Codec.STRING.fieldOf("name").forGetter(SettlementDto::name),
            UUID_CODEC.fieldOf("civ").forGetter(SettlementDto::civId),
            Codec.INT.fieldOf("x").forGetter(SettlementDto::x),
            Codec.INT.fieldOf("y").forGetter(SettlementDto::y),
            Codec.INT.fieldOf("z").forGetter(SettlementDto::z),
            Codec.INT.fieldOf("radius").forGetter(SettlementDto::radius),
            Codec.BOOL.fieldOf("capital").forGetter(SettlementDto::capital),
            Codec.INT.fieldOf("housing").forGetter(SettlementDto::housingCapacity),
            Codec.DOUBLE.fieldOf("happiness").forGetter(SettlementDto::happiness),
            Codec.DOUBLE.fieldOf("security").forGetter(SettlementDto::security),
            Codec.DOUBLE.fieldOf("defense").forGetter(SettlementDto::defense),
                Codec.DOUBLE.optionalFieldOf("growthProgress", 0.0).forGetter(SettlementDto::populationGrowthProgress),
                    Codec.BOOL.optionalFieldOf("physicalGenerated", false)
                        .forGetter(SettlementDto::physicalSettlementGenerated),
            Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).fieldOf("resources")
                    .forGetter(SettlementDto::resources),
            UUID_CODEC.listOf().fieldOf("citizens").forGetter(SettlementDto::citizens)
                ).apply(i, (id, name, civ, x, y, z, radius, capital, housing, happiness, security, defense,
                    growth, generated, resources, citizens) -> new SettlementDto(id, name, civ, x, y, z,
                    radius, capital, housing, happiness, security, defense, growth, generated, resources, citizens)));

    private static final Codec<CraftingOrderDto> CRAFTING_ORDER_CODEC = RecordCodecBuilder.create(order -> order.group(
            Codec.STRING.fieldOf("item").forGetter(CraftingOrderDto::itemId),
            Codec.INT.fieldOf("batches").forGetter(CraftingOrderDto::batchesRemaining)
    ).apply(order, CraftingOrderDto::new));

    private static final Codec<ColonyManagementDto> COLONY_MANAGEMENT_CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("settlement").forGetter(ColonyManagementDto::settlementId),
            UUID_CODEC.optionalFieldOf("owner").forGetter(dto -> java.util.Optional.ofNullable(dto.ownerPlayerId())),
            Codec.stringResolver(p -> p.name().toLowerCase(Locale.ROOT), ColonyPriority::parse)
                    .optionalFieldOf("priority", ColonyPriority.FOOD).forGetter(ColonyManagementDto::priority),
            Codec.stringResolver(s -> s.name().toLowerCase(Locale.ROOT), DevelopmentStrategy::parse)
                    .optionalFieldOf("strategy", DevelopmentStrategy.GROWTH).forGetter(ColonyManagementDto::strategy),
            Codec.stringResolver(t -> t.name().toLowerCase(Locale.ROOT), id -> BuildingType.valueOf(id.toUpperCase(Locale.ROOT)))
                    .listOf().optionalFieldOf("buildingQueue", List.of()).forGetter(ColonyManagementDto::buildingQueue),
            CRAFTING_ORDER_CODEC.listOf().optionalFieldOf("craftingQueue", List.of())
                    .forGetter(ColonyManagementDto::craftingQueue),
            Codec.STRING.optionalFieldOf("buildingMaterialDemand", "")
                    .forGetter(ColonyManagementDto::buildingMaterialDemand)
    ).apply(i, (settlement, owner, priority, strategy, queue, craftQueue, demand) -> new ColonyManagementDto(settlement,
            owner.orElse(null), priority, strategy, queue, craftQueue, demand)));

    private static final Codec<CivilizationDto> CIVILIZATION_CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("id").forGetter(CivilizationDto::id),
            Codec.STRING.fieldOf("name").forGetter(CivilizationDto::name),
            Codec.INT.fieldOf("color").forGetter(CivilizationDto::color),
            UUID_CODEC.optionalFieldOf("capital").forGetter(d -> java.util.Optional.ofNullable(d.capitalId())),
            UUID_CODEC.listOf().fieldOf("settlements").forGetter(CivilizationDto::settlements),
            Codec.DOUBLE.fieldOf("treasury").forGetter(CivilizationDto::treasury),
            Codec.DOUBLE.fieldOf("culture").forGetter(CivilizationDto::culture),
            Codec.DOUBLE.fieldOf("military").forGetter(CivilizationDto::military),
            Codec.unboundedMap(Codec.STRING, RELATION_CODEC).optionalFieldOf("relations", Map.of())
                    .forGetter(CivilizationDto::relations),
                Codec.STRING.listOf().fieldOf("history").forGetter(CivilizationDto::history),
                Codec.STRING.optionalFieldOf("scenario", "custom").forGetter(CivilizationDto::scenarioId),
                Codec.STRING.optionalFieldOf("era", "Unrecorded").forGetter(CivilizationDto::historicalEra),
                Codec.INT.optionalFieldOf("historicalStartYear", 0).forGetter(CivilizationDto::historicalStartYear),
                    Codec.STRING.optionalFieldOf("architecture", "generic").forGetter(CivilizationDto::architectureStyle),
                    UUID_CODEC.listOf().optionalFieldOf("knownSettlements", List.of())
                        .forGetter(CivilizationDto::knownSettlements),
                    Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("opinions", Map.of())
                        .forGetter(CivilizationDto::opinions)
                ).apply(i, (id, name, color, cap, setts, tre, cul, mil, rel, hist, scenario, era, startYear,
                    architecture, knownSettlements, opinions) ->
                new CivilizationDto(id, name, color, cap.orElse(null), setts, tre, cul, mil, rel, hist,
                        scenario, era, startYear, architecture, knownSettlements, opinions)));

                private static final Codec<TradeRouteDto> TRADE_ROUTE_CODEC = RecordCodecBuilder.create(i -> i.group(
                    UUID_CODEC.fieldOf("id").forGetter(TradeRouteDto::id),
                    UUID_CODEC.fieldOf("merchant").forGetter(TradeRouteDto::merchantId),
                    UUID_CODEC.fieldOf("source").forGetter(TradeRouteDto::sourceSettlementId),
                    UUID_CODEC.fieldOf("destination").forGetter(TradeRouteDto::destinationSettlementId),
                    Codec.STRING.fieldOf("resource").forGetter(TradeRouteDto::resourceId),
                    Codec.DOUBLE.fieldOf("distance").forGetter(TradeRouteDto::distance),
                    Codec.LONG.fieldOf("shipments").forGetter(TradeRouteDto::shipments),
                    Codec.DOUBLE.fieldOf("quantity").forGetter(TradeRouteDto::totalQuantity),
                    Codec.BOOL.optionalFieldOf("active", true).forGetter(TradeRouteDto::active)
                ).apply(i, TradeRouteDto::new));

    private static final Codec<MerchantDto> MERCHANT_CODEC = RecordCodecBuilder.create(i -> i.group(
                    UUID_CODEC.fieldOf("id").forGetter(MerchantDto::id),
                    UUID_CODEC.fieldOf("route").forGetter(MerchantDto::routeId),
                    Codec.LONG.fieldOf("deliveries").forGetter(MerchantDto::deliveries),
                    Codec.DOUBLE.fieldOf("quantity").forGetter(MerchantDto::lifetimeQuantity),
                    Codec.DOUBLE.optionalFieldOf("transportRevenue", 0.0).forGetter(MerchantDto::transportRevenue)
                ).apply(i, MerchantDto::new));

    private static final Codec<TradeShipmentDto> TRADE_SHIPMENT_CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("id").forGetter(TradeShipmentDto::id),
            UUID_CODEC.fieldOf("route").forGetter(TradeShipmentDto::routeId),
            UUID_CODEC.fieldOf("buyer").forGetter(TradeShipmentDto::buyerCivilizationId),
            UUID_CODEC.fieldOf("seller").forGetter(TradeShipmentDto::sellerCivilizationId),
            Codec.STRING.fieldOf("resource").forGetter(TradeShipmentDto::resourceId),
            Codec.DOUBLE.fieldOf("quantity").forGetter(TradeShipmentDto::quantity),
            Codec.DOUBLE.fieldOf("buyerPrice").forGetter(TradeShipmentDto::buyerUnitPrice),
            Codec.DOUBLE.fieldOf("sellerPrice").forGetter(TradeShipmentDto::sellerUnitPrice),
            Codec.DOUBLE.fieldOf("departureDay").forGetter(TradeShipmentDto::departureDay),
            Codec.DOUBLE.fieldOf("arrivalDay").forGetter(TradeShipmentDto::arrivalDay)
    ).apply(i, TradeShipmentDto::new));

    private static final Codec<SettlementRole> SETTLEMENT_ROLE_CODEC = Codec.stringResolver(
            role -> role.name().toLowerCase(Locale.ROOT),
            value -> SettlementRole.valueOf(value.toUpperCase(Locale.ROOT)));

    private static final Codec<SettlementNetworkDto> SETTLEMENT_NETWORK_CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("settlement").forGetter(SettlementNetworkDto::settlementId),
            UUID_CODEC.optionalFieldOf("parent").forGetter(profile -> Optional.ofNullable(profile.parentSettlementId())),
            SETTLEMENT_ROLE_CODEC.optionalFieldOf("role", SettlementRole.VILLAGE)
                    .forGetter(SettlementNetworkDto::role),
            Codec.INT.optionalFieldOf("foundedYear", 0).forGetter(SettlementNetworkDto::foundedYear)
    ).apply(i, (id, parent, role, foundedYear) -> new SettlementNetworkDto(
            id, parent.orElse(null), role, foundedYear)));

    private static final Codec<DiplomaticAgreement.Type> AGREEMENT_TYPE_CODEC = Codec.stringResolver(
            type -> type.name().toLowerCase(Locale.ROOT),
            id -> DiplomaticAgreement.Type.valueOf(id.toUpperCase(Locale.ROOT)));
    private static final Codec<DiplomaticAgreementDto> DIPLOMATIC_AGREEMENT_CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("id").forGetter(DiplomaticAgreementDto::id),
            UUID_CODEC.fieldOf("first").forGetter(DiplomaticAgreementDto::firstCivilization),
            UUID_CODEC.fieldOf("second").forGetter(DiplomaticAgreementDto::secondCivilization),
            AGREEMENT_TYPE_CODEC.fieldOf("type").forGetter(DiplomaticAgreementDto::type),
            Codec.INT.fieldOf("duration").forGetter(DiplomaticAgreementDto::durationDays),
            Codec.INT.fieldOf("remaining").forGetter(DiplomaticAgreementDto::remainingDays)
    ).apply(i, DiplomaticAgreementDto::new));

    private static final Codec<RegionDto> REGION_CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("id").forGetter(RegionDto::id),
            Codec.INT.fieldOf("gridX").forGetter(RegionDto::gridX),
            Codec.INT.fieldOf("gridZ").forGetter(RegionDto::gridZ),
            Codec.STRING.fieldOf("resource").forGetter(RegionDto::resource),
            Codec.DOUBLE.fieldOf("richness").forGetter(RegionDto::richness),
            UUID_CODEC.optionalFieldOf("owner").forGetter(dto -> java.util.Optional.ofNullable(dto.ownerCivilizationId())),
            UUID_CODEC.optionalFieldOf("settlement").forGetter(dto -> java.util.Optional.ofNullable(dto.nearestSettlementId()))
    ).apply(i, (id, gridX, gridZ, resource, richness, owner, settlement) -> new RegionDto(id, gridX, gridZ,
            resource, richness, owner.orElse(null), settlement.orElse(null))));

    private static final Codec<BorderOutpostDto> BORDER_OUTPOST_CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("id").forGetter(BorderOutpostDto::id),
            UUID_CODEC.fieldOf("civilization").forGetter(BorderOutpostDto::civilizationId),
            UUID_CODEC.fieldOf("rival").forGetter(BorderOutpostDto::rivalCivilizationId),
            Codec.INT.fieldOf("x").forGetter(BorderOutpostDto::x),
            Codec.INT.fieldOf("y").forGetter(BorderOutpostDto::y),
            Codec.INT.fieldOf("z").forGetter(BorderOutpostDto::z),
            Codec.DOUBLE.fieldOf("foundedDay").forGetter(BorderOutpostDto::foundedDay),
            Codec.DOUBLE.fieldOf("garrison").forGetter(BorderOutpostDto::garrisonStrength)
    ).apply(i, BorderOutpostDto::new));

    private static final Codec<PhysicalPlanDto> PHYSICAL_PLAN_CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("id").forGetter(PhysicalPlanDto::id),
            Codec.STRING.fieldOf("type").forGetter(PhysicalPlanDto::type),
            Codec.INT.fieldOf("progress").forGetter(PhysicalPlanDto::progress),
            Codec.INT.fieldOf("required").forGetter(PhysicalPlanDto::required),
            Codec.INT.optionalFieldOf("x", Integer.MIN_VALUE).forGetter(PhysicalPlanDto::x),
            Codec.INT.optionalFieldOf("y", Integer.MIN_VALUE).forGetter(PhysicalPlanDto::y),
            Codec.INT.optionalFieldOf("z", Integer.MIN_VALUE).forGetter(PhysicalPlanDto::z),
            Codec.INT.optionalFieldOf("cursor", 0).forGetter(PhysicalPlanDto::cursor),
            Codec.BOOL.optionalFieldOf("materialsPaid", false).forGetter(PhysicalPlanDto::materialsPaid)
    ).apply(i, PhysicalPlanDto::new));

    private static final Codec<HomeConstructionDto> HOME_CONSTRUCTION_CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("settlement").forGetter(HomeConstructionDto::settlementId),
            Codec.INT.fieldOf("completed").forGetter(HomeConstructionDto::completedHomes),
            Codec.INT.fieldOf("materialized").forGetter(HomeConstructionDto::materializedHomes),
            Codec.INT.fieldOf("days").forGetter(HomeConstructionDto::constructionDays),
            Codec.INT.optionalFieldOf("buildCursor", 0).forGetter(HomeConstructionDto::buildCursor),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("buildings", Map.of())
                    .forGetter(HomeConstructionDto::buildings),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("materializedBuildings", Map.of())
                    .forGetter(HomeConstructionDto::materializedBuildings),
            Codec.STRING.optionalFieldOf("activeType", "").forGetter(HomeConstructionDto::activeType),
            UUID_CODEC.optionalFieldOf("activePlanId").forGetter(dto -> java.util.Optional.ofNullable(dto.activePlanId())),
            Codec.INT.optionalFieldOf("activeProgress", 0).forGetter(HomeConstructionDto::activeProgress),
            Codec.INT.optionalFieldOf("activeRequired", 1).forGetter(HomeConstructionDto::activeRequired),
            Codec.INT.optionalFieldOf("activeX", Integer.MIN_VALUE).forGetter(HomeConstructionDto::activeSiteX),
            Codec.INT.optionalFieldOf("activeY", Integer.MIN_VALUE).forGetter(HomeConstructionDto::activeSiteY),
            Codec.INT.optionalFieldOf("activeZ", Integer.MIN_VALUE).forGetter(HomeConstructionDto::activeSiteZ),
            Codec.INT.optionalFieldOf("activeCursor", 0).forGetter(HomeConstructionDto::activeBlockCursor),
            PHYSICAL_PLAN_CODEC.listOf().optionalFieldOf("pendingPhysicalBuildings", List.of())
                    .forGetter(HomeConstructionDto::pendingPhysicalBuildings)
    ).apply(i, (settlement, completed, materialized, days, cursor, buildings, materializedBuildings,
                 type, planId, progress, required, x, y, z, blockCursor, pending) ->
            new HomeConstructionDto(settlement, completed, materialized, days, cursor, buildings,
                    materializedBuildings, type, planId.orElse(null), progress, required,
                    x, y, z, blockCursor, pending)));

    public static final Codec<SimulationDocument> DOCUMENT_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.fieldOf("timeDays").forGetter(SimulationDocument::timeDays),
            Codec.LONG.fieldOf("tickCount").forGetter(SimulationDocument::tickCount),
            CIVILIZATION_CODEC.listOf().fieldOf("civilizations").forGetter(SimulationDocument::civilizations),
            SETTLEMENT_CODEC.listOf().fieldOf("settlements").forGetter(SimulationDocument::settlements),
            CITIZEN_CODEC.listOf().fieldOf("citizens").forGetter(SimulationDocument::citizens),
            Codec.stringResolver(speed -> speed.name().toLowerCase(Locale.ROOT),
                id -> SimulationSpeed.valueOf(id.toUpperCase(Locale.ROOT)))
                .optionalFieldOf("simulationSpeed", SimulationSpeed.REALISTIC)
                    .forGetter(SimulationDocument::simulationSpeed),
                Codec.INT.optionalFieldOf("calendarStartYear", 0).forGetter(SimulationDocument::calendarStartYear)
                    ,TRADE_ROUTE_CODEC.listOf().optionalFieldOf("tradeRoutes", List.of())
                        .forGetter(SimulationDocument::tradeRoutes),
                    MERCHANT_CODEC.listOf().optionalFieldOf("merchants", List.of())
                        .forGetter(SimulationDocument::merchants),
                    HOME_CONSTRUCTION_CODEC.listOf().optionalFieldOf("homeConstruction", List.of())
                        .forGetter(SimulationDocument::homeConstruction),
                    DIPLOMATIC_AGREEMENT_CODEC.listOf().optionalFieldOf("diplomaticAgreements", List.of())
                        .forGetter(SimulationDocument::diplomaticAgreements),
                    REGION_CODEC.listOf().optionalFieldOf("regions", List.of())
                        .forGetter(SimulationDocument::regions),
                    COLONY_MANAGEMENT_CODEC.listOf().optionalFieldOf("colonyManagement", List.of())
                        .forGetter(SimulationDocument::colonyManagement),
                    TRADE_SHIPMENT_CODEC.listOf().optionalFieldOf("tradeShipments", List.of())
                        .forGetter(SimulationDocument::tradeShipments),
                    SETTLEMENT_NETWORK_CODEC.listOf().optionalFieldOf("settlementNetwork", List.of())
                        .forGetter(SimulationDocument::settlementNetwork),
                    BORDER_OUTPOST_CODEC.listOf().optionalFieldOf("borderOutposts", List.of())
                        .forGetter(SimulationDocument::borderOutposts)
            ).apply(i, (days, ticks, civs, settlements, citizens, speed, startYear, routes, merchants, homes,
                        agreements, regions, management, shipments, network, outposts) ->
                new SimulationDocument(days, ticks, civs, settlements, citizens, speed, startYear, routes,
                        merchants, homes, agreements, regions, management, shipments, network, outposts)));

    public static Codec<SimulationDocument> documentCodec() {
        return DOCUMENT_CODEC;
    }

    // ------------------------------------------------------------ sim -> doc

    public static SimulationDocument toDocument(WorldSimulation sim) {
        List<CivilizationDto> civs = new ArrayList<>();
        for (Civilization c : sim.civilizations()) {
            Map<String, Civilization.Relation> rel = new HashMap<>();
            c.relations().forEach((k, v) -> rel.put(k.toString(), v));
            Map<String, Double> opinions = new HashMap<>();
            c.diplomaticOpinions().forEach((k, v) -> opinions.put(k.toString(), v));
            civs.add(new CivilizationDto(c.id(), c.name(), c.color(), c.capitalSettlementId(),
                    new ArrayList<>(c.settlementIds()), c.treasury(), c.culture(), c.militaryStrength(),
                    rel, new ArrayList<>(c.history()), c.scenarioId(), c.historicalEra(),
                    c.historicalStartYear(), c.architectureStyle(), new ArrayList<>(c.knownSettlementIds()), opinions));
        }
        List<SettlementDto> setts = new ArrayList<>();
        for (Settlement s : sim.settlements()) {
            Map<String, Double> res = new HashMap<>();
            for (Map.Entry<ResourceType, Double> e : s.stockpile().entries()) {
                if (e.getValue() > 0) {
                    res.put(e.getKey().id(), e.getValue());
                }
            }
            setts.add(new SettlementDto(s.id(), s.name(), s.civilizationId(), s.x(), s.y(), s.z(),
                    s.radius(), s.isCapital(), s.housingCapacity(), s.happiness(), s.security(), s.defense(),
                    s.populationGrowthProgress(), s.physicalSettlementGenerated(), res,
                    new ArrayList<>(s.citizenIds())));
        }
        List<CitizenDto> citizens = new ArrayList<>();
        for (Citizen c : sim.citizens()) {
            citizens.add(new CitizenDto(c.id(), c.name(), c.homeSettlementId(), c.ageYears(), c.profession(),
                    c.health(), c.education(), c.income(), c.mood(),
                    new HashMap<>(c.skills()), new ArrayList<>(c.family()), c.workplaceSettlementId(),
                    c.needs().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                            e -> e.getKey().name().toLowerCase(Locale.ROOT), Map.Entry::getValue)),
                    c.inventory().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                            e -> e.getKey().id(), Map.Entry::getValue)), c.itemCargo()));
        }
        List<TradeRouteDto> routes = sim.tradeRoutes().stream().map(route -> new TradeRouteDto(
            route.id(), route.merchantId(), route.sourceSettlementId(), route.destinationSettlementId(),
            route.resource().id(), route.distance(), route.completedShipments(), route.totalQuantity(),
            route.active())).toList();
        List<MerchantDto> merchants = sim.merchants().stream().map(merchant -> new MerchantDto(
            merchant.id(), merchant.routeId(), merchant.deliveries(), merchant.lifetimeQuantity(),
                merchant.transportRevenue())).toList();
        List<TradeShipmentDto> shipments = sim.tradeShipments().stream().map(shipment -> new TradeShipmentDto(
                shipment.id(), shipment.routeId(), shipment.buyerCivilizationId(), shipment.sellerCivilizationId(),
                shipment.resource().id(), shipment.quantity(), shipment.buyerUnitPrice(), shipment.sellerUnitPrice(),
                shipment.departureDay(), shipment.arrivalDay())).toList();
        List<DiplomaticAgreementDto> agreements = sim.diplomaticAgreements().stream()
                .map(agreement -> new DiplomaticAgreementDto(agreement.id(), agreement.firstCivilizationId(),
                        agreement.secondCivilizationId(), agreement.type(), agreement.durationDays(),
                        agreement.remainingDays())).toList();
        List<RegionDto> regions = sim.regions().stream().map(region -> new RegionDto(region.id(), region.gridX(),
                region.gridZ(), region.resource().id(), region.richness(), region.ownerCivilizationId(),
                region.nearestSettlementId())).toList();
        List<BorderOutpostDto> outposts = sim.borderOutposts().stream().map(outpost -> new BorderOutpostDto(
                outpost.id(), outpost.civilizationId(), outpost.rivalCivilizationId(), outpost.x(), outpost.y(),
                outpost.z(), outpost.foundedDay(), outpost.garrisonStrength())).toList();
        List<HomeConstructionDto> homeConstruction = sim.settlements().stream()
                .filter(settlement -> settlement.completedHomes() > 0 || settlement.homeConstructionDays() > 0
                        || !settlement.buildings().isEmpty() || settlement.activeBuildingPlan() != null
                        || !settlement.pendingPhysicalBuildings().isEmpty())
                .map(settlement -> new HomeConstructionDto(settlement.id(), settlement.completedHomes(),
                        settlement.materializedHomes(), settlement.homeConstructionDays(),
                        settlement.homeMaterialsPaid() && settlement.homeBuildCursor() == 0
                                ? -1 : settlement.homeBuildCursor(),
                        settlement.buildings().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                                entry -> entry.getKey().name().toLowerCase(Locale.ROOT), Map.Entry::getValue)),
                        settlement.materializedBuildings().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                                entry -> entry.getKey().name().toLowerCase(Locale.ROOT), Map.Entry::getValue)),
                        settlement.activeBuildingPlan() == null ? "" : settlement.activeBuildingPlan().type()
                                .name().toLowerCase(Locale.ROOT),
                        settlement.activeBuildingPlan() == null ? null : settlement.activeBuildingPlan().id(),
                        settlement.activeBuildingPlan() == null ? 0 : settlement.activeBuildingPlan().progressDays(),
                        settlement.activeBuildingPlan() == null ? 1 : settlement.activeBuildingPlan().requiredDays(),
                        settlement.activeBuildingPlan() == null ? Integer.MIN_VALUE : settlement.activeBuildingPlan().siteX(),
                        settlement.activeBuildingPlan() == null ? Integer.MIN_VALUE : settlement.activeBuildingPlan().siteY(),
                        settlement.activeBuildingPlan() == null ? Integer.MIN_VALUE : settlement.activeBuildingPlan().siteZ(),
                        settlement.activeBuildingPlan() == null ? 0 : settlement.activeBuildingPlan().blockCursor(),
                        settlement.pendingPhysicalBuildings().stream().map(plan -> new PhysicalPlanDto(
                                plan.id(), plan.type().name().toLowerCase(Locale.ROOT), plan.progressDays(),
                                plan.requiredDays(), plan.siteX(), plan.siteY(), plan.siteZ(), plan.blockCursor(),
                                plan.materialsPaid())).toList()))
                .toList();
        List<ColonyManagementDto> management = sim.settlements().stream().map(city ->
                new ColonyManagementDto(city.id(), city.ownerPlayerId(), city.priority(), city.strategy(),
                        List.copyOf(city.buildingQueue()), city.craftingQueue().stream()
                        .map(order -> new CraftingOrderDto(order.itemId(), order.batchesRemaining())).toList(),
                        city.buildingMaterialDemand() == null ? "" : city.buildingMaterialDemand())).toList();
        List<SettlementNetworkDto> settlementNetwork = sim.settlements().stream().map(city ->
                new SettlementNetworkDto(city.id(), city.parentSettlementId(), city.role(), city.foundedYear()))
                .toList();
        return new SimulationDocument(sim.timeDays(), sim.tickCount(), civs, setts, citizens,
            sim.simulationSpeed(), sim.calendarStartYear(), routes, merchants, homeConstruction, agreements, regions,
                management, shipments, settlementNetwork, outposts);
    }

    // ------------------------------------------------------------ doc -> sim

    public static WorldSimulation fromDocument(SimulationDocument doc) {
        WorldSimulation sim = new WorldSimulation();
        WorldSimulation.Loader loader = sim.loader();
        Map<UUID, Map<String, Double>> savedResources = new HashMap<>();

        for (CitizenDto cd : doc.citizens()) {
            Citizen c = new Citizen(cd.id(), cd.name(), cd.home(), cd.age(), cd.profession());
            c.setHealth(cd.health());
            c.setEducation(cd.education());
            c.setIncome(cd.income());
            c.setMood(cd.mood());
            cd.skills().forEach(c::setSkill);
            c.setWorkplaceSettlementId(cd.workplace());
            cd.needs().forEach((key, value) -> c.setNeed(Citizen.Need.valueOf(key.toUpperCase(Locale.ROOT)), value));
            cd.inventory().forEach((key, value) -> c.carry(ResourceType.byIdOrRegister(key), value));
            c.restoreItemCargo(cd.itemCargo());
            c.family().addAll(cd.family());
            loader.addCitizen(c);
        }
        for (SettlementDto sd : doc.settlements()) {
            Settlement s = new Settlement(sd.id(), sd.name(), sd.civId(), sd.x(), sd.y(), sd.z());
            s.setRadius(sd.radius());
            s.setCapital(sd.capital());
            s.setHousingCapacity(sd.housingCapacity());
            s.setHappiness(sd.happiness());
            s.setSecurity(sd.security());
            s.setDefense(sd.defense());
            s.addPopulationGrowthProgress(sd.populationGrowthProgress());
            s.setPhysicalSettlementGenerated(sd.physicalSettlementGenerated());
            savedResources.put(sd.id(), sd.resources());
            for (UUID cid : sd.citizens()) {
                loader.attachCitizenToSettlement(s, cid);
            }
            loader.addSettlement(s);
        }
        for (ColonyManagementDto management : doc.colonyManagement()) {
            sim.settlement(management.settlementId()).ifPresent(city -> {
                city.setOwnerPlayerId(management.ownerPlayerId());
                city.setPriority(management.priority());
                city.setStrategy(management.strategy());
                city.restoreBuildingQueue(management.buildingQueue());
                city.restoreCraftingQueue(management.craftingQueue().stream()
                        .filter(order -> order.itemId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                                && order.batchesRemaining() >= 1 && order.batchesRemaining() <= 64)
                        .limit(32).map(order -> new dev.autociv.simulation.model.CraftingOrder(
                                order.itemId(), order.batchesRemaining())).toList());
                if (management.buildingMaterialDemand().length() <= 128
                        && management.buildingMaterialDemand().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                    city.setBuildingMaterialDemand(management.buildingMaterialDemand());
                }
            });
        }
        for (HomeConstructionDto home : doc.homeConstruction()) {
            sim.settlement(home.settlementId()).ifPresent(settlement -> settlement.setHomeBuildingState(
                    home.completedHomes(), home.materializedHomes(), home.constructionDays()));
            sim.settlement(home.settlementId()).ifPresent(settlement -> settlement.setHomeBuildCursor(home.buildCursor()));
            sim.settlement(home.settlementId()).ifPresent(settlement -> settlement.restoreBuildings(
                    home.buildings().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                            entry -> BuildingType.valueOf(entry.getKey().toUpperCase(Locale.ROOT)), Map.Entry::getValue))));
            sim.settlement(home.settlementId()).ifPresent(settlement -> settlement.restoreMaterializedBuildings(
                    home.materializedBuildings().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                            entry -> BuildingType.valueOf(entry.getKey().toUpperCase(Locale.ROOT)), Map.Entry::getValue))));
            if (!home.activeType().isBlank() && home.activePlanId() != null) {
                BuildingType type = BuildingType.valueOf(home.activeType().toUpperCase(Locale.ROOT));
                sim.settlement(home.settlementId()).ifPresent(settlement -> {
                    BuildingPlan plan = new BuildingPlan(home.activePlanId(), type, home.activeProgress(),
                            home.activeRequired(), home.activeSiteX(), home.activeSiteY(), home.activeSiteZ(),
                            home.activeBlockCursor());
                    settlement.restoreActiveBuildingPlan(plan);
                });
            }
            sim.settlement(home.settlementId()).ifPresent(settlement -> settlement.restorePendingPhysicalBuildings(
                    home.pendingPhysicalBuildings().stream().map(dto -> {
                        BuildingPlan plan = new BuildingPlan(dto.id(),
                                BuildingType.valueOf(dto.type().toUpperCase(Locale.ROOT)), dto.progress(), dto.required(),
                                dto.x(), dto.y(), dto.z(), dto.cursor());
                        plan.setMaterialsPaid(dto.materialsPaid());
                        return plan;
                    }).toList()));
        }
        savedResources.forEach((settlementId, resources) -> sim.settlement(settlementId).ifPresent(settlement ->
                resources.forEach((resourceId, amount) -> settlement.stockpile()
                        .add(ResourceType.byIdOrRegister(resourceId), amount))));
        for (CivilizationDto cd : doc.civilizations()) {
            Civilization c = new Civilization(cd.id(), cd.name(), cd.color());
            c.setScenario(cd.scenarioId(), cd.historicalEra(), cd.historicalStartYear(), cd.architectureStyle());
            // Traits are deterministic from the saved scenario id. Deriving them here
            // avoids a 17th field in DFU's RecordCodecBuilder while keeping old saves
            // and the existing flat civilization document format compatible.
            c.setTraits(CivilizationTrait.forScenario(cd.scenarioId()));
            c.addToTreasury(cd.treasury());
            c.addToCulture(cd.culture());
            c.setMilitaryStrength(cd.military());
            cd.relations().forEach((k, v) -> c.setRelationWith(UUID.fromString(k), v));
            cd.opinions().forEach((k, v) -> c.setDiplomaticOpinionWith(UUID.fromString(k), v));
            cd.knownSettlements().forEach(c::knowSettlement);
            for (String h : cd.history()) {
                c.recordEvent(h);
            }
            for (UUID sid : cd.settlements()) {
                loader.attachSettlementToCivilization(c, sid);
            }
            c.setCapitalSettlementId(cd.capitalId());
            loader.addCivilization(c);
        }
        for (SettlementNetworkDto profile : doc.settlementNetwork()) {
            Settlement city = sim.settlement(profile.settlementId()).orElse(null);
            if (city == null) continue;
            city.setNetworkProfile(null, profile.role(), profile.foundedYear());
            if (profile.parentSettlementId() != null) {
                Settlement parent = sim.settlement(profile.parentSettlementId()).orElse(null);
                if (!sim.linkSettlement(city, parent, profile.role(), profile.foundedYear())) {
                    city.setNetworkProfile(null, city.isCapital()
                            ? SettlementRole.CAPITAL : profile.role(), profile.foundedYear());
                }
            }
        }
            doc.tradeRoutes().forEach(route -> loader.addTradeRoute(new TradeRoute(route.id(), route.merchantId(),
                route.sourceSettlementId(), route.destinationSettlementId(),
                ResourceType.byIdOrRegister(route.resourceId()), route.distance(), route.shipments(),
                route.totalQuantity(), route.active())));
            doc.merchants().forEach(merchant -> loader.addMerchant(new Merchant(merchant.id(), merchant.routeId(),
                merchant.deliveries(), merchant.lifetimeQuantity(), merchant.transportRevenue())));
            for (TradeShipmentDto shipment : doc.tradeShipments()) {
                TradeRoute route = sim.tradeRoute(shipment.routeId()).orElse(null);
                Settlement source = route == null ? null : sim.settlement(route.sourceSettlementId()).orElse(null);
                Settlement destination = route == null ? null
                        : sim.settlement(route.destinationSettlementId()).orElse(null);
                if (route == null || source == null || destination == null
                        || sim.civilization(shipment.buyerCivilizationId()).isEmpty()
                        || sim.civilization(shipment.sellerCivilizationId()).isEmpty()
                        || !source.civilizationId().equals(shipment.sellerCivilizationId())
                        || !destination.civilizationId().equals(shipment.buyerCivilizationId())
                        || !route.resource().id().equals(shipment.resourceId())) continue;
                try {
                    loader.addTradeShipment(new TradeShipment(shipment.id(), shipment.routeId(),
                            shipment.buyerCivilizationId(), shipment.sellerCivilizationId(),
                            ResourceType.byIdOrRegister(shipment.resourceId()), shipment.quantity(),
                            shipment.buyerUnitPrice(), shipment.sellerUnitPrice(), shipment.departureDay(),
                            shipment.arrivalDay()));
                } catch (IllegalArgumentException invalidShipment) {
                    // Invalid cargo records are ignored rather than breaking the whole world save.
                }
            }
            doc.diplomaticAgreements().forEach(agreement -> loader.addDiplomaticAgreement(
                    new DiplomaticAgreement(agreement.id(), agreement.firstCivilization(),
                            agreement.secondCivilization(), agreement.type(), agreement.durationDays(),
                            agreement.remainingDays())));
            doc.regions().forEach(region -> loader.addRegion(new Region(region.id(), region.gridX(), region.gridZ(),
                    ResourceType.byIdOrRegister(region.resource()), region.richness(), region.ownerCivilizationId(),
                    region.nearestSettlementId())));
            for (BorderOutpostDto outpost : doc.borderOutposts()) {
                if (sim.civilization(outpost.civilizationId()).isEmpty()
                        || sim.civilization(outpost.rivalCivilizationId()).isEmpty()
                        || outpost.civilizationId().equals(outpost.rivalCivilizationId())) continue;
                try {
                    loader.addBorderOutpost(new BorderOutpost(outpost.id(), outpost.civilizationId(),
                            outpost.rivalCivilizationId(), outpost.x(), outpost.y(), outpost.z(),
                            outpost.foundedDay(), outpost.garrisonStrength()));
                } catch (IllegalArgumentException invalidOutpost) {
                    // Ignore corrupt camps so a single invalid record cannot block the world save.
                }
            }
        sim.setTimeDays(doc.timeDays());
        sim.setTickCount(doc.tickCount());
        sim.setSimulationSpeed(doc.simulationSpeed());
        sim.setCalendarStartYear(doc.calendarStartYear());
        return sim;
    }

    private SimulationSerializer() {
    }
}
