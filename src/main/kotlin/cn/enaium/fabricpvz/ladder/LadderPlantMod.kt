package cn.enaium.fabricpvz.ladder

import cn.enaium.fabricpvz.api.plant.PlantType
import cn.enaium.fabricpvz.portable.lawn.Board
import cn.enaium.fabricpvz.portable.lawn.ImageID
import cn.enaium.fabricpvz.portable.lawn.Plant
import cn.enaium.fabricpvz.portable.lawn.PlantPriority
import cn.enaium.fabricpvz.portable.lawn.PlantingReason
import cn.enaium.fabricpvz.portable.lawn.ReanimationType
import cn.enaium.fabricpvz.portable.lawn.SeedType
import net.fabricmc.api.ModInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Mod entry point (Fabric Loader's [ModInitializer]): registers the **Ladder** plant.
 *
 * <p>The plant is deliberately thin. It costs 0 sun and draws nothing of its own
 * ([ReanimationType.REANIM_NONE]); the visible ladder is the game's own ladder grid item, which the
 * placement callback adds to the square the plant lands on. Zombies already know how to climb it.
 *
 * <p>Two rules make it "plantable only on a Pumpkin or a Tall-nut":
 * <ul>
 *   <li>[PlantType.Builder.plantingRule] answers [PlantingReason.PLANTING_OK] only when the target
 *       square holds one of those two plants and carries no ladder yet, and vetoes every other
 *       square. [Board.canPlantAt] consults it before its own rules; the board's built-in rules
 *       would otherwise reject any square that already holds a plant.</li>
 *   <li>[PlantType.Builder.flying] is how the built-in occupancy rules are stepped aside without
 *       declaring the Ladder an upgrade (which would make the game replace the Pumpkin/Tall-nut
 *       instead of planting the ladder on top of it): the game's own rule for a flying seed asks
 *       only whether the square already holds a flying plant. The rule above is what actually
 *       restricts the placement, and being flying also keeps zombies from picking the ladder as
 *       food.</li>
 * </ul>
 *
 * <p>This class is a Kotlin `class` with a no-argument constructor rather than an `object`:
 * Fabric Loader instantiates an entry point reflectively through that constructor.
 */
class LadderPlantMod : ModInitializer {

    override fun onInitialize() {
        val aType = plantType()
        initialized = true
        LOGGER.info("registered the Ladder plant as seed type {} ({} sun)", aType.seedType(), 0)
    }

    companion object {

        /** Fabric mod id, as declared in `fabric.mod.json`. */
        const val MOD_ID: String = "ladder-plant"

        /** The seed type's cost, in sun. */
        const val SUN_COST: Int = 0

        /** Health: a zombie standing on the square can never eat the ladder faster than intended. */
        const val HEALTH: Int = 4000

        /** The mod's logger, named by the mod id so its lines read `[ladder-plant]`. */
        private val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

        private val LOCK = Any()

        private var plant: PlantType? = null

        /** Set by [onInitialize]: proves to the end-to-end harness that Fabric Loader ran this entry point. */
        @Volatile
        var initialized: Boolean = false
            private set

        /**
         * @return the registered plant, registering it on the first call (the entry point does that,
         *     so the harness may call this before or after the loader ran [onInitialize])
         */
        @JvmStatic
        fun plantType(): PlantType {
            synchronized(LOCK) {
                val aRegistered = plant
                if (aRegistered != null) {
                    return aRegistered
                }

                val aType = createPlant()
                plant = aType
                return aType
            }
        }

        /** @return the game's `SeedType` value of the Ladder, for `Board.addPlant` and the packet. */
        @JvmStatic
        fun seedType(): Int = plantType().seedType()

        private fun createPlant(): PlantType {
            return PlantType.builder("ladder")
                    .name("Ladder")
                    .tooltip("0 sun. Plant on a Pumpkin or a Tall-nut: zombies climb over it.")
                    .cost(SUN_COST)
                    .health(HEALTH)
                    .reanimation(ReanimationType.REANIM_NONE)
                    // The retail ladder art, pinned to the packet: the plant itself draws nothing.
                    .packetArt(ImageID.IMAGE_REANIM_ZOMBIE_LADDER_1, 0)
                    .invisible(true)
                    .chooserVisible(true)
                    .flying(true)
                    .plantingRule(::canPlantLadderAt)
                    .onPlanted(::addLadderToSquare)
                    .register()
        }

        /**
         * The planting rule of the Ladder: a Pumpkin or a Tall-nut has to be under it, and the square
         * must not already carry a ladder.
         *
         * @return [PlantingReason.PLANTING_OK] for exactly those squares, and a veto reason for every
         *     other one
         */
        private fun canPlantLadderAt(theBoard: Board, theGridX: Int, theGridY: Int,
                                     theReason: Int): Int {
            val aPumpkin = theBoard.getTopPlantAt(theGridX, theGridY, PlantPriority.TOPPLANT_ONLY_PUMPKIN)
            val aNormalPlant = theBoard.getTopPlantAt(theGridX, theGridY, PlantPriority.TOPPLANT_ONLY_NORMAL_POSITION)
            val aSupported = (aPumpkin != null && aPumpkin.mSeedType == SeedType.SEED_PUMPKINSHELL)
                    || (aNormalPlant != null && aNormalPlant.mSeedType == SeedType.SEED_TALLNUT)
            if (!aSupported || theBoard.getLadderAt(theGridX, theGridY) != null) {
                // A veto: returning the reason unchanged would let the game's own rules decide, and
                // they would happily plant a flying seed on an empty square.
                return PlantingReason.PLANTING_NOT_HERE
            }

            return PlantingReason.PLANTING_OK
        }

        /**
         * Placement callback: puts the game's own ladder grid item on the plant's square, which is
         * exactly what the ladder zombie does to a plant it walks over.
         *
         * <p>Idempotent: the callback also runs again for every plant a restored save carries, and a
         * square must never hold two ladders.
         */
        private fun addLadderToSquare(thePlant: Plant) {
            val aBoard = thePlant.mBoard ?: return
            if (aBoard.getLadderAt(thePlant.mPlantCol, thePlant.mRow) == null) {
                aBoard.addALadder(thePlant.mPlantCol, thePlant.mRow)
            }
        }
    }
}
