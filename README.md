
# OreVein - Vein Mining Mod

**Now available for both Fabric and NeoForge on Minecraft 1.21.11!** Also its not very fancy since im still very new to modding. Thank you for understanding.

## What is this?

OreVein is a Fabric and NeoForge mod that lets you mine entire ore veins and chop down whole trees at once. Just sneak and break a block to automatically mine all connected blocks of the same type. There's also an XP system that unlocks tunnel mining modes once you reach 500 XP.

## Main Features

### Vein Mining

Hold shift and break a block to mine the entire vein. Works on:

* All ores (coal, iron, gold, diamond, emerald, copper, redstone, lapis, nether gold, quartz, ancient debris)
* Ore blocks (diamond block, iron block, etc.)
* All wood types including crimson and warped stems
* Leaves and nether plants
* Common blocks like stone, cobblestone, netherrack, basalt (sneak required)
* Ice blocks (regular ice needs Silk Touch to drop anything)
* Building blocks (terracotta, concrete, wool, bricks, purpur, prismarine, quartz, stairs, slabs, etc.)

The mod tries to find all connected blocks of the same type. For ores and logs it checks diagonals too. For leaves, it only checks directly touching blocks so you don't accidentally mine half a forest.

**Important:** If you release shift (stop sneaking) while vein mining is in progress, it will cancel the remaining blocks. This lets you stop large vein mining operations if you change your mind.

### Radial Menu

Hold **Left Alt** or **Right Alt** to open the circular menu. Toggle options:

* Vein Mining ON/OFF
* Auto-Collect (items to inventory or drop on ground)
* 1x2 Tunnel Mode (requires 500 XP)
* 3x3 Tunnel Mode (requires 2000 XP)
* 5x5 Tunnel Mode (requires 5000 XP)
* View current XP progress

Release ALT to close the menu. Click options to toggle them.

### Tool Requirements

Some blocks need better tools to vein mine:

* **Iron pickaxe or better:** Gold ore, redstone, packed ice, blue ice
* **Diamond pickaxe or better:** Diamond ore, emerald ore
* **Netherite pickaxe:** Ancient debris, obsidian

If your tool is too weak you'll get a message telling you what you need.

### Tunnel Mining

Tunnel modes unlock at different XP levels based on their power:

| Mode          | XP Required | Blocks Mined | Description                     |
| ------------- | ----------- | ------------ | ------------------------------- |
| **1x2** | 500 XP      | 2 blocks     | Mines block + one below         |
| **3x3** | 2,000 XP    | 9 blocks     | Mines 3x3 area based on facing  |
| **5x5** | 5,000 XP    | 25 blocks    | Mines 5x5 area - very powerful! |

**Why the high XP requirements?** The larger tunnel modes are extremely powerful. Mining 25 blocks per swing with 5x5 mode would trivialize the game if unlocked too early. The tiered XP system ensures these are late-game rewards that feel earned.

Use the radial menu (ALT key) to toggle between modes.

### XP System

You get 1 XP for each block you mine with a pickaxe (only pickaxes count for XP). Different tunnel modes unlock at different XP thresholds:

* **500 XP** - Unlock 1x2 tunnel mode
* **2,000 XP** - Unlock 3x3 tunnel mode
* **5,000 XP** - Unlock 5x5 tunnel mode

If you die, you lose all your XP and tunnel mode gets disabled. This adds risk to exploration!

Use `/miningxp` to check your current XP.

### Item Drops

By default, items drop where you mine them just like vanilla Minecraft. Use the radial menu to toggle auto-collect if you want items to go straight to your inventory.

### Enchantment Support

The mod respects vanilla Minecraft enchantments:

* **Efficiency** - Speeds up vein mining delay between blocks
* **Unbreaking** - Reduces durability loss (checked per block)
* **Fortune** - Increases drops from ores
* **Silk Touch** - Returns the block itself

## How It Works

The mod uses vanilla Minecraft's mining calculations so everything should feel natural. Fortune works, Silk Touch works, tool durability is consumed properly, and Unbreaking extends your tools like normal.

When you vein mine, each block breaks with a slight delay based on how fast you can actually mine that block. So mining stone with an iron pickaxe is faster than mining obsidian. Efficiency enchantments make vein mining faster too.

### Water and Lava Safety

The vein mining algorithm treats water and lava as boundaries. This means:

* You can safely vein-mine underwater without issues
* The search stops at water blocks instead of exploring through them
* Mining ores next to water won't cause the algorithm to freeze
* Water and lava act like walls that contain the vein search

### Tool Requirements for Vein Mining

You need the right tool to vein-mine certain blocks:

* **Pickaxe required:** All ores, stone blocks, ice
* **Axe required:** Logs, wood blocks, leaves
* **Shovel required:** Dirt, sand, gravel

Minimum tool tiers:

* **Iron pickaxe:** Gold ore, redstone, packed ice, blue ice
* **Diamond pickaxe:** Diamond ore, emerald ore
* **Netherite pickaxe:** Ancient debris, obsidian

If your tool isn't good enough, you'll get a message and vein mining won't work on that block.

## Configuration

The mod saves settings to `config/orevein.json`. You can edit this file directly or use in-game commands.

### Config Options

* `maxVeinSizeOres` - Maximum blocks per vein for ores (default: 64)
* `maxVeinSizeLogs` - Maximum blocks per vein for logs (default: 128)
* `maxVeinSizeLeaves` - Maximum blocks per vein for leaves (default: 256)
* `maxVeinSizeStone` - Maximum blocks per vein for stone/dirt (default: 64)
* `minDelayMs` - Minimum delay between vein mining blocks in ms (default: 50)
* `maxDelayMs` - Maximum delay between vein mining blocks in ms (default: 500)
* `tunnel1x2UnlockXP` - XP to unlock 1x2 tunnel (default: 500)
* `tunnel3x3UnlockXP` - XP to unlock 3x3 tunnel (default: 2000)
* `tunnel5x5UnlockXP` - XP to unlock 5x5 tunnel (default: 5000)
* `enable1x2Tunnel`, `enable3x3Tunnel`, `enable5x5Tunnel` - Enable/disable tunnel modes
* `enforceToolTiers` - Require correct tool tier for vein mining (default: true)
* `stopAtDurability` - Stop mining when tool has this much durability left (default: 1)

### Whitelist/Blacklist

You can customize which blocks can be vein-mined:

* **Block Whitelist** - Additional blocks to allow (e.g., `minecraft:glowstone`)
* **Block Blacklist** - Blocks to prevent from vein mining (overrides everything)
* **Tag Whitelist** - Block tags to allow (e.g., `c:ores`, `minecraft:logs`)
* **Tag Blacklist** - Block tags to prevent

### Default Tags

The mod comes with these tags enabled by default:

* `minecraft:coal_ores`, `minecraft:iron_ores`, `minecraft:gold_ores`
* `minecraft:diamond_ores`, `minecraft:emerald_ores`, `minecraft:copper_ores`
* `minecraft:lapis_ores`, `minecraft:redstone_ores`
* `minecraft:logs`, `minecraft:leaves`
* `c:ores` (Fabric convention tag for mod compatibility)

### Adding Mod Support

To add support for modded ores, use commands like:

```
/orevein tag add "create:ores"
/orevein whitelist add "mekanism:tin_ore"
```

## Known Issues

* The death penalty can't be disabled, you'll always lose XP on death

## Requirements

* **Minecraft 1.21.11**
* **Fabric:** Fabric Loader 0.18.4+ and Fabric API
* **NeoForge:** NeoForge 21.11.38-beta+
* [Architectury API](https://modrinth.com/mod/architectury-api) v19.0.1+
* Java 21

## Installation

### Fabric
1. Install Fabric Loader for Minecraft 1.21.11
2. Download Fabric API and Architectury API (Fabric) and put them in your mods folder
3. Download OreVein (Fabric) and put it in your mods folder
4. Launch Minecraft

### NeoForge
1. Install NeoForge for Minecraft 1.21.11
2. Download Architectury API (NeoForge) and put it in your mods folder
3. Download OreVein (NeoForge) and put it in your mods folder
4. Launch Minecraft

## Commands

### Player Commands

* `/miningxp` - Check your current XP and active tunnel mode
* `/orevein check` - Check if the block you're looking at can be vein-mined
* `/orevein add` - Add the block you're looking at to the whitelist
* `/orevein remove` - Blacklist the block you're looking at
* `/orevein list` - Show all whitelists and blacklists
* `/orevein help` - Show all available commands

### Block Management

* `/orevein whitelist add <block>` - Add a block by ID (e.g., `minecraft:glowstone`)
* `/orevein whitelist remove <block>` - Remove a block from whitelist
* `/orevein blacklist add <block>` - Add a block to blacklist
* `/orevein blacklist remove <block>` - Remove a block from blacklist

### Tag Management

* `/orevein tag add <tag>` - Add a tag to whitelist (e.g., `c:ores/copper`)
* `/orevein tag remove <tag>` - Remove a tag from whitelist
* `/orevein tag block <tag>` - Add a tag to blacklist

### OP Commands

* `/orevein setxp <amount>` - Set your XP (for testing)
* `/orevein reload` - Reload config from file

## Support

If something breaks or doesn't work right, let me know. Include your Minecraft version, mod version, and what other mods you're running if possible.

## Credits

Created by **NoZeroG**

### Inspiration

The radial menu design was inspired by [owo-lib](https://github.com/wisp-forest/owo-lib)'s UI components, simplified and adapted for this mod's needs.

## License

All Rights Reserved - You can use this mod and include it in modpacks (with credit), but no modifications or reuploads without permission. See LICENSE file for details.
