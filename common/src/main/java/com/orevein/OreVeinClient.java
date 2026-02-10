package com.orevein;

import com.orevein.screen.CircularMenuScreen;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import io.netty.buffer.Unpooled;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OreVeinClient {

    public static final String MOD_ID = "orevein";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyMapping openMenuKey;
    private static boolean wasAltDown = false;
    private static long menuOpenTime = 0;
    private static final long MIN_MENU_OPEN_TIME = 100;

    public static void initClient() {
        LOGGER.info("OreVein Client initializing...");

        // Register networking (client side receivers)
        registerClientNetworking();

        // Register keybinding for circular menu (default: LEFT_ALT key)
        openMenuKey = new KeyMapping(
                "key.orevein.open_menu",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_ALT,
                KeyMapping.Category.GAMEPLAY
        );
        KeyMappingRegistry.register(openMenuKey);

        // Handle key press - show menu while ALT is held, close when released
        ClientTickEvent.CLIENT_POST.register(client -> {
            if (client.player == null) return;
            if (client.getWindow() == null) return;

            // Use raw GLFW state - KeyMapping.isDown() doesn't work when screen is open
            long windowHandle = client.getWindow().handle();
            boolean isAltDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS;

            // Also check right ALT
            boolean isRightAltDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
            isAltDown = isAltDown || isRightAltDown;

            boolean isMenuOpen = client.screen instanceof CircularMenuScreen;
            boolean altJustPressed = !wasAltDown && isAltDown;
            boolean altJustReleased = wasAltDown && !isAltDown;

            if (altJustPressed && !isMenuOpen) {
                LOGGER.info("Opening menu");
                client.setScreen(new CircularMenuScreen());
                menuOpenTime = System.currentTimeMillis();
            } else if (isMenuOpen) {
                long timeOpen = System.currentTimeMillis() - menuOpenTime;

                if (altJustReleased && timeOpen > MIN_MENU_OPEN_TIME) {
                    LOGGER.info("Closing menu (ALT released after {}ms)", timeOpen);
                    client.setScreen(null);
                } else if (!isAltDown && timeOpen > MIN_MENU_OPEN_TIME) {
                    LOGGER.info("Closing menu (ALT not held)");
                    client.setScreen(null);
                }
            }

            wasAltDown = isAltDown;
        });

        LOGGER.info("OreVein Client initialized!");
        LOGGER.info("Hold 'Left Alt' or 'Right Alt' to show circular menu!");
    }

    private static void registerClientNetworking() {
        // Handle sync packets from server
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, OreVeinMod.SYNC_STATE_ID, (buf, context) -> {
            int xp = buf.readInt();
            boolean itemTeleport = buf.readBoolean();
            String tunnelMode = buf.readUtf();
            boolean veinMining = buf.readBoolean();

            OreVeinClientState state = OreVeinClientState.getInstance();
            state.syncFromServer(xp, itemTeleport, tunnelMode, veinMining);
            LOGGER.info("Synced state from server: XP={}, ItemTeleport={}, TunnelMode={}, VeinMining={}",
                xp, itemTeleport, tunnelMode, veinMining);
        });
    }

    /**
     * Send a toggle request to the server
     */
    public static void sendToggleRequest(String toggleType) {
        if (NetworkManager.canServerReceive(OreVeinMod.TOGGLE_REQUEST_ID)) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
            buf.writeUtf(toggleType);
            NetworkManager.sendToServer(OreVeinMod.TOGGLE_REQUEST_ID, buf);
            LOGGER.info("Sent toggle request: {}", toggleType);
        } else {
            LOGGER.warn("Cannot send toggle request - not connected to server");
        }
    }
}
