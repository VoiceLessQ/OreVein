package com.orevein;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages persistent player data for OreVein mod.
 * Data is saved to the world folder and persists across server restarts.
 * Uses MC 1.21.11 SavedData with Codec-based serialization.
 */
public class PlayerDataManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("orevein");
    private static final String DATA_NAME = "orevein_player_data";
    private static final int SAVE_INTERVAL_SECONDS = 10;

    private final MinecraftServer server;
    private final AtomicBoolean isDirty = new AtomicBoolean(false);
    private final ScheduledExecutorService saveScheduler = Executors.newSingleThreadScheduledExecutor();

    // In-memory caches for fast access
    private final Map<UUID, Integer> playerXP = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerMode = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> itemTeleport = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> veinMiningEnabled = new ConcurrentHashMap<>();

    // Persistent storage reference
    private OreVeinSavedData storage;

    public PlayerDataManager(MinecraftServer server) {
        this.server = server;
        loadData();
        startAutoSave();
        LOGGER.info("PlayerDataManager initialized with auto-save every {}s", SAVE_INTERVAL_SECONDS);
    }

    // ==================== XP Methods ====================

    public int getPlayerXP(UUID playerId) {
        return playerXP.getOrDefault(playerId, 0);
    }

    public void setPlayerXP(UUID playerId, int xp) {
        playerXP.put(playerId, xp);
        markDirty();
    }

    public void addPlayerXP(UUID playerId, int amount) {
        int current = getPlayerXP(playerId);
        setPlayerXP(playerId, current + amount);
    }

    public boolean hasTunnelUnlocked(UUID playerId) {
        return getPlayerXP(playerId) >= OreVeinMod.getTunnelUnlockXP();
    }

    // ==================== Tunnel Mode Methods ====================

    public String getPlayerMode(UUID playerId) {
        return playerMode.getOrDefault(playerId, "OFF");
    }

    public void setPlayerMode(UUID playerId, String mode) {
        if (mode == null || mode.equals("OFF")) {
            playerMode.remove(playerId);
        } else {
            playerMode.put(playerId, mode);
        }
        markDirty();
    }

    // ==================== Item Teleport Methods ====================

    public boolean getItemTeleport(UUID playerId) {
        return itemTeleport.getOrDefault(playerId, false);
    }

    public void setItemTeleport(UUID playerId, boolean enabled) {
        itemTeleport.put(playerId, enabled);
        markDirty();
    }

    // ==================== Vein Mining Methods ====================

    public boolean isVeinMiningEnabled(UUID playerId) {
        return veinMiningEnabled.getOrDefault(playerId, true);
    }

    public void setVeinMiningEnabled(UUID playerId, boolean enabled) {
        veinMiningEnabled.put(playerId, enabled);
        markDirty();
    }

    // ==================== Death Reset ====================

    public void resetPlayerProgress(UUID playerId) {
        int lostXP = getPlayerXP(playerId);
        playerXP.remove(playerId);
        playerMode.remove(playerId);
        markDirty();
        LOGGER.info("Reset progress for player {}: lost {} XP", playerId, lostXP);
    }

    // ==================== Persistence ====================

    private void markDirty() {
        isDirty.set(true);
    }

    private void startAutoSave() {
        saveScheduler.scheduleAtFixedRate(() -> {
            try {
                if (isDirty.compareAndSet(true, false)) {
                    saveData();
                }
            } catch (Exception e) {
                LOGGER.error("Error during auto-save: {}", e.getMessage());
            }
        }, SAVE_INTERVAL_SECONDS, SAVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void loadData() {
        try {
            DimensionDataStorage dataStorage = server.overworld().getDataStorage();

            SavedDataType<OreVeinSavedData> factory = new SavedDataType<>(
                DATA_NAME,
                OreVeinSavedData::new,
                OreVeinSavedData.CODEC,
                DataFixTypes.LEVEL
            );

            this.storage = dataStorage.computeIfAbsent(factory);

            if (this.storage != null) {
                playerXP.clear();
                playerXP.putAll(storage.playerXP);

                playerMode.clear();
                playerMode.putAll(storage.playerMode);

                itemTeleport.clear();
                itemTeleport.putAll(storage.itemTeleport);

                veinMiningEnabled.clear();
                veinMiningEnabled.putAll(storage.veinMiningEnabled);

                LOGGER.info("Loaded OreVein data for {} players", playerXP.size());
                playerXP.forEach((uuid, xp) -> LOGGER.info("  - Player {}: {} XP", uuid, xp));
            } else {
                LOGGER.warn("Storage is null after computeIfAbsent!");
            }
        } catch (Exception e) {
            LOGGER.error("Error loading player data: {}", e.getMessage(), e);
        }
    }

    private void saveData() {
        try {
            if (storage != null) {
                storage.playerXP.clear();
                storage.playerXP.putAll(playerXP);

                storage.playerMode.clear();
                storage.playerMode.putAll(playerMode);

                storage.itemTeleport.clear();
                storage.itemTeleport.putAll(itemTeleport);

                storage.veinMiningEnabled.clear();
                storage.veinMiningEnabled.putAll(veinMiningEnabled);

                storage.setDirty();
                LOGGER.info("Saved OreVein data for {} players", playerXP.size());
            } else {
                LOGGER.warn("Cannot save - storage is null!");
            }
        } catch (Exception e) {
            LOGGER.error("Error saving player data: {}", e.getMessage(), e);
        }
    }

    public void shutdown() {
        saveScheduler.shutdown();
        try {
            if (!saveScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                saveScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            saveScheduler.shutdownNow();
        }

        saveData();
        LOGGER.info("PlayerDataManager shutdown complete - saved {} players", playerXP.size());
    }

    // ==================== SavedData with Codec-based serialization ====================

    public static class OreVeinSavedData extends SavedData {
        final Map<UUID, Integer> playerXP;
        final Map<UUID, String> playerMode;
        final Map<UUID, Boolean> itemTeleport;
        final Map<UUID, Boolean> veinMiningEnabled;

        public static final Codec<OreVeinSavedData> CODEC = CompoundTag.CODEC.flatXmap(
            nbt -> {
                try {
                    OreVeinSavedData data = new OreVeinSavedData();

                    nbt.getCompound("playerXP").ifPresent(xpNbt -> {
                        for (String key : xpNbt.keySet()) {
                            try {
                                UUID uuid = UUID.fromString(key);
                                data.playerXP.put(uuid, xpNbt.getIntOr(key, 0));
                            } catch (IllegalArgumentException ignored) {}
                        }
                    });

                    nbt.getCompound("playerMode").ifPresent(modeNbt -> {
                        for (String key : modeNbt.keySet()) {
                            try {
                                UUID uuid = UUID.fromString(key);
                                data.playerMode.put(uuid, modeNbt.getStringOr(key, "OFF"));
                            } catch (IllegalArgumentException ignored) {}
                        }
                    });

                    nbt.getCompound("itemTeleport").ifPresent(teleportNbt -> {
                        for (String key : teleportNbt.keySet()) {
                            try {
                                UUID uuid = UUID.fromString(key);
                                data.itemTeleport.put(uuid, teleportNbt.getBooleanOr(key, false));
                            } catch (IllegalArgumentException ignored) {}
                        }
                    });

                    nbt.getCompound("veinMiningEnabled").ifPresent(vmNbt -> {
                        for (String key : vmNbt.keySet()) {
                            try {
                                UUID uuid = UUID.fromString(key);
                                data.veinMiningEnabled.put(uuid, vmNbt.getBooleanOr(key, true));
                            } catch (IllegalArgumentException ignored) {}
                        }
                    });

                    return DataResult.success(data);
                } catch (Exception e) {
                    return DataResult.error(() -> "Failed to decode OreVein data: " + e.getMessage());
                }
            },
            data -> {
                try {
                    CompoundTag nbt = new CompoundTag();

                    CompoundTag xpNbt = new CompoundTag();
                    for (Map.Entry<UUID, Integer> entry : data.playerXP.entrySet()) {
                        xpNbt.putInt(entry.getKey().toString(), entry.getValue());
                    }
                    nbt.put("playerXP", xpNbt);

                    CompoundTag modeNbt = new CompoundTag();
                    for (Map.Entry<UUID, String> entry : data.playerMode.entrySet()) {
                        modeNbt.putString(entry.getKey().toString(), entry.getValue());
                    }
                    nbt.put("playerMode", modeNbt);

                    CompoundTag teleportNbt = new CompoundTag();
                    for (Map.Entry<UUID, Boolean> entry : data.itemTeleport.entrySet()) {
                        teleportNbt.putBoolean(entry.getKey().toString(), entry.getValue());
                    }
                    nbt.put("itemTeleport", teleportNbt);

                    CompoundTag vmNbt = new CompoundTag();
                    for (Map.Entry<UUID, Boolean> entry : data.veinMiningEnabled.entrySet()) {
                        vmNbt.putBoolean(entry.getKey().toString(), entry.getValue());
                    }
                    nbt.put("veinMiningEnabled", vmNbt);

                    return DataResult.success(nbt);
                } catch (Exception e) {
                    return DataResult.error(() -> "Failed to encode OreVein data: " + e.getMessage());
                }
            }
        );

        public OreVeinSavedData(Map<UUID, Integer> playerXP, Map<UUID, String> playerMode,
                                Map<UUID, Boolean> itemTeleport, Map<UUID, Boolean> veinMiningEnabled) {
            this.playerXP = new HashMap<>(playerXP);
            this.playerMode = new HashMap<>(playerMode);
            this.itemTeleport = new HashMap<>(itemTeleport);
            this.veinMiningEnabled = new HashMap<>(veinMiningEnabled);
        }

        public OreVeinSavedData() {
            this.playerXP = new HashMap<>();
            this.playerMode = new HashMap<>();
            this.itemTeleport = new HashMap<>();
            this.veinMiningEnabled = new HashMap<>();
        }
    }
}
