package dev.autociv.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.autociv.simulation.model.Citizen;
import dev.autociv.simulation.model.Civilization;
import dev.autociv.simulation.model.BuildingPlan;
import dev.autociv.simulation.model.BuildingType;
import dev.autociv.simulation.model.DiplomaticAgreement;
import dev.autociv.simulation.model.Region;
import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.economy.Market;
import dev.autociv.simulation.economy.PriceSystem;
import dev.autociv.simulation.economy.RoadNetwork;
import dev.autociv.simulation.economy.RailNetwork;
import dev.autociv.simulation.world.WorldSimulation;
import dev.autociv.simulation.world.WorldSimulationManager;
import dev.autociv.simulation.world.SimulationSpeed;
import dev.autociv.debug.CivDebugPayload;
import dev.autociv.debug.TownHallScreenPayload;
import dev.autociv.simulation.ai.CityPlanner;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * /civ command tree for civilization data, simulation and world travel:
 * <pre>
 * /civ list
 * /civ info &lt;name-or-id&gt;
 * /civ create &lt;name&gt; [color]            - create civilization
 * /civ city create &lt;civ&gt; &lt;name&gt;         - found settlement at player position
 * /civ city addcitizen &lt;civ&gt; &lt;city&gt; &lt;name&gt; &lt;age&gt; [profession]
 * /civ city resource &lt;civ&gt; &lt;city&gt; &lt;resource&gt; &lt;amount&gt;
 * /civ simulate [realistic|fast|debug]
 * /civ economy <civ> <city>
 * /civ trade
 * /civ debug                             - aggregate world dump
 * </pre>
 * Reads are public; world mutations and simulation-speed changes require OP level 2.
 */
public final class CivCommand {

    private static final DynamicCommandExceptionType NOT_FOUND =
            new DynamicCommandExceptionType(o -> Component.literal("Civilization not found: " + o));

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        buildAndRegister(dispatcher);
    }

    private static void buildAndRegister(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("civ")
            .executes(CivCommand::list)
                .then(Commands.literal("list").executes(CivCommand::list))
                .then(Commands.literal("history")
                        .executes(ctx -> worldHistory(ctx, null))
                        .then(Commands.argument("civ", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        manager(ctx).simulation().civilizations().stream()
                                                .map(Civilization::name).sorted().toList(), builder))
                                .executes(ctx -> worldHistory(ctx, StringArgumentType.getString(ctx, "civ")))))
                .then(Commands.literal("lod").executes(CivCommand::simulationLodStatus))
                .then(Commands.literal("info")
                        .then(Commands.argument("civ", StringArgumentType.word())
                                .executes(CivCommand::info)))
                .then(Commands.literal("create")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> create(ctx, 0xAAAAAA))
                                .then(Commands.argument("color", IntegerArgumentType.integer(0, 0xFFFFFF))
                                        .executes(ctx -> create(ctx, IntegerArgumentType.getInteger(ctx, "color"))))))
                .then(Commands.literal("charter")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(CivCommand::charterCivilization)))
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
                .then(Commands.literal("debug")
                        .executes(CivCommand::debug)
                        .then(Commands.literal("years")
                                .requires(source -> source.hasPermission(2))
                                .executes(CivCommand::debugYearsStatus)
                                .then(Commands.literal("stop").executes(CivCommand::debugYearsStop))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                List.of("1", "5", "10", "25", "50", "100"), builder))
                                        .executes(CivCommand::debugYearsStart)))
                        .then(Commands.argument("civ", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        manager(ctx).simulation().civilizations().stream()
                                                .map(Civilization::name).sorted().toList(), builder))
                                .executes(CivCommand::debugCivilization)
                                .then(Commands.argument("city", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            WorldSimulation simulation = manager(ctx).simulation();
                                            Civilization civilization = simulation.findCivilization(
                                                    StringArgumentType.getString(ctx, "civ")).orElse(null);
                                            return SharedSuggestionProvider.suggest(civilization == null ? List.of()
                                                    : simulation.settlementsOf(civilization.id()).stream()
                                                            .map(Settlement::name).sorted().toList(), builder);
                                        })
                                        .executes(CivCommand::debugCity)))
                        .then(Commands.literal("build")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("civ", StringArgumentType.word())
                                        .then(Commands.argument("city", StringArgumentType.word())
                                                .executes(CivCommand::debugStartConstruction))))
                        .then(Commands.literal("facility")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("civ", StringArgumentType.word())
                                        .then(Commands.argument("city", StringArgumentType.word())
                                                .then(Commands.argument("type", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                                java.util.Arrays.stream(BuildingType.values())
                                                                        .map(type -> type.name().toLowerCase(java.util.Locale.ROOT)),
                                                                builder))
                                                        .executes(CivCommand::debugStartFacility))))))
                .then(Commands.literal("hall")
                        .then(Commands.argument("cityId", StringArgumentType.word())
                                .executes(CivCommand::openTownHall)
                                .then(Commands.literal("donate")
                                        .then(Commands.argument("resource", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                        List.of("food", "wood", "stone", "coal", "iron"), builder))
                                                .executes(CivCommand::townHallDonate)))
                                .then(Commands.literal("bank").executes(CivCommand::townHallBuildBank))))
                .then(Commands.literal("manage")
                        .then(Commands.argument("cityId", StringArgumentType.word())
                                .executes(CivCommand::openTownHall)
                                .then(Commands.literal("claim").executes(CivCommand::claimColony))
                                .then(Commands.literal("priority")
                                        .then(Commands.argument("value", StringArgumentType.word())
                                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                        List.of("food", "construction", "extraction", "trade", "defense"), b))
                                                .executes(CivCommand::setColonyPriority)))
                                .then(Commands.literal("strategy")
                                        .then(Commands.argument("value", StringArgumentType.word())
                                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                        List.of("growth", "reserves", "fortify"), b))
                                                .executes(CivCommand::setColonyStrategy)))
                                .then(Commands.literal("job")
                                        .then(Commands.argument("citizen", StringArgumentType.word())
                                                .then(Commands.argument("profession", StringArgumentType.word())
                                                        .executes(CivCommand::manageCitizenJob))))
                                .then(Commands.literal("build")
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                        java.util.Arrays.stream(BuildingType.values())
                                                                .map(t -> t.name().toLowerCase(java.util.Locale.ROOT)), b))
                                                .executes(CivCommand::manageBuild)))
                                .then(Commands.literal("order")
                                        .then(Commands.argument("index", IntegerArgumentType.integer(1, 128))
                                                .then(Commands.argument("direction", StringArgumentType.word())
                                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(List.of("up", "down"), b))
                                                        .executes(CivCommand::manageOrder))))
                                .then(Commands.literal("cancel").executes(CivCommand::manageCancel))
                                .then(Commands.literal("craft")
                                        .then(Commands.argument("itemId", StringArgumentType.word())
                                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                        craftableBlockIds(ctx.getSource().getLevel()), b))
                                                .then(Commands.argument("batches", IntegerArgumentType.integer(1, 64))
                                                        .executes(CivCommand::manageCraft))))
                                .then(Commands.literal("craftcancel")
                                        .then(Commands.argument("index", IntegerArgumentType.integer(1, 32))
                                                .executes(CivCommand::manageCraftCancel)))
                                .then(Commands.literal("craftorder")
                                        .then(Commands.argument("index", IntegerArgumentType.integer(1, 32))
                                                .then(Commands.argument("direction", StringArgumentType.word())
                                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(List.of("up", "down"), b))
                                                        .executes(CivCommand::manageCraftOrder)))))
                        )
                .then(Commands.literal("admin").requires(s -> s.hasPermission(2))
                        .executes(ctx -> adminHelp(ctx))
                        .then(Commands.literal("step").executes(CivCommand::adminStep))
                        .then(Commands.literal("city")
                                .then(Commands.argument("cityId", StringArgumentType.word())
                                        .then(Commands.literal("rename").then(Commands.argument("name", StringArgumentType.word())
                                                .executes(CivCommand::adminRenameCity)))
                                        .then(Commands.literal("move")
                                                .then(Commands.argument("x", IntegerArgumentType.integer(-30_000_000, 30_000_000))
                                                        .then(Commands.argument("y", IntegerArgumentType.integer(-2048, 4096))
                                                                .then(Commands.argument("z", IntegerArgumentType.integer(-30_000_000, 30_000_000))
                                                                        .executes(CivCommand::adminMoveCity)))))
                                        .then(Commands.literal("remove")
                                                .then(Commands.literal("CONFIRM").executes(CivCommand::adminRemoveCity)))))
                        .then(Commands.literal("citizen")
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("citizenId", StringArgumentType.word())
                                                .then(Commands.literal("CONFIRM").executes(CivCommand::adminRemoveCitizen))))
                                .then(Commands.literal("age")
                                        .then(Commands.argument("citizenId", StringArgumentType.word())
                                                .then(Commands.argument("age", IntegerArgumentType.integer(0, 80))
                                                        .executes(CivCommand::adminCitizenAge))))
                                .then(Commands.literal("job")
                                        .then(Commands.argument("citizenId", StringArgumentType.word())
                                                .then(Commands.argument("profession", StringArgumentType.word())
                                                        .executes(CivCommand::adminCitizenJob)))))
                        .then(Commands.literal("stock")
                                .then(Commands.argument("cityId", StringArgumentType.word())
                                        .then(Commands.argument("resource", StringArgumentType.word())
                                                .then(Commands.argument("amount", IntegerArgumentType.integer(0, 1_000_000))
                                                        .executes(CivCommand::adminStock)))))
                        .then(Commands.literal("owner")
                                .then(Commands.argument("cityId", StringArgumentType.word())
                                        .then(Commands.argument("playerUuid", StringArgumentType.word())
                                                .executes(CivCommand::adminOwner))))
                        .then(Commands.literal("citystats")
                                .then(Commands.argument("cityId", StringArgumentType.word())
                                        .then(Commands.argument("happiness", IntegerArgumentType.integer(0, 100))
                                                .then(Commands.argument("security", IntegerArgumentType.integer(0, 100))
                                                        .executes(CivCommand::adminCityStats)))))
                        .then(Commands.literal("treasury")
                                .then(Commands.argument("civ", StringArgumentType.word())
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(0, 1_000_000_000))
                                                .executes(CivCommand::adminTreasury))))
                        .then(Commands.literal("culture")
                                .then(Commands.argument("civ", StringArgumentType.word())
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(0, 1_000_000_000))
                                                .executes(CivCommand::adminCulture))))
                        .then(Commands.literal("project")
                                .then(Commands.argument("cityId", StringArgumentType.word())
                                        .then(Commands.literal("complete").executes(ctx -> adminProject(ctx, true)))
                                        .then(Commands.literal("cancel").executes(ctx -> adminProject(ctx, false)))))
                        .then(Commands.literal("craft")
                                .then(Commands.argument("cityId", StringArgumentType.word())
                                        .then(Commands.argument("itemId", StringArgumentType.word())
                                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                        craftableBlockIds(ctx.getSource().getLevel()), b))
                                                .then(Commands.argument("batches", IntegerArgumentType.integer(1, 64))
                                                        .executes(CivCommand::adminCraft)))))
                        .then(Commands.literal("craftcancel")
                                .then(Commands.argument("cityId", StringArgumentType.word())
                                        .then(Commands.argument("index", IntegerArgumentType.integer(1, 32))
                                                .executes(CivCommand::adminCraftCancel))))
                        .then(Commands.literal("history")
                                .then(Commands.argument("cityId", StringArgumentType.word())
                                        .executes(CivCommand::adminHistory)))
                        .then(Commands.literal("pause").executes(ctx -> adminSetSpeed(ctx, SimulationSpeed.PAUSED)))
                        .then(Commands.literal("resume").executes(ctx -> adminSetSpeed(ctx, SimulationSpeed.DEBUG))))
                .then(Commands.literal("gui").executes(CivCommand::openDebugGui))
                .then(Commands.literal("job")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("civ", StringArgumentType.word())
                                        .then(Commands.argument("city", StringArgumentType.word())
                                            .then(Commands.argument("citizen", StringArgumentType.word())
                                                    .then(Commands.argument("profession", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                                java.util.Arrays.stream(Citizen.Profession.values())
                                                                        .map(profession -> profession.name()
                                                                                .toLowerCase(java.util.Locale.ROOT)),
                                                                builder))
                                                        .executes(CivCommand::assignJob))))))
                .then(Commands.literal("economy")
                    .then(Commands.argument("civ", StringArgumentType.word())
                        .then(Commands.argument("city", StringArgumentType.word())
                            .executes(CivCommand::economy))))
                .then(Commands.literal("trade").executes(CivCommand::tradeRoutes))
                .then(Commands.literal("diplomacy")
                        .executes(CivCommand::diplomacyStatus)
                        .then(Commands.argument("civ", StringArgumentType.word())
                                .executes(CivCommand::diplomacyForCivilization))
                        .then(Commands.literal("treaty")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("first", StringArgumentType.word())
                                        .then(Commands.argument("second", StringArgumentType.word())
                                                .then(Commands.argument("type", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                                List.of("trade_pact", "non_aggression", "alliance"), builder))
                                                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 3600))
                                                                .executes(CivCommand::diplomacyTreaty))))))
                        .then(Commands.literal("war")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("first", StringArgumentType.word())
                                        .then(Commands.argument("second", StringArgumentType.word())
                                                .executes(CivCommand::diplomacyWar))))
                        .then(Commands.literal("peace")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("first", StringArgumentType.word())
                                        .then(Commands.argument("second", StringArgumentType.word())
                                                .executes(CivCommand::diplomacyPeace)))))
                .then(Commands.literal("territory")
                        .executes(CivCommand::territorySummary)
                        .then(Commands.argument("civ", StringArgumentType.word())
                                .executes(CivCommand::territoryDetails)))
                .then(Commands.literal("network")
                        .then(Commands.argument("civ", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        manager(ctx).simulation().civilizations().stream()
                                                .map(Civilization::name).sorted().toList(), builder))
                                .executes(ctx -> settlementNetwork(ctx, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1, 250))
                                        .executes(ctx -> settlementNetwork(ctx,
                                                IntegerArgumentType.getInteger(ctx, "page"))))))
                .then(Commands.literal("roads")
                        .then(Commands.argument("civ", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        manager(ctx).simulation().civilizations().stream()
                                                .map(Civilization::name).sorted().toList(), builder))
                                .executes(CivCommand::roadNetworkStatus)))
                .then(Commands.literal("rails")
                        .then(Commands.argument("civ", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        manager(ctx).simulation().civilizations().stream()
                                                .map(Civilization::name).sorted().toList(), builder))
                                .executes(CivCommand::railNetworkStatus)))
                .then(Commands.literal("simulate")
                    .executes(CivCommand::simulationStatus)
                    .then(Commands.argument("speed", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                            List.of("realistic", "fast", "debug", "paused"), builder))
                        .requires(source -> source.hasPermission(2))
                        .executes(CivCommand::setSimulationSpeed)))
                    .then(Commands.literal("tp")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("civ", StringArgumentType.word())
                            .executes(CivCommand::teleportToCapital)
                            .then(Commands.argument("city", StringArgumentType.word())
                                .executes(CivCommand::teleportToCity))
                            .then(Commands.literal("outpost")
                                .then(Commands.argument("rival", StringArgumentType.word())
                                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                            manager(ctx).simulation().civilizations().stream()
                                                    .map(Civilization::name).sorted().toList(), builder))
                                    .executes(CivCommand::teleportToOutpost))))));
    }

    private static int simulationStatus(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
            WorldSimulation simulation = manager(ctx).simulation();
            SimulationSpeed speed = simulation.simulationSpeed();
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "Simulation speed: %s (one simulation day every %d server ticks, %.0f seconds); days=%.0f, steps=%d",
                    speed.name().toLowerCase(java.util.Locale.ROOT), speed.intervalTicks(),
                    speed.intervalTicks() / 20.0, simulation.timeDays(), simulation.tickCount())), false);
            return 1;
    }

    private static int setSimulationSpeed(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String requested = StringArgumentType.getString(ctx, "speed");
        SimulationSpeed speed = SimulationSpeed.byId(requested).orElse(null);
        if (speed == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown speed. Use realistic, fast, or debug."));
            return 0;
        }
        manager(ctx).setSimulationSpeed(speed);
        ctx.getSource().sendSuccess(() -> Component.literal("Simulation speed set to "
                + speed.name().toLowerCase(java.util.Locale.ROOT) + "."), true);
        return 1;
    }

    private static int debugYearsStart(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int years = IntegerArgumentType.getInteger(ctx, "count");
        WorldSimulationManager manager = manager(ctx);
        if (!manager.startDebugYearSkip(years)) {
            ctx.getSource().sendFailure(Component.literal("Уже выполняется пропуск времени. Статус: /civ debug years, остановка: /civ debug years stop."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Запущен пропуск " + years
                + " лет симуляции (" + (years * 360) + " дней). Симуляция ускоряется порциями, мир продолжает тикать. "
                + "Статус: /civ debug years; остановить: /civ debug years stop.")
                .withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    private static int debugYearsStatus(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulationManager manager = manager(ctx);
        if (manager.debugDaysRemaining() <= 0) {
            ctx.getSource().sendSuccess(() -> Component.literal("Пропуск лет не выполняется. Текущий год: "
                    + manager.simulation().year() + ". Запуск: /civ debug years <1-100>.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        int totalDays = manager.debugYearsRequested() * 360;
        int completedDays = totalDays - manager.debugDaysRemaining();
        int percent = completedDays * 100 / totalDays;
        ctx.getSource().sendSuccess(() -> Component.literal("Пропуск времени: " + percent + "% | прошло "
                + completedDays + "/" + totalDays + " дней | текущий год " + manager.simulation().year()
                + " | остановить: /civ debug years stop").withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int debugYearsStop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulationManager manager = manager(ctx);
        if (manager.debugDaysRemaining() <= 0) {
            ctx.getSource().sendFailure(Component.literal("Сейчас пропуск времени не выполняется."));
            return 0;
        }
        manager.stopDebugYearSkip();
        ctx.getSource().sendSuccess(() -> Component.literal("Пропуск времени остановлен. Текущий год: "
                + manager.simulation().year() + ".").withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    private static int openTownHall(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null) return 0;
        TownHallScreenPayload.openFor(player, manager(ctx).simulation(), city);
        return 1;
    }

    private static int townHallDonate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        WorldSimulationManager manager = manager(ctx);
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null) return 0;
        ResourceType resource = ResourceType.byId(StringArgumentType.getString(ctx, "resource")).orElse(null);
        if (resource == null || !List.of(ResourceType.FOOD, ResourceType.WOOD, ResourceType.STONE,
                ResourceType.COAL, ResourceType.IRON).contains(resource)) {
            ctx.getSource().sendFailure(Component.literal("Этот ресурс нельзя передать через ратушу."));
            return 0;
        }
        dev.autociv.simulation.world.TownHallInteraction.donateFromInventory(player, city, resource);
        TownHallScreenPayload.openFor(player, manager.simulation(), city);
        return 1;
    }

    private static int townHallBuildBank(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        WorldSimulationManager manager = manager(ctx);
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null || !colonyAccess(ctx, city, true)) return 0;
        net.minecraft.core.BlockPos bell = new net.minecraft.core.BlockPos(city.x(), city.y() + 1, city.z());
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)
                || !level.hasChunkAt(bell)
                || player.distanceToSqr(bell.getX() + 0.5, bell.getY(), bell.getZ() + 0.5) > 100.0) {
            ctx.getSource().sendFailure(Component.literal("Подойди к ратуше, чтобы заказать банк."));
            return 0;
        }
        boolean alreadyPlanned = (city.activeBuildingPlan() != null
                && city.activeBuildingPlan().type() == BuildingType.BANK)
                || city.pendingPhysicalBuildings().stream().anyMatch(plan -> plan.type() == BuildingType.BANK);
        if (city.buildingCount(BuildingType.BANK) > 0 || alreadyPlanned) {
            ctx.getSource().sendFailure(Component.literal("В этом городе банк уже есть или строится."));
            TownHallScreenPayload.openFor(player, manager.simulation(), city);
            return 0;
        }
        var cost = Map.of(ResourceType.WOOD, (double) BuildingType.BANK.woodCost(),
                ResourceType.STONE, (double) BuildingType.BANK.stoneCost());
        if (!city.stockpile().tryRemove(cost)) {
            ctx.getSource().sendFailure(Component.literal("В городском запасе не хватает материалов для проекта банка."));
            TownHallScreenPayload.openFor(player, manager.simulation(), city);
            return 0;
        }
        city.stockpile().recordDelta(ResourceType.WOOD, -BuildingType.BANK.woodCost());
        city.stockpile().recordDelta(ResourceType.STONE, -BuildingType.BANK.stoneCost());
        city.setActiveBuildingPlan(BuildingPlan.start(BuildingType.BANK));
        manager.flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Проект банка запущен. Для физической постройки жители"
                + " должны доставить брёвна и камень в сундук ратуши."), false);
        TownHallScreenPayload.openFor(player, manager.simulation(), city);
        return 1;
    }

    private static boolean colonyAccess(CommandContext<CommandSourceStack> ctx, Settlement city,
                                        boolean requireOwner) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        net.minecraft.core.BlockPos center = new net.minecraft.core.BlockPos(city.x(), city.y(), city.z());
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level) || !level.hasChunkAt(center)
                || player.distanceToSqr(center.getX() + 0.5, center.getY(), center.getZ() + 0.5) > 144.0) {
            ctx.getSource().sendFailure(Component.literal("Подойди к ратуше города (не дальше 12 блоков)."));
            return false;
        }
        if (requireOwner && !player.getUUID().equals(city.ownerPlayerId())) {
            ctx.getSource().sendFailure(Component.literal(city.ownerPlayerId() == null
                    ? "Сначала закрепи колонию через /civ manage " + city.id() + " claim."
                    : "Эта колония принадлежит другому игроку."));
            return false;
        }
        return true;
    }

    private static int claimColony(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null || !colonyAccess(ctx, city, false)) return 0;
        if (city.ownerPlayerId() != null && !city.ownerPlayerId().equals(player.getUUID())) {
            ctx.getSource().sendFailure(Component.literal("У города уже есть владелец."));
            return 0;
        }
        double trust = dev.autociv.simulation.world.TownHallInteraction.favor(player, city);
        if (trust < 25 && !player.hasPermissions(2)) {
            ctx.getSource().sendFailure(Component.literal("Чтобы стать управляющим, нужно отношение не ниже 25/100. Сейчас: "
                    + Math.round(trust) + ". Передавай ресурсы через ратушу."));
            return 0;
        }
        city.claim(player.getUUID());
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Ты теперь управляешь городом «" + city.name() + "»."), false);
        TownHallScreenPayload.openFor(player, manager(ctx).simulation(), city);
        return 1;
    }

    private static int setColonyPriority(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return updateColonySetting(ctx, true);
    }

    private static int setColonyStrategy(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return updateColonySetting(ctx, false);
    }

    private static int updateColonySetting(CommandContext<CommandSourceStack> ctx, boolean priority) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null || !colonyAccess(ctx, city, true)) return 0;
        String raw = StringArgumentType.getString(ctx, "value");
        try {
            if (priority) city.setPriority(dev.autociv.simulation.model.ColonyPriority.parse(raw));
            else city.setStrategy(dev.autociv.simulation.model.DevelopmentStrategy.parse(raw));
        } catch (IllegalArgumentException invalid) {
            ctx.getSource().sendFailure(Component.literal("Неизвестный режим."));
            return 0;
        }
        manager(ctx).flushChange();
        TownHallScreenPayload.openFor(player, manager(ctx).simulation(), city);
        ctx.getSource().sendSuccess(() -> Component.literal("Настройки города обновлены."), false);
        return 1;
    }

    private static int manageCitizenJob(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        WorldSimulationManager manager = manager(ctx);
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null || !colonyAccess(ctx, city, true)) return 0;
        String id = StringArgumentType.getString(ctx, "citizen");
        Citizen citizen = city.citizenIds().stream().map(manager.simulation()::citizen).flatMap(Optional::stream)
                .filter(c -> c.id().toString().startsWith(id) || c.name().equalsIgnoreCase(id)).findFirst().orElse(null);
        if (citizen == null) {
            ctx.getSource().sendFailure(Component.literal("Житель этого города не найден."));
            return 0;
        }
        try {
            citizen.setProfession(Citizen.Profession.valueOf(StringArgumentType.getString(ctx, "profession")
                    .toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException invalid) {
            ctx.getSource().sendFailure(Component.literal("Неизвестная профессия."));
            return 0;
        }
        manager.flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal(citizen.name() + " назначен на работу: "
                + citizen.profession().name().toLowerCase(java.util.Locale.ROOT) + "."), false);
        TownHallScreenPayload.openFor(player, manager.simulation(), city);
        return 1;
    }

    private static int manageBuild(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        WorldSimulationManager manager = manager(ctx);
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null || !colonyAccess(ctx, city, true)) return 0;
        BuildingType type;
        try { type = BuildingType.valueOf(StringArgumentType.getString(ctx, "type").toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException invalid) {
            ctx.getSource().sendFailure(Component.literal("Неизвестный проект.")); return 0;
        }
        long queuedCount = city.buildingQueue().stream().filter(existing -> existing == type).count();
        if (city.buildingCount(type) + queuedCount + (city.activeBuildingPlan() != null
                && city.activeBuildingPlan().type() == type ? 1 : 0) >= type.cityLimit()) {
            ctx.getSource().sendFailure(Component.literal("Достигнут лимит этого здания.")); return 0;
        }
        Map<ResourceType, Double> cost = Map.of(ResourceType.WOOD, (double) type.woodCost(),
                ResourceType.STONE, (double) type.stoneCost());
        if (!city.stockpile().tryRemove(cost)) {
            ctx.getSource().sendFailure(Component.literal("Не хватает запасов: нужно " + type.woodCost()
                    + " дерева и " + type.stoneCost() + " камня.")); return 0;
        }
        city.stockpile().recordDelta(ResourceType.WOOD, -type.woodCost());
        city.stockpile().recordDelta(ResourceType.STONE, -type.stoneCost());
        city.queueBuilding(type, city.buildingQueue().size());
        manager.flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Проект добавлен в очередь №" + city.buildingQueue().size()
                + ": " + type.name().toLowerCase(java.util.Locale.ROOT)), false);
        TownHallScreenPayload.openFor(player, manager.simulation(), city);
        return 1;
    }

    private static int manageCancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        WorldSimulationManager manager = manager(ctx);
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null || !colonyAccess(ctx, city, true)) return 0;
        if (!city.buildingQueue().isEmpty()) {
            int index = city.buildingQueue().size() - 1;
            BuildingType queued = city.buildingQueue().get(index);
            city.removeQueuedBuilding(index);
            city.stockpile().add(ResourceType.WOOD, queued.woodCost());
            city.stockpile().add(ResourceType.STONE, queued.stoneCost());
            manager.flushChange();
            TownHallScreenPayload.openFor(player, manager.simulation(), city);
            ctx.getSource().sendSuccess(() -> Component.literal("Последний проект удалён из очереди, резервы возвращены."), false);
            return 1;
        }
        BuildingPlan plan = city.activeBuildingPlan();
        if (plan == null || plan.progressDays() > 0) {
            ctx.getSource().sendFailure(Component.literal("Отмена возможна только до начала активного этапа проекта."));
            return 0;
        }
        city.stockpile().add(ResourceType.WOOD, plan.type().woodCost());
        city.stockpile().add(ResourceType.STONE, plan.type().stoneCost());
        city.setActiveBuildingPlan(null);
        manager.flushChange();
        TownHallScreenPayload.openFor(player, manager.simulation(), city);
        ctx.getSource().sendSuccess(() -> Component.literal("Проект отменён, материалы возвращены в городской запас."), false);
        return 1;
    }

    private static int manageOrder(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        WorldSimulationManager manager = manager(ctx);
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null || !colonyAccess(ctx, city, true)) return 0;
        int index = IntegerArgumentType.getInteger(ctx, "index") - 1;
        String direction = StringArgumentType.getString(ctx, "direction");
        int delta = direction.equalsIgnoreCase("up") ? -1 : direction.equalsIgnoreCase("down") ? 1 : 0;
        if (delta == 0 || !city.moveQueuedBuilding(index, delta)) {
            ctx.getSource().sendFailure(Component.literal("Нельзя переместить проект в эту позицию.")); return 0;
        }
        manager.flushChange();
        TownHallScreenPayload.openFor(player, manager.simulation(), city);
        ctx.getSource().sendSuccess(() -> Component.literal("Порядок очереди обновлён."), false);
        return 1;
    }

    private static int manageCraft(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        WorldSimulationManager manager = manager(ctx);
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null || !colonyAccess(ctx, city, true)) return 0;
        return enqueueCraft(ctx, manager, city, player);
    }

    private static int adminCraft(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulationManager manager = manager(ctx);
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null) return 0;
        return enqueueCraft(ctx, manager, city, null);
    }

    private static int enqueueCraft(CommandContext<CommandSourceStack> ctx, WorldSimulationManager manager,
                                    Settlement city, ServerPlayer player) {
        if (city.craftingQueue().size() >= 32) {
            ctx.getSource().sendFailure(Component.literal("Очередь мастерской заполнена (максимум 32 заказа)."));
            return 0;
        }
        int batches = IntegerArgumentType.getInteger(ctx, "batches");
        Item item = resolveCraftableBlock(ctx, StringArgumentType.getString(ctx, "itemId"));
        if (item == null) return 0;
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        city.queueCrafting(new dev.autociv.simulation.model.CraftingOrder(id, batches));
        manager.flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Заказано " + batches + " партий: "
                + item.getDescription().getString() + ". Инженер возьмёт сырьё из общего склада."), false);
        if (player != null) TownHallScreenPayload.openFor(player, manager.simulation(), city);
        return 1;
    }

    private static Item resolveCraftableBlock(CommandContext<CommandSourceStack> ctx, String rawId) {
        ResourceLocation id = ResourceLocation.tryParse(rawId);
        Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (!(item instanceof BlockItem)) {
            ctx.getSource().sendFailure(Component.literal("Укажи существующий предмет-блок с рецептом крафта."));
            return null;
        }
        boolean recipeExists = ctx.getSource().getLevel().getRecipeManager()
                .getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING).stream()
                .anyMatch(holder -> holder.value().getResultItem(ctx.getSource().registryAccess()).is(item));
        if (!recipeExists) {
            ctx.getSource().sendFailure(Component.literal("В рецептах этого мира нет крафта для указанного блока."));
            return null;
        }
        return item;
    }

    private static List<String> craftableBlockIds(net.minecraft.server.level.ServerLevel level) {
        return level.getRecipeManager().getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING).stream()
                .map(holder -> holder.value().getResultItem(level.registryAccess()))
                .filter(stack -> !stack.isEmpty() && stack.getItem() instanceof BlockItem)
                .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                .distinct().sorted().toList();
    }

    private static int manageCraftCancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return cancelCraft(ctx, true);
    }

    private static int adminCraftCancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return cancelCraft(ctx, false);
    }

    private static int cancelCraft(CommandContext<CommandSourceStack> ctx, boolean requireOwner)
            throws CommandSyntaxException {
        ServerPlayer player = requireOwner ? ctx.getSource().getPlayerOrException() : null;
        WorldSimulationManager manager = manager(ctx);
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null || requireOwner && !colonyAccess(ctx, city, true)) return 0;
        int index = IntegerArgumentType.getInteger(ctx, "index") - 1;
        if (!city.removeCraftingOrder(index)) {
            ctx.getSource().sendFailure(Component.literal("Заказ с таким номером не найден."));
            return 0;
        }
        manager.flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Заказ мастерской отменён; уже изготовленные предметы остались на складе."), false);
        if (player != null) TownHallScreenPayload.openFor(player, manager.simulation(), city);
        return 1;
    }

    private static int manageCraftOrder(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        WorldSimulationManager manager = manager(ctx);
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null || !colonyAccess(ctx, city, true)) return 0;
        int index = IntegerArgumentType.getInteger(ctx, "index") - 1;
        String direction = StringArgumentType.getString(ctx, "direction");
        int delta = direction.equalsIgnoreCase("up") ? -1 : direction.equalsIgnoreCase("down") ? 1 : 0;
        if (delta == 0 || !city.moveCraftingOrder(index, delta)) {
            ctx.getSource().sendFailure(Component.literal("Нельзя переместить заказ крафта."));
            return 0;
        }
        manager.flushChange();
        TownHallScreenPayload.openFor(player, manager.simulation(), city);
        ctx.getSource().sendSuccess(() -> Component.literal("Очередь мастерской обновлена."), false);
        return 1;
    }

    private static int adminHelp(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("Админ-панель (OP): /civ admin city <UUID> rename|move|remove CONFIRM; "
                + "/civ admin citizen age|job|remove; /civ admin stock; owner; project complete|cancel; "
                + "craft <UUID> <block-id> <batches>; craftcancel <UUID> <index>; history; pause/resume/step."), false);
        return 1;
    }

    private static int adminStep(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulationManager manager = manager(ctx);
        manager.stepSimulationDay();
        ctx.getSource().sendSuccess(() -> Component.literal("Симуляция продвинута на один день; год "
                + manager.simulation().year() + "."), false);
        return 1;
    }

    private static int adminSetSpeed(CommandContext<CommandSourceStack> ctx, SimulationSpeed speed)
            throws CommandSyntaxException {
        manager(ctx).setSimulationSpeed(speed);
        ctx.getSource().sendSuccess(() -> Component.literal("Скорость симуляции: "
                + speed.name().toLowerCase(java.util.Locale.ROOT) + "."), true);
        return 1;
    }

    private static int adminRenameCity(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null) return 0;
        String name = StringArgumentType.getString(ctx, "name").trim();
        if (name.isBlank() || name.length() > 48) {
            ctx.getSource().sendFailure(Component.literal("Название должно содержать 1–48 символов.")); return 0;
        }
        city.setName(name);
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Город переименован: " + name), true);
        return 1;
    }

    private static int adminMoveCity(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null) return 0;
        if (city.physicalSettlementGenerated()) {
            ctx.getSource().sendFailure(Component.literal("Город уже материализован. Перемещение физических зданий пока не поддерживается; отказано, чтобы не потерять постройки."));
            return 0;
        }
        manager(ctx).simulation().moveSettlement(city, IntegerArgumentType.getInteger(ctx, "x"),
                IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z"));
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Центр города перемещён; карта чанков обновлена."), true);
        return 1;
    }

    private static int adminRemoveCity(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "cityId");
        Settlement city = settlementById(ctx, id);
        if (city == null) return 0;
        WorldSimulationManager manager = manager(ctx);
        String name = city.name();
        manager.simulation().removeSettlement(city.id());
        manager.flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Город «" + name + "» удалён из симуляции. Физические блоки в мире сохранены."), true);
        return 1;
    }

    private static Citizen adminCitizen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String prefix = StringArgumentType.getString(ctx, "citizenId");
        Citizen citizen = manager(ctx).simulation().citizens().stream()
                .filter(c -> c.id().toString().startsWith(prefix)).findFirst().orElse(null);
        if (citizen == null) ctx.getSource().sendFailure(Component.literal("Житель не найден."));
        return citizen;
    }

    private static int adminRemoveCitizen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Citizen citizen = adminCitizen(ctx);
        if (citizen == null) return 0;
        String name = citizen.name();
        manager(ctx).simulation().removeCitizen(citizen);
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Житель «" + name + "» удалён из модели."), true);
        return 1;
    }

    private static int adminCitizenAge(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Citizen citizen = adminCitizen(ctx);
        if (citizen == null) return 0;
        citizen.setAgeYears(IntegerArgumentType.getInteger(ctx, "age"));
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Возраст " + citizen.name() + ": "
                + Math.round(citizen.ageYears())), true);
        return 1;
    }

    private static int adminCitizenJob(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Citizen citizen = adminCitizen(ctx);
        if (citizen == null) return 0;
        try { citizen.setProfession(Citizen.Profession.valueOf(StringArgumentType.getString(ctx, "profession")
                .toUpperCase(java.util.Locale.ROOT))); }
        catch (IllegalArgumentException invalid) { ctx.getSource().sendFailure(Component.literal("Неизвестная профессия.")); return 0; }
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Профессия жителя изменена."), true);
        return 1;
    }

    private static int adminStock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null) return 0;
        ResourceType resource = ResourceType.byId(StringArgumentType.getString(ctx, "resource")).orElse(null);
        if (resource == null) { ctx.getSource().sendFailure(Component.literal("Неизвестный ресурс.")); return 0; }
        int target = IntegerArgumentType.getInteger(ctx, "amount");
        city.stockpile().add(resource, target - city.stockpile().get(resource));
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Запас " + resource.id() + " теперь "
                + Math.round(city.stockpile().get(resource)) + "."), true);
        return 1;
    }

    private static int adminOwner(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null) return 0;
        String raw = StringArgumentType.getString(ctx, "playerUuid");
        try { city.setOwnerPlayerId(raw.equalsIgnoreCase("none") ? null : UUID.fromString(raw)); }
        catch (IllegalArgumentException invalid) { ctx.getSource().sendFailure(Component.literal("Укажи UUID игрока или none.")); return 0; }
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Владелец города обновлён."), true);
        return 1;
    }

    private static int adminCityStats(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null) return 0;
        city.setHappiness(IntegerArgumentType.getInteger(ctx, "happiness") / 100.0);
        city.setSecurity(IntegerArgumentType.getInteger(ctx, "security") / 100.0);
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Настроение и безопасность города обновлены."), true);
        return 1;
    }

    private static int adminTreasury(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Civilization civ = resolveCiv(manager(ctx).simulation(), StringArgumentType.getString(ctx, "civ"));
        double target = IntegerArgumentType.getInteger(ctx, "amount");
        civ.addToTreasury(target - civ.treasury());
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Казна обновлена: " + Math.round(civ.treasury())), true);
        return 1;
    }

    private static int adminCulture(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Civilization civ = resolveCiv(manager(ctx).simulation(), StringArgumentType.getString(ctx, "civ"));
        double target = IntegerArgumentType.getInteger(ctx, "amount");
        civ.addToCulture(target - civ.culture());
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Культура обновлена: " + Math.round(civ.culture())), true);
        return 1;
    }

    private static int adminProject(CommandContext<CommandSourceStack> ctx, boolean complete) throws CommandSyntaxException {
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null) return 0;
        BuildingPlan plan = city.activeBuildingPlan();
        if (plan == null) { ctx.getSource().sendFailure(Component.literal("Нет активного проекта.")); return 0; }
        if (complete) city.completeActiveBuildingPlan();
        else {
            city.stockpile().add(ResourceType.WOOD, plan.type().woodCost());
            city.stockpile().add(ResourceType.STONE, plan.type().stoneCost());
            city.setActiveBuildingPlan(null);
        }
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal(complete ? "Проект завершён." : "Проект отменён, резерв материалов возвращён."), true);
        return 1;
    }

    private static int adminHistory(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Settlement city = settlementById(ctx, StringArgumentType.getString(ctx, "cityId"));
        if (city == null) return 0;
        Civilization civ = manager(ctx).simulation().civilization(city.civilizationId()).orElse(null);
        if (civ == null || civ.visibleHistory().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("Для этой цивилизации пока нет записей."), false);
            return 1;
        }
        civ.visibleHistory().stream().skip(Math.max(0, civ.visibleHistory().size() - 10L))
                .forEach(event -> ctx.getSource().sendSuccess(() -> Component.literal("• " + event), false));
        return 1;
    }

    private record WorldHistoryEntry(int year, String civilization, String event) { }

    private static int simulationLodStatus(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (player.serverLevel() != player.getServer().overworld()) {
            ctx.getSource().sendFailure(Component.literal("LOD расстояний показывается только в Верхнем мире."));
            return 0;
        }
        WorldSimulation simulation = manager(ctx).simulation();
        List<Settlement> nearest = simulation.settlements().stream()
                .sorted(java.util.Comparator.comparingDouble((Settlement city) -> {
                    double dx = city.x() - player.getX();
                    double dz = city.z() - player.getZ();
                    return dx * dx + dz * dz;
                })).limit(20).toList();
        ctx.getSource().sendSuccess(() -> Component.literal("━━ LOD симуляции · физические жители только рядом ━━")
                .withStyle(ChatFormatting.GOLD), false);
        for (Settlement city : nearest) {
            double dx = city.x() - player.getX();
            double dz = city.z() - player.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            dev.autociv.simulation.world.SimulationLod lod =
                    dev.autociv.simulation.world.SimulationLod.atDistanceSquared(distance * distance);
            String line = String.format(java.util.Locale.ROOT,
                    "%s / %s · %.0f блоков · %s · максимум сущностей: %d жителей, %d стражей",
                    simulation.civilization(city.civilizationId()).map(Civilization::name).orElse("?"),
                    city.name(), distance, lod.name(), lod.physicalCitizenBudget(), lod.physicalGuardBudget());
            ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        if (nearest.isEmpty()) ctx.getSource().sendSuccess(() -> Component.literal("Поселений пока нет."), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Дальние города продолжают математическую симуляцию без загрузки чанков."), false);
        return nearest.size();
    }

    private static int worldHistory(CommandContext<CommandSourceStack> ctx, String civilizationName)
            throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        Civilization selected = civilizationName == null ? null : resolveCiv(simulation, civilizationName);
        List<WorldHistoryEntry> entries = new java.util.ArrayList<>();
        for (Civilization civilization : simulation.civilizations()) {
            if (selected != null && !selected.id().equals(civilization.id())) continue;
            for (String event : civilization.visibleHistory()) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("(?i)year\\s+(-?\\d+)[: ]").matcher(event);
                int year = matcher.find() ? Integer.parseInt(matcher.group(1)) : simulation.year();
                entries.add(new WorldHistoryEntry(year, civilization.name(), event));
            }
        }
        entries.sort(java.util.Comparator.comparingInt(WorldHistoryEntry::year)
                .thenComparing(WorldHistoryEntry::civilization, String.CASE_INSENSITIVE_ORDER));
        ctx.getSource().sendSuccess(() -> Component.literal("━━ История мира · записей: " + entries.size()
                + (selected == null ? "" : " · " + selected.name()) + " ━━").withStyle(ChatFormatting.GOLD), false);
        int start = Math.max(0, entries.size() - 40);
        for (WorldHistoryEntry entry : entries.subList(start, entries.size())) {
            String line = "[" + entry.year() + "] " + entry.civilization() + " — " + entry.event()
                    .replaceFirst("(?i)^year\\s+-?\\d+[: ]*", "");
            ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        if (entries.isEmpty()) ctx.getSource().sendSuccess(() -> Component.literal("История пока пуста."), false);
        if (entries.size() > 40) ctx.getSource().sendSuccess(() -> Component.literal(
                "Показаны последние 40 событий; подробная история города: /civ admin history <uuid>"), false);
        return entries.size();
    }

    private static Settlement settlementById(CommandContext<CommandSourceStack> ctx, String rawId)
            throws CommandSyntaxException {
        UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (IllegalArgumentException exception) {
            ctx.getSource().sendFailure(Component.literal("Некорректный идентификатор города."));
            return null;
        }
        Settlement city = manager(ctx).simulation().settlement(id).orElse(null);
        if (city == null) ctx.getSource().sendFailure(Component.literal("Город не найден."));
        return city;
    }

    private static int teleportToCity(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        Civilization civilization = resolveCiv(simulation, StringArgumentType.getString(ctx, "civ"));
        Settlement settlement = resolveCity(simulation, civilization,
                StringArgumentType.getString(ctx, "city"));
        return teleportToSettlement(ctx, civilization, settlement);
    }

    private static int teleportToOutpost(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        Civilization civilization = resolveCiv(simulation, StringArgumentType.getString(ctx, "civ"));
        Civilization rival = resolveCiv(simulation, StringArgumentType.getString(ctx, "rival"));
        var outpost = simulation.borderOutpost(civilization.id(), rival.id()).orElse(null);
        if (outpost == null) {
            ctx.getSource().sendFailure(Component.literal("У этой цивилизации нет аванпоста против "
                    + rival.name() + ". Координаты и состояние смотрите через /civ diplomacy."));
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var overworld = player.serverLevel().getServer().overworld();
        var surface = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new net.minecraft.core.BlockPos(outpost.x(), overworld.getMinBuildHeight(), outpost.z()));
        player.teleportTo(overworld, surface.getX() + 0.5, surface.getY() + 0.05, surface.getZ() + 0.5,
                java.util.Set.of(), player.getYRot(), player.getXRot());
        ctx.getSource().sendSuccess(() -> Component.literal("Телепорт к аванпосту " + civilization.name()
                + " на границе с " + rival.name() + "."), false);
        return 1;
    }

    private static int teleportToCapital(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        Civilization civilization = resolveCiv(simulation, StringArgumentType.getString(ctx, "civ"));
        Settlement capital = civilization.capitalSettlementId() == null ? null
                : simulation.settlement(civilization.capitalSettlementId()).orElse(null);
        if (capital == null) {
            ctx.getSource().sendFailure(Component.literal(civilization.name() + " has no capital settlement."));
            return 0;
        }
        return teleportToSettlement(ctx, civilization, capital);
    }

    private static int teleportToSettlement(CommandContext<CommandSourceStack> ctx,
                                            Civilization civilization, Settlement settlement)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var overworld = player.serverLevel().getServer().overworld();
        var surface = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new net.minecraft.core.BlockPos(settlement.x(), overworld.getMinBuildHeight(), settlement.z()));
        player.teleportTo(overworld, surface.getX() + 0.5, surface.getY() + 0.05, surface.getZ() + 0.5,
                java.util.Set.of(), player.getYRot(), player.getXRot());
        ctx.getSource().sendSuccess(() -> Component.literal("Teleported to " + settlement.name()
                + " in " + civilization.name() + "."), false);
        return 1;
    }

    private static int economy(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        Civilization civilization = resolveCiv(simulation, StringArgumentType.getString(ctx, "civ"));
        Settlement settlement = resolveCity(simulation, civilization,
                StringArgumentType.getString(ctx, "city"));
        PriceSystem prices = new PriceSystem();
        Market market = new Market(settlement, prices);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "=== Market: %s | %s treasury=%.2f ===", settlement.name(),
                civilization.name(), civilization.treasury()))
                .withStyle(ChatFormatting.GOLD), false);
        for (ResourceType resource : ResourceType.all().values()) {
            double stock = settlement.stockpile().get(resource);
            double price = market.price(resource);
            double delta = settlement.stockpile().deltaPerDay(resource);
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "%s stock=%.1f target=%.1f price=%.2f delta/day=%+.1f",
                    resource.id(), stock, prices.targetStock(settlement, resource), price, delta)), false);
        }
        List<Region> regions = simulation.regionsAssignedToSettlement(settlement.id());
        Map<String, Long> regionalResources = regions.stream().collect(java.util.stream.Collectors.groupingBy(
                region -> region.resource().id(), java.util.TreeMap::new, java.util.stream.Collectors.counting()));
        long activeRoutes = simulation.tradeRoutes().stream().filter(route -> route.active()
                && (route.sourceSettlementId().equals(settlement.id())
                || route.destinationSettlementId().equals(settlement.id()))).count();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "Regions=%d %s | active trade links=%d", regions.size(), regionalResources, activeRoutes))
                .withStyle(ChatFormatting.AQUA), false);
        List<dev.autociv.simulation.economy.TradeOffer> offers = market.offers();
        if (offers.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("Market: no current buy/sell offers."), false);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal("Market offers:").withStyle(ChatFormatting.YELLOW), false);
            for (var offer : offers) {
                String side = offer.side() == dev.autociv.simulation.economy.TradeOffer.Side.BUY ? "BUY" : "SELL";
                ctx.getSource().sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                        "  %s %.1f %s @ %.2f/unit", side, offer.quantity(), offer.resource().id(),
                        offer.unitPrice())), false);
            }
        }
        return 1;
    }

    private static int tradeRoutes(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        long activeRoutes = simulation.tradeRoutes().stream().filter(route -> route.active()).count();
        if (simulation.tradeRoutes().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(simulation.tradeShipments().isEmpty()
                    ? "Пока нет торговых маршрутов."
                    : "Маршруты отсутствуют, но в пути грузов: " + simulation.tradeShipments().size()), false);
            if (!simulation.tradeShipments().isEmpty()) sendShipmentStatus(ctx, simulation);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("=== Торговля: маршруты активны "
                + activeRoutes + "/" + simulation.tradeRoutes().size() + ") ===")
                .withStyle(ChatFormatting.GOLD), false);
        for (var route : simulation.tradeRoutes()) {
            String source = simulation.settlement(route.sourceSettlementId()).map(Settlement::name).orElse("unknown");
            String destination = simulation.settlement(route.destinationSettlementId())
                    .map(Settlement::name).orElse("unknown");
            double transportRevenue = simulation.merchant(route.merchantId())
                    .map(dev.autociv.simulation.economy.Merchant::transportRevenue).orElse(0.0);
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "%s -> %s: %s доставлено=%.1f рейсов=%d логистика=%.1f путь=%.0f блоков статус=%s",
                    source, destination, route.resource().id(), route.totalQuantity(),
                    route.completedShipments(), transportRevenue, route.distance(),
                    route.active() ? "активен" : "пауза")), false);
        }
        sendShipmentStatus(ctx, simulation);
        return simulation.tradeRoutes().size();
    }

    private static void sendShipmentStatus(CommandContext<CommandSourceStack> ctx, WorldSimulation simulation) {
        if (simulation.tradeShipments().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("Грузы в пути: нет"), false);
            return;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Грузы в пути: " + simulation.tradeShipments().size())
                .withStyle(ChatFormatting.YELLOW), false);
        for (var shipment : simulation.tradeShipments()) {
            var route = simulation.tradeRoute(shipment.routeId()).orElse(null);
            if (route == null) continue;
            String source = simulation.settlement(route.sourceSettlementId()).map(Settlement::name).orElse("город удалён");
            String destination = simulation.settlement(route.destinationSettlementId())
                    .map(Settlement::name).orElse("город удалён");
            String line = String.format(java.util.Locale.ROOT, "%s → %s: %s ×%.0f, прибытие через %d дн.",
                    source, destination, shipment.resource().id(), shipment.quantity(),
                    Math.max(0, (int) Math.ceil(shipment.arrivalDay() - simulation.timeDays())));
            ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
    }

    private static int diplomacyStatus(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        List<Civilization> civilizations = manager(ctx).simulation().civilizations().stream()
                .sorted(java.util.Comparator.comparing(civ -> civ.name().toLowerCase(java.util.Locale.ROOT)))
                .toList();
        if (civilizations.size() < 2) {
            ctx.getSource().sendSuccess(() -> Component.literal("Diplomacy needs at least two civilizations."), false);
            return 0;
        }
        int pairs = 0;
        ctx.getSource().sendSuccess(() -> Component.literal("=== Diplomatic relations ===")
                .withStyle(ChatFormatting.GOLD), false);
        for (int first = 0; first < civilizations.size(); first++) {
            Civilization left = civilizations.get(first);
            for (int second = first + 1; second < civilizations.size(); second++) {
                Civilization right = civilizations.get(second);
                Civilization.Relation relation = left.relationWith(right.id());
                double opinion = (left.diplomaticOpinionWith(right.id())
                        + right.diplomaticOpinionWith(left.id())) * 0.5;
                String treaties = manager(ctx).simulation().agreementsBetween(left.id(), right.id()).stream()
                        .map(agreement -> agreement.type().name().toLowerCase(java.util.Locale.ROOT)
                                + " " + agreement.remainingDays() + "d")
                        .collect(java.util.stream.Collectors.joining(", "));
                String line = String.format(java.util.Locale.ROOT, "%s ↔ %s: %s (opinion %+.2f)%s",
                        left.name(), right.name(), relation.name().toLowerCase(java.util.Locale.ROOT), opinion,
                        treaties.isBlank() ? "" : " | " + treaties);
                ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.WHITE), false);
                pairs++;
            }
        }
        return pairs;
    }

    private static int diplomacyForCivilization(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        Civilization focus = resolveCiv(simulation, StringArgumentType.getString(ctx, "civ"));
        ctx.getSource().sendSuccess(() -> Component.literal("=== Diplomacy: " + focus.name() + " ===")
                .withStyle(ChatFormatting.GOLD), false);
        for (Civilization other : simulation.civilizations().stream()
                .filter(candidate -> !candidate.id().equals(focus.id()))
                .sorted(java.util.Comparator.comparing(candidate -> candidate.name().toLowerCase(java.util.Locale.ROOT)))
                .toList()) {
            double opinion = (focus.diplomaticOpinionWith(other.id()) + other.diplomaticOpinionWith(focus.id())) * 0.5;
            String treaties = simulation.agreementsBetween(focus.id(), other.id()).stream()
                    .map(agreement -> agreement.type().name().toLowerCase(java.util.Locale.ROOT)
                            + " " + agreement.remainingDays() + "d")
                    .collect(java.util.stream.Collectors.joining(", "));
            String line = String.format(java.util.Locale.ROOT, "%s: %s (opinion %+.2f)%s", other.name(),
                    focus.relationWith(other.id()).name().toLowerCase(java.util.Locale.ROOT), opinion,
                    treaties.isBlank() ? "" : " | " + treaties);
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            var ownOutpost = simulation.borderOutpost(focus.id(), other.id()).orElse(null);
            var rivalOutpost = simulation.borderOutpost(other.id(), focus.id()).orElse(null);
            if (ownOutpost != null || rivalOutpost != null) {
                int standoffDays = ownOutpost == null || rivalOutpost == null ? 0
                        : Math.max(0, (int) (simulation.timeDays()
                                - Math.max(ownOutpost.foundedDay(), rivalOutpost.foundedDay())));
                String camps = String.format(java.util.Locale.ROOT,
                        "  Пограничные аванпосты: ваши %s; противника %s; противостояние %d/%d дней",
                        ownOutpost == null ? "не построены" : ownOutpost.x() + ", " + ownOutpost.z(),
                        rivalOutpost == null ? "не построены" : rivalOutpost.x() + ", " + rivalOutpost.z(),
                        standoffDays, 180);
                ctx.getSource().sendSuccess(() -> Component.literal(camps).withStyle(ChatFormatting.GRAY), false);
            }
        }
        return simulation.civilizations().size() - 1;
    }

    private static int diplomacyTreaty(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        Civilization first = resolveCiv(simulation, StringArgumentType.getString(ctx, "first"));
        Civilization second = resolveCiv(simulation, StringArgumentType.getString(ctx, "second"));
        if (first.id().equals(second.id())) {
            ctx.getSource().sendFailure(Component.literal("A civilization cannot sign a treaty with itself."));
            return 0;
        }
        if (first.relationWith(second.id()) == Civilization.Relation.WAR
                || second.relationWith(first.id()) == Civilization.Relation.WAR) {
            ctx.getSource().sendFailure(Component.literal("Make peace before signing another treaty."));
            return 0;
        }
        DiplomaticAgreement.Type type;
        try {
            type = DiplomaticAgreement.Type.valueOf(StringArgumentType.getString(ctx, "type")
                    .toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            ctx.getSource().sendFailure(Component.literal("Treaty type must be trade_pact, non_aggression, or alliance."));
            return 0;
        }
        if (type == DiplomaticAgreement.Type.PEACE) {
            ctx.getSource().sendFailure(Component.literal("Use /civ diplomacy peace for a peace agreement."));
            return 0;
        }
        int days = IntegerArgumentType.getInteger(ctx, "days");
        simulation.addDiplomaticAgreement(DiplomaticAgreement.create(first.id(), second.id(), type, days));
        if (type == DiplomaticAgreement.Type.ALLIANCE) {
            first.setRelationWith(second.id(), Civilization.Relation.ALLIED);
            second.setRelationWith(first.id(), Civilization.Relation.ALLIED);
            first.setDiplomaticOpinionWith(second.id(), Math.max(0.65, first.diplomaticOpinionWith(second.id())));
            second.setDiplomaticOpinionWith(first.id(), Math.max(0.65, second.diplomaticOpinionWith(first.id())));
        } else if (type == DiplomaticAgreement.Type.TRADE_PACT) {
            first.setRelationWith(second.id(), Civilization.Relation.TRADE_PARTNER);
            second.setRelationWith(first.id(), Civilization.Relation.TRADE_PARTNER);
            first.setDiplomaticOpinionWith(second.id(), Math.max(0.2, first.diplomaticOpinionWith(second.id())));
            second.setDiplomaticOpinionWith(first.id(), Math.max(0.2, second.diplomaticOpinionWith(first.id())));
        }
        recordDiplomaticEvent(simulation, first, second, type.name().toLowerCase(java.util.Locale.ROOT)
                + " treaty signed for " + days + " days");
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Treaty signed: " + first.name() + " and "
                + second.name() + " / " + type.name().toLowerCase(java.util.Locale.ROOT) + " / " + days + " days."), true);
        return 1;
    }

    private static int diplomacyWar(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        Civilization first = resolveCiv(simulation, StringArgumentType.getString(ctx, "first"));
        Civilization second = resolveCiv(simulation, StringArgumentType.getString(ctx, "second"));
        if (first.id().equals(second.id())) {
            ctx.getSource().sendFailure(Component.literal("A civilization cannot declare war on itself."));
            return 0;
        }
        simulation.removeAgreementsBetween(first.id(), second.id());
        first.setRelationWith(second.id(), Civilization.Relation.WAR);
        second.setRelationWith(first.id(), Civilization.Relation.WAR);
        first.setDiplomaticOpinionWith(second.id(), -1.0);
        second.setDiplomaticOpinionWith(first.id(), -1.0);
        recordDiplomaticEvent(simulation, first, second, "war declared");
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("War declared: " + first.name() + " vs " + second.name()
                + ". Their trade shipments are suspended."), true);
        return 1;
    }

    private static int diplomacyPeace(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        Civilization first = resolveCiv(simulation, StringArgumentType.getString(ctx, "first"));
        Civilization second = resolveCiv(simulation, StringArgumentType.getString(ctx, "second"));
        if (first.id().equals(second.id())) {
            ctx.getSource().sendFailure(Component.literal("A civilization cannot make peace with itself."));
            return 0;
        }
        simulation.removeAgreementsBetween(first.id(), second.id());
        simulation.addDiplomaticAgreement(DiplomaticAgreement.create(first.id(), second.id(),
                DiplomaticAgreement.Type.PEACE, 360));
        first.setRelationWith(second.id(), Civilization.Relation.TENSE);
        second.setRelationWith(first.id(), Civilization.Relation.TENSE);
        first.setDiplomaticOpinionWith(second.id(), -0.2);
        second.setDiplomaticOpinionWith(first.id(), -0.2);
        recordDiplomaticEvent(simulation, first, second, "peace signed for one year");
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Peace signed between " + first.name() + " and "
                + second.name() + ". A 360-day non-hostility period begins."), true);
        return 1;
    }

    private static void recordDiplomaticEvent(WorldSimulation simulation, Civilization first,
                                               Civilization second, String event) {
        first.recordEvent("year " + simulation.year() + ": " + event + " with " + second.name() + ".");
        second.recordEvent("year " + simulation.year() + ": " + event + " with " + first.name() + ".");
    }

    private static int territorySummary(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        ctx.getSource().sendSuccess(() -> Component.literal("=== Claimed world regions ===")
                .withStyle(ChatFormatting.GOLD), false);
        for (Civilization civilization : simulation.civilizations().stream()
                .sorted(java.util.Comparator.comparing(Civilization::name)).toList()) {
            List<Region> owned = simulation.regionsOwnedBy(civilization.id());
            Map<String, Long> resources = owned.stream().collect(java.util.stream.Collectors.groupingBy(
                    region -> region.resource().id(), java.util.stream.Collectors.counting()));
            String line = civilization.name() + ": " + owned.size() + " regions | resources " + resources;
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return simulation.regions().size();
    }

    private record NetworkRow(Settlement city, int depth) { }

    private static int roadNetworkStatus(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        Civilization civilization = resolveCiv(simulation, StringArgumentType.getString(ctx, "civ"));
        List<RoadNetwork.Link> links = RoadNetwork.links(simulation, civilization.id());
        int roadCities = (int) simulation.settlementsOf(civilization.id()).stream()
                .filter(city -> city.buildingCount(BuildingType.ROAD) > 0).count();
        double totalDistance = links.stream().mapToDouble(RoadNetwork.Link::distance).sum();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "━━ Дороги «%s» · узлов: %d · связей: %d · протяжённость: %.0f блоков ━━",
                civilization.name(), roadCities, links.size(), totalDistance)).withStyle(ChatFormatting.GOLD), false);
        if (links.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Связных межгородских дорог пока нет. Для связи нужны построенные дороги в городе и его городе-родителе.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        for (RoadNetwork.Link link : links) {
            String quality = switch (link.level()) {
                case 1 -> "грунтовый тракт";
                case 2 -> "укреплённая дорога";
                default -> "мощёная трасса";
            };
            String line = String.format(java.util.Locale.ROOT, "%s → %s · %s · %.0f блоков",
                    link.parent().name(), link.child().name(), quality, link.distance());
            ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        return links.size();
    }

    private static int railNetworkStatus(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        Civilization civilization = resolveCiv(simulation, StringArgumentType.getString(ctx, "civ"));
        List<RailNetwork.Link> links = RailNetwork.links(simulation, civilization.id());
        ctx.getSource().sendSuccess(() -> Component.literal("━━ Железные дороги «" + civilization.name()
                + "» · станций: " + simulation.settlementsOf(civilization.id()).stream()
                .filter(city -> city.buildingCount(BuildingType.RAILWAY_STATION) > 0).count()
                + " · межгородских линий: " + links.size() + " ━━").withStyle(ChatFormatting.GOLD), false);
        if (links.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Действующих линий пока нет. Нужны станции в связанном городе и его городе-родителе.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        for (RailNetwork.Link link : links) {
            String line = String.format(java.util.Locale.ROOT, "%s ⇄ %s · %.0f блоков · грузовой тариф 42%%",
                    link.parent().name(), link.child().name(), link.distance());
            ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        return links.size();
    }

    private static int settlementNetwork(CommandContext<CommandSourceStack> ctx, int page)
            throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        Civilization civilization = resolveCiv(simulation, StringArgumentType.getString(ctx, "civ"));
        List<Settlement> cities = simulation.settlementsOf(civilization.id()).stream()
                .sorted(java.util.Comparator.comparing(Settlement::name, String.CASE_INSENSITIVE_ORDER)).toList();
        java.util.Set<UUID> cityIds = cities.stream().map(Settlement::id).collect(java.util.stream.Collectors.toSet());
        Map<UUID, List<Settlement>> children = cities.stream().filter(city -> city.parentSettlementId() != null)
                .collect(java.util.stream.Collectors.groupingBy(Settlement::parentSettlementId));
        List<Settlement> roots = cities.stream().filter(city -> city.parentSettlementId() == null
                        || !cityIds.contains(city.parentSettlementId()))
                .sorted(java.util.Comparator.comparing((Settlement city) ->
                        !city.id().equals(civilization.capitalSettlementId()))
                        .thenComparing(Settlement::name, String.CASE_INSENSITIVE_ORDER)).toList();
        java.util.ArrayDeque<NetworkRow> queue = new java.util.ArrayDeque<>();
        roots.forEach(root -> queue.addLast(new NetworkRow(root, 0)));
        java.util.Set<UUID> visited = new java.util.HashSet<>();
        List<NetworkRow> rows = new java.util.ArrayList<>(cities.size());
        while (!queue.isEmpty()) {
            NetworkRow row = queue.removeFirst();
            if (!visited.add(row.city().id())) continue;
            rows.add(row);
            children.getOrDefault(row.city().id(), List.of()).stream()
                    .sorted(java.util.Comparator.comparing(Settlement::name, String.CASE_INSENSITIVE_ORDER))
                    .forEach(child -> queue.addLast(new NetworkRow(child, row.depth() + 1)));
        }
        // Corrupt legacy links cannot hide a city from diagnostics.
        cities.stream().filter(city -> !visited.contains(city.id()))
                .forEach(city -> rows.add(new NetworkRow(city, 0)));

        int pageSize = 40;
        int pageCount = Math.max(1, (rows.size() + pageSize - 1) / pageSize);
        int requestedPage = Math.min(page, pageCount);
        String capitalName = simulation.settlement(civilization.capitalSettlementId())
                .map(Settlement::name).orElse("не назначена");
        ctx.getSource().sendSuccess(() -> Component.literal("━━ Сеть «" + civilization.name() + "» · столица: "
                + capitalName + " · городов: " + cities.size() + " · страница " + requestedPage + "/" + pageCount
                + " ━━").withStyle(ChatFormatting.GOLD), false);
        int from = (requestedPage - 1) * pageSize;
        for (NetworkRow row : rows.subList(from, Math.min(rows.size(), from + pageSize))) {
            Settlement city = row.city();
            String indent = "  ".repeat(Math.min(row.depth(), 8));
            String parent = city.parentSettlementId() == null ? "корень"
                    : simulation.settlement(city.parentSettlementId()).map(Settlement::name).orElse("связь потеряна");
            String label = city.id().equals(civilization.capitalSettlementId()) ? "★ " : "• ";
            String line = String.format(java.util.Locale.ROOT,
                    "%s%s%s — %s, %s, %d/%d жителей, родитель: %s, основан в %d г.",
                    indent, label, city.name(), city.tier().displayNameRu(), city.role().displayNameRu(),
                    city.population(), city.housingCapacity(), parent, city.foundedYear());
            ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        if (requestedPage < pageCount) {
            ctx.getSource().sendSuccess(() -> Component.literal("Следующая страница: /civ network "
                    + civilization.name() + " " + (requestedPage + 1)).withStyle(ChatFormatting.YELLOW), false);
        }
        return rows.size();
    }

    private static int territoryDetails(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        Civilization civilization = resolveCiv(simulation, StringArgumentType.getString(ctx, "civ"));
        List<Region> owned = simulation.regionsOwnedBy(civilization.id()).stream()
                .sorted(java.util.Comparator.comparingInt(Region::gridX).thenComparingInt(Region::gridZ)).toList();
        ctx.getSource().sendSuccess(() -> Component.literal("=== Regions of " + civilization.name()
                + " (" + owned.size() + ") ===").withStyle(ChatFormatting.GOLD), false);
        for (Region region : owned) {
            String settlement = simulation.settlement(region.nearestSettlementId())
                    .map(Settlement::name).orElse("unassigned");
            String line = String.format(java.util.Locale.ROOT,
                    "%d,%d center=%d,%d resource=%s richness=%.2f city=%s",
                    region.gridX(), region.gridZ(), region.centerX(), region.centerZ(), region.resource().id(),
                    region.richness(), settlement);
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return owned.size();
    }

    // ------------------------------------------------------------------ list

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation sim = manager(ctx).simulation();
        if (sim.civilizations().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("Цивилизаций пока нет. Создай: /civ create <имя>")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("━━ Цивилизации: " + sim.civilizations().size() + " ━━")
                .withStyle(ChatFormatting.GOLD), false);
        for (Civilization c : sim.civilizations().stream()
                .sorted(java.util.Comparator.comparing(Civilization::name, String.CASE_INSENSITIVE_ORDER)).toList()) {
            List<Settlement> cities = sim.settlementsOf(c.id());
            int pop = cities.stream().mapToInt(Settlement::population).sum();
            String cityNames = cities.isEmpty() ? "городов нет" : String.join(", ", cities.stream()
                    .map(city -> city.name() + (city.id().equals(c.capitalSettlementId()) ? " ★" : ""))
                    .toList());
            ctx.getSource().sendSuccess(() -> Component.literal("● " + c.name())
                    .withStyle(style -> style.withColor(net.minecraft.network.chat.TextColor.fromRgb(c.color()))
                            .withBold(true)), false);
            ctx.getSource().sendSuccess(() -> Component.literal("   Города: " + cityNames
                    + "  |  жителей: " + pop + "  |  казна: " + String.format(java.util.Locale.ROOT, "%.0f", c.treasury())
                    + "  |  ID " + shortId(c.id())).withStyle(ChatFormatting.GRAY), false);
        }
        return sim.civilizations().size();
    }

    private static int debugCivilization(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        Civilization civilization = resolveCiv(simulation, StringArgumentType.getString(ctx, "civ"));
        List<Settlement> cities = simulation.settlementsOf(civilization.id());
        ctx.getSource().sendSuccess(() -> Component.literal("━━ " + civilization.name() + " ━━")
                .withStyle(ChatFormatting.GOLD), false);
        ctx.getSource().sendSuccess(() -> Component.literal("Города: " + cities.size() + " | население: "
                + cities.stream().mapToInt(Settlement::population).sum() + " | казна: "
                + String.format(java.util.Locale.ROOT, "%.1f", civilization.treasury())
                + " | отношение игрока смотри у ратуши"), false);
        for (Settlement city : cities) {
            String parent = city.parentSettlementId() == null ? "столица/корень"
                    : simulation.settlement(city.parentSettlementId()).map(Settlement::name).orElse("неизвестен");
            ctx.getSource().sendSuccess(() -> Component.literal("• " + city.name() + " — жителей "
                    + city.population() + ", дома " + city.materializedHomes() + "/" + city.completedHomes()
                    + ", " + city.tier().displayNameRu() + ", " + city.role().displayNameRu()
                    + ", родитель " + parent
                    + ", стройка " + (city.activeBuildingPlan() == null ? "нет" : "идёт")
                    + "  [город: " + shortId(city.id()) + "]").withStyle(ChatFormatting.AQUA), false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Выбери город через Tab: /civ debug "
                + civilization.name() + " <город>"), false);
        return cities.size();
    }

    private static int debugCity(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        Civilization civilization = resolveCiv(simulation, StringArgumentType.getString(ctx, "civ"));
        Settlement city = resolveCity(simulation, civilization, StringArgumentType.getString(ctx, "city"));
        int builders = (int) city.citizenIds().stream().map(id -> simulation.citizen(id).orElse(null))
                .filter(java.util.Objects::nonNull).filter(citizen -> citizen.profession() == Citizen.Profession.BUILDER
                        && citizen.isAdult()).count();
        ctx.getSource().sendSuccess(() -> Component.literal("━━ " + civilization.name() + " / " + city.name() + " ━━")
                .withStyle(ChatFormatting.GOLD), false);
        String parent = city.parentSettlementId() == null ? "столица/корень"
                : simulation.settlement(city.parentSettlementId()).map(Settlement::name).orElse("связь потеряна");
        ctx.getSource().sendSuccess(() -> Component.literal("Сеть: " + city.tier().displayNameRu() + " · "
                + city.role().displayNameRu() + " · родитель: " + parent + " · основан в "
                + city.foundedYear() + " г."), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "Жители %d/%d | физические дома %d/%d | строители %d | доски %.0f | камень %.0f",
                city.population(), city.housingCapacity(), city.materializedHomes(), city.completedHomes(), builders,
                city.stockpile().get(ResourceType.WOOD), city.stockpile().get(ResourceType.STONE))), false);
        double favor = ctx.getSource().getEntity() instanceof ServerPlayer player
                ? dev.autociv.simulation.world.TownHallInteraction.favor(player, city) : 0.0;
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "Доверие жителей к тебе: %.1f/100 | отдай ресурсы колоколу ратуши, чтобы пополнить общий сундук",
                favor)).withStyle(ChatFormatting.GREEN), false);
        String project = city.activeBuildingPlan() == null ? "нет проекта" : "проект "
                + city.activeBuildingPlan().type().name().toLowerCase(java.util.Locale.ROOT) + " "
                + city.activeBuildingPlan().progressDays() + "/" + city.activeBuildingPlan().requiredDays() + " дней";
        ctx.getSource().sendSuccess(() -> Component.literal("Текущая стройка: " + project
                + " | физически ждёт: " + city.pendingPhysicalBuildings().size()), false);
        ctx.getSource().sendSuccess(() -> Component.literal("Тест домика: /civ debug build "
                + civilization.name() + " " + city.name()), false);
        ctx.getSource().sendSuccess(() -> Component.literal("Тест здания: /civ debug facility "
                + civilization.name() + " " + city.name() + " <тип>"), false);
        ctx.getSource().sendSuccess(() -> Component.literal("Абстрактный запас: /civ city resource "
                + civilization.name() + " " + city.name() + " wood 200. Для реальной стройки жители ждут брёвна и камень в сундуке ратуши."), false);
        return 1;
    }

    // ------------------------------------------------------------------ info

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation sim = manager(ctx).simulation();
        Civilization civ = resolveCiv(sim, StringArgumentType.getString(ctx, "civ"));
        ctx.getSource().sendSuccess(() -> Component.literal("=== " + civ.name() + " ===").withStyle(ChatFormatting.GOLD), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("id=%s color=#%s capital=%s",
                civ.id(), colorHex(civ.color()), capitalName(sim, civ))), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("profile=%s era=%s startYear=%d architecture=%s currentYear=%d",
            civ.scenarioId(), civ.historicalEra(), civ.historicalStartYear(),
            civ.architectureStyle(), sim.year())).withStyle(ChatFormatting.AQUA), false);
        String traits = civ.traits().stream().map(dev.autociv.simulation.model.CivilizationTrait::displayNameRu)
                .collect(java.util.stream.Collectors.joining(" · "));
        ctx.getSource().sendSuccess(() -> Component.literal("Черты: " + traits)
                .withStyle(ChatFormatting.LIGHT_PURPLE), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("treasury=%.0f culture=%.0f military=%.0f",
                civ.treasury(), civ.culture(), civ.militaryStrength())), false);
        for (UUID sid : civ.settlementIds()) {
            sim.settlement(sid).ifPresent(s -> {
                String buildStatus = s.materializedHomes() < s.completedHomes()
                        ? String.format(" build=%d%%", s.homeBuildProgressPercent()) : "";
                ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                        "  city '%s' at (%d,%d,%d) pop=%d/%d happiness=%.0f%% homes=%d/%d%s",
                        s.name(), s.x(), s.y(), s.z(), s.population(), s.housingCapacity(),
                        s.happiness() * 100, s.materializedHomes(), s.completedHomes(), buildStatus)), false);
            });
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Known foreign settlements: "
            + civ.knownSettlementIds().size()).withStyle(ChatFormatting.AQUA), false);
        for (UUID knownSettlementId : civ.knownSettlementIds()) {
            sim.settlement(knownSettlementId).ifPresent(known -> sim.civilization(known.civilizationId())
                .ifPresent(owner -> ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "  known: %s / %s at (%d,%d)", owner.name(), known.name(), known.x(), known.z()))
                    .withStyle(ChatFormatting.GRAY), false)));
        }
        for (String h : civ.visibleHistory()) {
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

    /** Player-facing foundation gameplay: the charter consumes real starter supplies. */
    private static int charterCivilization(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)
                || level != player.getServer().overworld()) {
            ctx.getSource().sendFailure(Component.literal("Закладывать столицу можно только в Верхнем мире."));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        WorldSimulationManager manager = manager(ctx);
        WorldSimulation simulation = manager.simulation();
        if (simulation.findCivilization(name).isPresent()) {
            ctx.getSource().sendFailure(Component.literal("Цивилизация с таким названием уже существует."));
            return 0;
        }
        int x = player.blockPosition().getX();
        int z = player.blockPosition().getZ();
        if (!simulation.settlementsNear(x, z, 512).isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Здесь уже есть поселение. Отойди минимум на 512 блоков от ближайшего города."));
            return 0;
        }
        int[] heights = new int[25];
        int index = 0;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            net.minecraft.core.BlockPos probe = new net.minecraft.core.BlockPos(
                    x + dx, level.getMinBuildHeight(), z + dz);
            if (!level.hasChunkAt(probe)) {
                ctx.getSource().sendFailure(Component.literal("Площадка должна быть в загруженных чанках."));
                return 0;
            }
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
            net.minecraft.core.BlockPos floor = new net.minecraft.core.BlockPos(x + dx, surface - 1, z + dz);
            if (!level.getFluidState(floor).isEmpty() || !level.getBlockState(floor).getCollisionShape(level, floor)
                    .isEmpty()) {
                heights[index++] = surface;
            } else {
                ctx.getSource().sendFailure(Component.literal("Для столицы нужна сухая площадка с твёрдым грунтом."));
                return 0;
            }
        }
        int minHeight = java.util.Arrays.stream(heights).min().orElse(0);
        int maxHeight = java.util.Arrays.stream(heights).max().orElse(0);
        if (maxHeight - minHeight > 3) {
            ctx.getSource().sendFailure(Component.literal("Площадка слишком неровная для основания столицы."));
            return 0;
        }
        if (!dev.autociv.scenario.HistoricSettlementBuilder.isNaturalSite(level, x, z)) {
            ctx.getSource().sendFailure(Component.literal(
                    "В радиусе будущей деревни есть постройки или неподходящие блоки. Выбери нетронутую природную площадку."));
            return 0;
        }
        var logs = net.minecraft.world.item.Items.OAK_LOG;
        var stone = net.minecraft.world.item.Items.COBBLESTONE;
        var wheat = net.minecraft.world.item.Items.WHEAT;
        if (!hasItems(player, logs, 96) || !hasItems(player, stone, 64) || !hasItems(player, wheat, 24)) {
            ctx.getSource().sendFailure(Component.literal(
                    "Для хартии нужны 96 дубовых брёвен, 64 булыжника и 24 пшеницы."));
            return 0;
        }

        consumeItems(player, logs, 96);
        consumeItems(player, stone, 64);
        consumeItems(player, wheat, 24);
        int color = player.getUUID().hashCode() & 0xFFFFFF;
        Civilization civilization = simulation.createCivilization(name, color);
        civilization.setScenario("player_charter", "founding", simulation.year(), "generic");
        Settlement capital = simulation.createSettlement(civilization.id(), name, x, minHeight - 1, z);
        capital.setCapital(true);
        capital.claim(player.getUUID());
        capital.setHousingCapacity(3);
        capital.setHappiness(0.72);
        capital.setSecurity(0.55);
        civilization.addToTreasury(100);
        simulation.addCitizen(capital, player.getGameProfile().getName() + " — основатель", 24,
                Citizen.Profession.FARMER);
        simulation.addCitizen(capital, "Мастер-хранитель", 31, Citizen.Profession.BUILDER);
        simulation.addCitizen(capital, "Лесной дозорный", 27, Citizen.Profession.LUMBERJACK);
        civilization.recordEvent("year " + simulation.year() + ": " + player.getGameProfile().getName()
                + " founded the chartered capital " + name + ".");
        dev.autociv.scenario.SettlementChunkGenerator.generateIfLoaded(player.getServer(), capital);
        storeCharterResource(level, capital, ResourceType.WOOD, 96);
        storeCharterResource(level, capital, ResourceType.STONE, 64);
        storeCharterResource(level, capital, ResourceType.FOOD, 24);
        manager.flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Основана цивилизация «" + civilization.name()
                + "»! Столица: " + capital.name() + "; первые жители уже собирают материалы для домов.")
                .withStyle(ChatFormatting.GREEN), false);
        ctx.getSource().sendSuccess(() -> Component.literal("Отчёт столицы: /civ info " + civilization.name()
                + " · карта истории: /civ history " + civilization.name()).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static boolean hasItems(ServerPlayer player, net.minecraft.world.item.Item item, int amount) {
        return player.getInventory().items.stream().filter(stack -> stack.is(item))
                .mapToInt(net.minecraft.world.item.ItemStack::getCount).sum() >= amount;
    }

    private static void consumeItems(ServerPlayer player, net.minecraft.world.item.Item item, int amount) {
        if (player.getAbilities().instabuild) return;
        int remaining = amount;
        for (var stack : player.getInventory().items) {
            if (remaining <= 0) break;
            if (!stack.is(item)) continue;
            int count = Math.min(remaining, stack.getCount());
            stack.shrink(count);
            remaining -= count;
        }
    }

    private static void storeCharterResource(net.minecraft.server.level.ServerLevel level, Settlement city,
                                             ResourceType resource, int amount) {
        if (!city.physicalSettlementGenerated()) return;
        net.minecraft.core.BlockPos bell = new net.minecraft.core.BlockPos(city.x(), city.y() + 1, city.z());
        int remaining = amount;
        for (net.minecraft.world.Container container : dev.autociv.simulation.world.TownHallStorage
                .containersFor(level, city, bell, resource)) {
            remaining -= dev.autociv.simulation.world.TownHallStorage.store(container, resource, remaining);
            if (remaining <= 0) break;
        }
        double stored = amount - remaining;
        double accepted = city.stockpile().add(resource, stored);
        city.stockpile().recordDelta(resource, accepted);
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
        ctx.getSource().sendSuccess(() -> Component.literal("━━ Отладка цивилизаций ━━")
                .withStyle(ChatFormatting.GOLD), false);
        ctx.getSource().sendSuccess(() -> Component.literal("Цивилизаций: " + sim.civilizations().size()
                + " | городов: " + sim.settlements().size() + " | жителей: " + sim.totalPopulation()
                + " | день симуляции: " + String.format(java.util.Locale.ROOT, "%.0f", sim.timeDays())), false);
        ctx.getSource().sendSuccess(() -> Component.literal("Выбери страну через Tab: /civ debug <цивилизация>")
                .withStyle(ChatFormatting.AQUA), false);
        return sim.civilizations().size();
    }

    private static int openDebugGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CivDebugPayload.openFor(player, manager(ctx).simulation());
        return 1;
    }

    private static int debugStartConstruction(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        Civilization civilization = resolveCiv(simulation, StringArgumentType.getString(ctx, "civ"));
        Settlement city = resolveCity(simulation, civilization, StringArgumentType.getString(ctx, "city"));
        if (city.materializedHomes() < city.completedHomes()) {
            ctx.getSource().sendFailure(Component.literal("This settlement already has an unfinished physical house."));
            return 0;
        }
        if (city.completedHomes() >= CityPlanner.MAX_HOME_SITES) {
            ctx.getSource().sendFailure(Component.literal("No free house sites remain in this settlement."));
            return 0;
        }
        if (!city.stockpile().tryRemove(Map.of(ResourceType.WOOD, CityPlanner.WOOD_COST,
                ResourceType.STONE, CityPlanner.STONE_COST))) {
            ctx.getSource().sendFailure(Component.literal("Need 48 wood and 24 stone; add supplies with /civ city resource."));
            return 0;
        }
        city.stockpile().recordDelta(ResourceType.WOOD, -CityPlanner.WOOD_COST);
        city.stockpile().recordDelta(ResourceType.STONE, -CityPlanner.STONE_COST);
        city.completeHomeConstruction();
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Debug construction started for " + city.name()
                + "; a builder must be assigned and near the loaded settlement."), true);
        return 1;
    }

    private static int debugStartFacility(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        Civilization civilization = resolveCiv(simulation, StringArgumentType.getString(ctx, "civ"));
        Settlement city = resolveCity(simulation, civilization, StringArgumentType.getString(ctx, "city"));
        String requested = StringArgumentType.getString(ctx, "type");
        BuildingType type;
        try {
            type = BuildingType.valueOf(requested.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            ctx.getSource().sendFailure(Component.literal("Unknown facility. Try: "
                    + String.join(", ", java.util.Arrays.stream(BuildingType.values())
                    .map(value -> value.name().toLowerCase(java.util.Locale.ROOT)).toList())));
            return 0;
        }
        if (city.activeBuildingPlan() != null) {
            ctx.getSource().sendFailure(Component.literal("This settlement already has an active facility project."));
            return 0;
        }
        if (city.buildingCount(type) >= type.cityLimit()) {
            ctx.getSource().sendFailure(Component.literal("This settlement reached its "
                    + type.name().toLowerCase(java.util.Locale.ROOT) + " limit."));
            return 0;
        }
        Map<ResourceType, Double> cost = Map.of(ResourceType.WOOD, (double) type.woodCost(),
                ResourceType.STONE, (double) type.stoneCost());
        if (!city.stockpile().tryRemove(cost)) {
            ctx.getSource().sendFailure(Component.literal("Need " + type.woodCost() + " wood and "
                    + type.stoneCost() + " stone for this project."));
            return 0;
        }
        city.stockpile().recordDelta(ResourceType.WOOD, -type.woodCost());
        city.stockpile().recordDelta(ResourceType.STONE, -type.stoneCost());
        city.setActiveBuildingPlan(BuildingPlan.start(type));
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal("Started debug "
                + type.name().toLowerCase(java.util.Locale.ROOT) + " project in " + city.name()
                + ". It advances with simulation days, then a builder can place its physical template."), true);
        return 1;
    }

    private static int assignJob(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        WorldSimulation simulation = manager(ctx).simulation();
        Civilization civilization = resolveCiv(simulation, StringArgumentType.getString(ctx, "civ"));
        Settlement city = resolveCity(simulation, civilization, StringArgumentType.getString(ctx, "city"));
        String citizenNeedle = StringArgumentType.getString(ctx, "citizen").toLowerCase(java.util.Locale.ROOT);
        String professionName = StringArgumentType.getString(ctx, "profession").toUpperCase(java.util.Locale.ROOT);
        Citizen.Profession profession;
        try {
            profession = Citizen.Profession.valueOf(professionName);
        } catch (IllegalArgumentException exception) {
            ctx.getSource().sendFailure(Component.literal("Unknown profession. Try farmer, miner, lumberjack, builder, or guard."));
            return 0;
        }
        Citizen citizen = city.citizenIds().stream().map(id -> simulation.citizen(id).orElse(null))
                .filter(java.util.Objects::nonNull)
                .filter(candidate -> candidate.name().equalsIgnoreCase(citizenNeedle)
                        || candidate.id().toString().startsWith(citizenNeedle))
                .findFirst().orElse(null);
        if (citizen == null) {
            ctx.getSource().sendFailure(Component.literal("Citizen not found in " + city.name() + ". Open /civ gui to see worker IDs."));
            return 0;
        }
        Citizen.Profession previous = citizen.profession();
        citizen.setProfession(profession);
        manager(ctx).flushChange();
        ctx.getSource().sendSuccess(() -> Component.literal(citizen.name() + ": "
                + previous.name().toLowerCase(java.util.Locale.ROOT) + " -> "
                + profession.name().toLowerCase(java.util.Locale.ROOT)), true);
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
