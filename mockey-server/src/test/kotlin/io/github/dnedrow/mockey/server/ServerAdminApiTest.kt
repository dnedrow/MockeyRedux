package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.HistoryEntryDto
import io.github.dnedrow.mockey.api.MatchCriterionDto
import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.PlanItemDto
import io.github.dnedrow.mockey.api.ProblemDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.github.dnedrow.mockey.core.history.HistoryEntry
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.PlanItem
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistPair
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.isRegularFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * The admin API of a server in the process of the test: the scenarios of the admin API that a server can
 * show whose API changes nothing yet. How the API takes a request, and what it answers when it does not do
 * what the request asks for, is the matter of [AdminApiTest].
 *
 * The scenarios "Validation failure" and "Unparseable body" need an operation that takes a body, and the
 * server has none yet. Until it does, [AdminApiTest] shows them with operations of its own.
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
                "/api/v1/services?token=$TOKEN",
            )

        val without = targets.map { RawHttp.get(admin, it) }
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
        val customers =
            server.engine.definitions.services
                .first()
                .id.value
        val reads =
            mapOf(
                "/api/v1/services" to 200,
                "/api/v1/services/$customers" to 200,
                "/api/v1/plans" to 200,
                "/api/v1/history" to 200,
                "/api/v1/system" to 200,
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
        const val TOKEN = "tk-58e0b3a9c1d7"
        val PRESENTING = listOf("Authorization: Bearer $TOKEN")
    }
}
