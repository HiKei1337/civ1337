package dev.autociv.simulation.world;

import dev.autociv.simulation.model.BuildingType;
import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.CivilizationTrait;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;

import java.util.List;

/** Condition-driven crises and booms; lifecycle state is persisted in civilization history. */
public final class WorldEventEngine {
    public void advanceDay(WorldSimulation simulation) {
        long day = simulation.tickCount();
        for (Settlement city : simulation.settlements()) {
            Civilization civilization = simulation.civilization(city.civilizationId()).orElse(null);
            if (civilization == null) continue;
            List<Citizen> residents = city.citizenIds().stream()
                    .map(id -> simulation.citizen(id).orElse(null))
                    .filter(citizen -> citizen != null && !citizen.isDead()).toList();
            int population = residents.size();
            double food = city.stockpile().get(ResourceType.FOOD);
            double averageHealth = residents.stream().mapToDouble(Citizen::health).average().orElse(1.0);
            double averageEducation = residents.stream().mapToDouble(Citizen::education).average().orElse(0.0);

            boolean famine = population >= 4 && food < Math.max(20, population * 1.5);
            if (civilization.setWorldEventActive("famine", city.id(), famine, day, simulation.year(),
                    famine ? "Начался голод в городе «" + city.name() + "» (запас еды "
                            + Math.round(food) + ")." : "Голод в городе «" + city.name() + "» преодолён.")) {
                city.setHappiness(city.happiness() + (famine ? -0.12 : 0.05));
            }
            if (civilization.worldEventActive("famine", city.id())) {
                double healthLoss = civilization.hasTrait(CivilizationTrait.AGRICULTURAL) ? 0.001 : 0.002;
                residents.forEach(citizen -> {
                    citizen.setHealth(citizen.health() - healthLoss);
                    citizen.setMood(citizen.mood() - 0.003);
                });
            }

            boolean epidemicActive = civilization.worldEventActive("epidemic", city.id());
            boolean epidemic = epidemicActive
                    ? population >= 4 && averageHealth <= 0.68 && city.buildingCount(BuildingType.CLINIC) == 0
                    : population >= 8 && averageHealth < 0.48 && city.buildingCount(BuildingType.CLINIC) == 0;
            if (civilization.setWorldEventActive("epidemic", city.id(), epidemic, day, simulation.year(),
                    epidemic ? "Эпидемия распространяется в городе «" + city.name() + "»; нужна клиника."
                            : "Эпидемия в городе «" + city.name() + "» отступила.")) {
                city.setHappiness(city.happiness() + (epidemic ? -0.10 : 0.04));
                city.setSecurity(city.security() + (epidemic ? -0.03 : 0.02));
            }
            if (civilization.worldEventActive("epidemic", city.id())) {
                residents.forEach(citizen -> {
                    citizen.setHealth(citizen.health() - 0.004);
                    citizen.setMood(citizen.mood() - 0.004);
                });
            }

            double educationThreshold = civilization.hasTrait(CivilizationTrait.SCIENTIFIC) ? 0.50 : 0.60;
            boolean goldenAgeActive = civilization.worldEventActive("golden_age", city.id());
            boolean goldenAge = goldenAgeActive
                    ? population >= 8 && averageEducation >= educationThreshold - 0.10
                            && city.happiness() >= 0.62 && food >= population * 3.0
                    : population >= 10 && averageEducation >= educationThreshold
                            && city.happiness() >= 0.78 && food >= population * 5.0;
            if (civilization.setWorldEventActive("golden_age", city.id(), goldenAge, day, simulation.year(),
                    goldenAge ? "В городе «" + city.name() + "» начался золотой век знаний."
                            : "Золотой век города «" + city.name() + "» завершился.")) {
                city.setHappiness(city.happiness() + (goldenAge ? 0.04 : -0.01));
            }
            if (civilization.worldEventActive("golden_age", city.id())) {
                civilization.addToCulture(0.15 + averageEducation * 0.1);
            }
        }
    }
}
