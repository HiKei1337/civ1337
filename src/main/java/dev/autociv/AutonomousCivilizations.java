package dev.autociv;

import com.mojang.logging.LogUtils;
import dev.autociv.command.CivCommand;
import dev.autociv.command.TestCommand;
import dev.autociv.debug.CommandDiagnostician;
import dev.autociv.debug.CivDebugPayload;
import dev.autociv.debug.TownHallScreenPayload;
import dev.autociv.debug.CitizenAiStatus;
import dev.autociv.scenario.CivilizationScenarioGameRules;
import dev.autociv.scenario.SettlementChunkGenerator;
import dev.autociv.scenario.ScenarioWorldBootstrap;
import dev.autociv.simulation.world.WorldSimulationManager;
import dev.autociv.simulation.world.ResidentAi;
import dev.autociv.simulation.world.TownHallInteraction;
import dev.autociv.simulation.world.VillageCivilizationImporter;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

/**
 * Mod entrypoint. Kept intentionally thin: wiring only, no game logic.
 */
@Mod(AutonomousCivilizations.MODID)
public final class AutonomousCivilizations {

    public static final String MODID = "autociv";
    private static final Logger LOGGER = LogUtils.getLogger();

    // Diagnostic flags (printed to chat via /autocivtest status if needed).
    private static volatile boolean constructorCalled = false;
    private static volatile int commandsRegistrationEvents = 0;

    static {
        LOGGER.info("[autociv-diag] Mod entrypoint class loaded");
    }

    public AutonomousCivilizations(IEventBus modEventBus, ModContainer modContainer) {
        CivilizationScenarioGameRules.registered();
        modEventBus.addListener(CivDebugPayload::register);
        modEventBus.addListener(TownHallScreenPayload::register);
        constructorCalled = true;
        LOGGER.info("[autociv-diag] Mod constructor called; modId={}",
                modContainer == null ? "?" : modContainer.getModId());

        // Classpath probe: pure Java class, no MC imports. If this loads,
        // our package IS on the runtime classpath.
        try {
            Class.forName("dev.autociv.BootstrapProbe");
            LOGGER.info("[autociv-diag] Runtime classpath probe: {}", BootstrapProbe.ping());
        } catch (Throwable t) {
            LOGGER.error("[autociv-diag] Runtime classpath probe failed", t);
        }

        // RegisterCommandsEvent and server lifecycle events are game-bus events.
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(TownHallInteraction::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(SettlementChunkGenerator::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(ResidentAi::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(VillageCivilizationImporter::onEntityJoin);
        if (FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
            try {
                Class<?> clientSetup = Class.forName("dev.autociv.client.ScenarioPresetEditors");
                clientSetup.getMethod("register", IEventBus.class).invoke(null, modEventBus);
                Class<?> debugClient = Class.forName("dev.autociv.client.CivDebugClient");
                debugClient.getMethod("register", IEventBus.class).invoke(null, modEventBus);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Could not register civilization scenario editor", exception);
            }
        }
        LOGGER.info("[autociv-diag] Registered listeners on NeoForge game event bus");

        LOGGER.info("[autociv] Autonomous Civilizations stage 1 initialized");
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        int eventNumber = ++commandsRegistrationEvents;
        LOGGER.info("[autociv-diag] RegisterCommandsEvent #{} fired; dispatcher={}",
                eventNumber, System.identityHashCode(event.getDispatcher()));
        CommandDiagnostician.dumpDispatcher("BEFORE registration", event.getDispatcher());
        try {
            TestCommand.register(event.getDispatcher());
            CivCommand.register(event.getDispatcher());
            boolean civPresent = event.getDispatcher().getRoot().getChildren().stream()
                    .anyMatch(node -> node.getName().equals("civ"));
            if (civPresent) {
                LOGGER.info("[autociv-diag] /civ successfully registered on dispatcher={}",
                        System.identityHashCode(event.getDispatcher()));
            } else {
                LOGGER.error("[autociv-diag] Registration returned, but /civ is absent from dispatcher={}",
                        System.identityHashCode(event.getDispatcher()));
            }
        } catch (Throwable t) {
            LOGGER.error("[autociv-diag] Command registration failed on dispatcher={}",
                    System.identityHashCode(event.getDispatcher()), t);
        }
        CommandDiagnostician.dumpDispatcher("AFTER registration", event.getDispatcher());
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        var dispatcher = server == null ? null : server.getCommands().getDispatcher();
        LOGGER.info("[autociv-diag] Player '{}' logged in; registration events={}, server dispatcher={}",
                player.getGameProfile().getName(), commandsRegistrationEvents,
                dispatcher == null ? "null" : System.identityHashCode(dispatcher));
        CommandDiagnostician.dumpDispatcher("PLAYER LOGIN server dispatcher", dispatcher);
        CommandDiagnostician.dumpCivTree(dispatcher, player.createCommandSourceStack());
        if (dispatcher == null || dispatcher.getRoot().getChildren().stream()
                .noneMatch(node -> node.getName().equals("civ"))) {
            LOGGER.error("[autociv-diag] /civ is missing from the live server dispatcher; the client cannot use it");
        } else {
            LOGGER.info("[autociv-diag] /civ exists server-side. If the client still reports Unknown command, "
                    + "inspect client command-tree sync or confirm the client loaded this same mod build.");
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        WorldSimulationManager.attach(server);
        WorldSimulationManager manager = WorldSimulationManager.get(server);
        if (ScenarioWorldBootstrap.initialize(server, manager.simulation())) {
            manager.flushChange();
        }
        SettlementChunkGenerator.generateAlreadyLoadedCapitals(server);
        System.out.println("[autociv] server started, simulation attached");

        // ---- Full command-tree diagnosis against the LIVE dispatcher ----
        try {
            var live = server.getCommands();
            var dispatcher = live == null ? null : live.getDispatcher();
            CommandDiagnostician.dumpDispatcher("LIVE (server.getCommands)", dispatcher);

            int opLevelOk = -1;
            var player = server.getPlayerList().getPlayers().isEmpty()
                    ? null : server.getPlayerList().getPlayers().get(0);
            if (player != null && dispatcher != null) {
                CommandSourceStack src = player.createCommandSourceStack();
                opLevelOk = src.hasPermission(1) ? 1 : 0;
                System.out.println("[autociv-diag] player '" + player.getName().getString()
                    + "' passes hasPermission(1)=" + (opLevelOk == 1));
                CommandDiagnostician.dumpCivTree(dispatcher, src);
            } else {
                System.out.println("[autociv-diag] no player connected yet at ServerStarted - skipping requires() probe");
            }
            boolean civInLive = false;
            if (dispatcher != null) {
                civInLive = dispatcher.getRoot().getChildren().stream()
                        .anyMatch(n -> n.getName().equals("civ"));
            }
            CommandDiagnostician.verdict(commandsRegistrationEvents > 0, civInLive, opLevelOk);
        } catch (Throwable t) {
            System.out.println("[autociv-diag] diagnosis failed: " + t);
            t.printStackTrace();
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        // SavedData autosaves on world save; explicit detach prevents stale refs.
        WorldSimulationManager.detach();
    }

    private void onServerTick(ServerTickEvent.Post event) {
        WorldSimulationManager.onServerTick(event.getServer());
    }

    private void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }
        var persistentData = ((net.neoforged.neoforge.common.extensions.IEntityExtension) villager)
                .getPersistentData();
        if (!persistentData.hasUUID("autocivCitizen")) {
            return;
        }
        MinecraftServer server = villager.getServer();
        if (server == null) {
            return;
        }
        WorldSimulationManager manager = WorldSimulationManager.get(server);
        var citizenId = persistentData.getUUID("autocivCitizen");
        CitizenAiStatus.forget(citizenId);
        manager.simulation().citizen(citizenId)
                .ifPresent(manager.simulation()::removeCitizen);
        manager.flushChange();
    }


    /** Status message for diagnostics. */
    public static Component statusComponent() {
        return Component.literal("=== AutoCiv diagnostics ===\n")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Mod constructor called: " + constructorCalled + "\n")
                        .withStyle(constructorCalled ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal("RegisterCommandsEvent count: " + commandsRegistrationEvents + "\n")
                    .withStyle(commandsRegistrationEvents > 0 ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal("Try /autocivtest to verify, then /civ list")
                        .withStyle(ChatFormatting.AQUA));
    }
}
