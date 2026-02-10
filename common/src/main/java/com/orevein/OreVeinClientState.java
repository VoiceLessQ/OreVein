package com.orevein;

/**
 * Client-side state for OreVein mod.
 * Manages UI state and syncs with server.
 */
public class OreVeinClientState {
    private static OreVeinClientState INSTANCE;

    // Client-side state (synced from server)
    private boolean veinMiningEnabled = true;
    private boolean itemTeleportEnabled = false;
    private boolean tunnel1x2Enabled = false;
    private boolean tunnel3x3Enabled = false;
    private boolean tunnel5x5Enabled = false;
    private int miningXP = 0;

    public static OreVeinClientState getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new OreVeinClientState();
        }
        return INSTANCE;
    }

    // XP thresholds (should match server config defaults)
    public static final int TUNNEL_1X2_XP = 500;
    public static final int TUNNEL_3X3_XP = 2000;
    public static final int TUNNEL_5X5_XP = 5000;
    // Getters
    public boolean isVeinMiningEnabled() { return veinMiningEnabled; }
    public boolean isItemTeleportEnabled() { return itemTeleportEnabled; }
    public boolean isTunnel1x2Enabled() { return tunnel1x2Enabled; }
    public boolean isTunnel3x3Enabled() { return tunnel3x3Enabled; }
    public boolean isTunnel5x5Enabled() { return tunnel5x5Enabled; }
    public int getMiningXP() { return miningXP; }

    // Separate unlock checks for each tunnel mode
    public boolean isTunnel1x2Unlocked() { return miningXP >= TUNNEL_1X2_XP; }
    public boolean isTunnel3x3Unlocked() { return miningXP >= TUNNEL_3X3_XP; }
    public boolean isTunnel5x5Unlocked() { return miningXP >= TUNNEL_5X5_XP; }

    // Backwards compatibility
    public boolean isTunnelUnlocked() { return miningXP >= TUNNEL_1X2_XP; }

    // Toggles - send packets to server
    public void toggleVeinMining() {
        veinMiningEnabled = !veinMiningEnabled;
        OreVeinClient.sendToggleRequest("vein_mining");
    }

    public void toggleItemTeleport() {
        itemTeleportEnabled = !itemTeleportEnabled;
        OreVeinClient.sendToggleRequest("item_teleport");
    }

    public void setTunnel1x2(boolean enabled) {
        if (enabled) {
            tunnel1x2Enabled = true;
            tunnel3x3Enabled = false;
            tunnel5x5Enabled = false;
        } else {
            tunnel1x2Enabled = false;
        }
        OreVeinClient.sendToggleRequest("tunnel_1x2");
    }

    public void setTunnel3x3(boolean enabled) {
        if (enabled) {
            tunnel3x3Enabled = true;
            tunnel1x2Enabled = false;
            tunnel5x5Enabled = false;
        } else {
            tunnel3x3Enabled = false;
        }
        OreVeinClient.sendToggleRequest("tunnel_3x3");
    }

    public void setTunnel5x5(boolean enabled) {
        if (enabled) {
            tunnel5x5Enabled = true;
            tunnel1x2Enabled = false;
            tunnel3x3Enabled = false;
        } else {
            tunnel5x5Enabled = false;
        }
        OreVeinClient.sendToggleRequest("tunnel_5x5");
    }

    /**
     * Called when receiving sync packet from server.
     * This is the authoritative state.
     */
    public void syncFromServer(int xp, boolean itemTeleport, String tunnelMode, boolean veinMining) {
        this.miningXP = xp;
        this.itemTeleportEnabled = itemTeleport;
        this.veinMiningEnabled = veinMining;

        this.tunnel1x2Enabled = "MODE_1X2".equals(tunnelMode);
        this.tunnel3x3Enabled = "MODE_3X3".equals(tunnelMode);
        this.tunnel5x5Enabled = "MODE_5X5".equals(tunnelMode);
    }

    public void setMiningXP(int xp) {
        this.miningXP = xp;
    }
}
