package io.github.dnedrow.mockey.server

import com.sun.net.httpserver.HttpServer
import io.github.dnedrow.mockey.server.TestDefinitions.orders
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 11.10: the commands of `docs/server.md` and of `docs/migrating-from-2x.md` run as they are written.
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
 *   follows it directly, that is what the commands print. A time is taken for any time, and the directory
 *   of the reader is `/srv/mockey` in the documents.
 *
 * The documents use the ports of the defaults, so the test needs them free.
 */
class ServerDocumentsTest {
    private val serverDocument = Path("../docs/server.md")
    private val migrationDocument = Path("../docs/migrating-from-2x.md")
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

    @Test
    fun everyCommandOfTheServerDocumentRunsAsItIsWritten() {
        needsTheDefaultPorts()
        Files.writeString(directory.resolve("definitions.json"), TestDefinitions.document { orders() })
        Files.copy(runnableJar, directory.resolve(runnableJar.name))

        val commands = runCommandsOf(serverDocument)

        assertEquals(Commands(blocks = 18, servers = 12, compared = 4), commands)
    }

    @Test
    fun everyCommandOfTheMigrationDocumentRunsAsItIsWritten() {
        needsTheDefaultPorts()
        TestEnvironment.needs(
            TestEnvironment.isFree("127.0.0.1", LEGACY_PORT),
            "the port $LEGACY_PORT free, for what stands in for Mockey 2.x",
        )
        legacyFixtures.resolve("directory").toFile().copyRecursively(directory.resolve("mockey-2x-definitions").toFile())
        val export = Files.copy(legacyFixtures.resolve("export.xml"), directory.resolve("export.xml"))

        val commands = runningMockey2(Files.readAllBytes(export)) { runCommandsOf(migrationDocument) }

        assertEquals(Commands(blocks = 6, servers = 2, compared = 2), commands)
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

    /** The files of the program that the documents name are the ones that the build of this version delivers. */
    @Test
    fun theArtifactsThatTheDocumentsNameAreTheOnesOfThisVersion() {
        val named =
            listOf(serverDocument, migrationDocument)
                .flatMap { ARTIFACT.findAll(Files.readString(it)).map { artifact -> artifact.value }.toList() }
                .toSet()

        val distribution = "$PROGRAM_NAME-${ServerVersion.text}"
        assertEquals(setOf("$distribution.zip", "$distribution.tar.gz", runnableJar.name), named)
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

    /** What [text] says without what differs from run to run: the time, and the directory of the reader. */
    private fun comparable(text: String): String = text.trim().replace("${directory.toRealPath()}", READER).replace(TIME, "<time>")

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
        server.createContext("/export") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/xml;charset=utf-8")
            exchange.sendResponseHeaders(HTTP_OK, export.size.toLong())
            exchange.responseBody.use { it.write(export) }
        }
        server.start()
        try {
            return commands()
        } finally {
            server.stop(0)
        }
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

    private companion object {
        const val SHELL = "shell"
        val OUTPUT = setOf("text", "json")

        /** The directory that the documents run their commands in. */
        const val READER = "/srv/mockey"

        const val MOCK_PORT = 8080
        const val ADMIN_PORT = 8081

        /** The port of the Mockey 2.x that the migration document imports from while it runs. */
        const val LEGACY_PORT = 8090
        const val HTTP_OK = 200

        val windows = System.getProperty("os.name").startsWith("Windows")
        val ASSIGNMENT = Regex("[A-Za-z_][A-Za-z0-9_]*=.*")
        val ENDS_BY_ITSELF = setOf("--help", "-h", "--version", "import-legacy")
        val TIME = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z""")
        val OPTION = Regex("--[a-z-]+")

        /** A file of the program with a version in its name, as a document names it. */
        val ARTIFACT = Regex("$PROGRAM_NAME-\\d[A-Za-z0-9.-]*?(\\.zip|\\.tar\\.gz|\\.jar)")

        /** A setting in the help: the line of its options, and after its description its variable and its default. */
        val SETTING_OF_THE_HELP = Regex("""\n {2}(--\S.*)\n(?: {6}.*\n)*? {6}Environment variable: (\S+)\n {6}Default: (.*)\n""")
    }
}
