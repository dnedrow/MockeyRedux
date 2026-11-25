package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.legacy.xml.LegacyImportResult
import io.github.dnedrow.mockey.legacy.xml.LegacyXmlIssueKind
import io.github.dnedrow.mockey.server.TestDefinitions.orders
import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * How a source of definitions is read: a file, a directory, and a URL, with a document of this system and
 * with definitions of Mockey 2.x, and what is said of a source from which nothing can be read.
 */
class DefinitionsSourceTest {
    private val directory: Path = Files.createTempDirectory("mockey-source-test")
    private val stubs = mutableListOf<StubUpstream>()

    @AfterTest
    fun removeWhatTheTestMade() {
        stubs.forEach(StubUpstream::close)
        directory.toFile().deleteRecursively()
    }

    /** Writes [content] to the file [name] and returns the path of the file. */
    private fun file(
        name: String,
        content: String,
    ): String = file(name, content.toByteArray())

    private fun file(
        name: String,
        content: ByteArray,
    ): String = Files.write(directory.resolve(name), content).toString()

    /** Starts a server that answers every request with [status], [headers], and [body]. */
    private fun serving(
        body: String,
        status: String = "200 OK",
        headers: List<String> = emptyList(),
        beforeAnswer: () -> Unit = {},
    ): StubUpstream = StubUpstream(status, headers, body, beforeAnswer).also { stubs += it }

    private fun documentOf(source: String): String = assertIs<SourceDefinitions.Document>(DefinitionsSource(source).read()).text

    private fun legacyOf(source: String): SourceDefinitions.Legacy = assertIs<SourceDefinitions.Legacy>(DefinitionsSource(source).read())

    private fun definitionsOf(source: String): LegacyImportResult = legacyOf(source).result

    /** Returns why nothing can be read from [source], without the sentence that names the source. */
    private fun reasonOf(source: DefinitionsSource): String {
        val failure = assertFailsWith<UnreadableSource> { source.read() }
        val start = "The definitions cannot be read from ${source.shown}. "
        assertTrue(failure.message!!.startsWith(start), failure.message)
        return failure.message!!.removePrefix(start)
    }

    private fun reasonOf(source: String): String = reasonOf(DefinitionsSource(source))

    // The three places of a source

    @Test
    fun aFileWithADocumentOfThisSystemGivesTheTextOfTheDocument() {
        val document = TestDefinitions.document { orders() }

        val read = documentOf(file("definitions.json", document))

        assertEquals(document, read)
        assertTrue(read.startsWith("{") && "\"Orders\"" in read, read)
    }

    @Test
    fun aFileWithAnExportDocumentOfMockey2GivesTheDefinitionsOfTheDocument() {
        val read = legacyOf(file("export.xml", LegacySources.export(withDefaultPlan = true)))

        val service =
            read.result.definitions.services
                .single()
        assertEquals(emptyList(), read.result.issues)
        assertNull(read.advice)
        assertEquals("Legacy Orders" to "legacy/orders", service.name to service.mockUrl)
        assertEquals(listOf(LegacySources.SILVER_ORDERS, LegacySources.GOLD_ORDERS), service.scenarios.map { it.responseBody })
        assertEquals(
            listOf("Gold path"),
            read.result.definitions.plans
                .map { it.name },
        )
        assertNotNull(read.result.definitions.settings.defaultPlanId)
    }

    @Test
    fun aDefinitionsDirectoryOfMockey2GivesItsDefinitionsWithTheFilesThatItsMainFileRefersTo() {
        val read = legacyOf(LegacySources.directory(directory.resolve("legacy")).toString())

        val service =
            read.result.definitions.services
                .single()
        assertEquals(emptyList(), read.result.issues)
        assertNull(read.advice)
        assertEquals("Directory Orders" to "directory/orders", service.name to service.mockUrl)
        assertEquals(listOf("list" to LegacySources.DIRECTORY_ORDERS), service.scenarios.map { it.name to it.responseBody })
    }

    @Test
    fun aUrlGivesTheDocumentThatItsServerAnswersWith() {
        val document = TestDefinitions.document { orders() }
        val ofThisSystem = serving(document)
        val ofMockey2 = serving(LegacySources.export())

        val read = documentOf("${ofThisSystem.origin}/definitions/current.json?version=3&draft")
        val legacy = definitionsOf("${ofMockey2.origin}/export")

        val request = ofThisSystem.requests.single()
        assertEquals(document, read)
        assertEquals(listOf("Legacy Orders"), legacy.definitions.services.map { it.name })
        assertEquals("GET" to "/definitions/current.json?version=3&draft", request.method to request.target)
        assertEquals(emptyList(), request.headerValues("Authorization"))
        assertEquals("GET" to "/export", ofMockey2.requests.single().let { it.method to it.target })
    }

    // The two kinds of document

    /** White space and a byte order mark can come before the first character. XML allows no white space before its declaration. */
    @Test
    fun theFirstCharacterOfADocumentTellsWhichKindItIs() {
        val json = TestDefinitions.document { orders() }
        val xml = """<mockservice><service name="Legacy Orders" url="legacy/orders"/></mockservice>"""
        val byteOrderMark = "\uFEFF"
        val ofThisSystem = listOf(json, " \r\n\t$json", byteOrderMark + json)
        val ofMockey2 = listOf(xml, " \r\n\t$xml", byteOrderMark + xml, byteOrderMark + LegacySources.export())

        val texts = ofThisSystem.mapIndexed { index, text -> documentOf(file("$index.json", text)) }
        val services = ofMockey2.mapIndexed { index, text -> definitionsOf(file("$index.xml", text)).definitions.services }

        assertEquals(ofThisSystem, texts)
        assertEquals(List(4) { listOf("Legacy Orders") }, services.map { ofDocument -> ofDocument.map { it.name } })
    }

    @Test
    fun anExportDocumentOfMockey2IsReadInTheCharsetThatItDeclares() {
        val name = "Bestellungen für Zürich"

        fun document(declaration: String) = """$declaration<mockservice><service name="$name" url="orders"/></mockservice>"""
        val latin1 = document("""<?xml version="1.0" encoding="ISO-8859-1"?>""").toByteArray(Charsets.ISO_8859_1)
        val utf16 = document("""<?xml version="1.0" encoding="UTF-16"?>""").toByteArray(Charsets.UTF_16)
        val utf8 = document("").toByteArray(Charsets.UTF_8)

        val read = listOf(latin1, utf16, utf8).mapIndexed { index, bytes -> definitionsOf(file("$index.xml", bytes)) }

        assertEquals(List(3) { listOf(name) }, read.map { result -> result.definitions.services.map { it.name } })
        assertEquals(List(3) { emptyList<Any>() }, read.map { it.issues })
    }

    @Test
    fun aDocumentOfMockey2IsReadWithoutTheFilesThatItRefersTo() {
        val legacy = LegacySources.directory(directory.resolve("legacy"))
        val referring =
            """<mockservice><serviceref file="mockey_def_depot/directoryorders/directoryorders.xml"/>""" +
                """<service name="Inline" url="inline"/></mockservice>"""

        val read = legacyOf(file("legacy/referring.xml", referring))
        val ofMainFile = reasonOf(legacy.resolve("mock_service_definitions.xml").toString())

        val notRead =
            "The file mockey_def_depot/directoryorders/directoryorders.xml was not read: " +
                "no directory is imported, so no file is read."
        val advice =
            "A document is read without the files that it refers to. " +
                "A definitions directory of Mockey 2.x is read with its files when the directory is named, and not its main file."
        assertEquals(
            listOf("Inline"),
            read.result.definitions.services
                .map { it.name },
        )
        assertEquals(listOf(notRead), read.result.issues.map { it.message })
        assertEquals(advice, read.advice)
        assertEquals("$notRead $advice", ofMainFile)
    }

    @Test
    fun aDirectoryIsReadWithoutWhatIsOutsideOfItAndThereIsNoOtherWayToReadThat() {
        val legacy = directory.resolve("legacy")
        Files.createDirectories(legacy)
        file("outside.xml", """<mockservice><service name="Outside" url="outside"/></mockservice>""")
        file(
            "legacy/mock_service_definitions.xml",
            """<mockservice><serviceref file="../outside.xml"/><service name="Inside" url="inside"/></mockservice>""",
        )

        val read = legacyOf(legacy.toString())

        assertEquals(
            listOf("Inside"),
            read.result.definitions.services
                .map { it.name },
        )
        assertEquals(listOf(LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT), read.result.issues.map { it.kind })
        assertNull(read.advice)
    }

    @Test
    fun aDocumentOfMockey2WithoutDefinitionsIsASourceOfNoDefinitions() {
        val empty = definitionsOf(file("empty.xml", "<mockservice/>"))
        val dangling = definitionsOf(file("dangling.xml", """<mockservice default_service_plan_id="7"/>"""))

        assertEquals(Definitions.EMPTY to emptyList(), empty.definitions to empty.issues)
        assertEquals(Definitions.EMPTY, dangling.definitions)
        assertEquals(listOf(LegacyXmlIssueKind.DANGLING_REFERENCE), dangling.issues.map { it.kind })
    }

    // Sources from which nothing can be read

    @Test
    fun aFileThatDoesNotExistIsNamed() {
        val missing = directory.resolve("no-such-file.xml").toString()

        val failure = assertFailsWith<UnreadableSource> { DefinitionsSource(missing).read() }

        assertEquals("The definitions cannot be read from $missing. There is no such file or directory.", failure.message)
    }

    @Test
    fun aFileThatMayNotBeReadIsNamed() {
        val secret = directory.resolve("secret.json")
        Files.writeString(secret, TestDefinitions.document { orders() })
        val forbidden = TestEnvironment.permit(secret, "---------") && !Files.isReadable(secret)
        TestEnvironment.needs(forbidden, "a user who cannot read every file")

        assertEquals("Access to the file is denied.", reasonOf(secret.toString()))
    }

    @Test
    fun aDirectoryWithoutTheMainFileOfMockey2IsNoSource() {
        val empty = Files.createDirectory(directory.resolve("empty"))
        val ofThisSystem = directory.resolve("stored")
        TestDefinitions.store(ofThisSystem) { orders() }

        val reason = "The directory holds no file mock_service_definitions.xml, which a definitions directory of Mockey 2.x has."
        assertEquals(reason, reasonOf(empty.toString()))
        assertEquals(reason, reasonOf(ofThisSystem.toString()))
        assertTrue(Files.exists(ofThisSystem.resolve("mockey.json")), "The directory holds definitions of this system")
    }

    @Test
    fun aDocumentThatIsEmptyOrOfNeitherKindIsNoSource() {
        val neither =
            "The document is neither a definitions document of this system, which is a JSON object, " +
                "nor one of Mockey 2.x, which is XML."

        assertEquals("The document is empty.", reasonOf(file("empty", "")))
        assertEquals("The document is empty.", reasonOf(file("blank", " \n\t\r\n")))
        assertEquals(neither, reasonOf(file("yaml", "services:\n  - name: Orders\n")))
        assertEquals(neither, reasonOf(file("array", """[{"name": "Orders"}]""")))
        assertEquals(neither, reasonOf("${serving("moved").origin}/definitions"))
    }

    @Test
    fun aDocumentOfThisSystemThatIsNoTextInUtf8IsNoSource() {
        val inLatin1 = """{"services": [{"name": "Bestellungen für Zürich"}]}""".toByteArray(Charsets.ISO_8859_1)

        assertEquals("The document is no text in UTF-8.", reasonOf(file("latin1.json", inLatin1)))
    }

    @Test
    fun anXmlDocumentOfWhichNothingCanBeReadIsNoSource() {
        val malformed = reasonOf(file("malformed.xml", """<mockservice><service name="Orders" """))
        val other = reasonOf(file("other.xml", "<html><body>Sign in</body></html>"))
        val withDocumentType =
            reasonOf(file("doctype.xml", """<!DOCTYPE mockservice [<!ENTITY e SYSTEM "file:///etc/hosts">]><mockservice/>"""))

        assertTrue(malformed.startsWith("The document is not well-formed XML in line 1: "), malformed)
        assertEquals("The document holds no definitions of Mockey 2.x: its root element is 'html', not 'mockservice'.", other)
        assertTrue(withDocumentType.startsWith("The document was not read: it declares a document type"), withDocumentType)
    }

    @Test
    fun aDefinitionsDirectoryOfMockey2OfWhichNothingCanBeReadIsNoSource() {
        val legacy = directory.resolve("legacy")
        Files.createDirectories(legacy)
        file("legacy/mock_service_definitions.xml", """<mockservice><serviceref file="gone.xml"/></mockservice>""")

        assertEquals("The file gone.xml does not exist.", reasonOf(legacy.toString()))
    }

    // URLs from which nothing can be read

    @Test
    fun aUrlThatTheServerDoesNotReadIsNoSource() {
        assertEquals("The server reads a URL with the scheme http or https only.", reasonOf("ftp://files.example/definitions.xml"))
        assertEquals("The server reads a URL with the scheme http or https only.", reasonOf("file:///tmp/definitions.xml"))
        assertEquals("The URL names no host.", reasonOf("http:///definitions.xml"))
        assertEquals("That is no URL.", reasonOf("https://files.example/my definitions.xml"))
    }

    @Test
    fun anAnswerWithAnotherStatusThanSuccessIsNoDocument() {
        val document = TestDefinitions.document { orders() }

        val reasons =
            listOf(
                "404 Not Found",
                "401 Unauthorized",
                "500 Internal Server Error",
            ).map { reasonOf("${serving(document, it).origin}/x") }

        assertEquals(listOf("The answer has the status 404.", "The answer has the status 401.", "The answer has the status 500."), reasons)
    }

    @Test
    fun aServerThatAcceptsNoConnectionIsNamed() {
        val (port) = RawHttp.freePorts(1)

        assertEquals("No connection to 127.0.0.1:$port could be made.", reasonOf("http://127.0.0.1:$port/definitions.json"))
    }

    @Test
    fun aServerThatDoesNotAnswerInTimeIsNotWaitedFor() {
        val released = CountDownLatch(1)
        val silent = serving(TestDefinitions.document { orders() }, beforeAnswer = { released.await() })

        try {
            val source = DefinitionsSource("${silent.origin}/definitions.json", answerTimeout = 300.milliseconds)
            val (reason, time) = measureTimedValue { reasonOf(source) }

            assertEquals("No answer arrived within 300ms.", reason)
            assertEquals(1, silent.requests.size)
            assertTrue(time < NOT_WAITED_FOR, "The server waited $time for the answer")
        } finally {
            released.countDown()
        }
    }

    /** The server sends the start of its answer at once and never the rest. The time is the one for the complete answer. */
    @Test
    fun aServerThatDoesNotCompleteItsAnswerInTimeIsNotWaitedFor() {
        ServerSocket().use { stalling ->
            stalling.bind(RawHttp.loopback(0))
            thread(isDaemon = true, name = "stalling-server") {
                try {
                    stalling.accept().use { connection ->
                        connection.getInputStream().read()
                        connection.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4096\r\n\r\n{\"services\": [".toByteArray())
                        connection.getOutputStream().flush()
                        connection.getInputStream().readAllBytes()
                    }
                } catch (_: IOException) {
                    // The test has ended.
                }
            }

            val source = DefinitionsSource("http://127.0.0.1:${stalling.localPort}/definitions.json", answerTimeout = 300.milliseconds)
            val (reason, time) = measureTimedValue { reasonOf(source) }

            assertEquals("No answer arrived within 300ms.", reason)
            assertTrue(time < NOT_WAITED_FOR, "The server waited $time for the rest of the answer")
        }
    }

    /** The server ends the connection without an answer. What the client of the Java runtime says of that is passed on. */
    @Test
    fun aRequestThatFailsIsExplainedWithWhatTheFailureSays() {
        ServerSocket().use { hangingUp ->
            hangingUp.bind(RawHttp.loopback(0))
            thread(isDaemon = true, name = "server-that-hangs-up") {
                try {
                    while (true) hangingUp.accept().close()
                } catch (_: IOException) {
                    // The test has ended.
                }
            }

            val reason = reasonOf("http://127.0.0.1:${hangingUp.localPort}/definitions.json")

            assertTrue(reason.startsWith("The request failed (") && reason.endsWith(")."), reason)
            assertTrue(reason.length > "The request failed ().".length, reason)
        }
    }

    /**
     * No failure of the client of the Java runtime is known to name the URL of its request. Should one do
     * so, the user information and the query of the URL stay out of what is said.
     */
    @Test
    fun whatAFailureSaysIsPassedOnWithoutTheUserInformationAndTheQueryOfTheUrl() {
        val url = "http://mockey:p%40ss-4e1b@files.example:8443/definitions.json?key=k3y-52ab07&draft"
        val source = DefinitionsSource(url)
        val failure = IOException("GET $url failed for mockey:p@ss-4e1b with key=k3y-52ab07&draft, also as mockey:p%40ss-4e1b")

        val explanation = source.explanationOf(failure, URI(url))

        assertEquals(
            "The request failed (GET http://***@files.example:8443/definitions.json?*** failed for *** with ***, also as ***).",
            explanation,
        )
        assertEquals("The request failed (IOException).", source.explanationOf(IOException(), URI(url)))
        assertEquals(
            "No connection to files.example:8443 could be made.",
            source.explanationOf(ConnectException("refused: $url"), URI(url)),
        )
    }

    // Where a request goes, and with what

    @Test
    fun aRequestWithoutCredentialsIsFollowedToAnotherUrl() {
        val document = TestDefinitions.document { orders() }
        val holding = serving(document)
        val moved = serving("", "302 Found", listOf("Location: ${holding.origin}/now/here.json"))

        val read = documentOf("${moved.origin}/definitions.json")

        assertEquals(document, read)
        assertEquals(listOf("/definitions.json"), moved.requests.map { it.target })
        assertEquals(listOf("/now/here.json"), holding.requests.map { it.target })
    }

    @Test
    fun theUserInformationOfAUrlIsPresentedAsCredentials() {
        val document = TestDefinitions.document { orders() }
        val server = serving(document)
        val address = server.origin.removePrefix("http://")

        val read = documentOf("http://mockey:p%40ss%3Aword@$address/definitions.json?version=3")

        val request = server.requests.single()
        assertEquals(document, read)
        assertEquals("/definitions.json?version=3", request.target)
        assertEquals(
            listOf("Basic " + Base64.getEncoder().encodeToString("mockey:p@ss:word".toByteArray())),
            request.headerValues("Authorization"),
        )
        assertEquals(listOf(address), request.headerValues("Host"))
    }

    @Test
    fun aRequestWithCredentialsIsNotFollowedToAnotherUrl() {
        val other = serving(TestDefinitions.document { orders() })
        val moved = serving("", "302 Found", listOf("Location: ${other.origin}/now/here.json"))

        val reason = reasonOf("http://mockey:password@${moved.origin.removePrefix("http://")}/definitions.json")

        assertEquals("The answer has the status 302, which leads to another URL, and the server did not go there.", reason)
        assertEquals(1, moved.requests.size)
        assertEquals(emptyList(), other.requests)
    }

    @Test
    fun nothingThatIsSaidOfAUrlHoldsItsUserInformationOrItsQuery() {
        val (port) = RawHttp.freePorts(1)
        val released = CountDownLatch(1)
        val refusing = serving("", "403 Forbidden").origin.removePrefix("http://")
        val silent = serving("", beforeAnswer = { released.await() }).origin.removePrefix("http://")
        val neither = serving("neither kind of document").origin.removePrefix("http://")
        val secrets = listOf("s3cr3t-9d41", "k3y-52ab07")

        fun url(address: String) = "http://mockey:s3cr3t-9d41@$address/definitions.json?key=k3y-52ab07"
        val sources =
            listOf(
                DefinitionsSource(url("127.0.0.1:$port")),
                DefinitionsSource(url(refusing)),
                DefinitionsSource(url(silent), answerTimeout = 300.milliseconds),
                DefinitionsSource(url(neither)),
                DefinitionsSource(url("127.0.0.1:$port") + " and a space"),
                DefinitionsSource(url("").replace("http://", "ftp://")),
            )

        try {
            for (source in sources) {
                val failure = assertFailsWith<UnreadableSource> { source.read() }
                val said = generateSequence<Throwable>(failure) { it.cause }.joinToString(" ") { "$it" } + source.shown

                assertTrue("://***@" in source.shown && source.shown.contains("/definitions.json?***"), source.shown)
                assertTrue(failure.message!!.startsWith("The definitions cannot be read from ${source.shown}. "), failure.message)
                for (secret in secrets) assertFalse(secret in said, said)
            }
        } finally {
            released.countDown()
        }
    }

    private companion object {
        /** The longest time that a request with a time limit of 300 ms takes, on a machine that is busy with other things. */
        val NOT_WAITED_FOR = 5.seconds
    }
}
