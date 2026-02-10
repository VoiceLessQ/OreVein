package com.orevein;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.architectury.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration system for OreVein mod.
 * Settings are saved to config/orevein.json
 */
public class OreVeinConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("orevein");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = Platform.getConfigFolder().resolve("orevein.json");

    // ==================== Vein Mining Settings ====================

    /** Maximum blocks per vein for ores */
    public int maxVeinSizeOres = 64;

    /** Maximum blocks per vein for logs/wood */
    public int maxVeinSizeLogs = 128;

    /** Maximum blocks per vein for leaves */
    public int maxVeinSizeLeaves = 256;

    /** Maximum blocks per vein for stone/dirt variants */
    public int maxVeinSizeStone = 64;

    /** Minimum delay between vein mining blocks (ms) - 50ms = 1 game tick */
    public int minDelayMs = 50;

    /** Maximum delay between vein mining blocks (ms) */
    public int maxDelayMs = 500;

    // ==================== Item Settings ====================

    /** Default item teleport setting for new players */
    public boolean defaultItemTeleport = false;

    // ==================== Tunnel Settings ====================

    /** XP required to unlock 1x2 tunnel mode (2 blocks per swing) */
    public int tunnel1x2UnlockXP = 500;

    /** XP required to unlock 3x3 tunnel mode (9 blocks per swing) */
    public int tunnel3x3UnlockXP = 2000;

    /** XP required to unlock 5x5 tunnel mode (25 blocks per swing - very powerful!) */
    public int tunnel5x5UnlockXP = 5000;

    /** Enable 1x2 tunnel mode */
    public boolean enable1x2Tunnel = true;

    /** Enable 3x3 tunnel mode */
    public boolean enable3x3Tunnel = true;

    /** Enable 5x5 tunnel mode */
    public boolean enable5x5Tunnel = true;

    /** Swings to skip between 1x2 tunnel activations (0 = every swing, 1 = every other, etc.) */
    public int tunnel1x2CooldownMs = 0;

    /** Swings to skip between 3x3 tunnel activations (0 = every swing, 1 = every other, etc.) */
    public int tunnel3x3CooldownMs = 1;

    /** Swings to skip between 5x5 tunnel activations (0 = every swing, 2 = every 3rd swing, etc.) */
    public int tunnel5x5CooldownMs = 2;

    // ==================== Tool Settings ====================

    /** Respect tool tier requirements */
    public boolean enforceToolTiers = true;

    /** Stop mining when tool has this much durability left */
    public int stopAtDurability = 1;

    // ==================== Block Whitelist/Blacklist ====================

    /**
     * Additional blocks to whitelist for vein mining.
     * Use full block IDs like "minecraft:glowstone" or "modname:custom_ore"
     */
    public List<String> blockWhitelist = new ArrayList<>();

    /**
     * Blocks to blacklist from vein mining (overrides whitelist and defaults).
     * Use full block IDs like "minecraft:diamond_ore"
     */
    public List<String> blockBlacklist = new ArrayList<>();

    // ==================== Tag-Based Detection ====================

    /**
     * Block tags to whitelist for vein mining.
     * Use tag names like "minecraft:coal_ores" or "c:ores"
     */
    public List<String> tagWhitelist = new ArrayList<>(List.of(
        "minecraft:coal_ores",
        "minecraft:iron_ores",
        "minecraft:gold_ores",
        "minecraft:diamond_ores",
        "minecraft:emerald_ores",
        "minecraft:copper_ores",
        "minecraft:lapis_ores",
        "minecraft:redstone_ores",
        "minecraft:logs",
        "minecraft:leaves",
        "c:ores"
    ));

    /**
     * Block tags to blacklist from vein mining.
     */
    public List<String> tagBlacklist = new ArrayList<>();

    // ==================== Load/Save ====================

    public static OreVeinConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                OreVeinConfig config = GSON.fromJson(json, OreVeinConfig.class);
                if (config != null) {
                    if (config.blockWhitelist == null) config.blockWhitelist = new ArrayList<>();
                    if (config.blockBlacklist == null) config.blockBlacklist = new ArrayList<>();
                    if (config.tagWhitelist == null) config.tagWhitelist = new ArrayList<>();
                    if (config.tagBlacklist == null) config.tagBlacklist = new ArrayList<>();
                    LOGGER.info("Loaded config from {}", CONFIG_PATH);
                    return config;
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load config: {}", e.getMessage());
            }
        }

        OreVeinConfig defaultConfig = new OreVeinConfig();
        defaultConfig.save();
        LOGGER.info("Created default config at {}", CONFIG_PATH);
        return defaultConfig;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            String json = GSON.toJson(this);
            Files.writeString(CONFIG_PATH, json);
        } catch (IOException e) {
            LOGGER.error("Failed to save config: {}", e.getMessage());
        }
    }

    public static OreVeinConfig reload() {
        return load();
    }
}
