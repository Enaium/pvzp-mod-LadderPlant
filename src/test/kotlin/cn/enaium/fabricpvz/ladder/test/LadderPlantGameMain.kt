package cn.enaium.fabricpvz.ladder.test

import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList
import java.util.Locale
import java.util.TreeSet

import cn.enaium.fabricpvz.api.plant.PlantRegistry
import cn.enaium.fabricpvz.ladder.LadderPlantMod
import cn.enaium.fabricpvz.portable.lawn.Board
import cn.enaium.fabricpvz.portable.lawn.CursorObject
import cn.enaium.fabricpvz.portable.lawn.CursorType
import cn.enaium.fabricpvz.portable.lawn.GameMode
import cn.enaium.fabricpvz.portable.lawn.LawnApp
import cn.enaium.fabricpvz.portable.lawn.Plant
import cn.enaium.fabricpvz.portable.lawn.PlantPriority
import cn.enaium.fabricpvz.portable.lawn.PlantingReason
import cn.enaium.fabricpvz.portable.lawn.SeedType
import cn.enaium.fabricpvz.portable.sexy.graphics.Graphics
import cn.enaium.fabricpvz.portable.sexy.graphics.MemoryImage
import net.fabricmc.loader.api.FabricLoader

/**
 * The game entry point of the end-to-end run: Fabric Loader starts this class instead of the real
 * `Main` (`-Dpvz.game.mainClass`), so everything below runs inside the loader's class loader with the
 * mod from a real `mods/` directory already resolved, its entry point run (and therefore its plant
 * registered) and its `fabric.mod.json` dependencies checked.
 *
 * <p>Because the loader defines its own copies of the game, the API and the mod classes, this class
 * cannot report to the test through memory: every observation is appended to the file named by
 * `-Dpvz.test.report` and the JUnit side asserts on it.
 *
 * <p>Phases:
 * <ol>
 *   <li>what the loader resolved and that the mod's Kotlin entry point ran;</li>
 *   <li>a real headless board, exactly the way the game starts an adventure level;</li>
 *   <li>the plant definition: cost 0, chooser-visible, present in the registry;</li>
 *   <li>the planting rule on a Pumpkin, a Tall-nut, an empty square and a Peashooter square;</li>
 *   <li>placement: the ladder grid item appears on both supported squares, both base plants
 *       survive, and a second ladder on a used square is refused.</li>
 * </ol>
 */
class LadderPlantGameMain private constructor() {

    companion object {

        private const val PUMPKIN_COLUMN = 0
        private const val TALLNUT_COLUMN = 1
        private const val EMPTY_COLUMN = 2
        private const val PEASHOOTER_COLUMN = 3
        private const val ROW = 0

        /** A sun-countdown far beyond this run: no sun may fall from the sky by the game's own timer. */
        private const val NO_SKY_SUNS = 1_000_000

        /** Side of the offscreen target the draw paths are exercised against. */
        private const val DRAW_SIZE = 200

        private val REPORT = StringBuilder()

        private var sError = ""

        @JvmStatic
        fun main(args: Array<String>) {
            val aReportPath = Path.of(requiredProperty("pvz.test.report"))
            var aApp: LawnApp? = null
            try {
                loaderPhase()
                aApp = boardPhase()
                definitionPhase(aApp)
                rulePhase(aApp.mBoard)
                placementPhase(aApp.mBoard)
                drawPhase(aApp)
            } catch (aThrowable: Throwable) {
                sError = aThrowable.toString()
                aThrowable.printStackTrace()
            } finally {
                report("harnessError", if (sError.isEmpty()) "none" else sError)
                try {
                    Files.writeString(aReportPath, REPORT.toString())
                } catch (anException: Exception) {
                    anException.printStackTrace()
                }

                if (aApp != null) {
                    LawnApp.gLawnApp = null
                }
            }

            System.out.println("[ladder-plant] report written to " + aReportPath)
            System.out.println(REPORT)

            if (!sError.isEmpty()) {
                throw IllegalStateException("the ladder-plant harness failed: " + sError)
            }
        }

        // ---------------------------------------------------------------- phase 1

        /** What the loader resolved, and that the mod's own entry point ran. */
        private fun loaderPhase() {
            val aLoader = FabricLoader.getInstance()
            report("loaderVersion", aLoader.getModContainer("fabricloader")
                    .map { container -> container.metadata.version.friendlyString }
                    .orElse("<missing>"))
            report("gameVersion", aLoader.rawGameVersion)
            report("environment", aLoader.environmentType.name.lowercase(Locale.ROOT))

            val aModIds = ArrayList<String>()
            for (aContainer in aLoader.allMods) {
                aModIds.add(aContainer.metadata.id)
            }
            report("modsLoaded", TreeSet(aModIds).joinToString(","))
            report("ladderModLoaded", aLoader.isModLoaded(LadderPlantMod.MOD_ID))
            // Set by the mod's own entry point, which only Fabric Loader runs.
            report("ladderModEntrypointRan", LadderPlantMod.initialized)
        }

        // ---------------------------------------------------------------- phase 2

        /** A real level: the game's own `preNewGame` + `startPlaying`, headlessly. */
        private fun boardPhase(): LawnApp {
            val aApp = HeadlessLawn.LoadedReanimApp()
            aApp.mGameMode = GameMode.GAMEMODE_ADVENTURE
            HeadlessLawn.initialize(aApp)
            aApp.mPlayerInfo.mLevel = 1
            // Not a first-time player: no tutorial popups from the first plant.
            aApp.mPlayerInfo.mFinishedAdventure = 1

            aApp.preNewGame(GameMode.GAMEMODE_ADVENTURE, false)
            val aBoard = aApp.mBoard
            report("boardCreated", aBoard != null)
            aApp.startPlaying()
            aBoard.mMenuButton.mBtnNoDraw = false
            HeadlessLawn.chooseSeeds(aBoard)
            aBoard.mSunCountDown = NO_SKY_SUNS

            report("boardLevel", aBoard.mLevel)
            report("boardRows", Board.MAX_GRID_SIZE_Y)
            report("boardColumns", Board.MAX_GRID_SIZE_X)
            return aApp
        }

        // ---------------------------------------------------------------- phase 3

        /** The registration: the definition row, its cost and its chooser availability. */
        private fun definitionPhase(theApp: LawnApp) {
            val aSeedType = LadderPlantMod.seedType()
            val aDefinition = Plant.getPlantDefinition(aSeedType)
            report("seedType", aSeedType)
            report("seedTypeIsCustom", PlantRegistry.isCustomSeedType(aSeedType))
            report("plantRegistered", PlantRegistry.get(aSeedType).isPresent)
            report("registryCount", PlantRegistry.count())
            report("definitionPresent", aDefinition != null)
            report("definitionCost", aDefinition?.mSeedCost ?: -1)
            report("plantCost", Plant.getCost(aSeedType, SeedType.SEED_NONE))
            report("refreshTime", Plant.getRefreshTime(aSeedType, SeedType.SEED_NONE))
            report("chooserVisible", PlantRegistry.get(aSeedType).orElse(null)?.definition()?.mChooserVisible ?: false)
            report("hasSeedType", theApp.hasSeedType(aSeedType))
            report("reanimationType", aDefinition?.mReanimationType ?: -1)
            report("health", PlantRegistry.get(aSeedType).orElse(null)?.definition()?.mPlantHealth ?: -1)
        }

        // ---------------------------------------------------------------- phase 4

        /** The planting rule, on a Pumpkin, a Tall-nut, an empty square and a Peashooter square. */
        private fun rulePhase(theBoard: Board) {
            val aSeedType = LadderPlantMod.seedType()
            theBoard.addPlant(PUMPKIN_COLUMN, ROW, SeedType.SEED_PUMPKINSHELL, SeedType.SEED_NONE)
            theBoard.addPlant(TALLNUT_COLUMN, ROW, SeedType.SEED_TALLNUT, SeedType.SEED_NONE)
            theBoard.addPlant(PEASHOOTER_COLUMN, ROW, SeedType.SEED_PEASHOOTER, SeedType.SEED_NONE)

            report("pumpkinPresent", plantAt(theBoard, PUMPKIN_COLUMN)?.mSeedType == SeedType.SEED_PUMPKINSHELL)
            report("tallnutPresent", plantAt(theBoard, TALLNUT_COLUMN)?.mSeedType == SeedType.SEED_TALLNUT)
            report("peashooterPresent", plantAt(theBoard, PEASHOOTER_COLUMN)?.mSeedType == SeedType.SEED_PEASHOOTER)

            report("pumpkinReason", theBoard.canPlantAt(PUMPKIN_COLUMN, ROW, aSeedType))
            report("tallnutReason", theBoard.canPlantAt(TALLNUT_COLUMN, ROW, aSeedType))
            report("emptyReason", theBoard.canPlantAt(EMPTY_COLUMN, ROW, aSeedType))
            report("peashooterReason", theBoard.canPlantAt(PEASHOOTER_COLUMN, ROW, aSeedType))

            report("pumpkinPlantable", theBoard.canPlantAt(PUMPKIN_COLUMN, ROW, aSeedType) == PlantingReason.PLANTING_OK)
            report("tallnutPlantable", theBoard.canPlantAt(TALLNUT_COLUMN, ROW, aSeedType) == PlantingReason.PLANTING_OK)
            report("emptyRefused", theBoard.canPlantAt(EMPTY_COLUMN, ROW, aSeedType) != PlantingReason.PLANTING_OK)
            report("peashooterRefused", theBoard.canPlantAt(PEASHOOTER_COLUMN, ROW, aSeedType) != PlantingReason.PLANTING_OK)
        }

        // ---------------------------------------------------------------- phase 5

        /** Placement and its one visible effect: the game's own ladder grid item. */
        private fun placementPhase(theBoard: Board) {
            val aSeedType = LadderPlantMod.seedType()
            report("ladderAtPumpkinBefore", theBoard.getLadderAt(PUMPKIN_COLUMN, ROW) != null)
            report("ladderAtTallnutBefore", theBoard.getLadderAt(TALLNUT_COLUMN, ROW) != null)

            val aPumpkinLadder = theBoard.addPlant(PUMPKIN_COLUMN, ROW, aSeedType, SeedType.SEED_NONE)
            report("pumpkinLadderPlaced", aPumpkinLadder != null)
            report("pumpkinLadderSeedType", aPumpkinLadder.mSeedType)
            report("ladderAtPumpkin", theBoard.getLadderAt(PUMPKIN_COLUMN, ROW) != null)

            val aTallnutLadder = theBoard.addPlant(TALLNUT_COLUMN, ROW, aSeedType, SeedType.SEED_NONE)
            report("tallnutLadderPlaced", aTallnutLadder != null)
            report("tallnutLadderSeedType", aTallnutLadder.mSeedType)
            report("ladderAtTallnut", theBoard.getLadderAt(TALLNUT_COLUMN, ROW) != null)

            // The plants under the ladders are still there: the ladder is planted on top, not in
            // place of them.
            report("pumpkinAliveUnderLadder", plantAt(theBoard, PUMPKIN_COLUMN)?.mSeedType == SeedType.SEED_PUMPKINSHELL)
            report("tallnutAliveUnderLadder", plantAt(theBoard, TALLNUT_COLUMN)?.mSeedType == SeedType.SEED_TALLNUT)
            report("tallnutAliveUnderLadderHealth", plantAt(theBoard, TALLNUT_COLUMN)?.mPlantHealth ?: -1)

            // The ladder plant itself is in the cell (it is a flying-position plant, so the normal
            // lookup does not see it).
            report("ladderPlantInPumpkinCell",
                    theBoard.getTopPlantAt(PUMPKIN_COLUMN, ROW, PlantPriority.TOPPLANT_ONLY_FLYING)?.mSeedType == aSeedType)
            report("ladderPlantInTallnutCell",
                    theBoard.getTopPlantAt(TALLNUT_COLUMN, ROW, PlantPriority.TOPPLANT_ONLY_FLYING)?.mSeedType == aSeedType)
            report("ladderPlantHealth", aPumpkinLadder.mPlantHealth)

            // A second ladder on the same square is refused by the rule.
            report("secondLadderReason", theBoard.canPlantAt(PUMPKIN_COLUMN, ROW, aSeedType))
            report("secondLadderRefused", theBoard.canPlantAt(PUMPKIN_COLUMN, ROW, aSeedType) != PlantingReason.PLANTING_OK)

            // The other squares are still refused after the supported ones were planted.
            report("emptyStillRefused", theBoard.canPlantAt(EMPTY_COLUMN, ROW, aSeedType) != PlantingReason.PLANTING_OK)
            report("peashooterStillRefused", theBoard.canPlantAt(PEASHOOTER_COLUMN, ROW, aSeedType) != PlantingReason.PLANTING_OK)
        }

        // ---------------------------------------------------------------- phase 6

        /**
         * The two draw paths that used to crash on a custom seed with no art of its own: the almanac
         * preview ({@code Plant::Draw} on a plant with no body reanimation) and the seed cursor
         * ({@code CursorObject::Draw} -> {@code Plant::DrawSeedType}).
         *
         * <p>Both draw into an offscreen image, exactly like the almanac draws into the window. The
         * plant declared itself invisible, so its own draw must leave every pixel untouched; the
         * cursor must find the packet art the registration pinned instead of a {@code null} image.
         */
        private fun drawPhase(theApp: LawnApp) {
            val aSeedType = LadderPlantMod.seedType()
            report("customSeedType", PlantRegistry.isCustomSeedType(aSeedType))
            report("seedImagePresent", Plant.getImage(aSeedType) != null)
            report("plantInvisible", PlantRegistry.get(aSeedType).orElse(null)?.definition()?.mInvisible ?: false)

            // The almanac's setup: a plant of the seed type that is not on a board, drawn by the game.
            val aPlant = Plant()
            aPlant.mBoard = null
            aPlant.mIsOnBoard = false
            aPlant.plantInitialize(0, 0, aSeedType, SeedType.SEED_NONE)
            val aPlantTarget = MemoryImage(theApp)
            aPlantTarget.create(DRAW_SIZE, DRAW_SIZE)
            val aPlantGraphics = Graphics(aPlantTarget)
            report("almanacPlantDrawError", drawError { aPlant.draw(aPlantGraphics) })
            val aPixels = aPlantTarget.getBits()
            var aDrawnPixels = 0
            for (i in 0 until DRAW_SIZE * DRAW_SIZE) {
                if (aPixels[i] != 0) {
                    aDrawnPixels++
                }
            }
            report("almanacPlantDrawnPixels", aDrawnPixels)

            // The cursor: the seed held on the mouse after a click on its packet.
            val aCursor = CursorObject()
            aCursor.mType = aSeedType
            aCursor.mImitaterType = SeedType.SEED_NONE
            aCursor.mCursorType = CursorType.CURSOR_TYPE_PLANT_FROM_BANK
            val aCursorTarget = MemoryImage(theApp)
            aCursorTarget.create(DRAW_SIZE, DRAW_SIZE)
            val aCursorGraphics = Graphics(aCursorTarget)
            report("cursorDrawError", drawError { aCursor.draw(aCursorGraphics) })
        }

        /** @return {@code "none"} when the draw completed, or the failure it threw */
        private fun drawError(theDraw: () -> Unit): String {
            return try {
                theDraw()
                "none"
            } catch (aThrowable: Throwable) {
                aThrowable.toString()
            }
        }

        // ---------------------------------------------------------------- helpers

        private fun plantAt(theBoard: Board, theColumn: Int): Plant? {
            for (aPlant in theBoard.mPlants) {
                if (!aPlant.mDead && aPlant.mPlantCol == theColumn && aPlant.mRow == ROW
                        && aPlant.mSeedType != LadderPlantMod.seedType()) {
                    return aPlant
                }
            }
            return null
        }

        // ---------------------------------------------------------------- reporting

        private fun requiredProperty(theName: String): String {
            val aValue = System.getProperty(theName, "")
            if (aValue.isBlank()) {
                throw IllegalStateException("-D" + theName + " is required")
            }
            return aValue
        }

        private fun report(theKey: String, theValue: Any?) {
            REPORT.append(theKey).append('=').append(theValue).append('\n')
        }
    }
}
