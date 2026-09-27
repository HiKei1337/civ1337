package dev.autociv.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.world.WorldSimulation;
import dev.autociv.simulation.world.WorldSimulationManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * /civ command tree (Stage 1 debug surface):
 * <pre>
 * /civ list
 * /civ info &lt;name-or-id&gt;
 * /civ create &lt;name&gt; [color]            - create civilization
 * /civ city create &lt;civ&gt; &lt;name&gt;         - found settlement at player position
 * /civ city addcitizen &lt;civ&gt; &lt;city&gt; &lt;name&gt; &lt;age&gt; [profession]
 * /civ resource add &lt;civ&gt; &lt;city&gt; &lt;resource&gt; &lt;amount&gt;
 * /civ debug                             - aggregate world dump
 * </pre>
 * Requires OP level 2 for mutations, level 1 for reads.
 */
public final class CivCommand {

    private static final DynamicCommandExceptionType NOT_FOUND =
            new DynamicCommandExceptionType(o -> Component.literal("Civilization not found: " + o));

    /**
     * Registers the command tree. Also attaches a fallback to the server's live
     * dispatcher (singleplayer client-server integration): if the world was
     * created before this mod was installed, RegisterCommandsEvent may not fire
     * for it until restart - this guarantees /civ exists immediately.
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        buildAndRegister(dispatcher);
        try {
            net.minecraft.server.MinecraftServer server =
                    net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null && server.getCommands() != null) {
                CommandDispatcher<CommandSourceStack> live = server.getCommands().getDispatcher();
                if (live != dispatcher) {
                    buildAndRegister(live);
                }
            }
        } catch (Throwable t) {
            // Server not up yet (normal dedicated start path) - event registration covers it.
        }
    }

    private static void buildAndRegister(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("civ")
                .requires(s -> s.hasPermission(1))
                .then(Commands.literal("list").executes(CivCommand::list))
                .then(Commands.literal("info")
                        .then(Commands.argument("civ", StringArgumentType.word())
                                .executes(CivCommand::info)))
                .then(Commands.literal("create")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> create(ctx, 0xAAAAAA))
                                .then(Commands.argument("color", IntegerArgumentType.integer(0, 0xFFFFFF))
                                        .executes(ctx -> create(ctx, IntegerArgumentType.getInteger(ctx, "color"))))))
                .then(Commands.literal("city")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("create")
                                .then(Commands.argument("civ", StringArgumentType.word())
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(CivCommand::createCity))))
                        .then(Commands.literal("addcitizen")
                                .then(Commands.argument("civ", StringArgumentType.word())
                                        .then(Commands.argument("city", StringArgumentType.word())
                                                .then(Commands.argument("name", StringArgumentType.word())
                                                        .then(Commands.argument("age", IntegerArgumentType.integer(0, 79))
                                                                .executes(ctx -> addCitizen(ctx, "unassigned"))
                                                                .then(Commands.argument("profession", StringArgumentType.word())
                                                                        .executes(ctx -> addCitizen(ctx, StringArgumentType.getString(ctx, "profession")))))))))
                        .then(Commands.literal("resource")
                                .then(Commands.argument("civ", StringArgumentType.word())
                                        .then(Commands.argument("city", StringArgumentType.word())
                                                .then(Commands.argument("resource", StringArgumentType.word())
                                                        .then(Commands.argument("amount", IntegerArgumentType.integer(-1_000_000, 1_000_000))
                                                                .executes(CivCommand::addResource)))))))
                .then(Commands.literal("debug").executes(CivCommand::debug)));
    }

    // ------------------------------------------------------------------ list

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation sim = manager(ctx).simulation();
        if (sim.civilizations().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No civilizations yet. Use /civ create <name>")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("=== Civilizations (" + sim.civilizations().size() + ") ===")
                .withStyle(ChatFormatting.GOLD), false);
        for (Civilization c : sim.civilizations()) {
            int pop = sim.settlementsOf(c.id()).stream().mapToInt(Settlement::population).sum();
            ctx.getSource().sendSuccess(() -> Component.literal(String.format("  #%s '%s' cities=%d pop=%d treasury=%.0f id=%s",
                            colorHex(c.color()), c.name(), c.settlementIds().size(), pop, c.treasury(), shortId(c.id())))
                            .withStyle(ChatFormatting.WHITE), false);
        }
        return sim.civilizations().size();
    }

    // ------------------------------------------------------------------ info

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation sim = manager(ctx).simulation();
        Civilization civ = resolveCiv(sim, StringArgumentType.getString(ctx, "civ"));
        ctx.getSource().sendSuccess(() -> Component.literal("=== " + civ.name() + " ===").withStyle(ChatFormatting.GOLD), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("id=%s color=#%s capital=%s",
                civ.id(), colorHex(civ.color()), capitalName(sim, civ))), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("treasury=%.0f culture=%.0f military=%.0f",
                civ.treasury(), civ.culture(), civ.militaryStrength())), false);
        for (UUID sid : civ.settlementIds()) {
            sim.settlement(sid).ifPresent(s -> ctx.getSource().sendSuccess(() ->
                    Component.literal(String.format("  city '%s' at (%d,%d,%d) pop=%d/%d happiness=%.0f%%",
                            s.name(), s.x(), s.y(), s.z(), s.population(), s.housingCapacity(), s.happiness() * 100)), false));
        }
        for (String h : civ.history()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  [hist] " + h).withStyle(ChatFormatting.DARK_GRAY), false);
        }
        return 1;
    }

    // ---------------------------------------------------------------- create

    private static int create(CommandContext<CommandSourceStack> ctx, int defaultColor)
            throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "name");
        // Brigadier has no getArguments() map API; probe parsed arguments instead.
        // The optional "color" argument is only present when the user typed it.
        int color = defaultColor;
        try {
            color = IntegerArgumentType.getInteger(ctx, "color");
        } catch (IllegalArgumentException ignored) {
            // optional argument not provided - keep default
        }
        WorldSimulationManager mgr = manager(ctx);
        Civilization civ = mgr.simulation().createCivilization(name, color);
        mgr.flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Civilization '" + civ.name()
                + "' created with id " + civ.id()).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int createCity(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation sim = manager(ctx).simulation();
        Civilization civ = resolveCiv(sim, StringArgumentType.getString(ctx, "civ"));
        String cityName = StringArgumentType.getString(ctx, "name");
        var pos = ctx.getSource().getPosition();
        Settlement s = sim.createSettlement(civ.id(), cityName,
                (int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("City '" + s.name() + "' founded for "
                + civ.name() + " at " + s.x() + "," + s.y() + "," + s.z() + " (id " + s.id() + ")")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int addCitizen(CommandContext<CommandSourceStack> ctx, String defaultProfession)
            throws CommandSyntaxException {
        WorldSimulation sim = manager(ctx).simulation();
        Civilization civ = resolveCiv(sim, StringArgumentType.getString(ctx, "civ"));
        Settlement city = resolveCity(sim, civ, StringArgumentType.getString(ctx, "city"));
        String name = StringArgumentType.getString(ctx, "name");
        int age = IntegerArgumentType.getInteger(ctx, "age");
        String profStr;
        try {
            profStr = StringArgumentType.getString(ctx, "profession");
        } catch (IllegalArgumentException ignored) {
            // optional argument not provided - use default
            profStr = defaultProfession;
        }
        Citizen.Profession profession;
        try {
            profession = Citizen.Profession.valueOf(profStr.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("Unknown profession: " + profStr));
            return 0;
        }
        if (!city.hasRoomForCitizen()) {
            ctx.getSource().sendFailure(Component.literal("No housing in " + city.name()
                    + " (" + city.population() + "/" + city.housingCapacity() + ")"));
            return 0;
        }
        Citizen c = sim.addCitizen(city, name, age, profession);
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Citizen '" + c.name() + "' joined "
                + city.name() + " as " + c.profession().name().toLowerCase(java.util.Locale.ROOT))
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int addResource(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation sim = manager(ctx).simulation();
        Civilization civ = resolveCiv(sim, StringArgumentType.getString(ctx, "civ"));
        Settlement city = resolveCity(sim, civ, StringArgumentType.getString(ctx, "city"));
        ResourceType type = ResourceType.byId(StringArgumentType.getString(ctx, "resource"))
                .orElse(null);
        if (type == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown resource. Known: "
                    + String.join(", ", ResourceType.all().keySet())));
            return 0;
        }
        double amount = IntegerArgumentType.getInteger(ctx, "amount");
        double applied = city.stockpile().add(type, amount);
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("%s %+.0f (%.0f -> %.0f) in %s",
                type.id(), amount, city.stockpile().get(type) - applied, city.stockpile().get(type), city.name()))
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    // ----------------------------------------------------------------- debug

    private static int debug(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation sim = manager(ctx).simulation();
        String summary = sim.debugSummary();
        ctx.getSource().sendSuccess(() -> Component.literal(summary).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    // --------------------------------------------------------------- helpers

    private static WorldSimulationManager manager(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return WorldSimulationManager.get(ctx.getSource().getServer());
    }

    private static Civilization resolveCiv(WorldSimulation sim, String needle) throws CommandSyntaxException {
        Optional<Civilization> civ = sim.findCivilization(needle);
        if (civ.isPresent()) {
            return civ.get();
        }
        throw NOT_FOUND.create(needle);
    }

    private static Settlement resolveCity(WorldSimulation sim, Civilization civ, String needle)
            throws CommandSyntaxException {
        List<Settlement> cities = sim.settlementsOf(civ.id());
        for (Settlement s : cities) {
            if (s.name().equalsIgnoreCase(needle) || shortId(s.id()).startsWith(needle.toLowerCase(java.util.Locale.ROOT))) {
                return s;
            }
        }
        throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                Component.literal("City not found in " + civ.name() + ": " + needle)).create();
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String colorHex(int color) {
        return String.format("%06X", color & 0xFFFFFF);
    }

    private static String capitalName(WorldSimulation sim, Civilization civ) {
        if (civ.capitalSettlementId() == null) {
            return "-";
        }
        return sim.settlement(civ.capitalSettlementId()).map(Settlement::name).orElse("?");
    }

    private CivCommand() {
    }
}
