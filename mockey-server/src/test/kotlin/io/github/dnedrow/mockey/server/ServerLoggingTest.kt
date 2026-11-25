package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.ProxyCredentials
import io.github.dnedrow.mockey.core.model.Tags
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.OffsetDateTime
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The log of the server program: where it goes, from which level on it is written, what the program writes
 * to it when it starts, and what it never holds.
 */
class ServerLoggingTest {
    private val logger = LoggerFactory.getLogger("io.github.dnedrow.mockey.server.ServerLoggingTest")
    private val levelBefore = ServerLogging.level()

    /** Settings with which a server of a test starts whatever else listens on this machine. */
    private val onFreePorts = ServerSettings(mockHost = "127.0.0.1", mockPort = 0, adminPort = 0)

    @AfterTest
    fun restoreTheLevel() {
        ServerLogging.configure(levelBefore)
    }

    /** Returns what [block] writes to the standard output. */
    private fun standardOutputOf(block: () -> Unit): String {
        val before = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true))
        try {
            block()
        } finally {
            System.setOut(before)
        }
        return captured.toString()
    }

    /** Writes a message at each level, from the one that says the least to the one that says the most. */
    private fun logAtEveryLevel() {
        logger.error("message at error")
        logger.warn("message at warn")
        logger.info("message at info")
        logger.debug("message at debug")
        logger.trace("message at trace")
    }

    private fun messagesIn(log: String): List<String> = Regex("message at (\\w+)").findAll(log).map { it.groupValues[1] }.toList()

    @Test
    fun theLogIsWrittenFromTheLevelThatIsSet() {
        val written =
            LogLevel.entries.associateWith { level ->
                ServerLogging.configure(level)
                assertEquals(level, ServerLogging.level())
                messagesIn(standardOutputOf(::logAtEveryLevel))
            }

        assertEquals(listOf("error"), written[LogLevel.ERROR])
        assertEquals(listOf("error", "warn"), written[LogLevel.WARN])
        assertEquals(listOf("error", "warn", "info"), written[LogLevel.INFO])
        assertEquals(listOf("error", "warn", "info", "debug"), written[LogLevel.DEBUG])
        assertEquals(listOf("error", "warn", "info", "debug", "trace"), written[LogLevel.TRACE])
    }

    @Test
    fun aLineOfTheLogStatesTheTimeTheLevelTheThreadAndTheLogger() {
        ServerLogging.configure(LogLevel.INFO)

        val log = standardOutputOf { logger.warn("a message with {} part", "one") }

        val line =
            Regex("""(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(?:Z|[+-]\d{2}:\d{2})) WARN  \[(.+)] (\S+) - a message with one part\n""")
        val (time, thread, name) = checkNotNull(line.matchEntire(log)) { "The log is: $log" }.destructured
        assertTrue(OffsetDateTime.parse(time).isAfter(OffsetDateTime.parse("2025-01-01T00:00:00Z")), time)
        assertEquals(Thread.currentThread().name, thread)
        assertTrue(name.endsWith(".ServerLoggingTest"), name)
    }

    @Test
    fun theServerSetsTheLevelOfItsSettingsAndWritesTheSettingsToTheLogFirst() {
        val settings = onFreePorts.copy(logLevel = LogLevel.WARN, tagFilter = Tags.of("regression"))
        ServerLogging.configure(LogLevel.TRACE)

        val quiet = standardOutputOf { MockeyServer.start(settings).close() }
        val log = standardOutputOf { MockeyServer.start(settings.copy(logLevel = LogLevel.INFO)).close() }

        assertEquals("", quiet)
        assertEquals(LogLevel.INFO, ServerLogging.level())
        val lines = log.lines().filter { it.isNotEmpty() }
        val expected =
            listOf("mockey-server ${System.getProperty("mockey.version")} starts with these settings:") +
                settings.copy(logLevel = LogLevel.INFO).summary().map { (name, value) -> "  $name = $value" }
        assertEquals(expected, lines.take(expected.size).map { it.substringAfter(" - ") })
        assertContains(expected, "  mock-port = 0")
        assertContains(expected, "  tag-filter = regression")
        assertTrue(lines.take(expected.size).all { " INFO  [" in it && " i.g.d.mockey.server.MockeyServer - " in it }, log)
    }

    @Test
    fun theServerWritesToTheLogWhereItsListenersAcceptConnections() {
        var ports = 0 to 0
        val log =
            standardOutputOf {
                MockeyServer.start(onFreePorts).use { server ->
                    ports = server.listeners.mock.address.port to server.listeners.admin.address.port
                }
            }

        val (mockPort, adminPort) = ports
        val start = " INFO  [${Thread.currentThread().name}] i.g.d.mockey.server.MockeyServer - "
        assertContains(log, start + "The mock listener accepts connections on 127.0.0.1:$mockPort.\n")
        assertContains(log, start + "The admin listener accepts connections on 127.0.0.1:$adminPort.\n")
        assertFalse(" WARN " in log, log)
    }

    /** Returns the settings that the command line makes of [arguments] and of the [environment]. */
    private fun settingsOf(
        vararg arguments: String,
        environment: Map<String, String> = emptyMap(),
    ): ServerSettings {
        val settings = mutableListOf<ServerSettings>()
        assertEquals(0, ServerCommand(environment::get) { settings += it }.execute(arguments.asList()))
        return settings.single()
    }

    /**
     * The scenario "Secrets are not logged", as far as the server goes at this point: it runs at the most
     * verbose level with an admin token, given both ways, and with an outbound proxy that takes credentials.
     * It gets requests that present the token in every part of them, and it forwards them through the
     * proxy. The log holds nothing of a request.
     */
    @Test
    fun theMostVerboseLogHoldsNeitherTheAdminTokenNorTheProxyPassword() {
        val token = "tk-9f3b2c71d4e8"
        val tokenOfVariable = "tk-6a0d5e1f8b42"
        val password = "pw-5a6c0e2d9b17"
        val proxyCredentials = ProxyCredentials("mockey", password)
        val credentials = Base64.getEncoder().encodeToString("mockey:$password".toByteArray())

        StubProxy().use { proxy ->
            val log =
                standardOutputOf {
                    val environment = mapOf("MOCKEY_ADMIN_TOKEN" to tokenOfVariable)
                    val arguments = arrayOf("--log-level", "trace", "--mock-host", "127.0.0.1", "--mock-port", "0", "--admin-port", "0")
                    val withBoth = settingsOf(*arguments, "--admin-token", token, environment = environment)
                    val withTheVariable = settingsOf(*arguments, environment = environment)
                    for ((settings, presented) in listOf(withBoth to token, withTheVariable to tokenOfVariable)) {
                        assertTrue(settings.adminToken!!.matches(presented))
                        MockeyServer.start(settings).use { server ->
                            val presenting = listOf("Authorization: Bearer $presented")
                            runBlocking {
                                server.engine.updateOutboundProxy {
                                    OutboundProxy(
                                        proxy.url,
                                        enabled = true,
                                        credentials = proxyCredentials,
                                    )
                                }
                            }
                            val ofAdmin = RawHttp.get(server.listeners.admin.address, "/api/v1/status?token=$presented", presenting)
                            val ofMock =
                                RawHttp.get(
                                    server.listeners.mock.address,
                                    "/service/http://upstream.test/$presented?token=$presented",
                                    presenting,
                                )
                            assertEquals(404, ofAdmin.status)
                            assertEquals(200 to StubProxy.ANSWER, ofMock.status to ofMock.body)
                        }
                    }
                    logAtEveryLevel()
                }

            assertEquals(listOf<String?>("Basic $credentials", "Basic $credentials"), proxy.authorizations, "The proxy got the credentials")
            assertEquals(listOf(token, tokenOfVariable).map { "http://upstream.test/$it?token=$it" }, proxy.targets.toList())
            assertEquals(listOf("error", "warn", "info", "debug", "trace"), messagesIn(log), "The log is written at every level")
            assertEquals(2, Regex(""" - {3}admin-token = set\n""").findAll(log).count(), log)
            for (secret in listOf(token, tokenOfVariable, password, credentials)) {
                assertFalse(secret in log, "The log holds $secret:\n$log")
            }
        }
    }
}
