package dev.autociv.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.commands.CommandSourceStack;

/**
 * Diagnostic command. Registered BEFORE {@link CivCommand} so we can tell
 * whether the problem is event wiring (then this also fails) or something
 * inside CivCommand itself (then this works but /civ doesn't).
 */
public final class TestCommand {

    private TestCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // requires(s -> true): no permission level needed, works even without cheats.
        dispatcher.register(
                net.minecraft.commands.Commands.literal("autocivtest")
                        .requires(s -> true)
                        .executes(ctx -> {
                            ctx.getSource().sendSystemMessage(
                                    Component.literal("AutoCiv test command works!")
                                            .withStyle(ChatFormatting.GREEN));
                            return 1;
                        }));
        System.out.println("[autociv] /autocivtest registered");
    }
}
