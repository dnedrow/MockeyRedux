package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.PlanReferenceDto
import io.github.dnedrow.mockey.api.PlanSaveRequestDto
import io.github.dnedrow.mockey.api.ReplayRequestDto
import io.github.dnedrow.mockey.api.ScenarioReferenceDto
import io.github.dnedrow.mockey.api.TagFilterDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.done
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.json
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 13.1: the client of the admin API, with which the UI asks the server for everything. The server is
 * a stand-in that answers as a test tells it to, and that holds every request against the description of
 * the admin API, see [FakeAdminApi].
 */
class AdminClientTest {
    private val fakes = mutableListOf<FakeAdminApi>()
    private val clients = mutableListOf<AdminClient>()
    private val fake = fake()
    private val client = clientOf(fake)

    @AfterTest
    fun stopEverything() {
        clients.forEach(AdminClient::close)
        fakes.forEach(FakeAdminApi::close)
    }

    private fun fake(token: String? = null): FakeAdminApi = FakeAdminApi(token).also { fakes += it }

    private fun clientOf(
        api: FakeAdminApi,
        token: String? = null,
    ): AdminClient = AdminClient(AdminUrl.of(api.url), token?.let(::AdminToken)).also { clients += it }

    private fun attributes(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /**
     * A function of the client, with what it is to ask for and to return: the request [line] without the
     * prefix of the API, what the server [answers], and what the function [returns] of that answer.
     */
    private class Function(
        val line: String,
        val answers: FakeAdminApi.Answer,
        val returns: Any?,
        val ask: suspend AdminClient.() -> Any?,
    )

    private fun functions(): List<Function> =
        with(Samples) {
            val universal = ScenarioReferenceDto(customers.id, missing.id)
            val filter = TagFilterDto(listOf("team-a"))
            val filtered = "token=customer&token=%21gold&serviceId=svc-customers&after=3&max=50"
            val csv = "Service identifier,Service name,Entries\nsvc-customers,Customers,3\n"
            val document = """{"formatVersion":1,"services":[]}"""
            val ofService = "services/${customers.id}"
            val ofScenario = "$ofService/scenarios/${found.id}"
            listOf(
                Function("GET services?tag=team-a&tag=team+b&name=Customers", json(listOf(customers, orders)), listOf(customers, orders)) {
                    listServices(listOf("team-a", "team b"), "Customers")
                },
                Function("POST services", json(customers, 201), customers) { createService(attributes("""{"name":"Customers"}""")) },
                Function("GET $ofService", json(customers), customers) { getService(customers.id) },
                Function("PATCH $ofService", json(customers), customers) {
                    updateService(customers.id, attributes("""{"delayMillis":5}"""))
                },
                Function("DELETE $ofService", done(), Unit) { deleteService(customers.id) },
                Function("POST $ofService/duplicate", json(customers, 201), customers) { duplicateService(customers.id) },
                Function("POST $ofService/merge", json(mergeReport), mergeReport) { mergeServices(customers.id, listOf(orders.id)) },
                Function("GET $ofService/schema-report", json(schemaReport), schemaReport) { getSchemaReport(customers.id) },
                Function("POST services/real-urls/derive", json(listOf(derived)), listOf(derived)) { deriveRealUrls("qa1", listOf("qa2")) },
                Function("GET $ofService/scenarios", json(listOf(found, missing)), listOf(found, missing)) { listScenarios(customers.id) },
                Function("POST $ofService/scenarios", json(found, 201), found) {
                    createScenario(customers.id, attributes("""{"name":"found"}"""))
                },
                Function("GET $ofScenario", json(found), found) { getScenario(customers.id, found.id) },
                Function("PATCH $ofScenario", json(found), found) {
                    updateScenario(customers.id, found.id, attributes("""{"statusCode":200}"""))
                },
                Function("DELETE $ofScenario", done(), Unit) { deleteScenario(customers.id, found.id) },
                Function("GET universal-error-scenario", json(universal), universal) { getUniversalErrorScenario() },
                Function("PUT universal-error-scenario", json(universal), universal) {
                    setUniversalErrorScenario(customers.id, missing.id)
                },
                Function("DELETE universal-error-scenario", done(), Unit) { clearUniversalErrorScenario() },
                Function("GET plans", json(listOf(plan)), listOf(plan)) { listPlans() },
                Function("POST plans", json(plan, 201), plan) { savePlan(PlanSaveRequestDto(listOf(customers.id), "Night")) },
                Function("GET plans/${plan.id}", json(plan), plan) { getPlan(plan.id) },
                Function("PATCH plans/${plan.id}", json(plan), plan) { updatePlan(plan.id, attributes("""{"description":null}""")) },
                Function("DELETE plans/${plan.id}", done(), Unit) { deletePlan(plan.id) },
                Function("POST plans/${plan.id}/save", json(plan), plan) {
                    saveIntoPlan(plan.id, PlanSaveRequestDto(listOf(customers.id)))
                },
                Function("POST plans/${plan.id}/apply", json(applyReport), applyReport) { applyPlan(plan.id) },
                Function("GET default-plan", json(PlanReferenceDto(plan.id)), PlanReferenceDto(plan.id)) { getDefaultPlan() },
                Function("PUT default-plan", json(PlanReferenceDto(plan.id)), PlanReferenceDto(plan.id)) { setDefaultPlan(plan.id) },
                Function("DELETE default-plan", done(), Unit) { clearDefaultPlan() },
                Function("GET twists", json(listOf(twist)), listOf(twist)) { listTwists() },
                Function("POST twists", json(twist, 201), twist) { createTwist(attributes("""{"name":"German paths"}""")) },
                Function("GET twists/${twist.id}", json(twist), twist) { getTwist(twist.id) },
                Function("PATCH twists/${twist.id}", json(twist), twist) { updateTwist(twist.id, attributes("""{"pairs":[]}""")) },
                Function("DELETE twists/${twist.id}", done(), Unit) { deleteTwist(twist.id) },
                Function("POST twists/${twist.id}/enable", json(twist), twist) { enableTwist(twist.id) },
                Function("POST twists/${twist.id}/disable", json(twist), twist) { disableTwist(twist.id) },
                Function("GET tag-filter", json(filter), filter) { getTagFilter() },
                Function("PUT tag-filter", json(filter), filter) { setTagFilter(listOf("team-a")) },
                Function("DELETE tag-filter", done(), Unit) { clearTagFilter() },
                Function("GET tags", json(listOf("team-a", "nightly")), listOf("team-a", "nightly")) { listTags() },
                Function("DELETE tags/team-a", done(), Unit) { removeTag("team-a") },
                Function("GET upstream-proxy", json(proxy), proxy) { getUpstreamProxy() },
                Function("PATCH upstream-proxy", json(proxy), proxy) {
                    updateUpstreamProxy(attributes("""{"enabled":true,"password":null}"""))
                },
                Function("DELETE system/upstream-session", done(), Unit) { clearUpstreamSession() },
                Function("GET history?$filtered", json(listOf(entry)), listOf(entry)) {
                    listHistory(listOf("customer", "!gold"), customers.id, after = 3, max = 50)
                },
                Function("GET history", json(listOf(entry)), listOf(entry)) { listHistory() },
                Function("DELETE history?serviceId=svc-customers", done(), Unit) { deleteHistory(customers.id) },
                Function("DELETE history", done(), Unit) { deleteHistory() },
                Function("GET history/7", json(entry), entry) { getHistoryEntry(7) },
                Function("PATCH history/7", json(entry), entry) { flagHistoryEntry(7, flagged = true) },
                Function("DELETE history/7", done(), Unit) { deleteHistoryEntry(7) },
                Function("POST history/7/replay", json(entry, 201), entry) { replayHistoryEntry(7, ReplayRequestDto(bodyBase64 = "e30=")) },
                Function("POST history/7/scenario", json(found, 201), found) {
                    saveHistoryEntryAsScenario(7, attributes("""{"name":"again"}"""))
                },
                Function("GET stats?from=2026-01-01T00%3A00%3A00Z&to=2026-12-31T23%3A59%3A59%2B01%3A00", json(statistics), statistics) {
                    getStatistics("2026-01-01T00:00:00Z", "2026-12-31T23:59:59+01:00")
                },
                Function("GET stats?format=csv", FakeAdminApi.Answer(200, csv, "text/csv; charset=UTF-8"), csv) { getStatisticsAsCsv() },
                Function("GET definitions/export", FakeAdminApi.Answer(200, document), document) { exportDefinitions() },
                Function("POST definitions/import?tag=imported&tag=legacy", json(importReport), importReport) {
                    importDefinitions("<mockservice/>".toByteArray(), isOfMockey2 = true, tags = listOf("imported", "legacy"))
                },
                Function("POST definitions/import", json(importReport), importReport) {
                    importDefinitions(document.toByteArray(), isOfMockey2 = false)
                },
                Function("GET diagnostics", json(listOf(diagnostic)), listOf(diagnostic)) { listDiagnostics() },
                Function("GET search?term=customer+7", json(listOf(searchResult)), listOf(searchResult)) { search("customer 7") },
                Function("GET system", json(FakeAdminApi.SYSTEM), FakeAdminApi.SYSTEM) { getSystem() },
                Function("PATCH system", json(FakeAdminApi.SYSTEM), FakeAdminApi.SYSTEM) { setReadOnly(false) },
                Function("POST system/reload", done(), Unit) { reloadDefinitions() },
                Function("POST system/reset", done(), Unit) { resetServer() },
                Function("DELETE system/last-used-times", done(), Unit) { clearLastUsedTimes() },
                Function("GET inspectors", json(listOf(inspector)), listOf(inspector)) { listInspectors() },
            )
        }

    /**
     * Every function of the client asks for an operation that the admin API has, as the description of the
     * API says it, and returns what the server answers with. Together the functions ask for every operation
     * of the API but for its description, which the UI does not read from the server.
     */
    @Test
    fun everyFunctionAsksForAnOperationOfTheApiAndTheFunctionsAskForAllOfThem() {
        for (function in functions()) {
            val before = fake.requests.size
            fake.on(function.line.substringBefore(' '), function.line.substringAfter(' ').substringBefore('?')) { function.answers }

            val returned = runBlocking { function.ask(client) }

            assertEquals(listOf(function.line), fake.requests.drop(before).map { it.line })
            assertEquals(function.returns, returned, function.line)
            assertEquals(emptyList(), fake.violations, function.line)
        }
        val ofTheApi = ApiContract.operations.map { it.id }.toSet()
        assertEquals(setOf("getDescription"), ofTheApi - fake.operations.toSet())
        assertTrue(ofTheApi.size > MANY, "The description has ${ofTheApi.size} operations.")
    }

    /** A request with a body says what its body is, and states it as the API reads it. A request without one says nothing of a body. */
    @Test
    fun aRequestStatesItsBodyAsJsonAndADocumentAsWhatItIs() {
        fake.on("PATCH", "services/svc-1") { json(Samples.customers) }
        fake.on("POST", "services/svc-1/merge") { json(Samples.mergeReport) }
        fake.on("POST", "history/7/replay") { json(Samples.entry, 201) }
        fake.on("POST", "definitions/import") { json(Samples.importReport) }
        fake.on("DELETE", "services/svc-1") { done() }
        val umlauts = "<mockservice name=\"K\u00fcche\"/>".toByteArray(Charsets.ISO_8859_1)
        val stated = "{\"name\":\"K\u00fcche \u2713\",\"tags\":[]}"

        runBlocking {
            client.updateService("svc-1", attributes(stated))
            client.mergeServices("svc-1", listOf("svc-2", "svc-3"))
            client.replayHistoryEntry(7)
            client.importDefinitions(umlauts, isOfMockey2 = true)
            client.deleteService("svc-1")
        }

        val (update, merge, replay, imported, delete) = fake.requests
        assertEquals("application/json" to stated, update.header("content-type") to update.body)
        assertEquals("application/json" to """{"sourceIds":["svc-2","svc-3"]}""", merge.header("content-type") to merge.body)
        assertEquals(null to "", replay.header("content-type") to replay.body)
        assertEquals("application/xml", imported.header("content-type"))
        assertEquals(String(umlauts, Charsets.UTF_8), imported.body)
        assertEquals(null to "", delete.header("content-type") to delete.body)
        assertEquals(List(5) { "application/json" }, fake.requests.map { it.header("accept") })
        assertEquals(emptyList(), fake.requests.filter { "origin" in it.headers })
        assertEquals(emptyList(), fake.violations)
    }

    /** What a path holds at a parameter is one segment, whatever it is, and a query string is written as a form writes it. */
    @Test
    fun anIdentifierWithAnyCharacterStaysOneSegmentOfThePath() {
        val odd = "a/b c?d#e%f\u00e4+g"
        val asSegment = "a%2Fb%20c%3Fd%23e%25f%C3%A4%2Bg"
        fake.on("GET", "services/$asSegment") { json(Samples.customers) }
        fake.on("DELETE", "tags/$asSegment") { done() }
        fake.on("GET", "search") { json(emptyList<String>()) }

        runBlocking {
            client.getService(odd)
            client.removeTag(odd)
            client.search(odd)
        }

        val asked = listOf("GET services/$asSegment", "DELETE tags/$asSegment", "GET search?term=a%2Fb+c%3Fd%23e%25f%C3%A4%2Bg")
        assertEquals(asked, fake.calls)
        assertEquals(emptyList(), fake.violations)
    }

    // The admin token

    /** The requirement "Server connection settings": the client sends the token with every request, as a bearer token. */
    @Test
    fun theClientPresentsTheTokenWithEveryRequestAndNothingWithoutAToken() {
        val token = "s3cr3t of the server/+~=."
        val guarded = fake(token)
        guarded.on("GET", "services") { json(emptyList<String>()) }
        guarded.on("DELETE", "tag-filter") { done() }
        fake.on("GET", "services") { json(emptyList<String>()) }
        val presenting = clientOf(guarded, token)

        runBlocking {
            presenting.getSystem()
            presenting.listServices()
            presenting.clearTagFilter()
            client.listServices()
        }

        assertEquals(listOf("GET system", "GET services", "DELETE tag-filter"), guarded.requests.map { it.line })
        assertEquals(List(3) { listOf("Bearer $token") }, guarded.requests.map { it.headers["authorization"] })
        assertEquals(listOf(null), fake.requests.map { it.headers["authorization"] })
        assertEquals(emptyList(), guarded.violations + fake.violations)
    }

    /** The client follows no redirect: the token is for the server, and for no other place. */
    @Test
    fun theClientFollowsNoRedirectAndPresentsTheTokenToNoOtherPlace() {
        val token = "the token of the server"
        val guarded = fake(token)
        val elsewhere = fake()
        for (status in REDIRECTS) {
            guarded.on("GET", "system") { FakeAdminApi.Answer(status, headers = mapOf("Location" to "${elsewhere.url}/api/v1/system")) }

            val failure = assertFailsWith<ApiFailure.Incompatible> { runBlocking { clientOf(guarded, token).getSystem() } }

            assertEquals("What answers at ${guarded.url} answered with the status $status, and not as an admin API does.", failure.reason)
        }
        assertEquals(emptyList(), elsewhere.requests.map { it.line })
        assertEquals(REDIRECTS.size, guarded.requests.size)
    }

    /** A server that asks for a token gives nothing to a UI without it, and nothing to a UI with another one. */
    @Test
    fun aServerThatDoesNotTakeTheTokenIsReportedAsSuch() {
        val guarded = fake("the token of the server")

        val without = assertFailsWith<ApiFailure.TokenRejected> { runBlocking { clientOf(guarded).getSystem() } }
        val withAnother = assertFailsWith<ApiFailure.TokenRejected> { runBlocking { clientOf(guarded, "another token").listServices() } }

        assertEquals("The server does not take the request without its admin token.", without.message)
        assertFalse("another token" in "${withAnother.message} $withAnother")
    }

    /**
     * Why a token of the UI is made of the characters of ASCII: the HTTP client of the Java runtime writes a
     * question mark in the place of every other character of a header. A runtime whose client sends such a
     * character lets the UI present every token that a server takes, see `docs/decisions/ui-stack.md`.
     */
    @Test
    fun theHttpClientOfTheRuntimeWritesAQuestionMarkForACharacterBeyondAscii() {
        fake.on("GET", "services") { json(emptyList<String>()) }
        val request = HttpRequest.newBuilder(URI.create("${fake.url}/api/v1/services")).header("X-Sent", "a\u00e4\u00c3\u00a4z").build()

        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().use { http ->
            http.send(request, HttpResponse.BodyHandlers.discarding())
        }

        assertEquals("a???z", fake.requests.single().header("x-sent"))
        assertFailsWith<IllegalArgumentException> { AdminToken("a\u00e4z") }
    }

    // What the server answers with

    @Test
    fun aServerThatDoesNotAnswerIsReportedAsNotReached() {
        val gone = fake()
        val asking = clientOf(gone)
        gone.close()
        val short = Duration.ofMillis(200)
        val notThere = AdminClient(AdminUrl.of("http://192.0.2.1:8081"), null, short, short).also { clients += it }
        val slow = fake()
        slow.on("GET", "system") {
            Thread.sleep(LONGER_THAN_SHORT)
            json(FakeAdminApi.SYSTEM)
        }
        val impatient = AdminClient(AdminUrl.of(slow.url), null, short, short).also { clients += it }

        val refused = assertFailsWith<ApiFailure.Unreachable> { runBlocking { asking.getSystem() } }
        val notConnected = assertFailsWith<ApiFailure.Unreachable> { runBlocking { notThere.getSystem() } }
        val notAnswered = assertFailsWith<ApiFailure.Unreachable> { runBlocking { impatient.getSystem() } }

        assertEquals("Nothing accepts connections there.", refused.message)
        assertEquals("The server did not answer in time.", notAnswered.message)
        // An address that is no machine gives no connection in time, or none at all where the system knows no way there.
        assertTrue(notConnected.message.orEmpty().startsWith("No connection to the server "), notConnected.message)
        assertEquals(listOf("GET system"), slow.requests.map { it.line })
    }

    /**
     * The requirement "Incompatible server": what has no admin API of the version of the UI, or answers as
     * no admin API does, is none that the UI manages.
     */
    @Test
    fun whatIsNoAdminApiOfTheVersionOfTheUiIsReportedAsIncompatible() {
        val reasons = mutableListOf<String>()
        val who = "What answers at ${fake.url}"
        val asNoApi = { status: Int -> "$who answered with the status $status, and not as an admin API does." }
        val withNothingOfAnApi = { status: Int -> "$who answered with the status $status and with what no admin API answers with." }
        val answers =
            listOf(
                problem(404, "The admin API has no resource at the path of the request.") to "$who has no admin API of the version v1.",
                FakeAdminApi.Answer(404, "<html>Not found</html>", "text/html") to asNoApi(404),
                FakeAdminApi.Answer(200, "<html>Welcome</html>", "text/html") to withNothingOfAnApi(200),
                FakeAdminApi.Answer(200, """{"status":"running"}""") to withNothingOfAnApi(200),
                FakeAdminApi.Answer(204) to withNothingOfAnApi(204),
                FakeAdminApi.Answer(302, "") to asNoApi(302),
            )

        for ((answer, _) in answers) {
            fake.on("GET", "system") { answer }
            reasons += assertFailsWith<ApiFailure.Incompatible> { runBlocking { client.getSystem() } }.reason
        }

        assertEquals(answers.map { it.second }, reasons)
    }

    /** A server of a later version can state more than this UI knows. The UI reads what it knows. */
    @Test
    fun aMemberThatTheUiDoesNotKnowIsLeftAside() {
        fake.on("GET", "system") {
            FakeAdminApi.Answer(200, json(FakeAdminApi.SYSTEM).body.replace("{\"version\"", "{\"uptime\":{\"seconds\":7},\"version\""))
        }

        val stated = runBlocking { client.getSystem() }

        assertEquals(FakeAdminApi.SYSTEM, stated)
    }

    /**
     * A request that the API refuses comes back with the problem of the API, and one that the server fails
     * at with what the server says of it.
     */
    @Test
    fun aRefusalAndAFailureComeWithTheProblemOfTheServer() {
        val errors = listOf(FieldErrorDto("mockUrl", "The service 'Customers' already uses the mock URL pattern 'customer/{id}'."))
        fake.on("POST", "services") { problem(422, "The request is not valid.", errors) }
        fake.on("GET", "services/gone") { problem(404, "No service has the identifier gone.") }
        fake.on("POST", "system/reload") { problem(500, "The server could not read or write its definitions location.") }

        val invalid = assertFailsWith<ApiFailure.Refused> { runBlocking { client.createService(attributes("""{"name":"Clients"}""")) } }
        val gone = assertFailsWith<ApiFailure.Refused> { runBlocking { client.getService("gone") } }
        val failed = assertFailsWith<ApiFailure.Failed> { runBlocking { client.reloadDefinitions() } }

        assertEquals(422 to errors, invalid.problem.status to invalid.problem.errors)
        assertEquals(404 to "No service has the identifier gone.", gone.problem.status to gone.message)
        assertEquals(500 to "The server could not read or write its definitions location.", failed.problem.status to failed.message)
    }

    /** While nothing is designated, the server answers the question for the designated one with the status 404, which is no failure. */
    @Test
    fun aDesignationThatIsNotThereIsNoFailure() {
        fake.on("GET", "universal-error-scenario") { problem(404, "No scenario is designated as the universal error scenario.") }
        fake.on("GET", "default-plan") { problem(404, "No plan is designated as the default plan.") }

        val designated = runBlocking { client.getUniversalErrorScenario() to client.getDefaultPlan() }

        assertNull(designated.first)
        assertNull(designated.second)
        fake.on("GET", "default-plan") { problem(500, "The server failed to handle the request.") }
        assertIs<ApiFailure.Failed>(runCatching { runBlocking { client.getDefaultPlan() } }.exceptionOrNull())
    }

    private companion object {
        /**
         * A number that the operations of the API exceed by far, so that a description without operations
         * does not pass for one that is covered.
         */
        const val MANY = 50

        /** The statuses with which a server sends a client elsewhere. */
        val REDIRECTS = listOf(301, 302, 303, 307, 308)

        /** A time in milliseconds that is longer than the one that a client of a test waits for an answer. */
        const val LONGER_THAN_SHORT = 1_500L
    }
}
