package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.ProxyCredentials
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.request.MockRequest
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
    fun theServerSetsTheLevelOfItsSettingsAndWritesTheSettingsToTheLog() {
        val settings = ServerSettings(mockPort = 9100, logLevel = LogLevel.WARN, tagFilter = Tags.of("regression"))
        ServerLogging.configure(LogLevel.TRACE)

        val quiet = standardOutputOf { serve(settings) }
        val log = standardOutputOf { serve(settings.copy(logLevel = LogLevel.INFO)) }

        assertEquals("", quiet)
        assertEquals(LogLevel.INFO, ServerLogging.level())
        val messages = log.lines().filter { it.isNotEmpty() }.map { it.substringAfter(" - ") }
        val expected =
            listOf("mockey-server ${System.getProperty("mockey.version")} starts with these settings:") +
                settings.copy(logLevel = LogLevel.INFO).summary().map { (name, value) -> "  $name = $value" }
        assertEquals(expected, messages)
        assertContains(messages, "  mock-port = 9100")
        assertContains(messages, "  tag-filter = regression")
        assertTrue(log.lines().filter { it.isNotEmpty() }.all { " INFO  [" in it && "i.g.dnedrow.mockey.server.Server - " in it }, log)
    }

    /**
     * The scenario "Secrets are not logged", as far as the program goes at this point: it starts at the most
     * verbose level with an admin token, given both ways, and an engine forwards a request through an
     * outbound proxy that takes credentials.
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
                    assertEquals(0, ServerCommand(environment::get).execute(listOf("--log-level", "trace", "--admin-token", token)))
                    assertEquals(0, ServerCommand(environment::get).execute(listOf("--log-level", "trace")))
                    runBlocking {
                        MockeyEngine.create().use { engine ->
                            engine.updateOutboundProxy { OutboundProxy(proxy.url, enabled = true, credentials = proxyCredentials) }
                            val handled = engine.handle(MockRequest("GET", "/service/http://upstream.test/orders"))
                            assertEquals(200 to StubProxy.ANSWER, handled.response.status to handled.response.bodyText)
                        }
                    }
                    logAtEveryLevel()
                }

            assertEquals(listOf<String?>("Basic $credentials"), proxy.authorizations, "The proxy got the credentials")
            assertEquals(listOf("error", "warn", "info", "debug", "trace"), messagesIn(log), "The log is written at every level")
            assertEquals(2, Regex(""" - {3}admin-token = set\n""").findAll(log).count(), log)
            for (secret in listOf(token, tokenOfVariable, password, credentials)) {
                assertFalse(secret in log, "The log holds $secret:\n$log")
            }
        }
    }
}
