package dev.autociv.scenario;

import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.CivilizationTrait;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.world.WorldSimulation;

import java.util.List;

/** Creates a scenario's abstract civilization state at world-generation sites. */
public final class ScenarioInitializer {

    private ScenarioInitializer() {
    }

    public static Civilization initialize(WorldSimulation simulation, CivilizationScenario scenario,
                                          List<SettlementSite> sites) {
        return initialize(simulation, scenario, sites, 0);
    }

    /**
     * Initializes a capital with a maturity appropriate to how far it is from the player.
     * Distant starts are established towns rather than identical four-person camps.
     */
    public static Civilization initialize(WorldSimulation simulation, CivilizationScenario scenario,
                                          List<SettlementSite> sites, int distanceFromPlayer) {
        if (sites.isEmpty()) {
            throw new IllegalArgumentException("Scenario requires at least one settlement site");
        }
        // Each new civilization begins at one capital. The remaining scenario
        // locations/names are available to later expansion, not instant cities.
        int cityCount = 1;

        if (simulation.civilizations().isEmpty()) {
            simulation.setCalendarStartYear(scenario.startYear());
        }
        Civilization civilization = simulation.createCivilization(scenario.name(), scenario.color());
        civilization.setScenario(scenario.id(), scenario.era(), scenario.startYear(),
                scenario.architecture().name().toLowerCase(java.util.Locale.ROOT));
        civilization.setTraits(CivilizationTrait.forScenario(scenario.id()));
        int foundingPopulation = initialPopulationForDistance(distanceFromPlayer,
                scenario.initialPopulation());
        double prosperity = foundingPopulation / 4.0;
        civilization.addToTreasury(scenario.treasury() * prosperity);
        civilization.addToCulture(scenario.culture() * prosperity);
        civilization.setMilitaryStrength(scenario.militaryStrength() * prosperity);

        // Near settlements are four-person camps; distant capitals use the
        // established population selected from their distance band.
        int remainingPopulation = foundingPopulation;
        for (int cityIndex = 0; cityIndex < cityCount; cityIndex++) {
            SettlementSite site = sites.get(cityIndex);
            Settlement city = simulation.createSettlement(civilization.id(), scenario.cityNames().get(cityIndex),
                    site.x(), site.y(), site.z());
            scenario.resources().forEach((resource, amount) ->
                    city.stockpile().add(resource, amount * prosperity / cityCount));

            int cityPopulation = remainingPopulation / (cityCount - cityIndex);
            remainingPopulation -= cityPopulation;
            city.setHousingCapacity(cityPopulation);
            for (int resident = 0; resident < cityPopulation; resident++) {
                Citizen.Profession profession = foundingProfession(resident, civilization);
                String name = scenario.name() + " Citizen " + (resident + 1);
                simulation.addCitizen(city, name, 18 + resident % 43, profession);
            }
            // Homes belong to the abstract city from founding, but none count as physically
            // materialized until their settlement chunks load and the world builder places them.
            city.setHomeBuildingState(cityPopulation, 0, 0);
            city.setSecurity(Math.min(0.9, 0.65 + (cityPopulation - 4) * 0.02));
            city.setHappiness(Math.min(0.9, 0.70 + (cityPopulation - 4) * 0.015));
        }
        return civilization;
    }

    public static int initialPopulationForDistance(int distance, int scenarioPopulation) {
        int establishedPopulation = distance < 1_500 ? 4
                : distance < 3_000 ? 6
                : distance < 4_500 ? 8
                : distance < 5_500 ? 10
                : distance < 7_500 ? 12 : 16;
        return Math.min(Math.max(1, scenarioPopulation), establishedPopulation);
    }

    private static Citizen.Profession foundingProfession(int resident, Civilization civilization) {
        return switch (resident) {
            case 0 -> Citizen.Profession.FARMER;
            case 1 -> Citizen.Profession.MINER;
            case 2 -> Citizen.Profession.LUMBERJACK;
            case 3 -> Citizen.Profession.BUILDER;
            case 4 -> civilization.hasTrait(CivilizationTrait.TRADING)
                    ? Citizen.Profession.MERCHANT : Citizen.Profession.FARMER;
            case 5 -> civilization.hasTrait(CivilizationTrait.MILITARISTIC)
                    || civilization.hasTrait(CivilizationTrait.DEFENSIVE)
                    ? Citizen.Profession.GUARD : Citizen.Profession.FARMER;
            case 6, 10, 12, 14 -> civilization.hasTrait(CivilizationTrait.SCIENTIFIC)
                    ? Citizen.Profession.RESEARCHER : Citizen.Profession.FARMER;
            case 7, 13 -> civilization.hasTrait(CivilizationTrait.INDUSTRIAL)
                    ? Citizen.Profession.BLACKSMITH : Citizen.Profession.MINER;
            case 8 -> civilization.hasTrait(CivilizationTrait.INDUSTRIAL)
                    ? Citizen.Profession.ENGINEER : Citizen.Profession.LUMBERJACK;
            case 9 -> civilization.hasTrait(CivilizationTrait.EXPANSIONIST)
                    ? Citizen.Profession.SOLDIER : Citizen.Profession.FARMER;
            default -> civilization.hasTrait(CivilizationTrait.MILITARISTIC)
                    ? Citizen.Profession.GUARD : Citizen.Profession.FARMER;
        };
    }

    public record SettlementSite(int x, int y, int z) {
    }
}
