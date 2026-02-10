package com.orevein;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.BlockEvent;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side OreVein mod - handles XP, vein mining, tunnel modes
 * Data is persistent and saved to disk. XP resets on death.
 */
public class OreVeinMod {
    public static final String MOD_ID = "orevein";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Config (loaded on init)
    private static OreVeinConfig config;

    // Persistent data manager (initialized when server starts)
    private static PlayerDataManager dataManager;

    public static int getTunnel1x2UnlockXP() {
        return config != null ? config.tunnel1x2UnlockXP : 500;
    }

    public static int getTunnel3x3UnlockXP() {
        return config != null ? config.tunnel3x3UnlockXP : 2000;
    }

    public static int getTunnel5x5UnlockXP() {
        return config != null ? config.tunnel5x5UnlockXP : 5000;
    }

    // For backwards compatibility - returns lowest unlock XP
    public static int getTunnelUnlockXP() {
        return getTunnel1x2UnlockXP();
    }

    // Packet IDs
    public static final Identifier SYNC_STATE_ID = Identifier.fromNamespaceAndPath(MOD_ID, "sync_state");
    public static final Identifier TOGGLE_REQUEST_ID = Identifier.fromNamespaceAndPath(MOD_ID, "toggle_request");

    public static void init() {
        LOGGER.info("OreVein Server initializing...");

        // Load config
        config = OreVeinConfig.load();
        VeinMiner.setConfig(config);
        LOGGER.info("Config loaded - tunnel XP: 1x2={}, 3x3={}, 5x5={}, maxVeinSizeOres={}",
            config.tunnel1x2UnlockXP, config.tunnel3x3UnlockXP, config.tunnel5x5UnlockXP, config.maxVeinSizeOres);

        // Initialize data manager when server starts
        LifecycleEvent.SERVER_STARTED.register(server -> {
            dataManager = new PlayerDataManager(server);
            LOGGER.info("PlayerDataManager initialized - data will persist!");
        });

        // Shutdown data manager when server stops
        LifecycleEvent.SERVER_STOPPED.register(server -> {
            if (dataManager != null) {
                dataManager.shutdown();
                dataManager = null;
            }
        });

        // Register block break event (Architectury fires this BEFORE the block breaks)
        BlockEvent.BREAK.register((world, pos, state, player, xp) -> {
            if (world.isClientSide()) return EventResult.pass();
            if (!(player instanceof ServerPlayer serverPlayer)) return EventResult.pass();
            if (dataManager == null) return EventResult.pass();

            return handleBlockBreak(world, pos, state, serverPlayer);
        });

        // Register death event - reset XP on death
        EntityEvent.LIVING_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayer player && dataManager != null) {
                UUID id = player.getUUID();
                int lostXP = dataManager.getPlayerXP(id);

                if (lostXP > 0) {
                    dataManager.resetPlayerProgress(id);
                    player.displayClientMessage(Component.literal("\u00A7c[OreVein] Lost " + lostXP + " Mining XP on death!"), false);
                }
            }
            return EventResult.pass();
        });

        // Register commands
        CommandRegistrationEvent.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });

        // Sync state when player joins
        PlayerEvent.PLAYER_JOIN.register(player -> {
            syncStateToClient(player);
        });

        // Register networking
        registerNetworking();

        LOGGER.info("OreVein Server initialized!");
    }

    /**
     * Handle block break event.
     * In Architectury, BlockEvent.BREAK fires before the block is broken.
     * We use this single event to handle both interception (item teleport) and post-processing (XP/mining).
     */
    private static EventResult handleBlockBreak(Level world, BlockPos pos, BlockState state, ServerPlayer player) {
        UUID id = player.getUUID();

        // Check if item teleport is enabled and this is a vein/tunnel scenario
        boolean itemTeleport = dataManager.getItemTeleport(id);
        String mode = dataManager.getPlayerMode(id);

        boolean isTunnelMiningScenario = false;
        if (!mode.equals("OFF") && isUsingNetheritePick(player)) {
            int xp = dataManager.getPlayerXP(id);
            isTunnelMiningScenario = switch (mode) {
                case "MODE_1X2" -> xp >= getTunnel1x2UnlockXP();
                case "MODE_3X3" -> xp >= getTunnel3x3UnlockXP();
                case "MODE_5X5" -> xp >= getTunnel5x5UnlockXP();
                default -> false;
            };
        }
        boolean isVeinMining = mode.equals("OFF") && player.isShiftKeyDown()
                && dataManager.isVeinMiningEnabled(id) && VeinMiner.isVeinMinable(state);

        if (itemTeleport && (isVeinMining || isTunnelMiningScenario)) {
            // Intercept: handle drops ourselves with teleport
            ServerLevel serverLevel = (ServerLevel) world;
            List<ItemStack> drops = Block.getDrops(state, serverLevel, pos, world.getBlockEntity(pos), player, player.getMainHandItem());

            for (ItemStack drop : drops) {
                if (!drop.isEmpty()) {
                    if (!player.getInventory().add(drop)) {
                        player.drop(drop, false);
                    }
                }
            }

            // Break block without drops (we already handled them)
            world.destroyBlock(pos, false);

            // Apply durability to tool
            ItemStack tool = player.getMainHandItem();
            if (tool.isDamageableItem()) {
                tool.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
            }

            // Play break effects
            serverLevel.levelEvent(2001, pos, Block.getId(state));

            // Trigger XP and vein/tunnel mining
            handleXPAndMining(serverLevel, player, pos, state);

            return EventResult.interruptFalse(); // Cancel vanilla break
        }

        // Normal flow: let vanilla break the block, but handle XP and trigger mining on other blocks
        handleXPAndMining((ServerLevel) world, player, pos, state);
        return EventResult.pass();
    }

    private static void registerCommands(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        // /miningxp - show current XP
        dispatcher.register(Commands.literal("miningxp")
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                if (dataManager != null) {
                    int xp = dataManager.getPlayerXP(player.getUUID());
                    int unlockXP = getTunnelUnlockXP();
                    boolean unlocked = xp >= unlockXP;
                    String mode = dataManager.getPlayerMode(player.getUUID());
                    player.displayClientMessage(Component.literal(
                        "\u00A7a[OreVein] XP: " + xp + "/" + unlockXP + " | Tunnel: " + (unlocked ? mode : "LOCKED")), false);
                    return 1;
                }
                return 0;
            }));

        // /orevein commands
        dispatcher.register(Commands.literal("orevein")
            // /orevein setxp <amount> (OP only - for testing)
            .then(Commands.literal("setxp")
                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();

                        // OP check using permission level
                        if (!context.getSource().permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))) {
                            player.displayClientMessage(Component.literal("\u00A7c[OreVein] You need OP permissions to set XP"), false);
                            return 0;
                        }

                        if (dataManager != null) {
                            int amount = IntegerArgumentType.getInteger(context, "amount");
                            UUID id = player.getUUID();
                            dataManager.setPlayerXP(id, amount);
                            syncStateToClient(player);
                            player.displayClientMessage(Component.literal("\u00A7a[OreVein] XP set to " + amount), false);
                            return 1;
                        }
                        return 0;
                    })))
            // /orevein check - check if block you're looking at is vein-mineable
            .then(Commands.literal("check")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();

                    HitResult hit = player.pick(5.0, 0.0f, false);
                    if (hit.getType() != HitResult.Type.BLOCK) {
                        player.displayClientMessage(Component.literal("\u00A7c[OreVein] Look at a block to check it"), false);
                        return 0;
                    }

                    BlockPos bPos = ((BlockHitResult) hit).getBlockPos();
                    BlockState bState = player.level().getBlockState(bPos);
                    String blockId = BuiltInRegistries.BLOCK.getKey(bState.getBlock()).toString();
                    boolean canVeinMine = VeinMiner.isVeinMinable(bState);

                    if (canVeinMine) {
                        player.displayClientMessage(Component.literal("\u00A7a[OreVein] \u00A7e" + blockId + "\u00A7a CAN be vein-mined"), false);
                    } else {
                        player.displayClientMessage(Component.literal("\u00A7c[OreVein] \u00A7e" + blockId + "\u00A7c CANNOT be vein-mined"), false);
                    }

                    // Show why
                    if (config.blockBlacklist.contains(blockId)) {
                        player.displayClientMessage(Component.literal("  \u00A77(in blacklist)"), false);
                    } else if (config.blockWhitelist.contains(blockId)) {
                        player.displayClientMessage(Component.literal("  \u00A77(in whitelist)"), false);
                    }

                    return 1;
                }))
            // /orevein add - add block you're looking at to whitelist
            .then(Commands.literal("add")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();

                    HitResult hit = player.pick(5.0, 0.0f, false);
                    if (hit.getType() != HitResult.Type.BLOCK) {
                        player.displayClientMessage(Component.literal("\u00A7c[OreVein] Look at a block to add it"), false);
                        return 0;
                    }

                    BlockPos bPos = ((BlockHitResult) hit).getBlockPos();
                    String blockId = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(bPos).getBlock()).toString();

                    if (config.blockBlacklist.contains(blockId)) {
                        config.blockBlacklist.remove(blockId);
                        player.displayClientMessage(Component.literal("\u00A7a[OreVein] Removed \u00A7e" + blockId + "\u00A7a from blacklist"), false);
                    }

                    if (!config.blockWhitelist.contains(blockId)) {
                        config.blockWhitelist.add(blockId);
                        config.save();
                        player.displayClientMessage(Component.literal("\u00A7a[OreVein] Added \u00A7e" + blockId + "\u00A7a to whitelist"), false);
                    } else {
                        player.displayClientMessage(Component.literal("\u00A7e[OreVein] " + blockId + " is already whitelisted"), false);
                    }
                    return 1;
                }))
            // /orevein remove - remove block you're looking at (add to blacklist)
            .then(Commands.literal("remove")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();

                    HitResult hit = player.pick(5.0, 0.0f, false);
                    if (hit.getType() != HitResult.Type.BLOCK) {
                        player.displayClientMessage(Component.literal("\u00A7c[OreVein] Look at a block to remove it"), false);
                        return 0;
                    }

                    BlockPos bPos = ((BlockHitResult) hit).getBlockPos();
                    String blockId = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(bPos).getBlock()).toString();

                    // Remove from whitelist if present
                    if (config.blockWhitelist.contains(blockId)) {
                        config.blockWhitelist.remove(blockId);
                    }

                    // Add to blacklist
                    if (!config.blockBlacklist.contains(blockId)) {
                        config.blockBlacklist.add(blockId);
                        config.save();
                        player.displayClientMessage(Component.literal("\u00A7a[OreVein] Added \u00A7e" + blockId + "\u00A7a to blacklist"), false);
                    } else {
                        player.displayClientMessage(Component.literal("\u00A7e[OreVein] " + blockId + " is already blacklisted"), false);
                    }
                    return 1;
                }))
            // /orevein whitelist add/remove [block]
            .then(Commands.literal("whitelist")
                .then(Commands.literal("add")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();

                        HitResult hit = player.pick(5.0, 0.0f, false);
                        if (hit.getType() != HitResult.Type.BLOCK) {
                            player.displayClientMessage(Component.literal("\u00A7c[OreVein] Look at a block to add it to whitelist"), false);
                            return 0;
                        }

                        BlockPos bPos = ((BlockHitResult) hit).getBlockPos();
                        String blockId = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(bPos).getBlock()).toString();

                        config.blockBlacklist.remove(blockId);
                        if (!config.blockWhitelist.contains(blockId)) {
                            config.blockWhitelist.add(blockId);
                            config.save();
                            player.displayClientMessage(Component.literal("\u00A7a[OreVein] Added \u00A7e" + blockId + "\u00A7a to whitelist"), false);
                        } else {
                            player.displayClientMessage(Component.literal("\u00A7e[OreVein] " + blockId + " is already whitelisted"), false);
                        }
                        return 1;
                    })
                    .then(Commands.argument("block", StringArgumentType.string())
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            String blockId = StringArgumentType.getString(context, "block");

                            config.blockBlacklist.remove(blockId);
                            if (!config.blockWhitelist.contains(blockId)) {
                                config.blockWhitelist.add(blockId);
                                config.save();
                                player.displayClientMessage(Component.literal("\u00A7a[OreVein] Added \u00A7e" + blockId + "\u00A7a to whitelist"), false);
                            } else {
                                player.displayClientMessage(Component.literal("\u00A7e[OreVein] " + blockId + " is already whitelisted"), false);
                            }
                            return 1;
                        })))
                .then(Commands.literal("remove")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();

                        HitResult hit = player.pick(5.0, 0.0f, false);
                        if (hit.getType() != HitResult.Type.BLOCK) {
                            player.displayClientMessage(Component.literal("\u00A7c[OreVein] Look at a block to remove it from whitelist"), false);
                            return 0;
                        }

                        BlockPos bPos = ((BlockHitResult) hit).getBlockPos();
                        String blockId = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(bPos).getBlock()).toString();

                        if (config.blockWhitelist.remove(blockId)) {
                            config.save();
                            player.displayClientMessage(Component.literal("\u00A7a[OreVein] Removed \u00A7e" + blockId + "\u00A7a from whitelist"), false);
                        } else {
                            player.displayClientMessage(Component.literal("\u00A7e[OreVein] " + blockId + " was not in whitelist"), false);
                        }
                        return 1;
                    })
                    .then(Commands.argument("block", StringArgumentType.string())
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            String blockId = StringArgumentType.getString(context, "block");

                            if (config.blockWhitelist.remove(blockId)) {
                                config.save();
                                player.displayClientMessage(Component.literal("\u00A7a[OreVein] Removed \u00A7e" + blockId + "\u00A7a from whitelist"), false);
                            } else {
                                player.displayClientMessage(Component.literal("\u00A7e[OreVein] " + blockId + " was not in whitelist"), false);
                            }
                            return 1;
                        }))))
            // /orevein blacklist add/remove [block]
            .then(Commands.literal("blacklist")
                .then(Commands.literal("add")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();

                        HitResult hit = player.pick(5.0, 0.0f, false);
                        if (hit.getType() != HitResult.Type.BLOCK) {
                            player.displayClientMessage(Component.literal("\u00A7c[OreVein] Look at a block to add it to blacklist"), false);
                            return 0;
                        }

                        BlockPos bPos = ((BlockHitResult) hit).getBlockPos();
                        String blockId = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(bPos).getBlock()).toString();

                        config.blockWhitelist.remove(blockId);
                        if (!config.blockBlacklist.contains(blockId)) {
                            config.blockBlacklist.add(blockId);
                            config.save();
                            player.displayClientMessage(Component.literal("\u00A7a[OreVein] Added \u00A7e" + blockId + "\u00A7a to blacklist"), false);
                        } else {
                            player.displayClientMessage(Component.literal("\u00A7e[OreVein] " + blockId + " is already blacklisted"), false);
                        }
                        return 1;
                    })
                    .then(Commands.argument("block", StringArgumentType.string())
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            String blockId = StringArgumentType.getString(context, "block");

                            config.blockWhitelist.remove(blockId);
                            if (!config.blockBlacklist.contains(blockId)) {
                                config.blockBlacklist.add(blockId);
                                config.save();
                                player.displayClientMessage(Component.literal("\u00A7a[OreVein] Added \u00A7e" + blockId + "\u00A7a to blacklist"), false);
                            } else {
                                player.displayClientMessage(Component.literal("\u00A7e[OreVein] " + blockId + " is already blacklisted"), false);
                            }
                            return 1;
                        })))
                .then(Commands.literal("remove")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();

                        HitResult hit = player.pick(5.0, 0.0f, false);
                        if (hit.getType() != HitResult.Type.BLOCK) {
                            player.displayClientMessage(Component.literal("\u00A7c[OreVein] Look at a block to remove it from blacklist"), false);
                            return 0;
                        }

                        BlockPos bPos = ((BlockHitResult) hit).getBlockPos();
                        String blockId = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(bPos).getBlock()).toString();

                        if (config.blockBlacklist.remove(blockId)) {
                            config.save();
                            player.displayClientMessage(Component.literal("\u00A7a[OreVein] Removed \u00A7e" + blockId + "\u00A7a from blacklist"), false);
                        } else {
                            player.displayClientMessage(Component.literal("\u00A7e[OreVein] " + blockId + " was not in blacklist"), false);
                        }
                        return 1;
                    })
                    .then(Commands.argument("block", StringArgumentType.string())
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            String blockId = StringArgumentType.getString(context, "block");

                            if (config.blockBlacklist.remove(blockId)) {
                                config.save();
                                player.displayClientMessage(Component.literal("\u00A7a[OreVein] Removed \u00A7e" + blockId + "\u00A7a from blacklist"), false);
                            } else {
                                player.displayClientMessage(Component.literal("\u00A7e[OreVein] " + blockId + " was not in blacklist"), false);
                            }
                            return 1;
                        }))))
            // /orevein tag add/remove/block <tag>
            .then(Commands.literal("tag")
                .then(Commands.literal("add")
                    .then(Commands.argument("tag", StringArgumentType.string())
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            String tag = StringArgumentType.getString(context, "tag");
                            config.tagBlacklist.remove(tag);
                            if (!config.tagWhitelist.contains(tag)) {
                                config.tagWhitelist.add(tag);
                                config.save();
                                player.displayClientMessage(Component.literal("\u00A7a[OreVein] Added tag \u00A7d" + tag + "\u00A7a to whitelist"), false);
                            } else {
                                player.displayClientMessage(Component.literal("\u00A7e[OreVein] Tag " + tag + " is already whitelisted"), false);
                            }
                            return 1;
                        })))
                .then(Commands.literal("remove")
                    .then(Commands.argument("tag", StringArgumentType.string())
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            String tag = StringArgumentType.getString(context, "tag");
                            if (config.tagWhitelist.remove(tag)) {
                                config.save();
                                player.displayClientMessage(Component.literal("\u00A7a[OreVein] Removed tag \u00A7d" + tag + "\u00A7a from whitelist"), false);
                            } else {
                                player.displayClientMessage(Component.literal("\u00A7e[OreVein] Tag " + tag + " was not in whitelist"), false);
                            }
                            return 1;
                        })))
                .then(Commands.literal("block")
                    .then(Commands.argument("tag", StringArgumentType.string())
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            String tag = StringArgumentType.getString(context, "tag");
                            config.tagWhitelist.remove(tag);
                            if (!config.tagBlacklist.contains(tag)) {
                                config.tagBlacklist.add(tag);
                                config.save();
                                player.displayClientMessage(Component.literal("\u00A7a[OreVein] Added tag \u00A7d" + tag + "\u00A7a to blacklist"), false);
                            } else {
                                player.displayClientMessage(Component.literal("\u00A7e[OreVein] Tag " + tag + " is already blacklisted"), false);
                            }
                            return 1;
                        }))))
            // /orevein list - show current whitelist/blacklist
            .then(Commands.literal("list")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();

                    player.displayClientMessage(Component.literal("\u00A76=== OreVein Config ==="), false);

                    if (config.blockWhitelist.isEmpty()) {
                        player.displayClientMessage(Component.literal("\u00A7aWhitelist: \u00A77(empty)"), false);
                    } else {
                        player.displayClientMessage(Component.literal("\u00A7aWhitelist:"), false);
                        for (String block : config.blockWhitelist) {
                            player.displayClientMessage(Component.literal("  \u00A77- \u00A7e" + block), false);
                        }
                    }

                    if (config.blockBlacklist.isEmpty()) {
                        player.displayClientMessage(Component.literal("\u00A7cBlacklist: \u00A77(empty)"), false);
                    } else {
                        player.displayClientMessage(Component.literal("\u00A7cBlacklist:"), false);
                        for (String block : config.blockBlacklist) {
                            player.displayClientMessage(Component.literal("  \u00A77- \u00A7e" + block), false);
                        }
                    }

                    if (!config.tagWhitelist.isEmpty()) {
                        player.displayClientMessage(Component.literal("\u00A7bTag Whitelist:"), false);
                        for (String tag : config.tagWhitelist) {
                            player.displayClientMessage(Component.literal("  \u00A77- \u00A7d" + tag), false);
                        }
                    }

                    if (!config.tagBlacklist.isEmpty()) {
                        player.displayClientMessage(Component.literal("\u00A7cTag Blacklist:"), false);
                        for (String tag : config.tagBlacklist) {
                            player.displayClientMessage(Component.literal("  \u00A77- \u00A7d" + tag), false);
                        }
                    }

                    return 1;
                }))
            // /orevein reload - reload config from file (OP only)
            .then(Commands.literal("reload")
                .executes(context -> {
                    if (!context.getSource().permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))) {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        player.displayClientMessage(Component.literal("\u00A7c[OreVein] You need OP permissions to reload config"), false);
                        return 0;
                    }
                    config = OreVeinConfig.load();
                    VeinMiner.setConfig(config);
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    player.displayClientMessage(Component.literal("\u00A7a[OreVein] Config reloaded!"), false);
                    LOGGER.info("Config reloaded by command");
                    return 1;
                }))
            // /orevein help - show available commands
            .then(Commands.literal("help")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();

                    player.displayClientMessage(Component.literal("\u00A76=== OreVein Commands ==="), false);
                    player.displayClientMessage(Component.literal("\u00A7e/orevein check \u00A77- Check if block can be vein-mined"), false);
                    player.displayClientMessage(Component.literal("\u00A7e/orevein add \u00A77- Add block you're looking at"), false);
                    player.displayClientMessage(Component.literal("\u00A7e/orevein remove \u00A77- Blacklist block you're looking at"), false);
                    player.displayClientMessage(Component.literal("\u00A7e/orevein whitelist add/remove [block] \u00A77- Manage whitelist"), false);
                    player.displayClientMessage(Component.literal("\u00A7e/orevein blacklist add/remove [block] \u00A77- Manage blacklist"), false);
                    player.displayClientMessage(Component.literal("\u00A7e/orevein tag add <tag> \u00A77- Add tag to whitelist"), false);
                    player.displayClientMessage(Component.literal("\u00A7e/orevein tag remove <tag> \u00A77- Remove tag from whitelist"), false);
                    player.displayClientMessage(Component.literal("\u00A7e/orevein tag block <tag> \u00A77- Add tag to blacklist"), false);
                    player.displayClientMessage(Component.literal("\u00A7e/orevein list \u00A77- Show all lists"), false);
                    player.displayClientMessage(Component.literal("\u00A7e/orevein reload \u00A77- Reload config (OP)"), false);
                    player.displayClientMessage(Component.literal("\u00A7e/miningxp \u00A77- Show your mining XP"), false);
                    return 1;
                })));
    }

    private static void registerNetworking() {
        // Register toggle request handler (client -> server)
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, TOGGLE_REQUEST_ID, (buf, context) -> {
            if (dataManager == null) return;

            String toggleType = buf.readUtf();
            ServerPlayer player = (ServerPlayer) context.getPlayer();
            UUID id = player.getUUID();

            switch (toggleType) {
                case "vein_mining" -> {
                    boolean current = dataManager.isVeinMiningEnabled(id);
                    dataManager.setVeinMiningEnabled(id, !current);
                    if (current) {
                        VeinMiner.cancelPlayerTasks(id);
                        dataManager.setPlayerMode(id, "OFF");
                    } else {
                        dataManager.setPlayerMode(id, "OFF");
                    }
                }
                case "item_teleport" -> {
                    boolean current = dataManager.getItemTeleport(id);
                    dataManager.setItemTeleport(id, !current);
                }
                case "tunnel_1x2" -> {
                    if (dataManager.getPlayerXP(id) >= getTunnel1x2UnlockXP() && config.enable1x2Tunnel) {
                        String current = dataManager.getPlayerMode(id);
                        if ("MODE_1X2".equals(current)) {
                            dataManager.setPlayerMode(id, "OFF");
                        } else {
                            dataManager.setPlayerMode(id, "MODE_1X2");
                            dataManager.setVeinMiningEnabled(id, false);
                            VeinMiner.cancelPlayerTasks(id);
                        }
                    }
                }
                case "tunnel_3x3" -> {
                    if (dataManager.getPlayerXP(id) >= getTunnel3x3UnlockXP() && config.enable3x3Tunnel) {
                        String current = dataManager.getPlayerMode(id);
                        if ("MODE_3X3".equals(current)) {
                            dataManager.setPlayerMode(id, "OFF");
                        } else {
                            dataManager.setPlayerMode(id, "MODE_3X3");
                            dataManager.setVeinMiningEnabled(id, false);
                            VeinMiner.cancelPlayerTasks(id);
                        }
                    }
                }
                case "tunnel_5x5" -> {
                    if (dataManager.getPlayerXP(id) >= getTunnel5x5UnlockXP() && config.enable5x5Tunnel) {
                        String current = dataManager.getPlayerMode(id);
                        if ("MODE_5X5".equals(current)) {
                            dataManager.setPlayerMode(id, "OFF");
                        } else {
                            dataManager.setPlayerMode(id, "MODE_5X5");
                            dataManager.setVeinMiningEnabled(id, false);
                            VeinMiner.cancelPlayerTasks(id);
                        }
                    }
                }
            }

            // Sync back to client
            syncStateToClient(player);
        });
    }

    // Per-player flag to prevent recursive tunnel mining
    private static final Set<UUID> tunnelMiningPlayers = Collections.synchronizedSet(new HashSet<>());

    // Per-player tunnel cooldown tracking (UUID -> swings remaining before next tunnel fires)
    private static final Map<UUID, Integer> tunnelCooldowns = new HashMap<>();

    /**
     * Check if a block is safe to break with tunnel mining.
     */
    private static boolean isTunnelSafe(BlockState state) {
        Block block = state.getBlock();

        if (block instanceof LiquidBlock) return false;
        if (state.is(Blocks.WATER) || state.is(Blocks.LAVA)) return false;

        if (state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.SUSPICIOUS_SAND) || state.is(Blocks.SUSPICIOUS_GRAVEL)) return false;

        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.PLANKS) || state.is(BlockTags.SAPLINGS)) return false;

        if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST)
                || state.is(Blocks.ENDER_CHEST) || state.is(Blocks.SPAWNER)
                || state.is(BlockTags.BEDS)) return false;

        return true;
    }

    private static boolean checkTunnelCooldown(UUID id, String mode) {
        int skipSwings = switch (mode) {
            case "MODE_1X2" -> config.tunnel1x2CooldownMs;
            case "MODE_3X3" -> config.tunnel3x3CooldownMs;
            case "MODE_5X5" -> config.tunnel5x5CooldownMs;
            default -> 0;
        };

        if (skipSwings <= 0) return true;

        int remaining = tunnelCooldowns.getOrDefault(id, 0);

        if (remaining <= 0) {
            tunnelCooldowns.put(id, skipSwings);
            return true;
        } else {
            tunnelCooldowns.put(id, remaining - 1);
            return false;
        }
    }

    /**
     * Handle XP tracking and trigger vein/tunnel mining.
     */
    private static void handleXPAndMining(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
        UUID id = player.getUUID();

        String mode = dataManager.getPlayerMode(id);

        if (!tunnelMiningPlayers.contains(id) && !mode.equals("OFF") && isUsingNetheritePick(player)) {
            int xp = dataManager.getPlayerXP(id);
            boolean hasXP = switch (mode) {
                case "MODE_1X2" -> xp >= getTunnel1x2UnlockXP();
                case "MODE_3X3" -> xp >= getTunnel3x3UnlockXP();
                case "MODE_5X5" -> xp >= getTunnel5x5UnlockXP();
                default -> false;
            };

            if (hasXP && checkTunnelCooldown(id, mode)) {
                tunnelMiningPlayers.add(id);
                try {
                    mineTunnel(level, player, pos, mode);
                } finally {
                    tunnelMiningPlayers.remove(id);
                }
            }
        }
        else if (mode.equals("OFF") && player.isShiftKeyDown() && dataManager.isVeinMiningEnabled(id)) {
            if (VeinMiner.isVeinMinable(state)) {
                VeinMiner.processVeinMining(level, player, pos, state);
            }
        }

        // XP tracking - only give XP for pickaxe mining
        if (!isUsingPickaxe(player)) return;

        int oldXP = dataManager.getPlayerXP(id);
        int newXP = oldXP + 1;
        dataManager.setPlayerXP(id, newXP);

        int basicUnlock = getTunnel1x2UnlockXP();
        int advancedUnlock = getTunnel5x5UnlockXP();

        if (newXP % 100 == 0 && newXP < advancedUnlock) {
            player.displayClientMessage(Component.literal("\u00A7a[OreVein] XP: " + newXP + "/" + advancedUnlock), true);
        }

        if (newXP == basicUnlock && oldXP < basicUnlock) {
            player.displayClientMessage(Component.literal("\u00A7a[OreVein] 1x2 and 3x3 Tunnel Modes UNLOCKED!"), true);
        }
        if (newXP == advancedUnlock && oldXP < advancedUnlock) {
            player.displayClientMessage(Component.literal("\u00A76[OreVein] 5x5 Tunnel Mode UNLOCKED!"), true);
        }

        if (newXP % 10 == 0 || newXP == basicUnlock || newXP == advancedUnlock) {
            syncStateToClient(player);
        }
    }

    private static boolean isUsingPickaxe(Player player) {
        ItemStack stack = player.getMainHandItem();
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return itemId.contains("pickaxe");
    }

    private static boolean isUsingNetheritePick(Player player) {
        ItemStack stack = player.getMainHandItem();
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return itemId.contains("netherite") && itemId.contains("pickaxe");
    }

    private static void mineTunnel(ServerLevel level, ServerPlayer player, BlockPos startPos, String mode) {
        if ("MODE_1X2".equals(mode)) {
            BlockPos below = startPos.below();
            BlockState belowState = level.getBlockState(below);
            if (!belowState.isAir() && belowState.getDestroySpeed(level, below) >= 0) {
                breakTunnelBlocks(level, player, List.of(below));
            }
            return;
        }

        int radius = "MODE_3X3".equals(mode) ? 1 : 2;
        int yMin = -1;
        int yMax = radius == 1 ? 1 : 3;

        Direction facing = player.getDirection();
        float pitch = player.getXRot();
        List<BlockPos> blocks = new ArrayList<>();

        if (pitch < -45 || pitch > 45) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x != 0 || z != 0) {
                        blocks.add(startPos.offset(x, 0, z));
                    }
                }
            }
        } else if (facing == Direction.NORTH || facing == Direction.SOUTH) {
            for (int x = -radius; x <= radius; x++) {
                for (int y = yMin; y <= yMax; y++) {
                    if (x != 0 || y != 0) {
                        blocks.add(startPos.offset(x, y, 0));
                    }
                }
            }
        } else {
            for (int z = -radius; z <= radius; z++) {
                for (int y = yMin; y <= yMax; y++) {
                    if (z != 0 || y != 0) {
                        blocks.add(startPos.offset(0, y, z));
                    }
                }
            }
        }

        breakTunnelBlocks(level, player, blocks);
    }

    private static void breakTunnelBlocks(ServerLevel level, ServerPlayer player, List<BlockPos> blocks) {
        ItemStack tool = player.getMainHandItem();

        for (BlockPos pos : blocks) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getDestroySpeed(level, pos) < 0) {
                continue;
            }

            if (!isTunnelSafe(state)) {
                continue;
            }

            breakAndDrop(level, player, pos);

            if (tool.isDamageableItem()) {
                tool.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
                if (tool.isEmpty()) {
                    break;
                }
            }
        }
    }

    private static void breakAndDrop(ServerLevel level, ServerPlayer player, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        if (state.isAir() || state.getDestroySpeed(level, pos) < 0) {
            return;
        }

        level.levelEvent(2001, pos, Block.getId(state));

        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), player, player.getMainHandItem());

        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);

        boolean teleport = isItemTeleportEnabled(player.getUUID());

        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;

            if (teleport) {
                if (!player.getInventory().add(drop)) {
                    player.drop(drop, false);
                }
            } else {
                double x = pos.getX() + 0.5;
                double y = pos.getY() + 0.5;
                double z = pos.getZ() + 0.5;
                ItemEntity itemEntity = new ItemEntity(level, x, y, z, drop);
                itemEntity.setPickUpDelay(10);
                level.addFreshEntity(itemEntity);
            }
        }
    }

    // ==================== Static Helper Methods ====================

    public static int getPlayerXP(UUID id) {
        return dataManager != null ? dataManager.getPlayerXP(id) : 0;
    }

    public static void setPlayerXP(UUID id, int xp) {
        if (dataManager != null) {
            dataManager.setPlayerXP(id, xp);
        }
    }

    public static boolean isVeinMiningEnabled(UUID id) {
        return dataManager != null ? dataManager.isVeinMiningEnabled(id) : true;
    }

    public static boolean isItemTeleportEnabled(UUID id) {
        return dataManager != null ? dataManager.getItemTeleport(id) : false;
    }

    public static void syncStateToClient(ServerPlayer player) {
        if (dataManager == null) return;

        UUID id = player.getUUID();
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        buf.writeInt(dataManager.getPlayerXP(id));
        buf.writeBoolean(dataManager.getItemTeleport(id));
        buf.writeUtf(dataManager.getPlayerMode(id));
        buf.writeBoolean(dataManager.isVeinMiningEnabled(id));

        NetworkManager.sendToPlayer(player, SYNC_STATE_ID, buf);
    }
}
