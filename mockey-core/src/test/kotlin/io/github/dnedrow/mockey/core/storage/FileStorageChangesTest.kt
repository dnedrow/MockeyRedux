package io.github.dnedrow.mockey.core.storage

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which files a save writes and deletes: those of the definitions that changed, and no others. And what the
 * names of files have to do with the definitions in them: nothing.
 */
class FileStorageChangesTest {
    @TempDir
    lateinit var directory: Path

    private val root: Path
        get() = directory.resolve("definitions")

    /** The steps of the saves of [sampleStorage], such as `MOVE services/orders/service.json`. */
    private val steps = mutableListOf<String>()

    private val customersDirectory = "services/customers"
    private val goldFile = "$customersDirectory/scenarios/gold-customer.json"
    private val goldBodyFile = "$customersDirectory/scenarios/gold-customer.body"

    /** Returns a storage for a location that holds the sample definitions, which the storage has loaded as an engine does. */
    private suspend fun sampleStorage(): FileDefinitionStorage {
        stored(root, Sample.definitions)
        return watchedStorage(root, steps).also { it.load() }
    }

    /** The scenarios of the service `Customers` of these definitions. */
    private val Definitions.customerScenarios: List<Scenario>
        get() = services.first { it.id == Sample.customers.id }.scenarios

    private fun Definitions.withCustomers(change: (Service) -> Service): Definitions =
        copy(services = services.map { if (it.id == Sample.customers.id) change(it) else it })

    private fun Definitions.withGold(change: (Scenario) -> Scenario): Definitions =
        withCustomers { service -> service.copy(scenarios = service.scenarios.map { if (it.id == Sample.gold.id) change(it) else it }) }

    private fun scenario(
        name: String,
        body: String = "",
    ) = Scenario(id = ScenarioId.generate(), name = name, responseBody = body)

    private fun service(
        name: String,
        vararg scenarios: Scenario,
    ) = Service(id = ServiceId.generate(), name = name, mockUrl = name, scenarios = scenarios.toList())

    /** What is in the location apart from the files at [paths]. */
    private fun treeWithout(vararg paths: String): Map<String, String> = treeOf(root) - paths.toSet()

    // Only what changed is written

    @Test
    fun aChangeToAScenarioWritesTheFileOfThatScenarioAndNoOther() =
        runTest {
            val storage = sampleStorage()
            val others = treeWithout(goldFile)
            val changed = Sample.definitions.withGold { it.copy(statusCode = 202) }

            storage.save(changed)

            assertEquals(listOf("STAGE $goldFile", "MOVE $goldFile"), steps)
            assertEquals(others, treeWithout(goldFile))
            assertEquals(changed, loaded(root))
        }

    @Test
    fun aChangeToAResponseBodyWritesTheBodyFileAndNoOther() =
        runTest {
            val storage = sampleStorage()
            val others = treeWithout(goldBodyFile)
            val changed = Sample.definitions.withGold { it.copy(responseBody = """{"tier":"platinum"}""") }

            storage.save(changed)

            assertEquals(listOf("STAGE $goldBodyFile", "MOVE $goldBodyFile"), steps)
            assertEquals(others, treeWithout(goldBodyFile))
            assertEquals("""{"tier":"platinum"}""", textOf(root, goldBodyFile))
            assertEquals(changed, loaded(root))
        }

    @Test
    fun aChangeToAServiceWritesItsServiceFileAndNoOther() =
        runTest {
            val storage = sampleStorage()
            val others = treeWithout("$customersDirectory/service.json")
            val changed = Sample.definitions.withCustomers { it.copy(delayMillis = 250) }

            storage.save(changed)

            assertEquals(listOf("STAGE $customersDirectory/service.json", "MOVE $customersDirectory/service.json"), steps)
            assertEquals(others, treeWithout("$customersDirectory/service.json"))
            assertEquals(changed, loaded(root))
        }

    @Test
    fun aChangeToAPlanOrToASettingWritesTheMainFileAndNoOther() =
        runTest {
            val storage = sampleStorage()
            val others = treeWithout("mockey.json")
            val renamed = Sample.definitions.copy(plans = listOf(Sample.plan.copy(name = "Platinum path")))
            val withoutTwist = renamed.copy(settings = renamed.settings.copy(enabledTwistId = null))

            storage.save(renamed)
            storage.save(withoutTwist)

            assertEquals(List(2) { listOf("STAGE mockey.json", "MOVE mockey.json") }.flatten(), steps)
            assertEquals(others, treeWithout("mockey.json"))
            assertEquals(withoutTwist, loaded(root))
        }

    @Test
    fun aSaveWritesEverythingUnderOtherNamesBeforeItPutsAnythingInItsPlace() =
        runTest {
            val storage = sampleStorage()
            val changed =
                Sample.definitions
                    .withGold { it.copy(statusCode = 202, responseBody = "changed") }
                    .withCustomers { it.copy(delayMillis = 250) }
                    .copy(plans = emptyList(), settings = Sample.settings.copy(defaultPlanId = null))

            storage.save(changed)

            val files = listOf(goldBodyFile, goldFile, "$customersDirectory/service.json", "mockey.json")
            assertEquals(files.map { "STAGE $it" } + files.map { "MOVE $it" }, steps)
            assertEquals(changed, loaded(root))
        }

    // New definitions and deleted ones

    @Test
    fun aNewServiceGetsADirectoryOfItsOwnAllAtOnce() =
        runTest {
            val storage = sampleStorage()
            val before = treeOf(root)
            val payments = service("Payments", scenario("paid", "paid in full"), scenario("declined"))
            val changed = Sample.definitions.copy(services = Sample.definitions.services + payments)

            storage.save(changed)

            val added =
                listOf(
                    "scenarios/paid.body",
                    "scenarios/paid.json",
                    "scenarios/declined.json",
                    "service.json",
                ).map { "services/payments/$it" }
            assertEquals(added.map { "STAGE $it" } + "MOVE services/payments", steps)
            assertEquals(before, treeOf(root) - added.toSet())
            assertEquals(changed, loaded(root))
        }

    @Test
    fun aNewScenarioGetsFilesOfItsOwn() =
        runTest {
            val storage = sampleStorage()
            val before = treeOf(root)
            val silver = scenario("silver customer", """{"tier":"silver"}""")
            val changed = Sample.definitions.withCustomers { it.copy(scenarios = it.scenarios + silver) }

            storage.save(changed)

            val added = listOf("$customersDirectory/scenarios/silver-customer.body", "$customersDirectory/scenarios/silver-customer.json")
            assertEquals(added.map { "STAGE $it" } + added.map { "MOVE $it" }, steps)
            assertEquals(before, treeOf(root) - added.toSet())
            assertEquals(changed, loaded(root))
        }

    @Test
    fun aDeletedScenarioLosesItsFilesAfterNothingNamesItAnyMore() =
        runTest {
            val storage = sampleStorage()
            val changed =
                Sample.definitions
                    .withCustomers { it.copy(scenarios = it.scenarios - Sample.error, errorScenarioId = null) }
                    .copy(settings = Sample.settings.copy(universalErrorScenario = null))

            storage.save(changed)

            val expected =
                listOf(
                    "STAGE $customersDirectory/service.json",
                    "STAGE mockey.json",
                    "MOVE $customersDirectory/service.json",
                    "MOVE mockey.json",
                    "DELETE $customersDirectory/scenarios/error.json",
                    "DELETE $customersDirectory/scenarios/error.body",
                )
            assertEquals(expected, steps)
            assertEquals(
                Sample.files - setOf("$customersDirectory/scenarios/error.json", "$customersDirectory/scenarios/error.body"),
                filesOf(root),
            )
            assertEquals(changed, loaded(root))
        }

    @Test
    fun aDeletedServiceLosesItsDirectoryAndItsServiceFileFirst() =
        runTest {
            val storage = sampleStorage()
            val changed = Definitions(services = listOf(Sample.orders), twists = listOf(Sample.twist))

            storage.save(changed)

            val deleted = listOf("service.json", "scenarios/by-rules.json", "scenarios/error.json", "scenarios/error.body")
            assertEquals(listOf("STAGE mockey.json", "MOVE mockey.json") + deleted.map { "DELETE $customersDirectory/$it" }, steps.take(6))
            assertEquals(listOf("mockey.json", "services/orders/service.json"), filesOf(root))
            assertFalse(root.resolve(customersDirectory).exists())
            assertEquals(changed, loaded(root))
        }

    @Test
    fun aServiceThatLosesAllItsScenariosKeepsNoDirectoryForThem() =
        runTest {
            val storage = sampleStorage()
            val changed =
                Sample.definitions.withCustomers {
                    it.copy(
                        scenarios = emptyList(),
                        defaultScenarioId = null,
                        errorScenarioId = null,
                    )
                }

            storage.save(changed)

            assertEquals(
                listOf("mockey.json", "$customersDirectory/service.json", "services/orders/service.json"),
                treeOf(root).keys.toList(),
            )
            assertEquals(changed, loaded(root))
        }

    @Test
    fun storingNoDefinitionsLeavesNoDefinitionFile() =
        runTest {
            val storage = sampleStorage()

            storage.save(Definitions.EMPTY)

            assertEquals(emptyMap(), treeOf(root))
            assertTrue(root.exists())
            assertEquals(Definitions.EMPTY, loaded(root))
        }

    @Test
    fun whatSomeoneElsePutIntoTheLocationIsLeftAlone() =
        runTest {
            val storage = sampleStorage()
            val foreign =
                mapOf(
                    "README.md" to "The definitions of our team.",
                    ".git/config" to "[core]",
                    "services/NOTES.txt" to "Ask before you delete a service.",
                    "services/orders/OWNERS" to "team-orders",
                    "$customersDirectory/scenarios/notes.txt" to "gold is the default.",
                )
            foreign.forEach { (path, text) -> write(root, path, text) }

            storage.save(Sample.definitions.copy(services = listOf(Sample.customers)).withGold { it.copy(statusCode = 202) })
            storage.save(Definitions.EMPTY)

            assertEquals(foreign.toSortedMap(), treeOf(root))
            assertEquals(Definitions.EMPTY, loaded(root))
        }

    // Names

    @Test
    fun similarNamesGetFilesOfTheirOwn() {
        val first = service("Order-Service", scenario("OK!", "first"), scenario("ok", "second"))
        val second = service("order service", scenario("ok", "third"))
        val third = service("ORDER_SERVICE")
        val definitions = Definitions(services = listOf(first, second, third))

        stored(root, definitions)

        val expected =
            listOf(
                "mockey.json",
                "services/order-service-2/scenarios/ok.body",
                "services/order-service-2/scenarios/ok.json",
                "services/order-service-2/service.json",
                "services/order-service-3/service.json",
                "services/order-service/scenarios/ok-2.body",
                "services/order-service/scenarios/ok-2.json",
                "services/order-service/scenarios/ok.body",
                "services/order-service/scenarios/ok.json",
                "services/order-service/service.json",
            )
        assertEquals(expected, filesOf(root))
        assertEquals(definitions, loaded(root))
    }

    @Test
    fun aNameThatSomethingInTheLocationHasIsNotGivenToADefinition() =
        runTest {
            val storage = sampleStorage()
            write(root, "services/Payments/README.md", "Not a service.")
            write(root, "$customersDirectory/scenarios/Silver.txt", "Not a scenario.")
            val silver = scenario("silver")
            val withSilver = Sample.definitions.withCustomers { it.copy(scenarios = it.scenarios + silver) }
            val changed = withSilver.copy(services = withSilver.services + service("payments"))

            storage.save(changed)

            assertTrue(isFile(root, "services/payments-2/service.json"))
            assertTrue(isFile(root, "$customersDirectory/scenarios/silver-2.json"))
            assertEquals("Not a service.", textOf(root, "services/Payments/README.md"))
            assertEquals(changed, loaded(root))
        }

    @Test
    fun aDefinitionThatIsRenamedKeepsItsFiles() =
        runTest {
            val storage = sampleStorage()
            val renamed = Sample.definitions.withGold { it.copy(name = "premium customer") }.withCustomers { it.copy(name = "Clients") }

            storage.save(renamed)

            val files = listOf(goldFile, "$customersDirectory/service.json")
            assertEquals(files.map { "STAGE $it" } + files.map { "MOVE $it" }, steps)
            assertEquals(Sample.files, filesOf(root))
            assertEquals(renamed, loaded(root))
        }

    // Identity is in the files

    @Test
    fun aDirectoryThatSomeoneRenamedIsReadAsBeforeAndWrittenToFromThenOn() =
        runTest {
            stored(root, Sample.definitions)
            Files.move(root.resolve(customersDirectory), root.resolve("services/Our CRM team"))
            val storage = watchedStorage(root, steps)

            val loaded = storage.load()
            storage.save(loaded.withCustomers { it.copy(delayMillis = 250) })

            assertEquals(Sample.definitions, loaded)
            assertEquals(listOf("STAGE services/Our CRM team/service.json", "MOVE services/Our CRM team/service.json"), steps)
            assertFalse(root.resolve(customersDirectory).exists())
            assertEquals(Sample.definitions.withCustomers { it.copy(delayMillis = 250) }, loaded(root))
        }

    @Test
    fun scenarioFilesThatSomeoneRenamedAreReadAsBeforeAndWrittenToFromThenOn() =
        runTest {
            stored(root, Sample.definitions)
            Files.move(root.resolve(goldFile), root.resolve("$customersDirectory/scenarios/Premium.json"))
            Files.move(root.resolve(goldBodyFile), root.resolve("$customersDirectory/scenarios/Premium.body"))
            val storage = watchedStorage(root, steps)

            val loaded = storage.load()
            storage.save(loaded.withGold { it.copy(responseBody = "changed") })

            assertEquals(Sample.definitions, loaded)
            assertEquals(
                listOf("STAGE $customersDirectory/scenarios/Premium.body", "MOVE $customersDirectory/scenarios/Premium.body"),
                steps,
            )
            assertEquals("changed", textOf(root, "$customersDirectory/scenarios/Premium.body"))
        }

    @Test
    fun aScenarioFileThatHoldsItsBodyIsReadAndLeftAsItIsUntilItsScenarioChanges() =
        runTest {
            stored(root, Sample.definitions)
            Files.delete(root.resolve(goldBodyFile))
            val byHand = """{"id": "scn-gold", "name": "gold customer", "responseBody": "by hand", "position": 0}"""
            write(root, goldFile, byHand)
            val storage = watchedStorage(root, steps)

            val loaded = storage.load()
            storage.save(loaded)
            val unchanged = textOf(root, goldFile)
            storage.save(loaded.withGold { it.copy(statusCode = 202) })

            val gold = loaded.customerScenarios.first()
            assertEquals("gold customer" to "by hand", gold.name to gold.responseBody)
            assertEquals(byHand, unchanged)
            assertEquals(listOf("STAGE $goldBodyFile", "STAGE $goldFile", "MOVE $goldBodyFile", "MOVE $goldFile"), steps)
            assertEquals("by hand", textOf(root, goldBodyFile))
            assertFalse("responseBody" in textOf(root, goldFile))
        }

    // The order

    @Test
    fun aNewDefinitionComesAfterTheOthersAndLeavesTheirFilesAsTheyAre() =
        runTest {
            val storage = sampleStorage()
            val silver = scenario("silver")
            val withoutByRules = Sample.definitions.withCustomers { it.copy(scenarios = listOf(Sample.gold, Sample.error)) }
            val withSilver = withoutByRules.withCustomers { it.copy(scenarios = it.scenarios + silver) }

            storage.save(withoutByRules)
            steps.clear()
            storage.save(withSilver)

            assertEquals(listOf("STAGE $customersDirectory/scenarios/silver.json", "MOVE $customersDirectory/scenarios/silver.json"), steps)
            assertTrue("\"position\": 3" in textOf(root, "$customersDirectory/scenarios/silver.json"))
            assertEquals(listOf("gold customer", "error", "silver"), loaded(root).customerScenarios.map { it.name })
        }

    @Test
    fun definitionsWithoutAPositionComeLastInTheOrderOfTheirIdentifiers() {
        write(root, "services/one/service.json", """{"id": "b", "name": "No position, b", "mockUrl": "b"}""")
        write(root, "services/two/service.json", """{"id": "a", "name": "No position, a", "mockUrl": "a"}""")
        write(root, "services/three/service.json", """{"id": "z", "name": "Position 5", "mockUrl": "z", "position": 5}""")
        write(root, "services/four/service.json", """{"id": "y", "name": "Position 5 as well", "mockUrl": "y", "position": 5}""")
        write(root, "services/five/service.json", """{"id": "x", "name": "Position -1", "mockUrl": "x", "position": -1}""")

        val names = loaded(root).services.map { it.name }

        assertEquals(listOf("Position -1", "Position 5 as well", "Position 5", "No position, a", "No position, b"), names)
    }

    @Test
    fun aLocationThatSomeoneWroteByHandIsLeftAsItIsBySavingWhatWasLoaded() =
        runTest {
            write(root, "mockey.json", """{"formatVersion": 1}""")
            write(root, "services/one/service.json", """{"id": "b", "name": "No position", "mockUrl": "b"}""")
            write(root, "services/one/scenarios/ok.json", """{"id": "s", "name": "ok", "responseBody": "fine"}""")
            write(root, "services/two/service.json", """{"id": "a", "name": "Position 5", "mockUrl": "a", "position": 5}""")
            val before = treeOf(root)
            val storage = watchedStorage(root, steps)

            storage.save(storage.load())

            assertEquals(emptyList(), steps)
            assertEquals(before, treeOf(root))
        }

    @Test
    fun aLocationWithoutAMainFileGetsOneWithTheFormatVersionWhenItIsSaved() =
        runTest {
            write(root, "services/one/service.json", """{"id": "b", "name": "No position", "mockUrl": "b"}""")
            val before = treeOf(root)
            val storage = watchedStorage(root, steps)

            storage.save(storage.load())

            assertEquals(listOf("STAGE mockey.json", "MOVE mockey.json"), steps)
            assertEquals("{\n  \"formatVersion\": 1\n}\n", textOf(root, "mockey.json"))
            assertEquals(before, treeOf(root) - "mockey.json")
        }
}
