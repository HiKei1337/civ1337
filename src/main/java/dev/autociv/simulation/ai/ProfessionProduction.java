package dev.autociv.simulation.ai;

import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.CivilizationTrait;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.model.Region;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Applies profession-specific outputs and recipes during one abstract city day. */
public final class ProfessionProduction {

    private static final Map<ResourceType, Double> TOOL_RECIPE = Map.of(
            ResourceType.IRON, 2.0, ResourceType.COAL, 1.0);
    private static final Map<ResourceType, Double> WEAPON_RECIPE = Map.of(
            ResourceType.IRON, 3.0, ResourceType.COAL, 2.0);
    private static final Map<ResourceType, Double> BUILDING_MATERIAL_RECIPE = Map.of(
            ResourceType.WOOD, 2.0, ResourceType.STONE, 2.0);

    /** Returns food actually added; other production is recorded directly in the stockpile. */
    public double produce(Settlement city, List<Citizen> residents, Civilization civilization) {
        return produce(city, residents, civilization, List.of());
    }

    /** Regional richness modestly improves extraction and food production for its owner. */
    public double produce(Settlement city, List<Citizen> residents, Civilization civilization,
                          List<Region> ownedRegions) {
        Map<Citizen.Profession, Double> workers = new EnumMap<>(Citizen.Profession.class);
        for (Citizen citizen : residents) {
            if (citizen.isAdult()) {
                double averageNeed = citizen.needs().values().stream().mapToDouble(Double::doubleValue)
                        .average().orElse(1.0);
                double effectiveness = (0.4 + 0.6 * citizen.health() * averageNeed)
                        * (0.9 + 0.1 * citizen.education());
                workers.merge(citizen.profession(), effectiveness, Double::sum);
            }
        }

        double food = workers.getOrDefault(Citizen.Profession.FARMER, 0.0) * 4.0
                + workers.getOrDefault(Citizen.Profession.FISHERMAN, 0.0) * 3.0
                + workers.getOrDefault(Citizen.Profession.HUNTER, 0.0) * 2.0
                + workers.getOrDefault(Citizen.Profession.SHEPHERD, 0.0) * 2.0;
        food += workers.getOrDefault(Citizen.Profession.FARMER, 0.0)
                * city.buildingCount(dev.autociv.simulation.model.BuildingType.FARM) * 0.5;
        if (civilization != null && civilization.hasTrait(CivilizationTrait.AGRICULTURAL)) food *= 1.20;
        food *= regionalMultiplier(ownedRegions, ResourceType.FOOD)
                * city.role().productionMultiplier(ResourceType.FOOD);
        // Food's final net change is recorded by SimulationEngine after consumption.
        double appliedFood = city.stockpile().add(ResourceType.FOOD, food);
        double timberOutput = workers.getOrDefault(Citizen.Profession.LUMBERJACK, 0.0) * 2.0;
        if (civilization != null && civilization.hasTrait(CivilizationTrait.INDUSTRIAL)) timberOutput *= 1.10;
        add(city, ResourceType.WOOD, timberOutput
                * regionalMultiplier(ownedRegions, ResourceType.WOOD)
                * city.role().productionMultiplier(ResourceType.WOOD));

        double miners = workers.getOrDefault(Citizen.Profession.MINER, 0.0);
        int mines = city.buildingCount(dev.autociv.simulation.model.BuildingType.MINE);
        double miningOutput = civilization != null && civilization.hasTrait(CivilizationTrait.INDUSTRIAL)
                ? 1.20 : 1.0;
        add(city, ResourceType.STONE, miners * (2.0 + mines * 0.25) * miningOutput
                * regionalMultiplier(ownedRegions, ResourceType.STONE)
                * city.role().productionMultiplier(ResourceType.STONE));
        add(city, ResourceType.IRON, miners * (0.5 + mines * 0.1) * miningOutput
                * regionalMultiplier(ownedRegions, ResourceType.IRON)
                * city.role().productionMultiplier(ResourceType.IRON));
        add(city, ResourceType.COAL, miners * (0.5 + mines * 0.1) * miningOutput
                * regionalMultiplier(ownedRegions, ResourceType.COAL)
                * city.role().productionMultiplier(ResourceType.COAL));

        craft(city, workers.getOrDefault(Citizen.Profession.BLACKSMITH, 0.0), TOOL_RECIPE,
                ResourceType.TOOLS, 1.0);
        craft(city, workers.getOrDefault(Citizen.Profession.BLACKSMITH, 0.0), WEAPON_RECIPE,
                ResourceType.WEAPONS, 1.0);
        craft(city, workers.getOrDefault(Citizen.Profession.ENGINEER, 0.0), BUILDING_MATERIAL_RECIPE,
                ResourceType.BUILDING_MATERIALS, 1.0);

        if (civilization != null) {
            double tradeBonus = city.role() == dev.autociv.simulation.model.SettlementRole.TRADE ? 1.5 : 1.0;
            if (civilization.hasTrait(CivilizationTrait.TRADING)) tradeBonus *= 1.25;
            civilization.addToTreasury(workers.getOrDefault(Citizen.Profession.MERCHANT, 0.0)
                    * 0.5 * tradeBonus);
            double cultureBonus = civilization.hasTrait(CivilizationTrait.SCIENTIFIC) ? 1.5 : 1.0;
            civilization.addToCulture(workers.getOrDefault(Citizen.Profession.RESEARCHER, 0.0)
                    * 0.05 * cultureBonus);
        }

        double doctors = workers.getOrDefault(Citizen.Profession.DOCTOR, 0.0);
        if (doctors > 0 && !residents.isEmpty()) {
            double care = Math.min(0.01, doctors * 0.001);
            residents.stream().filter(citizen -> !citizen.isDead())
                    .forEach(citizen -> citizen.setHealth(citizen.health() + care));
        }
        double guards = workers.getOrDefault(Citizen.Profession.GUARD, 0.0)
                + workers.getOrDefault(Citizen.Profession.SOLDIER, 0.0);
        if (guards > 0) {
            double frontierBonus = city.role() == dev.autociv.simulation.model.SettlementRole.FRONTIER ? 1.5 : 1.0;
            if (civilization != null && civilization.hasTrait(CivilizationTrait.DEFENSIVE)) frontierBonus *= 1.25;
            city.setSecurity(city.security() + Math.min(0.01, guards * 0.001 * frontierBonus));
        }
        return appliedFood;
    }

    private double regionalMultiplier(List<Region> regions, ResourceType resource) {
        double potential = regions.stream().filter(region -> region.resource().equals(resource))
                .mapToDouble(Region::richness).sum();
        return 1.0 + Math.min(0.35, potential * 0.05);
    }

    private void craft(Settlement city, double workers, Map<ResourceType, Double> recipe,
                       ResourceType output, double outputAmount) {
        double remainingOutput = workers * outputAmount;
        while (remainingOutput > 1.0e-9) {
            double outputSpace = city.stockpile().capacity(output) - city.stockpile().get(output);
            if (outputSpace <= 1.0e-9) {
                break;
            }
            double portion = Math.min(Math.min(1.0, remainingOutput / outputAmount), outputSpace / outputAmount);
            Map<ResourceType, Double> costs = new java.util.HashMap<>();
            recipe.forEach((resource, amount) -> costs.put(resource, amount * portion));
            if (!city.stockpile().tryRemove(costs)) {
                break;
            }
            costs.forEach((resource, amount) -> city.stockpile().recordDelta(resource, -amount));
            add(city, output, outputAmount * portion);
            remainingOutput -= outputAmount * portion;
        }
    }

    private double add(Settlement city, ResourceType resource, double amount) {
        double applied = city.stockpile().add(resource, amount);
        city.stockpile().recordDelta(resource, applied);
        return applied;
    }
}
