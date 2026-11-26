package io.github.dnedrow.mockey.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 13.13: `docs/ui.md` says what the program does. Its commands run as they are written, and what it
 * states of the settings, the pages, and the help is what the program has.
 *
 * A reader runs the commands of the document in their order, in one directory, with the start script of the
 * distribution on the path of the shell. So does this test. Every block of the document that is marked
 * `shell` is a script, which the shell of the system runs as it stands.
 *
 * - A block whose last command starts a UI leaves that UI running for the blocks that follow. The test goes
 *   on when the UI accepts connections. It stops the UI with the signal that the document names, before
 *   the next UI starts and at the end of the document, and the UI has to end with the status 0.
 * - Every other block has to end by itself with the status 0. Where a block that is marked `text` follows
 *   it directly, that is what the commands print.
 *
 * The server of the document is a stand-in here, because this module has no server program. It listens
 * where the admin listener of a server with its defaults does. While the UI of the document runs with the
 * admin token of the document, the stand-in asks for that token, as a server does that was started with it.
 * The end-to-end check of the build runs the two programs together, see `scripts/end-to-end-check.sh`.
 *
 * The document uses the ports of the defaults, so the test needs them free.
 */
class UiDocumentsTest {
    private val document = Path("../docs/ui.md")
    private val readme = Path("../README.md")
    private val distribution = Path(System.getProperty("mockey.distribution"))
    private val runnableJar = Path(System.getProperty("mockey.runnableJar"))
    private val started = Started()

    /** The directory of the reader, and beside it the directory of the scripts that the blocks are. */
    private val scratch: Path = Files.createTempDirectory("mockey-ui-documents-")
    private val directory: Path = Files.createDirectory(scratch.resolve("reader"))
    private val scripts: Path = Files.createDirectory(scratch.resolve("scripts"))

    /** What stands in for the server of the document, while there is one. */
    private var server: FakeAdminApi? = null

    @AfterTest
    fun deleteWhatTheTestWrote() {
        server?.close()
        started.close()
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
     * What was run of the document: the number of its blocks of commands, the number of them that started a
     * UI, the number of those UIs that had the admin token, and the number of blocks whose output was
     * compared with the document. The test states the numbers of the document, so that a block which the
     * test does not find, or takes for something else, shows.
     */
    private data class Commands(
        val blocks: Int,
        val uis: Int,
        val withToken: Int,
        val compared: Int,
    )

    @Test
    fun everyCommandOfTheUiDocumentRunsAsItIsWritten() {
        needsTheDefaultPorts()
        Files.copy(runnableJar, directory.resolve(runnableJar.name))

        val commands = runCommandsOf(document)

        assertEquals(Commands(blocks = 13, uis = 7, withToken = 2, compared = 5), commands)
    }

    /** The table of the settings in the document is what the help of the program says, setting for setting. */
    @Test
    fun theSettingsOfTheUiDocumentAreTheOnesOfTheHelp() {
        val help = StringBuilder().also { UiCommand(environment = { null }, output = it).execute(listOf("--help")) }.toString()
        val ofTheHelp =
            SETTING_OF_THE_HELP.findAll(help.substringAfter("Options:").substringBefore("  --version")).map { setting ->
                val (options, variable, default) = setting.destructured
                Triple(OPTION.findAll(options).map { it.value }.toList(), variable, default)
            }

        val rows = Files.readAllLines(document).filter { it.startsWith("| `--") }.map { row -> row.split("|").map(String::trim) }
        val ofTheDocument =
            rows.map { row ->
                val options = OPTION.findAll(row[1]).map { it.value }
                Triple(options.toList(), row[2].trim('`'), row[3].replace("`", ""))
            }

        assertEquals(ofTheHelp.toList(), ofTheDocument)
        assertEquals(UiSettings().summary().keys.toList(), ofTheDocument.map { it.first.single().removePrefix("--") })
    }

    /**
     * The files of the program that the document names are the ones that the build of this version delivers.
     * So are the ones that the README names: its quick start starts the UI from the runnable jar.
     */
    @Test
    fun theArtifactsThatTheDocumentNamesAreTheOnesOfThisVersion() {
        assertContains(Files.readString(readme), "java -jar ${runnableJar.name}\n")
        val named = listOf(document, readme).flatMap { ARTIFACT.findAll(Files.readString(it)).map { artifact -> artifact.value } }.toSet()

        val distribution = "$PROGRAM_NAME-${UiVersion.text}"
        assertEquals(setOf("$distribution.zip", "$distribution.tar.gz", runnableJar.name), named)
    }

    /**
     * What the document says of the pages is what the UI has: the sections of the navigation, in their
     * order, the links that it names as the way to a page, and the numbers that it gives.
     */
    @Test
    fun whatTheDocumentSaysOfThePagesIsWhatTheUiHas() {
        val text = Files.readString(document)
        val api = started.api()
        val services = api.holdSamples()
        // The page of a service leads to the schema report when the service has a response schema.
        services[Samples.customers.id] = Samples.customers.copy(responseSchema = """{"type":"object"}""")
        val found = Crawl.from(started.browserFor(api))

        val sections = tableBelow(text, "| Section | What it's for |").map { it.first() }
        val named = tableBelow(text, "| Mockey 2.x | Mockey UI |").flatMap { row -> QUOTED.findAll(row[1]).map { it.groupValues[1] } }
        val shown = found.pages.values.map { it.page }
        val linkTexts = shown.flatMap { page -> page.select("a").eachText() }.toSet()

        assertEquals(Html.sections.map { it.label }, sections)
        assertEquals(emptyList(), named.filterNot { it in linkTexts })
        assertEquals(NAMED_LINKS, named.size)
        assertContains(text, "in ${HelpTopics.all.size} topics")
        assertContains(text, "every ${HistoryPages.REFRESH_SECONDS} seconds")
        assertContains(text, "It waits ${SHUTDOWN_GRACE_PERIOD.inWholeSeconds} seconds, in which the requests in progress finish.")
        assertContains(text, "A form holds at most ${FormReader.LIMIT / MEBIBYTE} MiB")
        assertContains(text, "no admin API of the version `${AdminClient.VERSION}`")
    }

    /** The documents that the help of the UI names as more to read are there, and so are the ones that `docs/ui.md` refers to. */
    @Test
    fun theDocumentsThatTheHelpAndTheUiDocumentReferToAreThere() {
        val ofTheHelp = DOCUMENT_OF_THE_HELP.findAll(HelpTopics.MORE_TO_READ).map { it.groupValues[1] }.toList()
        val ofTheDocument = REFERENCE.findAll(Files.readString(document)).map { it.groupValues[1] }.filterNot { it.startsWith("https://") }

        assertEquals(listOf("docs/ui.md", "docs/server.md", "docs/rules.md", "docs/admin-api.md"), ofTheHelp)
        assertEquals(emptyList(), ofTheHelp.filterNot { Files.isRegularFile(Path("..", it)) })
        assertEquals(emptyList(), ofTheDocument.toSet().filterNot { Files.isRegularFile(Path("../docs", it)) })
        assertTrue(ofTheDocument.toSet().size >= FEW_REFERENCES, "The references of the document are to be read: ${ofTheDocument.toSet()}")
    }

    /** Returns the rows of the table of [text] whose line of headings is [headings], each as its cells. */
    private fun tableBelow(
        text: String,
        headings: String,
    ): List<List<String>> {
        val lines = text.lines()
        val first = lines.indexOf(headings)
        check(first >= 0) { "The document has no table with the headings $headings" }
        val rows = lines.drop(first + 2).takeWhile { it.startsWith("|") }
        return rows.map { row -> row.trim('|').split("|").map(String::trim) }
    }

    /** Runs the commands of [document] in their order, as the description of this class says. */
    private fun runCommandsOf(document: Path): Commands {
        val blocks = blocksOf(document)
        var commands = 0
        var uis = 0
        var withToken = 0
        var compared = 0
        var ui: UiProgram.Running? = null
        serve(token = null)
        try {
            blocks.forEachIndexed { index, block ->
                if (block.language != SHELL) return@forEachIndexed
                commands++
                val where = "${document.name}, line ${block.firstLine}"
                if (startsAUi(block)) {
                    ui?.let { stop(it, where) }
                    val hasToken = TOKEN in block.text
                    serve(TOKEN.takeIf { hasToken })
                    ui = start(block, index).also { it.listening() }
                    uis++
                    if (hasToken) withToken++
                } else {
                    val outcome = start(block, index).use { it.awaitEnd() }
                    assertEquals(0, outcome.status, "The commands of $where: $outcome")
                    shownOutputOf(blocks, index)?.let { shown ->
                        assertEquals(shown.text.trim(), outcome.output.trim(), "What the commands of $where print")
                        compared++
                    }
                }
            }
            ui?.let { stop(it, "the end of ${document.name}") }
            ui = null
        } finally {
            ui?.close()
        }
        assertEquals(emptyList(), server?.violations.orEmpty())
        return Commands(commands, uis, withToken, compared)
    }

    /** Lets a server stand where the server of the document is, one that asks for [token] when there is one. */
    private fun serve(token: String?) {
        server?.let {
            assertEquals(emptyList(), it.violations)
            it.close()
        }
        server = FakeAdminApi(token, ADMIN_PORT).also { it.holdSamples() }
    }

    /**
     * Starts the shell with the script that [block] is, in the directory of the reader. The start script of
     * the distribution is on its path, and so is the Java runtime of this test. No setting of the program
     * comes from the environment of this test.
     */
    private fun start(
        block: Block,
        index: Int,
    ): UiProgram.Running {
        val script = scripts.resolve("block-$index.sh")
        Files.writeString(script, block.text + "\n")
        val javaHome = System.getProperty("java.home")
        return UiProgram.startIn(directory, UiProgram.withSignals() + listOf("sh", "-e", "$script")) { environment ->
            environment.keys.removeIf { it.startsWith("MOCKEY_") }
            environment["JAVA_HOME"] = javaHome
            environment["PATH"] = listOf(distribution.resolve("bin"), Path(javaHome, "bin"), environment["PATH"]).joinToString(":")
        }
    }

    /** Stops [ui] with the signal to end, before [what], and expects it to end in order. */
    private fun stop(
        ui: UiProgram.Running,
        what: String,
    ) {
        ui.signal("TERM")
        val outcome = ui.use { it.awaitEnd() }
        assertEquals(0, outcome.status, "The UI that ran before $what: $outcome")
    }

    /**
     * Whether the last command of [block] starts a UI: it runs the program, by its start script or by its
     * runnable jar, without an option that only prints something.
     */
    private fun startsAUi(block: Block): Boolean {
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

    /** The block that shows what the commands of the block [index] print: one marked `text` that follows it directly. */
    private fun shownOutputOf(
        blocks: List<Block>,
        index: Int,
    ): Block? = blocks.getOrNull(index + 1)?.takeIf { it.language == OUTPUT && it.firstLine == blocks[index].lastLine + 2 }

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

    private fun needsTheDefaultPorts() {
        TestEnvironment.needs(!windows, "a shell, to run the commands of the document")
        TestEnvironment.needs(
            TestEnvironment.isFree("127.0.0.1", ADMIN_PORT) && TestEnvironment.isFree("0.0.0.0", UI_PORT),
            "the ports $ADMIN_PORT and $UI_PORT free",
        )
        val curl = runCatching { ProcessBuilder("curl", "--version").start().waitFor() }.getOrNull() == 0
        TestEnvironment.needs(curl, "curl, which the document sends requests with")
    }

    private companion object {
        const val SHELL = "shell"
        const val OUTPUT = "text"

        /** The port of the admin listener of a server with its defaults, and the port of a UI with its defaults. */
        const val ADMIN_PORT = 8081
        const val UI_PORT = 8082

        /** The admin token of the server in the document. */
        const val TOKEN = "a-long-text-that-nobody-guesses"

        /** The number of links that the document names as the way to what a page of Mockey 2.x did. */
        const val NAMED_LINKS = 7
        const val FEW_REFERENCES = 5
        const val MEBIBYTE = 1024 * 1024

        val windows = System.getProperty("os.name").startsWith("Windows")
        val ASSIGNMENT = Regex("[A-Za-z_][A-Za-z0-9_]*=.*")
        val ENDS_BY_ITSELF = setOf("--help", "-h", "--version")
        val OPTION = Regex("--[a-z-]+")

        /** A file of the program with a version in its name, as the document names it. */
        val ARTIFACT = Regex("$PROGRAM_NAME-\\d[A-Za-z0-9.-]*?(\\.zip|\\.tar\\.gz|\\.jar)")

        /** A setting in the help: the line of its options, and after its description its variable and its default. */
        val SETTING_OF_THE_HELP = Regex("""\n {2}(--\S.*)\n(?: {6}.*\n)*? {6}Environment variable: (\S+)\n {6}Default: (.*)\n""")

        /** What a cell of a table names in quotation marks, which is what a link of the UI says. */
        val QUOTED = Regex(""""([^"]+)"""")

        /** A document that the help names, by its path in the sources. */
        val DOCUMENT_OF_THE_HELP = Regex("""`(docs/[a-z0-9-]+\.md)`""")

        /** What a link of a Markdown document refers to. */
        val REFERENCE = Regex("""\]\(([^)#]+)(?:#[^)]*)?\)""")
    }
}
