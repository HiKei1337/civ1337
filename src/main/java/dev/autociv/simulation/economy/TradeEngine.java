package dev.autociv.simulation.economy;

import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.CivilizationTrait;
import dev.autociv.simulation.model.BuildingType;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.world.WorldSimulation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Matches daily surpluses and deficits, reserving goods and escrow until a shipment arrives. */
public final class TradeEngine {

    private static final double SHIPMENT_LIMIT = 100.0;
    private static final double TRANSPORT_COST_PER_BLOCK = 0.001;
    private static final double ROUTE_BLOCKS_PER_DAY = 350.0;
    private static final int MAX_TRAVEL_DAYS = 360;
    private final PriceSystem prices;

    private record RouteResource(UUID settlementId, ResourceType resource) { }

    public TradeEngine(PriceSystem prices) {
        this.prices = prices;
    }

    /** Dispatches new cargo. The return value is the number of departures, not arrivals. */
    public int clearMarkets(WorldSimulation simulation) {
        advanceShipments(simulation);
        Map<UUID, Settlement> settlements = new HashMap<>();
        Map<UUID, Civilization> owners = new HashMap<>();
        Map<UUID, Market> markets = new HashMap<>();
        List<TradeOffer> sellers = new ArrayList<>();
        Map<ResourceType, List<TradeOffer>> buyersByResource = new HashMap<>();
        for (Settlement settlement : simulation.settlements()) {
            settlements.put(settlement.id(), settlement);
            simulation.civilization(settlement.civilizationId()).ifPresent(civ -> owners.put(settlement.id(), civ));
            Market market = new Market(settlement, prices);
            markets.put(settlement.id(), market);
            for (TradeOffer offer : market.offers()) {
                if (offer.side() == TradeOffer.Side.SELL) sellers.add(offer);
                else buyersByResource.computeIfAbsent(offer.resource(), ignored -> new ArrayList<>()).add(offer);
            }
        }
        closeUnprofitableRoutes(simulation, settlements, markets);
        sellers.sort(Comparator.comparingDouble(TradeOffer::unitPrice)
                .thenComparing(offer -> offer.settlementId().toString()));
        Map<TradeOffer, Double> remainingDemand = new HashMap<>();
        Map<RouteResource, Double> inbound = new HashMap<>();
        for (TradeShipment shipment : simulation.tradeShipments()) {
            TradeRoute route = simulation.tradeRoute(shipment.routeId()).orElse(null);
            if (route != null) inbound.merge(new RouteResource(route.destinationSettlementId(), shipment.resource()),
                    shipment.quantity(), Double::sum);
        }
        buyersByResource.values().forEach(buyers -> {
            buyers.sort(Comparator.comparingDouble(TradeOffer::unitPrice).reversed()
                    .thenComparing(offer -> offer.settlementId().toString()));
            buyers.forEach(buy -> remainingDemand.put(buy, Math.max(0, buy.quantity()
                    - inbound.getOrDefault(new RouteResource(buy.settlementId(), buy.resource()), 0.0))));
        });

        int dispatched = 0;
        for (TradeOffer sell : sellers) {
            Settlement source = settlements.get(sell.settlementId());
            double remainingSupply = Math.min(sell.quantity(), source.stockpile().get(sell.resource())
                    - prices.targetStock(source, sell.resource()));
            for (TradeOffer buy : buyersByResource.getOrDefault(sell.resource(), List.of())) {
                double demand = remainingDemand.getOrDefault(buy, 0.0);
                if (remainingSupply <= 0 || demand <= 0 || buy.settlementId().equals(sell.settlementId())) continue;
                Settlement destination = settlements.get(buy.settlementId());
                Civilization buyer = owners.get(destination.id());
                Civilization seller = owners.get(source.id());
                if (buyer == null || seller == null
                        || buyer.relationWith(seller.id()) == Civilization.Relation.WAR
                        || seller.relationWith(buyer.id()) == Civilization.Relation.WAR) continue;

                TravelPlan travelPlan = travelPlan(simulation, source, destination);
                double distance = travelPlan.distance();
                double sellerPrice = sell.unitPrice();
                double purchasePrice = sellerPrice + transportCost(simulation, source, destination,
                        distance, travelPlan.road(), travelPlan.rail());
                if (markets.get(destination.id()).price(buy.resource()) <= purchasePrice) continue;
                double quantity = Math.min(SHIPMENT_LIMIT, Math.min(remainingSupply, demand));
                quantity = Math.min(quantity, destination.stockpile().capacity(buy.resource())
                        - destination.stockpile().get(buy.resource())
                        - inbound.getOrDefault(new RouteResource(destination.id(), buy.resource()), 0.0));
                quantity = Math.min(quantity, buyer.treasury() / purchasePrice);
                if (quantity <= 0 || !buyer.withdrawFromTreasury(quantity * purchasePrice)) continue;

                double removed = source.stockpile().remove(sell.resource(), quantity);
                if (removed <= 0) {
                    buyer.addToTreasury(quantity * purchasePrice);
                    continue;
                }
                if (removed < quantity) buyer.addToTreasury((quantity - removed) * purchasePrice);
                source.stockpile().recordDelta(sell.resource(), -removed);
                TradeRoute route = getOrCreateRoute(simulation, source, destination, sell.resource(), distance);
                double arrivalDay = simulation.timeDays() + travelDays(simulation, source, destination,
                        distance, travelPlan.road(), travelPlan.rail());
                TradeShipment shipment = new TradeShipment(UUID.randomUUID(), route.id(), buyer.id(), seller.id(),
                        sell.resource(), removed, purchasePrice, sellerPrice, simulation.timeDays(), arrivalDay);
                simulation.addTradeShipment(shipment);
                RouteResource key = new RouteResource(destination.id(), sell.resource());
                inbound.merge(key, removed, Double::sum);
                remainingSupply -= removed;
                remainingDemand.put(buy, demand - removed);
                dispatched++;
            }
        }
        return dispatched;
    }

    /** Settles due cargo and refunds canceled or undeliverable portions from escrow. */
    public int advanceShipments(WorldSimulation simulation) {
        int arrivals = 0;
        for (TradeShipment shipment : new ArrayList<>(simulation.tradeShipments())) {
            if (shipment.arrivalDay() > simulation.timeDays()) continue;
            TradeRoute route = simulation.tradeRoute(shipment.routeId()).orElse(null);
            Settlement source = route == null ? null
                    : simulation.settlement(route.sourceSettlementId()).orElse(null);
            Settlement destination = route == null ? null
                    : simulation.settlement(route.destinationSettlementId()).orElse(null);
            Civilization buyer = simulation.civilization(shipment.buyerCivilizationId()).orElse(null);
            Civilization seller = simulation.civilization(shipment.sellerCivilizationId()).orElse(null);
            boolean atWar = buyer != null && seller != null
                    && (buyer.relationWith(seller.id()) == Civilization.Relation.WAR
                    || seller.relationWith(buyer.id()) == Civilization.Relation.WAR);
            if (route == null || destination == null || buyer == null || seller == null || atWar) {
                returnCargo(source, shipment);
                refund(buyer, shipment.escrowedAmount());
                simulation.removeTradeShipment(shipment.id());
                continue;
            }

            double delivered = destination.stockpile().add(shipment.resource(), shipment.quantity());
            if (delivered > 0) {
                destination.stockpile().recordDelta(shipment.resource(), delivered);
                seller.addToTreasury(delivered * shipment.sellerUnitPrice());
                double paid = delivered * shipment.buyerUnitPrice();
                refund(buyer, Math.max(0, shipment.escrowedAmount() - paid));
                route.recordShipment(delivered);
                double transportFee = delivered * Math.max(0,
                        shipment.buyerUnitPrice() - shipment.sellerUnitPrice());
                simulation.merchant(route.merchantId())
                        .ifPresent(merchant -> merchant.recordDelivery(delivered, transportFee));
                arrivals++;
            } else {
                refund(buyer, shipment.escrowedAmount());
            }
            if (delivered < shipment.quantity()) returnCargo(source, shipment, shipment.quantity() - delivered);
            simulation.removeTradeShipment(shipment.id());
        }
        return arrivals;
    }

    private void returnCargo(Settlement source, TradeShipment shipment) {
        returnCargo(source, shipment, shipment.quantity());
    }

    private void returnCargo(Settlement source, TradeShipment shipment, double quantity) {
        if (source == null || quantity <= 0) return;
        double returned = source.stockpile().add(shipment.resource(), quantity);
        if (returned > 0) source.stockpile().recordDelta(shipment.resource(), returned);
    }

    private void refund(Civilization buyer, double amount) {
        if (buyer != null && amount > 0) buyer.addToTreasury(amount);
    }

    private void closeUnprofitableRoutes(WorldSimulation simulation, Map<UUID, Settlement> settlements,
                                         Map<UUID, Market> markets) {
        for (TradeRoute route : simulation.tradeRoutes()) {
            Settlement source = settlements.get(route.sourceSettlementId());
            Settlement destination = settlements.get(route.destinationSettlementId());
            if (source == null || destination == null || !markets.containsKey(source.id())
                    || !markets.containsKey(destination.id())) {
                route.setActive(false);
                continue;
            }
            Civilization sourceOwner = simulation.civilization(source.civilizationId()).orElse(null);
            Civilization destinationOwner = simulation.civilization(destination.civilizationId()).orElse(null);
            if (sourceOwner == null || destinationOwner == null
                    || sourceOwner.relationWith(destinationOwner.id()) == Civilization.Relation.WAR
                    || destinationOwner.relationWith(sourceOwner.id()) == Civilization.Relation.WAR) {
                route.setActive(false);
                continue;
            }
            TravelPlan travelPlan = travelPlan(simulation, source, destination);
            double distance = travelPlan.distance();
            boolean destinationStillNeeds = destination.stockpile().get(route.resource())
                    < prices.targetStock(destination, route.resource()) * 0.9;
            boolean profitable = markets.get(destination.id()).price(route.resource())
                    > markets.get(source.id()).price(route.resource())
                    + transportCost(simulation, source, destination, distance,
                    travelPlan.road(), travelPlan.rail());
            if (!destinationStillNeeds || !profitable) route.setActive(false);
        }
    }

    private double transportCost(WorldSimulation simulation, Settlement source, Settlement destination,
                                 double distance, boolean connectedByRoad, boolean connectedByRail) {
        double efficiency = 1.0;
        if (source.buildingCount(BuildingType.MARKET) > 0) efficiency *= 0.85;
        if (destination.buildingCount(BuildingType.MARKET) > 0) efficiency *= 0.85;
        if (!connectedByRoad && !connectedByRail) {
            if (source.buildingCount(BuildingType.ROAD) > 0) efficiency *= 0.9;
            if (destination.buildingCount(BuildingType.ROAD) > 0) efficiency *= 0.9;
        }
        if (simulation.hasAgreement(source.civilizationId(), destination.civilizationId(),
                dev.autociv.simulation.model.DiplomaticAgreement.Type.TRADE_PACT)) efficiency *= 0.8;
        if (simulation.hasAgreement(source.civilizationId(), destination.civilizationId(),
                dev.autociv.simulation.model.DiplomaticAgreement.Type.ALLIANCE)) efficiency *= 0.9;
        Civilization sourceOwner = simulation.civilization(source.civilizationId()).orElse(null);
        Civilization destinationOwner = simulation.civilization(destination.civilizationId()).orElse(null);
        boolean sourceTrader = sourceOwner != null && sourceOwner.hasTrait(CivilizationTrait.TRADING);
        boolean destinationTrader = destinationOwner != null
                && destinationOwner.hasTrait(CivilizationTrait.TRADING);
        if (sourceTrader && destinationTrader) efficiency *= 0.75;
        else if (sourceTrader || destinationTrader) efficiency *= 0.88;
        return distance * TRANSPORT_COST_PER_BLOCK * efficiency;
    }

    private int travelDays(WorldSimulation simulation, Settlement source, Settlement destination,
                           double distance, boolean connectedByRoad, boolean connectedByRail) {
        double speed = 1.0;
        if (connectedByRail) {
            speed *= 1.25;
        } else if (!connectedByRoad) {
            if (source.buildingCount(BuildingType.ROAD) > 0) speed *= 1.2;
            if (destination.buildingCount(BuildingType.ROAD) > 0) speed *= 1.2;
        }
        if (source.buildingCount(BuildingType.MARKET) > 0) speed *= 1.05;
        if (destination.buildingCount(BuildingType.MARKET) > 0) speed *= 1.05;
        int days = (int) Math.ceil(distance / (ROUTE_BLOCKS_PER_DAY * speed));
        return Math.clamp(days, 1, MAX_TRAVEL_DAYS);
    }

    private record TravelPlan(double distance, boolean road, boolean rail) { }

    /** Chooses the fastest currently completed corridor, retaining direct travel as fallback. */
    private TravelPlan travelPlan(WorldSimulation simulation, Settlement source, Settlement destination) {
        RoadNetwork.Route road = RoadNetwork.route(simulation, source, destination);
        RailNetwork.Route rail = RailNetwork.route(simulation, source, destination);
        if (rail.connected() && (!road.connected() || rail.travelDistance() < road.travelDistance())) {
            return new TravelPlan(rail.travelDistance(), false, true);
        }
        if (road.connected()) return new TravelPlan(road.travelDistance(), true, false);
        return new TravelPlan(road.directDistance(), false, false);
    }

    private TradeRoute getOrCreateRoute(WorldSimulation simulation, Settlement source, Settlement destination,
                                        ResourceType resource, double distance) {
        TradeRoute route = simulation.tradeRoutes().stream()
                .filter(candidate -> candidate.sourceSettlementId().equals(source.id())
                        && candidate.destinationSettlementId().equals(destination.id())
                        && candidate.resource().equals(resource))
                .findFirst().orElse(null);
        if (route == null) {
            UUID routeId = UUID.randomUUID();
            UUID merchantId = UUID.randomUUID();
            route = new TradeRoute(routeId, merchantId, source.id(), destination.id(), resource,
                    distance, 0, 0, true);
            simulation.addTradeRoute(route);
            simulation.addMerchant(new Merchant(merchantId, routeId, 0, 0));
        } else {
            route.setActive(true);
        }
        return route;
    }
}
