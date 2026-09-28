package dev.autociv.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** In-game overview for civilization travel, resources, housing and live citizen jobs. */
public final class CivDebugScreen extends Screen {

    private static final int CITIES_VIEW = 0;
    private static final int WORKERS_VIEW = 1;
    private static final int ADMIN_VIEW = 2;

    private final String snapshot;
    private final List<String[]> cities = new ArrayList<>();
    private final List<String[]> workers = new ArrayList<>();
    private int view = CITIES_VIEW;
    private int page;
    private String speed = "?";
    private String days = "?";
    private String civilizationCount = "0";
    private String[] selectedWorker;

    private CivDebugScreen(String snapshot) {
        super(Component.literal("Автономные цивилизации"));
        this.snapshot = snapshot;
        parseSnapshot();
    }

    public static void open(String snapshot) {
        Minecraft.getInstance().setScreen(new CivDebugScreen(snapshot));
    }

    @Override
    protected void init() {
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearWidgets();
        int top = 38;
        addRenderableWidget(Button.builder(Component.literal("Поселения"), button -> show(CITIES_VIEW))
                .bounds(width / 2 - 210, top, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Жители и AI"), button -> show(WORKERS_VIEW))
                .bounds(width / 2 - 105, top, 105, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Админ"), button -> show(ADMIN_VIEW))
                .bounds(width / 2 - 295, top, 75, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Обновить"), button -> send("civ gui"))
                .bounds(width / 2 + 5, top, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("/civ debug"), button -> send("civ debug"))
                .bounds(width / 2 + 90, top, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Экономика"), button -> send("civ trade"))
                .bounds(width / 2 + 185, top, 95, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Сим. время"), button -> {
                    send("civ simulate debug");
                    send("civ gui");
                })
                .bounds(width / 2 + 285, top, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Пауза"), button -> send("civ admin pause"))
                .bounds(width / 2 - 210, top + 24, 70, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Шаг +1 день"), button -> send("civ admin step"))
                .bounds(width / 2 - 135, top + 24, 90, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Админ справка"), button -> send("civ admin"))
                .bounds(width / 2 - 40, top + 24, 105, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Дипломатия"), button -> send("civ diplomacy"))
                .bounds(width / 2 + 70, top + 24, 90, 18).build());

        int rowCount = rowsPerPage();
        List<String[]> rows = view == WORKERS_VIEW ? workers : cities;
        int pages = Math.max(1, (rows.size() + rowCount - 1) / rowCount);
        page = Math.min(page, pages - 1);
        if (view != WORKERS_VIEW) {
            for (int index = 0; index < rowCount; index++) {
                int rowIndex = page * rowCount + index;
                if (rowIndex >= cities.size()) {
                    break;
                }
                String[] city = cities.get(rowIndex);
                String label = cityLabel(city);
                addRenderableWidget(Button.builder(Component.literal(label), button ->
                                send(view == ADMIN_VIEW ? "civ admin history " + city[2] : "civ tp " + city[1] + " " + city[2]))
                        .bounds(width / 2 - 270, 110 + index * 46, 270, 21).build());
                addRenderableWidget(Button.builder(Component.literal("Рынок"), button ->
                                send("civ economy " + city[1] + " " + city[2]))
                        .bounds(width / 2 + 5, 110 + index * 46, 68, 21).build());
                addRenderableWidget(Button.builder(Component.literal("Тест стройки"), button -> {
                                send("civ debug build " + city[1] + " " + city[2]);
                                send("civ gui");
                            }).bounds(width / 2 + 78, 110 + index * 46, 95, 21).build());
                addRenderableWidget(Button.builder(Component.literal("Тест школы"), button -> {
                                send("civ debug facility " + city[1] + " " + city[2] + " school");
                                send("civ gui");
                            }).bounds(width / 2 + 178, 110 + index * 46, 82, 21).build());
                addRenderableWidget(Button.builder(Component.literal(view == ADMIN_VIEW ? "Команды" : "Админ"), button -> openCommand(
                                "/civ admin city " + city[2] + " "))
                        .bounds(width / 2 + 265, 110 + index * 46, 72, 21).build());
            }
        } else {
            if (selectedWorker != null) {
                String[] selected = selectedWorker;
                String[] roles = {"farmer", "miner", "lumberjack", "builder", "guard"};
                String[] labels = {"Фермер", "Шахтёр", "Лесоруб", "Строитель", "Страж"};
                int buttonWidth = 86;
                int firstX = width / 2 - roles.length * buttonWidth / 2;
                for (int roleIndex = 0; roleIndex < roles.length; roleIndex++) {
                    String role = roles[roleIndex];
                    addRenderableWidget(Button.builder(Component.literal(labels[roleIndex]), button -> {
                                send("civ job " + selected[1] + " " + selected[2]
                                        + " " + selected[8] + " " + role);
                                selectedWorker = null;
                                send("civ gui");
                            }).bounds(firstX + roleIndex * (buttonWidth + 4), 86, buttonWidth, 20).build());
                }
            }
            int workerStartY = selectedWorker == null ? 112 : 174;
            for (int index = 0; index < rowCount; index++) {
                int rowIndex = page * rowCount + index;
                if (rowIndex >= workers.size()) {
                    break;
                }
                String[] worker = workers.get(rowIndex);
                String cargo = worker.length > 9 ? " | рюкзак " + worker[9] : "";
                String items = worker.length > 15 && !worker[15].isEmpty()
                        ? " | предметы " + worker[15] : "";
                String line = String.format("%s | %s | %s | цель %s | в мире %s%s%s",
                        worker[4], professionName(worker[3]), worker[5], worker[6],
                        worker[7].equals("1") ? "да" : "нет", cargo, items);
                addRenderableWidget(Button.builder(Component.literal(line), button -> {
                            selectedWorker = worker;
                            rebuildButtons();
                        }).bounds(20, workerStartY + index * 17, width - 40, 16).build());
            }
        }
        int footer = height - 32;
        addRenderableWidget(Button.builder(Component.literal("‹"), button -> changePage(-1, rowCount))
                .bounds(width / 2 - 90, footer, 35, 20).build());
        addRenderableWidget(Button.builder(Component.literal((page + 1) + " / " + pages), button -> { })
                .bounds(width / 2 - 50, footer, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("›"), button -> changePage(1, rowCount))
                .bounds(width / 2 + 55, footer, 35, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Закрыть"), button -> onClose())
                .bounds(width - 75, footer, 65, 20).build());
    }

    private void show(int nextView) {
        view = nextView;
        page = 0;
        rebuildButtons();
    }

    private void changePage(int delta, int rowCount) {
        List<String[]> rows = view == WORKERS_VIEW ? workers : cities;
        int pages = Math.max(1, (rows.size() + rowCount - 1) / rowCount);
        page = Math.floorMod(page + delta, pages);
        rebuildButtons();
    }

    private int rowsPerPage() {
        return view != WORKERS_VIEW
                ? Math.max(1, Math.min(8, (height - 175) / 46))
                : Math.max(1, Math.min(12, (height - (selectedWorker == null ? 145 : 205)) / 17));
    }

    private void parseSnapshot() {
        for (String line : snapshot.split("\\R")) {
            String[] fields = line.split("\\|", -1);
            if (fields.length == 0) {
                continue;
            }
            if (fields[0].equals("META") && fields.length >= 5) {
                speed = fields[1];
                days = fields[3];
                civilizationCount = fields[4];
            } else if (fields[0].equals("CITY") && fields.length >= 18) {
                cities.add(fields);
            } else if (fields[0].equals("WORK") && fields.length >= 9) {
                workers.add(fields);
            }
        }
    }

    private String cityLabel(String[] city) {
        String building = Integer.parseInt(city[11]) < Integer.parseInt(city[10])
                ? " строит " + city[12] + "%" : "";
        return String.format("%s / %s | %s,%s | жители %s/%s | дома %s/%s%s",
                city[3], city[4], city[5], city[7], city[8], city[9], city[11], city[10], building)
                + (city.length > 19 && !city[19].equals("-") ? " | проект " + buildingName(city[19]) : "")
                + (city.length > 20 && !city[20].equals("0") ? " | ждут стройки " + city[20] : "");
    }

    private String professionName(String id) {
        return switch (id.toLowerCase(java.util.Locale.ROOT)) {
            case "farmer" -> "фермер";
            case "miner" -> "шахтёр";
            case "lumberjack" -> "лесоруб";
            case "builder" -> "строитель";
            case "guard", "soldier" -> "стражник";
            case "fisherman" -> "рыбак";
            case "hunter" -> "охотник";
            case "shepherd" -> "пастух";
            case "blacksmith" -> "кузнец";
            case "engineer" -> "инженер";
            case "merchant" -> "торговец";
            case "doctor" -> "лекарь";
            case "researcher" -> "учёный";
            default -> "без работы";
        };
    }

    private String tierName(String tier) {
        return switch (tier) { case "hamlet" -> "посёлок"; case "village" -> "деревня";
            case "town" -> "городок"; case "city" -> "город"; case "metropolis" -> "метрополия";
            default -> "застава"; };
    }

    private String roleName(String role) {
        return switch (role) { case "agriculture" -> "сельхозцентр"; case "forestry" -> "лесной центр";
            case "mining" -> "рудный центр"; case "trade" -> "торговый центр";
            case "frontier" -> "пограничный город"; case "capital" -> "столица";
            default -> "деревня"; };
    }

    private String buildingName(String project) {
        String type = project.split(" ", 2)[0].toLowerCase(java.util.Locale.ROOT);
        String name = switch (type) {
            case "farm" -> "ферма";
            case "mine" -> "шахта";
            case "warehouse" -> "склад";
            case "market" -> "рынок";
            case "bank" -> "банк";
            case "barracks" -> "казарма";
            case "clinic" -> "лечебница";
            case "school" -> "школа";
            case "fortification" -> "укрепление";
            case "road" -> "дорога";
            case "railway_station" -> "вокзал";
            default -> "здание";
        };
        int progress = project.indexOf('%');
        return progress < 0 ? name : name + " " + project.substring(project.lastIndexOf(' ') + 1, progress + 1);
    }

    private void send(String command) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.connection != null) {
            minecraft.player.connection.sendCommand(command);
        }
    }

    private void openCommand(String command) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new net.minecraft.client.gui.screens.ChatScreen(command));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
        graphics.drawCenteredString(font,
                Component.literal("Цивилизаций: " + civilizationCount + "  |  скорость: " + speed
                        + "  |  сим. дней: " + days).withStyle(ChatFormatting.GRAY),
                width / 2, 84, 0xAAAAAA);
        if (view == CITIES_VIEW) {
            if (cities.isEmpty()) {
                graphics.drawCenteredString(font, "В мире пока нет поселений", width / 2, 115, 0xCCCCCC);
            } else {
                graphics.drawCenteredString(font, "Нажми на город для ТП · запасы, уровень и специализация ниже",
                        width / 2, 98, 0xAAAAAA);
                int rowCount = rowsPerPage();
                int start = page * rowCount;
                for (int index = 0; index < rowCount && start + index < cities.size(); index++) {
                    String[] city = cities.get(start + index);
                    graphics.drawString(font, String.format("еда %s | дерево %s | камень %s | железо %s | уголь %s",
                            city[13], city[14], city[15], city[16], city[17]),
                            width / 2 - 240, 134 + index * 46, 0xAAAAAA);
                    if (city.length > 23) {
                        graphics.drawString(font, tierName(city[22]) + " · " + roleName(city[21])
                                        + " · родитель: " + city[23],
                                width / 2 - 240, 147 + index * 46, 0xFFB9D0C0);
                    }
                }
            }
        } else if (view == ADMIN_VIEW) {
            graphics.drawCenteredString(font, "Панель администратора · все изменения требуют права OP 2",
                    width / 2, 98, 0xFFFFD36C);
            int rowCount = rowsPerPage();
            int start = page * rowCount;
            for (int index = 0; index < rowCount && start + index < cities.size(); index++) {
                String[] city = cities.get(start + index);
                graphics.drawString(font, "Позиция " + city[5] + " " + city[6] + " " + city[7]
                        + " · жителей " + city[8] + " · проекты " + city[19],
                        width / 2 - 240, 134 + index * 46, 0xFFAAAAAA);
                if (city.length > 23) {
                    graphics.drawString(font, tierName(city[22]) + " · " + roleName(city[21])
                                    + " · родитель: " + city[23],
                            width / 2 - 240, 147 + index * 46, 0xFFB9D0C0);
                }
            }
        } else {
            graphics.drawString(font, "Житель: выбери строку, затем назначь профессию для проверки AI",
                    20, selectedWorker == null ? 96 : 122, 0xAAAAAA);
            if (selectedWorker != null && selectedWorker.length >= 15) {
                graphics.drawString(font, "Груз " + selectedWorker[9] + " | еда " + selectedWorker[10]
                        + " | сон " + selectedWorker[11] + " | безопасность " + selectedWorker[12],
                        20, 140, 0xD8C98B);
                graphics.drawString(font, "Общение " + selectedWorker[13] + " | комфорт " + selectedWorker[14],
                        20, 154, 0xD8C98B);
                if (selectedWorker.length > 15 && !selectedWorker[15].isEmpty()) {
                    graphics.drawString(font, "Предметы в рюкзаке: " + selectedWorker[15],
                            20, 168, 0xFFCD9A);
                }
            }
            int rowCount = rowsPerPage();
            int start = page * rowCount;
            if (workers.isEmpty()) {
                graphics.drawCenteredString(font, "Жителей пока нет", width / 2, 115, 0xCCCCCC);
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
