package dev.autociv;

import com.mojang.logging.LogUtils;
import dev.autociv.command.CivCommand;
import dev.autociv.command.TestCommand;
import dev.autociv.simulation.world.WorldSimulationManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
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
    private static volatile boolean commandsRegistered = false;

    static {
        // Runs as soon as the JVM loads this class at all. If you NEVER see
        // this line in the console, the class is not on the run classpath
        // (i.e. you are launching an old build or a stale jar from mods/).
        System.out.println("[autociv] CLASS LOADED by JVM (static initializer)");
    }

    public AutonomousCivilizations(IEventBus modEventBus, ModContainer modContainer) {
        constructorCalled = true;
        System.out.println("[autociv] CONSTRUCTOR CALLED. modEventBus=" + modEventBus
                + ", modContainer=" + modContainer
                + ", modId=" + (modContainer == null ? "?" : modContainer.getModId()));

        // Classpath probe: pure Java class, no MC imports. If this loads,
        // our package IS on the runtime classpath.
        try {
            Class.forName("dev.autociv.BootstrapProbe");
            System.out.println("[autociv] probe result: " + BootstrapProbe.ping());
        } catch (Throwable t) {
            System.out.println("[autociv] PROBE FAILED - package not on classpath?! " + t);
        }

        // Game bus listener (commands / server lifecycle are GAME bus events in NeoForge).
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        System.out.println("[autociv] listeners added to NeoForge.EVENT_BUS (game bus)");

        // Belt & suspenders: ALSO register on the MOD bus. In dev environments
        // RegisterCommandsEvent can be dispatched through the mod bus too; a
        // duplicate registration is harmless because we guard with commandsRegistered.
        modEventBus.addListener(this::onRegisterCommands);
        System.out.println("[autociv] listener also added to mod event bus");

        LOGGER.info("[autociv] Autonomous Civilizations stage 1 initialized");
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        if (commandsRegistered) {
            System.out.println("[autociv] RegisterCommandsEvent fired again - skipped (already registered)");
            return;
        }
        System.out.println("[autociv] RegisterCommandsEvent fired, registering commands...");
        try {
            // Test command first: if /autocivtest works but /civ doesn't,
            // the problem is inside CivCommand, not in event wiring.
            TestCommand.register(event.getDispatcher());
            CivCommand.register(event.getDispatcher());
            commandsRegistered = true;
            System.out.println("[autociv] /civ command registered OK");
            LOGGER.info("[autociv] /civ command registered");
        } catch (Throwable t) {
            System.out.println("[autociv] COMMAND REGISTRATION FAILED: " + t);
            t.printStackTrace();
            LOGGER.error("[autociv] command registration failed", t);
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        WorldSimulationManager.attach(server);
        System.out.println("[autociv] server started, simulation attached");
    }

    private void onServerStopping(ServerStoppingEvent event) {
        // SavedData autosaves on world save; explicit detach prevents stale refs.
        WorldSimulationManager.detach();
    }

    /** Status message for diagnostics. */
    public static Component statusComponent() {
        return Component.literal("=== AutoCiv diagnostics ===\n")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Mod constructor called: " + constructorCalled + "\n")
                        .withStyle(constructorCalled ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal("Commands registered: " + commandsRegistered + "\n")
                        .withStyle(commandsRegistered ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal("Try /autocivtest to verify, then /civ list")
                        .withStyle(ChatFormatting.AQUA));
    }
}
