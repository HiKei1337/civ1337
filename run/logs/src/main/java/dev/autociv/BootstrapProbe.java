package dev.autociv;

/**
 * Pure-Java probe class with NO Minecraft/NeoForge imports.
 * It is loaded reflectively from the mod constructor. If this prints,
 * our entire package is on the runtime classpath and the problem can
 * only be in event wiring - not in classloading.
 */
public final class BootstrapProbe {

    static {
        System.out.println("[autociv] BOOTSTRAP PROBE CLASS LOADED (package dev.autociv IS on classpath)");
    }

    private BootstrapProbe() {
    }

    public static String ping() {
        return "probe-ok";
    }
}
