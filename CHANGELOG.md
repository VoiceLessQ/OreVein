# Changelog

All changes to OreVein are tracked here.

## [1.7.0] - 2026-02-10 (Minecraft 1.21.11)

### Added

* **NeoForge support!** The mod now runs on both Fabric and NeoForge using Architectury. Same features on both loaders.

### Changed

* Ported from Fabric-only to Architectury multi-loader project structure
* Updated to Minecraft 1.21.11 stable
* Architectury API 19.0.1
* Fabric Loader 0.18.4
* Fabric API 0.139.5+1.21.11
* NeoForge 21.11.38-beta
* Java 21

## [1.6.0] - 2026-01-30 (Minecraft 26.1-snapshot-2)

### Added

* **Radial Menu** - Hold ALT to open circular menu for quick toggles
  * Toggle vein mining on/off
  * Toggle auto-collect (items to inventory)
  * Toggle tunnel modes (1x2 / 3x3 / 5x5)
  * View current XP progress
  * Optimized rendering for smooth performance
* **5x5 Tunnel Mode** - Mine 25 blocks at once for large-scale tunneling
* **Tiered Tunnel Unlocks** - Each tunnel mode has its own XP requirement:
  * 1x2 Tunnel: 500 XP (2 blocks per swing)
  * 3x3 Tunnel: 2,000 XP (9 blocks per swing)
  * 5x5 Tunnel: 5,000 XP (25 blocks per swing)
* **Config System** - Full configuration via `config/orevein.json`
  * Separate XP thresholds for each tunnel mode
  * Max vein sizes for different block types
  * Enable/disable individual tunnel modes
  * Block whitelist/blacklist
  * Tag whitelist/blacklist
* **Tag-Based Block Detection** - Automatic mod compatibility using Minecraft's tag system
  * Default tags: `minecraft:coal_ores`, `minecraft:iron_ores`, `minecraft:logs`, `c:ores`, etc.
  * Add custom tags for mod support
* **In-Game Commands** - Manage config without editing files
  * `/orevein check` - Check if a block can be vein-mined
  * `/orevein add` / `/orevein remove` - Whitelist/blacklist block you're looking at
  * `/orevein whitelist add/remove <block>` - Manage block whitelist
  * `/orevein blacklist add/remove <block>` - Manage block blacklist
  * `/orevein tag add/remove/block <tag>` - Manage tag lists
  * `/orevein list` - View all whitelists and blacklists
  * `/orevein reload` - Reload config (OP only)
  * `/orevein help` - Show all commands
* **Enchantment Support for Vein Mining**
  * Efficiency enchantment now speeds up vein mining delay
  * Mining delay calculated from tool speed and block hardness
  * Delay ranges from 25ms (fast) to 200ms (slow) based on actual mining capability

## [1.5.1] - 2026-01-14 (Minecraft 26.1-snapshot-2)

### Fixed

* **ChunkPos compilation errors** - Fixed compatibility issues with Minecraft 26.1-snapshot-2 by replacing non-existent `toLong()` and `asLong()` methods with manual bit-shifting operations. The mod now compiles and runs correctly on the latest snapshot.

## [1.5.0-beta] - 2026-01-11 (Minecraft 26.1-snapshot-2)

### Changed

* **Updated to Minecraft 26.1-snapshot-2** - First beta release for the latest snapshot version
* **Removed debug logging** - All `LOGGER.info("[OreVein] DEBUG: ...")` statements have been removed to stop spamming the terminal. The mod still logs errors and warnings when needed, but won't flood your console during normal gameplay.
* **Cleaner terminal output** - Your Minecraft server/client logs are now much easier to read without hundreds of vein mining debug messages.

### Technical

* Updated to Minecraft 26.1-snapshot-2
* Updated to Fabric Loader 0.18.4
* Updated to Fabric API 0.141.2+26.1
* Requires Java 25
* Removed verbose debug logging from `onBlockBreak()`, `veinMineOre()`, `processVeinMining()`, `processVeinBlocks()`, `findVein()`, `areEquivalent()`, and `canVeinMineBlock()` methods
* Kept all player-facing messages (tool tier warnings, XP notifications, etc.)
* Kept error logging for troubleshooting actual issues

## [1.4.0] - 2026-01-10 (Minecraft 1.21.11)

### Fixed

* **Game freezing after vein mining** - The search algorithm was exploring way too many blocks. Now it only checks blocks that actually match what you're mining instead of checking everything nearby.
* **Game freezing near water** - Mining ores near water or underwater was causing freezes. The algorithm now treats water and lava as walls that stop the search.
* **Missing item drops** - Items weren't spawning when you vein mined blocks. Fixed the drop logic completely for both vein mining and tunnel mining.
* **Breaking sounds and particles** - Added proper vanilla-style breaking sounds and particle effects so it feels more natural.
* **Ice water flooding** - Regular ice now requires Silk Touch to vein mine to prevent water source block flooding.
* **Item teleportation cache issue** - Old saved player data had item teleportation enabled from when that was the default. Now uses config default unless explicitly set.

### Added

* **Item teleportation toggle** - Choose if vein mined items go straight to your inventory or drop on the ground. Drops to ground by default.
* **Tool tier restrictions** - Some blocks need better tools now. Iron pickaxe minimum for gold/redstone ores, diamond for diamond/emerald ores, netherite for ancient debris.
* **Config file support** - Settings saved in `config/orevein.json`. Adjust max vein sizes, tool restrictions, and default item teleport behavior.

### Changed

* Item teleportation is off by default now. Items drop where you mine them unless you enable teleport.
* Ice blocks use a safer 6-directional search instead of 26-directional to prevent water physics lag.

## [1.3.4] - 2026-01-08

### Changed

* Updated to Minecraft 1.21.11 (stable release)
* Updated Fabric API to 0.141.1+1.21.11
* Uses Java 21 for better compatibility

No feature changes, just version updates to work with stable Minecraft 1.21.11.

## [1.3.3] - 2026-01-08

### Fixed

* Player data wasn't saving properly. XP and tunnel mode preferences were getting lost when you closed the game. This should be fixed now - your progress actually saves between sessions.

## [1.3.2] - Previous Release

### Added

* Basic tunnel mode and XP tracking features
* Nether wood support (Crimson and Warped)

### Known Issues

* Data wasn't persisting correctly (fixed in 1.3.3)
