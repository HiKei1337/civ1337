package dev.autociv.simulation.ai;

import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.CivilizationTrait;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Region;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.model.SettlementRole;
import dev.autociv.simulation.world.WorldSimulation;

import java.util.List;
import java.util.UUID;

/** Founds daughter cities when a developed city can spare settlers and supplies. */
public final class SettlementExpansionEngine {

    private record FoundingSite(int x, int y, int z, Region region, SettlementRole role, double score) { }

    private static final int FOUNDING_POPULATION = 28;
    private static final int MIGRANTS = 4;
    private static final int MIN_CITY_DISTANCE = 192;
    private static final double MIN_TREASURY = 250.0;
    private static final List<Citizen.Profession> FOUNDING_JOBS = List.of(
            Citizen.Profession.FARMER, Citizen.Profession.MINER,
            Citizen.Profession.LUMBERJACK, Citizen.Profession.BUILDER);

    public int update(WorldSimulation simulation) {
        int founded = 0;
        for (Civilization civilization : simulation.civilizations()) {
            List<Settlement> cities = simulation.settlementsOf(civilization.id());
            int foundingPopulation = civilization.hasTrait(CivilizationTrait.EXPANSIONIST)
                    ? FOUNDING_POPULATION - 6
                    : civilization.hasTrait(CivilizationTrait.ISOLATIONIST)
                    ? FOUNDING_POPULATION + 10 : FOUNDING_POPULATION;
            double foundingTreasury = civilization.hasTrait(CivilizationTrait.EXPANSIONIST)
                    ? MIN_TREASURY * 0.8
                    : civilization.hasTrait(CivilizationTrait.ISOLATIONIST)
                    ? MIN_TREASURY * 1.5 : MIN_TREASURY;
            if (cities.size() >= 10_000 || civilization.treasury() < foundingTreasury) continue;
            Settlement parent = cities.stream()
                    .filter(city -> city.population() >= foundingPopulation)
                    .filter(city -> city.completedHomes() >= foundingPopulation)
                    .filter(city -> city.stockpile().get(ResourceType.FOOD) >= 300
                            && city.stockpile().get(ResourceType.WOOD) >= 300
                            && city.stockpile().get(ResourceType.STONE) >= 200)
                    .max(java.util.Comparator.comparingInt(Settlement::population)).orElse(null);
            if (parent == null) continue;

            List<Citizen> migrants = selectMigrants(simulation, parent);
            if (migrants.size() < MIGRANTS) continue;
            int cityIndex = cities.size() + 1;
            FoundingSite site = findSite(simulation, civilization, cities, parent, cityIndex);
            if (site == null) continue;

            Settlement child = simulation.createSettlement(civilization.id(), settlementName(site.role(), cityIndex),
                    site.x(), site.y(), site.z());
            if (!simulation.linkSettlement(child, parent, site.role(), simulation.year())) {
                simulation.removeSettlement(child.id());
                continue;
            }
            simulation.addRegion(site.region());
            child.setHousingCapacity(MIGRANTS);
            child.setHomeBuildingState(MIGRANTS, 0, 0);
            // A frontier settlement begins exposed, so the planner prioritizes
            // defensive construction before it grows into a mature city.
            child.setSecurity(0.30);
            transferStarterResources(parent, child);
            for (int index = 0; index < migrants.size(); index++) {
                Citizen citizen = migrants.get(index);
                simulation.transferCitizen(citizen.id(), child);
                citizen.setProfession(FOUNDING_JOBS.get(index));
            }
            civilization.addToTreasury(-foundingTreasury);
            civilization.recordEvent("year " + simulation.year() + ": founded " + child.name() + " ("
                    + site.role().name().toLowerCase(java.util.Locale.ROOT) + ") from " + parent.name()
                    + " in rich " + site.region().resource().id() + " country ("
                    + String.format(java.util.Locale.ROOT, "%.2f", site.region().richness())
                    + "); four settlers established a daughter city.");
            founded++;
        }
        return founded;
    }

    private List<Citizen> selectMigrants(WorldSimulation simulation, Settlement parent) {
        return parent.citizenIds().stream().map(id -> simulation.citizen(id).orElse(null))
                .filter(java.util.Objects::nonNull).filter(Citizen::isAdult)
                .sorted(java.util.Comparator.comparingInt((Citizen citizen) -> {
                    int roleIndex = FOUNDING_JOBS.indexOf(citizen.profession());
                    return roleIndex < 0 ? 4 : roleIndex;
                }).thenComparing(citizen -> citizen.id().toString()))
                .limit(MIGRANTS).toList();
    }

    private FoundingSite findSite(WorldSimulation simulation, Civilization civilization,
                                  List<Settlement> cities, Settlement parent, int cityIndex) {
        double angle = cityIndex * 2.399963229728653;
        List<FoundingSite> candidates = new java.util.ArrayList<>();
        for (int attempt = 0; attempt < 24; attempt++) {
            double distance = MIN_CITY_DISTANCE + (attempt / 8) * 128.0;
            double candidateAngle = angle + attempt * Math.PI / 4.0;
            int x = parent.x() + (int) Math.round(Math.cos(candidateAngle) * distance);
            int z = parent.z() + (int) Math.round(Math.sin(candidateAngle) * distance);
            if (Math.abs(x) > 29_000_000 || Math.abs(z) > 29_000_000) continue;
            boolean occupied = simulation.settlementsNear(x, z, MIN_CITY_DISTANCE).stream().anyMatch(city ->
                    squareDistance(city.x(), city.z(), x, z)
                            < (long) MIN_CITY_DISTANCE * MIN_CITY_DISTANCE);
            if (occupied) continue;
            int gridX = Math.floorDiv(x, Region.SIZE);
            int gridZ = Math.floorDiv(z, Region.SIZE);
            Region region = simulation.region(gridX, gridZ).orElseGet(() -> Region.generate(gridX, gridZ));
            double distancePenalty = Math.hypot(x - (double) parent.x(), z - (double) parent.z()) / 10_000.0;
            double neighboringInfluence = simulation.settlementsNear(x, z, 800).stream()
                    .filter(city -> !city.civilizationId().equals(civilization.id()))
                    .mapToDouble(city -> 300.0 / Math.max(300.0,
                            Math.hypot(city.x() - (double) x, city.z() - (double) z)))
                    .sum();
            SettlementRole role = neighboringInfluence > 0.65 ? SettlementRole.FRONTIER
                    : SettlementRole.forResource(region.resource());
            long sameRole = roleCount(cities, role);
            if (role != SettlementRole.FRONTIER && sameRole >= Math.max(2, cities.size() / 3)) {
                role = SettlementRole.TRADE;
                sameRole = roleCount(cities, role);
            }
            double diversity = sameRole == 0 ? 1.0 : -Math.min(0.8, sameRole * 0.25);
            double score = region.richness() * 0.5 + diversity - distancePenalty - neighboringInfluence;
            candidates.add(new FoundingSite(x, parent.y(), z, region, role, score));
        }
        return candidates.stream().max(java.util.Comparator.comparingDouble(FoundingSite::score)).orElse(null);
    }

    private String settlementName(SettlementRole role, int number) {
        String[] roots = switch (role) {
            case AGRICULTURE -> new String[]{"Зелёный Луг", "Хлебный Брод", "Житное Поле", "Медовая Долина"};
            case FORESTRY -> new String[]{"Сосновый Край", "Тихая Роща", "Лесной Ручей", "Еловый Бор"};
            case MINING -> new String[]{"Каменный Брод", "Рудный Холм", "Серый Уступ", "Железный Ключ"};
            case TRADE -> new String[]{"Перекрёсток", "Большой Брод", "Торговый Путь", "Речной Рынок"};
            case FRONTIER -> new String[]{"Дальний Дозор", "Край Земли", "Пограничье", "Новый Рубеж"};
            case CAPITAL, VILLAGE -> new String[]{"Новая Надежда", "Солнечный Край", "Тихая Долина", "Старый Мост"};
        };
        return roots[Math.floorMod(number - 2, roots.length)] + " " + number;
    }

    private long roleCount(List<Settlement> settlements, SettlementRole role) {
        return settlements.stream().filter(city -> city.role() == role).count();
    }

    private long squareDistance(int ax, int az, int bx, int bz) {
        long dx = (long) ax - bx;
        long dz = (long) az - bz;
        return dx * dx + dz * dz;
    }

    private void transferStarterResources(Settlement source, Settlement destination) {
        transfer(source, destination, ResourceType.FOOD, 400);
        transfer(source, destination, ResourceType.WOOD, 300);
        transfer(source, destination, ResourceType.STONE, 200);
        transfer(source, destination, ResourceType.COAL, 20);
    }

    private void transfer(Settlement source, Settlement destination, ResourceType resource, double amount) {
        double available = source.stockpile().get(resource);
        double moved = Math.min(amount, available);
        if (moved <= 0) return;
        source.stockpile().remove(resource, moved);
        source.stockpile().recordDelta(resource, -moved);
        double accepted = destination.stockpile().add(resource, moved);
        destination.stockpile().recordDelta(resource, accepted);
    }
}
