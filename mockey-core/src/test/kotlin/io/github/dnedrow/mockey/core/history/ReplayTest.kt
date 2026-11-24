package io.github.dnedrow.mockey.core.history

import io.github.dnedrow.mockey.core.definitions.ServiceChanges
import io.github.dnedrow.mockey.core.definitions.changed
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.request.Parameter
import io.github.dnedrow.mockey.core.response.staticAnswer
import io.github.dnedrow.mockey.core.routing.Route
import io.github.dnedrow.mockey.core.routing.RoutingTable
import io.github.dnedrow.mockey.core.routing.Target
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class ReplayTest {
    private val ok = scenarioOf("ok")
    private val error = scenarioOf("error", status = 500)

    /** A service in static mode whose default scenario is `ok`. */
    private val orders = serviceOf("Orders", "orders/{id}", ok, error).copy(responseMode = ResponseMode.STATIC)
    private val history = HistoryStore()

    /** The definitions as they are now, which a test changes between a request and its replay. */
    private var definitions = Definitions(services = listOf(orders))

    /**
     * Handles [request] as the mock endpoint handles a request for a service in static mode: the request is
     * routed in the definitions as they are now, the default scenario of its service answers, and the exchange
     * is recorded.
     */
    private fun handle(request: MockRequest): HistoryEntry {
        val route = assertIs<Route.ToService>(RoutingTable(definitions).route(targetOf(request), Tags.EMPTY, true))
        return history.record(exchangeOf(Handling.of(route.service, staticAnswer(route.service)), request))
    }

    private fun replay(
        entry: HistoryEntry,
        edits: ReplayEdits = ReplayEdits(),
    ): Replay<HistoryEntry> = runBlocking { history.replay(entry.id, edits, ::handle) }

    private val sent = headersOf("Content-Type" to "text/plain", "X-Request-Id" to "7", "Content-Length" to "7")
    private val posted = requestFor("orders/7", "POST", "expand=items&note=two%20words", sent, "ordered".toByteArray())

    private fun MockRequest.parts() = listOf(method, url, query, headers, String(body))

    // Replaying an entry

    @Test
    fun aReplayIsHandledUnderTheDefinitionsAsTheyAreNow() {
        val original = handle(posted)
        definitions = definitions.changed(ServiceChanges.update(orders.id) { it.copy(defaultScenarioId = error.id) }).definitions

        val replayed = assertIs<Replay.Handled<HistoryEntry>>(replay(original))

        assertEquals("ok" to 200, original.scenario?.name to original.response.status)
        assertEquals("error" to 500, replayed.result.scenario?.name to replayed.result.response.status)
        assertEquals(listOf(replayed.result, original), history.entries())
        assertEquals(original, history.entry(original.id))
    }

    @Test
    fun aReplayIsARequestLikeTheOneOfItsEntry() {
        val original = handle(posted)

        val replayed = assertIs<Replay.Handled<HistoryEntry>>(replay(original))

        assertEquals(posted.parts(), replayed.request.parts())
        assertEquals(original.request, replayed.result.request)
        assertEquals(HistoryEntryId(2), replayed.result.id)
    }

    @Test
    fun aReplayWithAnotherBodyCarriesThatBodyAndTheRestOfTheRequestOfTheEntry() {
        val original = handle(posted)

        val replayed = assertIs<Replay.Handled<HistoryEntry>>(replay(original, ReplayEdits(body = "ordered again".toByteArray())))

        val request = replayed.request
        assertEquals("ordered again", String(request.body))
        assertEquals("POST" to posted.url, request.method to request.url)
        assertEquals("expand=items&note=two%20words", request.query)
        assertEquals(headersOf("Content-Type" to "text/plain", "X-Request-Id" to "7", "Content-Length" to "13"), request.headers)
        assertEquals(listOf(Parameter("expand", "items"), Parameter("note", "two words")), request.parameters)
        assertEquals("ordered again", replayed.result.request.bodyText)
    }

    @Test
    fun aReplayWithOtherHeadersCarriesThoseAndNoneOfTheEntry() {
        val original = handle(posted)
        val headers = headersOf("Accept" to "application/json", "X-Request-Id" to "8")

        val replayed = assertIs<Replay.Handled<HistoryEntry>>(replay(original, ReplayEdits(headers = headers)))

        assertEquals(headers, replayed.request.headers)
        assertEquals(listOf("POST", posted.url, posted.query, "ordered"), replayed.request.parts() - listOf(headers))
        assertEquals(
            emptyList(),
            assertIs<Replay.Handled<HistoryEntry>>(replay(original, ReplayEdits(headers = emptyList()))).request.headers,
        )
    }

    @Test
    fun aReplayWithOtherParametersGetsAQueryStringThatHoldsThem() {
        val original = handle(posted)
        val parameters =
            listOf(Parameter("name", "jane doe"), Parameter("q", "a&b=c"), Parameter("empty", ""), Parameter("caf\u00e9", "100%"))

        val replayed = assertIs<Replay.Handled<HistoryEntry>>(replay(original, ReplayEdits(parameters = parameters)))

        assertEquals("name=jane%20doe&q=a%26b%3Dc&empty=&caf%C3%A9=100%25", replayed.request.query)
        assertEquals(parameters, replayed.request.parameters)
        assertEquals(listOf("POST", posted.url, sent, "ordered"), replayed.request.parts() - listOf(replayed.request.query))
    }

    @Test
    fun aReplayWithoutParametersHasNoQueryString() {
        val original = handle(posted)

        val replayed = assertIs<Replay.Handled<HistoryEntry>>(replay(original, ReplayEdits(parameters = emptyList())))

        assertNull(replayed.request.query)
        assertEquals(emptyList(), replayed.result.request.parameters)
    }

    @Test
    fun aReplayCanReplaceEverythingAtOnce() {
        val original = handle(posted)
        val edits =
            ReplayEdits(
                headers = headersOf("content-length" to "0", "X-A" to "1"),
                parameters = listOf(Parameter("page", "2")),
                body = "x".toByteArray(),
            )

        val replayed = assertIs<Replay.Handled<HistoryEntry>>(replay(original, edits))

        assertEquals(listOf("POST", posted.url, "page=2", headersOf("content-length" to "1", "X-A" to "1"), "x"), replayed.request.parts())
    }

    @Test
    fun theFieldsOfAFormStayInTheBodyOfAReplay() {
        val form = headersOf("Content-Type" to "application/x-www-form-urlencoded")
        val original = handle(requestFor("orders/7", "POST", "page=1", form, "item=book".toByteArray()))

        val replayed = assertIs<Replay.Handled<HistoryEntry>>(replay(original, ReplayEdits(parameters = listOf(Parameter("page", "2")))))

        assertEquals(listOf(Parameter("page", "1"), Parameter("item", "book")), original.request.parameters)
        assertEquals(listOf(Parameter("page", "2"), Parameter("item", "book")), replayed.request.parameters)
        assertEquals("page=2" to "item=book", replayed.request.query to String(replayed.request.body))
    }

    @Test
    fun aReplayGoesToTheTargetAsTheClientWroteItBeforeTwisting() {
        val request = requestFor("orders/7")
        val twisted = history.record(exchangeOf(served(orders, ok), request, target = Target("orders/8")))

        val replayed = assertIs<Replay.Handled<HistoryEntry>>(replay(twisted))

        assertEquals("orders/8" to "orders/7", twisted.target to twisted.targetBeforeTwisting)
        assertEquals(request.url, replayed.request.url)
        assertEquals("orders/7", replayed.result.target)
    }

    @Test
    fun anEntryWhoseRequestBodyWasCutIsReplayedOnlyWithABodyOfTheReplay() {
        val limited = HistoryStore(maxBodyBytes = 4)
        val cut = limited.record(exchangeOf(served(orders, ok), posted))
        var handled = 0
        val count: suspend (MockRequest) -> Int = { ++handled }

        val refused = runBlocking { limited.replay(cut.id, ReplayEdits(headers = emptyList()), count) }
        val replayed = runBlocking { limited.replay(cut.id, ReplayEdits(body = "ordered".toByteArray()), count) }

        val explanation =
            "The request body of the history entry 1 was cut when it was recorded, so the entry can be replayed only with a body that is " +
                "given for the replay."
        assertEquals(Replay.Refused(explanation), refused)
        assertEquals("ordered", String(assertIs<Replay.Handled<Int>>(replayed).request.body))
        assertEquals(1, handled)
    }

    @Test
    fun anEntryWhoseResponseBodyWasCutIsReplayedAsAnyOther() {
        val limited = HistoryStore(maxBodyBytes = 1)
        val cut = limited.record(exchangeOf(served(orders, ok), requestFor("orders/7")))

        val replayed = runBlocking { limited.replay(cut.id, ReplayEdits()) { it.url } }

        assertEquals(true to false, cut.response.body.isCut to cut.request.body.isCut)
        assertEquals(posted.url, assertIs<Replay.Handled<String>>(replayed).result)
    }

    @Test
    fun anEntryThatTheHistoryDoesNotKeepIsNotReplayed() {
        var handled = false

        val replayed = runBlocking { history.replay(HistoryEntryId(99), ReplayEdits()) { handled = true } }

        assertEquals(Replay.NotFound("The history keeps no entry with the identifier 99."), replayed)
        assertFalse(handled)
    }

    @Test
    fun theEditsOfAReplayKeepWhatTheyWereGiven() {
        val headers = mutableListOf(Header("X-A", "1"))
        val body = "body".toByteArray()
        val edits = ReplayEdits(headers, listOf(Parameter("a", "1")), body)

        headers += Header("X-B", "2")
        edits.body!![0] = 'B'.code.toByte()

        assertEquals(listOf(Header("X-A", "1")), edits.headers)
        assertContentEquals("body".toByteArray(), edits.body)
        assertEquals("ReplayEdits(headers, parameters, body)", edits.toString())
        assertEquals("ReplayEdits()", ReplayEdits().toString())
        assertEquals(listOf(null, null, null), ReplayEdits().let { listOf(it.headers, it.parameters, it.body) })
    }
}
