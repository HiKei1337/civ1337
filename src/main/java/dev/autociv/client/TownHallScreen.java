package dev.autociv.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Styled town overview, relationship and economy interface opened from the town-hall bell. */
public final class TownHallScreen extends Screen {
    private static final int OVERVIEW = 0;
    private static final int ECONOMY = 1;
    private static final int MANAGEMENT = 2;
    private final String cityId;
    private String[] data;
    private int tab;

    private TownHallScreen(String cityId, String snapshot) {
        super(Component.literal("Ратуша"));
        this.cityId = cityId;
        setSnapshot(snapshot);
    }

    public static void open(String cityId, String snapshot) {
        Minecraft.getInstance().setScreen(new TownHallScreen(cityId, snapshot));
    }

    private void setSnapshot(String snapshot) {
        data = snapshot.split("\\|", -1);
    }

    @Override
    protected void init() {
        clearWidgets();
        int left = width / 2 - 190;
        int top = height / 2 - 112;
        addRenderableWidget(Button.builder(Component.literal("Обзор"), button -> setTab(OVERVIEW))
                .bounds(left + 16, top + 39, 78, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Экономика"), button -> setTab(ECONOMY))
                .bounds(left + 98, top + 39, 88, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Управление"), button -> setTab(MANAGEMENT))
                .bounds(left + 190, top + 39, 94, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Обновить"), button -> refresh())
                .bounds(left + 288, top + 39, 78, 20).build());
        addRenderableWidget(Button.builder(Component.literal("×"), button -> onClose())
                .bounds(left + 344, top + 8, 24, 20).build());

        int buttonY = top + 211;
        if (tab == OVERVIEW) {
            addRenderableWidget(donateButton("Сдать еду", "food", left + 18, buttonY));
            addRenderableWidget(donateButton("Сдать дерево", "wood", left + 106, buttonY));
            addRenderableWidget(donateButton("Сдать камень", "stone", left + 194, buttonY));
            addRenderableWidget(donateButton("Сдать уголь", "coal", left + 282, buttonY));
        } else if (tab == ECONOMY && isOwner() && number(15) <= 0 && !isBankPlanned()) {
            addRenderableWidget(Button.builder(Component.literal("Заказать строительство банка · 128 дерева / 96 камня"),
                            button -> send("civ hall " + cityId + " bank"))
                    .bounds(left + 18, buttonY, 350, 20).build());
        } else if (tab == MANAGEMENT) {
            String owner = field(29);
            String playerId = minecraft != null && minecraft.player != null ? minecraft.player.getUUID().toString() : "";
            if (owner.equals("-")) {
                addRenderableWidget(Button.builder(Component.literal("Стать управляющим"),
                                button -> send("civ manage " + cityId + " claim"))
                        .bounds(left + 18, top + 188, 120, 20).build());
            } else if (owner.equals(playerId)) {
                addRenderableWidget(Button.builder(Component.literal("Сменить приоритет"),
                                button -> send("civ manage " + cityId + " priority " + nextPriority()))
                        .bounds(left + 18, top + 188, 120, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Сменить стратегию"),
                                button -> send("civ manage " + cityId + " strategy " + nextStrategy()))
                        .bounds(left + 144, top + 188, 120, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Проект здания"),
                                button -> openCommand("civ manage " + cityId + " build "))
                        .bounds(left + 270, top + 188, 96, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Назначить работу"),
                                button -> openCommand("civ manage " + cityId + " job "))
                        .bounds(left + 18, top + 211, 84, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Отменить стройку"),
                                button -> send("civ manage " + cityId + " cancel"))
                        .bounds(left + 106, top + 211, 84, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Порядок очереди"),
                                button -> openCommand("civ manage " + cityId + " order "))
                        .bounds(left + 194, top + 211, 84, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Заказ крафта"),
                                button -> openCommand("civ manage " + cityId + " craft "))
                        .bounds(left + 282, top + 211, 84, 20).build());
            }
        }
        addRenderableWidget(Button.builder(Component.literal("Закрыть"), button -> onClose())
                .bounds(left + 300, top + 8, 66, 20).build());
    }

    private Button donateButton(String label, String resource, int x, int y) {
        return Button.builder(Component.literal(label), button -> send("civ hall " + cityId + " donate " + resource))
                .bounds(x, y, 82, 20).build();
    }

    private boolean isBankPlanned() {
        return field(18).equals("1");
    }

    private void setTab(int next) {
        tab = next;
        init();
    }

    private void refresh() { send("civ hall " + cityId); }

    private void send(String command) {
        if (minecraft != null && minecraft.player != null && minecraft.player.connection != null) {
            minecraft.player.connection.sendCommand(command);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = width / 2 - 190;
        int top = height / 2 - 112;
        int right = left + 380;
        int bottom = top + 260;
        graphics.fill(left - 3, top - 3, right + 3, bottom + 3, 0xFF24180F);
        graphics.fill(left, top, right, bottom, 0xFF42301E);
        graphics.fill(left + 5, top + 5, right - 5, bottom - 5, 0xFF30251A);
        graphics.fill(left + 10, top + 31, right - 10, top + 33, 0xFFC89B52);
        graphics.drawCenteredString(font, field(1) + " · " + field(2), width / 2, top + 15, 0xFFF0D59A);
        graphics.drawString(font, "РАТУША", left + 16, top + 25, 0xFFD8B56D);

        if (tab == OVERVIEW) renderOverview(graphics, left, top);
        else if (tab == ECONOMY) renderEconomy(graphics, left, top);
        else renderManagement(graphics, left, top);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderOverview(GuiGraphics g, int x, int y) {
        String relation = relationship(number(3));
        g.drawString(font, "Отношение жителей", x + 20, y + 70, 0xFFE5D6B8);
        g.drawString(font, relation + " · " + String.format(Locale.ROOT, "%.0f", number(3)) + "/100",
                x + 20, y + 84, 0xFFFFD36C);
        g.fill(x + 20, y + 98, x + 358, y + 105, 0xFF17130F);
        g.fill(x + 21, y + 99, x + 21 + (int) (336 * Math.clamp(number(3) / 100.0, 0, 1)),
                y + 104, 0xFF76B56B);

        g.drawString(font, "Жители: " + field(4) + " / " + field(5) + " мест · " + tierName(field(43)),
                x + 20, y + 117, 0xFFE5D6B8);
        g.drawString(font, "Дома: " + field(6) + " готовы / " + field(7) + " по плану",
                x + 20, y + 131, 0xFFE5D6B8);
        g.drawString(font, "Профиль города: " + roleName(field(42)), x + 20, y + 144, 0xFFD8B56D);
        g.drawString(font, "Настроение: " + percent(10) + "   Здоровье: " + percent(11)
                + "   Потребности: " + percent(12), x + 20, y + 160, 0xFFB9D0C0);
        g.drawString(font, "Безопасность: " + percent(9) + "   Казна: " + field(13),
                x + 20, y + 173, 0xFFB9D0C0);
        g.drawString(font, "Запасы города", x + 20, y + 189, 0xFFD8B56D);
        g.drawString(font, "Еда " + field(19) + "   Дерево " + field(21) + "   Камень " + field(23)
                + "   Уголь " + field(27), x + 20, y + 198, 0xFFE5D6B8);
        g.drawCenteredString(font, "Кнопка передачи сдаёт ресурс из инвентаря в общий склад ратуши",
                width / 2, y + 238, 0xFFB5A68D);
    }

    private void renderEconomy(GuiGraphics g, int x, int y) {
        g.drawString(font, "ЭКОНОМИКА ГОРОДА", x + 20, y + 70, 0xFFD8B56D);
        g.drawString(font, "Банк: " + (number(15) > 0 ? "построен" : isBankPlanned() ? "строится" : "не построен"),
                x + 20, y + 89, 0xFFE5D6B8);
        g.drawString(font, "Казна цивилизации: " + field(13), x + 20, y + 108, 0xFFE5D6B8);
        g.drawString(font, "Средний доход жителя: " + field(14) + " монет / день", x + 20, y + 127, 0xFFE5D6B8);
        g.drawString(font, "Еда: " + field(19) + " (" + field(20) + "/д)", x + 20, y + 149, 0xFFB9D0C0);
        g.drawString(font, "Дерево: " + field(21) + " (" + field(22) + "/д)", x + 20, y + 165, 0xFFB9D0C0);
        g.drawString(font, "Камень: " + field(23) + " (" + field(24) + "/д)", x + 20, y + 181, 0xFFB9D0C0);
        g.drawString(font, "Железо: " + field(25) + " (" + field(26) + "/д)   Уголь: " + field(27)
                + " (" + field(28) + "/д)", x + 20, y + 197, 0xFFB9D0C0);
        g.drawString(font, "Торговые связи: " + field(32) + " · рынок: " + shorten(field(35), 27),
                x + 20, y + 211, 0xFFE5D6B8);
        g.drawString(font, "Грузы в пути сюда: " + shorten(field(40).isBlank() ? "нет" : field(40), 48),
                x + 20, y + 225, 0xFFB9D0C0);
        g.drawString(font, "Отправлено городом: " + shorten(field(41).isBlank() ? "нет" : field(41), 43),
                x + 20, y + 239, 0xFFB9D0C0);
    }

    private void renderManagement(GuiGraphics g, int x, int y) {
        String owner = field(29);
        String playerId = minecraft != null && minecraft.player != null ? minecraft.player.getUUID().toString() : "";
        g.drawString(font, "УПРАВЛЕНИЕ КОЛОНИЕЙ", x + 20, y + 70, 0xFFD8B56D);
        g.drawString(font, "Владелец: " + (owner.equals("-") ? "не назначен" : owner.equals(playerId) ? "ты" : owner),
                x + 20, y + 87, 0xFFE5D6B8);
        g.drawString(font, "Приоритет: " + field(30) + "   Стратегия: " + field(31),
                x + 20, y + 103, 0xFFB9D0C0);
        g.drawString(font, "Жители: " + shorten(field(33), 50), x + 20, y + 115, 0xFFE5D6B8);
        g.drawString(font, "Стройка: " + shorten(field(36), 47), x + 20, y + 131, 0xFFB9D0C0);
        g.drawString(font, "Мастерская: " + shorten(field(37), 43), x + 20, y + 147, 0xFFB9D0C0);
        g.drawString(font, "Склад: " + shorten(field(38), 56), x + 20, y + 163, 0xFFB9D0C0);
        g.drawString(font, field(39).equals("нет") ? "Событие: " + shorten(field(34), 50)
                : "Строителю нужен: " + shorten(demandLabel(field(39)), 43), x + 20, y + 175,
                field(39).equals("нет") ? 0xFFB9D0C0 : 0xFFFFD36C);
        g.drawString(font, "Город-основатель: " + shorten(field(44), 36), x + 20, y + 188, 0xFFB9D0C0);
        if (!owner.equals("-") && !owner.equals(playerId)) {
            g.drawString(font, "Изменения доступны владельцу.", x + 20, y + 202, 0xFFFF8B76);
        }
        g.drawCenteredString(font, "Владелец назначается рядом с ратушей при доверии 25/100",
                width / 2, y + 238, 0xFFB5A68D);
    }

    private String nextPriority() {
        return switch (field(30)) { case "food" -> "construction"; case "construction" -> "extraction";
            case "extraction" -> "trade"; case "trade" -> "defense"; default -> "food"; };
    }

    private String nextStrategy() {
        return switch (field(31)) { case "growth" -> "reserves"; case "reserves" -> "fortify"; default -> "growth"; };
    }

    private boolean isOwner() {
        return minecraft != null && minecraft.player != null
                && minecraft.player.getUUID().toString().equals(field(29));
    }

    private void openCommand(String command) {
        if (minecraft != null) minecraft.setScreen(new net.minecraft.client.gui.screens.ChatScreen("/" + command));
    }

    private String shorten(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, Math.max(0, limit - 1)) + "…";
    }

    private String demandLabel(String itemId) {
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(itemId);
        if (id == null) return itemId;
        net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getOptional(id).orElse(null);
        return item == null ? id.getPath().replace('_', ' ')
                : new net.minecraft.world.item.ItemStack(item).getHoverName().getString();
    }

    private String percent(int index) { return String.format(Locale.ROOT, "%.0f%%", number(index) * 100); }
    private String field(int index) { return index < data.length ? data[index] : "—"; }
    private double number(int index) {
        try { return Double.parseDouble(field(index)); } catch (NumberFormatException ignored) { return 0; }
    }

    private String relationship(double favor) {
        if (favor >= 80) return "Почётный союзник";
        if (favor >= 55) return "Уважаемый гость";
        if (favor >= 25) return "Знакомый";
        if (favor > 0) return "Настороженное доверие";
        return "Незнакомец";
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

    @Override public boolean isPauseScreen() { return false; }
}
