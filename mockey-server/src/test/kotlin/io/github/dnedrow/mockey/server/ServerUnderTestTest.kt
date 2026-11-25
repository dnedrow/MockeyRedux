package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.model.TwistPair
import io.github.dnedrow.mockey.server.TestDefinitions.orders
import io.github.dnedrow.mockey.server.TestDefinitions.staticService
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The server that the tests of other modules run beside them: it is what the program is, but for how it ends. */
class ServerUnderTestTest {
    private val directory: Path = Files.createTempDirectory("mockey-server-under-test-")
    private val definitions: Path = directory.resolve("definitions")

    @AfterTest
    fun deleteWhatTheTestWrote() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun theServerOfTheArgumentsAnswersOnItsMockListenerUntilItIsClosed() {
        val document = write("initial.json", TestDefinitions.document { orders() })

        val server = ServerUnderTest.start(arguments("--initial-definitions", "$document"))
        val answer = server.use { get(it, "/service/orders") }

        assertEquals(200 to TestDefinitions.SILVER_ORDERS, answer.status to answer.body)
        assertEquals("http://127.0.0.1:${server.mockUrl.port}", "${server.mockUrl}")
        assertFailsWith<IOException> { get(server, "/status") }
    }

    @Test
    fun argumentsThatStartNoServerFailWithWhatTheProgramPrints() {
        val unusable = assertFailsWith<IllegalArgumentException> { ServerUnderTest.start(listOf("--mock-port", "none")) }
        val version = assertFailsWith<IllegalArgumentException> { ServerUnderTest.start(listOf("--version")) }
        val notStarted =
            assertFailsWith<IllegalArgumentException> {
                ServerUnderTest.start(
                    arguments("--initial-definitions", "$directory/missing.json"),
                )
            }

        assertContains(unusable.message.orEmpty(), "the program ends with the status 1: ")
        assertContains(unusable.message.orEmpty(), "invalid value for --mock-port: none is not a valid integer")
        assertContains(version.message.orEmpty(), "the program ends with the status 0: $PROGRAM_NAME version ${ServerVersion.text}")
        assertContains(notStarted.message.orEmpty(), "the program ends with the status 1: Error: ")
    }

    @Test
    fun aCommandOfTheProgramRunsAndItsOutputIsReturned() {
        val export = write("export.xml", LegacySources.export())

        val report = ServerUnderTest.run(listOf("import-legacy", "$export", "--definitions", "$definitions"))
        val answer = ServerUnderTest.start(arguments()).use { get(it, "/service/legacy/orders") }

        assertContains(report, "Imported $export into the definitions location ")
        assertEquals(200 to LegacySources.SILVER_ORDERS, answer.status to answer.body)
    }

    @Test
    fun argumentsThatAreNoCommandOrThatFailAreNotRun() {
        val server = assertFailsWith<IllegalArgumentException> { ServerUnderTest.run(arguments()) }
        val failed =
            assertFailsWith<IllegalArgumentException> { ServerUnderTest.run(listOf("import-legacy", "$directory/missing.xml")) }

        assertContains(server.message.orEmpty(), "name no command: the program starts a server with them.")
        assertContains(failed.message.orEmpty(), "The program ends with the status 1: Error: ")
    }

    @Test
    fun theTwistConfigurationWithTheNameIsEnabled() {
        val document =
            write(
                "initial.json",
                TestDefinitions.document {
                    staticService("Plain", "plain", "only" to "not twisted")
                    staticService("Silver", "silver", "only" to "twisted to silver")
                    staticService("Gold", "gold", "only" to "twisted to gold")
                    twists.create { it.copy(name = "to silver", pairs = listOf(TwistPair("plain", "silver"))) }.getOrThrow()
                    twists.create { it.copy(name = "to gold", pairs = listOf(TwistPair("plain", "gold"))) }.getOrThrow()
                },
            )

        ServerUnderTest.start(arguments("--initial-definitions", "$document")).use { server ->
            val before = get(server, "/service/plain")
            server.enableTwist("to gold")
            val after = get(server, "/service/plain")
            val unknown = assertFailsWith<IllegalArgumentException> { server.enableTwist("to bronze") }

            assertEquals("not twisted" to "twisted to gold", before.body to after.body)
            assertEquals("Not exactly one twist configuration has the name 'to bronze'.", unknown.message)
        }
    }

    @Test
    fun theOutboundProxyGetsTheRequestsToRealServicesWithTheCredentials() {
        StubProxy().use { proxy ->
            ServerUnderTest.start(arguments()).use { server ->
                server.useOutboundProxy(proxy.url, "parity-user", "parity-pass")
                val answer = get(server, "/service/http://real.invalid/orders")
                val noProxy = assertFailsWith<IllegalArgumentException> { server.useOutboundProxy("no address", "user", "pass") }

                val credentials = Base64.getEncoder().encodeToString("parity-user:parity-pass".toByteArray())
                assertEquals(200 to StubProxy.ANSWER, answer.status to answer.body)
                assertEquals(listOf("http://real.invalid/orders"), proxy.targets)
                assertEquals(listOf<String?>("Basic $credentials"), proxy.authorizations)
                assertContains(noProxy.message.orEmpty(), "url")
            }
        }
    }

    /** The arguments of a server on free ports of the loopback address that keeps its definitions in [definitions], and [more]. */
    private fun arguments(vararg more: String): List<String> =
        listOf("--mock-host", "127.0.0.1", "--mock-port", "0", "--admin-port", "0", "--definitions", "$definitions") + more

    private fun write(
        name: String,
        content: String,
    ): Path = directory.resolve(name).also { Files.writeString(it, content) }

    private fun get(
        server: ServerUnderTest,
        target: String,
    ): RawHttp.Answer = RawHttp.exchange(InetSocketAddress(server.mockUrl.host, server.mockUrl.port), RawHttp.request("GET", target))
}
