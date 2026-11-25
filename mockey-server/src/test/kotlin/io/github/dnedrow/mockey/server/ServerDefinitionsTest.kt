package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.storage.FileDefinitionStorage
import io.github.dnedrow.mockey.server.TestDefinitions.orders
import io.github.dnedrow.mockey.server.TestDefinitions.staticService
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The definitions that a server starts with, as its clients find them over HTTP: the definitions of its
 * definitions location, and the initial definitions that take their place.
 */
class ServerDefinitionsTest {
    private val levelBefore = ServerLogging.level()
    private val directory: Path = Files.createTempDirectory("mockey-server-definitions-test")

    /** The definitions location of the servers of a test. It does not exist until the test or a server makes it. */
    private val location: Path = directory.resolve("mockey-definitions")
    private val servers = mutableListOf<MockeyServer>()
    private val stubs = mutableListOf<StubUpstream>()

    @AfterTest
    fun endWhatTheTestStarted() {
        servers.forEach(MockeyServer::close)
        stubs.forEach(StubUpstream::close)
        if (Files.isDirectory(location)) TestEnvironment.permit(location, "rwxr-xr-x")
        directory.toFile().deleteRecursively()
        ServerLogging.configure(levelBefore)
    }

    /** Settings of a server on free ports of the loopback address, which says little in the log. */
    private fun settings(
        initialDefinitions: String? = null,
        readOnly: Boolean = true,
        tagFilter: Tags = Tags.EMPTY,
    ) = ServerSettings(
        mockHost = "127.0.0.1",
        mockPort = 0,
        adminPort = 0,
        definitions = location,
        readOnly = readOnly,
        initialDefinitions = initialDefinitions,
        tagFilter = tagFilter,
        logLevel = LogLevel.WARN,
    )

    private fun start(settings: ServerSettings): MockeyServer = MockeyServer.start(settings).also { servers += it }

    /** Returns the status and the body of the answer of the mock listener to a GET request for [target]. */
    private fun MockeyServer.answerTo(target: String): Pair<Int?, String> =
        RawHttp.get(listeners.mock.address, target).let {
            it.status to
                it.body
        }

    /** Returns why a server with [settings] does not start. */
    private fun refusalOf(settings: ServerSettings): String =
        assertFailsWith<StartupFailure> { MockeyServer.start(settings).close() }.message

    /** Writes [text] to the file [name] and returns the path of the file. */
    private fun file(
        name: String,
        text: String,
    ): String = Files.writeString(directory.resolve(name), text).toString()

    /** Starts a server that answers every request with [body] and [status]. */
    private fun serving(
        body: String,
        status: String = "200 OK",
    ): StubUpstream = StubUpstream(status, emptyList(), body).also { stubs += it }

    private fun stored(): Map<String, String> = TestDefinitions.contentOf(location)

    /** Defines a service that carries the tag `regression` and one that carries no tag. Each answers with its mock URL. */
    private suspend fun io.github.dnedrow.mockey.core.engine.MockeyEngine.taggedAndUntagged() {
        staticService("Tagged", "tagged", "only" to "tagged", tags = Tags.of("regression"))
        staticService("Untagged", "untagged", "only" to "untagged")
    }

    // The definitions of the definitions location

    /** The scenario "Empty location". */
    @Test
    fun aServerWhoseDefinitionsLocationDoesNotExistOrIsEmptyStartsWithoutDefinitions() {
        val withoutLocation = start(settings())
        val missing = !Files.exists(location)
        Files.createDirectory(location)
        val withEmptyLocation = start(settings())

        assertTrue(missing, "The first server started without a definitions location")
        for (server in listOf(withoutLocation, withEmptyLocation)) {
            assertEquals(Definitions.EMPTY, server.engine.definitions)
            assertEquals(404 to "No service matches the target 'orders'.", server.answerTo("/service/orders"))
        }
        assertEquals(setOf(""), stored().keys, "A server in read-only mode stores nothing")
    }

    /** The scenario "Default plan is applied at startup". */
    @Test
    fun theDefaultPlanOfTheStoredDefinitionsIsAppliedWhenTheServerStarts() {
        TestDefinitions.store(location) { orders(withDefaultPlan = true) }
        val asStored = runBlocking { FileDefinitionStorage(location).load() }.services.single()
        val before = stored()

        val server = start(settings())

        assertEquals(
            "silver",
            asStored.scenarios
                .single {
                    it.id == asStored.defaultScenarioId
                }.name,
            "The plan is stored without having been applied",
        )
        assertEquals(200 to TestDefinitions.GOLD_ORDERS, server.answerTo("/service/orders"))
        assertEquals(before, stored(), "A server in read-only mode stores nothing")
    }

    /** The scenario "Corrupt file". */
    @Test
    fun aDefinitionFileThatCannotBeReadKeepsTheServerFromStartingAndIsNamedWithWhatIsWrong() {
        TestDefinitions.store(location) { orders() }
        val scenarioFile = location.resolve("services/orders/scenarios/gold.json")
        Files.writeString(scenarioFile, """{"name": """)
        val (mockPort, adminPort) = RawHttp.freePorts(2)

        val refusal = refusalOf(settings().copy(mockPort = mockPort, adminPort = adminPort))

        val start = "The definitions cannot be loaded. The definition file $scenarioFile cannot be read: it is not valid JSON: "
        assertTrue(refusal.startsWith(start), refusal)
        assertNull(RawHttp.getIfReached(RawHttp.loopback(mockPort), WAIT_MILLIS), "The mock listener")
        assertNull(RawHttp.getIfReached(RawHttp.loopback(adminPort), WAIT_MILLIS), "The admin listener")
    }

    /** The scenario "Definitions from a newer release". */
    @Test
    fun definitionsOfANewerFormatVersionKeepTheServerFromStartingWithTheVersionsNamed() {
        TestDefinitions.store(location) { orders(withDefaultPlan = true) }
        val mainFile = location.resolve("mockey.json")
        Files.writeString(mainFile, Files.readString(mainFile).replace(""""formatVersion": 1""", """"formatVersion": 7"""))

        val refusal = refusalOf(settings())

        assertEquals(
            "The definitions cannot be loaded. The definition file $mainFile cannot be read: " +
                "it has the format version 7, and this version of Mockey reads the format version 1 only.",
            refusal,
        )
    }

    /** The scenario "Startup tag filter", as far as a client of the mock listener can tell. */
    @Test
    fun theTagFilterOfTheSettingsIsInEffectFromTheFirstRequest() {
        TestDefinitions.store(location) { taggedAndUntagged() }

        val filtering = start(settings(tagFilter = Tags.of("regression")))
        val admitting = start(settings())

        assertEquals(Tags.of("regression"), filtering.engine.tagFilter)
        assertEquals(
            listOf(200 to "tagged", 404 to "No service matches the target 'untagged'."),
            listOf("tagged", "untagged").map {
                filtering.answerTo("/service/$it")
            },
        )
        assertEquals(listOf(200 to "tagged", 200 to "untagged"), listOf("tagged", "untagged").map { admitting.answerTo("/service/$it") })
    }

    // Initial definitions in read-only mode

    /** The scenario "Start from a legacy document". The first request that the server gets is the one for the service of the document. */
    @Test
    fun inReadOnlyModeAnExportDocumentOfMockey2TakesThePlaceOfTheStoredDefinitionsInMemory() {
        StoredDefinitions.customers(location)
        val before = stored()

        val server = start(settings(file("export.xml", LegacySources.export())))

        assertEquals(200 to LegacySources.SILVER_ORDERS, server.answerTo("/service/legacy/orders"))
        assertEquals(
            listOf("Legacy Orders"),
            server.engine.definitions.services
                .map { it.name },
        )
        assertEquals(404 to "No service matches the target 'customer/7'.", server.answerTo("/service/customer/7"))
        assertTrue(server.engine.readOnly)
        assertEquals(before, stored(), "The definitions location stays as it is")
    }

    /** Checks that a server that starts with the initial definitions [source] answers a request for [target] with [body]. */
    private fun assertServes(
        source: String,
        target: String,
        body: String,
    ) {
        StoredDefinitions.customers(location)

        val server = start(settings(source))

        assertEquals(200 to body, server.answerTo(target))
        assertEquals(404, server.answerTo("/service/customer/7").first)
        assertEquals(1, server.engine.definitions.services.size)
    }

    @Test
    fun aServerStartsWithADocumentOfThisSystemInAFile() {
        assertServes(file("definitions.json", TestDefinitions.document { orders() }), "/service/orders", TestDefinitions.SILVER_ORDERS)
    }

    @Test
    fun aServerStartsWithADefinitionsDirectoryOfMockey2() {
        val legacy = LegacySources.directory(directory.resolve("legacy"))

        assertServes(legacy.toString(), "/service/directory/orders", LegacySources.DIRECTORY_ORDERS)
    }

    @Test
    fun aServerStartsWithADocumentOfThisSystemAtAUrl() {
        val server = serving(TestDefinitions.document { orders() })

        assertServes("${server.origin}/definitions.json", "/service/orders", TestDefinitions.SILVER_ORDERS)
        assertEquals(listOf("/definitions.json"), server.requests.map { it.target })
    }

    @Test
    fun aServerStartsWithAnExportDocumentOfMockey2AtAUrl() {
        val server = serving(LegacySources.export())

        assertServes("${server.origin}/export?format=xml", "/service/legacy/orders", LegacySources.SILVER_ORDERS)
        assertEquals(listOf("/export?format=xml"), server.requests.map { it.target })
    }

    @Test
    fun theDefaultPlanOfTheInitialDefinitionsIsApplied() {
        val ofThisSystem = file("definitions.json", TestDefinitions.document { orders(withDefaultPlan = true) })
        val ofMockey2 = file("export.xml", LegacySources.export(withDefaultPlan = true))

        assertEquals(200 to TestDefinitions.GOLD_ORDERS, start(settings(ofThisSystem)).answerTo("/service/orders"))
        assertEquals(200 to LegacySources.GOLD_ORDERS, start(settings(ofMockey2)).answerTo("/service/legacy/orders"))
    }

    @Test
    fun theTagFilterOfTheSettingsIsInEffectWithInitialDefinitions() {
        StoredDefinitions.customers(location)
        val source = file("definitions.json", TestDefinitions.document { taggedAndUntagged() })

        val server = start(settings(source, tagFilter = Tags.of("regression")))

        assertEquals(Tags.of("regression"), server.engine.tagFilter)
        assertEquals(
            listOf(200 to "tagged", 404 to "No service matches the target 'untagged'."),
            listOf("tagged", "untagged").map {
                server.answerTo("/service/$it")
            },
        )
    }

    @Test
    fun aDocumentOfThisSystemWithAByteOrderMarkIsTaken() {
        assertServes(
            file("definitions.json", "\uFEFF" + TestDefinitions.document { orders() }),
            "/service/orders",
            TestDefinitions.SILVER_ORDERS,
        )
    }

    // Initial definitions in writable mode

    @Test
    fun inWritableModeTheInitialDefinitionsAreStoredInADefinitionsLocationThatHoldsNoDefinitions() {
        val source = file("export.xml", LegacySources.export())

        val first = start(settings(source, readOnly = false))
        val ofFirst = first.answerTo("/service/legacy/orders")
        val writable = !first.engine.readOnly
        first.close()
        val second = start(settings())

        assertTrue(writable, "The first server is in writable mode")
        assertEquals(200 to LegacySources.SILVER_ORDERS, ofFirst)
        assertEquals(
            200 to LegacySources.SILVER_ORDERS,
            second.answerTo("/service/legacy/orders"),
            "A server that starts with what the first one stored",
        )
        assertTrue(Files.isRegularFile(location.resolve("mockey.json")), "${stored().keys}")
    }

    /** A definitions location with files that are no definitions holds no definitions. The files stay as they are. */
    @Test
    fun aDefinitionsLocationThatHoldsOtherFilesOnlyTakesTheInitialDefinitions() {
        Files.createDirectories(location.resolve(".git"))
        Files.writeString(location.resolve("README.md"), "The definitions of the order team.")
        Files.writeString(location.resolve(".git/HEAD"), "ref: refs/heads/main")
        val before = stored().filterKeys { it == "README.md" || it.startsWith(".git") }

        val server = start(settings(file("definitions.json", TestDefinitions.document { orders() }), readOnly = false))

        assertEquals(200 to TestDefinitions.SILVER_ORDERS, server.answerTo("/service/orders"))
        assertTrue(Files.isRegularFile(location.resolve("services/orders/service.json")), "${stored().keys}")
        assertEquals(before, stored().filterKeys { it in before })
    }

    /** The scenario "Stored definitions are protected", which is DEV-30. */
    @Test
    fun inWritableModeTheServerDoesNotStartWithInitialDefinitionsWhileItsDefinitionsLocationHoldsDefinitions() {
        StoredDefinitions.customers(location)
        val before = stored()
        val (mockPort, adminPort) = RawHttp.freePorts(2)
        val source = file("export.xml", LegacySources.export())

        val refusal = refusalOf(settings(source, readOnly = false).copy(mockPort = mockPort, adminPort = adminPort))

        assertEquals(
            "The definitions location $location holds definitions, and the server is in writable mode, " +
                "in which the initial definitions would be stored in their place. " +
                "The server takes initial definitions in writable mode only while the definitions location holds no definitions. " +
                "To start with the initial definitions and leave the stored definitions as they are, " +
                "start the server in read-only mode, which is the default: leave out --writable, or set MOCKEY_READ_ONLY=true. " +
                "To go on with the stored definitions, leave out --initial-definitions and MOCKEY_INITIAL_DEFINITIONS.",
            refusal,
        )
        assertEquals(before, stored(), "The stored definitions stay as they are")
        assertNull(RawHttp.getIfReached(RawHttp.loopback(mockPort), WAIT_MILLIS), "The mock listener")
        assertNull(RawHttp.getIfReached(RawHttp.loopback(adminPort), WAIT_MILLIS), "The admin listener")
        assertEquals(200 to StoredDefinitions.CUSTOMER, start(settings(readOnly = false)).answerTo("/service/customer/7"))
    }

    /**
     * A server in writable mode stores what the default plan of its definitions changes when it loads them.
     * A server that does not start has not loaded them that way.
     */
    @Test
    fun theStoredDefinitionsStayAsTheyAreAlsoWhereTheirDefaultPlanWouldChangeThem() {
        TestDefinitions.store(location) { orders(withDefaultPlan = true) }
        val before = stored()

        val refusal = refusalOf(settings(file("export.xml", LegacySources.export()), readOnly = false))
        val afterTheRefusal = stored()
        start(settings(readOnly = false)).close()

        assertTrue(refusal.startsWith("The definitions location $location holds definitions, and the server is in writable mode"), refusal)
        assertEquals(before, afterTheRefusal)
        assertTrue(before != stored(), "A server that starts in writable mode stores what the default plan changed")
    }

    /** What the server stores of a setting or of a plan is a definition as well, and a source is not read for a refusal. */
    @Test
    fun aPlanAloneIsAStoredDefinitionAndTheSourceIsNotEvenRead() {
        TestDefinitions.store(location) { plans.create { it.copy(name = "A plan without items") }.getOrThrow() }
        val before = stored()
        val source = serving(LegacySources.export())

        val refusal = refusalOf(settings("${source.origin}/export", readOnly = false))

        assertTrue(refusal.startsWith("The definitions location $location holds definitions, and the server is in writable mode"), refusal)
        assertEquals(emptyList(), source.requests)
        assertEquals(before, stored())
        assertEquals(setOf("", "mockey.json"), before.keys)
    }

    @Test
    fun initialDefinitionsThatCannotBeStoredKeepTheServerFromStarting() {
        Files.createDirectory(location)
        val forbidden = TestEnvironment.permit(location, "r-xr-xr-x") && !Files.isWritable(location)
        TestEnvironment.needs(forbidden, "a user who cannot write to every directory")

        val refusal = refusalOf(settings(file("export.xml", LegacySources.export()), readOnly = false))

        assertTrue(refusal.startsWith("The initial definitions cannot be stored in the definitions location. "), refusal)
        assertContains(refusal, location.toString())
        assertEquals(setOf(""), stored().keys)
    }

    /** The server looks at its ports first, so that it has stored nothing when it finds one in use. */
    @Test
    fun aPortInUseEndsTheStartBeforeTheInitialDefinitionsAreReadOrStored() {
        val source = serving(LegacySources.export())
        ServerSocket().use { other ->
            other.bind(RawHttp.loopback(0))

            val refusal = refusalOf(settings("${source.origin}/export", readOnly = false).copy(mockPort = other.localPort))

            assertTrue(refusal.startsWith("The mock listener cannot accept connections on 127.0.0.1:${other.localPort}: "), refusal)
            assertEquals(emptyList(), source.requests)
            assertFalse(Files.exists(location), "The definitions location")
        }
    }

    // Initial definitions that cannot be taken

    /** The scenario "Source cannot be read". */
    @Test
    fun aSourceThatDoesNotExistKeepsTheServerFromStartingAndIsNamed() {
        val missing = directory.resolve("no-such-file.xml")
        val (mockPort, adminPort) = RawHttp.freePorts(2)

        val refusals =
            listOf(
                true,
                false,
            ).map { refusalOf(settings("$missing", readOnly = it).copy(mockPort = mockPort, adminPort = adminPort)) }

        val expected =
            "The definitions cannot be read from $missing. There is no such file or directory. " +
                "The source is the setting --initial-definitions or MOCKEY_INITIAL_DEFINITIONS."
        assertEquals(listOf(expected, expected), refusals)
        assertNull(RawHttp.getIfReached(RawHttp.loopback(mockPort), WAIT_MILLIS), "The mock listener")
        assertNull(RawHttp.getIfReached(RawHttp.loopback(adminPort), WAIT_MILLIS), "The admin listener")
        assertFalse(Files.exists(location), "The definitions location")
    }

    @Test
    fun aSourceThatCannotBeReadLeavesTheStoredDefinitionsAsTheyAre() {
        StoredDefinitions.customers(location)
        val before = stored()
        val answering = serving("Sign in to read this document.", "403 Forbidden")

        val refusal = refusalOf(settings("${answering.origin}/definitions.json"))

        assertEquals(
            "The definitions cannot be read from ${answering.origin}/definitions.json. The answer has the status 403. " +
                "The source is the setting --initial-definitions or MOCKEY_INITIAL_DEFINITIONS.",
            refusal,
        )
        assertEquals(before, stored())
        assertEquals(200 to StoredDefinitions.CUSTOMER, start(settings()).answerTo("/service/customer/7"))
    }

    @Test
    fun aJsonDocumentThatIsNoDefinitionsDocumentKeepsTheServerFromStarting() {
        StoredDefinitions.customers(location)
        val before = stored()
        val documents =
            mapOf(
                """{"formatVersion": 99}""" to "it has the format version 99, and this version of Mockey reads the format version 1 only.",
                """{"services": [""" to "it is not valid JSON: ",
                """{"services": "none"}""" to "Expected JsonArray",
            )

        for ((document, problem) in documents) {
            val source = file("not-a-document.json", document)

            val refusal = refusalOf(settings(source))

            assertTrue(
                refusal.startsWith("The definitions cannot be read from $source. The document cannot be imported: $problem"),
                refusal,
            )
            assertTrue(refusal.endsWith(". The source is the setting --initial-definitions or MOCKEY_INITIAL_DEFINITIONS."), refusal)
        }
        assertEquals(before, stored())
    }

    @Test
    fun anXmlDocumentThatHoldsNoDefinitionsOfMockey2KeepsTheServerFromStarting() {
        val source = file("page.xml", "<html><body>Sign in</body></html>")

        val refusal = refusalOf(settings(source))

        assertEquals(
            "The definitions cannot be read from $source. " +
                "The document holds no definitions of Mockey 2.x: its root element is 'html', not 'mockservice'. " +
                "The source is the setting --initial-definitions or MOCKEY_INITIAL_DEFINITIONS.",
            refusal,
        )
    }

    // The log

    @Test
    fun theLogSaysWhichDefinitionsTheServerStartsWith() {
        StoredDefinitions.customers(location)
        val source = file("definitions.json", TestDefinitions.document { orders(withDefaultPlan = true) })
        val other = directory.resolve("other-location")

        val inReadOnlyMode = TestLog.of { MockeyServer.start(settings(source).copy(logLevel = LogLevel.INFO)).close() }
        val inWritableMode =
            TestLog.of {
                MockeyServer.start(settings(source, readOnly = false).copy(definitions = other, logLevel = LogLevel.INFO)).close()
            }

        val content = "1 service with 2 scenarios, 1 plan, and 0 twist configurations"
        val readOnlyMessages = TestLog.messages(inReadOnlyMode)
        val expected =
            listOf(
                "The definitions location $location holds 1 service with 1 scenario, 0 plans, and 0 twist configurations.",
                "The server starts with the initial definitions of $source, which it keeps in memory: $content.",
                "The default plan 'Gold path' is applied.",
            )
        val first = readOnlyMessages.indexOf(expected.first())
        assertEquals(expected, readOnlyMessages.drop(first).take(expected.size), inReadOnlyMode)
        assertTrue(
            readOnlyMessages.indexOf(expected.last()) <
                readOnlyMessages.indexOfFirst {
                    it.startsWith("The mock listener accepts")
                },
            inReadOnlyMode,
        )
        assertContains(TestLog.messages(inWritableMode), "The definitions location $other holds no definitions.")
        assertContains(
            TestLog.messages(inWritableMode),
            "The server starts with the initial definitions of $source, which it has stored: $content.",
        )
        assertEquals(emptyList(), TestLog.messages(inReadOnlyMode + inWritableMode, "WARN"))
    }

    @Test
    fun whatASourceOfMockey2DidNotGiveTheServerIsInTheLogAsWarnings() {
        val document =
            """
            <mockservice>
            <serviceref file="other/service.xml"/>
            <service name="Inline" url="inline" service_response_type="1">
            <request_inspector_json_rules enable_flag="true"><![CDATA[{"headers": ]]></request_inspector_json_rules>
            <scenario id="1" name="only"><scenario_response>inline</scenario_response></scenario>
            </service>
            <service name="Second" url="inline" service_response_type="1"><scenario id="1" name="only"/></service>
            </mockservice>
            """.trimIndent()
        val source = file("export.xml", document)
        var answer: Pair<Int?, String>? = null

        val log = TestLog.of { answer = start(settings(source)).answerTo("/service/inline") }

        val warnings = TestLog.messages(log, "WARN").map { it.removePrefix("Of the initial definitions: ") }
        assertEquals(200 to "inline", answer)
        assertEquals(
            listOf(
                "The file other/service.xml was not read: no directory is imported, so no file is read.",
                "The service 'Second' was not added: the service 'Inline' already uses its mock URL pattern 'inline'.",
                "The scenario 'only' was not added, because its service 'Second' was not.",
                "The service 'Inline': The request rules of the service cannot be read. The rule set is not valid JSON",
                "A document is read without the files that it refers to. " +
                    "A definitions directory of Mockey 2.x is read with its files when the directory is named, and not its main file.",
            ),
            warnings.map { it.substringBefore(": Cannot read Json element") },
        )
        assertEquals(warnings.size, TestLog.messages(log, "WARN").count { it.startsWith("Of the initial definitions: ") })
    }

    /** The log is written at the most verbose level, and the request for the document presents the credentials. */
    @Test
    fun neitherTheLogNorARefusalHoldsTheUserInformationOrTheQueryOfAUrl() {
        val password = "pw-71c0e4a95b3d"
        val key = "k3y-0d2f6b81"
        val credentials = Base64.getEncoder().encodeToString("mockey:$password".toByteArray())
        val answering = serving(TestDefinitions.document { orders() })
        val refusing = serving("", "403 Forbidden")

        fun url(server: StubUpstream) = "http://mockey:$password@${server.origin.removePrefix("http://")}/definitions.json?key=$key"
        var refusal = ""
        var answer: Pair<Int?, String>? = null

        val log =
            TestLog.of {
                answer = start(settings(url(answering)).copy(logLevel = LogLevel.TRACE)).answerTo("/service/orders")
                refusal = refusalOf(settings(url(refusing)).copy(logLevel = LogLevel.TRACE))
            }

        val shown = "http://***@${answering.origin.removePrefix("http://")}/definitions.json?***"
        assertEquals(200 to TestDefinitions.SILVER_ORDERS, answer)
        assertEquals(listOf("Basic $credentials"), answering.requests.single().headerValues("Authorization"))
        assertEquals("/definitions.json?key=$key", answering.requests.single().target)
        assertContains(TestLog.messages(log), "  initial-definitions = $shown")
        assertContains(
            TestLog.messages(log),
            "The server starts with the initial definitions of $shown, which it keeps in memory: " +
                "1 service with 2 scenarios, 0 plans, and 0 twist configurations.",
        )
        assertTrue(
            refusal.endsWith(
                "The answer has the status 403. The source is the setting --initial-definitions or MOCKEY_INITIAL_DEFINITIONS.",
            ),
            refusal,
        )
        assertTrue(log.lines().any { " TRACE " in it || " DEBUG " in it }, "The log is written at the most verbose level")
        for (secret in listOf(password, key, credentials)) {
            assertFalse(secret in log, "The log holds $secret:\n$log")
            assertFalse(secret in refusal, refusal)
        }
    }

    private companion object {
        /** How long a test waits for an answer that is not to come. */
        const val WAIT_MILLIS = 500
    }
}
