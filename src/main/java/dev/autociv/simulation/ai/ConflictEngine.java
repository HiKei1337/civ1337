package dev.autociv.simulation.ai;

import dev.autociv.simulation.model.BuildingType;
import dev.autociv.simulation.model.BorderOutpost;
import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.CivilizationTrait;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.world.WorldSimulation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Resolves one bounded, supply-backed border campaign every thirty simulation days. */
public final class ConflictEngine {
    private static final int BATTLE_INTERVAL_DAYS = 30;
    private static final int STANDOFF_WAR_DAYS = 180;
    private static final int MAX_FRONT_DISTANCE = 1_280;
    private static final int MAX_OUTPOST_DISTANCE = 3_200;

    private record Front(Settlement first, Settlement second, double distance) { }

    public int advanceDay(WorldSimulation simulation) {
        int outpostsBuilt = prepareFrontiers(simulation);
        long day = (long) Math.floor(simulation.timeDays());
        if (day == 0 || day % BATTLE_INTERVAL_DAYS != 0) return outpostsBuilt;
        List<Civilization> civilizations = simulation.civilizations().stream()
                .sorted(Comparator.comparing(civ -> civ.id().toString())).toList();
        Map<UUID, List<Settlement>> citiesByCivilization = new HashMap<>();
        for (Settlement city : simulation.settlements()) {
            citiesByCivilization.computeIfAbsent(city.civilizationId(), ignored -> new ArrayList<>()).add(city);
        }
        citiesByCivilization.values().forEach(cities -> cities.sort(Comparator.comparing(city -> city.id().toString())));
        int battles = 0;
        for (int i = 0; i < civilizations.size(); i++) {
            Civilization first = civilizations.get(i);
            for (int j = i + 1; j < civilizations.size(); j++) {
                Civilization second = civilizations.get(j);
                if (first.relationWith(second.id()) != Civilization.Relation.WAR
                        && second.relationWith(first.id()) != Civilization.Relation.WAR) continue;
                Front front = closestFront(citiesByCivilization.getOrDefault(first.id(), List.of()),
                        citiesByCivilization.getOrDefault(second.id(), List.of()));
                if (front == null) continue;
                resolveBattle(simulation, first, second, front);
                battles++;
            }
        }
        return battles + outpostsBuilt;
    }

    /** Mobilizes forward camps before war; a persisted 180-day standoff escalates into war. */
    private int prepareFrontiers(WorldSimulation simulation) {
        List<Civilization> civilizations = simulation.civilizations().stream()
                .sorted(Comparator.comparing(civ -> civ.id().toString())).toList();
        Map<UUID, List<Settlement>> citiesByCivilization = new HashMap<>();
        for (Settlement city : simulation.settlements()) {
            citiesByCivilization.computeIfAbsent(city.civilizationId(), ignored -> new ArrayList<>()).add(city);
        }
        int built = 0;
        for (int i = 0; i < civilizations.size(); i++) for (int j = i + 1; j < civilizations.size(); j++) {
            Civilization first = civilizations.get(i);
            Civilization second = civilizations.get(j);
            Civilization.Relation firstRelation = first.relationWith(second.id());
            Civilization.Relation secondRelation = second.relationWith(first.id());
            boolean atWar = firstRelation == Civilization.Relation.WAR || secondRelation == Civilization.Relation.WAR;
            boolean hostileBorder = firstRelation == Civilization.Relation.TENSE
                    || firstRelation == Civilization.Relation.HOSTILE
                    || secondRelation == Civilization.Relation.TENSE || secondRelation == Civilization.Relation.HOSTILE;
            boolean protectedByTreaty = simulation.hasAgreement(first.id(), second.id(),
                    dev.autociv.simulation.model.DiplomaticAgreement.Type.NON_AGGRESSION)
                    || simulation.hasAgreement(first.id(), second.id(),
                    dev.autociv.simulation.model.DiplomaticAgreement.Type.PEACE)
                    || simulation.hasAgreement(first.id(), second.id(),
                    dev.autociv.simulation.model.DiplomaticAgreement.Type.ALLIANCE);
            if (protectedByTreaty || !atWar && !hostileBorder) {
                simulation.removeBorderOutposts(first.id(), second.id());
                continue;
            }
            if (atWar) continue;

            Front front = closestFront(citiesByCivilization.getOrDefault(first.id(), List.of()),
                    citiesByCivilization.getOrDefault(second.id(), List.of()), MAX_OUTPOST_DISTANCE);
            if (front == null) continue;
            built += buildOutpostIfReady(simulation, first, second, front);
            built += buildOutpostIfReady(simulation, second, first,
                    new Front(front.second(), front.first(), front.distance()));

            BorderOutpost firstCamp = simulation.borderOutpost(first.id(), second.id()).orElse(null);
            BorderOutpost secondCamp = simulation.borderOutpost(second.id(), first.id()).orElse(null);
            if (firstCamp == null || secondCamp == null
                    || simulation.timeDays() - Math.max(firstCamp.foundedDay(), secondCamp.foundedDay())
                    < STANDOFF_WAR_DAYS) continue;
            first.setRelationWith(second.id(), Civilization.Relation.WAR);
            second.setRelationWith(first.id(), Civilization.Relation.WAR);
            first.recordEvent("year " + simulation.year() + ": the frontier standoff with " + second.name()
                    + " lasted 180 days; war began at " + firstCamp.x() + "," + firstCamp.z() + ".");
            second.recordEvent("year " + simulation.year() + ": the frontier standoff with " + first.name()
                    + " lasted 180 days; war began at " + secondCamp.x() + "," + secondCamp.z() + ".");
        }
        return built;
    }

    private int buildOutpostIfReady(WorldSimulation simulation, Civilization owner, Civilization rival,
                                    Front front) {
        if (simulation.borderOutpost(owner.id(), rival.id()).isPresent()) return 0;
        Settlement supplyCity = front.first();
        double strength = armyPower(simulation, owner, supplyCity, rival.id());
        if (strength <= 0.0 || !reserveOutpostMaterials(supplyCity)) return 0;
        double progress = 0.42;
        int x = (int) Math.round(front.first().x() + (front.second().x() - front.first().x()) * progress);
        int z = (int) Math.round(front.first().z() + (front.second().z() - front.first().z()) * progress);
        int y = (int) Math.round(front.first().y() + (front.second().y() - front.first().y()) * progress);
        BorderOutpost outpost = new BorderOutpost(UUID.randomUUID(), owner.id(), rival.id(), x, y, z,
                simulation.timeDays(), strength * 0.60);
        simulation.addBorderOutpost(outpost);
        owner.recordEvent("year " + simulation.year() + ": an army left " + supplyCity.name()
                + " and built a frontier outpost at " + x + "," + z + " facing " + rival.name() + ".");
        return 1;
    }

    private boolean reserveOutpostMaterials(Settlement city) {
        double wood = city.stockpile().get(ResourceType.WOOD);
        double stone = city.stockpile().get(ResourceType.STONE);
        double food = city.stockpile().get(ResourceType.FOOD);
        double weapons = city.stockpile().get(ResourceType.WEAPONS);
        if (wood < 120 || stone < 64 || food < 40 || weapons < 6) return false;
        city.stockpile().remove(ResourceType.WOOD, 120);
        city.stockpile().remove(ResourceType.STONE, 64);
        city.stockpile().remove(ResourceType.FOOD, 40);
        city.stockpile().remove(ResourceType.WEAPONS, 6);
        city.stockpile().recordDelta(ResourceType.WOOD, -120);
        city.stockpile().recordDelta(ResourceType.STONE, -64);
        city.stockpile().recordDelta(ResourceType.FOOD, -40);
        city.stockpile().recordDelta(ResourceType.WEAPONS, -6);
        return true;
    }

    private Front closestFront(List<Settlement> firstCities, List<Settlement> secondCities) {
        return closestFront(firstCities, secondCities, MAX_FRONT_DISTANCE);
    }

    private Front closestFront(List<Settlement> firstCities, List<Settlement> secondCities, int maxDistance) {
        Map<Long, List<Settlement>> spatialIndex = new HashMap<>();
        for (Settlement city : secondCities) {
            spatialIndex.computeIfAbsent(cellKey(city.x(), city.z(), maxDistance), ignored -> new ArrayList<>())
                    .add(city);
        }
        Front closest = null;
        for (Settlement a : firstCities) {
            int cellX = Math.floorDiv(a.x(), maxDistance);
            int cellZ = Math.floorDiv(a.z(), maxDistance);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                for (Settlement b : spatialIndex.getOrDefault(
                        cellKeyFromGrid(cellX + dx, cellZ + dz), List.of())) {
                    double distance = Math.sqrt(a.distanceSqTo(b));
                    if (distance > maxDistance) continue;
                    Front candidate = new Front(a, b, distance);
                    if (closest == null || distance < closest.distance()
                            || distance == closest.distance() && a.id().toString()
                                    .compareTo(closest.first().id().toString()) < 0) closest = candidate;
                }
            }
        }
        return closest;
    }

    private long cellKey(int x, int z, int cellSize) {
        return cellKeyFromGrid(Math.floorDiv(x, cellSize), Math.floorDiv(z, cellSize));
    }

    private long cellKeyFromGrid(int gridX, int gridZ) {
        return ((long) gridX << 32) ^ (gridZ & 0xFFFFFFFFL);
    }

    private void resolveBattle(WorldSimulation simulation, Civilization first, Civilization second, Front front) {
        double firstPower = armyPower(simulation, first, front.first(), second.id());
        double secondPower = armyPower(simulation, second, front.second(), first.id());
        if (firstPower <= 0 && secondPower <= 0) return;
        boolean firstAttacks = firstPower >= secondPower;
        Civilization attacker = firstAttacks ? first : second;
        Civilization defender = firstAttacks ? second : first;
        Settlement attackingCity = firstAttacks ? front.first() : front.second();
        Settlement target = firstAttacks ? front.second() : front.first();
        double attackPower = Math.max(firstPower, secondPower);
        double defendingPower = Math.min(firstPower, secondPower);

        double supply = spendCampaignSupplies(attackingCity);
        double fortification = target.defense() + target.buildingCount(BuildingType.FORTIFICATION) * 8.0
                + target.buildingCount(BuildingType.BARRACKS) * 3.0;
        double pressure = Math.clamp((attackPower - defendingPower) / Math.max(8.0, attackPower), 0.0, 1.0);
        double damage = (0.015 + pressure * 0.025) * supply;
        target.setSecurity(target.security() - damage);
        target.setHappiness(target.happiness() - damage * 0.35);
        target.setDefense(Math.max(0, target.defense() - (0.8 + pressure * 1.4) * supply));
        attackingCity.setSecurity(attackingCity.security() - damage * 0.3);
        sufferCasualties(simulation, attackingCity, 0.35 + (1.0 - supply) * 0.65);
        sufferCasualties(simulation, target, 0.25 + pressure * 0.5);

        String battle = "year " + simulation.year() + ": border campaign near " + target.name()
                + "; " + attacker.name() + " attacked " + defender.name() + " (supply "
                + Math.round(supply * 100) + "%, " + Math.round(front.distance()) + " blocks).";
        attacker.recordEvent(battle);
        defender.recordEvent(battle);

        double garrison = garrisonPower(simulation, target);
        boolean capture = !target.isCapital() && target.defense() <= 0.01
                && supply >= 0.60 && attackPower > Math.max(5.0, garrison + fortification) * 1.35;
        if (capture && simulation.transferSettlement(target, attacker)) {
            attacker.recordEvent("year " + simulation.year() + ": captured " + target.name()
                    + " after its defenses fell.");
            defender.recordEvent("year " + simulation.year() + ": lost " + target.name()
                    + " after its defenses fell.");
        } else if (target.defense() <= 0.01 && !target.isCapital()) {
            defender.recordEvent("year " + simulation.year() + ": " + target.name()
                    + " is under siege; reinforce the garrison or restore supplies.");
        }
    }

    private double armyPower(WorldSimulation simulation, Civilization civilization, Settlement city, UUID rivalId) {
        double troops = garrisonPower(simulation, city);
        int cities = Math.max(1, simulation.settlementsOf(civilization.id()).size());
        double veteranSupport = civilization.militaryStrength() / cities * 0.20;
        double deployedGarrison = simulation.borderOutpost(civilization.id(), rivalId)
                .map(BorderOutpost::garrisonStrength).orElse(0.0);
        double doctrine = civilization.hasTrait(CivilizationTrait.MILITARISTIC) ? 1.20 : 1.0;
        if (civilization.hasTrait(CivilizationTrait.DEFENSIVE) && city.role()
                == dev.autociv.simulation.model.SettlementRole.FRONTIER) doctrine *= 1.15;
        return (troops + veteranSupport + deployedGarrison) * campaignReadiness(city) * doctrine;
    }

    private double garrisonPower(WorldSimulation simulation, Settlement city) {
        double power = city.buildingCount(BuildingType.BARRACKS) * 2.0;
        for (var id : city.citizenIds()) {
            Citizen citizen = simulation.citizen(id).orElse(null);
            if (citizen == null || !citizen.isAdult()) continue;
            if (citizen.profession() == Citizen.Profession.SOLDIER) power += 2.0;
            else if (citizen.profession() == Citizen.Profession.GUARD) power += 1.25;
        }
        return power;
    }

    private double campaignReadiness(Settlement city) {
        double food = Math.clamp(city.stockpile().get(ResourceType.FOOD) / 30.0, 0.0, 1.0);
        double weapons = Math.clamp(city.stockpile().get(ResourceType.WEAPONS) / 12.0, 0.0, 1.0);
        double tools = Math.clamp(city.stockpile().get(ResourceType.TOOLS) / 12.0, 0.0, 1.0);
        return 0.35 + 0.35 * food + 0.20 * weapons + 0.10 * tools;
    }

    private double spendCampaignSupplies(Settlement city) {
        double food = city.stockpile().remove(ResourceType.FOOD, 30.0);
        double weapons = city.stockpile().remove(ResourceType.WEAPONS, 3.0);
        double tools = city.stockpile().remove(ResourceType.TOOLS, 2.0);
        city.stockpile().recordDelta(ResourceType.FOOD, -food);
        city.stockpile().recordDelta(ResourceType.WEAPONS, -weapons);
        city.stockpile().recordDelta(ResourceType.TOOLS, -tools);
        return 0.35 + 0.35 * food / 30.0 + 0.20 * weapons / 3.0 + 0.10 * tools / 2.0;
    }

    private void sufferCasualties(WorldSimulation simulation, Settlement city, double severity) {
        List<Citizen> candidates = new ArrayList<>();
        for (var id : city.citizenIds()) {
            Citizen citizen = simulation.citizen(id).orElse(null);
            if (citizen != null && citizen.isAdult()
                    && (citizen.profession() == Citizen.Profession.SOLDIER
                    || citizen.profession() == Citizen.Profession.GUARD)) candidates.add(citizen);
        }
        candidates.sort(Comparator.comparing(citizen -> citizen.id().toString()));
        if (!candidates.isEmpty() && severity >= 0.45) simulation.removeCitizen(candidates.get(0));
        else if (candidates.isEmpty() && city.population() > 8 && severity >= 0.85) {
            city.citizenIds().stream().map(id -> simulation.citizen(id).orElse(null))
                    .filter(java.util.Objects::nonNull).filter(Citizen::isAdult)
                    .min(Comparator.comparing(citizen -> citizen.id().toString()))
                    .ifPresent(simulation::removeCitizen);
        }
    }
}
