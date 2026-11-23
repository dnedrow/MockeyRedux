package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.PlanId
import io.github.dnedrow.mockey.core.model.PlanItem
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A state that is broken could make a test wait forever, so each test has a time limit. */
@Timeout(30)
class EngineStateTest {
    /** Adds a service unless its mock URL is in use: a rule that changes made at the same time must not get around. */
    private fun addService(mockUrl: String): (Definitions) -> ChangeResult<Service> =
        { definitions ->
            val owner = definitions.services.firstOrNull { it.mockUrl == mockUrl }
            if (owner == null) {
                val service = Service(id = ServiceId.generate(), name = "Service for $mockUrl", mockUrl = mockUrl)
                ChangeResult.Changed(definitions.copy(services = definitions.services + service), service)
            } else {
                val error = FieldError("mockUrl", "The service '${owner.name}' already uses the mock URL $mockUrl.")
                ChangeResult.Rejected(ValidationFailure(listOf(error)))
            }
        }

    /** Renames every service and every plan item at once: a change that touches two places. */
    private fun renameEverything(name: String): (Definitions) -> ChangeResult<Unit> =
        { definitions ->
            val services = definitions.services.map { it.copy(name = name) }
            val plans = definitions.plans.map { plan -> plan.copy(items = plan.items.map { it.copy(serviceName = name) }) }
            ChangeResult.Changed(definitions.copy(services = services, plans = plans), Unit)
        }

    @Test
    fun aNewStateHoldsTheDefinitionsItWasGiven() {
        val definitions = Definitions(plans = listOf(Plan(id = PlanId.generate(), name = "Gold path")))

        assertSame(definitions, EngineState(definitions).current)
        assertEquals(Definitions.EMPTY, EngineState().current)
    }

    @Test
    fun aChangePutsNewDefinitionsInPlace(): Unit =
        runBlocking {
            val state = EngineState()

            val result = state.change(addService("customer/{id}"))

            val changed = assertIs<ChangeResult.Changed<Service>>(result)
            assertEquals(listOf(changed.value), state.current.services)
            assertSame(changed.definitions, state.current)
        }

    @Test
    fun aRejectedChangeLeavesTheDefinitionsAsTheyWere(): Unit =
        runBlocking {
            val told = AtomicInteger()
            val state = EngineState(observers = listOf(ChangeObserver { _, _ -> told.incrementAndGet() }))
            state.change(addService("customer/{id}"))
            val before = state.current

            val result = state.change(addService("customer/{id}"))

            val rejected = assertIs<ChangeResult.Rejected>(result)
            assertEquals(
                "mockUrl",
                rejected.failure.errors
                    .single()
                    .field,
            )
            assertSame(before, state.current)
            assertEquals(1, told.get())
        }

    @Test
    fun aValidationFailureNamesAtLeastOneField() {
        assertFailsWith<IllegalArgumentException> { ValidationFailure(emptyList()) }
    }

    @Test
    fun observersAreToldOfTheDefinitionsBeforeAndAfterInTheirOrder(): Unit =
        runBlocking {
            val told = mutableListOf<Triple<String, Definitions, Definitions>>()
            val state =
                EngineState(
                    observers =
                        listOf(
                            ChangeObserver { before, after -> told += Triple("first", before, after) },
                            ChangeObserver { before, after -> told += Triple("second", before, after) },
                        ),
                )
            val before = state.current

            val changed = assertIs<ChangeResult.Changed<Service>>(state.change(addService("customer/{id}")))

            assertEquals(2, told.size)
            assertEquals(listOf("first", "second"), told.map { it.first })
            told.forEach { (_, toldBefore, toldAfter) ->
                assertSame(before, toldBefore)
                assertSame(changed.definitions, toldAfter)
            }
        }

    @Test
    fun aChangeTakesEffectOnlyAfterItsObserversWereTold(): Unit =
        runBlocking {
            lateinit var state: EngineState
            val visibleToObserver = mutableListOf<Definitions>()
            state = EngineState(observers = listOf(ChangeObserver { _, _ -> visibleToObserver += state.current }))
            val before = state.current

            state.change(addService("customer/{id}"))

            assertSame(before, visibleToObserver.single())
            assertEquals(1, state.current.services.size)
        }

    @Test
    fun aChangeThatChangesNothingIsNotToldToObservers(): Unit =
        runBlocking {
            val told = AtomicInteger()
            val state = EngineState(observers = listOf(ChangeObserver { _, _ -> told.incrementAndGet() }))
            state.change(addService("customer/{id}"))
            val before = state.current

            state.change { ChangeResult.Changed(it, Unit) }
            state.change { ChangeResult.Changed(it.copy(services = it.services.toList()), Unit) }

            assertSame(before, state.current)
            assertEquals(1, told.get())
        }

    @Test
    fun aFailingObserverKeepsTheChangeFromTakingEffect(): Unit =
        runBlocking {
            val fails = AtomicBoolean(true)
            val toldAfterIt = AtomicInteger()
            val state =
                EngineState(
                    observers =
                        listOf(
                            ChangeObserver { _, _ -> if (fails.get()) throw IOException("The disk is full.") },
                            ChangeObserver { _, _ -> toldAfterIt.incrementAndGet() },
                        ),
                )

            val failure = assertFailsWith<IOException> { state.change(addService("customer/{id}")) }

            assertEquals("The disk is full.", failure.message)
            assertEquals(Definitions.EMPTY, state.current)
            assertEquals(0, toldAfterIt.get())

            fails.set(false)
            assertIs<ChangeResult.Changed<Service>>(state.change(addService("customer/{id}")))
            assertEquals(1, state.current.services.size)
            assertEquals(1, toldAfterIt.get())
        }

    @Test
    fun aChangeThatFailsLeavesTheDefinitionsAsTheyWere(): Unit =
        runBlocking {
            val state = EngineState()

            assertFailsWith<IllegalStateException> { state.change<Unit> { error("This change cannot be made.") } }

            assertEquals(Definitions.EMPTY, state.current)
            assertIs<ChangeResult.Changed<Service>>(state.change(addService("customer/{id}")))
        }

    @Test
    fun changesMadeAtTheSameTimeNeverBreakARule(): Unit =
        runBlocking(Dispatchers.Default) {
            val state = EngineState(observers = listOf(ChangeObserver { _, _ -> yield() }))
            val mockUrls = List(20) { "service/$it" }
            val attempts = 2_000

            val results = List(attempts) { attempt -> async { state.change(addService(mockUrls[attempt % mockUrls.size])) } }.awaitAll()

            assertEquals(mockUrls.size, results.count { it is ChangeResult.Changed })
            assertEquals(attempts - mockUrls.size, results.count { it is ChangeResult.Rejected })
            assertEquals(
                mockUrls.sorted(),
                state.current.services
                    .map { it.mockUrl }
                    .sorted(),
            )
        }

    @Test
    fun noChangeIsLostWhenChangesAreMadeAtTheSameTime(): Unit =
        runBlocking(Dispatchers.Default) {
            val state = EngineState(observers = listOf(ChangeObserver { _, _ -> yield() }))
            val mockUrls = List(500) { "service/$it" }

            val results = mockUrls.map { mockUrl -> async { state.change(addService(mockUrl)) } }.awaitAll()

            assertTrue(results.all { it is ChangeResult.Changed })
            assertEquals(
                mockUrls.sorted(),
                state.current.services
                    .map { it.mockUrl }
                    .sorted(),
            )
        }

    @Test
    fun changesAreAppliedOneAtATime(): Unit =
        runBlocking(Dispatchers.Default) {
            val inProgress = AtomicInteger()
            val mostInProgress = AtomicInteger()
            val told = CopyOnWriteArrayList<Pair<Definitions, Definitions>>()
            val state =
                EngineState(
                    observers =
                        listOf(
                            ChangeObserver { before, after ->
                                mostInProgress.accumulateAndGet(inProgress.incrementAndGet(), ::maxOf)
                                told += before to after
                                yield()
                                inProgress.decrementAndGet()
                            },
                        ),
                )
            val start = state.current

            List(200) { index -> launch { state.change(addService("service/$index")) } }.forEach { it.join() }

            assertEquals(1, mostInProgress.get())
            assertEquals(200, told.size)
            assertSame(start, told.first().first)
            told.zipWithNext().forEach { (earlier, later) -> assertSame(earlier.second, later.first) }
            assertSame(told.last().second, state.current)
        }

    @Test
    fun readersNeverSeeAChangeThatIsHalfDone() {
        val service = Service(id = ServiceId.generate(), name = "name-start", mockUrl = "customer/{id}")
        val plan = Plan(id = PlanId.generate(), name = "Gold path", items = listOf(PlanItem(serviceName = service.name)))
        val state = EngineState(Definitions(services = listOf(service), plans = listOf(plan)))
        val readers = 4
        val reading = CountDownLatch(readers)
        val stop = AtomicBoolean(false)
        val reads = AtomicInteger()
        val halfDone = AtomicInteger()
        val threads =
            List(readers) {
                thread(isDaemon = true) {
                    reading.countDown()
                    while (!stop.get()) {
                        val definitions = state.current
                        if (definitions.services.single().name !=
                            definitions.plans
                                .single()
                                .items
                                .single()
                                .serviceName
                        ) {
                            halfDone.incrementAndGet()
                        }
                        reads.incrementAndGet()
                    }
                }
            }

        try {
            reading.await()
            runBlocking(Dispatchers.Default) {
                repeat(20_000) { round -> state.change(renameEverything("name-$round")) }
            }
        } finally {
            stop.set(true)
        }
        threads.forEach { it.join() }

        assertEquals(0, halfDone.get())
        assertTrue(reads.get() > 0)
        assertEquals(
            "name-19999",
            state.current.services
                .single()
                .name,
        )
    }

    @Test
    fun aChangeIsCompletedEvenWhenItsCallerIsCanceledMeanwhile(): Unit =
        runBlocking {
            val observerStarted = CompletableDeferred<Unit>()
            val observerMayFinish = CompletableDeferred<Unit>()
            val observerFinished = AtomicBoolean(false)
            val state =
                EngineState(
                    observers =
                        listOf(
                            ChangeObserver { _, _ ->
                                observerStarted.complete(Unit)
                                observerMayFinish.await()
                                observerFinished.set(true)
                            },
                        ),
                )

            val caller = launch(Dispatchers.Default) { state.change(addService("customer/{id}")) }
            try {
                observerStarted.await()
                caller.cancel()
            } finally {
                observerMayFinish.complete(Unit)
            }
            caller.join()

            assertTrue(caller.isCancelled)
            assertTrue(observerFinished.get())
            assertEquals(1, state.current.services.size)
        }

    @Test
    fun aCallerThatIsCanceledWhileItWaitsChangesNothing(): Unit =
        runBlocking {
            val firstStarted = CompletableDeferred<Unit>()
            val firstMayFinish = CompletableDeferred<Unit>()
            val state =
                EngineState(
                    observers =
                        listOf(
                            ChangeObserver { _, _ ->
                                firstStarted.complete(Unit)
                                firstMayFinish.await()
                            },
                        ),
                )
            val first = launch(Dispatchers.Default) { state.change(addService("first")) }
            val waiting =
                try {
                    firstStarted.await()
                    launch(start = CoroutineStart.UNDISPATCHED) { state.change(addService("second")) }.also { it.cancel() }
                } finally {
                    firstMayFinish.complete(Unit)
                }
            joinAll(first, waiting)

            assertTrue(waiting.isCancelled)
            assertEquals(listOf("first"), state.current.services.map { it.mockUrl })
        }
}
