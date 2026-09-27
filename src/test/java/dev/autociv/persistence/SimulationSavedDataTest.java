package dev.autociv.persistence;

import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.model.SettlementRole;
import dev.autociv.simulation.model.ColonyPriority;
import dev.autociv.simulation.model.DevelopmentStrategy;
import dev.autociv.simulation.model.CraftingOrder;
import dev.autociv.simulation.model.BuildingType;
import dev.autociv.simulation.world.SimulationSpeed;
import dev.autociv.scenario.CivilizationScenario;
import dev.autociv.simulation.economy.Merchant;
import dev.autociv.simulation.economy.TradeRoute;
import dev.autociv.simulation.economy.TradeShipment;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimulationSavedDataTest {

    @Test
    void savesAndLoadsCivilizationsCitiesCitizensAndResources() {
        SimulationSavedData original = new SimulationSavedData();
        Civilization civilization = original.simulation().createCivilization("Oakland", 0x2E8B57);
        Civilization neighbour = original.simulation().createCivilization("Riverland", 0x456789);
        Settlement city = original.simulation().createSettlement(civilization.id(), "Oaktown", 100, 64, -200);
        Settlement neighbourCity = original.simulation().createSettlement(neighbour.id(), "Riverport", 1000, 64, 1000);
        Settlement daughterCity = original.simulation().createSettlement(civilization.id(), "Pineford", 350, 64, -200);
        original.simulation().linkSettlement(daughterCity, city, SettlementRole.FORESTRY, -750);
        civilization.knowSettlement(neighbourCity.id());
        Citizen citizen = original.simulation().addCitizen(city, "Ada", 27, Citizen.Profession.FARMER);
        citizen.carryItem("minecraft:spruce_log", 4);
        city.stockpile().add(ResourceType.FOOD, 125.5);
        city.addPopulationGrowthProgress(0.35);
        city.setHomeBuildingState(2, 1, 7);
        UUID owner = UUID.randomUUID();
        city.claim(owner);
        city.setPriority(ColonyPriority.DEFENSE);
        city.setStrategy(DevelopmentStrategy.FORTIFY);
        city.queueBuilding(BuildingType.FORTIFICATION, 0);
        city.queueCrafting(new CraftingOrder("minecraft:oak_planks", 3));
        city.setBuildingMaterialDemand("minecraft:spruce_planks");
        civilization.addToTreasury(300);
        civilization.setScenario("rome", "Roman Kingdom, traditionally founded 753 BCE", -753, "roman");
        original.simulation().setSimulationSpeed(SimulationSpeed.FAST);
        original.simulation().setCalendarStartYear(-753);
        UUID routeId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        original.simulation().addTradeRoute(new TradeRoute(routeId, merchantId, city.id(),
            neighbourCity.id(), ResourceType.FOOD, 1000, 3, 75, true));
        original.simulation().addMerchant(new Merchant(merchantId, routeId, 3, 75, 0.6));
        UUID shipmentId = UUID.randomUUID();
        original.simulation().addTradeShipment(new TradeShipment(shipmentId, routeId, neighbour.id(),
                civilization.id(), ResourceType.FOOD, 12, 1.4, 1.1, 2, 5));

        CompoundTag savedTag = original.save(new CompoundTag(), null);
        SimulationSavedData restored = SimulationSavedData.load(savedTag, null);

        Civilization loadedCivilization = restored.simulation().civilization(civilization.id()).orElseThrow();
        Settlement loadedCity = restored.simulation().settlement(city.id()).orElseThrow();
        Citizen loadedCitizen = restored.simulation().citizen(citizen.id()).orElseThrow();
        assertEquals("Oakland", loadedCivilization.name());
        assertEquals(0x2E8B57, loadedCivilization.color());
        assertEquals(300, loadedCivilization.treasury(), 0.001);
        assertEquals("rome", loadedCivilization.scenarioId());
        assertEquals(-753, loadedCivilization.historicalStartYear());
        assertEquals("roman", loadedCivilization.architectureStyle());
        assertEquals(java.util.Set.of(neighbourCity.id()), loadedCivilization.knownSettlementIds());
        assertEquals(city.id(), loadedCivilization.capitalSettlementId());
        assertEquals(civilization.id(), loadedCity.civilizationId());
        Settlement loadedDaughter = restored.simulation().settlement(daughterCity.id()).orElseThrow();
        assertEquals(city.id(), loadedDaughter.parentSettlementId());
        assertEquals(SettlementRole.FORESTRY, loadedDaughter.role());
        assertEquals(-750, loadedDaughter.foundedYear());
        assertEquals(100, loadedCity.x());
        assertEquals(64, loadedCity.y());
        assertEquals(-200, loadedCity.z());
        assertEquals(125.5, loadedCity.stockpile().get(ResourceType.FOOD), 0.001);
        assertEquals(city.id(), loadedCitizen.homeSettlementId());
        assertEquals(Citizen.Profession.FARMER, loadedCitizen.profession());
        assertEquals(java.util.Map.of("minecraft:spruce_log", 4), loadedCitizen.itemCargo());
        assertEquals(1, restored.simulation().totalPopulation());
        assertEquals(0.35, loadedCity.populationGrowthProgress(), 0.001);
        assertEquals(2, loadedCity.completedHomes());
        assertEquals(1, loadedCity.materializedHomes());
        assertEquals(7, loadedCity.homeConstructionDays());
        assertEquals(owner, loadedCity.ownerPlayerId());
        assertEquals(ColonyPriority.DEFENSE, loadedCity.priority());
        assertEquals(DevelopmentStrategy.FORTIFY, loadedCity.strategy());
        assertEquals(java.util.List.of(BuildingType.FORTIFICATION), loadedCity.buildingQueue());
        assertEquals(java.util.List.of(new CraftingOrder("minecraft:oak_planks", 3)), loadedCity.craftingQueue());
        assertEquals("minecraft:spruce_planks", loadedCity.buildingMaterialDemand());
        assertEquals(new CraftingOrder("minecraft:oak_planks", 2),
                loadedCity.nextCraftingOrder().completeBatch());
        assertEquals(SimulationSpeed.FAST, restored.simulation().simulationSpeed());
        assertEquals(-753, restored.simulation().calendarStartYear());
        TradeRoute loadedRoute = restored.simulation().tradeRoute(routeId).orElseThrow();
        Merchant loadedMerchant = restored.simulation().merchant(merchantId).orElseThrow();
        assertEquals(3, loadedRoute.completedShipments());
        assertEquals(75, loadedMerchant.lifetimeQuantity(), 0.001);
        assertEquals(0.6, loadedMerchant.transportRevenue(), 0.001);
        TradeShipment loadedShipment = restored.simulation().tradeShipments().stream()
                .filter(shipment -> shipment.id().equals(shipmentId)).findFirst().orElseThrow();
        assertEquals(12, loadedShipment.quantity(), 0.001);
        assertEquals(1.4, loadedShipment.buyerUnitPrice(), 0.001);
        assertEquals(5, loadedShipment.arrivalDay(), 0.001);
    }
}
