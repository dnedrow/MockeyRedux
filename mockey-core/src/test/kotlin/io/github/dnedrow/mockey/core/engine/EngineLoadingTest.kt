package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.definitions.OutboundProxyChanges
import io.github.dnedrow.mockey.core.definitions.ServiceChanges
import io.github.dnedrow.mockey.core.history.NOON
import io.github.dnedrow.mockey.core.history.requestFor
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.ProxyCredentials
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.storage.FileDefinitionStorage
import io.github.dnedrow.mockey.core.storage.InMemoryDefinitionStorage
import io.github.dnedrow.mockey.core.storage.Sample
import io.github.dnedrow.mockey.core.storage.StorageException
import io.github.dnedrow.mockey.core.storage.loaded
import io.github.dnedrow.mockey.core.storage.stored
import io.github.dnedrow.mockey.core.storage.textOf
import io.github.dnedrow.mockey.core.storage.treeOf
import io.github.dnedrow.mockey.core.storage.write
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** How an engine comes by its definitions: when it is created, when it reloads them, and when it is reset. */
class EngineLoadingTest {
    @TempDir
    lateinit var directory: Path

    private val root: Path
        get() = directory.resolve("definitions")

    /** The sample definitions after their default plan was applied: `Customers` is in static mode and has the delay of the plan. */
    private val sampleWithPlanApplied =
        Sample.definitions.copy(
            services = listOf(Sample.customers.copy(responseMode = ResponseMode.STATIC, delayMillis = 250), Sample.orders),
        )

    private val goldFile = "services/customers/scenarios/gold-customer.json"

    /** Leaves the scenario file of `gold customer` cut short, as something other than the storage can leave a file. */
    private fun corruptGoldFile() = write(root, goldFile, """{"id": "scn-gold", "name": "gold""")

    private val goldFileProblem: String
        get() = "The definition file ${root.resolve(goldFile)} cannot be read: it is not valid JSON:"

    // Creating an engine

    @Test
    fun anEngineStartsWithTheDefinitionsThatItsStorageHolds(): Unit =
        runBlocking {
            stored(root, Sample.definitions.copy(settings = Sample.settings.copy(defaultPlanId = null)))

            val onFiles = createdEngine { storage = FileDefinitionStorage(root) }
            val inMemory = createdEngine { storage = InMemoryDefinitionStorage(onFiles.definitions) }

            assertEquals(Sample.definitions.copy(settings = Sample.settings.copy(defaultPlanId = null)), onFiles.definitions)
            assertEquals(onFiles.definitions, inMemory.definitions)
        }

    @Test
    fun anEngineWithoutAStorageOfItsOwnStartsWithoutDefinitions(): Unit =
        runBlocking {
            assertSame(Definitions.EMPTY, createdEngine().definitions)
        }

    @Test
    fun anEngineOnALocationThatDoesNotExistStartsWithoutDefinitionsAndCreatesNothing(): Unit =
        runBlocking {
            val inReadOnlyMode = createdEngine { storage = FileDefinitionStorage(root) }
            val inWritableMode =
                createdEngine {
                    storage = FileDefinitionStorage(root)
                    readOnly = false
                }

            assertEquals(listOf(Definitions.EMPTY, Definitions.EMPTY), listOf(inReadOnlyMode.definitions, inWritableMode.definitions))
            assertFalse(root.exists())
        }

    @Test
    fun noEngineIsCreatedOnALocationWithAFileThatCannotBeRead(): Unit =
        runBlocking {
            stored(root, Sample.definitions)
            corruptGoldFile()

            val failure = assertFailsWith<StorageException> { createdEngine { storage = FileDefinitionStorage(root) } }

            assertTrue(failure.message.orEmpty().startsWith(goldFileProblem), failure.message)
        }

    @Test
    fun noEngineIsCreatedOnDefinitionsOfANewerFormat(): Unit =
        runBlocking {
            stored(root, Sample.definitions)
            write(root, "mockey.json", """{"formatVersion": 2, "plans": []}""")

            val failure = assertFailsWith<StorageException> { createdEngine { storage = FileDefinitionStorage(root) } }

            val expected =
                "The definition file ${root.resolve("mockey.json")} cannot be read: " +
                    "it has the format version 2, and this version of Mockey reads the format version 1 only."
            assertEquals(expected, failure.message)
        }

    @Test
    fun anyFailureOfAStorageToLoadIsReportedAsAStorageFailure(): Unit =
        runBlocking {
            val broken = RecordingStorage().apply { failure = IOException("Connection reset") }

            val failure = assertFailsWith<StorageException> { createdEngine { storage = broken } }

            assertEquals("The definitions could not be loaded: Connection reset", failure.message)
        }

    // The default plan

    @Test
    fun theDefaultPlanIsAppliedAfterTheDefinitionsWereLoaded(): Unit =
        runBlocking {
            stored(root, Sample.definitions)
            val before = treeOf(root)

            val engine = createdEngine { storage = FileDefinitionStorage(root) }

            assertEquals(sampleWithPlanApplied, engine.definitions)
            assertEquals(NOON, engine.lastUsedTimes.ofPlan(Sample.plan.id))
            assertEquals(before, treeOf(root))
        }

    @Test
    fun inWritableModeWhatTheDefaultPlanChangesIsSaved(): Unit =
        runBlocking {
            stored(root, Sample.definitions)
            val store = RecordingStorage(Sample.definitions)

            val onFiles =
                createdEngine {
                    storage = FileDefinitionStorage(root)
                    readOnly = false
                }
            val recorded =
                createdEngine {
                    storage = store
                    readOnly = false
                }
            val again =
                createdEngine {
                    storage = store
                    readOnly = false
                }

            assertEquals(sampleWithPlanApplied, loaded(root))
            assertEquals(onFiles.definitions, loaded(root))
            assertEquals(listOf(sampleWithPlanApplied), store.saves)
            assertEquals(recorded.definitions, again.definitions)
        }

    @Test
    fun definitionsWithoutADefaultPlanAreAsTheyWereLoaded(): Unit =
        runBlocking {
            val withoutDefault = Sample.definitions.copy(settings = Sample.settings.copy(defaultPlanId = null))
            val store = RecordingStorage(withoutDefault)

            val engine =
                createdEngine {
                    storage = store
                    readOnly = false
                }

            assertSame(withoutDefault, engine.definitions)
            assertEquals(emptyList(), store.saves)
            assertNull(engine.lastUsedTimes.ofPlan(Sample.plan.id))
        }

    // Reloading

    @Test
    fun aReloadPutsTheDefinitionsOfTheStorageInThePlaceOfThoseInMemory(): Unit =
        runBlocking {
            stored(root, Sample.definitions)
            val time = TestClock()
            val engine =
                createdEngine {
                    storage = FileDefinitionStorage(root)
                    clock = time
                    tagFilter = Tags.of("crm")
                }
            val handled = engine.handle(requestFor("customer/7"))
            val newer =
                Definitions(
                    services = listOf(Sample.orders.copy(name = "Purchases")),
                    plans = listOf(Sample.plan.copy(name = "Silver path")),
                )
            stored(root, newer)
            time.now = NOON + Duration.ofHours(1)

            engine.reload()

            assertEquals(newer, engine.definitions)
            assertEquals(listOf(handled.entry), engine.history.entries())
            assertEquals(Tags.of("crm"), engine.tagFilter)
            assertEquals(NOON, engine.lastUsedTimes.ofPlan(Sample.plan.id))
        }

    @Test
    fun aReloadDiscardsWhatWasChangedInMemoryOnly(): Unit =
        runBlocking {
            stored(root, Sample.definitions)
            val engine = createdEngine { storage = FileDefinitionStorage(root) }
            val proxy =
                OutboundProxy("http://proxy.example.com:3128", enabled = true, credentials = ProxyCredentials("proxy-user", "s3cret"))
            engine.change(ServiceChanges.delete(Sample.orders.id))
            engine.change(OutboundProxyChanges.update { proxy })

            engine.reload()

            assertEquals(sampleWithPlanApplied, engine.definitions)
            assertEquals(OutboundProxy("http://proxy.example.com:3128", enabled = true), engine.definitions.settings.outboundProxy)
        }

    @Test
    fun aReloadAppliesTheDefaultPlanOfWhatItLoads(): Unit =
        runBlocking {
            val time = TestClock()
            val engine =
                createdEngine {
                    storage = FileDefinitionStorage(root)
                    clock = time
                }
            stored(root, Sample.definitions)
            time.now = NOON + Duration.ofHours(1)

            engine.reload()

            assertEquals(sampleWithPlanApplied, engine.definitions)
            assertEquals(NOON + Duration.ofHours(1), engine.lastUsedTimes.ofPlan(Sample.plan.id))
        }

    @Test
    fun aReloadThatFailsLeavesTheDefinitionsInMemoryAsTheyWere(): Unit =
        runBlocking {
            stored(root, Sample.definitions)
            val engine = createdEngine { storage = FileDefinitionStorage(root) }
            engine.change(ServiceChanges.delete(Sample.orders.id))
            val before = engine.definitions
            corruptGoldFile()

            val failure = assertFailsWith<StorageException> { engine.reload() }

            assertTrue(failure.message.orEmpty().startsWith(goldFileProblem), failure.message)
            assertSame(before, engine.definitions)
        }

    @Test
    fun aReloadWorksAgainOnceTheLocationWasPutRight(): Unit =
        runBlocking {
            stored(root, Sample.definitions)
            val engine =
                createdEngine {
                    storage = FileDefinitionStorage(root)
                    readOnly = false
                }
            corruptGoldFile()
            assertFailsWith<StorageException> { engine.reload() }
            val refused = assertFailsWith<StorageException> { engine.change(ServiceChanges.delete(Sample.orders.id)) }
            stored(directory.resolve("copy"), Sample.definitions)
            write(root, goldFile, textOf(directory.resolve("copy"), goldFile))

            engine.reload()
            engine.change(ServiceChanges.delete(Sample.orders.id))

            assertTrue(refused.message.orEmpty().startsWith(goldFileProblem), refused.message)
            assertEquals(listOf("Customers"), engine.definitions.services.map { it.name })
            assertEquals(engine.definitions, loaded(root))
        }

    // Resetting

    @Test
    fun aResetLeavesACleanSlate(): Unit =
        runBlocking {
            stored(root, Sample.definitions)
            val before = treeOf(root)
            val engine =
                createdEngine {
                    storage = FileDefinitionStorage(root)
                    tagFilter = Tags.of("crm")
                }
            engine.handle(requestFor("customer/7"))

            engine.reset()

            assertEquals(Definitions.EMPTY, engine.definitions)
            assertEquals(Tags.EMPTY, engine.tagFilter)
            assertEquals(emptyList(), engine.history.entries())
            assertEquals(
                listOf(emptyMap(), emptyMap(), emptyMap()),
                engine.lastUsedTimes.let { listOf(it.services, it.scenarios, it.plans) },
            )
            assertEquals(404, engine.handle(requestFor("customer/7")).response.status)
            assertEquals(before, treeOf(root))
        }

    @Test
    fun inWritableModeAResetEmptiesTheStorageAsWell(): Unit =
        runBlocking {
            stored(root, Sample.definitions)
            val engine =
                createdEngine {
                    storage = FileDefinitionStorage(root)
                    readOnly = false
                }

            engine.reset()

            assertEquals(Definitions.EMPTY, engine.definitions)
            assertEquals(emptyMap(), treeOf(root))
            assertEquals(Definitions.EMPTY, loaded(root))
        }

    @Test
    fun aResetThatCannotBeSavedFailsAndClearsNothing(): Unit =
        runBlocking {
            val store = RecordingStorage(Sample.definitions.copy(settings = Sample.settings.copy(defaultPlanId = null)))
            val engine =
                createdEngine {
                    storage = store
                    readOnly = false
                    tagFilter = Tags.of("crm")
                }
            val handled = engine.handle(requestFor("customer/7"))
            val before = engine.definitions
            store.failure = StorageException("The definitions location cannot be written to.")

            assertFailsWith<StorageException> { engine.reset() }

            assertSame(before, engine.definitions)
            assertEquals(Tags.of("crm"), engine.tagFilter)
            assertEquals(listOf(handled.entry), engine.history.entries())
        }

    @Test
    fun anEngineThatHoldsNothingIsResetAllTheSame(): Unit =
        runBlocking {
            val store = RecordingStorage()
            val engine =
                createdEngine {
                    storage = store
                    readOnly = false
                    tagFilter = Tags.of("crm")
                }
            engine.handle(requestFor("unknown"))

            engine.reset()

            assertEquals(Tags.EMPTY, engine.tagFilter)
            assertEquals(emptyList(), engine.history.entries())
            assertEquals(emptyList(), store.saves)
        }
}
