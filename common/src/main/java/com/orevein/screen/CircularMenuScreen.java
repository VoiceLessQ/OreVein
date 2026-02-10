package com.orevein.screen;

import com.orevein.OreVeinClientState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

public class CircularMenuScreen extends Screen {

    private static final int MENU_RADIUS = 100;
    private static final int ITEM_RADIUS = 20;
    private static final int ITEM_RADIUS_HOVERED = 24;
    private static final int CENTER_RADIUS = 25;
    private static final int ITEM_COUNT = 6;

    // Pre-calculated positions (set in init())
    private int centerX, centerY;
    private final int[] itemX = new int[ITEM_COUNT];
    private final int[] itemY = new int[ITEM_COUNT];
    private final double[] cosAngles = new double[ITEM_COUNT];
    private final double[] sinAngles = new double[ITEM_COUNT];

    // Cached ItemStacks (created once)
    private static final ItemStack ICON_VEIN_MINING = new ItemStack(Items.DIAMOND_PICKAXE);
    private static final ItemStack ICON_AUTO_COLLECT = new ItemStack(Items.ENDER_CHEST);
    private static final ItemStack ICON_TUNNEL_1X2 = new ItemStack(Items.IRON_PICKAXE);
    private static final ItemStack ICON_TUNNEL_3X3 = new ItemStack(Items.GOLDEN_PICKAXE);
    private static final ItemStack ICON_TUNNEL_5X5 = new ItemStack(Items.NETHERITE_PICKAXE);
    private static final ItemStack ICON_XP = new ItemStack(Items.EXPERIENCE_BOTTLE);
    private static final ItemStack[] ICONS = {
        ICON_VEIN_MINING, ICON_AUTO_COLLECT, ICON_TUNNEL_1X2,
        ICON_TUNNEL_3X3, ICON_TUNNEL_5X5, ICON_XP
    };

    private int hoveredIndex = -1;
    private final OreVeinClientState state = OreVeinClientState.getInstance();

    // Reusable string builders to reduce allocations
    private final StringBuilder labelBuilder = new StringBuilder(32);

    public CircularMenuScreen() {
        super(Component.literal("OreVein Menu"));

        // Pre-calculate angles (these never change)
        for (int i = 0; i < ITEM_COUNT; i++) {
            double angle = (Math.PI * 2 * i) / ITEM_COUNT - Math.PI / 2;
            cosAngles[i] = Math.cos(angle);
            sinAngles[i] = Math.sin(angle);
        }
    }

    @Override
    protected void init() {
        super.init();

        // Calculate center and item positions once
        centerX = this.width / 2;
        centerY = this.height / 2;

        for (int i = 0; i < ITEM_COUNT; i++) {
            itemX[i] = centerX + (int)(cosAngles[i] * MENU_RADIUS);
            itemY[i] = centerY + (int)(sinAngles[i] * MENU_RADIUS);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
        // Semi-transparent dark background (single draw call)
        guiGraphics.fill(0, 0, this.width, this.height, 0x90000000);

        // Calculate which item is hovered (optimized - no sqrt)
        hoveredIndex = getHoveredIndex(mouseX, mouseY);

        // Draw connecting lines first (behind circles)
        for (int i = 0; i < ITEM_COUNT; i++) {
            int stateColor = getStateColor(i);
            int lineColor = (i == hoveredIndex) ? (stateColor & 0x00FFFFFF) | 0xAA000000 : 0x30FFFFFF;
            drawLineOptimized(guiGraphics, centerX, centerY, itemX[i], itemY[i], lineColor);
        }

        // Draw center circle
        fillCircleOptimized(guiGraphics, centerX, centerY, CENTER_RADIUS, 0xFF1a1a2e, 0xFF4a4a6a);
        guiGraphics.drawCenteredString(this.font, "OreVein", centerX, centerY - 4, 0xFFFFFFFF);

        // Draw menu item circles
        for (int i = 0; i < ITEM_COUNT; i++) {
            boolean isHovered = (i == hoveredIndex);
            int stateColor = getStateColor(i);

            int bgColor = isHovered ? darken(stateColor, 0.3f) : 0xFF2a2a4a;
            int outlineColor = isHovered ? stateColor : 0xFF4a4a6a;
            int radius = isHovered ? ITEM_RADIUS_HOVERED : ITEM_RADIUS;

            fillCircleOptimized(guiGraphics, itemX[i], itemY[i], radius, bgColor, outlineColor);

            // Draw cached item icon
            int iconOffset = 8;
            guiGraphics.renderItem(ICONS[i], itemX[i] - iconOffset, itemY[i] - iconOffset);
        }

        // Draw label for hovered item
        if (hoveredIndex >= 0) {
            renderHoveredLabel(guiGraphics, hoveredIndex);
        }

        // Draw hint text
        guiGraphics.drawCenteredString(this.font, "Release ALT to close | Click to toggle", centerX, this.height - 25, 0xFF666666);
    }

    private void renderHoveredLabel(GuiGraphics guiGraphics, int index) {
        String label = getLabel(index);
        String description = getDescription(index);
        int stateColor = getStateColor(index);

        int labelWidth = this.font.width(label);
        int descWidth = this.font.width(description);
        int maxWidth = Math.max(labelWidth, descWidth);

        int labelX = centerX - maxWidth / 2 - 8;
        int labelY = centerY + 55;

        // Background box (2 draw calls instead of complex shapes)
        guiGraphics.fill(labelX - 4, labelY - 4, labelX + maxWidth + 12, labelY + 28, 0xE0000000);
        guiGraphics.fill(labelX - 4, labelY - 4, labelX - 2, labelY + 28, stateColor);

        guiGraphics.drawCenteredString(this.font, label, centerX, labelY, stateColor);
        guiGraphics.drawCenteredString(this.font, description, centerX, labelY + 12, 0xFFAAAAAA);
    }

    // Get label based on current state (no object allocation)
    private String getLabel(int index) {
        return switch (index) {
            case 0 -> state.isVeinMiningEnabled() ? "Vein Mining: ON" : "Vein Mining: OFF";
            case 1 -> state.isItemTeleportEnabled() ? "Auto-Collect: ON" : "Auto-Collect: OFF";
            case 2 -> state.isTunnel1x2Enabled() ? "1x2 Tunnel: ON" : "1x2 Tunnel: OFF";
            case 3 -> state.isTunnel3x3Enabled() ? "3x3 Tunnel: ON" : "3x3 Tunnel: OFF";
            case 4 -> state.isTunnel5x5Enabled() ? "5x5 Tunnel: ON" : "5x5 Tunnel: OFF";
            case 5 -> "XP: " + state.getMiningXP() + "/" + OreVeinClientState.TUNNEL_5X5_XP;
            default -> "";
        };
    }

    // Get description based on current state
    private String getDescription(int index) {
        return switch (index) {
            case 0 -> state.isVeinMiningEnabled() ? "Click to disable" : "Click to enable";
            case 1 -> state.isItemTeleportEnabled() ? "Items go to inventory" : "Items drop on ground";
            case 2 -> state.isTunnel1x2Unlocked() ? "Mine 1x2 vertical tunnel" : "Requires " + OreVeinClientState.TUNNEL_1X2_XP + " XP (" + state.getMiningXP() + ")";
            case 3 -> state.isTunnel3x3Unlocked() ? "Mine 3x3 based on direction" : "Requires " + OreVeinClientState.TUNNEL_3X3_XP + " XP (" + state.getMiningXP() + ")";
            case 4 -> state.isTunnel5x5Unlocked() ? "Mine 5x5 large tunnel" : "Requires " + OreVeinClientState.TUNNEL_5X5_XP + " XP (" + state.getMiningXP() + ")";
            case 5 -> state.isTunnel5x5Unlocked() ? "All tunnel modes unlocked!" : "Mine with pickaxe to earn XP";
            default -> "";
        };
    }

    // Get state color for item
    private int getStateColor(int index) {
        return switch (index) {
            case 0 -> state.isVeinMiningEnabled() ? 0xFF00AA00 : 0xFF666666;
            case 1 -> state.isItemTeleportEnabled() ? 0xFF00AA00 : 0xFF666666;
            case 2 -> !state.isTunnel1x2Unlocked() ? 0xFF444444 : (state.isTunnel1x2Enabled() ? 0xFF00AAAA : 0xFF666666);
            case 3 -> !state.isTunnel3x3Unlocked() ? 0xFF444444 : (state.isTunnel3x3Enabled() ? 0xFFAA00AA : 0xFF666666);
            case 4 -> !state.isTunnel5x5Unlocked() ? 0xFF444444 : (state.isTunnel5x5Enabled() ? 0xFFFF5500 : 0xFF666666);
            case 5 -> state.isTunnel5x5Unlocked() ? 0xFF00FF00 : 0xFFAAAA00;
            default -> 0xFF666666;
        };
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        hoveredIndex = getHoveredIndex((int) event.x(), (int) event.y());
        if (hoveredIndex >= 0 && hoveredIndex < ITEM_COUNT) {
            handleMenuAction(hoveredIndex);
        }
        return true;
    }

    private void handleMenuAction(int index) {
        if (minecraft == null || minecraft.player == null) return;

        switch (index) {
            case 0 -> { // Vein Mining Toggle
                state.toggleVeinMining();
                String msg = state.isVeinMiningEnabled() ? "Vein Mining enabled" : "Vein Mining disabled";
                minecraft.player.displayClientMessage(Component.literal(msg), true);
            }
            case 1 -> { // Item Teleport
                state.toggleItemTeleport();
                String msg = state.isItemTeleportEnabled() ?
                    "Items will teleport to inventory" :
                    "Items will drop on ground";
                minecraft.player.displayClientMessage(Component.literal(msg), true);
            }
            case 2 -> { // 1x2 Tunnel
                if (!state.isTunnel1x2Unlocked()) {
                    minecraft.player.displayClientMessage(
                        Component.literal("Need " + OreVeinClientState.TUNNEL_1X2_XP + " Mining XP! (Current: " + state.getMiningXP() + ")"), true);
                    return;
                }
                boolean newState = !state.isTunnel1x2Enabled();
                state.setTunnel1x2(newState);
                String msg = newState ? "1x2 Tunnel Mode enabled" : "1x2 Tunnel Mode disabled";
                minecraft.player.displayClientMessage(Component.literal(msg), true);
            }
            case 3 -> { // 3x3 Tunnel
                if (!state.isTunnel3x3Unlocked()) {
                    minecraft.player.displayClientMessage(
                        Component.literal("Need " + OreVeinClientState.TUNNEL_3X3_XP + " Mining XP! (Current: " + state.getMiningXP() + ")"), true);
                    return;
                }
                boolean newState3x3 = !state.isTunnel3x3Enabled();
                state.setTunnel3x3(newState3x3);
                String msg3x3 = newState3x3 ? "3x3 Tunnel Mode enabled" : "3x3 Tunnel Mode disabled";
                minecraft.player.displayClientMessage(Component.literal(msg3x3), true);
            }
            case 4 -> { // 5x5 Tunnel
                if (!state.isTunnel5x5Unlocked()) {
                    minecraft.player.displayClientMessage(
                        Component.literal("Need " + OreVeinClientState.TUNNEL_5X5_XP + " Mining XP! (Current: " + state.getMiningXP() + ")"), true);
                    return;
                }
                boolean newState5x5 = !state.isTunnel5x5Enabled();
                state.setTunnel5x5(newState5x5);
                String msg5x5 = newState5x5 ? "5x5 Tunnel Mode enabled" : "5x5 Tunnel Mode disabled";
                minecraft.player.displayClientMessage(Component.literal(msg5x5), true);
            }
            case 5 -> { // Show XP
                int xp = state.getMiningXP();
                String unlockStatus;
                if (state.isTunnel5x5Unlocked()) {
                    unlockStatus = "All tunnel modes UNLOCKED!";
                } else if (state.isTunnel1x2Unlocked()) {
                    unlockStatus = "5x5 needs " + (OreVeinClientState.TUNNEL_5X5_XP - xp) + " more XP";
                } else {
                    unlockStatus = "Need " + (OreVeinClientState.TUNNEL_1X2_XP - xp) + " more XP for tunnels";
                }
                minecraft.player.displayClientMessage(
                    Component.literal("Mining XP: " + xp + " | " + unlockStatus), false);
            }
        }
    }

    // Optimized hover detection using squared distance (no Math.sqrt)
    private int getHoveredIndex(int mouseX, int mouseY) {
        int hoverRadiusSq = ITEM_RADIUS_HOVERED * ITEM_RADIUS_HOVERED;

        for (int i = 0; i < ITEM_COUNT; i++) {
            int dx = mouseX - itemX[i];
            int dy = mouseY - itemY[i];
            int distSq = dx * dx + dy * dy;

            if (distSq <= hoverRadiusSq) {
                return i;
            }
        }
        return -1;
    }

    public int getHoveredItem() {
        return hoveredIndex;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    private int darken(int color, float amount) {
        int a = (color >> 24) & 0xFF;
        int r = (int)(((color >> 16) & 0xFF) * (1 - amount));
        int g = (int)(((color >> 8) & 0xFF) * (1 - amount));
        int b = (int)((color & 0xFF) * (1 - amount));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // Optimized circle fill - uses horizontal spans instead of pixel-by-pixel
    private void fillCircleOptimized(GuiGraphics guiGraphics, int cx, int cy, int radius, int fillColor, int outlineColor) {
        int rSq = radius * radius;
        for (int y = -radius; y <= radius; y++) {
            int ySq = y * y;
            int xSpan = (int) Math.sqrt(rSq - ySq);
            if (xSpan > 0) {
                guiGraphics.fill(cx - xSpan, cy + y, cx + xSpan + 1, cy + y + 1, fillColor);
            }
        }

        drawCircleOutlineOptimized(guiGraphics, cx, cy, radius, outlineColor);
    }

    // Optimized circle outline using midpoint circle algorithm with 8-way symmetry
    private void drawCircleOutlineOptimized(GuiGraphics guiGraphics, int cx, int cy, int radius, int color) {
        int x = radius;
        int y = 0;
        int err = 1 - radius;

        while (x >= y) {
            guiGraphics.fill(cx + x, cy + y, cx + x + 1, cy + y + 1, color);
            guiGraphics.fill(cx - x, cy + y, cx - x + 1, cy + y + 1, color);
            guiGraphics.fill(cx + x, cy - y, cx + x + 1, cy - y + 1, color);
            guiGraphics.fill(cx - x, cy - y, cx - x + 1, cy - y + 1, color);
            guiGraphics.fill(cx + y, cy + x, cx + y + 1, cy + x + 1, color);
            guiGraphics.fill(cx - y, cy + x, cx - y + 1, cy + x + 1, color);
            guiGraphics.fill(cx + y, cy - x, cx + y + 1, cy - x + 1, color);
            guiGraphics.fill(cx - y, cy - x, cx - y + 1, cy - x + 1, color);

            y++;
            if (err < 0) {
                err += 2 * y + 1;
            } else {
                x--;
                err += 2 * (y - x + 1);
            }
        }
    }

    // Optimized line drawing - uses larger rectangles where possible
    private void drawLineOptimized(GuiGraphics guiGraphics, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);

        if (dy <= 2 && dx > dy) {
            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            int avgY = (y1 + y2) / 2;
            guiGraphics.fill(minX, avgY, maxX + 1, avgY + 1, color);
            return;
        }
        if (dx <= 2 && dy > dx) {
            int minY = Math.min(y1, y2);
            int maxY = Math.max(y1, y2);
            int avgX = (x1 + x2) / 2;
            guiGraphics.fill(avgX, minY, avgX + 1, maxY + 1, color);
            return;
        }

        // Bresenham for diagonal lines
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;

        while (true) {
            guiGraphics.fill(x1, y1, x1 + 1, y1 + 1, color);

            if (x1 == x2 && y1 == y2) break;

            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x1 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y1 += sy;
            }
        }
    }
}
