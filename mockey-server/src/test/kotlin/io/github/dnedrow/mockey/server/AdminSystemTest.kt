package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.InspectorDto
import io.github.dnedrow.mockey.api.ProblemDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.github.dnedrow.mockey.api.TwistConfigurationDto
import io.github.dnedrow.mockey.api.TwistPairDto
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.inspection.Inspector
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistPair
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.storage.FileDefinitionStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The operations of the admin API on the server as a whole, over real HTTP: on a listener that runs the
 * admin API with these operations, for an engine and a mock listener of the test. They are the status of
 * the server and its inspectors, and from task 12.7 on the read-only mode, the reload, the reset, and the
 * last-used times, for an engine that holds the definitions of a directory of the test.
 */
class AdminSystemTest {
    private val since = Instant.parse("2026-01-02T03:04:05.678912Z")
    private val engine: MockeyEngine = runBlocking { MockeyEngine.create() }
    private val otherEngines = mutableListOf<MockeyEngine>()
    private val listeners = mutableListOf<Listener>()
    private val directories = mutableListOf<Path>()

    @AfterTest
    fun stopEverything() {
        listeners.forEach(Listener::stop)
        engine.close()
        otherEngines.forEach(MockeyEngine::close)
        directories.forEach { it.toFile().deleteRecursively() }
    }

    /** Returns a new directory, which is the definitions location of an engine of a test. */
    private fun location(): Path = Files.createTempDirectory("mockey-admin-system-test").also { directories.add(it) }

    /** Returns an engine that holds the definitions of [location]. It is in read-only mode unless it is [writable]. */
    private fun engineOf(
        location: Path,
        writable: Boolean = false,
    ): MockeyEngine {
        val created =
            runBlocking {
                MockeyEngine.create {
                    storage = FileDefinitionStorage(location)
                    readOnly = !writable
                    inspectors = emptyList()
                }
            }
        otherEngines += created
        return created
    }

    /** Returns the names of the services that [location] holds, as an engine reads them that starts now. */
    private fun storedIn(location: Path): List<String> = engineOf(location).definitions.services.map { it.name }

    /** Defines a service in static mode in [engine], with a scenario that answers. */
    private fun service(
        engine: MockeyEngine,
        name: String,
    ): Service =
        runBlocking {
            val service = engine.services.create { it.copy(name = name, mockUrl = name.lowercase(), responseMode = ResponseMode.STATIC) }
            engine.scenarios.create(service.getOrThrow().id) { it.copy(name = "ok", responseBody = name) }.getOrThrow()
            engine.definitions.services.single { it.name == name }
        }

    /** Sends a request to the API of [listener], with [body] as JSON, and returns the answer. */
    private fun send(
        listener: Listener,
        method: String,
        target: String,
        body: String = "",
    ): RawHttp.Answer {
        val headers = if (body.isEmpty()) emptyList() else listOf("Content-Type: application/json")
        return RawHttp.exchange(listener.address, RawHttp.request(method, "/api/v1/$target", headers, body))
    }

    private inline fun <reified T> RawHttp.Answer.decoded(): T = AdminApiJson.instance.decodeFromString<T>(bodyInUtf8)

    /** [answer] is the problem of a request that failed at the definitions location, and it names [location] and says [why]. */
    private fun assertNotStored(
        answer: RawHttp.Answer,
        location: Path,
        why: String,
    ) {
        assertEquals("HTTP/1.1 500 Internal Server Error", answer.statusLine, "$answer")
        assertEquals(listOf("application/problem+json"), answer.headerValues("Content-Type"))
        val problem = answer.decoded<ProblemDto>()
        val reason = problem.detail.removePrefix("The server could not read or write its definitions location, and nothing was changed. ")
        assertTrue(reason.length < problem.detail.length && "$location" in reason && why in reason, problem.detail)
        assertEquals(ProblemDto("about:blank", "Internal Server Error", 500, problem.detail), problem)
    }

    /**
     * Starts a listener that runs the admin API of a server whose mock listener accepts connections on
     * [mock], and whose engine is [engine].
     */
    private fun api(
        mock: InetSocketAddress = address("127.0.0.1", 8080),
        definitions: Path = Path("mockey-definitions"),
        engine: MockeyEngine = this.engine,
    ): Listener {
        val api = AdminApi(token = null, maxRequestBody = 1_024, routes = AdminSystem(engine, since, definitions, mock).routes)
        val module = MockeyServer.adminListenerModule(ForwardMark(adminPort = null), api)
        return Listener.start(ListenerKind.ADMIN, RawHttp.loopback(0), module).also { listeners += it }
    }

    private fun address(
        host: String,
        port: Int,
    ) = InetSocketAddress(InetAddress.getByName(host), port)

    /** Returns an inspector with [name] that has nothing to say about a request. */
    private fun inspector(
        name: String,
        global: Boolean = false,
    ): Inspector =
        object : Inspector {
            override val name = name
            override val isGlobal = global

            override fun inspect(request: MockRequest): String? = null
        }

    /** Returns the status that the API states to a client that names [host] in its request. */
    private fun Listener.status(host: String = "mockey.test"): SystemStatusDto {
        val request = listOf("GET /api/v1/system HTTP/1.1", "Host: $host", "Connection: close", "", "")
        val answer = RawHttp.exchange(address, request.joinToString(RawHttp.CRLF).toByteArray(Charsets.ISO_8859_1))
        assertEquals("HTTP/1.1 200 OK", answer.statusLine)
        return AdminApiJson.instance.decodeFromString<SystemStatusDto>(answer.body)
    }

    /** The scenario "Status is read", for a server that was started without anything. */
    @Test
    fun theStatusStatesEveryItemOfTheServer() {
        val answer = RawHttp.get(api().address, "/api/v1/system")

        val items = (Json.parseToJsonElement(answer.body) as JsonObject).keys
        assertEquals(
            setOf(
                "version",
                "startTime",
                "definitionsLocation",
                "readOnly",
                "tagFilter",
                "enabledTwist",
                "mockBaseUrl",
                "legacyShimEnabled",
            ),
            items,
        )
        assertEquals(
            SystemStatusDto(
                version = System.getProperty("mockey.version"),
                startTime = "2026-01-02T03:04:05.678Z",
                definitionsLocation = Path("mockey-definitions").toAbsolutePath().toString(),
                readOnly = true,
                tagFilter = emptyList(),
                enabledTwist = null,
                mockBaseUrl = "http://127.0.0.1:8080",
                legacyShimEnabled = false,
            ),
            AdminApiJson.instance.decodeFromString<SystemStatusDto>(answer.body),
        )
        assertEquals(listOf("application/json"), answer.headerValues("Content-Type"))
    }

    /** The status is what the server is now: a change to the engine shows in the next answer. */
    @Test
    fun theStatusStatesTheModeTheTagFilterAndTheEnabledTwistConfigurationOfTheEngine() {
        val api = api()
        val before = api.status()
        val twist =
            runBlocking {
                engine.twists.create { it.copy(name = "to the other stage", pairs = listOf(TwistPair("qa1", "qa2"))) }.getOrThrow()
            }

        runBlocking {
            engine.setReadOnly(false)
            engine.tagFilter = Tags.of("Team-B nightly", "team-a")
        }
        val withoutTwist = api.status()
        runBlocking { engine.twists.enable(twist.id) }.getOrThrow()
        val withTwist = api.status()
        runBlocking { engine.twists.disable(twist.id) }.getOrThrow()

        assertEquals(before.copy(readOnly = false, tagFilter = listOf("nightly", "team-a", "team-b")), withoutTwist)
        val stated = TwistConfigurationDto(twist.id.value, "to the other stage", listOf(TwistPairDto("qa1", "qa2")))
        assertEquals(withoutTwist.copy(enabledTwist = stated), withTwist)
        assertEquals(withoutTwist, api.status())
    }

    /** The log states the definitions location in the same way when the server starts. */
    @Test
    fun theDefinitionsLocationIsStatedAsAnAbsolutePathWithoutDetours() {
        val status = api(definitions = Path("some/where/../../mockey definitions/.")).status()

        assertEquals(Path("mockey definitions").toAbsolutePath().toString(), status.definitionsLocation)
    }

    @Test
    fun theUrlOfAMockListenerOnOneAddressNamesThatAddressWhateverTheClientNames() {
        val urls =
            mapOf(
                address("127.0.0.1", 8080) to "http://127.0.0.1:8080",
                address("192.0.2.7", 80) to "http://192.0.2.7:80",
                address("::1", 49152) to "http://[0:0:0:0:0:0:0:1]:49152",
                address("2001:db8::7", 8080) to "http://[2001:db8:0:0:0:0:0:7]:8080",
            )

        for ((mock, url) in urls) {
            val api = api(mock)

            assertEquals(
                listOf(url, url, url),
                listOf("mockey.test", "localhost:8081", "[::1]:8081").map { api.status(it).mockBaseUrl },
                "$mock",
            )
        }
    }

    /**
     * A mock listener on all addresses, which is the default, has no address that a client could write. The
     * client reached the admin listener by the host that it names, and the mock listener answers there too.
     */
    @Test
    fun theUrlOfAMockListenerOnAllAddressesNamesTheHostByWhichTheClientReachedTheAdminListener() {
        for (mock in listOf(address("0.0.0.0", 8080), address("::", 8080))) {
            val api = api(mock)
            val urls =
                mapOf(
                    "mockey.test" to "http://mockey.test:8080",
                    "mockey.test:8081" to "http://mockey.test:8080",
                    "localhost:8081" to "http://localhost:8080",
                    "127.0.0.1:8081" to "http://127.0.0.1:8080",
                    "[::1]:8081" to "http://[::1]:8080",
                    "[2001:db8::7]" to "http://[2001:db8::7]:8080",
                )

            assertEquals(urls, urls.mapValues { (host, _) -> api.status(host).mockBaseUrl }, "$mock")
        }
    }

    /** A request of HTTP/1.0 need not name a host. Its client reached the admin listener at the address of that listener. */
    @Test
    fun theUrlOfAMockListenerOnAllAddressesNamesTheAddressOfTheAdminListenerForARequestThatNamesNoHost() {
        val api = api(address("0.0.0.0", 8080))

        val answer = RawHttp.exchange(api.address, "GET /api/v1/system HTTP/1.0\r\n\r\n".toByteArray())

        assertEquals("HTTP/1.0 200 OK", answer.statusLine)
        assertEquals("http://127.0.0.1:8080", AdminApiJson.instance.decodeFromString<SystemStatusDto>(answer.body).mockBaseUrl)
    }

    /** The mock listener says since when the server runs at `/status`, to the millisecond, and the status says the same. */
    @Test
    fun theStartTimeIsTheOneThatTheLivenessEndpointStates() {
        val module = MockeyServer.mockListenerModule(MockEndpoint(engine, maxRequestBody = 1_024), StatusEndpoint(since))
        val ofLiveness = Listener.start(ListenerKind.MOCK, RawHttp.loopback(0), module).also { listeners += it }

        val liveness = Json.parseToJsonElement(RawHttp.get(ofLiveness.address, "/status").body) as JsonObject

        assertEquals("\"${api().status().startTime}\"", liveness.getValue("since").toString())
    }

    /** Task 12.5, and the scenario "Inspectors are listed" of the inspection, through the API. The list is in the order of the names. */
    @Test
    fun theInstalledInspectorsAreListedWithTheirNamesAndWhetherTheyAreGlobal() {
        val installed = listOf(inspector("trace-header", global = true), inspector("session-check"))
        val withInspectors = runBlocking { MockeyEngine.create { inspectors = installed } }.also { otherEngines += it }

        val listed = RawHttp.get(api(engine = withInspectors).address, "/api/v1/inspectors")
        val none = RawHttp.get(api().address, "/api/v1/inspectors")

        assertEquals("HTTP/1.1 200 OK", listed.statusLine, "$listed")
        assertEquals("""[{"name":"session-check","global":false},{"name":"trace-header","global":true}]""", listed.body)
        val inspectors = AdminApiJson.instance.decodeFromString<List<InspectorDto>>(listed.body)
        assertEquals(listOf(InspectorDto("session-check", global = false), InspectorDto("trace-header", global = true)), inspectors)
        assertEquals(200 to "[]", none.status to none.body)
    }

    // The read-only mode

    /**
     * The scenario "Leave read-only mode", and the scenario "Leaving read-only mode saves earlier changes"
     * of the storage, through the API. The definitions are stored when the request is answered. Once the
     * mode is on again, a change is kept in memory.
     */
    @Test
    fun theReadOnlyModeIsSwitchedOffAndOnAndTheDefinitionsAreStoredWhenItGoesOff() {
        val location = location()
        val stored = engineOf(location)
        val api = api(engine = stored, definitions = location)
        service(stored, "Orders")
        val before = storedIn(location)

        val off = send(api, "PATCH", "system", """{"readOnly": false}""")
        val afterOff = storedIn(location)
        val statusWhenOff = api.status()
        val on = send(api, "PATCH", "system", """{"readOnly": true}""")
        service(stored, "Customers")

        assertEquals(emptyList(), before)
        assertEquals("HTTP/1.1 200 OK", off.statusLine, "$off")
        assertEquals(statusWhenOff to false, off.decoded<SystemStatusDto>() to statusWhenOff.readOnly)
        assertEquals(listOf("Orders"), afterOff)
        assertEquals(200 to true, on.status to on.decoded<SystemStatusDto>().readOnly)
        assertEquals(true to listOf("Orders"), stored.readOnly to storedIn(location))
        assertEquals(listOf("Orders", "Customers"), stored.definitions.services.map { it.name })
    }

    /** A request that states nothing leaves the mode as it is, and so does one that states the mode that the server is in. */
    @Test
    fun anUpdateOfTheSystemStatesTheReadOnlyModeAndNothingElse() {
        val api = api()

        val ofNothing = send(api, "PATCH", "system", "{}")
        val ofTheSame = send(api, "PATCH", "system", """{"readOnly": true}""")
        val ofAText = send(api, "PATCH", "system", """{"readOnly": "false"}""")
        val ofNull = send(api, "PATCH", "system", """{"readOnly": null}""")
        val ofAnother = send(api, "PATCH", "system", """{"readOnly": false, "tagFilter": ["nightly"]}""")

        assertEquals(200 to api.status(), ofNothing.status to ofNothing.decoded<SystemStatusDto>())
        assertEquals(200 to true, ofTheSame.status to ofTheSame.decoded<SystemStatusDto>().readOnly)
        val problems = listOf(ofAText, ofNull, ofAnother).map { it.status to it.decoded<ProblemDto>().errors }
        val expected =
            listOf(
                422 to listOf(FieldErrorDto("readOnly", "The value has to be true or false.")),
                422 to listOf(FieldErrorDto("readOnly", "The member must have a value, and null is none.")),
                422 to listOf(FieldErrorDto("tagFilter", "The request can have no member of this name here.")),
            )
        assertEquals(expected, problems)
        assertTrue(engine.readOnly)
    }

    /** When the definitions cannot be stored, the server stays in read-only mode, and the answer says why. */
    @Test
    fun theReadOnlyModeStaysOnWhenTheDefinitionsCannotBeStored() {
        val location = location()
        val stored = engineOf(location)
        val api = api(engine = stored, definitions = location)
        service(stored, "Orders")
        Files.delete(location)
        Files.writeString(location, "A file is where the definitions location was.")

        val answer = send(api, "PATCH", "system", """{"readOnly": false}""")

        assertNotStored(answer, location, "")
        assertTrue(stored.readOnly && api.status().readOnly)
        assertEquals(listOf("Orders"), stored.definitions.services.map { it.name })
    }

    // Reload

    /**
     * The scenario "Reload after files were updated" of the storage, through the API. What was changed in
     * memory and not stored is gone after the reload.
     */
    @Test
    fun theDefinitionsAreReloadedFromTheDefinitionsLocation() {
        val location = location()
        TestDefinitions.store(location) { services.create { it.copy(name = "Orders", mockUrl = "orders") }.getOrThrow() }
        val stored = engineOf(location)
        val api = api(engine = stored, definitions = location)
        service(stored, "Unsaved")
        TestDefinitions.store(location) { services.create { it.copy(name = "Customers", mockUrl = "customers") }.getOrThrow() }

        val answer = send(api, "POST", "system/reload")

        assertEquals("HTTP/1.1 204 No Content" to "", answer.statusLine to answer.body)
        assertEquals(emptyList(), answer.headerValues("Content-Type") + answer.headerValues("Content-Length"))
        assertEquals(listOf("Orders", "Customers"), stored.definitions.services.map { it.name })
    }

    /** The scenario "Reload of a broken set" of the storage, through the API: the answer names the file, and the definitions stay. */
    @Test
    fun aReloadOfABrokenSetFailsNamingTheFileAndLeavesTheDefinitionsAsTheyAre() {
        val location = location()
        TestDefinitions.store(location) { services.create { it.copy(name = "Orders", mockUrl = "orders") }.getOrThrow() }
        val stored = engineOf(location)
        val api = api(engine = stored, definitions = location)
        service(stored, "Unsaved")
        val before = stored.definitions
        Files.writeString(location.resolve(MAIN_FILE), "{ no JSON")

        val answer = send(api, "POST", "system/reload")

        assertNotStored(answer, location, "$MAIN_FILE cannot be read")
        assertEquals(before, stored.definitions)
    }

    // Reset

    /**
     * The scenario "Clean slate" of the storage, through the API, for a server in writable mode, whose
     * definitions location holds no definitions afterwards either. The mode stays as it is.
     */
    @Test
    fun aResetMakesACleanSlateInMemoryAndInTheDefinitionsLocation() {
        val location = location()
        val stored = engineOf(location, writable = true)
        val api = api(engine = stored, definitions = location)
        val orders = service(stored, "Orders")
        runBlocking {
            val plan = stored.plans.save(listOf(orders.id), "Night").getOrThrow()
            stored.plans.designateDefault(plan.id).getOrThrow()
            val twist = stored.twists.create { it.copy(name = "to-qa2", pairs = listOf(TwistPair("qa1", "qa2"))) }.getOrThrow()
            stored.twists.enable(twist.id).getOrThrow()
            stored.handle(MockRequest("GET", "http://mockey.test/service/orders"), "192.0.2.7")
        }
        stored.tagFilter = Tags.of("nightly")
        val before = listOf(storedIn(location).size, stored.history.entries().size, stored.lastUsedTimes.services.size)

        val answer = send(api, "POST", "system/reset")

        assertEquals(listOf(1, 1, 1), before)
        assertEquals("HTTP/1.1 204 No Content" to "", answer.statusLine to answer.body)
        assertEquals(Definitions.EMPTY to Tags.EMPTY, stored.definitions to stored.tagFilter)
        assertEquals(emptyList(), stored.history.entries())
        assertEquals(0, stored.lastUsedTimes.run { services.size + scenarios.size + plans.size })
        assertEquals(Definitions.EMPTY, engineOf(location).definitions)
        val status = api.status()
        assertEquals(Triple(false, emptyList<String>(), null), Triple(status.readOnly, status.tagFilter, status.enabledTwist))
    }

    // The last-used times

    /** The scenario "Clearing last-used times" of the history, through the API. The history and the definitions stay. */
    @Test
    fun theLastUsedTimesAreCleared() {
        val api = api()
        val orders = service(engine, "Orders")
        runBlocking {
            val plan = engine.plans.save(listOf(orders.id), "Night").getOrThrow()
            engine.plans.apply(plan.id).getOrThrow()
            engine.handle(MockRequest("GET", "http://mockey.test/service/orders"), "192.0.2.7")
        }
        val before = engine.lastUsedTimes.run { listOf(services.size, scenarios.size, plans.size) }
        val definitions = engine.definitions

        val answer = send(api, "DELETE", "system/last-used-times")
        val again = send(api, "DELETE", "system/last-used-times")

        assertEquals(listOf(1, 1, 1), before)
        assertEquals("HTTP/1.1 204 No Content" to "", answer.statusLine to answer.body)
        assertEquals(listOf(0, 0, 0), engine.lastUsedTimes.run { listOf(services.size, scenarios.size, plans.size) })
        assertEquals(204, again.status)
        assertEquals(definitions to 1, engine.definitions to engine.history.entries().size)
    }

    private companion object {
        /** The file of a definitions location that holds everything but the services. */
        const val MAIN_FILE = "mockey.json"
    }
}
