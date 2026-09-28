package dev.autociv.simulation;

import dev.autociv.simulation.economy.PriceSystem;
import dev.autociv.simulation.economy.TradeRoute;
import dev.autociv.simulation.economy.TradeShipment;
import dev.autociv.simulation.economy.TradeEngine;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.world.SimulationEngine;
import dev.autociv.simulation.world.WorldSimulation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeEngineTest {

    @Test
    void profitableTradeEscrowsGoodsUntilShipmentArrives() {
        WorldSimulation simulation = new WorldSimulation();
        Civilization seller = simulation.createCivilization("Surplus", 0x225522);
        Civilization buyer = simulation.createCivilization("Hungry", 0x552222);
        seller.addToTreasury(0);
        buyer.addToTreasury(10_000);
        Settlement source = simulation.createSettlement(seller.id(), "Granary", 0, 64, 0);
        Settlement destination = simulation.createSettlement(buyer.id(), "Harbor", 1000, 64, 0);
        source.stockpile().add(ResourceType.FOOD, 500);

        TradeEngine engine = new TradeEngine(new PriceSystem());
        int shipments = engine.clearMarkets(simulation);

        assertTrue(shipments > 0);
        assertTrue(source.stockpile().get(ResourceType.FOOD) < 500);
        assertEquals(0, destination.stockpile().get(ResourceType.FOOD), 0.001);
        assertTrue(buyer.treasury() < 10_000);
        assertEquals(0, seller.treasury(), 0.001);
        assertEquals(shipments, simulation.tradeShipments().size());
        assertEquals(1, simulation.tradeRoutes().size());
        assertEquals(1, simulation.merchants().size());
        assertEquals(0, simulation.tradeRoutes().iterator().next().completedShipments());

        double arrivalDay = simulation.tradeShipments().iterator().next().arrivalDay();
        simulation.setTimeDays(arrivalDay);
        assertEquals(shipments, engine.advanceShipments(simulation));
        assertTrue(destination.stockpile().get(ResourceType.FOOD) > 0);
        assertTrue(seller.treasury() > 0);
        assertTrue(simulation.tradeShipments().isEmpty());
        assertEquals(shipments, simulation.tradeRoutes().iterator().next().completedShipments());
        assertTrue(simulation.merchants().iterator().next().transportRevenue() > 0);
    }

    @Test
    void tradeDoesNotCreateDebtForACompletelyInsolventBuyer() {
        WorldSimulation simulation = new WorldSimulation();
        Civilization seller = simulation.createCivilization("Surplus", 0x225522);
        Civilization buyer = simulation.createCivilization("Insolvent", 0x552222);
        Settlement source = simulation.createSettlement(seller.id(), "Granary", 0, 64, 0);
        Settlement destination = simulation.createSettlement(buyer.id(), "Harbor", 100, 64, 0);
        source.stockpile().add(ResourceType.FOOD, 500);
        double initialFood = source.stockpile().get(ResourceType.FOOD);

        int shipments = new TradeEngine(new PriceSystem()).clearMarkets(simulation);

        assertEquals(0, shipments);
        assertEquals(initialFood, source.stockpile().get(ResourceType.FOOD), 0.001);
        assertEquals(0, destination.stockpile().get(ResourceType.FOOD), 0.001);
        assertEquals(0, buyer.treasury(), 0.001);
        assertTrue(simulation.tradeRoutes().isEmpty());
    }

    @Test
    void warCancelsAnInTransitShipmentAndRefundsEscrow() {
        WorldSimulation simulation = new WorldSimulation();
        Civilization seller = simulation.createCivilization("Surplus", 0x225522);
        Civilization buyer = simulation.createCivilization("Hungry", 0x552222);
        buyer.addToTreasury(10_000);
        Settlement source = simulation.createSettlement(seller.id(), "Granary", 0, 64, 0);
        simulation.createSettlement(buyer.id(), "Harbor", 100, 64, 0);
        source.stockpile().add(ResourceType.FOOD, 500);
        TradeEngine engine = new TradeEngine(new PriceSystem());
        double initialSellerStock = source.stockpile().get(ResourceType.FOOD);

        assertTrue(engine.clearMarkets(simulation) > 0);
        double heldTreasury = buyer.treasury();
        TradeShipment shipment = simulation.tradeShipments().iterator().next();
        buyer.setRelationWith(seller.id(), Civilization.Relation.WAR);
        seller.setRelationWith(buyer.id(), Civilization.Relation.WAR);
        simulation.setTimeDays(shipment.arrivalDay());

        assertEquals(0, engine.advanceShipments(simulation));
        assertEquals(initialSellerStock, source.stockpile().get(ResourceType.FOOD), 0.001);
        assertEquals(10_000, buyer.treasury(), 0.001);
        assertTrue(heldTreasury < buyer.treasury());
        assertTrue(simulation.tradeShipments().isEmpty());
    }

    @Test
    void routeIsReactivatedInsteadOfDuplicatedWhenTradeResumes() {
        WorldSimulation simulation = new WorldSimulation();
        Civilization seller = simulation.createCivilization("Surplus", 0x225522);
        Civilization buyer = simulation.createCivilization("Hungry", 0x552222);
        buyer.addToTreasury(10_000);
        Settlement source = simulation.createSettlement(seller.id(), "Granary", 0, 64, 0);
        Settlement destination = simulation.createSettlement(buyer.id(), "Harbor", 100, 64, 0);
        source.stockpile().add(ResourceType.FOOD, 500);
        TradeEngine engine = new TradeEngine(new PriceSystem());

        assertTrue(engine.clearMarkets(simulation) > 0);
        double arrivalDay = simulation.tradeShipments().iterator().next().arrivalDay();
        destination.stockpile().add(ResourceType.FOOD, 10_000);
        simulation.setTimeDays(arrivalDay);
        engine.advanceShipments(simulation);
        engine.clearMarkets(simulation);
        assertEquals(1, simulation.tradeRoutes().size());
        assertTrue(!simulation.tradeRoutes().iterator().next().active());

        destination.stockpile().remove(ResourceType.FOOD, 10_000);
        assertTrue(engine.clearMarkets(simulation) > 0);
        assertEquals(1, simulation.tradeRoutes().size());
        assertTrue(simulation.tradeRoutes().iterator().next().active());
    }

    @Test
    void deletingSettlementClosesItsHistoricalRoutes() {
        WorldSimulation simulation = new WorldSimulation();
        Civilization civ = simulation.createCivilization("Trade", 0x225522);
        Settlement source = simulation.createSettlement(civ.id(), "Source", 0, 64, 0);
        Settlement destination = simulation.createSettlement(civ.id(), "Destination", 100, 64, 0);
        TradeRoute route = new TradeRoute(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                source.id(), destination.id(), ResourceType.FOOD, 100, 1, 25, true);
        simulation.addTradeRoute(route);

        assertTrue(simulation.removeSettlement(source.id()));
        org.junit.jupiter.api.Assertions.assertFalse(route.active());
    }

    @Test
    void dailySimulationFundsTheTreasuryFromResidents() {
        WorldSimulation simulation = new WorldSimulation();
        Civilization civ = simulation.createCivilization("Town", 0x225522);
        Settlement city = simulation.createSettlement(civ.id(), "Center", 0, 64, 0);
        simulation.addCitizen(city, "Resident", 30, dev.autociv.simulation.model.Citizen.Profession.UNASSIGNED);

        new SimulationEngine().advanceDay(simulation);

        assertEquals(0.10, civ.treasury(), 0.001);
    }
}
