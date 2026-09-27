package dev.autociv.simulation.ai;

import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.CivilizationTrait;
import dev.autociv.simulation.model.DiplomaticAgreement;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.world.WorldSimulation;
import dev.autociv.simulation.economy.TradeRoute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Slowly evolves bilateral diplomatic stances from commerce, proximity, and shared eras. */
public final class DiplomacyEngine {

    private static final double BORDER_DISTANCE = 1_600.0;
    private static final double TRADE_GAIN_PER_DAY = 0.008;

    public void advanceDay(WorldSimulation simulation) {
        List<Civilization> civilizations = simulation.civilizations().stream()
                .sorted(Comparator.comparing(civilization -> civilization.id().toString())).toList();
        Map<UUID, List<Settlement>> citiesByCivilization = new HashMap<>();
        simulation.settlements().forEach(city -> citiesByCivilization
                .computeIfAbsent(city.civilizationId(), ignored -> new ArrayList<>()).add(city));
        Set<CivilizationPair> tradingPairs = new HashSet<>();
        for (TradeRoute route : simulation.tradeRoutes()) {
            if (!route.active()) continue;
            Settlement source = simulation.settlement(route.sourceSettlementId()).orElse(null);
            Settlement destination = simulation.settlement(route.destinationSettlementId()).orElse(null);
            if (source != null && destination != null && !source.civilizationId().equals(destination.civilizationId())) {
                tradingPairs.add(CivilizationPair.of(source.civilizationId(), destination.civilizationId()));
            }
        }

        for (int firstIndex = 0; firstIndex < civilizations.size(); firstIndex++) {
            Civilization first = civilizations.get(firstIndex);
            for (int secondIndex = firstIndex + 1; secondIndex < civilizations.size(); secondIndex++) {
                Civilization second = civilizations.get(secondIndex);
                Civilization.Relation oldFirst = first.relationWith(second.id());
                Civilization.Relation oldSecond = second.relationWith(first.id());
                if (oldFirst == Civilization.Relation.WAR || oldSecond == Civilization.Relation.WAR) {
                    first.setRelationWith(second.id(), Civilization.Relation.WAR);
                    second.setRelationWith(first.id(), Civilization.Relation.WAR);
                    continue;
                }
                boolean alliance = simulation.hasAgreement(first.id(), second.id(),
                        DiplomaticAgreement.Type.ALLIANCE);
                if (alliance) {
                    first.setRelationWith(second.id(), Civilization.Relation.ALLIED);
                    second.setRelationWith(first.id(), Civilization.Relation.ALLIED);
                    continue;
                }

                CivilizationPair pair = CivilizationPair.of(first.id(), second.id());
                boolean activeTrade = tradingPairs.contains(pair);
                boolean tradePact = simulation.hasAgreement(first.id(), second.id(),
                        DiplomaticAgreement.Type.TRADE_PACT);
                boolean trade = activeTrade || tradePact;
                boolean protectedByPact = simulation.hasAgreement(first.id(), second.id(),
                        DiplomaticAgreement.Type.NON_AGGRESSION)
                        || simulation.hasAgreement(first.id(), second.id(), DiplomaticAgreement.Type.PEACE);
                double distance = closestSettlementDistance(citiesByCivilization.get(first.id()),
                        citiesByCivilization.get(second.id()));
                double borderMultiplier = first.hasTrait(CivilizationTrait.MILITARISTIC)
                        || second.hasTrait(CivilizationTrait.MILITARISTIC) ? 1.5 : 1.0;
                double pressure = Double.isFinite(distance) && distance < BORDER_DISTANCE
                        ? (BORDER_DISTANCE - distance) / BORDER_DISTANCE * 0.0012 * borderMultiplier : 0.0;
                boolean sharedTrait = first.traits().stream().anyMatch(second.traits()::contains);
                double eraAffinity = (first.historicalEra().equalsIgnoreCase(second.historicalEra())
                        ? 0.0001 : 0.0) + (sharedTrait ? 0.00015 : 0.0);
                double opinion = (first.diplomaticOpinionWith(second.id())
                        + second.diplomaticOpinionWith(first.id())) * 0.5;
                double isolation = first.hasTrait(CivilizationTrait.ISOLATIONIST)
                        || second.hasTrait(CivilizationTrait.ISOLATIONIST) ? 0.60 : 1.0;
                double tradeAffinity = first.hasTrait(CivilizationTrait.TRADING)
                        || second.hasTrait(CivilizationTrait.TRADING) ? 1.5 : 1.0;
                double drift = -opinion * 0.001 + eraAffinity * isolation - pressure
                        + (trade ? TRADE_GAIN_PER_DAY * tradeAffinity * isolation : 0.0);
                opinion = Math.clamp(opinion + drift, -1.0, 1.0);
                first.setDiplomaticOpinionWith(second.id(), opinion);
                second.setDiplomaticOpinionWith(first.id(), opinion);

                Civilization.Relation next = classify(opinion, trade);
                if (protectedByPact && next == Civilization.Relation.HOSTILE) next = Civilization.Relation.TENSE;
                first.setRelationWith(second.id(), next);
                second.setRelationWith(first.id(), next);
                if (oldFirst != next || oldSecond != next) {
                    String event = "year " + simulation.year() + ": relations with " + second.name()
                            + " changed to " + next.name().toLowerCase(java.util.Locale.ROOT) + ".";
                    first.recordEvent(event);
                    second.recordEvent("year " + simulation.year() + ": relations with " + first.name()
                            + " changed to " + next.name().toLowerCase(java.util.Locale.ROOT) + ".");
                }
                maintainAutonomousTreaties(simulation, first, second, opinion, activeTrade);
            }
        }
        expireAgreements(simulation);
    }

    private void maintainAutonomousTreaties(WorldSimulation simulation, Civilization first, Civilization second,
                                             double opinion, boolean activeTrade) {
        if (activeTrade && opinion >= 0.15) {
            renewWhenNeeded(simulation, first, second, DiplomaticAgreement.Type.TRADE_PACT,
                    360, 30, "automatically signed a trade pact");
        }
        boolean stableRelationship = opinion >= 0.70
                && (activeTrade || simulation.hasAgreement(first.id(), second.id(),
                        DiplomaticAgreement.Type.TRADE_PACT));
        if (stableRelationship && !simulation.hasAgreement(first.id(), second.id(),
                DiplomaticAgreement.Type.ALLIANCE)) {
            renewWhenNeeded(simulation, first, second, DiplomaticAgreement.Type.NON_AGGRESSION,
                    360, 30, "automatically signed a non-aggression pact");
        }
    }

    private void renewWhenNeeded(WorldSimulation simulation, Civilization first, Civilization second,
                                 DiplomaticAgreement.Type type, int duration, int renewalThreshold, String event) {
        DiplomaticAgreement current = simulation.agreementsBetween(first.id(), second.id()).stream()
                .filter(agreement -> agreement.type() == type).findFirst().orElse(null);
        if (current != null && current.remainingDays() > renewalThreshold) return;
        simulation.addDiplomaticAgreement(DiplomaticAgreement.create(first.id(), second.id(), type, duration));
        first.recordEvent("year " + simulation.year() + ": " + event + " with " + second.name() + ".");
        second.recordEvent("year " + simulation.year() + ": " + event + " with " + first.name() + ".");
    }

    private void expireAgreements(WorldSimulation simulation) {
        for (DiplomaticAgreement agreement : new ArrayList<>(simulation.diplomaticAgreements())) {
            if (!agreement.advanceDay()) continue;
            simulation.removeAgreement(agreement.id());
            simulation.civilization(agreement.firstCivilizationId()).ifPresent(civilization ->
                    civilization.recordEvent("year " + simulation.year() + ": " + agreement.type()
                            .name().toLowerCase(java.util.Locale.ROOT) + " with "
                            + simulation.civilization(agreement.secondCivilizationId())
                                    .map(Civilization::name).orElse("an unknown civilization") + " expired."));
            simulation.civilization(agreement.secondCivilizationId()).ifPresent(civilization ->
                    civilization.recordEvent("year " + simulation.year() + ": " + agreement.type()
                            .name().toLowerCase(java.util.Locale.ROOT) + " with "
                            + simulation.civilization(agreement.firstCivilizationId())
                                    .map(Civilization::name).orElse("an unknown civilization") + " expired."));
        }
    }

    private Civilization.Relation classify(double opinion, boolean activeTrade) {
        if (opinion >= 0.55) return Civilization.Relation.FRIENDLY;
        if (activeTrade && opinion > -0.55) return Civilization.Relation.TRADE_PARTNER;
        if (opinion >= -0.15) return Civilization.Relation.NEUTRAL;
        if (opinion >= -0.60) return Civilization.Relation.TENSE;
        return Civilization.Relation.HOSTILE;
    }

    private double closestSettlementDistance(List<Settlement> first, List<Settlement> second) {
        if (first == null || second == null || first.isEmpty() || second.isEmpty()) return Double.POSITIVE_INFINITY;
        double minDistanceSquared = Double.POSITIVE_INFINITY;
        for (Settlement a : first) {
            for (Settlement b : second) minDistanceSquared = Math.min(minDistanceSquared, a.distanceSqTo(b));
        }
        return Math.sqrt(minDistanceSquared);
    }

    private record CivilizationPair(UUID first, UUID second) {
        private static CivilizationPair of(UUID first, UUID second) {
            return first.toString().compareTo(second.toString()) <= 0
                    ? new CivilizationPair(first, second) : new CivilizationPair(second, first);
        }
    }
}
