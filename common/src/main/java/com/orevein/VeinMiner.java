package com.orevein;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.component.DataComponents;

import java.util.*;
import java.util.concurrent.*;

/**
 * Handles vein mining logic - finding connected blocks and breaking them.
 * Supports config-based whitelist/blacklist and tag-based detection.
 */
public class VeinMiner {

    // Config reference (set by OreVeinMod on init)
    private static OreVeinConfig config;

    // Track active vein mining tasks per player (for cancellation)
    private static final Map<UUID, List<ScheduledFuture<?>>> playerTasks = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    // 26-directional offsets (includes diagonals)
    private static final int[][] ADJACENT_26 = {
        // Face adjacent (6)
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
        // Edge adjacent (12)
        {1, 1, 0}, {1, -1, 0}, {-1, 1, 0}, {-1, -1, 0},
        {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1},
        {0, 1, 1}, {0, 1, -1}, {0, -1, 1}, {0, -1, -1},
        // Corner adjacent (8)
        {1, 1, 1}, {1, 1, -1}, {1, -1, 1}, {1, -1, -1},
        {-1, 1, 1}, {-1, 1, -1}, {-1, -1, 1}, {-1, -1, -1}
    };

    /**
     * Set config reference (called by OreVeinMod on init)
     */
    public static void setConfig(OreVeinConfig cfg) {
        config = cfg;
    }

    /**
     * Get max vein size based on block type
     */
    private static int getMaxVeinSize(BlockState state) {
        if (config == null) return 64;

        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

        if (blockId.contains("_ore") || blockId.contains("ancient_debris")) {
            return config.maxVeinSizeOres;
        }
        if (blockId.contains("_log") || blockId.contains("_wood") ||
            blockId.contains("_stem") || blockId.contains("_hyphae")) {
            return config.maxVeinSizeLogs;
        }
        if (blockId.contains("_leaves")) {
            return config.maxVeinSizeLeaves;
        }
        return config.maxVeinSizeStone;
    }

    /**
     * Process vein mining when a block is broken
     * @return true if vein mining was triggered
     */
    public static boolean processVeinMining(ServerLevel level, ServerPlayer player, BlockPos startPos, BlockState brokenState) {
        // Check if block is vein-minable
        if (!isVeinMinable(brokenState)) {
            return false;
        }

        // Check tool tier
        ItemStack tool = player.getMainHandItem();
        if (!canMineBlock(tool, brokenState)) {
            return false;
        }

        // Get max size from config based on block type
        int maxSize = getMaxVeinSize(brokenState);

        // Find connected vein blocks
        List<BlockPos> vein = findVein(level, startPos, brokenState, maxSize);

        if (vein.isEmpty()) {
            return false;
        }

        // Cancel any existing tasks for this player
        cancelPlayerTasks(player.getUUID());

        // Calculate mining delay based on tool speed, efficiency, and block hardness
        long miningDelay = calculateMiningDelay(level, tool, brokenState, vein.get(0));

        // Schedule breaking of all blocks in vein
        List<ScheduledFuture<?>> tasks = new ArrayList<>();
        UUID playerId = player.getUUID();

        for (int i = 0; i < vein.size(); i++) {
            final BlockPos pos = vein.get(i);
            final int index = i;

            long delay = miningDelay * (index + 1);

            ScheduledFuture<?> task = scheduler.schedule(() -> {
                level.getServer().execute(() -> {
                    ServerPlayer currentPlayer = level.getServer().getPlayerList().getPlayer(playerId);
                    if (currentPlayer == null || !currentPlayer.isShiftKeyDown()) {
                        cancelPlayerTasks(playerId);
                        return;
                    }

                    BlockState currentState = level.getBlockState(pos);
                    if (!areBlocksEquivalent(currentState, brokenState)) {
                        return;
                    }

                    breakAndDrop(level, currentPlayer, pos, currentState, tool);
                });
            }, delay, TimeUnit.MILLISECONDS);

            tasks.add(task);
        }

        playerTasks.put(playerId, tasks);
        OreVeinMod.LOGGER.info("Vein mining {} blocks for player {} (delay: {}ms)", vein.size(), player.getName().getString(), miningDelay);
        return true;
    }

    /**
     * Calculate mining delay based on tool speed, efficiency enchantment, and block hardness.
     */
    public static long calculateMiningDelay(ServerLevel level, ItemStack tool, BlockState blockState, BlockPos pos) {
        int minDelay = config != null ? config.minDelayMs : 25;
        int maxDelay = config != null ? config.maxDelayMs : 200;

        if (tool.isEmpty()) {
            return maxDelay;
        }

        float blockHardness = blockState.getDestroySpeed(level, pos);
        if (blockHardness < 0) {
            return maxDelay;
        }
        if (blockHardness == 0) {
            return minDelay;
        }

        float toolSpeed = tool.getDestroySpeed(blockState);
        boolean isCorrectTool = tool.isCorrectToolForDrops(blockState);
        if (!isCorrectTool) {
            toolSpeed = 1.0f;
        }

        int efficiencyLevel = getEnchantmentLevel(tool, "efficiency");
        if (efficiencyLevel > 0 && toolSpeed > 1.0f) {
            toolSpeed += (efficiencyLevel * efficiencyLevel + 1);
        }

        // Vanilla break time: ticks = hardness * 30 / toolSpeed (for correct tool)
        // 1 tick = 50ms
        float vanillaBreakTicks = blockHardness * 30.0f / toolSpeed;
        long delayMs = (long)(vanillaBreakTicks * 50.0f);

        return Math.max(minDelay, Math.min(maxDelay, delayMs));
    }

    /**
     * Get enchantment level from an item stack by name.
     */
    public static int getEnchantmentLevel(ItemStack stack, String enchantmentName) {
        if (stack.isEmpty()) return 0;

        ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);

        for (var entry : enchantments.entrySet()) {
            var keyOpt = entry.getKey().unwrapKey();
            if (keyOpt.isPresent()) {
                String enchantId = keyOpt.get().identifier().getPath();
                if (enchantId.contains(enchantmentName)) {
                    return entry.getIntValue();
                }
            }
        }
        return 0;
    }

    /**
     * Find all connected blocks of the same type using BFS
     */
    private static List<BlockPos> findVein(ServerLevel level, BlockPos start, BlockState targetState, int maxSize) {
        List<BlockPos> vein = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();

        visited.add(start);

        for (int[] offset : ADJACENT_26) {
            BlockPos neighbor = start.offset(offset[0], offset[1], offset[2]);
            if (!visited.contains(neighbor)) {
                visited.add(neighbor);
                queue.add(neighbor);
            }
        }

        while (!queue.isEmpty() && vein.size() < maxSize) {
            BlockPos current = queue.poll();
            BlockState currentState = level.getBlockState(current);

            if (areBlocksEquivalent(currentState, targetState)) {
                vein.add(current);

                for (int[] offset : ADJACENT_26) {
                    BlockPos neighbor = current.offset(offset[0], offset[1], offset[2]);
                    if (!visited.contains(neighbor)) {
                        visited.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
        }

        return vein;
    }

    /**
     * Break a block and drop its items
     */
    private static void breakAndDrop(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state, ItemStack tool) {
        List<ItemStack> drops = Block.getDrops(state, level, pos, null, player, tool);

        level.levelEvent(2001, pos, Block.getId(state));
        level.removeBlock(pos, false);

        applyDurabilityDamage(tool, player);

        boolean teleport = OreVeinMod.isItemTeleportEnabled(player.getUUID());

        for (ItemStack drop : drops) {
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

    /**
     * Apply durability damage to tool
     */
    private static void applyDurabilityDamage(ItemStack tool, ServerPlayer player) {
        if (tool.isEmpty() || !tool.isDamageableItem()) {
            return;
        }

        // Check stopAtDurability config
        if (config != null && tool.getDamageValue() >= tool.getMaxDamage() - config.stopAtDurability) {
            return; // Don't damage tool further
        }

        tool.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
    }

    /**
     * Check if a block can be vein mined.
     * Checks: blacklist -> tags -> whitelist -> built-in patterns
     */
    public static boolean isVeinMinable(BlockState state) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Block block = state.getBlock();

        // 1. Check blacklist first (always skip blacklisted blocks)
        if (config != null && config.blockBlacklist.contains(blockId)) {
            return false;
        }

        // 2. Check tag blacklist
        if (config != null) {
            for (String tagName : config.tagBlacklist) {
                if (hasTag(block, tagName)) {
                    return false;
                }
            }
        }

        // 3. Check tag whitelist
        if (config != null) {
            for (String tagName : config.tagWhitelist) {
                if (hasTag(block, tagName)) {
                    return true;
                }
            }
        }

        // 4. Check block whitelist
        if (config != null && config.blockWhitelist.contains(blockId)) {
            return true;
        }

        // 5. Built-in patterns (fallback)
        return isBuiltInVeinMinable(blockId);
    }

    /**
     * Check if block has a specific tag
     */
    private static boolean hasTag(Block block, String tagName) {
        try {
            Identifier tagId = Identifier.parse(tagName);
            TagKey<Block> tagKey = TagKey.create(Registries.BLOCK, tagId);
            return block.builtInRegistryHolder().is(tagKey);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Built-in vein minable check (hardcoded patterns)
     */
    private static boolean isBuiltInVeinMinable(String blockId) {
        // Ores
        if (blockId.contains("_ore")) return true;
        if (blockId.contains("ancient_debris")) return true;

        // Logs
        if (blockId.contains("_log")) return true;
        if (blockId.contains("_wood")) return true;
        if (blockId.contains("_stem")) return true;
        if (blockId.contains("_hyphae")) return true;

        // Leaves
        if (blockId.contains("_leaves")) return true;

        // Stone variants
        if (blockId.equals("minecraft:stone")) return true;
        if (blockId.equals("minecraft:deepslate")) return true;
        if (blockId.equals("minecraft:granite")) return true;
        if (blockId.equals("minecraft:diorite")) return true;
        if (blockId.equals("minecraft:andesite")) return true;
        if (blockId.equals("minecraft:tuff")) return true;
        if (blockId.equals("minecraft:calcite")) return true;
        if (blockId.equals("minecraft:dripstone_block")) return true;

        // Dirt variants
        if (blockId.equals("minecraft:dirt")) return true;
        if (blockId.equals("minecraft:grass_block")) return true;
        if (blockId.equals("minecraft:sand")) return true;
        if (blockId.equals("minecraft:gravel")) return true;
        if (blockId.equals("minecraft:clay")) return true;

        // Sandstone
        if (blockId.contains("sandstone")) return true;

        // Nether
        if (blockId.equals("minecraft:netherrack")) return true;
        if (blockId.equals("minecraft:basalt")) return true;
        if (blockId.equals("minecraft:blackstone")) return true;

        // Storage blocks
        if (blockId.equals("minecraft:coal_block")) return true;
        if (blockId.equals("minecraft:iron_block")) return true;
        if (blockId.equals("minecraft:gold_block")) return true;
        if (blockId.equals("minecraft:diamond_block")) return true;
        if (blockId.equals("minecraft:emerald_block")) return true;
        if (blockId.equals("minecraft:lapis_block")) return true;
        if (blockId.equals("minecraft:redstone_block")) return true;
        if (blockId.equals("minecraft:copper_block")) return true;
        if (blockId.equals("minecraft:raw_iron_block")) return true;
        if (blockId.equals("minecraft:raw_gold_block")) return true;
        if (blockId.equals("minecraft:raw_copper_block")) return true;
        if (blockId.equals("minecraft:netherite_block")) return true;
        if (blockId.equals("minecraft:amethyst_block")) return true;
        if (blockId.equals("minecraft:glowstone")) return true;

        return false;
    }

    /**
     * Check if two blocks are equivalent for vein mining purposes
     */
    private static boolean areBlocksEquivalent(BlockState a, BlockState b) {
        String idA = BuiltInRegistries.BLOCK.getKey(a.getBlock()).toString();
        String idB = BuiltInRegistries.BLOCK.getKey(b.getBlock()).toString();

        if (idA.equals(idB)) return true;

        // Handle deepslate ore variants
        if (idA.contains("_ore") && idB.contains("_ore")) {
            String baseA = idA.replace("deepslate_", "").replace("minecraft:", "");
            String baseB = idB.replace("deepslate_", "").replace("minecraft:", "");
            return baseA.equals(baseB);
        }

        return false;
    }

    /**
     * Check if the tool can mine this block (tier check)
     */
    private static boolean canMineBlock(ItemStack tool, BlockState state) {
        if (config != null && !config.enforceToolTiers) {
            return !tool.isEmpty();
        }

        if (tool.isEmpty()) return false;

        String toolId = BuiltInRegistries.ITEM.getKey(tool.getItem()).toString();
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

        int toolTier = getToolTier(toolId);
        int requiredTier = getRequiredTier(blockId);

        return toolTier >= requiredTier;
    }

    private static int getToolTier(String toolId) {
        if (toolId.contains("netherite")) return 4;
        if (toolId.contains("diamond")) return 3;
        if (toolId.contains("iron")) return 2;
        if (toolId.contains("gold")) return 2;
        if (toolId.contains("stone")) return 1;
        if (toolId.contains("wooden")) return 0;
        return 0;
    }

    private static int getRequiredTier(String blockId) {
        if (blockId.contains("ancient_debris")) return 4;
        if (blockId.contains("netherite_block")) return 4;
        if (blockId.contains("obsidian")) return 3;
        if (blockId.contains("diamond")) return 3;
        if (blockId.contains("emerald")) return 3;
        if (blockId.contains("gold")) return 2;
        if (blockId.contains("redstone")) return 2;
        if (blockId.contains("lapis")) return 2;
        if (blockId.contains("copper")) return 2;
        if (blockId.contains("iron")) return 1;
        if (blockId.contains("coal")) return 1;
        return 0;
    }

    public static void cancelPlayerTasks(UUID playerId) {
        List<ScheduledFuture<?>> tasks = playerTasks.remove(playerId);
        if (tasks != null) {
            for (ScheduledFuture<?> task : tasks) {
                task.cancel(false);
            }
            OreVeinMod.LOGGER.debug("Cancelled {} vein mining tasks for player", tasks.size());
        }
    }

    public static void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }
}
