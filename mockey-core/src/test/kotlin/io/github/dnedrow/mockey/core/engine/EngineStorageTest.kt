package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.definitions.OutboundProxyChanges
import io.github.dnedrow.mockey.core.definitions.ServiceChanges
import io.github.dnedrow.mockey.core.history.Outcome
import io.github.dnedrow.mockey.core.history.requestFor
import io.github.dnedrow.mockey.core.history.scenarioOf
import io.github.dnedrow.mockey.core.history.serviceOf
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.ProxyCredentials
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.response.MockResponse
import io.github.dnedrow.mockey.core.storage.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Timeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * When an engine saves its definitions to its storage: never in read-only mode, and in writable mode before a
 * change takes effect. An engine that is broken could make a test wait forever, so each test has a time limit.
 */
@Timeout(60)
class EngineStorageTest {
    private val store = RecordingStorage()
    private val unwritable = StorageException("The definitions location /definitions cannot be written to.")

    private fun addService(
        name: String,
        mockUrl: String = name.lowercase(),
    ): Change<Service> = ServiceChanges.create { it.copy(name = name, mockUrl = mockUrl) }

    /**
     * The failure and what caused it, down to the first cause. A failure that crosses from one coroutine to
     * another can arrive as a copy of itself that names the original as its cause.
     */
    private fun Throwable.withCauses(): List<Throwable> = generateSequence(this) { it.cause }.toList()

    private fun readOnlyEngine(definitions: Definitions = Definitions.EMPTY) = engineOf(definitions) { storage = store }

    private fun writableEngine(definitions: Definitions = Definitions.EMPTY) =
        engineOf(definitions) {
            storage = store
            readOnly = false
        }

    // Read-only mode

    @Test
    fun anEngineIsInReadOnlyModeUnlessItIsToldOtherwise() {
        assertTrue(engineOf().readOnly)
        assertTrue(readOnlyEngine().readOnly)
        assertFalse(writableEngine().readOnly)
    }

    @Test
    fun inReadOnlyModeAChangeStaysInMemory() =
        runTest {
            val engine = readOnlyEngine()

            val created = assertIs<ChangeResult.Changed<Service>>(engine.change(addService("Orders")))
            engine.change(ServiceChanges.update(created.value.id) { it.copy(description = "Orders of customers") })
            val handled = engine.handle(requestFor("orders"))

            assertEquals(listOf("Orders of customers"), engine.definitions.services.map { it.description })
            assertEquals("Orders", handled.entry.service?.name)
            assertEquals(emptyList(), store.saves)
            assertEquals(Definitions.EMPTY, store.stored)
        }

    // Writable mode

    @Test
    fun inWritableModeAChangeIsSavedBeforeItTakesEffect() =
        runTest {
            val engine = writableEngine()
            val definitionsDuringSave = mutableListOf<Definitions>()
            store.beforeSave = { definitionsDuringSave += engine.definitions }

            val result = assertIs<ChangeResult.Changed<Service>>(engine.change(addService("Orders")))

            assertEquals(listOf(Definitions.EMPTY), definitionsDuringSave)
            assertEquals(listOf(result.definitions), store.saves)
            assertSame(result.definitions, engine.definitions)
        }

    @Test
    fun everyChangeIsSavedWithTheCompleteDefinitions() =
        runTest {
            val engine = writableEngine()

            engine.change(addService("Orders"))
            engine.change(addService("Customers"))

            assertEquals(
                listOf(listOf("Orders"), listOf("Orders", "Customers")),
                store.saves.map { saved -> saved.services.map { it.name } },
            )
            assertEquals(engine.definitions, store.stored)
        }

    @Test
    fun whatChangesNothingIsNotSaved() =
        runTest {
            val orders = serviceOf("Orders", "orders")
            val engine = writableEngine(Definitions(services = listOf(orders)))

            val rejected = engine.change(addService("Other orders", mockUrl = "orders"))
            val notFound = engine.change(ServiceChanges.delete(serviceOf("Gone", "gone").id))
            val unchanged = engine.change(ServiceChanges.update(orders.id) { it })

            assertIs<ChangeResult.Rejected>(rejected)
            assertIs<ChangeResult.NotFound>(notFound)
            assertIs<ChangeResult.Changed<Service>>(unchanged)
            assertEquals(emptyList(), store.saves)
        }

    @Test
    fun aChangeThatCannotBeSavedFailsAndLeavesTheDefinitionsAsTheyWere() =
        runTest {
            val engine = writableEngine()
            engine.change(addService("Orders"))
            val before = engine.definitions
            store.failure = unwritable

            val failure = assertFailsWith<StorageException> { engine.change(addService("Customers")) }

            assertEquals("The definitions location /definitions cannot be written to.", failure.message)
            assertTrue(unwritable in failure.withCauses())
            assertSame(before, engine.definitions)
            assertEquals(before, store.stored)
        }

    @Test
    fun aStorageThatWorksAgainSavesTheNextChange() =
        runTest {
            val engine = writableEngine()
            store.failure = unwritable
            assertFailsWith<StorageException> { engine.change(addService("Orders")) }
            store.failure = null

            engine.change(addService("Customers"))

            assertEquals(listOf("Customers"), engine.definitions.services.map { it.name })
            assertEquals(engine.definitions, store.stored)
        }

    @Test
    fun anyFailureOfAStorageToWriteIsReportedAsAStorageFailure() =
        runTest {
            val engine = writableEngine()
            val diskFull = IOException("No space left on device")
            store.failure = diskFull

            val failure = assertFailsWith<StorageException> { engine.change(addService("Orders")) }

            assertEquals("The definitions could not be saved: No space left on device", failure.message)
            assertTrue(diskFull in failure.withCauses())
            assertEquals(Definitions.EMPTY, engine.definitions)
        }

    // Switching the mode

    @Test
    fun switchingReadOnlyModeOffSavesTheDefinitionsAsTheyAre() =
        runTest {
            val engine = readOnlyEngine()
            engine.change(addService("Orders"))
            engine.change(addService("Customers"))
            val readOnlyDuringSave = mutableListOf<Boolean>()
            store.beforeSave = { readOnlyDuringSave += engine.readOnly }

            engine.setReadOnly(false)

            assertFalse(engine.readOnly)
            assertEquals(listOf(engine.definitions), store.saves)
            assertEquals(listOf(true), readOnlyDuringSave)
        }

    @Test
    fun afterReadOnlyModeWasSwitchedOffEveryChangeIsSaved() =
        runTest {
            val engine = readOnlyEngine()
            engine.setReadOnly(false)

            engine.change(addService("Orders"))

            assertEquals(listOf(Definitions.EMPTY, engine.definitions), store.saves)
        }

    @Test
    fun readOnlyModeStaysOnWhenTheDefinitionsCannotBeSaved() =
        runTest {
            val engine = readOnlyEngine()
            engine.change(addService("Orders"))
            store.failure = unwritable

            val failure = assertFailsWith<StorageException> { engine.setReadOnly(false) }
            engine.change(addService("Customers"))

            assertTrue(unwritable in failure.withCauses())
            assertTrue(engine.readOnly)
            assertEquals(listOf("Orders", "Customers"), engine.definitions.services.map { it.name })
            assertEquals(Definitions.EMPTY, store.stored)
        }

    @Test
    fun switchingReadOnlyModeOnKeepsLaterChangesInMemory() =
        runTest {
            val engine = writableEngine()
            engine.change(addService("Orders"))
            val saved = engine.definitions

            engine.setReadOnly(true)
            engine.change(addService("Customers"))

            assertTrue(engine.readOnly)
            assertEquals(listOf(saved), store.saves)
            assertEquals(listOf("Orders", "Customers"), engine.definitions.services.map { it.name })
        }

    @Test
    fun switchingToTheModeThatIsOnDoesNothing() =
        runTest {
            val writable = writableEngine()
            val readOnly = engineOf()

            writable.setReadOnly(false)
            readOnly.setReadOnly(true)

            assertEquals(listOf(false, true), listOf(writable.readOnly, readOnly.readOnly))
            assertEquals(emptyList(), store.saves)
        }

    @Test
    fun changesAndASwitchOfTheModeAtTheSameTimeLeaveTheStorageWithTheDefinitionsOfTheEngine() {
        val engine = readOnlyEngine()

        runBlocking(Dispatchers.Default) {
            val changes = List(8) { worker -> launch { repeat(25) { engine.change(addService("Service $worker-$it")) } } }
            val switch = launch { engine.setReadOnly(false) }
            joinAll(switch, *changes.toTypedArray())
        }

        assertEquals(200, engine.definitions.services.size)
        assertEquals(engine.definitions, store.stored)
    }

    // What the storage is not given

    @Test
    fun theStorageGetsNoCredentialsOfTheOutboundProxy() =
        runTest {
            val writable = writableEngine()
            val withCredentials = OutboundProxy("http://proxy.example.com:3128", enabled = true, ProxyCredentials("mockey", "s3cret"))
            val other = RecordingStorage()
            val readOnly = engineOf { storage = other }

            writable.change(OutboundProxyChanges.update { withCredentials })
            readOnly.change(OutboundProxyChanges.update { withCredentials })
            readOnly.setReadOnly(false)

            val stored = OutboundProxy("http://proxy.example.com:3128", enabled = true)
            assertEquals(listOf(stored, stored), listOf(store, other).map { it.stored.settings.outboundProxy })
            assertEquals(listOf(withCredentials, withCredentials), listOf(writable, readOnly).map { it.definitions.settings.outboundProxy })
            assertFalse("s3cret" in (store.saves + other.saves).toString())
        }

    // Unknown targets

    @Test
    fun aServiceThatIsRegisteredForAnUnknownTargetIsSaved() =
        runTest {
            val upstream = FakeUpstream { MockResponse(200) }
            val engine =
                engineOf {
                    storage = store
                    readOnly = false
                    upstreamClient = upstream
                }

            val handled = engine.handle(requestFor("http://api.example.com/v1/customers"))

            assertEquals(listOf("customers (auto generated)"), store.stored.services.map { it.name })
            assertEquals(engine.definitions, store.stored)
            assertEquals("customers (auto generated)", handled.entry.service?.name)
        }

    @Test
    fun aRequestForAnUnknownTargetIsForwardedAlsoWhenItsServiceCannotBeSaved() =
        runTest {
            val upstream = FakeUpstream { MockResponse(200, body = "real".toByteArray()) }
            val engine =
                engineOf {
                    storage = store
                    readOnly = false
                    upstreamClient = upstream
                }
            store.failure = unwritable

            val handled = engine.handle(requestFor("http://api.example.com/v1/customers", query = "active=true"))

            assertEquals(200 to "real", handled.response.status to handled.response.bodyText)
            assertEquals(listOf("http://api.example.com/v1/customers?active=true"), upstream.urls)
            assertEquals(Definitions.EMPTY, engine.definitions)
            assertNull(handled.entry.service)
            assertEquals(ResponseMode.PROXY to Outcome.PROXIED, handled.entry.responseMode to handled.entry.outcome)
        }

    @Test
    fun aRequestToAServiceNeedsNoStorage() =
        runTest {
            val orders = serviceOf("Orders", "orders", scenarioOf("ok")).copy(responseMode = ResponseMode.STATIC)
            val engine = writableEngine(Definitions(services = listOf(orders)))
            store.failure = unwritable

            val handled = engine.handle(requestFor("orders"))

            assertEquals(200, handled.response.status)
            assertEquals(emptyList(), store.saves)
        }
}
