package dev.autociv.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Client-only keybind glue, loaded reflectively so dedicated servers never load GUI classes. */
public final class CivDebugClient {

    private static final KeyMapping OPEN_SCREEN = new KeyMapping("key.autociv.debug_screen",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.autociv");

    private CivDebugClient() { }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(CivDebugClient::registerKeyMapping);
        NeoForge.EVENT_BUS.addListener(CivDebugClient::onClientTick);
    }

    private static void registerKeyMapping(RegisterKeyMappingsEvent event) {
        event.register(OPEN_SCREEN);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (!OPEN_SCREEN.consumeClick()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.connection != null) {
            minecraft.player.connection.sendCommand("civ gui");
        }
    }
}
