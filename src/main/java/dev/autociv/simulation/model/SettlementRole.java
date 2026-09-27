package dev.autociv.simulation.model;

/** Economic identity of a city in its civilization's settlement network. */
public enum SettlementRole {
    CAPITAL,
    AGRICULTURE,
    FORESTRY,
    MINING,
    TRADE,
    FRONTIER,
    VILLAGE;

    public static SettlementRole forResource(ResourceType resource) {
        return switch (resource.id()) {
            case "food" -> AGRICULTURE;
            case "wood" -> FORESTRY;
            case "stone", "iron", "coal", "copper", "gold" -> MINING;
            default -> TRADE;
        };
    }

    public double productionMultiplier(ResourceType resource) {
        if (this == CAPITAL) return 1.05;
        return switch (this) {
            case AGRICULTURE -> resource.equals(ResourceType.FOOD) ? 1.30 : 1.0;
            case FORESTRY -> resource.equals(ResourceType.WOOD) ? 1.30 : 1.0;
            case MINING -> resource.equals(ResourceType.STONE) || resource.equals(ResourceType.IRON)
                    || resource.equals(ResourceType.COAL) ? 1.30 : 1.0;
            case TRADE -> 1.0;
            case FRONTIER, VILLAGE, CAPITAL -> 1.0;
        };
    }

    public String displayNameRu() {
        return switch (this) {
            case CAPITAL -> "столица";
            case AGRICULTURE -> "сельскохозяйственный центр";
            case FORESTRY -> "лесной центр";
            case MINING -> "горнодобывающий центр";
            case TRADE -> "торговый центр";
            case FRONTIER -> "пограничный город";
            case VILLAGE -> "деревня";
        };
    }
}
