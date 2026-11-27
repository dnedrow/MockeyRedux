package io.github.dnedrow.mockey.server

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.github.dnedrow.mockey.server.TestDefinitions.orders
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.io.path.Path
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 11.10: the commands of `docs/server.md` and of `docs/migrating-from-2x.md` run as they are written.
 * Task 12.9: so do the examples of `docs/admin-api.md`. Task 14.5: so do the requests to the legacy shim
 * in the migration document.
 *
 * A reader runs the commands of a document in their order, in one directory, with the start script of the
 * distribution on the path of the shell. So does this test. Every block of a document that is marked
 * `shell` is a script, which the shell of the system runs as it stands, in a directory that holds what the
 * document says the reader has.
 *
 * - A block whose last command starts a server leaves that server running for the blocks that follow. The
 *   test goes on when the server accepts connections, and it stops the server, with the signal that the
 *   documents name, before the next server starts and at the end of the document. The server has to end
 *   with the status 0.
 * - Every other block has to end by itself with the status 0. Where a block that is marked `text` or `json`
 *   follows it directly, that is what the commands print. A time is taken for any time, an identifier that
 *   a server gives a definition for any such identifier, and the directory of the reader is `/srv/mockey`
 *   in the documents.
 *
 * The documents use the ports of the defaults, so the test needs them free.
 */
class ServerDocumentsTest {
    private val serverDocument = Path("../docs/server.md")
    private val migrationDocument = Path("../docs/migrating-from-2x.md")
    private val adminApiDocument = Path("../docs/admin-api.md")
    private val readme = Path("../README.md")

    /** The release notes of the release that the version of the build leads to: those of 3.0.0 for 3.0.0-rc.1. */
    private val releaseNotes = Path("../docs/release-notes/${ServerVersion.text.substringBefore('-')}.md")
    private val legacyFixtures = Path("../mockey-legacy/src/test/resources/legacy")
    private val distribution = Path(System.getProperty("mockey.distribution"))
    private val runnableJar = Path(System.getProperty("mockey.runnableJar"))

    /** The directory of the reader, and beside it the directory of the scripts that the blocks are. */
    private val scratch: Path = Files.createTempDirectory("mockey-documents-")
    private val directory: Path = Files.createDirectory(scratch.resolve("reader"))
    private val scripts: Path = Files.createDirectory(scratch.resolve("scripts"))

    @AfterTest
    fun deleteWhatTheTestWrote() {
        scratch.toFile().deleteRecursively()
    }

    /** A fenced block of a Markdown document: the language that its fence names, its text, and the lines of its fences. */
    private class Block(
        val language: String,
        val text: String,
        val firstLine: Int,
        val lastLine: Int,
    )

    /**
     * One section of the server document is about a real service whose certificate the Java runtime does not
     * trust. While the commands run, such a real service answers at `https://localhost:8443`, and the reader
     * has its certificate as the file `company-root.pem`.
     */
    @Test
    fun everyCommandOfTheServerDocumentRunsAsItIsWritten() {
        needsTheDefaultPorts()
        TestEnvironment.needs(
            TestEnvironment.isFree("127.0.0.1", HTTPS_PORT),
            "the port $HTTPS_PORT free, for what stands in for a real service that speaks TLS",
        )
        Files.writeString(directory.resolve("definitions.json"), TestDefinitions.document { orders() })
        Files.copy(runnableJar, directory.resolve(runnableJar.name))

        val commands = runningARealServiceWithTls(directory.resolve("company-root.pem")) { runCommandsOf(serverDocument) }

        assertEquals(Commands(blocks = 25, servers = 15, compared = 7), commands)
    }

    /**
     * The migration document imports the definitions of a Mockey 2.x, and then it sends the requests of a
     * script for Mockey 2.x to a server with the legacy shim. It reads the answers of the shim with `jq`.
     */
    @Test
    fun everyCommandOfTheMigrationDocumentRunsAsItIsWritten() {
        needsTheDefaultPorts()
        needsJq()
        TestEnvironment.needs(
            TestEnvironment.isFree("127.0.0.1", LEGACY_PORT),
            "the port $LEGACY_PORT free, for what stands in for Mockey 2.x",
        )
        legacyFixtures.resolve("directory").toFile().copyRecursively(directory.resolve("mockey-2x-definitions").toFile())
        val export = Files.copy(legacyFixtures.resolve("export.xml"), directory.resolve("export.xml"))

        val commands = runningMockey2(Files.readAllBytes(export)) { runCommandsOf(migrationDocument) }

        assertEquals(Commands(blocks = 13, servers = 3, compared = 8), commands)
    }

    /**
     * The examples of the admin API document are requests to a server that the document starts, and each
     * builds on the ones before it. They read the answers with `jq`.
     */
    @Test
    fun everyCommandOfTheAdminApiDocumentRunsAsItIsWritten() {
        needsTheDefaultPorts()
        needsJq()

        val commands = runCommandsOf(adminApiDocument)

        assertEquals(Commands(blocks = 14, servers = 2, compared = 12), commands)
    }

    /** The table of the settings in the server document is what the help of the program says, setting for setting. */
    @Test
    fun theSettingsOfTheServerDocumentAreTheOnesOfTheHelp() {
        val help = StringBuilder().also { ServerCommand(environment = { null }, output = it).execute(listOf("--help")) }.toString()
        val ofTheHelp =
            SETTING_OF_THE_HELP.findAll(help.substringAfter("Options:").substringBefore("  --version")).map { setting ->
                val (options, variable, default) = setting.destructured
                Triple(OPTION.findAll(options).map { it.value }.toList(), variable, default)
            }

        val rows = Files.readAllLines(serverDocument).filter { it.startsWith("| `--") }.map { row -> row.split("|").map(String::trim) }
        val ofTheDocument =
            rows.map {
                Triple(
                    OPTION
                        .findAll(it[1])
                        .map { o ->
                            o.value
                        }.toList(),
                    it[2].trim('`'),
                    it[3].replace("`", ""),
                )
            }

        assertEquals(ofTheHelp.toList(), ofTheDocument)
        assertEquals(ServerSettings().summary().keys.toList(), ofTheDocument.map { it.first.first().removePrefix("--") })
    }

    /**
     * The files of the program that the documents name are the ones that the build of this version delivers.
     * The README is one of the documents: its quick start starts the server from the runnable jar. So are
     * the release notes, once the release of this version has some: they list the files of the release.
     */
    @Test
    fun theArtifactsThatTheDocumentsNameAreTheOnesOfThisVersion() {
        assertContains(Files.readString(readme), "java -jar ${runnableJar.name}\n")
        val named =
            listOf(serverDocument, migrationDocument, readme, releaseNotes)
                .filter { Files.exists(it) }
                .flatMap { ARTIFACT.findAll(Files.readString(it)).map { artifact -> artifact.value }.toList() }
                .toSet()

        val distribution = "$PROGRAM_NAME-${ServerVersion.text}"
        assertEquals(setOf("$distribution.zip", "$distribution.tar.gz", runnableJar.name), named)
    }

    /**
     * The library has the version of the build as well. The document of the library and the embedded sample
     * ask for the library by that version, so that a reader and the CI job get the one that the build publishes.
     */
    @Test
    fun theLibraryThatTheDocumentsAskForIsTheOneOfThisVersion() {
        val coordinates = "implementation(\"io.github.dnedrow:mockey-core:${ServerVersion.text}\")"

        assertContains(Files.readString(Path("../docs/library.md")), coordinates)
        assertContains(Files.readString(Path("../samples/embedded/build.gradle.kts")), coordinates)
    }

    /**
     * What was run of a document: the number of its blocks of commands, the number of them that started a
     * server, and the number of them whose output was compared with the document. A test states the numbers
     * of its document, so that a block which the test does not find, or takes for something else, shows.
     */
    private data class Commands(
        val blocks: Int,
        val servers: Int,
        val compared: Int,
    )

    /** Runs the commands of [document] in their order, as the description of this class says. */
    private fun runCommandsOf(document: Path): Commands {
        val blocks = blocksOf(document)
        var commands = 0
        var servers = 0
        var compared = 0
        var server: ServerProgram.Running? = null
        try {
            blocks.forEachIndexed { index, block ->
                if (block.language != SHELL) return@forEachIndexed
                commands++
                val where = "${document.name}, line ${block.firstLine}"
                if (startsAServer(block)) {
                    server?.let { stop(it, where) }
                    server = start(block, index).also { it.listening() }
                    servers++
                } else {
                    val outcome = start(block, index).use { it.awaitEnd() }
                    assertEquals(0, outcome.status, "The commands of $where: $outcome")
                    shownOutputOf(blocks, index)?.let { shown ->
                        assertEquals(comparable(shown.text), comparable(outcome.output), "What the commands of $where print")
                        compared++
                    }
                }
            }
            server?.let { stop(it, "the end of ${document.name}") }
            server = null
        } finally {
            server?.close()
        }
        return Commands(commands, servers, compared)
    }

    /**
     * Starts the shell with the script that [block] is, in the directory of the reader. The start script of
     * the distribution is on its path, and so is the Java runtime of this test. No setting of the program
     * comes from the environment of this test.
     */
    private fun start(
        block: Block,
        index: Int,
    ): ServerProgram.Running {
        val script = scripts.resolve("block-$index.sh")
        Files.writeString(script, block.text + "\n")
        val javaHome = System.getProperty("java.home")
        return ServerProgram.startIn(directory, ServerProgram.withSignals() + listOf("sh", "-e", "$script")) { environment ->
            environment.keys.removeIf { it.startsWith("MOCKEY_") }
            environment["JAVA_HOME"] = javaHome
            environment["PATH"] = listOf(distribution.resolve("bin"), Path(javaHome, "bin"), environment["PATH"]).joinToString(":")
        }
    }

    /** Stops [server] with the signal to end, before [what], and expects it to end in order. */
    private fun stop(
        server: ServerProgram.Running,
        what: String,
    ) {
        server.signal("TERM")
        val outcome = server.use { it.awaitEnd() }
        assertEquals(0, outcome.status, "The server that ran before $what: $outcome")
    }

    /**
     * Whether the last command of [block] starts a server: it runs the program, by its start script or by
     * its runnable jar, without a command of the program and without an option that only prints something.
     */
    private fun startsAServer(block: Block): Boolean {
        val words =
            block.text
                .lines()
                .last { it.isNotBlank() && !it.trimStart().startsWith("#") }
                .trim()
                .split(Regex("\\s+"))
        val command = words.dropWhile { ASSIGNMENT.matches(it) }
        val program = command.firstOrNull() == PROGRAM_NAME || (command.firstOrNull() == "java" && "-jar" in command)
        return program && command.none { it in ENDS_BY_ITSELF }
    }

    /** The block that shows what the commands of the block [index] print: one marked `text` or `json` that follows it directly. */
    private fun shownOutputOf(
        blocks: List<Block>,
        index: Int,
    ): Block? = blocks.getOrNull(index + 1)?.takeIf { it.language in OUTPUT && it.firstLine == blocks[index].lastLine + 2 }

    /** What [text] says without what differs from run to run: the time, the identifiers, and the directory of the reader. */
    private fun comparable(text: String): String {
        val inTheDirectoryOfTheDocuments = text.trim().replace("${directory.toRealPath()}", READER)
        return inTheDirectoryOfTheDocuments.replace(TIME, "<time>").replace(IDENTIFIER, "<identifier>")
    }

    /** Returns the fenced blocks of [document]. A block that is indented, as in a list, loses that indentation. */
    private fun blocksOf(document: Path): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = mutableListOf<String>()
        var language: String? = null
        var indentation = ""
        var firstLine = 0
        Files.readAllLines(document).forEachIndexed { index, line ->
            val opened = language
            if (!line.trimStart().startsWith("```")) {
                if (opened != null) lines += line.removePrefix(indentation)
            } else if (opened == null) {
                language = line.trim().removePrefix("```")
                indentation = line.takeWhile { it == ' ' }
                firstLine = index + 1
                lines.clear()
            } else {
                blocks += Block(opened, lines.joinToString("\n"), firstLine, index + 1)
                language = null
            }
        }
        check(language == null) { "$document ends in a block that is not closed." }
        return blocks
    }

    /**
     * Runs [commands] while a server on the port [LEGACY_PORT] stands in for a running Mockey 2.x: it gives
     * [export] as its export document at the path `/export`, and nothing else.
     */
    private fun <T> runningMockey2(
        export: ByteArray,
        commands: () -> T,
    ): T {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), LEGACY_PORT), 0)
        return running(server, "/export", "text/xml;charset=utf-8", export, commands)
    }

    /**
     * Runs [commands] while a server on the port [HTTPS_PORT] stands in for a real service that speaks TLS,
     * and answers at the path `/orders`. Its certificate names `localhost`, and the server signed it itself,
     * so a runtime trusts it only after the certificate is added to a trust store. The certificate is written
     * to [certificate] in the PEM format.
     *
     * The `keytool` of the JDK that runs this test makes the key for each run, so that the repository holds
     * no key.
     */
    private fun <T> runningARealServiceWithTls(
        certificate: Path,
        commands: () -> T,
    ): T {
        val keys = scratch.resolve("real-service.p12")
        val inTheKeyStore = listOf("-alias", "service", "-keystore", "$keys", "-storepass", KEY_PASSWORD)
        val key = listOf("-keyalg", "EC", "-groupname", "secp256r1", "-validity", "2", "-storetype", "PKCS12")
        keytool(listOf("-genkeypair", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1") + key + inTheKeyStore)
        keytool(listOf("-exportcert", "-rfc", "-file", "$certificate") + inTheKeyStore)
        val password = KEY_PASSWORD.toCharArray()
        val store = Files.newInputStream(keys).use { stored -> KeyStore.getInstance("PKCS12").apply { load(stored, password) } }
        val ofTheService = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), HTTPS_PORT), 0)
        server.httpsConfigurator = HttpsConfigurator(SSLContext.getInstance("TLS").apply { init(ofTheService.keyManagers, null, null) })
        return running(server, "/orders", "text/plain", "orders of the real service\n".toByteArray(), commands)
    }

    /** Runs [commands] while [server] answers at [path] with [body], and stops the server after them. */
    private fun <T> running(
        server: HttpServer,
        path: String,
        contentType: String,
        body: ByteArray,
        commands: () -> T,
    ): T {
        server.createContext(path) { exchange ->
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(HTTP_OK, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            return commands()
        } finally {
            server.stop(0)
        }
    }

    /**
     * Runs the `keytool` of the JDK that runs this test with [arguments], and expects it to succeed. The tool
     * gets no input, so one that asks a question ends instead of waiting for an answer.
     */
    private fun keytool(arguments: List<String>) {
        val tool = Path(System.getProperty("java.home"), "bin", "keytool")
        val process = ProcessBuilder(listOf("$tool") + arguments).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(KEYTOOL_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool failed: $output" }
    }

    private fun needsTheDefaultPorts() {
        TestEnvironment.needs(!windows, "a shell, to run the commands of the documents")
        TestEnvironment.needs(
            TestEnvironment.isFree("0.0.0.0", MOCK_PORT) && TestEnvironment.isFree("0.0.0.0", ADMIN_PORT),
            "the ports $MOCK_PORT and $ADMIN_PORT free",
        )
        val curl = runCatching { ProcessBuilder("curl", "--version").start().waitFor() }.getOrNull() == 0
        TestEnvironment.needs(curl, "curl, which the documents send requests with")
    }

    private fun needsJq() {
        val jq = runCatching { ProcessBuilder("jq", "--version").start().waitFor() }.getOrNull() == 0
        TestEnvironment.needs(jq, "jq, which a document reads the answers with")
    }

    private companion object {
        const val SHELL = "shell"
        val OUTPUT = setOf("text", "json")

        /** The directory that the documents run their commands in. */
        const val READER = "/srv/mockey"

        const val MOCK_PORT = 8080
        const val ADMIN_PORT = 8081

        /** The port of the Mockey 2.x that the migration document imports from while it runs. */
        const val LEGACY_PORT = 8090

        /** The port of the real service that speaks TLS, which the server document forwards a request to. */
        const val HTTPS_PORT = 8443
        const val KEY_PASSWORD = "the-key-of-a-test"
        const val KEYTOOL_SECONDS = 60L
        const val HTTP_OK = 200

        val windows = System.getProperty("os.name").startsWith("Windows")
        val ASSIGNMENT = Regex("[A-Za-z_][A-Za-z0-9_]*=.*")
        val ENDS_BY_ITSELF = setOf("--help", "-h", "--version", "import-legacy")
        val TIME = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z""")

        /** An identifier that a server gives a service, a scenario, a plan, or a twist configuration. */
        val IDENTIFIER = Regex("""[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}""")
        val OPTION = Regex("--[a-z-]+")

        /** A file of the program with a version in its name, as a document names it. */
        val ARTIFACT = Regex("$PROGRAM_NAME-\\d[A-Za-z0-9.-]*?(\\.zip|\\.tar\\.gz|\\.jar)")

        /** A setting in the help: the line of its options, and after its description its variable and its default. */
        val SETTING_OF_THE_HELP = Regex("""\n {2}(--\S.*)\n(?: {6}.*\n)*? {6}Environment variable: (\S+)\n {6}Default: (.*)\n""")
    }
}
