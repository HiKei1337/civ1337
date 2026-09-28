package dev.autociv.simulation.model;

/** Scale of a settlement, derived from its real population and completed homes. */
public enum SettlementTier {
    OUTPOST("застава"),
    HAMLET("посёлок"),
    VILLAGE("деревня"),
    TOWN("городок"),
    CITY("город"),
    METROPOLIS("метрополия");

    private final String displayNameRu;

    SettlementTier(String displayNameRu) {
        this.displayNameRu = displayNameRu;
    }

    public String displayNameRu() { return displayNameRu; }

    public static SettlementTier from(int population, int homes) {
        int scale = Math.min(Math.max(0, population), Math.max(0, homes));
        if (scale >= 200) return METROPOLIS;
        if (scale >= 80) return CITY;
        if (scale >= 30) return TOWN;
        if (scale >= 12) return VILLAGE;
        if (scale >= 4) return HAMLET;
        return OUTPOST;
    }
}
