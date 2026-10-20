package cn.enaium.fabricpvz.ladder

import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

import cn.enaium.fabricpvz.provider.PvzGameProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * End-to-end verification of the Ladder plant on the real Fabric Loader.
 *
 * <p>The test does what a launcher does: it copies the <em>built mod jar</em> into a real `mods/`
 * directory and starts `net.fabricmc.loader.impl.launch.knot.KnotClient` in a second JVM with the
 * game, the loader (plus Mixin and ASM, from the distribution's manifest) and the workspace API on
 * the class path. The loader resolves the mod, runs its `ModInitializer` (which registers the
 * plant), and only then calls the game entry point, which this test redirects to
 * [cn.enaium.fabricpvz.ladder.test.LadderPlantGameMain] with `-Dpvz.game.mainClass`.
 *
 * <p>Running inside the loader is the whole point: registration writes into the game's definition
 * table through the API's Mixins, and the harness then plants real plants on a real board. The
 * harness reports through a file because the loader defines its own copies of the game and the mod
 * classes; every assertion below is made on that report.
 */
class LadderPlantEndToEndTest {

    companion object {

        /** Launcher main class of the real Fabric Loader (client environment), from its own manifest. */
        private const val LAUNCHER_CLASS = "net.fabricmc.loader.impl.launch.knot.KnotClient"

        /** System property carrying the class path the launcher has to run with. */
        private const val FORK_CLASSPATH_PROPERTY = "pvz.test.forkClasspath"

        /** System property naming the built mod jar. */
        private const val MOD_JAR_PROPERTY = "pvz.test.modJar"

        /** Mod ids the loader must resolve next to this mod. */
        private val DEPENDENCY_MODS = listOf(
                "fabricloader", "pvz-portable", "fabric-language-kotlin", "fabric-api", "api-base", "api-plant"
        )
    }

    @TempDir
    lateinit var tmp: Path

    @Test
    fun plantsTheLadderOnPumpkinAndTallnutFromTheInstalledModJar() {
        val modJar = Path.of(requiredProperty(MOD_JAR_PROPERTY))
        assertTrue(Files.isRegularFile(modJar), "the mod jar must be built first: $modJar")
        assertModJarContents(modJar)

        val mods = Files.createDirectories(tmp.resolve("mods"))
        val installed = Files.copy(modJar, mods.resolve(modJar.fileName))
        assertTrue(Files.size(installed) > 0, "the installed mod jar must not be empty")

        val runDir = Files.createDirectories(tmp.resolve("run"))
        val report = tmp.resolve("ladder-report.txt")
        val output = tmp.resolve("loader-output.txt")

        val exitCode = launch(mods, runDir, report, output)
        assertEquals(0, exitCode, "Fabric Loader must boot the game with the mod: " + failureMessage(output, report))

        val values = readReport(report)
        assertEquals("none", values["harnessError"], "the harness must run to the end")

        // 1) The loader resolved the game, the language module, the API and this mod jar, and ran its
        //    entry point.
        assertEquals(PvzGameProvider.DEFAULT_GAME_VERSION, values["gameVersion"],
                "the provider must report the game's version")
        assertEquals("client", values["environment"])
        assertEquals("true", values["ladderModLoaded"])
        assertEquals("true", values["ladderModEntrypointRan"])
        for (id in DEPENDENCY_MODS) {
            assertTrue(values["modsLoaded"]!!.split(",").contains(id),
                    "mod $id must be loaded, got ${values["modsLoaded"]}")
        }

        // 2) The plant is registered as a custom seed type with its zero cost and its definition row.
        assertEquals("true", values["plantRegistered"], "the entry point must register the Ladder")
        assertEquals("true", values["seedTypeIsCustom"], "the seed type must be in the mod range")
        assertEquals("1", values["registryCount"], "the mod registers exactly one plant")
        assertEquals("true", values["definitionPresent"], "the definition row must be in the game's table")
        assertEquals("0", values["definitionCost"], "the Ladder costs 0 sun")
        assertEquals("0", values["plantCost"], "the game's own cost lookup must answer 0")
        assertEquals("true", values["chooserVisible"], "the seed must be offered by the seed chooser")
        assertEquals("true", values["hasSeedType"], "the game must make the seed available to the player")
        assertEquals("4000", values["health"], "the registration declares the high health")

        // 3) The planting rule: allowed on a Pumpkin and on a Tall-nut, refused everywhere else.
        assertEquals("true", values["pumpkinPresent"])
        assertEquals("true", values["tallnutPresent"])
        assertEquals("true", values["peashooterPresent"])
        assertEquals("0", values["pumpkinReason"],
                "a square holding a Pumpkin must report PLANTING_OK, got ${values["pumpkinReason"]}")
        assertEquals("0", values["tallnutReason"],
                "a square holding a Tall-nut must report PLANTING_OK, got ${values["tallnutReason"]}")
        assertEquals("true", values["emptyRefused"], "an empty square must be refused")
        assertEquals("true", values["peashooterRefused"], "a Peashooter square must be refused")
        assertEquals("true", values["pumpkinPlantable"])
        assertEquals("true", values["tallnutPlantable"])

        // 4) Placement: the game's own ladder grid item appears on both supported squares, the base
        //    plants survive, and the ladder plant itself is in the cells.
        assertEquals("false", values["ladderAtPumpkinBefore"])
        assertEquals("false", values["ladderAtTallnutBefore"])
        assertEquals("true", values["pumpkinLadderPlaced"])
        assertEquals("true", values["ladderAtPumpkin"], "placing the Ladder must add the ladder grid item")
        assertEquals("true", values["tallnutLadderPlaced"])
        assertEquals("true", values["ladderAtTallnut"], "placing the Ladder must add the ladder grid item")
        assertEquals(values["seedType"], values["pumpkinLadderSeedType"])
        assertEquals(values["seedType"], values["tallnutLadderSeedType"])
        assertEquals("true", values["pumpkinAliveUnderLadder"], "the Pumpkin must not be replaced")
        assertEquals("true", values["tallnutAliveUnderLadder"], "the Tall-nut must not be replaced")
        assertEquals("true", values["ladderPlantInPumpkinCell"])
        assertEquals("true", values["ladderPlantInTallnutCell"])
        assertEquals("4000", values["ladderPlantHealth"], "the placed plant must use the declared health")

        // 5) A square carries one ladder at most, and the other squares stay refused.
        assertEquals("true", values["secondLadderRefused"], "a second ladder on the same square must be refused")
        assertEquals("true", values["emptyStillRefused"])
        assertEquals("true", values["peashooterStillRefused"])

        // 6) Drawing the custom plant: the almanac preview and the seed cursor used to crash with a
        //    null image. The registration pins the packet art (every seed-art path has an image) and
        //    declares the plant invisible, so its own draw is a no-op.
        assertEquals("true", values["customSeedType"])
        assertEquals("true", values["seedImagePresent"],
                "the seed-art paths must find the packet art: ${values["seedImagePresent"]}")
        assertEquals("true", values["plantInvisible"])
        assertEquals("none", values["almanacPlantDrawError"],
                "the almanac must draw the Ladder without an exception: ${values["almanacPlantDrawError"]}")
        assertEquals("0", values["almanacPlantDrawnPixels"], "an invisible plant draws nothing of its own")
        assertEquals("none", values["cursorDrawError"],
                "the seed cursor must draw the Ladder without an exception: ${values["cursorDrawError"]}")
    }

    /** Asserts the jar really carries this mod and not, say, a Kotlin runtime. */
    private fun assertModJarContents(theJar: Path) {
        val entries = JarFile(theJar.toFile()).use { aJar ->
            aJar.entries().asSequence().map { it.name }.toList()
        }
        assertTrue(entries.contains("fabric.mod.json"), "the jar must carry fabric.mod.json")
        assertTrue(entries.contains("cn/enaium/fabricpvz/ladder/LadderPlantMod.class"),
                "the entry point class")
        assertTrue(entries.any { it.startsWith("META-INF/") && it.endsWith(".kotlin_module") },
                "the jar must carry the Kotlin compilation metadata of the mod's own sources, got $entries")
        assertTrue(entries.none { it.startsWith("kotlin/") },
                "a mod jar must bundle no stdlib classes, got $entries")
    }

    /**
     * Starts the real Fabric Loader in a second JVM, exactly like the launcher would.
     *
     * @return the launcher's exit code
     */
    private fun launch(mods: Path, runDir: Path, report: Path, output: Path): Int {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command = ArrayList<String>()
        command.add(java)
        command.add("--enable-native-access=ALL-UNNAMED")

        if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("mac")) {
            // SDL (LWJGL's bindings) needs the first thread.
            command.add("-XstartOnFirstThread")
        }

        command.add("-cp")
        command.add(forkClasspath())
        // The harness entry point lives in this project's test classes: the provider adds them to the
        // loader's class loader so the harness and the game classes it inspects are all defined by it.
        command.add("-Dpvz.extraClasspath=" + harnessDirectory())
        // Development mode: the loader also discovers mods that are on the class path (the API
        // aggregate and the language module). The mod under test is deliberately NOT there: it is only
        // in the mods directory, like a player's jar.
        command.add("-Dfabric.development=true")
        // This port is the only provider on the class path.
        command.add("-Dfabric.skipMcProvider=true")
        // A crash must exit instead of opening the loader's Swing error window.
        command.add("-Dfabric.noGui=true")
        command.add("-Dpvz.gameDir=" + runDir)
        command.add("-Dfabric.modsFolder=" + mods)
        command.add("-Dpvz.game.mainClass=cn.enaium.fabricpvz.ladder.test.LadderPlantGameMain")
        command.add("-Dpvz.test.report=" + report)
        command.add(LAUNCHER_CLASS)
        command.add("-savedir")
        command.add(runDir.resolve("userdata").toString())

        val builder = ProcessBuilder(command)
                .directory(tmp.toFile())
                .redirectOutput(output.toFile())
                .redirectErrorStream(true)
        System.out.println("[ladder-plant-e2e] boot command:\n  " + command.joinToString(" \\\n  "))
        val process = builder.start()

        if (!process.waitFor(15L, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw AssertionError("Fabric Loader did not finish; launcher output:\n" + read(output))
        }

        System.out.println("[ladder-plant-e2e] " + LAUNCHER_CLASS + " exited with " + process.exitValue())
        System.out.println("[ladder-plant-e2e] launcher output:\n" + read(output))
        return process.exitValue()
    }

    /**
     * The class path the launcher runs with: the test classes, the loader, the game, the game
     * provider and the API - but not this project's own main output, so the mod can only come from
     * the mods directory.
     */
    private fun forkClasspath(): String {
        val classpath = System.getProperty(FORK_CLASSPATH_PROPERTY, "")
        if (classpath.isBlank()) {
            fail<Nothing>("-D$FORK_CLASSPATH_PROPERTY is not set (run through Gradle)")
        }
        return classpath
    }

    /** The directory holding the harness classes (this test's own output directory). */
    private fun harnessDirectory(): String {
        try {
            return Path.of(LadderPlantEndToEndTest::class.java.protectionDomain.codeSource.location.toURI())
                    .toString()
        } catch (e: Exception) {
            throw IllegalStateException("cannot locate the harness classes", e)
        }
    }

    private fun readReport(report: Path): Map<String, String> {
        assertTrue(Files.isRegularFile(report), "the harness must have written its report")
        return parse(Files.readString(report))
    }

    private fun parse(content: String): Map<String, String> {
        val values = HashMap<String, String>()
        for (line in content.split("\n")) {
            val equals = line.indexOf('=')
            if (equals > 0) {
                values[line.substring(0, equals)] = line.substring(equals + 1).trim()
            }
        }
        return values
    }

    private fun read(path: Path): String {
        return try {
            if (Files.exists(path)) Files.readString(path) else "<no output>"
        } catch (e: Exception) {
            "<unreadable: $e>"
        }
    }

    private fun failureMessage(output: Path, report: Path): String {
        var tail = read(output)
        val keep = 4000
        if (tail.length > keep) {
            tail = "..." + tail.substring(tail.length - keep)
        }
        return "\n--- launcher output (tail) ---\n" + tail +
                "\n--- report ---\n" + (if (Files.exists(report)) Files.readString(report) else "<none>")
    }

    private fun requiredProperty(theName: String): String {
        val value = System.getProperty(theName, "")
        assertNotNull(value, "-D$theName is required")
        assertTrue(value.isNotBlank(), "-D$theName is required")
        return value
    }
}
