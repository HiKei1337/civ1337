package dev.autociv.simulation.model;

/** Abstract civic facilities planned in Stage 5 and materialized by the building system later. */
public enum BuildingType {
    FARM(64, 24, 20, 3),
    MINE(72, 48, 28, 2),
    WAREHOUSE(80, 40, 24, 2),
    MARKET(72, 48, 24, 1),
    BANK(128, 96, 40, 1),
    BARRACKS(100, 80, 35, 1),
    CLINIC(96, 72, 30, 1),
    SCHOOL(112, 72, 32, 1),
    FORTIFICATION(120, 120, 40, 1),
    ROAD(32, 8, 10, 8),
    RAILWAY_STATION(160, 120, 72, 1);

    private final int woodCost;
    private final int stoneCost;
    private final int constructionDays;
    private final int cityLimit;

    BuildingType(int woodCost, int stoneCost, int constructionDays, int cityLimit) {
        this.woodCost = woodCost;
        this.stoneCost = stoneCost;
        this.constructionDays = constructionDays;
        this.cityLimit = cityLimit;
    }

    public int woodCost() { return woodCost; }
    public int stoneCost() { return stoneCost; }
    public int constructionDays() { return constructionDays; }
    public int cityLimit() { return cityLimit; }
}
