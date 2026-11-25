package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.github.dnedrow.mockey.api.TwistConfigurationDto
import io.github.dnedrow.mockey.api.TwistPairDto
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistPair
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The status of the server as the admin API states it, over real HTTP: on a listener that runs the admin
 * API with the operations on the server as a whole, for an engine and a mock listener of the test.
 */
class AdminSystemTest {
    private val since = Instant.parse("2026-01-02T03:04:05.678912Z")
    private val engine: MockeyEngine = runBlocking { MockeyEngine.create() }
    private val listeners = mutableListOf<Listener>()

    @AfterTest
    fun stopEverything() {
        listeners.forEach(Listener::stop)
        engine.close()
    }

    /** Starts a listener that runs the admin API of a server whose mock listener accepts connections on [mock]. */
    private fun api(
        mock: InetSocketAddress = address("127.0.0.1", 8080),
        definitions: Path = Path("mockey-definitions"),
    ): Listener {
        val api = AdminApi(token = null, maxRequestBody = 1_024, routes = AdminSystem(engine, since, definitions, mock).routes)
        return Listener.start(ListenerKind.ADMIN, RawHttp.loopback(0), MockeyServer.adminListenerModule(api)).also { listeners += it }
    }

    private fun address(
        host: String,
        port: Int,
    ) = InetSocketAddress(InetAddress.getByName(host), port)

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
}
