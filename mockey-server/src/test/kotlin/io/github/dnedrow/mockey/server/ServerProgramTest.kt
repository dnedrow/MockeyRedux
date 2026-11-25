package io.github.dnedrow.mockey.server

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server program as a process: its exit status, its two output streams, its environment, and the
 * sockets on which it listens.
 */
class ServerProgramTest {
    /** Returns the messages of the log in [output], each without the time, the level, the thread, and the logger. */
    private fun messages(output: String): List<String> = output.lines().filter { it.isNotEmpty() }.map { it.substringAfter(" - ") }

    @Test
    fun theHelpOptionPrintsTheSettingsAndEndsTheProgramWithStatusZero() {
        val outcome = ServerProgram.run("--help")

        assertEquals(0, outcome.status, "$outcome")
        assertTrue(outcome.output.startsWith("Usage: mockey-server [<options>] [<command> [<args>]...]\n"), "$outcome")
        assertContains(outcome.output, "  --mock-port=<port>\n")
        assertContains(outcome.output, "      Environment variable: MOCKEY_MOCK_PORT\n      Default: 8080\n")
        assertEquals("", outcome.errors)
    }

    @Test
    fun theVersionOptionPrintsTheVersionAndEndsTheProgramWithStatusZero() {
        val outcome = ServerProgram.run("--version")

        assertEquals(0, outcome.status, "$outcome")
        assertEquals("mockey-server version ${System.getProperty("mockey.version")}\n", outcome.output)
        assertEquals("", outcome.errors)
    }

    @Test
    fun anArgumentThatCannotBeUsedEndsTheProgramWithAnotherStatus() {
        val outcome = ServerProgram.run("--mock-port", "http")

        assertEquals(1, outcome.status, "$outcome")
        assertEquals(
            "Usage: mockey-server [<options>] [<command> [<args>]...]\n\n" +
                "Error: invalid value for --mock-port: http is not a valid integer\n",
            outcome.errors,
        )
        assertEquals("", outcome.output)
    }

    @Test
    fun aVariableThatCannotBeUsedEndsTheProgramWithAnotherStatus() {
        val outcome = ServerProgram.run(variables = mapOf("MOCKEY_ADMIN_PORT" to "70000"))

        assertEquals(1, outcome.status, "$outcome")
        assertContains(outcome.errors, "Error: invalid value for MOCKEY_ADMIN_PORT: 70000 is not in the valid range of 0 to 65535")
        assertEquals("", outcome.output)
    }

    /** The scenario "Option overrides environment". */
    @Test
    fun theOptionForThePortOfTheMockListenerOverridesTheEnvironment() {
        val (ofVariable, ofOption, ofAdmin) = RawHttp.freePorts(3)
        val variables =
            mapOf(
                "MOCKEY_MOCK_HOST" to "127.0.0.1",
                "MOCKEY_MOCK_PORT" to "$ofVariable",
                "MOCKEY_ADMIN_PORT" to "$ofAdmin",
                "MOCKEY_TAG_FILTER" to "regression",
            )

        ServerProgram.start("--mock-port", "$ofOption", variables = variables).use { program ->
            val listening = program.listening()

            assertEquals(ofOption to ofAdmin, listening.mockPort to listening.adminPort)
            assertEquals(404, RawHttp.get(RawHttp.loopback(ofOption), "/service/customer/7").status)
            assertNull(RawHttp.getIfReached(RawHttp.loopback(ofVariable), WAIT_MILLIS), "Nothing listens on the port of the variable")
            val messages = messages(program.output)
            assertEquals("mockey-server ${System.getProperty("mockey.version")} starts with these settings:", messages.first())
            assertContains(messages, "  mock-host = 127.0.0.1")
            assertContains(messages, "  mock-port = $ofOption")
            assertContains(messages, "  admin-port = $ofAdmin")
            assertContains(messages, "  tag-filter = regression")
            assertContains(messages, "  history-limit = 1000")
            assertEquals("", program.errors)
        }
    }

    /**
     * The scenario "Defaults". It takes the two default ports, which have to be free. The program finds its
     * definitions in the default location, under its working directory.
     */
    @Test
    fun theProgramStartedWithoutSettingsServesMockTrafficOnAllAddressesAtPort8080AndTheAdminApiOnLoopbackAtPort8081() {
        TestEnvironment.needs(
            TestEnvironment.isFree("0.0.0.0", 8080) && TestEnvironment.isFree("127.0.0.1", 8081),
            "the ports 8080 and 8081 free",
        )

        ServerProgram.start(prepare = { StoredDefinitions.customers(it.resolve("mockey-definitions")) }).use { program ->
            val listening = program.listening()

            assertEquals("0.0.0.0" to 8080, listening.mockHost to listening.mockPort)
            assertEquals("127.0.0.1" to 8081, listening.adminHost to listening.adminPort)
            val ofMock = RawHttp.get(listening.mock, "/service/customer/7")
            val ofAdmin = RawHttp.get(listening.admin, "/service/customer/7")
            val status = RawHttp.get(listening.mock, "/status")
            assertEquals(200 to StoredDefinitions.CUSTOMER, ofMock.status to ofMock.body)
            assertEquals(404 to "", ofAdmin.status to ofAdmin.body)
            assertEquals(200, status.status)
            assertTrue(status.body.startsWith("""{"status":"running","since":""""), status.body)
            assertEquals(404, RawHttp.get(listening.admin, "/status").status)
            val ofApi = RawHttp.get(listening.admin, "/api/v1/services")
            val ofApiOnMock = RawHttp.get(listening.mock, "/api/v1/services")
            assertEquals(200, ofApi.status)
            assertContains(ofApi.body, "\"name\":\"Customers\"")
            assertEquals(404 to "", ofApiOnMock.status to ofApiOnMock.body)
            assertContains(messages(program.output), "The mock listener accepts connections on 0.0.0.0:8080.")
            assertContains(messages(program.output), "The admin listener accepts connections on 127.0.0.1:8081.")
            assertEquals("", program.errors)
        }
    }

    /** The scenario "Unprotected admin listener is refused". */
    @Test
    fun anAdminListenerOnAllAddressesWithoutATokenEndsTheProgramWithAnExplanation() {
        val outcome = ServerProgram.run("--admin-host", "0.0.0.0", "--mock-port", "0", "--admin-port", "0")

        assertEquals(1, outcome.status, "$outcome")
        assertEquals(
            "Error: The admin listener is set to the address 0.0.0.0, which other machines can reach, and there is no admin token. " +
                "Whoever reaches the admin API can change the definitions and make the server send requests. " +
                "Set a token with --admin-token or MOCKEY_ADMIN_TOKEN, or allow the admin API without one with " +
                "--allow-unprotected-admin or MOCKEY_ALLOW_UNPROTECTED_ADMIN=true.\n",
            outcome.errors,
        )
        assertContains(messages(outcome.output), "  admin-host = 0.0.0.0")
        assertFalse("accepts connections" in outcome.output, outcome.output)
    }

    /** The scenario "Explicit override". */
    @Test
    fun theOverrideLetsAnAdminListenerOnAllAddressesStartWithoutAToken() {
        val arguments =
            arrayOf(
                "--admin-host",
                "0.0.0.0",
                "--allow-unprotected-admin",
                "--mock-host",
                "127.0.0.1",
                "--mock-port",
                "0",
                "--admin-port",
                "0",
            )

        ServerProgram.start(*arguments).use { program ->
            val listening = program.listening()

            assertEquals("0.0.0.0", listening.adminHost)
            assertEquals(404, RawHttp.get(listening.admin).status)
            assertEquals(404, RawHttp.get(listening.mock).status)
            assertContains(
                program.output,
                " WARN  [main] i.g.d.mockey.server.MockeyServer - " +
                    "The admin listener accepts connections from other machines without an admin token. " +
                    "Whoever reaches it can change the definitions and make the server send requests.\n",
            )
            assertEquals("", program.errors)
        }
    }

    @Test
    fun aTokenLetsAnAdminListenerOnAllAddressesStart() {
        val variables = mapOf("MOCKEY_ADMIN_HOST" to "0.0.0.0", "MOCKEY_ADMIN_TOKEN" to "tk-2d9e61a07c43")

        ServerProgram.start("--mock-host", "127.0.0.1", "--mock-port", "0", "--admin-port", "0", variables = variables).use { program ->
            val listening = program.listening()

            assertEquals("0.0.0.0", listening.adminHost)
            assertEquals(404, RawHttp.get(listening.admin).status)
            assertFalse(" WARN " in program.output, program.output)
            assertEquals("", program.errors)
        }
    }

    /** The scenario "Port already in use", with a port that the system picked in place of port 8080. */
    @Test
    fun aPortThatAnotherProgramListensOnEndsTheProgramWithTheAddressAndThePort() {
        ServerSocket().use { other ->
            other.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))
            val inUse = other.localPort

            val outcome = ServerProgram.run("--mock-port", "$inUse", "--admin-port", "0")

            assertEquals(1, outcome.status, "$outcome")
            assertEquals(
                "Error: The mock listener cannot accept connections on 0.0.0.0:$inUse: Address already in use. " +
                    "The address is the setting --mock-host or MOCKEY_MOCK_HOST, and the port is --mock-port or MOCKEY_MOCK_PORT.\n",
                outcome.errors,
            )
            assertFalse("accepts connections" in outcome.output, outcome.output)
        }
    }

    /**
     * The scenario "Secrets are not logged" for the admin token: the program runs at the most verbose level
     * with a token from both sources and gets requests that present the tokens, to the admin API as well,
     * which takes the token of the option.
     */
    @Test
    fun theProgramWritesItsLogToTheStandardOutputWithoutTheAdminToken() {
        val ofVariable = "tk-0b7d43e19a6c"
        val ofOption = "tk-e52f8c0a1d37"
        val arguments =
            arrayOf("--log-level", "trace", "--admin-token", ofOption, "--mock-host", "127.0.0.1", "--mock-port", "0", "--admin-port", "0")

        ServerProgram.start(*arguments, variables = mapOf("MOCKEY_ADMIN_TOKEN" to ofVariable)).use { program ->
            val listening = program.listening()
            // The option comes before the variable, so its token is the token of the server. The token does not
            // belong in a query string, and the admin API takes no such parameter from a request that presents it.
            for ((token, ofApi) in listOf(ofVariable to (401 to 401), ofOption to (200 to 400))) {
                val presenting = listOf("Authorization: Bearer $token")
                assertEquals(ofApi.first, RawHttp.get(listening.admin, "/api/v1/system", presenting).status)
                assertEquals(ofApi.second, RawHttp.get(listening.admin, "/api/v1/system?token=$token", presenting).status)
                assertEquals(401, RawHttp.get(listening.admin, "/api/v1/services?token=$token").status)
                assertEquals(404, RawHttp.get(listening.mock, "/service/customer/7?token=$token", presenting).status)
                assertEquals(404, RawHttp.get(listening.mock, "/service/$token").status)
                assertEquals(200, RawHttp.get(listening.mock, "/status?token=$token", presenting).status)
            }

            val messages = messages(program.output)
            assertContains(messages, "  admin-token = set")
            assertContains(messages, "  log-level = trace")
            assertTrue(program.output.lines().any { " TRACE " in it || " DEBUG " in it }, "The log is written at the most verbose level")
            assertEquals("", program.errors)
            for (token in listOf(ofVariable, ofOption)) {
                assertFalse(token in program.output, "The log holds $token:\n${program.output}")
            }
        }
    }

    private companion object {
        /** How long a test waits for an answer that is not to come. */
        const val WAIT_MILLIS = 500
    }
}
