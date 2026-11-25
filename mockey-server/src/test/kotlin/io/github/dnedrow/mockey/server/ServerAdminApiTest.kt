package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.HistoryEntryDto
import io.github.dnedrow.mockey.api.ImportReportDto
import io.github.dnedrow.mockey.api.MatchCriterionDto
import io.github.dnedrow.mockey.api.PlanApplyReportDto
import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.PlanItemDto
import io.github.dnedrow.mockey.api.ProblemDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.github.dnedrow.mockey.api.TwistConfigurationDto
import io.github.dnedrow.mockey.api.TwistPairDto
import io.github.dnedrow.mockey.core.history.HistoryEntry
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.PlanItem
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistPair
import io.github.dnedrow.mockey.server.TestDefinitions.orders
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlin.io.path.isRegularFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The admin API of a server in the process of the test: the scenarios of the admin API as a whole server
 * shows them, with its definitions location and its mock listener. How the API takes a request, and what it
 * answers when it does not do what the request asks for, is the matter of [AdminApiTest]. What each
 * operation does is the matter of the tests of its area, such as [AdminServicesTest].
 */
class ServerAdminApiTest {
    private val levelBefore = ServerLogging.level()
    private val servers = mutableListOf<MockeyServer>()
    private val directories = mutableListOf<Path>()

    @AfterTest
    fun closeTheServersAndRestoreTheLevelOfTheLog() {
        servers.forEach(MockeyServer::close)
        directories.forEach { it.toFile().deleteRecursively() }
        ServerLogging.configure(levelBefore)
    }

    /** Returns a definitions location that holds the service `Customers`. */
    private fun definitionsLocation(): Path =
        Files.createTempDirectory("mockey-admin-api-test").also {
            directories.add(it)
            StoredDefinitions.customers(it)
        }

    /** Settings with which a server of a test listens on the loopback address, on ports that the system picks, and logs little. */
    private fun settings(definitions: Path = definitionsLocation()) =
        ServerSettings(mockHost = "127.0.0.1", mockPort = 0, adminPort = 0, definitions = definitions, logLevel = LogLevel.WARN)

    private fun start(settings: ServerSettings): MockeyServer = MockeyServer.start(settings).also { servers += it }

    private inline fun <reified T> RawHttp.Answer.decoded(): T = AdminApiJson.instance.decodeFromString<T>(bodyInUtf8)

    /**
     * What a request that reads is not to change: the definitions with their settings, the settings of the
     * engine, the history, the last-used times, and what the definitions location holds, file by file.
     */
    private data class State(
        val definitions: Definitions,
        val tagFilter: Tags,
        val readOnly: Boolean,
        val history: List<HistoryEntry>,
        val lastUsed: List<Map<out Any, Instant>>,
        val stored: Map<String, Pair<FileTime, List<Byte>>>,
    )

    private fun stateOf(
        server: MockeyServer,
        definitions: Path,
    ): State {
        val engine = server.engine
        val stored =
            Files.walk(definitions).use { files ->
                files
                    .filter { it.isRegularFile() }
                    .toList()
                    .associate { "${definitions.relativize(it)}" to (Files.getLastModifiedTime(it) to Files.readAllBytes(it).toList()) }
            }
        val lastUsed =
            listOf(engine.lastUsedTimes.services.toMap(), engine.lastUsedTimes.scenarios.toMap(), engine.lastUsedTimes.plans.toMap())
        return State(engine.definitions, engine.tagFilter, engine.readOnly, engine.history.entries(), lastUsed, stored)
    }

    /**
     * Gives [server] what a read could change: a second service, a plan that is the default plan, a twist
     * configuration that is enabled, a history of three requests of which one is marked, and a tag filter.
     */
    private fun fill(server: MockeyServer) {
        val engine = server.engine
        runBlocking {
            engine.services.create { it.copy(name = "Kundenübersicht ✓", mockUrl = "kunden", tags = Tags.of("team-a")) }.getOrThrow()
            val items = listOf(PlanItem("Customers", "found", ResponseMode.STATIC, 250))
            val plan = engine.plans.create { it.copy(name = "Night", items = items) }
            engine.plans.designateDefault(plan.getOrThrow().id).getOrThrow()
            val twist = engine.twists.create { it.copy(name = "to the other stage", pairs = listOf(TwistPair("qa1", "qa2"))) }
            engine.twists.enable(twist.getOrThrow().id).getOrThrow()
        }
        val mock = server.listeners.mock.address
        val targets = listOf("/service/customer/7", "/service/unknown", "/service/customer/8?verbose=true")
        assertEquals(listOf<Int?>(200, 404, 200), targets.map { RawHttp.get(mock, it).status })
        engine.history.flag(
            engine.history
                .entries()
                .last()
                .id,
            flagged = true,
        )
        engine.tagFilter = Tags.of("nightly")
    }

    /** The scenario "Listing services". The name of the second service shows that the bytes of the body are UTF-8. */
    @Test
    fun theAdminListenerListsTheServicesAsJsonInUtf8() {
        val server = start(settings())
        runBlocking {
            server.engine.services
                .create { it.copy(name = "Kundenübersicht ✓", mockUrl = "kunden", tags = Tags.of("team-a")) }
                .getOrThrow()
        }
        val (customers, overview) = server.engine.definitions.services
        val found = customers.scenarios.single()

        val answer = RawHttp.get(server.listeners.admin.address, "/api/v1/services")

        assertEquals("HTTP/1.1 200 OK", answer.statusLine)
        assertEquals(listOf("application/json"), answer.headerValues("Content-Type"))
        assertContains(answer.body, RawHttp.utf8("Kundenübersicht ✓"))
        assertEquals(
            listOf(
                ServiceDto(
                    id = customers.id.value,
                    name = "Customers",
                    mockUrl = "customer/{id}",
                    responseMode = ResponseModeDto.STATIC,
                    effectiveResponseMode = ResponseModeDto.STATIC,
                    scenarios =
                        listOf(
                            ScenarioDto(found.id.value, "found", StoredDefinitions.CUSTOMER, 200, emptyList(), MatchCriterionDto.None),
                        ),
                    defaultScenarioId = found.id.value,
                ),
                ServiceDto(id = overview.id.value, name = "Kundenübersicht ✓", mockUrl = "kunden", tags = listOf("team-a")),
            ),
            answer.decoded<List<ServiceDto>>(),
        )
    }

    /**
     * The scenario "Admin path on the mock listener". The answer is the one that Ktor gives to what nothing
     * serves, and not one of the admin API: no admin operation is performed, with a token and without one.
     */
    @Test
    fun theMockListenerServesNothingOfTheAdminApi() {
        val targets = listOf("/api/v1/services", "/api/v1/plans", "/api/v1/history", "/api/v1/system", "/api/v1", "/api/v1/nothing")

        for (token in listOf(null, AdminToken(TOKEN))) {
            val server = start(settings().copy(adminToken = token))
            val customers =
                server.engine.definitions.services
                    .single()
                    .id.value

            for (target in targets + "/api/v1/services/$customers") {
                for (method in listOf("GET", "HEAD", "POST", "DELETE")) {
                    val answer = RawHttp.exchange(server.listeners.mock.address, RawHttp.request(method, target, PRESENTING))

                    assertEquals("HTTP/1.1 404 Not Found", answer.statusLine, "$method $target")
                    assertEquals(listOf("Content-Length: 0", "Connection: close"), answer.headers, "$method $target")
                    assertEquals("", answer.body, "$method $target")
                }
            }
            assertEquals(200, RawHttp.get(server.listeners.admin.address, "/api/v1/services", PRESENTING).status)
        }
    }

    /**
     * The scenarios "Request without the token" and "Request with the token", for a server in writable mode,
     * where a change would show in the definitions location. The mock listener asks for no token.
     */
    @Test
    fun aServerWithAnAdminTokenTakesARequestToItsApiOnlyWithTheToken() {
        val definitions = definitionsLocation()
        val server = start(settings(definitions).copy(adminToken = AdminToken(TOKEN), readOnly = false))
        fill(server)
        val before = stateOf(server, definitions)
        val admin = server.listeners.admin.address
        val customers =
            server.engine.definitions.services
                .first()
                .id.value
        val targets =
            listOf(
                "/api/v1/services",
                "/api/v1/services/$customers",
                "/api/v1/plans",
                "/api/v1/history",
                "/api/v1/system",
                "/api/v1/openapi.json",
            )
        val inTheQueryString = RawHttp.get(admin, "/api/v1/services?token=$TOKEN")

        val without = targets.map { RawHttp.get(admin, it) } + inTheQueryString
        val withAnother = targets.map { RawHttp.get(admin, it, listOf("Authorization: Bearer ${TOKEN.reversed()}")) }
        val withTheToken = targets.map { RawHttp.get(admin, it, PRESENTING) }
        val ofMock = RawHttp.get(server.listeners.mock.address, "/status")

        val refusal =
            ProblemDto(
                type = "about:blank",
                title = "Unauthorized",
                status = 401,
                detail =
                    "The admin API takes a request only with the admin token, which a request presents as a bearer token " +
                        "in its Authorization header.",
            )
        for (answer in without + withAnother) {
            assertEquals("HTTP/1.1 401 Unauthorized", answer.statusLine)
            assertEquals(listOf("Bearer"), answer.headerValues("WWW-Authenticate"))
            assertEquals(refusal, answer.decoded<ProblemDto>())
        }
        assertEquals(List(targets.size) { 200 }, withTheToken.map { it.status })
        assertContains(withTheToken.first().bodyInUtf8, "\"name\":\"Customers\"")
        assertEquals(200, ofMock.status)
        assertEquals(before, stateOf(server, definitions))
    }

    /**
     * The scenario "Reading does not change state", for a server in writable mode, where a change would show
     * in the definitions location as well. A read that finds nothing changes nothing either.
     */
    @Test
    fun readingServicesPlansHistoryAndStatusChangesNothing() {
        val definitions = definitionsLocation()
        val server = start(settings(definitions).copy(readOnly = false))
        fill(server)
        val before = stateOf(server, definitions)
        val admin = server.listeners.admin.address
        val held = server.engine.definitions
        val customers = held.services.first().id
        val plan = held.plans.single().id
        val twist = held.twists.single().id
        val entry = before.history.first().id
        val reads =
            mapOf(
                "/api/v1/services" to 200,
                "/api/v1/services/${customers.value}" to 200,
                "/api/v1/services/${customers.value}/scenarios" to 200,
                "/api/v1/universal-error-scenario" to 404,
                "/api/v1/plans" to 200,
                "/api/v1/plans/${plan.value}" to 200,
                "/api/v1/default-plan" to 200,
                "/api/v1/twists" to 200,
                "/api/v1/twists/${twist.value}" to 200,
                "/api/v1/tag-filter" to 200,
                "/api/v1/tags" to 200,
                "/api/v1/upstream-proxy" to 200,
                "/api/v1/inspectors" to 200,
                "/api/v1/history" to 200,
                "/api/v1/history?token=customer&max=1" to 200,
                "/api/v1/history/$entry" to 200,
                "/api/v1/history/0" to 404,
                "/api/v1/stats" to 200,
                "/api/v1/stats?format=csv" to 200,
                "/api/v1/definitions/export" to 200,
                "/api/v1/diagnostics" to 200,
                "/api/v1/search?term=customer" to 200,
                "/api/v1/services/${customers.value}/schema-report" to 404,
                "/api/v1/system" to 200,
                "/api/v1/openapi.json" to 200,
                "/api/v1/services?name=customers&tag=team-a" to 200,
                "/api/v1/services/no-such-service" to 404,
                "/api/v1/nothing" to 404,
            )

        repeat(2) {
            for (method in listOf("GET", "HEAD")) {
                val statuses = reads.mapValues { (target, _) -> RawHttp.exchange(admin, RawHttp.request(method, target)).status }

                assertEquals<Map<String, Int?>>(reads, statuses, method)
            }
        }

        assertEquals(before, stateOf(server, definitions))
        assertEquals(3, before.history.size)
        assertContains(before.stored.keys, "mockey.json")
    }

    /** Returns the services that [server] holds. */
    private fun read(server: MockeyServer): List<Service> = server.engine.definitions.services

    /** Sends a request to the admin API of this server, with [body] as JSON, and returns the answer. */
    private fun MockeyServer.asked(
        method: String,
        target: String,
        body: String = "",
        headers: List<String> = emptyList(),
    ): RawHttp.Answer {
        val json = if (body.isEmpty()) emptyList() else listOf("Content-Type: application/json")
        return RawHttp.exchange(listeners.admin.address, RawHttp.request(method, "/api/v1/$target", headers + json, body))
    }

    /**
     * The scenario "Create a service with a scenario and select it", on a server with its default mode, in
     * which it keeps a change in memory. The mock endpoint answers with the scenario, and the definitions
     * location holds what it held.
     */
    @Test
    fun aClientDefinesAServiceWithAScenarioThroughTheApiAndTheMockEndpointAnswersWithIt() {
        val definitions = definitionsLocation()
        val server = start(settings(definitions))
        val stored = stateOf(server, definitions).stored

        val service = server.asked("POST", "services", """{"name":"Orders","mockUrl":"orders"}""")
        val id = service.decoded<ServiceDto>().id
        val scenario = server.asked("POST", "services/$id/scenarios", """{"name":"listed","responseBody":"[7, 8]","statusCode":203}""")
        val updated = server.asked("PATCH", "services/$id", """{"responseMode":"static"}""")
        val read = server.asked("GET", "services/$id").decoded<ServiceDto>()
        val ofMock = RawHttp.get(server.listeners.mock.address, "/service/orders")

        assertEquals(listOf<Int?>(201, 201, 200), listOf(service.status, scenario.status, updated.status))
        assertEquals(ResponseModeDto.STATIC to ResponseModeDto.STATIC, read.responseMode to read.effectiveResponseMode)
        assertEquals(scenario.decoded<ScenarioDto>().id to listOf("listed"), read.defaultScenarioId to read.scenarios.map { it.name })
        assertEquals(203 to "[7, 8]", ofMock.status to ofMock.body)
        assertEquals(stored, stateOf(server, definitions).stored)
        assertEquals(listOf("Customers", "Orders"), read(server).map { it.name })
    }

    /** In writable mode a change is in the definitions location when its request is answered, and the next server starts with it. */
    @Test
    fun aChangeThroughTheApiIsStoredInWritableModeAndTheNextServerHasIt() {
        val definitions = definitionsLocation()
        val writable = settings(definitions).copy(readOnly = false)
        val first = start(writable)
        val stored = stateOf(first, definitions).stored

        val created = first.asked("POST", "services", """{"name":"Orders","mockUrl":"orders","tags":["nightly"]}""")
        val deleted = first.asked("DELETE", "services/${read(first).first().id.value}")
        val afterTheChanges = stateOf(first, definitions).stored
        first.close()
        val second = start(writable)

        assertEquals(listOf<Int?>(201, 204), listOf(created.status, deleted.status))
        assertNotEquals(stored, afterTheChanges)
        val listed = second.asked("GET", "services").decoded<List<ServiceDto>>()
        assertEquals(listOf(created.decoded<ServiceDto>()), listed)
        assertEquals(listOf("nightly"), listed.single().tags)
    }

    /** The scenarios "Validation failure" and "Unparseable body", and the scenario "Created service is returned". */
    @Test
    fun aServiceThatIsNotValidOrNotReadableIsNotCreatedAndAValidOneIsReturned() {
        val definitions = definitionsLocation()
        val server = start(settings(definitions).copy(readOnly = false))
        val before = stateOf(server, definitions)

        val taken = server.asked("POST", "services", """{"name":"Clients","mockUrl":"customer/{id}"}""")
        val unparseable = server.asked("POST", "services", """{"name":"Clients","mockUrl":""")
        val afterBoth = stateOf(server, definitions)
        val created = server.asked("POST", "services", """{"name":"Clients","mockUrl":"client/{id}"}""")

        val message = "The service 'Customers' already uses the mock URL pattern 'customer/{id}'."
        val invalid = listOf(FieldErrorDto("mockUrl", message))
        assertEquals("HTTP/1.1 422 Unprocessable Entity", taken.statusLine)
        assertEquals(listOf("application/problem+json"), taken.headerValues("Content-Type"))
        assertEquals(
            ProblemDto("about:blank", "Unprocessable Entity", 422, "The request is not valid. mockUrl: $message", invalid),
            taken.decoded<ProblemDto>(),
        )
        assertEquals("HTTP/1.1 400 Bad Request", unparseable.statusLine)
        val noObject = ProblemDto("about:blank", "Bad Request", 400, "The body of the request is no JSON object.")
        assertEquals(noObject, unparseable.decoded<ProblemDto>())
        assertEquals(before, afterBoth)
        assertEquals("HTTP/1.1 201 Created", created.statusLine)
        val service = created.decoded<ServiceDto>()
        assertEquals(ServiceDto(service.id, "Clients", "client/{id}"), service)
        assertEquals(service, read(server).last().toAdminDto())
    }

    /** The scenario "Request without the token", for a request that would change something: nothing is changed for it. */
    @Test
    fun aServerWithAnAdminTokenChangesSomethingOnlyForARequestWithTheToken() {
        val definitions = definitionsLocation()
        val server = start(settings(definitions).copy(adminToken = AdminToken(TOKEN), readOnly = false))
        val customers = read(server).single()
        val changes =
            listOf(
                Triple("POST", "services", """{"name":"Orders","mockUrl":"orders"}"""),
                Triple("PATCH", "services/${customers.id.value}", """{"delayMillis":250}"""),
                Triple("DELETE", "services/${customers.id.value}", ""),
                Triple("POST", "services/${customers.id.value}/duplicate", ""),
                Triple("POST", "plans", """{"serviceIds":["${customers.id.value}"]}"""),
                Triple("POST", "twists", """{"name":"to-qa2"}"""),
                Triple("PUT", "tag-filter", """{"tags":["nightly"]}"""),
                Triple("DELETE", "tag-filter", ""),
                Triple("PATCH", "upstream-proxy", """{"url":"http://proxy.example:3128","enabled":true}"""),
                Triple("DELETE", "system/upstream-session", ""),
                Triple("PATCH", "history/1", """{"flagged":true}"""),
                Triple("POST", "history/1/replay", ""),
                Triple("POST", "history/1/scenario", ""),
                Triple("DELETE", "history/1", ""),
                Triple("DELETE", "history", ""),
                Triple("PATCH", "system", """{"readOnly":true}"""),
                Triple("POST", "system/reload", ""),
                Triple("DELETE", "system/last-used-times", ""),
                Triple("POST", "definitions/import", """{"formatVersion":1,"plans":[{"id":"p1","name":"Night"}]}"""),
                Triple("POST", "system/reset", ""),
            )

        val ofMock = RawHttp.get(server.listeners.mock.address, "/service/customer/7")
        val before = stateOf(server, definitions)
        val without = changes.map { (method, target, body) -> server.asked(method, target, body) }
        val afterThem = stateOf(server, definitions)
        val with = server.asked("PATCH", "services/${customers.id.value}", """{"delayMillis":250}""", PRESENTING)

        assertEquals(200 to 1, ofMock.status to before.history.size)
        assertEquals(List(changes.size) { 401 }, without.map { it.status })
        assertEquals(before, afterThem)
        assertEquals(200 to 250L, with.status to read(server).single().delayMillis)
    }

    /**
     * The scenario "Apply a plan", on a server: a plan that a client saves and applies through the API puts
     * a service back into the state in which it was saved, and the mock endpoint answers as it did then.
     */
    @Test
    fun aPlanThatIsAppliedThroughTheApiPutsAServiceBackIntoTheStateInWhichItWasSaved() {
        val server = start(settings())
        val mock = server.listeners.mock.address
        val customers = read(server).single().id.value

        val saved = server.asked("POST", "plans", """{"name":"Found","serviceIds":["$customers"]}""")
        val lost = server.asked("POST", "services/$customers/scenarios", """{"name":"lost","responseBody":"lost","statusCode":410}""")
        val selecting = """{"defaultScenarioId":"${lost.decoded<ScenarioDto>().id}","delayMillis":5}"""
        val selected = server.asked("PATCH", "services/$customers", selecting)
        val ofLost = RawHttp.get(mock, "/service/customer/7")
        val applied = server.asked("POST", "plans/${saved.decoded<PlanDto>().id}/apply")
        val ofThePlan = RawHttp.get(mock, "/service/customer/7")

        assertEquals(listOf<Int?>(201, 201, 200), listOf(saved.status, lost.status, selected.status))
        assertEquals(410 to "lost", ofLost.status to ofLost.body)
        assertEquals("HTTP/1.1 200 OK", applied.statusLine)
        assertEquals(PlanApplyReportDto(saved.decoded<PlanDto>(), emptyList(), emptyList()), applied.decoded<PlanApplyReportDto>())
        assertEquals(listOf(PlanItemDto("Customers", "found", ResponseModeDto.STATIC, 0)), saved.decoded<PlanDto>().items)
        assertEquals(200 to StoredDefinitions.CUSTOMER, ofThePlan.status to ofThePlan.body)
        assertEquals(0L, read(server).single().delayMillis)
    }

    /**
     * The scenario "Enable a twist configuration": the status states the configuration, and the mock
     * endpoint twists the target of the next request with it. A configuration twists nothing before it is
     * enabled, and nothing after it is disabled.
     */
    @Test
    fun aTwistConfigurationThatIsEnabledThroughTheApiIsStatedByTheStatusAndTwistsTheNextMockRequest() {
        val server = start(settings())
        val mock = server.listeners.mock.address
        val pairs = """[{"origination":"kunde","destination":"customer"}]"""

        val created = server.asked("POST", "twists", """{"name":"German paths","pairs":$pairs}""")
        val twist = created.decoded<TwistConfigurationDto>()
        val beforeItIsEnabled = RawHttp.get(mock, "/service/kunde/7")
        val enabled = server.asked("POST", "twists/${twist.id}/enable")
        val status = server.asked("GET", "system").decoded<SystemStatusDto>()
        val twisted = RawHttp.get(mock, "/service/kunde/7")
        val disabled = server.asked("POST", "twists/${twist.id}/disable")
        val statusAfterwards = server.asked("GET", "system").decoded<SystemStatusDto>()
        val afterItIsDisabled = RawHttp.get(mock, "/service/kunde/7")

        assertEquals(listOf<Int?>(201, 200, 200), listOf(created.status, enabled.status, disabled.status))
        assertEquals(TwistConfigurationDto(twist.id, "German paths", listOf(TwistPairDto("kunde", "customer"))), status.enabledTwist)
        assertEquals(200 to StoredDefinitions.CUSTOMER, twisted.status to twisted.body)
        assertEquals(listOf<Int?>(404, 404), listOf(beforeItIsEnabled.status, afterItIsDisabled.status))
        assertNull(statusAfterwards.enabledTwist)
    }

    /**
     * The tag filter that a client sets through the API counts for the next mock request: a service without
     * one of its tags gets no request. The status states the filter. A tag that is removed from all
     * definitions is a tag of the service no longer.
     */
    @Test
    fun theTagFilterOfTheApiDecidesWhetherTheNextMockRequestGoesToAService() {
        val server = start(settings())
        val mock = server.listeners.mock.address
        val customers = read(server).single().id.value

        val set = server.asked("PUT", "tag-filter", """{"tags":["nightly"]}""")
        val status = server.asked("GET", "system").decoded<SystemStatusDto>()
        val withoutTheTag = RawHttp.get(mock, "/service/customer/7")
        val tagged = server.asked("PATCH", "services/$customers", """{"tags":["nightly"]}""")
        val withTheTag = RawHttp.get(mock, "/service/customer/7")
        val removed = server.asked("DELETE", "tags/nightly")
        val afterTheRemoval = RawHttp.get(mock, "/service/customer/7")
        val cleared = server.asked("DELETE", "tag-filter")
        val withoutAFilter = RawHttp.get(mock, "/service/customer/7")

        assertEquals(listOf<Int?>(200, 200, 204, 204), listOf(set.status, tagged.status, removed.status, cleared.status))
        assertEquals(listOf("nightly"), status.tagFilter)
        val ofMock = listOf(withoutTheTag.status, withTheTag.status, afterTheRemoval.status, withoutAFilter.status)
        assertEquals(listOf<Int?>(404, 200, 404, 200), ofMock)
        assertEquals(emptyList(), server.asked("GET", "system").decoded<SystemStatusDto>().tagFilter)
    }

    /**
     * The outbound proxy that a client sets through the API is the one that the next forwarded request goes
     * through, with its credentials, and a proxy that is disabled is gone through no longer. A request that
     * disables the proxy need not state the password, and the proxy has it when it is enabled again.
     */
    @Test
    fun theOutboundProxyOfTheApiIsTheOneThatTheNextForwardedRequestGoesThrough() {
        StubUpstream().use { upstream ->
            StubProxy().use { proxy ->
                val server = start(settings())
                val mock = server.listeners.mock.address
                val real = """{"name":"Real","mockUrl":"real","realUrls":["${upstream.origin}/real"]}"""
                val settings = """{"url":"${proxy.url}","enabled":true,"username":"mockey","password":"$PROXY_PASSWORD"}"""

                val created = server.asked("POST", "services", real)
                val direct = RawHttp.get(mock, "/service/real")
                val set = server.asked("PATCH", "upstream-proxy", settings)
                val through = RawHttp.get(mock, "/service/real")
                val disabled = server.asked("PATCH", "upstream-proxy", """{"enabled":false}""")
                val directAgain = RawHttp.get(mock, "/service/real")
                val enabled = server.asked("PATCH", "upstream-proxy", """{"enabled":true}""")
                val throughAgain = RawHttp.get(mock, "/service/real")

                assertEquals(listOf<Int?>(201, 200, 200, 200), listOf(created.status, set.status, disabled.status, enabled.status))
                val ofTheService = "answered by the real service"
                val bodies = listOf(direct.body, through.body, directAgain.body, throughAgain.body)
                assertEquals(listOf(ofTheService, StubProxy.ANSWER, ofTheService, StubProxy.ANSWER), bodies)
                val credentials = Base64.getEncoder().encodeToString("mockey:$PROXY_PASSWORD".toByteArray())
                assertEquals(listOf<String?>("Basic $credentials", "Basic $credentials"), proxy.authorizations)
                assertEquals(List(2) { "${upstream.origin}/real" }, proxy.targets.toList())
                assertEquals(2, upstream.requests.size)
                for (answer in listOf(set, disabled, enabled)) {
                    assertFalse(PROXY_PASSWORD in answer.text || credentials in answer.text, "$answer")
                }
            }
        }
    }

    /**
     * The scenario "Emptying the store" of the proxying, through the API: after it, the next forwarded
     * request carries no cookie that a real service has set.
     */
    @Test
    fun theUpstreamSessionThatIsEmptiedThroughTheApiSendsNoCookieWithTheNextForwardedRequest() {
        StubUpstream(headers = listOf("Content-Type: text/plain", "Set-Cookie: sid=abc; Path=/")).use { upstream ->
            val server = start(settings())
            val mock = server.listeners.mock.address
            val created = server.asked("POST", "services", """{"name":"Real","mockUrl":"real","realUrls":["${upstream.origin}/real"]}""")

            val answers = List(2) { RawHttp.get(mock, "/service/real") }
            val emptied = server.asked("DELETE", "system/upstream-session")
            val afterwards = RawHttp.get(mock, "/service/real")

            val statuses = (listOf(created) + answers + emptied + afterwards).map { it.status }
            assertEquals(listOf<Int?>(201, 200, 200, 204, 200), statuses)
            assertEquals(listOf(emptyList(), listOf("sid=abc"), emptyList()), upstream.requests.map { it.headerValues("Cookie") })
        }
    }

    /**
     * The scenario "Replay under changed definitions" of the history, on a server: the replay of a request
     * that the mock endpoint handled is handled as the mock endpoint handles a request now, and the history
     * has its entry. The response of the entry that was replayed becomes a scenario of its service.
     */
    @Test
    fun aRequestOfTheMockEndpointIsReplayedAndSavedAsAScenarioThroughTheApi() {
        val server = start(settings())
        val customers = read(server).single().id.value
        val first = RawHttp.get(server.listeners.mock.address, "/service/customer/7?verbose=true")
        val entry = server.asked("GET", "history").decoded<List<HistoryEntryDto>>().single()
        val lost = server.asked("POST", "services/$customers/scenarios", """{"name":"lost","responseBody":"lost","statusCode":410}""")
        val selected = server.asked("PATCH", "services/$customers", """{"defaultScenarioId":"${lost.decoded<ScenarioDto>().id}"}""")

        val replayed = server.asked("POST", "history/${entry.id}/replay")
        val saved = server.asked("POST", "history/${entry.id}/scenario", """{"name":"recorded"}""")
        val listed = server.asked("GET", "history?token=verbose").decoded<List<HistoryEntryDto>>()

        assertEquals(listOf<Int?>(200, 201, 200), listOf(first.status, lost.status, selected.status))
        assertEquals("HTTP/1.1 201 Created", replayed.statusLine)
        val ofTheReplay = replayed.decoded<HistoryEntryDto>()
        assertEquals(listOf("/api/v1/history/${ofTheReplay.id}"), replayed.headerValues("Location"))
        assertEquals(entry.request, ofTheReplay.request)
        val answered = listOf(entry, ofTheReplay).flatMap { listOf(it.scenario?.name, it.response.status) }
        assertEquals(listOf("found", 200, "lost", 410), answered)
        assertEquals(listOf(ofTheReplay, entry), listed)
        assertEquals(201 to StoredDefinitions.CUSTOMER, saved.status to saved.decoded<ScenarioDto>().responseBody)
        assertEquals(listOf("found", "lost", "recorded"), read(server).single().scenarios.map { it.name })
    }

    /**
     * The scenario "Import a legacy export document", on a server: the answer is the report, what was added
     * carries the tag, and the mock endpoint answers with the imported service as its default plan sets it.
     * An export of the server holds what was imported.
     */
    @Test
    fun anExportDocumentOfMockey2IsImportedThroughTheApiAndTheMockEndpointAnswersWithIt() {
        val server = start(settings())
        val mock = server.listeners.mock.address
        val document = LegacySources.export(withDefaultPlan = true)
        val asXml = RawHttp.request("POST", "/api/v1/definitions/import?tag=migrated", listOf("Content-Type: application/xml"), document)

        val before = RawHttp.get(mock, "/service/legacy/orders")
        val imported = RawHttp.exchange(server.listeners.admin.address, asXml)
        val ofMock = RawHttp.get(mock, "/service/legacy/orders")
        val exported = server.asked("GET", "definitions/export")

        assertEquals(404, before.status)
        assertEquals("HTTP/1.1 200 OK", imported.statusLine, "$imported")
        val report = imported.decoded<ImportReportDto>()
        val added = listOf("service" to "Legacy Orders", "scenario" to "silver", "scenario" to "gold", "plan" to "Gold path")
        assertEquals(added + ("setting" to "default plan"), report.additions.map { it.kind to it.name })
        assertEquals("Gold path", report.appliedDefaultPlan?.plan?.name)
        val legacy = read(server).last()
        assertEquals("Legacy Orders" to listOf("migrated"), legacy.name to legacy.tags.toList())
        assertEquals(200 to LegacySources.GOLD_ORDERS, ofMock.status to ofMock.body)
        assertEquals(200 to true, exported.status to ("Legacy Orders" in exported.body && "Customers" in exported.body))
    }

    /**
     * The scenario "Leave read-only mode", on a server: what was changed in read-only mode is in the
     * definitions location when the request is answered, the status says that the mode is off, and the
     * next server starts with it.
     */
    @Test
    fun aServerThatLeavesReadOnlyModeThroughTheApiStoresWhatWasChangedBefore() {
        val definitions = definitionsLocation()
        val first = start(settings(definitions))
        val stored = stateOf(first, definitions).stored

        val created = first.asked("POST", "services", """{"name":"Orders","mockUrl":"orders"}""")
        val afterTheChange = stateOf(first, definitions).stored
        val switched = first.asked("PATCH", "system", """{"readOnly":false}""")
        val status = first.asked("GET", "system").decoded<SystemStatusDto>()
        first.close()
        val second = start(settings(definitions))

        assertEquals(201 to stored, created.status to afterTheChange)
        assertEquals("HTTP/1.1 200 OK", switched.statusLine, "$switched")
        assertEquals(false to false, switched.decoded<SystemStatusDto>().readOnly to status.readOnly)
        assertEquals(listOf("Customers", "Orders"), read(second).map { it.name })
        assertEquals(true, second.asked("GET", "system").decoded<SystemStatusDto>().readOnly)
    }

    /**
     * A reload puts the definitions of the definitions location in the place of those in memory, and a reset
     * leaves none. The mock endpoint answers by the definitions as they are after each.
     */
    @Test
    fun aServerReloadsAndResetsItsDefinitionsThroughTheApi() {
        val definitions = definitionsLocation()
        val server = start(settings(definitions))
        val mock = server.listeners.mock.address
        TestDefinitions.store(definitions) { orders() }

        val beforeTheReload = RawHttp.get(mock, "/service/orders")
        val reloaded = server.asked("POST", "system/reload")
        val afterTheReload = RawHttp.get(mock, "/service/orders")
        val reset = server.asked("POST", "system/reset")
        val afterTheReset = listOf("/service/orders", "/service/customer/7").map { RawHttp.get(mock, it).status }

        assertEquals(listOf<Int?>(204, 204), listOf(reloaded.status, reset.status))
        assertEquals(404, beforeTheReload.status)
        assertEquals(200 to TestDefinitions.SILVER_ORDERS, afterTheReload.status to afterTheReload.body)
        assertEquals(listOf<Int?>(404, 404), afterTheReset)
        assertEquals(emptyList(), read(server))
        assertEquals(2, server.asked("GET", "history").decoded<List<HistoryEntryDto>>().size)
        assertContains(stateOf(server, definitions).stored.keys, "mockey.json")
    }

    /** The scenario "Unknown resource". */
    @Test
    fun aServiceThatDoesNotExistGetsTheStatus404AndAProblemDocument() {
        val server = start(settings())
        val admin = server.listeners.admin.address
        val customers =
            server.engine.definitions.services
                .single()

        val ofCustomers = RawHttp.get(admin, "/api/v1/services/${customers.id.value}")
        val ofNoService = RawHttp.get(admin, "/api/v1/services/no-such-service")
        val ofTheName = RawHttp.get(admin, "/api/v1/services/Customers")

        assertEquals(200 to "Customers", ofCustomers.status to ofCustomers.decoded<ServiceDto>().name)
        assertEquals(customers.toAdminDto(), ofCustomers.decoded<ServiceDto>())
        for ((answer, id) in listOf(ofNoService to "no-such-service", ofTheName to "Customers")) {
            assertEquals("HTTP/1.1 404 Not Found", answer.statusLine)
            assertEquals(listOf("application/problem+json"), answer.headerValues("Content-Type"))
            assertEquals(ProblemDto("about:blank", "Not Found", 404, "No service has the identifier $id."), answer.decoded<ProblemDto>())
        }
    }

    /**
     * The scenario "Status is read", and the scenario "Startup tag filter" of the server: the status of a
     * server that was started with settings of its own, on ports that the system picked.
     */
    @Test
    fun theStatusStatesTheServerAsItsSettingsMadeIt() {
        val definitions = definitionsLocation()
        val server = start(settings(definitions).copy(tagFilter = Tags.of("regression"), readOnly = false))

        val status = RawHttp.get(server.listeners.admin.address, "/api/v1/system").decoded<SystemStatusDto>()

        assertEquals(
            SystemStatusDto(
                version = System.getProperty("mockey.version"),
                startTime = server.since.truncatedTo(ChronoUnit.MILLIS).toString(),
                definitionsLocation = definitions.toAbsolutePath().normalize().toString(),
                readOnly = false,
                tagFilter = listOf("regression"),
                enabledTwist = null,
                mockBaseUrl = "http://127.0.0.1:${server.listeners.mock.address.port}",
                legacyShimEnabled = false,
            ),
            status,
        )
        val mock = URI(status.mockBaseUrl)
        assertEquals(200, RawHttp.get(InetSocketAddress(mock.host, mock.port), "/status").status)
    }

    @Test
    fun thePlansAndTheHistoryAreStatedAsTheEngineHoldsThem() {
        val server = start(settings())
        fill(server)
        val admin = server.listeners.admin.address
        val plan =
            server.engine.definitions.plans
                .single()

        val plans = RawHttp.get(admin, "/api/v1/plans").decoded<List<PlanDto>>()
        val history = RawHttp.get(admin, "/api/v1/history").decoded<List<HistoryEntryDto>>()

        assertEquals(
            listOf(PlanDto(plan.id.value, "Night", items = listOf(PlanItemDto("Customers", "found", ResponseModeDto.STATIC, 250)))),
            plans,
        )
        assertEquals(
            listOf(
                listOf("customer/8", "verbose=true", "scenarioServed", "Customers", "found", 200, "eyJpZCI6MX0=", false),
                listOf("unknown", null, "noServiceMatched", null, null, 404, null, false),
                listOf("customer/7", null, "scenarioServed", "Customers", "found", 200, "eyJpZCI6MX0=", true),
            ),
            history.map {
                val body = if (it.service == null) null else it.response.body.base64
                listOf(it.target, it.request.query, it.outcome, it.service?.name, it.scenario?.name, it.response.status, body, it.flagged)
            },
        )
        assertEquals(
            server.engine.history
                .entries()
                .map { it.id.value },
            history.map { it.id },
        )
        assertEquals("http://mockey.test/service/customer/7" to "GET", history.last().request.url to history.last().request.method)
        assertEquals(
            Instant.parse(history.last().time),
            server.engine.history
                .entries()
                .last()
                .time,
        )
    }

    private companion object {
        const val PROXY_PASSWORD = "pw-1c4e7a9d2f60"
        const val TOKEN = "tk-58e0b3a9c1d7"
        val PRESENTING = listOf("Authorization: Bearer $TOKEN")
    }
}
