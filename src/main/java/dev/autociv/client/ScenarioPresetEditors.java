package dev.autociv.client;

import dev.autociv.scenario.CivilizationScenario;
import dev.autociv.scenario.CivilizationScenarioGameRules;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.RegisterPresetEditorsEvent;
import org.slf4j.Logger;

import java.util.List;

public final class ScenarioPresetEditors {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ScenarioPresetEditors() {
    }

    public static void register(net.neoforged.bus.api.IEventBus modEventBus) {
        modEventBus.addListener(ScenarioPresetEditors::registerPresetEditor);
        LOGGER.info("[autociv-worldgen] Registered client listener for historical scenario selector");
    }

    private static void registerPresetEditor(RegisterPresetEditorsEvent event) {
        event.register(WorldPresets.NORMAL, ScenarioSelectionScreen::new);
        LOGGER.info("[autociv-worldgen] Registered scenario selector for the Normal world preset");
    }

    private static final class ScenarioSelectionScreen extends Screen {
        private final CreateWorldScreen parent;
        private final List<CivilizationScenario> scenarios = CivilizationScenario.all();
        private int selectedIndex;
        private Button selectedButton;

        private ScenarioSelectionScreen(CreateWorldScreen parent,
                                        net.minecraft.client.gui.screens.worldselection.WorldCreationContext context) {
            super(Component.literal("Civilization Scenario"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int scenarioId = parent.getUiState().getGameRules()
                    .getRule(CivilizationScenarioGameRules.SCENARIO_ID).get();
            if (CivilizationScenarioGameRules.bySelectionId(scenarioId) == null) {
                selectedIndex = 0;
                storeSelection();
            } else {
                selectedIndex = scenarioId - 1;
            }
            int selectorY = height / 2 - 8;
                addRenderableWidget(Button.builder(Component.literal("<"), button -> cycle(-1))
                    .bounds(width / 2 - 205, selectorY, 40, 24).build());
            selectedButton = Button.builder(Component.literal(""), button -> {})
                    .bounds(width / 2 - 159, selectorY, 318, 24).build();
            addRenderableWidget(selectedButton);
            addRenderableWidget(Button.builder(Component.literal(">"), button -> cycle(1))
                    .bounds(width / 2 + 165, selectorY, 40, 24).build());
            addRenderableWidget(Button.builder(Component.literal("Back"), button -> Minecraft.getInstance()
                            .setScreen(parent))
                    .bounds(width / 2 - 75, selectorY + 42, 150, 20)
                    .build());
            refreshSelectionLabel();
        }

        private void cycle(int direction) {
            selectedIndex = Math.floorMod(selectedIndex + direction, scenarios.size());
            storeSelection();
            refreshSelectionLabel();
        }

        private void storeSelection() {
            parent.getUiState().getGameRules().getRule(CivilizationScenarioGameRules.SCENARIO_ID)
                    .set(CivilizationScenarioGameRules.selectionId(scenarios.get(selectedIndex)), null);
            parent.getUiState().onChanged();
        }

        private void refreshSelectionLabel() {
            if (selectedButton != null) {
                selectedButton.setMessage(Component.literal(scenarios.get(selectedIndex).name()));
            }
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            renderBackground(graphics, mouseX, mouseY, partialTick);
            graphics.drawCenteredString(font, title, width / 2, height / 2 - 72, 0xFFFFFF);
            graphics.drawCenteredString(font,
                    Component.literal(scenarios.get(selectedIndex).era())
                            .withStyle(ChatFormatting.GRAY), width / 2, height / 2 - 59, 0xAAAAAA);
            super.render(graphics, mouseX, mouseY, partialTick);
        }
    }
}