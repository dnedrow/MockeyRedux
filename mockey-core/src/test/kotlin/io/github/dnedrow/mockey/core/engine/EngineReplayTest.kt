package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.definitions.ServiceChanges
import io.github.dnedrow.mockey.core.history.HistoryEntryId
import io.github.dnedrow.mockey.core.history.Outcome
import io.github.dnedrow.mockey.core.history.Replay
import io.github.dnedrow.mockey.core.history.ReplayEdits
import io.github.dnedrow.mockey.core.history.headersOf
import io.github.dnedrow.mockey.core.history.requestFor
import io.github.dnedrow.mockey.core.history.scenarioOf
import io.github.dnedrow.mockey.core.history.serviceOf
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Settings
import io.github.dnedrow.mockey.core.model.TwistConfiguration
import io.github.dnedrow.mockey.core.model.TwistId
import io.github.dnedrow.mockey.core.model.TwistPair
import io.github.dnedrow.mockey.core.request.Parameter
import io.github.dnedrow.mockey.core.response.MockResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** How an engine replays a history entry: as a request like any other, under the definitions as they are now. */
class EngineReplayTest {
    private val ok = scenarioOf("ok")
    private val busy = scenarioOf("busy", status = 503)
    private val orders = serviceOf("Orders", "orders/{id}", ok, busy).copy(responseMode = ResponseMode.STATIC)

    @Test
    fun aReplayIsHandledUnderTheDefinitionsAsTheyAreNow() =
        runTest {
            val engine = engineOf(Definitions(services = listOf(orders)))
            val original = engine.handle(requestFor("orders/7"), "192.0.2.10")
            engine.change(ServiceChanges.update(orders.id) { it.copy(defaultScenarioId = busy.id) })

            val replayed = assertIs<Replay.Handled<HandledRequest>>(engine.replay(original.entry.id, clientAddress = "192.0.2.99"))

            val replay = replayed.result
            assertEquals(200 to "ok", original.response.status to original.entry.scenario?.name)
            assertEquals(503 to "busy", replay.response.status to replay.entry.scenario?.name)
            assertEquals("192.0.2.99", replay.entry.clientAddress)
            assertEquals(listOf(replay.entry, original.entry), engine.history.entries())
        }

    @Test
    fun aReplayGoesToTheRealServiceWithWhatItsEditsSay() =
        runTest {
            val upstream = FakeUpstream { MockResponse(200) }
            val proxied = serviceOf("Orders", "orders/{id}").copy(realUrls = listOf("http://api.example.com/orders/{id}"))
            val engine = engineOf(Definitions(services = listOf(proxied))) { upstreamClient = upstream }
            val sent = headersOf("Content-Type" to "text/plain", "X-Request-Id" to "7")
            val original = engine.handle(requestFor("orders/7", "POST", "expand=items", sent, "ordered".toByteArray()))
            val edits = ReplayEdits(headersOf("X-Request-Id" to "8"), listOf(Parameter("note", "two words")), "ordered again".toByteArray())

            val replayed = assertIs<Replay.Handled<HandledRequest>>(engine.replay(original.entry.id, edits))

            val forwarded = upstream.requests.last()
            assertEquals("http://api.example.com/orders/7?note=two%20words", forwarded.url)
            assertEquals(headersOf("X-Request-Id" to "8") to "ordered again", forwarded.headers to String(forwarded.body))
            assertEquals(Outcome.PROXIED, replayed.result.entry.outcome)
            assertEquals(2, upstream.requests.size)
        }

    @Test
    fun aReplayIsTwistedAsTheDefinitionsSayNow() =
        runTest {
            val upstream = FakeUpstream { MockResponse(200) }
            val toQa2 = TwistConfiguration(TwistId.generate(), "to-qa2", listOf(TwistPair("qa1", "qa2")))
            val toQa3 = TwistConfiguration(TwistId.generate(), "to-qa3", listOf(TwistPair("qa1", "qa3")))
            val definitions = Definitions(twists = listOf(toQa2, toQa3), settings = Settings(enabledTwistId = toQa2.id))
            val engine = engineOf(definitions) { upstreamClient = upstream }
            val original = engine.handle(requestFor("http://qa1.example.com/orders"))
            engine.change { ChangeResult.Changed(it.copy(settings = it.settings.copy(enabledTwistId = toQa3.id)), Unit) }

            val replayed = assertIs<Replay.Handled<HandledRequest>>(engine.replay(original.entry.id))

            val entry = replayed.result.entry
            assertEquals(listOf("http://qa2.example.com/orders", "http://qa3.example.com/orders"), upstream.urls)
            assertEquals("http://qa3.example.com/orders" to "http://qa1.example.com/orders", entry.target to entry.targetBeforeTwisting)
        }

    @Test
    fun anEntryWhoseRequestBodyWasCutIsReplayedOnlyWithABody() =
        runTest {
            val engine = engineOf(Definitions(services = listOf(orders))) { bodyCaptureLimit = 4 }
            val original = engine.handle(requestFor("orders/7", "POST", body = "ordered".toByteArray()))

            val refused = assertIs<Replay.Refused>(engine.replay(original.entry.id))
            val replayed =
                assertIs<Replay.Handled<HandledRequest>>(engine.replay(original.entry.id, ReplayEdits(body = "new".toByteArray())))

            assertContains(refused.explanation, "was cut when it was recorded")
            assertEquals("new", String(replayed.request.body))
            assertEquals(listOf(replayed.result.entry, original.entry), engine.history.entries())
        }

    @Test
    fun anEntryThatTheHistoryDoesNotKeepIsNotReplayed() =
        runTest {
            val engine = engineOf(Definitions(services = listOf(orders)))

            val replay = engine.replay(HistoryEntryId(7))

            assertEquals(Replay.NotFound("The history keeps no entry with the identifier 7."), replay)
            assertEquals(emptyList(), engine.history.entries())
        }
}
