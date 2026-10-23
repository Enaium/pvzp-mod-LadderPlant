# ladder-plant-mod

Adds one plant to PvZ-Portable: the **Ladder**.

- It costs **0 sun**.
- It may be planted **only on a Pumpkin or a Tall-nut**.
- When planted, it puts the game's own **ladder grid item** on that square, so zombies climb over
  the plant instead of eating it.

It is a plain Fabric mod: `fabric.mod.json`, a Kotlin `ModInitializer`, and a jar that is loaded
from `<gamedir>/mods` by the real Fabric Loader through the game provider the game jar itself
carries (`cn.enaium.fabricpvz.provider`). It registers the plant through the workspace's
**api-plant** module.

| mod id | version | entry point |
|---|---|---|
| `ladder-plant` | `1.0.0` | `cn.enaium.fabricpvz.ladder.LadderPlantMod` (Kotlin) |

## How it works

The plant is registered with `PlantType.builder("ladder")`:

| builder call | effect |
|---|---|
| `.name("Ladder")` / `.tooltip(...)` | the packet's name and tooltip |
| `.cost(0)` | the seed packet costs 0 sun |
| `.health(4000)` | the ladder itself is far too tough to be eaten away |
| `.reanimation(REANIM_NONE)` | the plant draws nothing of its own - the visible ladder is the grid item |
| `.packetArt(IMAGE_REANIM_ZOMBIE_LADDER_1, 0)` | the packet icon is the retail ladder art |
| `.chooserVisible(true)` | the seed is offered by the seed chooser, ungated |
| `.flying(true)` | lets the board's built-in occupancy check step aside, so the ladder can go on top of an existing plant; it also keeps zombies from targeting the ladder as food |
| `.plantingRule(...)` | the rule described below |
| `.onPlanted(...)` | the callback that adds the ladder grid item |

### The planting rule

`Board.canPlantAt` asks the registered rule before its own rules. The rule allows the square only
when it holds a **Pumpkin** (`SEED_PUMPKINSHELL`) or a **Tall-nut** (`SEED_TALLNUT`) and there is no
ladder there yet. Every other square - empty ground, a Peashooter, or a square that already carries
a ladder - gets a veto reason, so the game refuses the placement. The base plant is not replaced:
the ladder is planted on top of it, and the next ladder on the same square is refused by the same
rule.

### The ladder grid item

The placement callback calls `Board.addALadder(plant.mPlantCol, plant.mRow)` - exactly what the
ladder zombie does to a plant it walks over. Zombies that reach that square climb the ladder
instead of eating the plant underneath. The callback is idempotent (a restored save calls it again),
so it first checks `Board.getLadderAt` and never stacks two ladders on one square.

## Prerequisites

This mod runs on [PvZ-Portable](https://github.com/FabricPvZ/pvz-portable) and needs one more mod
next to it:

- **PvZ-Portable** - the engine (the game itself): <https://github.com/FabricPvZ/pvz-portable>.
- **fabric-language-kotlin** - this mod is written in Kotlin and carries no Kotlin runtime of its
  own. Download: [Modrinth](https://modrinth.com/mod/fabric-language-kotlin) ·
  [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fabric-language-kotlin) ·
  [Fabric's maven](https://maven.fabricmc.net/net/fabricmc/fabric-language-kotlin/).

The workspace's API (which brings `api-plant` and `api-base`) is carried **inside this mod's jar**.

## Installing

1. Install [PvZ-Portable](https://github.com/FabricPvZ/pvz-portable) (it brings Fabric Loader).
2. Put `fabric-language-kotlin` into the game's `mods/` directory.
3. Put `ladder-plant-mod-1.0.0.jar` into the same `mods/` directory.
4. Start the game, pick a level, and drag the **Ladder** seed onto a Pumpkin or a Tall-nut.

## License

[MIT](./LICENSE).
