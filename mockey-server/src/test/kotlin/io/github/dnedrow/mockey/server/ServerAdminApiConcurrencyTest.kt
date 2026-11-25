package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.ProblemDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.github.dnedrow.mockey.api.TagFilterDto
import io.github.dnedrow.mockey.api.TwistConfigurationDto
import io.github.dnedrow.mockey.core.model.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 12.8: a change through the admin API takes effect at once, and changes at the same time do not
 * corrupt the definitions. The tests run against a whole server over real HTTP, with the mock endpoint and
 * the admin API on their listeners, and each repeats what it shows many times, because a fault of this kind
 * shows only now and then.
 *
 * Every request of a test goes over a connection of its own, so a request that follows an answer is a later
 * request for the server as well.
 */
class ServerAdminApiConcurrencyTest {
    private val servers = mutableListOf<MockeyServer>()
    private val directories = mutableListOf<Path>()
    private val clients = Executors.newFixedThreadPool(CLIENTS)

    @AfterTest
    fun stopEverything() {
        clients.shutdownNow()
        servers.forEach(MockeyServer::close)
        directories.forEach { it.toFile().deleteRecursively() }
    }

    /** Starts a server on a definitions location of its own, in read-only mode unless it is [writable], and returns it. */
    private fun server(writable: Boolean = false): MockeyServer {
        val definitions = Files.createTempDirectory("mockey-admin-api-concurrency-test").also { directories.add(it) }
        val settings = ServerSettings(mockHost = "127.0.0.1", mockPort = 0, adminPort = 0, definitions = definitions)
        return MockeyServer.start(settings.copy(readOnly = !writable, logLevel = LogLevel.WARN)).also { servers += it }
    }

    /** Sends a request to the admin API of this server, with [body] as JSON, and returns the answer. */
    private fun MockeyServer.asked(
        method: String,
        target: String,
        body: String = "",
    ): RawHttp.Answer {
        val json = if (body.isEmpty()) emptyList() else listOf("Content-Type: application/json")
        return RawHttp.exchange(listeners.admin.address, RawHttp.request(method, "/api/v1/$target", json, body))
    }

    /** Sends a request for [target] to the mock endpoint of this server, and returns the answer. */
    private fun MockeyServer.mocked(target: String): RawHttp.Answer = RawHttp.get(listeners.mock.address, "/service/$target")

    private inline fun <reified T> RawHttp.Answer.decoded(): T = AdminApiJson.instance.decodeFromString<T>(bodyInUtf8)

    /** Returns the answer as what was created, which it has to be. */
    private inline fun <reified T> RawHttp.Answer.created(): T {
        assertEquals("HTTP/1.1 201 Created", statusLine, "$this")
        return decoded<T>()
    }

    private val MockeyServer.services: List<Service>
        get() = engine.definitions.services

    /** Creates a service in static mode with the mock URL `orders` on this server, and returns it. */
    private fun MockeyServer.orders(): ServiceDto =
        asked("POST", "services", """{"name":"Orders","mockUrl":"orders","responseMode":"static"}""").created()

    /** Creates a scenario with these [attributes] for [service] on this server, and returns it. */
    private fun MockeyServer.scenario(
        service: ServiceDto,
        attributes: String,
    ): ScenarioDto = asked("POST", "services/${service.id}/scenarios", attributes).created()

    /** The status and the body that this server gives a request for the mock URL `orders`. */
    private fun MockeyServer.answerForOrders(): Pair<Int?, String> = mocked("orders").let { it.status to it.body }

    /**
     * Lets [count] clients do [action] at the same time, each with its number, and returns what they got in
     * the order of their numbers. The clients wait for one another, so that all of them begin together.
     */
    private fun <T> atTheSameTime(
        count: Int,
        action: (Int) -> T,
    ): List<T> {
        val together = CyclicBarrier(count)
        val started =
            List(count) { client ->
                clients.submit(
                    Callable {
                        together.await(WAIT_SECONDS, TimeUnit.SECONDS)
                        action(client)
                    },
                )
            }
        return started.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
    }

    /**
     * Runs [changes] while other clients send requests for the mock URL `orders` to [server], each of them
     * one request after another with a pause in between. Returns what [changes] returns, and the answers
     * that the other clients got.
     */
    private fun <T> amongOtherClients(
        server: MockeyServer,
        changes: () -> T,
    ): Pair<T, List<Pair<Int?, String>>> {
        val changing = AtomicBoolean(true)
        val others =
            List(OTHER_CLIENTS) {
                clients.submit(
                    Callable {
                        buildList {
                            while (changing.get() && size < MOST_REQUESTS) {
                                add(server.answerForOrders())
                                Thread.sleep(PAUSE_MILLIS)
                            }
                        }
                    },
                )
            }
        val changed =
            try {
                changes()
            } finally {
                changing.set(false)
            }
        return changed to others.flatMap { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
    }

    // Immediate effect

    /**
     * The scenario "Scenario switch is visible to the next mock request", many times over and in both
     * directions, for a server in each mode: in writable mode a change is stored before its request is
     * answered. The next read of the admin API states the change as well. Meanwhile other clients send
     * requests to the same service, and each of their requests gets the answer of one of the two scenarios,
     * whole.
     */
    @Test
    fun aChangeOfTheDefaultScenarioIsVisibleToTheNextMockRequestAndToTheNextRead() {
        for (writable in listOf(false, true)) {
            val server = server(writable)
            val service = server.orders()
            val ok = server.scenario(service, """{"name":"ok","responseBody":"all is well"}""")
            val error = server.scenario(service, """{"name":"error","responseBody":"no order","statusCode":500}""")
            val answers = mapOf(ok to (200 to "all is well"), error to (500 to "no order"))
            assertEquals(answers.getValue(ok), server.answerForOrders(), "The default scenario is 'ok'. writable: $writable")

            val (unexpected, seenByOthers) =
                amongOtherClients(server) {
                    (1..SWITCHES).mapNotNull { round ->
                        val wanted = if (round % 2 == 1) error else ok
                        val switched = server.asked("PATCH", "services/${service.id}", """{"defaultScenarioId":"${wanted.id}"}""")
                        val ofMock = server.answerForOrders()
                        val read = server.asked("GET", "services/${service.id}").decoded<ServiceDto>()
                        val seen = Triple(switched.status, ofMock, read.defaultScenarioId)
                        val expected = Triple(200, answers.getValue(wanted), wanted.id)
                        if (seen == expected) null else "In round $round the client saw $seen, and not $expected."
                    }
                }

            assertEquals(emptyList(), unexpected, "writable: $writable")
            assertEquals(answers.values.toSet(), seenByOthers.toSet(), "What the other clients got. writable: $writable")
        }
    }

    /**
     * The requirement "Changes take effect immediately", for other changes than the one of the scenario: a
     * service that is created, a scenario that is created, a new response body, and a deletion, each many
     * times over. The mock request that follows the answer to a change gets what the change makes of the
     * service: a service in static mode without a scenario has nothing to answer with and no real service
     * to ask, which is the status 502.
     */
    @Test
    fun aChangeOfAScenarioAndOfAServiceIsVisibleToTheNextMockRequest() {
        val server = server()

        val unexpected =
            (1..CHANGES).mapNotNull { round ->
                val service = server.orders()
                val withoutAScenario = server.mocked("orders").status
                val scenario = server.scenario(service, """{"name":"listed","responseBody":"first"}""")
                val first = server.mocked("orders").body
                val ofScenario = "services/${service.id}/scenarios/${scenario.id}"
                val updated = server.asked("PATCH", ofScenario, """{"responseBody":"round $round"}""").status
                val second = server.mocked("orders").body
                val deleted = server.asked("DELETE", "services/${service.id}").status
                val afterTheDeletion = server.mocked("orders").status
                val seen = listOf(withoutAScenario, first, updated, second, deleted, afterTheDeletion)
                val expected = listOf(502, "first", 200, "round $round", 204, 404)
                if (seen == expected) null else "In round $round the client saw $seen, and not $expected."
            }

        assertEquals(emptyList(), unexpected)
        assertEquals(emptyList(), server.services)
    }

    /**
     * The requirement "Changes take effect immediately", for settings: the tag filter, which is a setting of
     * the running server and no part of the definitions, a plan that is applied, and a twist configuration
     * that is enabled and disabled, each many times over. The mock request that follows the answer to a
     * change is routed and answered as the change says, and the next read of the admin API states the change.
     */
    @Test
    fun aChangeOfASettingIsVisibleToTheNextMockRequestAndToTheNextRead() {
        val server = server()
        val service = server.orders()
        val ok = server.scenario(service, """{"name":"ok","responseBody":"all is well"}""")
        val error = server.scenario(service, """{"name":"error","responseBody":"no order","statusCode":500}""")
        val ofService = "services/${service.id}"
        val allIsWell = server.asked("POST", "plans", """{"serviceIds":["${service.id}"],"name":"all is well"}""").created<PlanDto>()
        assertEquals(200, server.asked("PATCH", ofService, """{"defaultScenarioId":"${error.id}"}""").status)
        val nothingWorks = server.asked("POST", "plans", """{"serviceIds":["${service.id}"],"name":"nothing works"}""").created<PlanDto>()
        val pairs = """[{"origination":"bestellungen","destination":"orders"}]"""
        val twist = server.asked("POST", "twists", """{"name":"German paths","pairs":$pairs}""").created<TwistConfigurationDto>()

        val unexpected =
            (1..CHANGES).mapNotNull { round ->
                val filtered = server.asked("PUT", "tag-filter", """{"tags":["billing"]}""").status
                val withTheFilter = server.mocked("orders").status to server.asked("GET", "tag-filter").decoded<TagFilterDto>().tags
                val unfiltered = server.asked("DELETE", "tag-filter").status
                val applied = server.asked("POST", "plans/${allIsWell.id}/apply").status
                val withThePlan = server.answerForOrders() to server.asked("GET", ofService).decoded<ServiceDto>().defaultScenarioId
                val appliedTheOther = server.asked("POST", "plans/${nothingWorks.id}/apply").status
                val withTheOtherPlan = server.answerForOrders()
                val enabled = server.asked("POST", "twists/${twist.id}/enable").status
                val twisted = server.mocked("bestellungen").let { it.status to it.body }
                val status = server.asked("GET", "system").decoded<SystemStatusDto>()
                val disabled = server.asked("POST", "twists/${twist.id}/disable").status
                val untwisted = server.mocked("bestellungen").status
                val changed = listOf(filtered, unfiltered, applied, appliedTheOther, enabled, disabled)
                val seen = listOf(withTheFilter, withThePlan, withTheOtherPlan, twisted to status.enabledTwist?.id, untwisted)
                val expected =
                    listOf(
                        404 to listOf("billing"),
                        (200 to "all is well") to ok.id,
                        500 to "no order",
                        (500 to "no order") to twist.id,
                        404,
                    )
                val asStated = changed == listOf(200, 204, 200, 200, 200, 200) && seen == expected
                if (asStated) null else "In round $round the client got $changed and saw $seen, and not $expected."
            }

        assertEquals(emptyList(), unexpected)
    }

    // Changes at the same time

    /**
     * The scenario "Two services race for the same mock URL", many times over, with two clients and with
     * more: exactly one creation succeeds, every other one fails with the status 422 and names the service
     * that has the mock URL, and the server holds one service with it.
     */
    @Test
    fun clientsThatCreateServicesWithOneMockUrlAtTheSameTimeGetExactlyOneSuccess() {
        val server = server()

        val unexpected =
            (1..RACES).mapNotNull { round ->
                val racers = if (round % 2 == 1) 2 else CLIENTS
                val mockUrl = "race/$round"
                val answers =
                    atTheSameTime(racers) { server.asked("POST", "services", """{"name":"Racer $it of $round","mockUrl":"$mockUrl"}""") }
                val winners = answers.filter { it.status == 201 }.map { it.decoded<ServiceDto>() }
                val losers = answers.filter { it.status != 201 }.map { it.status to it.decoded<ProblemDto>().errors }
                val taken = winners.map { "The service '${it.name}' already uses the mock URL pattern '$mockUrl'." }
                val refusal = 422 to taken.map { FieldErrorDto("mockUrl", it) }
                val held = server.services.filter { it.mockUrl == mockUrl }.map { it.id.value }
                val asOneAfterAnother = winners.size == 1 && losers == List(racers - 1) { refusal } && held == winners.map { it.id }
                val got = answers.map { it.status to it.body }
                if (asOneAfterAnother) null else "In round $round the clients got $got, and the server holds the services $held."
            }

        assertEquals(emptyList(), unexpected)
        assertEquals(RACES, server.services.size)
    }

    /**
     * The requirement "Concurrent changes do not corrupt definitions", for changes that do not conflict: of
     * updates of one service at the same time, each of which states another attribute, none undoes another,
     * and of scenarios that are created at the same time, none is lost. The result is what the requests give
     * one after another, in any order.
     */
    @Test
    fun updatesAndCreationsAtTheSameTimeAllTakeEffect() {
        val server = server()
        val service = server.asked("POST", "services", """{"name":"Orders","mockUrl":"orders"}""").created<ServiceDto>()
        val modes = ResponseModeDto.entries

        val unexpected =
            (1..RACES).mapNotNull { round ->
                val mode = modes[round % modes.size]
                val updates =
                    listOf(
                        """{"delayMillis":$round}""",
                        """{"description":"round $round"}""",
                        """{"tags":["round-$round"]}""",
                        """{"responseMode":"${mode.name.lowercase()}"}""",
                    )
                val answers = atTheSameTime(updates.size) { server.asked("PATCH", "services/${service.id}", updates[it]) }
                val held = server.services.single().toAdminDto()
                val seen = listOf(answers.map { it.status }, held.delayMillis, held.description, held.tags, held.responseMode)
                val expected = listOf(List(updates.size) { 200 }, round.toLong(), "round $round", listOf("round-$round"), mode)
                if (seen == expected) null else "In round $round the server holds $seen, and not $expected."
            }
        val created = atTheSameTime(CLIENTS) { server.asked("POST", "services/${service.id}/scenarios", """{"name":"scenario $it"}""") }

        assertEquals(emptyList(), unexpected)
        val scenarios = created.map { it.created<ScenarioDto>() }
        val held = server.services.single().scenarios
        assertEquals(scenarios.map { it.id }.toSet(), held.map { it.id.value }.toSet())
        assertEquals(List(CLIENTS) { "scenario $it" }.toSet() to CLIENTS, held.map { it.name }.toSet() to held.size)
    }

    /**
     * The rule that a designated scenario is a scenario of its service, which requests at the same time do
     * not get around: while one client deletes a scenario, others make it the default scenario and the error
     * scenario of its service, and the universal error scenario. Each of them is answered as before the
     * deletion or as after it, and afterwards the scenario is gone and nothing names it.
     *
     * A client that names the scenario after the deletion gets the status 422. The one that designates the
     * universal error scenario gets the status 404 when the scenario goes while its request is handled.
     *
     * The deleting client begins a little later from round to round, so that the deletion comes before the
     * designations in some rounds, after them in others, and between them in the rest.
     */
    @Test
    fun aScenarioThatIsDeletedWhileOtherClientsDesignateItIsGoneAndNothingNamesIt() {
        val server = server()
        val service = server.orders()
        val kept = server.scenario(service, """{"name":"kept"}""")
        val ofService = "services/${service.id}"
        val possible = listOf(setOf(204), setOf(200, 422), setOf(200, 422), setOf(200, 422, 404))

        val unexpected =
            (1..RACES).mapNotNull { round ->
                val doomed = server.scenario(service, """{"name":"doomed $round"}""")
                val requests =
                    listOf(
                        Triple("DELETE", "$ofService/scenarios/${doomed.id}", ""),
                        Triple("PATCH", ofService, """{"defaultScenarioId":"${doomed.id}"}"""),
                        Triple("PATCH", ofService, """{"errorScenarioId":"${doomed.id}"}"""),
                        Triple("PUT", "universal-error-scenario", """{"serviceId":"${service.id}","scenarioId":"${doomed.id}"}"""),
                    )
                val statuses =
                    atTheSameTime(requests.size) {
                        if (it == 0) LockSupport.parkNanos(round % HEAD_STARTS * HEAD_START_NANOS)
                        val (method, target, body) = requests[it]
                        server.asked(method, target, body).status
                    }
                val held = server.asked("GET", ofService).decoded<ServiceDto>()
                val universal = server.asked("GET", "universal-error-scenario").status
                val seen = listOf(held.scenarios.map { it.id }, held.defaultScenarioId, held.errorScenarioId, universal)
                val expected = listOf(listOf(kept.id), kept.id, null, 404)
                val asOneAfterAnother = statuses.indices.all { statuses[it] in possible[it] } && seen == expected
                if (asOneAfterAnother) null else "In round $round the clients got $statuses, and the server holds $seen, and not $expected."
            }

        assertEquals(emptyList(), unexpected)
    }

    private companion object {
        /** How often a test switches the default scenario of a service, for a server in each mode. */
        const val SWITCHES = 60

        /** How often a test makes each of its other changes. */
        const val CHANGES = 30

        /** How many rounds of requests at the same time a test sends. */
        const val RACES = 40

        /** The most clients that send a request at the same time. */
        const val CLIENTS = 8

        /** How many other clients send requests to the mock endpoint while a test switches the default scenario. */
        const val OTHER_CLIENTS = 2

        /** The most requests that one of the other clients sends, and its pause between two of them. */
        const val MOST_REQUESTS = 200
        const val PAUSE_MILLIS = 3L

        /**
         * How many head starts the clients that designate a scenario get over the one that deletes it, and
         * by how much one head start is longer than the one before it: they go from none to seven tenths of
         * a millisecond.
         */
        const val HEAD_STARTS = 8
        const val HEAD_START_NANOS = 100_000L

        /** How long a client waits for the others, and a test for a client. */
        const val WAIT_SECONDS = 30L
    }
}
