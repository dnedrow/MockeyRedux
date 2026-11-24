package io.github.dnedrow.mockey.core.storage

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What becomes of the definitions location when a save fails, and when the program is ended in the middle of one. */
class FileStorageFailureTest {
    @TempDir
    lateinit var directory: Path

    /** Ends a save as the end of the program does: nothing of the save gets to tidy up after it. */
    private class Killed : Error("The program was ended.")

    private val silver = Scenario(id = ScenarioId("scn-silver"), name = "silver customer", responseBody = """{"tier":"silver"}""")
    private val paid = Scenario(id = ScenarioId("scn-paid"), name = "paid", responseBody = "paid in full")
    private val declined = Scenario(id = ScenarioId("scn-declined"), name = "declined")
    private val payments =
        Service(id = ServiceId("svc-payments"), name = "Payments", mockUrl = "payments", scenarios = listOf(paid, declined))

    /**
     * The sample definitions after changes of every kind: a service and a scenario are changed, one of each
     * is new, one of each is gone, and so are the plan and some settings. To save them takes 10 writes, 7
     * moves, and 3 deletions.
     */
    private val changed =
        Definitions(
            services =
                listOf(
                    Sample.customers.copy(
                        delayMillis = 250,
                        scenarios = listOf(Sample.gold.copy(statusCode = 202, responseBody = "changed"), Sample.byRules, silver),
                        errorScenarioId = null,
                    ),
                    payments,
                ),
            twists = listOf(Sample.twist),
            settings = Settings(enabledTwistId = Sample.twist.id),
        )

    private val leftover = Regex("""\..+\.[0-9a-f]{16}\.tmp""")

    /** Returns a new location that holds the sample definitions. */
    private fun sampleLocation(name: Any): Path = directory.resolve("location-$name").also { stored(it, Sample.definitions) }

    /** What is in the location [root], without what a save wrote under other names and did not move into place. */
    private fun definitionFilesOf(root: Path): Map<String, String> =
        treeOf(root).filterKeys { path -> path.split('/').none(leftover::matches) }

    /**
     * Saves [changed] to the location [root] with a storage that has loaded it. The save is stopped with what
     * [stop] gives at the step with the number [stopAt] among the steps of the kinds [kinds]. Returns what
     * stopped the save, or `null` when the save was done before that step.
     */
    private fun stoppedSave(
        root: Path,
        stopAt: Int,
        kinds: Set<FileOperation.Kind> = FileOperation.Kind.entries.toSet(),
        stop: () -> Throwable,
    ): Throwable? {
        var step = 0
        val storage = FileDefinitionStorage(root) { if (it.kind in kinds && step++ == stopAt) throw stop() }
        return runBlocking {
            storage.load()
            runCatching { storage.save(changed) }.exceptionOrNull()
        }
    }

    // A save that fails

    @Test
    fun aSaveThatCannotWriteLeavesTheLocationAsItWas() {
        var writes = 0
        while (true) {
            val root = sampleLocation(writes)
            val before = treeOf(root)

            val failure = stoppedSave(root, writes, setOf(FileOperation.Kind.STAGE)) { IOException("No space left on device") } ?: break

            assertIs<StorageException>(failure)
            assertEquals("The definitions could not be saved to $root: No space left on device", failure.message)
            assertEquals(before, treeOf(root), "The location after the write $writes failed")
            writes++
        }
        assertEquals(10, writes)
    }

    @Test
    fun aSaveThatCannotWriteCreatesNoLocation() {
        val root = directory.resolve("new")

        val failure = stoppedSave(root, stopAt = 0) { IOException("No space left on device") }

        assertIs<StorageException>(failure)
        assertFalse(root.exists())
    }

    @Test
    fun aSaveAfterOneThatFailedHalfwayPutsTheLocationRight() {
        var steps = 0
        while (true) {
            val root = sampleLocation(steps)
            var step = 0
            var fails = true
            val storage =
                FileDefinitionStorage(root) {
                    if (fails && it.kind != FileOperation.Kind.STAGE &&
                        step++ == steps
                    ) {
                        throw IOException("Input/output error")
                    }
                }

            val failure =
                runBlocking {
                    storage.load()
                    runCatching { storage.save(changed) }.exceptionOrNull()
                } ?: break
            fails = false
            runBlocking { storage.save(changed) }

            assertIs<StorageException>(failure)
            assertEquals(changed, loaded(root), "The location after the step $steps failed and the save was repeated")
            assertEquals(definitionFilesOf(root), treeOf(root))
            steps++
        }
        assertEquals(10, steps)
    }

    @Test
    fun theLocationCannotBeAFile() =
        runTest {
            val file = directory.resolve("definitions.txt")
            Files.write(file, "Not a directory.".toByteArray())
            val storage = FileDefinitionStorage(file)

            val onLoad = assertFailsWith<StorageException> { storage.load() }
            val onSave = assertFailsWith<StorageException> { storage.save(Sample.definitions) }

            assertEquals("The definitions location $file is no directory.", onLoad.message)
            assertEquals(onLoad.message, onSave.message)
            assertEquals("Not a directory.", String(Files.readAllBytes(file)))
        }

    @Test
    fun aSaveThatCannotCreateADirectoryFailsAndLeavesTheLocationAsItWas() =
        runTest {
            val root = directory.resolve("definitions")
            write(root, "services", "A file where the directory of the services belongs.")
            val before = treeOf(root)
            val storage = FileDefinitionStorage(root)

            val loaded = storage.load()
            val failure = assertFailsWith<StorageException> { storage.save(Sample.definitions) }

            assertEquals(Definitions.EMPTY, loaded)
            assertEquals("The definitions could not be saved to $root: ${root.resolve("services")} is in the way.", failure.message)
            assertEquals(before, treeOf(root))
        }

    @Test
    fun definitionsThatHoldAnIdentifierTwiceAreNotSaved() =
        runTest {
            val root = sampleLocation("twice")
            val before = treeOf(root)
            val storage = FileDefinitionStorage(root)
            val twoServices = Sample.definitions.copy(services = Sample.definitions.services + Sample.orders.copy(name = "More orders"))
            val twoScenarios =
                Sample.definitions.copy(
                    services = listOf(Sample.customers.copy(scenarios = listOf(Sample.gold, Sample.gold))),
                )

            val failures =
                listOf(
                    twoServices,
                    twoScenarios,
                ).map { definitions -> assertFailsWith<StorageException> { storage.save(definitions) } }

            val cannotSave = "The definitions cannot be saved to $root: they hold the"
            assertEquals(
                listOf(
                    "$cannotSave service identifier svc-orders more than once.",
                    "$cannotSave scenario identifier scn-gold more than once.",
                ),
                failures.map { it.message },
            )
            assertEquals(before, treeOf(root))
        }

    // A save that is ended

    @Test
    fun aSaveThatIsEndedAtAnyStepLeavesEveryDefinitionFileAsItWasOrAsItIsToBe() {
        val old = treeOf(sampleLocation("old"))
        val new = treeOf(sampleLocation("new").also { stored(it, changed) })
        var steps = 0
        while (true) {
            val root = sampleLocation(steps)

            val failure = stoppedSave(root, steps) { Killed() } ?: break
            val files = definitionFilesOf(root)
            val afterRestart = loaded(root)

            assertIs<Killed>(failure)
            files.forEach { (path, bytes) ->
                assertTrue(bytes == old[path] || bytes == new[path], "The file $path after the save was ended at the step $steps")
            }
            assertTrue(afterRestart.services.all { it.id in (Sample.definitions.services + changed.services).map(Service::id) })
            steps++
        }
        assertEquals(20, steps)
    }

    @Test
    fun aSaveAfterOneThatWasEndedPutsTheLocationRightAndClearsWhatWasLeftBehind() {
        var steps = 0
        while (true) {
            val root = sampleLocation(steps)

            stoppedSave(root, steps) { Killed() } ?: break
            stored(root, changed)

            assertEquals(changed, loaded(root), "The location after the save was ended at the step $steps and repeated")
            assertEquals(definitionFilesOf(root), treeOf(root))
            steps++
        }
        assertEquals(20, steps)
    }

    @Test
    fun whatASaveLeftBehindIsNoDefinitionAndIsClearedByTheNextSaveThatWrites() =
        runTest {
            val root = sampleLocation("leftovers")
            val leftovers =
                mapOf(
                    ".mockey.json.0123456789abcdef.tmp" to "{ half a file",
                    "services/customers/.service.json.0123456789abcdef.tmp" to "{ half a file",
                    "services/customers/scenarios/.gold-customer.body.0123456789abcdef.tmp" to "half a body",
                    "services/.payments.0123456789abcdef.tmp/service.json" to
                        """{"id": "svc-payments", "name": "Payments", "mockUrl": "payments"}""",
                    "services/.payments.0123456789abcdef.tmp/scenarios/paid.json" to """{"id": "scn-paid", "name": "paid"}""",
                )
            val notOfASave =
                mapOf(
                    "services/customers/scenarios/.draft.json" to "Not a scenario.",
                    "services/.orders.tmp" to "Not of a save.",
                )
            (leftovers + notOfASave).forEach { (path, text) -> write(root, path, text) }
            val withLeftovers = treeOf(root)
            val storage = FileDefinitionStorage(root)

            val loaded = storage.load()
            storage.save(loaded)
            val afterSaveOfNothing = treeOf(root)
            storage.save(loaded.copy(plans = emptyList(), settings = loaded.settings.copy(defaultPlanId = null)))

            assertEquals(Sample.definitions, loaded)
            assertEquals(withLeftovers, afterSaveOfNothing)
            assertEquals((Sample.files + notOfASave.keys).sorted(), filesOf(root))
        }
}
