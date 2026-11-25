package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.HistoryEntryDto
import io.github.dnedrow.mockey.api.HistoryStatisticsDto
import io.github.dnedrow.mockey.api.MatchCriterionDto
import io.github.dnedrow.mockey.api.NameValueDto
import io.github.dnedrow.mockey.api.ProblemDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ServiceStatisticsDto
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.history.HistoryEntry
import io.github.dnedrow.mockey.core.history.HistoryEntryId
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.request.MockRequest
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Task 12.6: the operations of the admin API on the history, over real HTTP: on a listener that runs the
 * admin API with the operations on the history, for an engine of the test.
 *
 * The engine handles the requests of a test as the mock endpoint hands them to it, see [handled], and each
 * arrives one second after the one before. A test sends its request to the listener, and looks at the answer
 * and at what the history holds afterwards.
 */
class AdminHistoryTest {
    /** A clock that tells the time that a test sets. */
    private class TestClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = TestClock(START)
    private val engines = mutableListOf<MockeyEngine>()
    private val listeners = mutableListOf<Listener>()
    private val engine: MockeyEngine = engine()
    private val listener = listenerOf(engine)

    /** The service `Customers`, in static mode: its scenario `ok` answers with JSON, and its scenario `error` with the status 500. */
    private val customers: Service =
        runBlocking {
            val service = service("Customers", "customer/{id}", engine)
            val json = listOf(Header("Content-Type", "application/json"))
            engine.scenarios.create(service.id) { it.copy(name = "ok", responseBody = """{"tier":"gold"}""", headers = json) }.getOrThrow()
            engine.scenarios.create(service.id) { it.copy(name = "error", responseBody = "no customer", statusCode = 500) }.getOrThrow()
            engine.definitions.services.single()
        }

    /** The service `Orders`, in static mode and with the tag `team-b`: its scenario `listed` answers with a text. */
    private val orders: Service =
        runBlocking {
            val service = service("Orders", "orders", engine) { it.copy(tags = Tags.of("team-b")) }
            engine.scenarios.create(service.id) { it.copy(name = "listed", responseBody = "the list of the day") }.getOrThrow()
            engine.definitions.services.last()
        }

    @AfterTest
    fun stopEverything() {
        listeners.forEach(Listener::stop)
        engines.forEach(MockeyEngine::close)
    }

    /** Returns an engine with the clock of the test, whose history entries hold [bodyBytes] bytes of a body at most. */
    private fun engine(bodyBytes: Int = 1_024): MockeyEngine {
        val created =
            runBlocking {
                MockeyEngine.create {
                    clock = this@AdminHistoryTest.clock
                    bodyCaptureLimit = bodyBytes
                    inspectors = emptyList()
                }
            }
        engines += created
        return created
    }

    /** Starts a listener that runs the admin API with the operations on the history of [engine]. */
    private fun listenerOf(engine: MockeyEngine): Listener {
        val api = AdminApi(token = null, maxRequestBody = 65_536, routes = AdminHistory(engine).routes)
        val started = Listener.start(ListenerKind.ADMIN, RawHttp.loopback(0), MockeyServer.adminListenerModule(ForwardMark(null), api))
        listeners += started
        return started
    }

    /** Defines a service in static mode in [engine], with the attributes that [define] gives it. */
    private fun service(
        name: String,
        mockUrl: String,
        engine: MockeyEngine,
        define: (Service) -> Service = { it },
    ): Service =
        runBlocking {
            engine.services.create { define(it.copy(name = name, mockUrl = mockUrl, responseMode = ResponseMode.STATIC)) }.getOrThrow()
        }

    /**
     * Lets [engine] handle a request for [target], which is what follows `/service/` with its query string,
     * one second after the request before, and returns the history entry of the request.
     */
    private fun handled(
        target: String,
        method: String = "GET",
        headers: List<Header> = emptyList(),
        body: String = "",
        engine: MockeyEngine = this.engine,
    ): HistoryEntry {
        clock.now = clock.now.plusSeconds(1)
        val query = target.substringAfter('?', "").ifEmpty { null }
        val request = MockRequest(method, "http://mockey.test/service/${target.substringBefore('?')}", query, headers, body.toByteArray())
        return runBlocking { engine.handle(request, CLIENT).entry }
    }

    private fun send(
        method: String,
        target: String,
        body: String = "",
        listener: Listener = this.listener,
    ): RawHttp.Answer {
        val headers = if (body.isEmpty()) emptyList() else listOf("Content-Type: application/json")
        return RawHttp.exchange(listener.address, RawHttp.request(method, "/api/v1/$target", headers, RawHttp.utf8(body)))
    }

    private inline fun <reified T> RawHttp.Answer.decoded(): T = AdminApiJson.instance.decodeFromString<T>(bodyInUtf8)

    /** Returns the identifiers of the entries that the answer lists, in their order. */
    private fun RawHttp.Answer.ids(): List<Long> {
        assertEquals("HTTP/1.1 200 OK", statusLine, "$this")
        return decoded<List<HistoryEntryDto>>().map { it.id }
    }

    /** The identifiers of the entries that the history keeps, with the newest one first. */
    private val kept: List<Long>
        get() = engine.history.entries().map { it.id.value }

    private fun base64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun assertProblem(
        answer: RawHttp.Answer,
        status: Int,
        title: String,
        detail: String,
        errors: List<FieldErrorDto> = emptyList(),
    ) {
        assertEquals("HTTP/1.1 $status $title", answer.statusLine, "$answer")
        assertEquals(listOf("application/problem+json"), answer.headerValues("Content-Type"))
        assertEquals(ProblemDto("about:blank", title, status, detail, errors), answer.decoded<ProblemDto>())
    }

    /** [answer] is the problem of a request that is not valid, and it names exactly [errors], which are fields with their messages. */
    private fun assertInvalid(
        answer: RawHttp.Answer,
        vararg errors: Pair<String, String>,
    ) {
        val fields = errors.map { (field, message) -> FieldErrorDto(field, message) }
        val detail = fields.joinToString(" ", "The request is not valid. ") { "${it.field}: ${it.message}" }
        assertProblem(answer, 422, "Unprocessable Entity", detail, fields)
    }

    private fun assertNotKept(
        answer: RawHttp.Answer,
        id: String,
    ) {
        assertProblem(answer, 404, "Not Found", "The history keeps no entry with the identifier $id.")
    }

    /** [answer] is the problem of a request whose query string has something at the parameter [name] that is not [what] it takes. */
    private fun assertNoValue(
        answer: RawHttp.Answer,
        name: String,
        what: String,
    ) {
        assertProblem(answer, 400, "Bad Request", "The parameter $name of the query string has to be $what.")
    }

    // Listing

    /** The entries are stated as the engine holds them, with the newest one first, and a history without entries is an empty list. */
    @Test
    fun theHistoryIsListedWithTheNewestEntryFirst() {
        val none = send("GET", "history")
        val first = handled("customer/7?verbose=true")
        val second = handled("unknown")
        val third = handled("orders", method = "POST", body = "an order")

        val listed = send("GET", "history")

        assertEquals(200 to "[]", none.status to none.body)
        assertEquals("HTTP/1.1 200 OK", listed.statusLine, "$listed")
        assertEquals(listOf(third, second, first).map(HistoryEntry::toAdminDto), listed.decoded<List<HistoryEntryDto>>())
        val bodies = listed.decoded<List<HistoryEntryDto>>().map { String(Base64.getDecoder().decode(it.request.body.base64)) }
        assertEquals(listOf("an order", "", ""), bodies)
    }

    /** The scenario "Filtered list with a maximum": of 50 entries, 12 satisfy the token, and the answer holds the 5 newest of them. */
    @Test
    fun aListWithATokenAndAMaximumHoldsTheNewestEntriesThatSatisfyTheToken() {
        val ofOrders = (1..50).filter { it % 4 == 1 }.take(12)
        val entries = (1..50).map { if (it in ofOrders) handled("orders") else handled("customer/$it") }
        val satisfying = entries.filter { "orders" in it.request.url }.map { it.id.value }

        val listed = send("GET", "history?token=orders&max=5")
        val all = send("GET", "history?token=orders")

        assertEquals(50 to 12, kept.size to satisfying.size)
        assertEquals(satisfying.sortedDescending().take(5), listed.ids())
        assertEquals(satisfying.sortedDescending(), all.ids())
    }

    /**
     * The scenarios "All tokens must be satisfied", "Negated token", and "Letter case matters" of the
     * history, through the API. A token is one value of the parameter, with its spaces, and it is looked
     * for in the texts of an entry: here in a URL, in a name, in a tag, in a header, and in a body.
     */
    @Test
    fun aListTakesEveryTokenAsTheRequestWritesIt() {
        val ofCustomers = handled("customer/7?verbose=true", headers = listOf(Header("X-Trace", "abc 123"))).id.value
        val ofOrders = handled("orders").id.value
        val ofNoService = handled("unknown/orders").id.value
        val filters =
            mapOf(
                "token=orders" to listOf(ofNoService, ofOrders),
                "token=orders&token=team-b" to listOf(ofOrders),
                "token=orders&token=!team-b" to listOf(ofNoService),
                "token=orders&token=%21team-b" to listOf(ofNoService),
                "token=Orders" to listOf(ofOrders),
                "token=ORDERS" to emptyList(),
                "token=gold" to listOf(ofCustomers),
                "token=!gold" to listOf(ofNoService, ofOrders),
                "token=verbose%3Dtrue" to listOf(ofCustomers),
                "token=X-Trace:+abc+123" to listOf(ofCustomers),
                "token=abc++123" to emptyList(),
                "token=list+of+the" to listOf(ofOrders),
                "token=$CLIENT" to listOf(ofNoService, ofOrders, ofCustomers),
                "token=" to listOf(ofNoService, ofOrders, ofCustomers),
                "token=!" to emptyList(),
                "token=orders&token=gold" to emptyList(),
            )

        val listed = filters.mapValues { (filter, _) -> send("GET", "history?$filter").ids() }

        assertEquals(filters, listed)
    }

    /**
     * The scenario "Only newer entries" of the history, through the API, and the filter by service. The
     * entry that a request names as its position need not be kept any longer, and neither does the service.
     */
    @Test
    fun aListIsLimitedToTheEntriesOfAServiceAndToTheOnesAfterAnEntry() {
        val a = handled("customer/7").id.value
        val b = handled("orders").id.value
        val c = handled("customer/8").id.value
        val d = handled("unknown").id.value
        runBlocking { engine.services.delete(orders.id).getOrThrow() }
        engine.history.delete(HistoryEntryId(a))
        val ofCustomers = "serviceId=${customers.id.value}"
        val filters =
            mapOf(
                "after=$a" to listOf(d, c, b),
                "after=$c" to listOf(d),
                "after=$d" to emptyList(),
                "after=0" to listOf(d, c, b),
                "after=9000000000000000000" to emptyList(),
                ofCustomers to listOf(c),
                "serviceId=${orders.id.value}" to listOf(b),
                "serviceId=Customers" to emptyList(),
                "$ofCustomers&after=$a" to listOf(c),
                "$ofCustomers&after=$c" to emptyList(),
                "after=$a&max=2" to listOf(d, c),
                "after=$a&max=0" to emptyList(),
                "max=1&token=customer" to listOf(c),
                "max=2147483647" to listOf(d, c, b),
            )

        val listed = filters.mapValues { (filter, _) -> send("GET", "history?$filter").ids() }

        assertEquals(filters, listed)
    }

    /** What is no value of a parameter is refused, and so is a parameter that the list does not take. A problem repeats no value. */
    @Test
    fun aListRefusesWhatIsNoValueOfAParameter() {
        handled("orders")
        val number = "a whole number from 0 to 2147483647"
        val entry = "the identifier of a history entry"

        for (maximum in listOf("-1", "five", "1.5", "", "2147483648", "+1", "01")) {
            assertNoValue(send("GET", "history?max=$maximum"), "max", number)
        }
        for (position in listOf("-1", "first", "1.0", "", "9223372036854775808")) {
            assertNoValue(send("GET", "history?after=$position"), "after", entry)
        }
        for (service in listOf("", "+", "%20")) {
            assertNoValue(send("GET", "history?serviceId=$service"), "serviceId", "the identifier of a service")
        }
        val twice = send("GET", "history?max=1&max=2")
        val another = send("GET", "history?limit=5&token=$SECRET")
        assertProblem(twice, 400, "Bad Request", "The query string of the request has the parameter max more than once.")
        assertProblem(another, 400, "Bad Request", "$NOT_TAKEN It takes these: token, serviceId, after, max.")
        assertFalse(SECRET in another.text)
    }

    // Reading

    @Test
    fun anEntryIsReadByItsIdentifier() {
        val first = handled("customer/7?verbose=true", headers = listOf(Header("Accept", "application/json")))
        handled("orders")

        val read = send("GET", "history/${first.id}")

        assertEquals("HTTP/1.1 200 OK", read.statusLine, "$read")
        val entry = read.decoded<HistoryEntryDto>()
        assertEquals(first.toAdminDto(), entry)
        val handling = entry.run { listOf(target, request.query, outcome, service?.name, scenario?.name) }
        assertEquals(listOf("customer/7", "verbose=true", "scenarioServed", "Customers", "ok"), handling)
        assertEquals(200 to base64("""{"tier":"gold"}"""), entry.response.status to entry.response.body.base64)
        assertEquals(CLIENT to "2026-01-02T03:04:06Z", entry.clientAddress to entry.time)
    }

    // Flagging

    /** The scenario "Flag and unflag" of the history, through the API. An update that states nothing leaves the mark as it is. */
    @Test
    fun anEntryIsFlaggedAndUnflagged() {
        val entry = handled("customer/7")
        val other = handled("orders")
        val target = "history/${entry.id}"

        val flagged = send("PATCH", target, """{"flagged": true}""")
        val read = send("GET", target)
        val unchanged = send("PATCH", target, "{}")
        val afterTheFlag = engine.history.entry(entry.id)
        val unflagged = send("PATCH", target, """{"flagged": false}""")

        assertEquals("HTTP/1.1 200 OK", flagged.statusLine, "$flagged")
        assertEquals(entry.copy(flagged = true).toAdminDto(), flagged.decoded<HistoryEntryDto>())
        assertEquals(200 to true, read.status to read.decoded<HistoryEntryDto>().flagged)
        assertEquals(200 to true, unchanged.status to unchanged.decoded<HistoryEntryDto>().flagged)
        assertEquals(entry.copy(flagged = true), afterTheFlag)
        assertEquals(200 to entry.toAdminDto(), unflagged.status to unflagged.decoded<HistoryEntryDto>())
        assertEquals(listOf(other, entry), engine.history.entries())
    }

    @Test
    fun anUpdateOfAnEntryStatesTheMarkAndNothingElse() {
        val entry = handled("customer/7")

        val ofAText = send("PATCH", "history/${entry.id}", """{"flagged": "true"}""")
        val ofAnother = send("PATCH", "history/${entry.id}", """{"flagged": true, "outcome": "proxied"}""")

        assertInvalid(ofAText, "flagged" to "The value has to be true or false.")
        assertInvalid(ofAnother, "outcome" to "The request can have no member of this name here.")
        assertEquals(listOf(entry), engine.history.entries())
    }

    // Deletion

    @Test
    fun oneEntryIsDeleted() {
        val first = handled("customer/7")
        val second = handled("orders")

        val answer = send("DELETE", "history/${first.id}")
        val again = send("DELETE", "history/${first.id}")

        assertEquals("HTTP/1.1 204 No Content" to "", answer.statusLine to answer.body)
        assertEquals(emptyList(), answer.headerValues("Content-Type") + answer.headerValues("Content-Length"))
        assertEquals(listOf(second), engine.history.entries())
        assertNotKept(again, "${first.id}")
    }

    /**
     * The scenario "Delete the entries of one service" of the history, through the API. The entries of the
     * requests that no service handled stay as well. A service without entries is no failure.
     */
    @Test
    fun theEntriesOfOneServiceAreDeleted() {
        val entries = listOf(handled("customer/7"), handled("orders"), handled("unknown"), handled("customer/8"))

        val answer = send("DELETE", "history?serviceId=${customers.id.value}")
        val again = send("DELETE", "history?serviceId=${customers.id.value}")
        val ofNoService = send("DELETE", "history?serviceId=no-such-service")

        assertEquals("HTTP/1.1 204 No Content" to "", answer.statusLine to answer.body)
        assertEquals(204 to 204, again.status to ofNoService.status)
        assertEquals(listOf(entries[2], entries[1]), engine.history.entries())
    }

    /** The entries that are recorded after all were deleted have identifiers that no entry had before. */
    @Test
    fun allEntriesAreDeleted() {
        val before = listOf(handled("customer/7"), handled("orders"), handled("unknown")).map { it.id.value }

        val answer = send("DELETE", "history")
        val afterwards = handled("orders").id.value
        val again = send("DELETE", "history")

        assertEquals("HTTP/1.1 204 No Content" to "", answer.statusLine to answer.body)
        assertEquals(listOf(1L, 2L, 3L) to 4L, before to afterwards)
        assertEquals(204 to emptyList(), again.status to kept)
    }

    /** A request that means some entries and says so in a way that the operation does not take deletes none. */
    @Test
    fun aDeletionOfSeveralEntriesDeletesNothingForAParameterThatItDoesNotTake() {
        val entries = listOf(handled("customer/7"), handled("orders")).map { it.id.value }
        val taken = "$NOT_TAKEN It takes these: serviceId."

        val ofAToken = send("DELETE", "history?token=orders")
        val misspelled = send("DELETE", "history?serviceid=${orders.id.value}")
        val blank = send("DELETE", "history?serviceId=")
        val twice = send("DELETE", "history?serviceId=${orders.id.value}&serviceId=${customers.id.value}")

        assertProblem(ofAToken, 400, "Bad Request", taken)
        assertProblem(misspelled, 400, "Bad Request", taken)
        assertNoValue(blank, "serviceId", "the identifier of a service")
        assertProblem(twice, 400, "Bad Request", "The query string of the request has the parameter serviceId more than once.")
        assertEquals(entries.reversed(), kept)
    }

    // Replaying

    /**
     * The scenario "Replay under changed definitions" of the history, through the API. The answer is the
     * entry of the replay and says where it is. That entry states the client of the API as its client, and
     * the entry that was replayed stays as it is.
     */
    @Test
    fun anEntryIsReplayedUnderTheDefinitionsAsTheyAreNow() {
        val entry = handled("customer/7?verbose=true", headers = listOf(Header("Accept", "application/json")))
        val error = customers.scenarios.single { it.name == "error" }
        runBlocking { engine.services.update(customers.id) { it.copy(defaultScenarioId = error.id) }.getOrThrow() }

        val answer = send("POST", "history/${entry.id}/replay")

        assertEquals("HTTP/1.1 201 Created", answer.statusLine, "$answer")
        val replayed = answer.decoded<HistoryEntryDto>()
        assertEquals(listOf("/api/v1/history/${replayed.id}"), answer.headerValues("Location"))
        assertEquals(listOf(replayed.id, entry.id.value), kept)
        val newest = engine.history.entries().first()
        assertEquals(newest.toAdminDto(), replayed)
        assertEquals(entry.toAdminDto().request, replayed.request)
        val answered = replayed.run { listOf(scenario?.name, response.status, response.body.base64) }
        assertEquals(listOf("error", 500, base64("no customer")), answered)
        assertEquals("127.0.0.1" to CLIENT, replayed.clientAddress to entry.clientAddress)
        assertEquals(entry, engine.history.entry(entry.id))
    }

    /**
     * The scenario "Replay with an edited body" of the history, through the API, and a replay with other
     * headers and other parameters. What the request does not state is that of the entry.
     */
    @Test
    fun anEntryIsReplayedWithWhatTheRequestChangesOfIt() {
        val sent = listOf(Header("Content-Type", "text/plain"), Header("X-Trace", "abc"))
        val entry = handled("orders?page=1&size=20", method = "POST", headers = sent, body = "an order")
        val recorded = entry.toAdminDto().request
        val otherHeaders = """[{"name": "Content-Type", "value": "application/json"}, {"name": "X-Trace", "value": "ä b"}]"""
        val replay = { changes: String -> send("POST", "history/${entry.id}/replay", changes).decoded<HistoryEntryDto>() }

        val withABody = replay("""{"bodyBase64": "${base64("another order")}"}""").request
        val withHeaders = replay("""{"headers": $otherHeaders}""").request
        val withParameters = replay("""{"parameters": [{"name": "page", "value": "2 & 3"}]}""").request
        val withNothing = replay("""{"headers": null, "parameters": null, "bodyBase64": null}""").request
        val withoutAll = replay("""{"headers": [], "parameters": [], "bodyBase64": ""}""").request

        assertEquals(recorded.copy(body = recorded.body.copy(base64 = base64("another order"), size = 13)), withABody)
        val headers = listOf(HeaderDto("Content-Type", "application/json"), HeaderDto("X-Trace", "ä b"))
        assertEquals(recorded.copy(headers = headers), withHeaders)
        assertEquals(recorded.copy(query = "page=2%20%26%203", parameters = listOf(NameValueDto("page", "2 & 3"))), withParameters)
        assertEquals(recorded, withNothing)
        val empty = recorded.body.copy(base64 = "", size = 0)
        assertEquals(recorded.copy(query = null, headers = emptyList(), parameters = emptyList(), body = empty), withoutAll)
        assertEquals(6, kept.size)
    }

    /** An entry holds the start of a long body, so a replay of it needs the body from the request. */
    @Test
    fun anEntryWithACutRequestBodyIsReplayedOnlyWithABody() {
        val short = engine(bodyBytes = 8)
        service("Orders", "orders", short)
        val api = listenerOf(short)
        val entry = handled("orders", method = "POST", body = "an order that is long", engine = short)
        val target = "history/${entry.id}/replay"

        val withoutABody = send("POST", target, listener = api)
        val withOtherHeaders = send("POST", target, """{"headers": []}""", api)
        val withABody = send("POST", target, """{"bodyBase64": "${base64("an order that is long")}"}""", api)

        val explanation =
            "The request body of the history entry ${entry.id} was cut when it was recorded, so the entry can be replayed only " +
                "with a body that is given for the replay."
        assertInvalid(withoutABody, "bodyBase64" to explanation)
        assertInvalid(withOtherHeaders, "bodyBase64" to explanation)
        assertEquals("HTTP/1.1 201 Created", withABody.statusLine, "$withABody")
        val replayed = withABody.decoded<HistoryEntryDto>().request.body
        assertEquals(21L to true, replayed.size to replayed.isCut)
        assertEquals(2, short.history.entries().size)
    }

    @Test
    fun aReplayTakesItsChangesAsTheWireTypeHasThem() {
        val entry = handled("orders", method = "POST", body = "an order")
        val target = "history/${entry.id}/replay"
        val notBase64 = "The value has to be a text in Base64."

        val ofNoBase64 = listOf("an order", "YW4-b3JkZXI=", " YW4gb3JkZXI=").map { send("POST", target, """{"bodyBase64": "$it"}""") }
        val changes = """{"body": "an order", "headers": {"X-Trace": "abc"}, "parameters": [{"name": "page"}]}"""
        val ofAnotherKind = send("POST", target, changes)
        val ofNoObject = send("POST", target, """["an order"]""")

        ofNoBase64.forEach { assertInvalid(it, "bodyBase64" to notBase64) }
        assertInvalid(
            ofAnotherKind,
            "body" to "The request can have no member of this name here.",
            "headers" to "The value has to be a list.",
            "parameters[0].value" to "The member is missing.",
        )
        assertProblem(ofNoObject, 400, "Bad Request", "The body of the request is no JSON object.")
        assertEquals(listOf(entry), engine.history.entries())
    }

    // Saving as a scenario

    /**
     * The scenario "Recorded upstream response becomes a scenario" of the history, through the API: the
     * answer of a real service becomes a scenario of the service that forwarded the request. The answer
     * holds the scenario and says where it is.
     */
    @Test
    fun theEntryOfAProxiedExchangeIsSavedAsAScenarioOfItsService() {
        StubUpstream(headers = listOf("Content-Type: application/json", "X-Rate: 7"), body = """{"order":7}""").use { upstream ->
            val realUrls = listOf("${upstream.origin}/real")
            val real = runBlocking { engine.services.create { it.copy(name = "Real", mockUrl = "real", realUrls = realUrls) } }
            val service = real.getOrThrow()
            val entry = handled("real")

            val answer = send("POST", "history/${entry.id}/scenario", """{"name": "recorded order"}""")

            assertEquals("proxied" to 200, entry.toAdminDto().run { outcome to response.status })
            assertEquals("HTTP/1.1 201 Created", answer.statusLine, "$answer")
            val scenario = answer.decoded<ScenarioDto>()
            assertEquals(listOf("/api/v1/services/${service.id.value}/scenarios/${scenario.id}"), answer.headerValues("Location"))
            val headers = listOf(HeaderDto("content-type", "application/json"), HeaderDto("x-rate", "7"))
            assertEquals(ScenarioDto(scenario.id, "recorded order", """{"order":7}""", 200, headers, MatchCriterionDto.None), scenario)
            val saved = engine.definitions.services.single { it.id == service.id }
            assertEquals(listOf(scenario), saved.scenarios.map { it.toAdminDto(engine.lastUsedTimes) })
            assertEquals(scenario.id, saved.defaultScenarioId?.value)
        }
    }

    /**
     * The request states attributes of the scenario, which take the place of what the entry gives. Without
     * a body the scenario is as the entry gives it, with a name that the engine generates.
     */
    @Test
    fun aScenarioOfAnEntryTakesTheAttributesThatTheRequestStates() {
        val entry = handled("customer/7")
        val attributes =
            """
            {"name": "gold customer", "statusCode": 203, "tags": ["Recorded"], "delayMillis": 5,
             "matchCriterion": {"type": "text", "text": "gold"}, "methodRestriction": "GET"}
            """.trimIndent()

        val asGiven = send("POST", "history/${entry.id}/scenario")
        val withAttributes = send("POST", "history/${entry.id}/scenario", attributes)

        assertEquals(201 to 201, asGiven.status to withAttributes.status)
        val json = listOf(HeaderDto("Content-Type", "application/json"))
        val given = asGiven.decoded<ScenarioDto>()
        val generated = "Scenario for Customers (name auto-generated)"
        assertEquals(ScenarioDto(given.id, generated, """{"tier":"gold"}""", 200, json, MatchCriterionDto.None), given)
        val stated = withAttributes.decoded<ScenarioDto>()
        val criterion = MatchCriterionDto.Text("gold")
        val expected = ScenarioDto(stated.id, "gold customer", """{"tier":"gold"}""", 203, json, criterion, "GET", 5, listOf("recorded"))
        assertEquals(expected, stated)
        val ofCustomers = engine.definitions.services.first()
        assertEquals(listOf("ok", "error", generated, "gold customer"), ofCustomers.scenarios.map { it.name })
    }

    /**
     * The scenario "Cut body is refused" of the history, through the API, and an entry without a service.
     * The problem names the entry and says why no scenario can be made of it, and none is created.
     */
    @Test
    fun anEntryWithoutAServiceOrWithACutResponseBodyIsNotSavedAsAScenario() {
        val short = engine(bodyBytes = 8)
        val api = listenerOf(short)
        val service = service("Orders", "orders", short)
        runBlocking { short.scenarios.create(service.id) { it.copy(name = "listed", responseBody = "the list of the day") }.getOrThrow() }
        val cut = handled("orders", engine = short)
        val withoutAService = handled("unknown", engine = short)
        val before = short.definitions

        val ofCut = send("POST", "history/${cut.id}/scenario", """{"name": "recorded"}""", api)
        val ofNoService = send("POST", "history/${withoutAService.id}/scenario", listener = api)

        val whyCut = "The response body of the history entry ${cut.id} was cut when it was recorded, so a scenario of it would not give "
        assertInvalid(ofCut, "entry" to whyCut + "that response.")
        val whyNoService = "The history entry ${withoutAService.id} has no service, so there is no service to give a scenario to."
        assertInvalid(ofNoService, "entry" to whyNoService)
        assertEquals(before, short.definitions)
    }

    /** An entry stays when its service is deleted, and then there is no service to give a scenario to. */
    @Test
    fun anEntryOfAServiceThatIsGoneIsNotSavedAsAScenario() {
        val entry = handled("orders")
        runBlocking { engine.services.delete(orders.id).getOrThrow() }

        val answer = send("POST", "history/${entry.id}/scenario")

        assertProblem(answer, 404, "Not Found", "No service has the identifier ${orders.id.value}.")
        assertEquals(listOf(customers), engine.definitions.services)
    }

    @Test
    fun aScenarioOfAnEntryThatBreaksTheRulesForScenariosIsNotCreated() {
        val entry = handled("customer/7")
        val before = engine.definitions

        val withABlankText = send("POST", "history/${entry.id}/scenario", """{"matchCriterion": {"type": "text", "text": " "}}""")
        val ofAnotherKind = send("POST", "history/${entry.id}/scenario", """{"statusCode": "200", "id": "s1"}""")

        assertInvalid(withABlankText, "matchCriterion.text" to "The text of a text criterion is not blank.")
        assertInvalid(
            ofAnotherKind,
            "id" to "The request can have no member of this name here.",
            "statusCode" to "The value has to be a whole number.",
        )
        assertEquals(before, engine.definitions)
    }

    // Statistics

    /**
     * The scenario "Counts within a time range" of the history, through the API. Every service is stated,
     * also one without entries, and the oldest entry that the history keeps whatever the range is.
     */
    @Test
    fun theStatisticsCountTheEntriesOfEveryServiceWithinATimeRange() {
        val none = send("GET", "stats")
        listOf("customer/7", "unknown", "customer/8", "orders", "customer/9").forEach { handled(it) }
        val ofCustomers = { count: Int, earliest: String? -> ServiceStatisticsDto(customers.id.value, "Customers", count, earliest) }
        val ofOrders = { count: Int, earliest: String? -> ServiceStatisticsDto(orders.id.value, "Orders", count, earliest) }
        val oldest = "2026-01-02T03:04:06Z"

        val all = send("GET", "stats")
        val inARange = send("GET", "stats?from=2026-01-02T03:04:08Z&to=2026-01-02T04:04:09%2B01:00")
        val fromATime = send("GET", "stats?from=2026-01-02T03:04:09.5Z")
        val upToATime = send("GET", "stats?to=2026-01-02T03:04:06Z&format=json")

        val empty = HistoryStatisticsDto(listOf(ofCustomers(0, null), ofOrders(0, null)))
        assertEquals(200 to empty, none.status to none.decoded<HistoryStatisticsDto>())
        assertEquals("HTTP/1.1 200 OK", all.statusLine, "$all")
        assertEquals(listOf("application/json"), all.headerValues("Content-Type"))
        val whole = HistoryStatisticsDto(listOf(ofCustomers(3, oldest), ofOrders(1, "2026-01-02T03:04:09Z")), oldest)
        assertEquals(whole, all.decoded<HistoryStatisticsDto>())
        val ranged = listOf(ofCustomers(1, "2026-01-02T03:04:08Z"), ofOrders(1, "2026-01-02T03:04:09Z"))
        val inTheRange = HistoryStatisticsDto(ranged, oldest, "2026-01-02T03:04:08Z", "2026-01-02T03:04:09Z")
        assertEquals(inTheRange, inARange.decoded<HistoryStatisticsDto>())
        val late = listOf(ofCustomers(1, "2026-01-02T03:04:10Z"), ofOrders(0, null))
        assertEquals(HistoryStatisticsDto(late, oldest, from = "2026-01-02T03:04:09.500Z"), fromATime.decoded<HistoryStatisticsDto>())
        val early = listOf(ofCustomers(1, oldest), ofOrders(0, null))
        assertEquals(HistoryStatisticsDto(early, oldest, to = oldest), upToATime.decoded<HistoryStatisticsDto>())
    }

    /** The scenario "CSV output" of the history, through the API: a line of column names, and one line for each service. */
    @Test
    fun theStatisticsAreStatedAsCsvWithOneLineForEachService() {
        runBlocking { engine.services.update(orders.id) { it.copy(name = "=Orders, \"new\" ✓") }.getOrThrow() }
        listOf("customer/7", "orders", "customer/8").forEach { handled(it) }

        val answer = send("GET", "stats?format=csv&from=2026-01-02T03:04:07Z")

        assertEquals("HTTP/1.1 200 OK", answer.statusLine, "$answer")
        assertEquals(listOf("text/csv; charset=UTF-8"), answer.headerValues("Content-Type"))
        val lines =
            listOf(
                "Service identifier,Service name,Entries,Earliest entry,Oldest retained entry",
                "${customers.id.value},Customers,1,2026-01-02T03:04:08Z,2026-01-02T03:04:06Z",
                "${orders.id.value},\"'=Orders, \"\"new\"\" ✓\",1,2026-01-02T03:04:07Z,2026-01-02T03:04:06Z",
            )
        assertEquals(lines.joinToString("") { "$it\r\n" }, answer.bodyInUtf8)
    }

    @Test
    fun theStatisticsRefuseWhatIsNoTimeAndNoFormat() {
        handled("orders")
        val time = "a time in ISO 8601 with an offset, such as 2026-01-02T03:04:05Z"

        for (text in listOf("yesterday", "2026-01-02", "2026-01-02T03:04:05", "2026-01-02T03:04:05+01:00", "1767323045", "")) {
            assertNoValue(send("GET", "stats?from=$text"), "from", time)
            assertNoValue(send("GET", "stats?to=$text"), "to", time)
        }
        for (format in listOf("xml", "CSV", "")) {
            assertNoValue(send("GET", "stats?format=$format"), "format", "json or csv")
        }
        val another = send("GET", "stats?since=2026-01-02T03:04:05Z")
        assertProblem(another, 400, "Bad Request", "$NOT_TAKEN It takes these: from, to, format.")
    }

    // An entry that the history does not keep

    /** The scenario "Unknown resource", for every operation on one entry. An identifier is a number as the history writes it. */
    @Test
    fun anOperationOnAnEntryThatTheHistoryDoesNotKeepGetsTheStatus404() {
        val entry = handled("customer/7")
        val before = engine.history.entries()

        for (id in listOf("2", "0", "01", "+1", "-1", "1.0", "first", "9223372036854775808")) {
            val answers =
                listOf(
                    send("GET", "history/$id"),
                    send("PATCH", "history/$id", """{"flagged": true}"""),
                    send("PATCH", "history/$id", """{"flagged": "not looked at"}"""),
                    send("DELETE", "history/$id"),
                    send("POST", "history/$id/replay"),
                    send("POST", "history/$id/replay", """{"bodyBase64": "not looked at"}"""),
                    send("POST", "history/$id/scenario"),
                )

            answers.forEach { assertNotKept(it, id) }
        }
        assertEquals(listOf(entry), before)
        assertEquals(before, engine.history.entries())
    }

    private companion object {
        /** The time before the first request of a test. */
        val START: Instant = Instant.parse("2026-01-02T03:04:05Z")

        /** The address that the requests of a test come from. */
        const val CLIENT = "192.0.2.7"

        /** A text that a client looks for and that no answer is to repeat. */
        const val SECRET = "tk-4b8e2d6f0a13"

        /** How the problem of a request begins whose query string has a parameter that its operation does not take. */
        const val NOT_TAKEN = "The query string of the request has a parameter that the operation does not take."
    }
}
