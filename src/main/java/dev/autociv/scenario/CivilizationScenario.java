package dev.autociv.scenario;

import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.ResourceType;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Historical-inspired starting data for a newly generated civilization. */
public record CivilizationScenario(
        String id,
        String name,
        String era,
        int startYear,
        int color,
        ArchitectureStyle architecture,
        int initialPopulation,
        double treasury,
        double culture,
        double militaryStrength,
        Map<ResourceType, Double> resources,
        List<Citizen.Profession> initialProfessions,
        List<HistoricalMilestone> milestones,
        List<String> cityNames) {

    public enum ArchitectureStyle {
                ROMAN, EGYPTIAN, HELLENIC, MAYAN, MESOPOTAMIAN, CHINESE,
                INDIAN, AFRICAN, SOUTHEAST_ASIAN, ANDEAN, PHOENICIAN, CARTHAGINIAN
    }

        public record HistoricalMilestone(int yearsAfterStart, String event,
                                                                          double treasuryDelta, double cultureDelta,
                                                                          double militaryDelta, Map<ResourceType, Double> resourceDeltas) {
                public HistoricalMilestone {
                        resourceDeltas = Map.copyOf(resourceDeltas);
                }
        }

    public CivilizationScenario {
        resources = Map.copyOf(resources);
        initialProfessions = List.copyOf(initialProfessions);
        milestones = List.copyOf(milestones);
        cityNames = List.copyOf(cityNames);
    }

    public static List<CivilizationScenario> all() {
        return List.of(
                new CivilizationScenario("rome", "Rome", "Roman Kingdom, traditionally founded 753 BCE",
                        -753, 0xA33A32, ArchitectureStyle.ROMAN, 24, 450, 8, 12,
                        Map.of(ResourceType.FOOD, 1800.0, ResourceType.WOOD, 900.0,
                                ResourceType.STONE, 500.0, ResourceType.IRON, 120.0),
                        professions(Citizen.Profession.FARMER, Citizen.Profession.FARMER,
                                Citizen.Profession.LUMBERJACK, Citizen.Profession.MINER,
                                Citizen.Profession.SOLDIER),
                        List.of(
                                new HistoricalMilestone(244, "Roman Republic traditionally established",
                                        180, 24, 18, Map.of(ResourceType.IRON, 80.0, ResourceType.FOOD, -120.0)),
                                new HistoricalMilestone(489, "Roman influence expands across the Italian peninsula",
                                        320, 35, 28, Map.of(ResourceType.WEAPONS, 60.0)),
                                new HistoricalMilestone(726, "Augustan principate begins; Rome enters its imperial era",
                                        800, 80, 45, Map.of(ResourceType.GOLD, 100.0))),
                        List.of("Roma", "Ostia", "Aricia")),
                new CivilizationScenario("egypt", "Egypt", "Old Kingdom, c. 2686 BCE",
                        -2686, 0xC28A38, ArchitectureStyle.EGYPTIAN, 28, 700, 15, 8,
                        Map.of(ResourceType.FOOD, 2200.0, ResourceType.WOOD, 500.0,
                                ResourceType.STONE, 1400.0, ResourceType.GOLD, 90.0),
                        professions(Citizen.Profession.FARMER, Citizen.Profession.FARMER,
                                Citizen.Profession.BUILDER, Citizen.Profession.MINER,
                                Citizen.Profession.MERCHANT),
                        List.of(
                                new HistoricalMilestone(126, "Monumental pyramid-building flourishes at Giza",
                                        220, 38, 4, Map.of(ResourceType.STONE, -180.0)),
                                new HistoricalMilestone(505, "Old Kingdom authority gives way to regional powers",
                                        -120, 24, -3, Map.of(ResourceType.FOOD, -200.0))),
                        List.of("Ineb-Hedj", "Waset", "Elephantine")),
                new CivilizationScenario("greece", "Hellas", "Classical Greece, c. 480 BCE",
                        -480, 0x547A9B, ArchitectureStyle.HELLENIC, 22, 520, 24, 15,
                        Map.of(ResourceType.FOOD, 1500.0, ResourceType.WOOD, 700.0,
                                ResourceType.STONE, 1000.0, ResourceType.COPPER, 140.0),
                        professions(Citizen.Profession.FARMER, Citizen.Profession.FISHERMAN,
                                Citizen.Profession.BUILDER, Citizen.Profession.MERCHANT,
                                Citizen.Profession.SOLDIER),
                        List.of(
                                new HistoricalMilestone(30, "Athenian-led alliances reshape the Aegean world",
                                        160, 34, 16, Map.of(ResourceType.COPPER, 50.0)),
                                new HistoricalMilestone(76, "The Peloponnesian War strains Greek city-states",
                                        -240, 12, -14, Map.of(ResourceType.FOOD, -180.0,
                                                ResourceType.WEAPONS, -35.0))),
                        List.of("Athens", "Sparta", "Corinth")),
                new CivilizationScenario("maya", "Maya", "Classic Maya period, c. 250 CE",
                        250, 0x3E7951, ArchitectureStyle.MAYAN, 20, 380, 18, 10,
                        Map.of(ResourceType.FOOD, 1600.0, ResourceType.WOOD, 1000.0,
                                ResourceType.STONE, 800.0, ResourceType.LUXURY_GOODS, 60.0),
                        professions(Citizen.Profession.FARMER, Citizen.Profession.FARMER,
                                Citizen.Profession.BUILDER, Citizen.Profession.HUNTER,
                                Citizen.Profession.RESEARCHER),
                        List.of(
                                new HistoricalMilestone(200, "Classic Maya city-states enter a period of growth",
                                        130, 30, 8, Map.of(ResourceType.LUXURY_GOODS, 45.0)),
                                new HistoricalMilestone(450, "Monumental centers and regional rivalries intensify",
                                        90, 42, 18, Map.of(ResourceType.STONE, -90.0,
                                                ResourceType.WEAPONS, 25.0))),
                        List.of("Tikal", "Calakmul", "Palenque")),
                additional("carthage", "Carthage", "Phoenician-founded Carthage, traditionally 814 BCE",
                        -814, 0xB77A42, ArchitectureStyle.CARTHAGINIAN, 24, ResourceType.COPPER,
                        "Carthage", "Utica", "Leptis Magna", 550,
                        "The Punic Wars reshape the western Mediterranean"),
                additional("persia", "Persia", "Achaemenid Persian Empire, c. 550 BCE",
                        -550, 0x7D506D, ArchitectureStyle.MESOPOTAMIAN, 30, ResourceType.GOLD,
                        "Pasargadae", "Persepolis", "Susa", 60,
                        "The Greco-Persian Wars test the Achaemenid frontier"),
                additional("babylon", "Babylon", "Neo-Babylonian Empire, c. 626 BCE",
                        -626, 0xA17A45, ArchitectureStyle.MESOPOTAMIAN, 26, ResourceType.STONE,
                        "Babylon", "Borsippa", "Sippar", 39,
                        "Babylon becomes the dominant power in Mesopotamia"),
                additional("assyria", "Assyria", "Neo-Assyrian Empire, c. 911 BCE",
                        -911, 0x80664D, ArchitectureStyle.MESOPOTAMIAN, 28, ResourceType.IRON,
                        "Assur", "Nineveh", "Nimrud", 189,
                        "Assyria reaches imperial scale across the Near East"),
                additional("han", "Han China", "Han dynasty, founded 202 BCE",
                        -202, 0xA84339, ArchitectureStyle.CHINESE, 32, ResourceType.IRON,
                        "Chang'an", "Luoyang", "Chengdu", 211,
                        "The Western Han gives way to the Xin interregnum"),
                additional("maurya", "Maurya", "Mauryan Empire, c. 322 BCE",
                        -322, 0xC09245, ArchitectureStyle.INDIAN, 30, ResourceType.FOOD,
                        "Pataliputra", "Taxila", "Ujjain", 61,
                        "The Kalinga War transforms Ashoka's rule"),
                additional("aksum", "Aksum", "Kingdom of Aksum, c. 100 CE",
                        100, 0x795849, ArchitectureStyle.AFRICAN, 22, ResourceType.GOLD,
                        "Aksum", "Adulis", "Matara", 250,
                        "Aksum becomes a major Red Sea trading power"),
                additional("khmer", "Khmer", "Khmer Empire, founded 802 CE",
                        802, 0x8C754D, ArchitectureStyle.SOUTHEAST_ASIAN, 28, ResourceType.STONE,
                        "Hariharalaya", "Angkor", "Mahendraparvata", 100,
                        "Angkor becomes the Khmer imperial center"),
                additional("inca", "Inca", "Inca expansion, c. 1438 CE",
                        1438, 0xA66C47, ArchitectureStyle.ANDEAN, 24, ResourceType.STONE,
                        "Cusco", "Quito", "Ollantaytambo", 40,
                        "Pachacuti begins rapid Inca expansion"),
                additional("phoenicia", "Phoenicia", "Phoenician city-states, c. 1200 BCE",
                        -1200, 0x416D78, ArchitectureStyle.PHOENICIAN, 22, ResourceType.WOOD,
                        "Tyre", "Sidon", "Byblos", 400,
                        "Phoenician maritime colonies spread across the Mediterranean"),
                additional("olmec", "Olmec", "Olmec heartland, c. 1500 BCE",
                        -1500, 0x46684F, ArchitectureStyle.MAYAN, 20, ResourceType.LUXURY_GOODS,
                        "San Lorenzo", "La Venta", "Tres Zapotes", 600,
                        "Olmec ceremonial centers decline as regional cultures rise"));
    }

    public static Optional<CivilizationScenario> byId(String id) {
        return all().stream().filter(scenario -> scenario.id().equalsIgnoreCase(id)).findFirst();
    }

    private static List<Citizen.Profession> professions(Citizen.Profession... professions) {
        return List.of(professions);
    }

    private static CivilizationScenario additional(String id, String name, String era, int startYear,
                                                  int color, ArchitectureStyle architecture, int population,
                                                  ResourceType signatureResource, String capital,
                                                  String secondCity, String thirdCity, int milestoneYears,
                                                  String milestoneEvent) {
        Map<ResourceType, Double> startingResources = new HashMap<>();
        startingResources.put(ResourceType.FOOD, 1800.0);
        startingResources.put(ResourceType.WOOD, 750.0);
        startingResources.put(ResourceType.STONE, 750.0);
        startingResources.merge(signatureResource, 180.0, Double::sum);
        Map<ResourceType, Double> milestoneResources = new HashMap<>();
        milestoneResources.put(ResourceType.FOOD, -80.0);
        milestoneResources.merge(signatureResource, 50.0, Double::sum);
        return new CivilizationScenario(id, name, era, startYear, color, architecture, population,
                420, 14, 11,
                startingResources,
                professions(Citizen.Profession.FARMER, Citizen.Profession.FARMER,
                        Citizen.Profession.MINER, Citizen.Profession.LUMBERJACK,
                        Citizen.Profession.BUILDER, Citizen.Profession.SOLDIER),
                List.of(new HistoricalMilestone(milestoneYears, milestoneEvent,
                        260, 32, 15, milestoneResources)),
                List.of(capital, secondCity, thirdCity));
    }
}