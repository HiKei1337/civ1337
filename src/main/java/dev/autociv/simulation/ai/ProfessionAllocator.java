package dev.autociv.simulation.ai;

import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Rebalances adult abstract workers when a city has a persistent resource or safety need. */
public final class ProfessionAllocator {

    public record AllocationResult(int farmers, int lumberjacks, int miners, int guards, int changed) {
    }

    public AllocationResult rebalance(Settlement settlement, List<Citizen> citizens) {
        List<Citizen> adults = citizens.stream().filter(Citizen::isAdult)
                .sorted(Comparator.comparing(Citizen::id)).toList();
        int population = adults.size();
        if (population == 0) {
            return new AllocationResult(0, 0, 0, 0, 0);
        }

        Map<Citizen.Profession, Integer> targets = new EnumMap<>(Citizen.Profession.class);
        int existingBuilders = count(adults, Citizen.Profession.BUILDER);
        boolean constructionDemand = settlement.physicalSettlementGenerated()
                && (settlement.homeConstructionDays() > 0
                || settlement.materializedHomes() < settlement.completedHomes()
                || settlement.activeBuildingPlan() != null || !settlement.pendingPhysicalBuildings().isEmpty()
                || !settlement.buildingQueue().isEmpty());
        targets.put(Citizen.Profession.BUILDER, constructionDemand
                ? Math.max(1, existingBuilders) : existingBuilders);
        int existingFarmers = count(adults, Citizen.Profession.FARMER);
        double foodTarget = Math.max(100, population * 10.0);
        boolean foodShortage = settlement.stockpile().get(ResourceType.FOOD) < foodTarget * 0.75
                || settlement.stockpile().deltaPerDay(ResourceType.FOOD) < 0;
        targets.put(Citizen.Profession.FARMER, foodShortage
                ? Math.max(existingFarmers, Math.max(1, (int) Math.ceil(population
                        * (settlement.role() == dev.autociv.simulation.model.SettlementRole.AGRICULTURE ? 0.45 : 0.3))))
                : Math.max(existingFarmers, Math.max(1, (int) Math.ceil(population
                        * (settlement.role() == dev.autociv.simulation.model.SettlementRole.AGRICULTURE ? 0.30 : 0.15)))));

        double woodTarget = Math.max(80, population * 3.0);
        int existingLumberjacks = count(adults, Citizen.Profession.LUMBERJACK);
        targets.put(Citizen.Profession.LUMBERJACK,
                settlement.stockpile().get(ResourceType.WOOD) < woodTarget
                        ? Math.max(existingLumberjacks, Math.max(1, population / 12))
                        : Math.max(existingLumberjacks, settlement.role()
                                == dev.autociv.simulation.model.SettlementRole.FORESTRY
                                ? Math.max(1, (int) Math.ceil(population * 0.20)) : 0));

        double mineralTarget = Math.max(50, population * 1.5);
        int existingMiners = count(adults, Citizen.Profession.MINER);
        boolean mineralShortage = settlement.stockpile().get(ResourceType.IRON) < mineralTarget
                || settlement.stockpile().get(ResourceType.STONE) < mineralTarget;
        targets.put(Citizen.Profession.MINER, mineralShortage
                ? Math.max(existingMiners, Math.max(1, population / 15))
                : Math.max(existingMiners, settlement.role() == dev.autociv.simulation.model.SettlementRole.MINING
                        ? Math.max(1, (int) Math.ceil(population * 0.20)) : 0));

        int merchants = count(adults, Citizen.Profession.MERCHANT);
        targets.put(Citizen.Profession.MERCHANT, settlement.role()
                == dev.autociv.simulation.model.SettlementRole.TRADE && population >= 8
                ? Math.max(merchants, Math.max(1, population / 12)) : merchants);

        int blacksmiths = count(adults, Citizen.Profession.BLACKSMITH);
        boolean craftingInputsAvailable = settlement.stockpile().get(ResourceType.IRON) >= 2
                && settlement.stockpile().get(ResourceType.COAL) >= 1;
        targets.put(Citizen.Profession.BLACKSMITH, population >= 12 && craftingInputsAvailable
                && settlement.stockpile().get(ResourceType.TOOLS) < 20
                ? Math.max(blacksmiths, 1) : blacksmiths);

        int engineers = count(adults, Citizen.Profession.ENGINEER);
        boolean needsBuildingMaterials = settlement.stockpile().get(ResourceType.BUILDING_MATERIALS)
                < Math.max(10, population * 0.5);
        targets.put(Citizen.Profession.ENGINEER, population >= 12 && needsBuildingMaterials
                && settlement.stockpile().get(ResourceType.WOOD) >= 2
                && settlement.stockpile().get(ResourceType.STONE) >= 2
                ? Math.max(engineers, 1) : engineers);

        int doctors = count(adults, Citizen.Profession.DOCTOR);
        double averageHealth = adults.stream().mapToDouble(Citizen::health).average().orElse(1.0);
        targets.put(Citizen.Profession.DOCTOR, population >= 20 && averageHealth < 0.8
                ? Math.max(doctors, 1) : doctors);

        int researchers = count(adults, Citizen.Profession.RESEARCHER);
        targets.put(Citizen.Profession.RESEARCHER,
                population >= 50 ? Math.max(researchers, 1) : researchers);

        int existingGuards = count(adults, Citizen.Profession.GUARD) + count(adults, Citizen.Profession.SOLDIER);
        targets.put(Citizen.Profession.GUARD, settlement.security() < 0.5
                ? Math.max(existingGuards, Math.max(1, population / 12)) : 0);

        int changed = 0;
        for (Citizen.Profession profession : List.of(Citizen.Profession.BUILDER, Citizen.Profession.FARMER,
                Citizen.Profession.GUARD, Citizen.Profession.SOLDIER,
                Citizen.Profession.LUMBERJACK, Citizen.Profession.MINER,
                Citizen.Profession.MERCHANT, Citizen.Profession.BLACKSMITH, Citizen.Profession.ENGINEER,
                Citizen.Profession.DOCTOR, Citizen.Profession.RESEARCHER)) {
            int current = profession == Citizen.Profession.GUARD
                    ? count(adults, Citizen.Profession.GUARD) + count(adults, Citizen.Profession.SOLDIER)
                    : count(adults, profession);
            int target = targets.getOrDefault(profession, 0);
            for (Citizen citizen : adults) {
                if (current >= target) {
                    break;
                }
                if (!canReassign(citizen.profession(), profession)) {
                    continue;
                }
                citizen.setProfession(profession);
                current++;
                changed++;
            }
        }

        return new AllocationResult(count(adults, Citizen.Profession.FARMER),
                count(adults, Citizen.Profession.LUMBERJACK), count(adults, Citizen.Profession.MINER),
                count(adults, Citizen.Profession.GUARD) + count(adults, Citizen.Profession.SOLDIER), changed);
    }

    private boolean canReassign(Citizen.Profession current, Citizen.Profession target) {
        if (current == target || (target == Citizen.Profession.GUARD && current == Citizen.Profession.SOLDIER)) {
            return false;
        }
        return current == Citizen.Profession.UNASSIGNED || current == Citizen.Profession.MERCHANT
                || current == Citizen.Profession.RESEARCHER || current == Citizen.Profession.BUILDER;
    }

    private int count(List<Citizen> citizens, Citizen.Profession profession) {
        return (int) citizens.stream().filter(citizen -> citizen.profession() == profession).count();
    }
}
