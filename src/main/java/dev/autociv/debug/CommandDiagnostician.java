package dev.autociv.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Diagnostic logger that answers ONE question: why does "/civ" show
 * "Unknown or incomplete command"?
 *
 * Dumps the LIVE Brigadier dispatcher at several moments:
 *  - when RegisterCommandsEvent fires (before/after our registration);
 *  - when the server has fully started (presence of /civ in the live tree,
 *    whether the player source passes the requires() permission check).
 *
 * Every line is prefixed with [autociv-diag] and goes BOTH to stdout and to
 * latest.log (slf4j), so you can grep either.
 */
public final class CommandDiagnostician {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[autociv-diag] ";

    private CommandDiagnostician() {}

    /** Dump top-level command names of a dispatcher. */
    public static void dumpDispatcher(String when, CommandDispatcher<CommandSourceStack> dispatcher) {
        if (dispatcher == null) {
            log(when + ": dispatcher is NULL");
            return;
        }
        Set<String> roots = new TreeSet<>();
        for (CommandNode<CommandSourceStack> node : dispatcher.getRoot().getChildren()) {
            roots.add(node.getName());
        }
        log(when + ": total root commands=" + roots.size()
                + ", /civ present=" + roots.contains("civ")
                + ", /autocivtest present=" + roots.contains("autocivtest"));
        log(when + ": roots=" + roots);
    }

    /** Deep-dump the /civ subtree: every child path and its visibility result. */
    public static void dumpCivTree(CommandDispatcher<CommandSourceStack> dispatcher,
                                   CommandSourceStack testSource) {
        if (dispatcher == null) {
            log("dumpCivTree: dispatcher is NULL");
            return;
        }
        CommandNode<CommandSourceStack> civ = null;
        for (CommandNode<CommandSourceStack> node : dispatcher.getRoot().getChildren()) {
            if (node.getName().equals("civ")) {
                civ = node;
                break;
            }
        }
        if (civ == null) {
            log("/civ NOT FOUND in dispatcher root -> registration never reached this dispatcher instance.");
            return;
        }
        log("/civ found. children=" + childNames(civ));
        walk(civ, "civ", testSource, 0);
    }

    private static void walk(CommandNode<CommandSourceStack> node, String path,
                             CommandSourceStack src, int depth) {
        if (depth > 3 || src == null) return;
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            String childPath = path + " <" + child.getName() + ">";
            String req;
            try {
                req = (child.getRequirement() == null || child.getRequirement().test(src))
                        ? "VISIBLE" : "HIDDEN (requires() failed -> OP LEVEL problem?)";
            } catch (Throwable t) {
                req = "ERROR: " + t;
            }
            log("  ".repeat(depth) + "- " + childPath + " : " + req
                    + (child.getCommand() != null ? " [executable]" : ""));
            walk(child, childPath, src, depth + 1);
        }
    }

    /** Final verdict helper. opLevelOk: 1 = player passes check, 0 = fails, -1 = unknown. */
    public static void verdict(boolean registeredFired, boolean civInLiveDispatcher, int opLevelOk) {
        StringBuilder sb = new StringBuilder("VERDICT: ");
        if (!registeredFired) {
            sb.append("RegisterCommandsEvent NEVER fired -> mod constructor/listener wiring broken.");
        } else if (!civInLiveDispatcher) {
            sb.append("Event fired but /civ missing from LIVE dispatcher -> we registered into a copy, not the used one.");
        } else if (opLevelOk == 0) {
            sb.append("/civ IS registered but HIDDEN for this source -> player lacks OP level. Fix: Open to LAN with cheats, or /op Dev 2.");
        } else {
            sb.append("Everything looks correct: /civ registered AND visible. If chat still says 'unknown command', client cached old command tree -> leave world (Esc) and re-enter.");
        }
        log(sb.toString());
    }

    private static List<String> childNames(CommandNode<CommandSourceStack> node) {
        List<String> out = new ArrayList<>();
        for (CommandNode<CommandSourceStack> c : node.getChildren()) out.add(c.getName());
        return out;
    }

    private static void log(String msg) {
        String line = P + msg;
        System.out.println(line);
        LOGGER.info(line);
    }
}
