package dev.autociv.simulation.world;

import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.model.SettlementRole;
import dev.autociv.simulation.model.DiplomaticAgreement;
import dev.autociv.simulation.model.Region;
import dev.autociv.simulation.model.BorderOutpost;
import dev.autociv.simulation.economy.Merchant;
import dev.autociv.simulation.economy.TradeRoute;
import dev.autociv.simulation.economy.TradeShipment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Root of the abstract world simulation (Level A of the two-level design).
 * <p>
 * Deliberately free of Minecraft imports: it can run in unit tests, on a
 * dedicated scheduler thread and while chunks are unloaded. Persistence is
 * handled by {@code SimulationSerializer} in the persistence package.
 * <p>
 * All cross-references are stored as UUIDs with lookup maps here - entities
 * never hold direct references to each other (avoids object-graph explosion
 * and makes serialization trivial).
 */
public final class WorldSimulation {

    /** Simulation calendar time in days (1.0 = one full day passed). Stage 2 advances it. */
    private double timeDays;
    /** Monotonic counter of executed simulation ticks (for debug). */
    private long tickCount;
    private int calendarStartYear;
    private SimulationSpeed simulationSpeed = SimulationSpeed.REALISTIC;

    private final Map<UUID, Civilization> civilizations = new HashMap<>();
    private final Map<UUID, Settlement> settlements = new HashMap<>();
    private final Map<Long, Set<UUID>> settlementsByChunk = new HashMap<>();
    private final Map<UUID, Citizen> citizens = new HashMap<>();
    private final Map<UUID, TradeRoute> tradeRoutes = new HashMap<>();
    private final Map<UUID, Merchant> merchants = new HashMap<>();
    private final Map<UUID, TradeShipment> tradeShipments = new HashMap<>();
    private final Map<UUID, DiplomaticAgreement> diplomaticAgreements = new HashMap<>();
    private final Map<String, Region> regions = new HashMap<>();
    private final Map<UUID, BorderOutpost> borderOutposts = new HashMap<>();

    // ------------------------------------------------------------------ time

    public double timeDays() {
        return timeDays;
    }

    public void setTimeDays(double timeDays) {
        this.timeDays = timeDays;
    }

    public int year() {
        return calendarStartYear + (int) (timeDays / 360.0); // 360-day calendar year
    }

    public int calendarStartYear() {
        return calendarStartYear;
    }

    public void setCalendarStartYear(int calendarStartYear) {
        this.calendarStartYear = calendarStartYear;
    }

    public long tickCount() {
        return tickCount;
    }

    public void setTickCount(long tickCount) {
        this.tickCount = Math.max(0, tickCount);
    }

    public void incrementTick() {
        tickCount++;
    }

    public SimulationSpeed simulationSpeed() {
        return simulationSpeed;
    }

    public void setSimulationSpeed(SimulationSpeed simulationSpeed) {
        this.simulationSpeed = Objects.requireNonNull(simulationSpeed, "simulationSpeed");
    }

    // -------------------------------------------------------- civilizations

    public Civilization createCivilization(String name, int color) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Civilization name must not be blank");
        }
        Civilization civ = new Civilization(UUID.randomUUID(), name.trim(), color);
        civilizations.put(civ.id(), civ);
        civ.recordEvent("year " + year() + ": " + name + " founded.");
        return civ;
    }

    public Optional<Civilization> civilization(UUID id) {
        return Optional.ofNullable(civilizations.get(id));
    }

    /** Resolves a civilization by exact name (case-insensitive) or short uuid prefix. */
    public Optional<Civilization> findCivilization(String nameOrPrefix) {
        String needle = nameOrPrefix.toLowerCase(java.util.Locale.ROOT);
        for (Civilization c : civilizations.values()) {
            if (c.name().equalsIgnoreCase(nameOrPrefix)) {
                return Optional.of(c);
            }
        }
        for (Civilization c : civilizations.values()) {
            if (c.id().toString().startsWith(needle)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    public Collection<Civilization> civilizations() {
        return Collections.unmodifiableCollection(civilizations.values());
    }

    public boolean removeCivilization(UUID id) {
        Civilization civ = civilizations.remove(id);
        if (civ == null) {
            return false;
        }
        for (UUID sid : new ArrayList<>(civ.settlementIds())) {
            removeSettlement(sid);
        }
        // clear relations pointing at the removed civ
        for (Civilization other : civilizations.values()) {
            other.relationsMutable().keySet().removeIf(k -> k.equals(id));
            other.diplomaticOpinionsMutable().keySet().removeIf(k -> k.equals(id));
        }
        diplomaticAgreements.values().removeIf(agreement -> agreement.firstCivilizationId().equals(id)
                || agreement.secondCivilizationId().equals(id));
        borderOutposts.values().removeIf(outpost -> outpost.civilizationId().equals(id)
                || outpost.rivalCivilizationId().equals(id));
        return true;
    }

    // ------------------------------------------------------------ settlements

    public Settlement createSettlement(UUID civilizationId, String name, int x, int y, int z) {
        Civilization civ = Objects.requireNonNull(civilizations.get(civilizationId),
                "unknown civilization " + civilizationId);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Settlement name must not be blank");
        }
        Settlement s = new Settlement(UUID.randomUUID(), name.trim(), civ.id(), x, y, z);
        s.setNetworkProfile(null, SettlementRole.VILLAGE, year());
        settlements.put(s.id(), s);
        indexSettlement(s);
        civ.addSettlementInternal(s.id());
        if (civ.capitalSettlementId() == null) {
            civ.setCapitalSettlementId(s.id());
            s.setCapital(true);
        }
        civ.recordEvent("year " + year() + ": " + s.name() + " founded.");
        return s;
    }

    /** Links a daughter city into its same-civilization tree without allowing cycles. */
    public boolean linkSettlement(Settlement child, Settlement parent, SettlementRole role, int foundedYear) {
        if (child == null || parent == null || child == parent || child.isCapital()
                || settlements.get(child.id()) != child || settlements.get(parent.id()) != parent
                || !child.civilizationId().equals(parent.civilizationId())) return false;
        Set<UUID> visited = new HashSet<>();
        Settlement cursor = parent;
        while (cursor != null) {
            if (cursor.id().equals(child.id()) || !visited.add(cursor.id())) return false;
            cursor = cursor.parentSettlementId() == null ? null
                    : settlements.get(cursor.parentSettlementId());
        }
        child.setNetworkProfile(parent.id(), role, foundedYear);
        return true;
    }

    public Optional<Settlement> settlement(UUID id) {
        return Optional.ofNullable(settlements.get(id));
    }

    public Collection<Settlement> settlements() {
        return Collections.unmodifiableCollection(settlements.values());
    }

    public void moveSettlement(Settlement settlement, int x, int y, int z) {
        Objects.requireNonNull(settlement, "settlement");
        if (settlements.get(settlement.id()) != settlement) throw new IllegalArgumentException("Unknown settlement");
        Set<UUID> previous = settlementsByChunk.get(chunkKey(settlement.x() >> 4, settlement.z() >> 4));
        if (previous != null) {
            previous.remove(settlement.id());
            if (previous.isEmpty()) settlementsByChunk.remove(chunkKey(settlement.x() >> 4, settlement.z() >> 4));
        }
        settlement.setPosition(x, y, z);
        indexSettlement(settlement);
    }

    public List<Settlement> settlementsInChunk(int chunkX, int chunkZ) {
        return settlementsByChunk.getOrDefault(chunkKey(chunkX, chunkZ), Set.of()).stream()
                .map(settlements::get).filter(Objects::nonNull).toList();
    }

    public List<Settlement> settlementsNear(int x, int z, int radius) {
        int minChunkX = Math.floorDiv(x - radius, 16);
        int maxChunkX = Math.floorDiv(x + radius, 16);
        int minChunkZ = Math.floorDiv(z - radius, 16);
        int maxChunkZ = Math.floorDiv(z + radius, 16);
        int radiusSq = radius * radius;
        List<Settlement> nearby = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (UUID id : settlementsByChunk.getOrDefault(chunkKey(chunkX, chunkZ), Set.of())) {
                    Settlement city = settlements.get(id);
                    if (city != null) {
                        long dx = (long) city.x() - x;
                        long dz = (long) city.z() - z;
                        if (dx * dx + dz * dz <= radiusSq) nearby.add(city);
                    }
                }
            }
        }
        return nearby;
    }

    public List<Settlement> settlementsOf(UUID civilizationId) {
        List<Settlement> out = new ArrayList<>();
        for (Settlement s : settlements.values()) {
            if (s.civilizationId().equals(civilizationId)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Transfers a non-capital city after a successful siege while keeping both city indexes valid. */
    public boolean transferSettlement(Settlement settlement, Civilization newOwner) {
        if (settlement == null || newOwner == null || settlements.get(settlement.id()) != settlement
                || civilizations.get(newOwner.id()) != newOwner || settlement.isCapital()
                || settlement.civilizationId().equals(newOwner.id())) return false;
        Civilization oldOwner = civilizations.get(settlement.civilizationId());
        Settlement newCapital = settlements.get(newOwner.capitalSettlementId());
        if (oldOwner == null || newCapital == null || newCapital == settlement) return false;
        int originalFoundingYear = settlement.foundedYear();
        oldOwner.removeSettlementInternal(settlement.id());
        newOwner.addSettlementInternal(settlement.id());
        settlement.setCivilizationId(newOwner.id());
        settlement.setOwnerPlayerId(null);
        settlement.setNetworkProfile(newCapital.id(), settlement.role(), originalFoundingYear);
        for (Settlement child : settlements.values()) {
            if (settlement.id().equals(child.parentSettlementId())) {
                child.setNetworkProfile(oldOwner.capitalSettlementId(), child.role(), child.foundedYear());
            }
        }
        return true;
    }

    public boolean removeSettlement(UUID id) {
        Settlement s = settlements.remove(id);
        if (s == null) {
            return false;
        }
        Set<UUID> chunkSettlements = settlementsByChunk.get(chunkKey(s.x() >> 4, s.z() >> 4));
        if (chunkSettlements != null) {
            chunkSettlements.remove(id);
            if (chunkSettlements.isEmpty()) settlementsByChunk.remove(chunkKey(s.x() >> 4, s.z() >> 4));
        }
        Civilization civ = civilizations.get(s.civilizationId());
        if (civ != null) {
            civ.removeSettlementInternal(id);
            if (id.equals(civ.capitalSettlementId())) {
                UUID replacement = civ.settlementIds().isEmpty() ? null : civ.settlementIds().get(0);
                civ.setCapitalSettlementId(replacement);
                if (replacement != null) settlements.get(replacement).setCapital(true);
            }
        }
        for (Settlement child : settlements.values()) {
            if (id.equals(child.parentSettlementId())) {
                child.setNetworkProfile(null, child.role(), child.foundedYear());
            }
        }
        for (UUID cid : new ArrayList<>(s.citizenIds())) {
            Citizen c = citizens.get(cid);
            if (c != null) {
                removeCitizen(c);
            }
        }
        // A removed city must not leave regions attributed to it until the next
        // abstract day recalculates the nearest settlement.
        for (Region region : regions.values()) {
            if (id.equals(region.nearestSettlementId())) {
                region.claim(null, null);
            }
        }
        for (TradeRoute route : tradeRoutes.values()) {
            if (route.sourceSettlementId().equals(id) || route.destinationSettlementId().equals(id)) {
                route.setActive(false);
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- citizens

    public Citizen addCitizen(Settlement home, String name, double ageYears, Citizen.Profession profession) {
        Objects.requireNonNull(home, "home");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Citizen name must not be blank");
        }
        Citizen c = new Citizen(UUID.randomUUID(), name.trim(), home.id(), ageYears, profession);
        citizens.put(c.id(), c);
        home.addCitizenInternal(c.id());
        return c;
    }

    public Optional<Citizen> citizen(UUID id) {
        return Optional.ofNullable(citizens.get(id));
    }

    public Collection<Citizen> citizens() {
        return Collections.unmodifiableCollection(citizens.values());
    }

    public void removeCitizen(Citizen c) {
        citizens.remove(c.id());
        Settlement home = settlements.get(c.homeSettlementId());
        if (home != null) {
            home.removeCitizenInternal(c.id());
        }
    }

    public boolean transferCitizen(UUID citizenId, Settlement destination) {
        Citizen citizen = citizens.get(citizenId);
        if (citizen == null || destination == null || !settlements.containsKey(destination.id())) return false;
        Settlement source = settlements.get(citizen.homeSettlementId());
        if (source == null || source.id().equals(destination.id())) return false;
        source.removeCitizenInternal(citizen.id());
        destination.addCitizenInternal(citizen.id());
        citizen.setHomeSettlementId(destination.id());
        citizen.setWorkplaceSettlementId(destination.id());
        return true;
    }

    // ------------------------------------------------------------- aggregate

    public int totalPopulation() {
        return citizens.size();
    }

    public Optional<TradeRoute> tradeRoute(UUID id) {
        return Optional.ofNullable(tradeRoutes.get(id));
    }

    public Collection<TradeRoute> tradeRoutes() {
        return Collections.unmodifiableCollection(tradeRoutes.values());
    }

    public void addTradeRoute(TradeRoute route) {
        tradeRoutes.put(route.id(), route);
    }

    public Optional<Merchant> merchant(UUID id) {
        return Optional.ofNullable(merchants.get(id));
    }

    public Collection<Merchant> merchants() {
        return Collections.unmodifiableCollection(merchants.values());
    }

    public void addMerchant(Merchant merchant) {
        merchants.put(merchant.id(), merchant);
    }

    public Collection<TradeShipment> tradeShipments() {
        return Collections.unmodifiableCollection(tradeShipments.values());
    }

    public void addTradeShipment(TradeShipment shipment) {
        tradeShipments.put(shipment.id(), shipment);
    }

    public Optional<TradeShipment> removeTradeShipment(UUID id) {
        return Optional.ofNullable(tradeShipments.remove(id));
    }

    public Collection<DiplomaticAgreement> diplomaticAgreements() {
        return Collections.unmodifiableCollection(diplomaticAgreements.values());
    }

    public void addDiplomaticAgreement(DiplomaticAgreement agreement) {
        diplomaticAgreements.values().removeIf(existing -> existing.type() == agreement.type()
                && existing.containsPair(agreement.firstCivilizationId(), agreement.secondCivilizationId()));
        diplomaticAgreements.put(agreement.id(), agreement);
    }

    public Collection<Region> regions() {
        return Collections.unmodifiableCollection(regions.values());
    }

    public Collection<BorderOutpost> borderOutposts() {
        return Collections.unmodifiableCollection(borderOutposts.values());
    }

    public Optional<BorderOutpost> borderOutpost(UUID civilization, UUID rival) {
        return borderOutposts.values().stream().filter(outpost -> outpost.civilizationId().equals(civilization)
                && outpost.rivalCivilizationId().equals(rival)).findFirst();
    }

    public void addBorderOutpost(BorderOutpost outpost) {
        borderOutposts.put(outpost.id(), outpost);
    }

    public void removeBorderOutposts(UUID first, UUID second) {
        borderOutposts.values().removeIf(outpost -> (outpost.civilizationId().equals(first)
                && outpost.rivalCivilizationId().equals(second))
                || (outpost.civilizationId().equals(second) && outpost.rivalCivilizationId().equals(first)));
    }

    public Optional<Region> region(int gridX, int gridZ) {
        return Optional.ofNullable(regions.get(Region.key(gridX, gridZ)));
    }

    public void addRegion(Region region) {
        regions.put(Region.key(region.gridX(), region.gridZ()), region);
    }

    public List<Region> regionsOwnedBy(UUID civilizationId) {
        return regions.values().stream().filter(region -> civilizationId.equals(region.ownerCivilizationId())).toList();
    }

    public List<Region> regionsAssignedToSettlement(UUID settlementId) {
        return regions.values().stream().filter(region -> settlementId.equals(region.nearestSettlementId())).toList();
    }

    public boolean hasAgreement(UUID first, UUID second, DiplomaticAgreement.Type type) {
        return diplomaticAgreements.values().stream().anyMatch(agreement -> agreement.type() == type
                && agreement.containsPair(first, second));
    }

    public List<DiplomaticAgreement> agreementsBetween(UUID first, UUID second) {
        return diplomaticAgreements.values().stream()
                .filter(agreement -> agreement.containsPair(first, second)).toList();
    }

    public void removeAgreement(UUID id) {
        diplomaticAgreements.remove(id);
    }

    public void removeAgreementsBetween(UUID first, UUID second) {
        diplomaticAgreements.values().removeIf(agreement -> agreement.containsPair(first, second));
    }

    // ------------------------------------------------------------- loader API

    /**
     * Controlled backdoor used ONLY by the persistence layer to rebuild a
     * simulation from saved data, preserving original UUIDs. Kept as an
     * explicit inner class so it is obvious in code review that this is not
     * general game logic API.
     */
    public final class Loader {
        private Loader() {
        }

        public void addCivilization(Civilization civ) {
            civilizations.put(civ.id(), civ);
        }

        public void addSettlement(Settlement s) {
            settlements.put(s.id(), s);
            indexSettlement(s);
        }

        public void addCitizen(Citizen c) {
            citizens.put(c.id(), c);
        }

        public void addTradeRoute(TradeRoute route) {
            tradeRoutes.put(route.id(), route);
        }

        public void addMerchant(Merchant merchant) {
            merchants.put(merchant.id(), merchant);
        }

        public void addTradeShipment(TradeShipment shipment) {
            tradeShipments.put(shipment.id(), shipment);
        }

        public void addDiplomaticAgreement(DiplomaticAgreement agreement) {
            diplomaticAgreements.put(agreement.id(), agreement);
        }

        public void addRegion(Region region) {
            regions.put(Region.key(region.gridX(), region.gridZ()), region);
        }

        public void addBorderOutpost(BorderOutpost outpost) {
            borderOutposts.put(outpost.id(), outpost);
        }

        public void attachCitizenToSettlement(Settlement s, UUID citizenId) {
            s.addCitizenInternal(citizenId);
        }

        public void attachSettlementToCivilization(Civilization civ, UUID settlementId) {
            civ.addSettlementInternal(settlementId);
        }
    }

    private final Loader loader = new Loader();

    private void indexSettlement(Settlement settlement) {
        settlementsByChunk.computeIfAbsent(chunkKey(settlement.x() >> 4, settlement.z() >> 4),
                ignored -> new HashSet<>()).add(settlement.id());
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    public Loader loader() {
        return loader;
    }

    /** Debug summary used by /civ debug. */
    public String debugSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("WorldSimulation: t=").append(String.format("%.2f", timeDays))
            .append("d, year=").append(year()).append(", ticks=").append(tickCount)
            .append(", speed=").append(simulationSpeed)
                .append(", civs=").append(civilizations.size())
                .append(", cities=").append(settlements.size())
                .append(", citizens=").append(citizens.size())
                .append(", frontierOutposts=").append(borderOutposts.size()).append('\n');
        for (Civilization civ : civilizations.values()) {
            int pop = settlementsOf(civ.id()).stream().mapToInt(Settlement::population).sum();
            double food = settlementsOf(civ.id()).stream()
                    .mapToDouble(s -> s.stockpile().get(ResourceType.FOOD)).sum();
                double foodDelta = settlementsOf(civ.id()).stream()
                    .mapToDouble(s -> s.stockpile().deltaPerDay(ResourceType.FOOD)).sum();
                double woodDelta = settlementsOf(civ.id()).stream()
                    .mapToDouble(s -> s.stockpile().deltaPerDay(ResourceType.WOOD)).sum();
                double stoneDelta = settlementsOf(civ.id()).stream()
                    .mapToDouble(s -> s.stockpile().deltaPerDay(ResourceType.STONE)).sum();
            sb.append("  civ '").append(civ.name()).append("' id=").append(civ.id())
                    .append(" cities=").append(civ.settlementIds().size())
                    .append(" knownSettlements=").append(civ.knownSettlementIds().size())
                    .append(" pop=").append(pop)
                    .append(" food=").append(String.format("%.0f", food))
                    .append(" daily(food/wood/stone)=")
                    .append(String.format("%+.1f/%+.1f/%+.1f", foodDelta, woodDelta, stoneDelta))
                    .append(" treasury=").append(String.format("%.0f", civ.treasury()))
                    .append('\n');
            for (Settlement city : settlementsOf(civ.id())) {
                Map<Citizen.Profession, Integer> jobs = new java.util.EnumMap<>(Citizen.Profession.class);
                for (UUID citizenId : city.citizenIds()) {
                    Citizen citizen = citizens.get(citizenId);
                    if (citizen != null) {
                        jobs.merge(citizen.profession(), 1, Integer::sum);
                    }
                }
                String jobSummary = jobs.entrySet().stream()
                        .filter(entry -> entry.getValue() > 0)
                        .map(entry -> entry.getKey().name().toLowerCase(java.util.Locale.ROOT)
                                + "=" + entry.getValue())
                        .collect(java.util.stream.Collectors.joining(","));
                sb.append("    city '").append(city.name()).append("' jobs=[")
                        .append(jobSummary).append("] daily[food/wood/stone/iron/coal/tools/weapons/building_materials]=")
                        .append(String.format("%+.1f/%+.1f/%+.1f/%+.1f/%+.1f/%+.1f/%+.1f/%+.1f",
                                city.stockpile().deltaPerDay(ResourceType.FOOD),
                                city.stockpile().deltaPerDay(ResourceType.WOOD),
                                city.stockpile().deltaPerDay(ResourceType.STONE),
                                city.stockpile().deltaPerDay(ResourceType.IRON),
                                city.stockpile().deltaPerDay(ResourceType.COAL),
                                city.stockpile().deltaPerDay(ResourceType.TOOLS),
                                city.stockpile().deltaPerDay(ResourceType.WEAPONS),
                                city.stockpile().deltaPerDay(ResourceType.BUILDING_MATERIALS)))
                        .append(" buildings=").append(city.buildings())
                        .append(" physicalBuildings=").append(city.materializedBuildings())
                        .append(" pendingPhysical=").append(city.pendingPhysicalBuildings().size())
                        .append(" activePlan=").append(city.activeBuildingPlan() == null ? "none"
                                : city.activeBuildingPlan().type() + " " + city.activeBuildingPlan().progressPercent() + "%")
                        .append('\n');
            }
        }
        return sb.toString();
    }
}
