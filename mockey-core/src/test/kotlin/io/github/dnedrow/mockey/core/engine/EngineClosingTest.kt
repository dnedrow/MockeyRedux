package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.TwistConfiguration
import io.github.dnedrow.mockey.core.model.TwistId
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.response.MockResponse
import io.github.dnedrow.mockey.core.storage.DefinitionStorage
import io.github.dnedrow.mockey.core.storage.FileDefinitionStorage
import io.github.dnedrow.mockey.core.storage.StorageException
import io.github.dnedrow.mockey.core.upstream.UpstreamClient
import io.github.dnedrow.mockey.core.upstream.UpstreamRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** How an engine is closed: a save that is under way is completed, the upstream client is closed, and the engine does no more work. */
class EngineClosingTest {
    /**
     * A storage whose first save waits until a test lets it go on. [started] is complete as soon as that save
     * has begun.
     */
    private class HeldStorage(
        private val storage: DefinitionStorage,
    ) : DefinitionStorage {
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()

        override suspend fun load(): Definitions = storage.load()

        override suspend fun save(definitions: Definitions) {
            started.complete(Unit)
            released.await()
            storage.save(definitions)
        }
    }

    /** An upstream client that counts how often it was closed. */
    private class CountingClient : UpstreamClient {
        val closings = AtomicInteger()

        override suspend fun send(request: UpstreamRequest): MockResponse = MockResponse(200)

        override fun close() {
            closings.incrementAndGet()
        }
    }

    /** Returns an engine in writable mode that keeps its definitions in [held]. Creating it saves nothing. */
    private suspend fun writableEngine(held: DefinitionStorage): MockeyEngine =
        MockeyEngine.create {
            storage = held
            readOnly = false
        }

    private suspend fun MockeyEngine.createCustomers(): ChangeResult<Service> =
        services.create { it.copy(name = "Customers", mockUrl = "customer/{id}") }

    /** Returns the names of the services that the definition files in [directory] hold. */
    private suspend fun storedIn(directory: Path): List<String> {
        val stored = FileDefinitionStorage(directory).load()
        return stored.services.map { it.name }
    }

    @Test
    fun closingCompletesASaveThatIsUnderWay(
        @TempDir directory: Path,
    ) = runTest {
        val held = HeldStorage(FileDefinitionStorage(directory))
        val engine = writableEngine(held)

        // The test waits in real time, so it leaves the virtual time of the test scheduler.
        withContext(Dispatchers.Default) {
            val change = async { engine.createCustomers() }
            held.started.await()
            val closing = async(Dispatchers.IO) { engine.close() }
            delay(100.milliseconds)
            val closedWhileSaving = closing.isCompleted
            val storedWhileSaving = storedIn(directory)
            held.released.complete(Unit)
            closing.await()
            val storedWhenClosed = storedIn(directory)

            assertFalse(closedWhileSaving)
            assertEquals(emptyList(), storedWhileSaving)
            assertEquals(listOf("Customers"), storedWhenClosed)
            assertIs<ChangeResult.Changed<Service>>(change.await())
        }
    }

    /**
     * A program can close its engine on the thread on which a change of it goes on after its save. Closing
     * waits for the save there, so the end of the save must not need that thread.
     */
    @Test
    fun closingWaitsForASaveOnTheThreadThatTheChangeReturnsTo(
        @TempDir directory: Path,
    ) = runTest {
        val held = HeldStorage(FileDefinitionStorage(directory))
        val engine = writableEngine(held)
        val thread = Executors.newSingleThreadExecutor { Thread(it, "program").apply { isDaemon = true } }
        val program = CoroutineScope(thread.asCoroutineDispatcher())

        try {
            val storedWhenClosed =
                program.async {
                    val change = async { engine.createCustomers() }
                    held.started.await()
                    launch(Dispatchers.Default) {
                        delay(100.milliseconds)
                        held.released.complete(Unit)
                    }
                    engine.close()
                    val stored = storedIn(directory)
                    change.await()
                    stored
                }

            // The wait is in real time, so a close that never returns fails the test after half a minute.
            val stored = withContext(Dispatchers.Default) { withTimeout(30.seconds) { storedWhenClosed.await() } }

            assertEquals(listOf("Customers"), stored)
        } finally {
            program.cancel()
            thread.shutdownNow()
        }
    }

    @Test
    fun aClosedEngineHandlesNoRequestAndChangesNothing() =
        runTest {
            val storage = RecordingStorage()
            val engine = createdEngine { this.storage = storage }
            engine.setReadOnly(false)
            engine.createCustomers()
            val before = engine.definitions
            val saved = storage.saves.toList()

            engine.close()

            val failures =
                listOf(
                    assertFailsWith<IllegalStateException> { engine.handle(MockRequest.forTarget("GET", "customer/1")) },
                    assertFailsWith<IllegalStateException> { engine.services.create { it.copy(name = "Orders", mockUrl = "orders") } },
                    assertFailsWith<IllegalStateException> { engine.importDocument(engine.exportDefinitions()) },
                    assertFailsWith<IllegalStateException> { engine.setReadOnly(true) },
                    assertFailsWith<IllegalStateException> { engine.reload() },
                    assertFailsWith<IllegalStateException> { engine.reset() },
                )
            assertEquals(List(failures.size) { "The engine is closed." }, failures.map { it.message })
            assertEquals(before, engine.definitions)
            assertEquals(saved, storage.saves)
            assertEquals(1, storage.loads.get())
            assertEquals(emptyList(), engine.history.entries())
        }

    @Test
    fun aChangeWhoseSaveHasNotBegunWhenTheEngineIsClosedIsNotMade() =
        runTest {
            val storage = RecordingStorage()
            val engine = createdEngine { this.storage = storage }
            engine.setReadOnly(false)
            // The engine is closed after the change has begun, right before its storage is to save the change.
            val change: Change<Unit> = {
                engine.close()
                ChangeResult.Changed(it.copy(twists = listOf(TwistConfiguration(TwistId.generate(), "to-qa"))), Unit)
            }

            val failure = assertFailsWith<IllegalStateException> { engine.change(change) }

            assertEquals("The engine is closed.", failure.message)
            assertEquals(Definitions.EMPTY, engine.definitions)
            assertEquals(listOf(Definitions.EMPTY), storage.saves)
        }

    @Test
    fun closingClosesTheUpstreamClientOnce() =
        runTest {
            val client = CountingClient()
            val engine = MockeyEngine.create { upstreamClient = client }
            val openBefore = client.closings.get()

            engine.close()
            engine.close()

            assertEquals(0 to 1, openBefore to client.closings.get())
        }

    @Test
    fun anEngineThatCannotBeCreatedLeavesTheClientOfTheProgramOpen() =
        runTest {
            val client = CountingClient()
            val storage = RecordingStorage()
            storage.failure = IOException("the disk is gone")

            val failure =
                assertFailsWith<StorageException> {
                    MockeyEngine.create {
                        this.storage = storage
                        upstreamClient = client
                    }
                }

            assertEquals("The definitions could not be loaded: the disk is gone", failure.message)
            assertEquals(0, client.closings.get())
        }
}
