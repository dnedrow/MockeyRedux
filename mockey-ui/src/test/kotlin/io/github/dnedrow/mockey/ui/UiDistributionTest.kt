package io.github.dnedrow.mockey.ui

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipFile
import kotlin.io.path.Path
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 13.13: the UI program as the build delivers it, which is the distribution with its start scripts and
 * the runnable jar. The build puts both where the system properties `mockey.distribution` and
 * `mockey.runnableJar` say.
 *
 * The program runs on the Java runtime of this test, which the build takes from its toolchain and which is a
 * Java 21 runtime. It gets nothing from its environment that tells a runtime which classes or options to
 * take, so it runs as it does on a machine that has a Java 21 runtime and nothing else.
 *
 * The server that the UI manages is a stand-in here, because this module has no server program. Task 15.1
 * runs the two programs together.
 */
class UiDistributionTest {
    private val distribution = Path(System.getProperty("mockey.distribution"))
    private val runnableJar = Path(System.getProperty("mockey.runnableJar"))
    private val api = FakeAdminApi()
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    @AfterTest
    fun stopTheServer() {
        api.close()
    }

    /** The answer to a request for a page: its status, and the page. */
    private class Loaded(
        val status: Int,
        val page: Document,
    ) {
        val heading: String
            get() = page.select("main h1").text()
    }

    private fun load(url: String): Loaded {
        val response = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        return Loaded(response.statusCode(), Jsoup.parse(response.body(), url))
    }

    /** The scenario "Plain Java runtime", for the UI. */
    @Test
    fun theStartScriptOfTheDistributionRunsTheUiOnAPlainJavaRuntime() {
        TestEnvironment.needs(!windows, "a shell, to run the start script of the distribution")

        assertShowsTheOverviewAndTheHelp(
            listOf("${distribution.resolve("bin/$PROGRAM_NAME")}"),
            mapOf("JAVA_HOME" to System.getProperty("java.home")),
        )
    }

    @Test
    fun theRunnableJarRunsTheUiOnAPlainJavaRuntime() {
        assertShowsTheOverviewAndTheHelp(listOf(UiProgram.java, "-jar", "$runnableJar"), emptyMap())
    }

    @Test
    fun theDistributionHasAStartScriptForUnixAndOneForWindowsAndTheLibrariesOfTheProgram() {
        val libraries = namesIn(distribution.resolve("lib"))

        assertEquals(listOf("LICENSE", "bin", "lib"), namesIn(distribution))
        assertEquals(listOf(PROGRAM_NAME, "$PROGRAM_NAME.bat"), namesIn(distribution.resolve("bin")))
        assertTrue(windows || distribution.resolve("bin/$PROGRAM_NAME").isExecutable(), "The start script can be executed")
        assertContains(libraries, "$PROGRAM_NAME-${UiVersion.text}.jar")
        assertTrue(libraries.all { it.endsWith(".jar") }, "$libraries")
        // The UI is a client of the admin API. It comes with the wire types of the API, and without the engine of the server.
        assertContains(libraries, "mockey-api-${UiVersion.text}.jar")
        assertEquals(emptyList(), libraries.filter { OF_THE_SERVER.containsMatchIn(it) })
    }

    /** The program runs without native libraries, as the server does. */
    @Test
    fun neitherTheDistributionNorTheRunnableJarHoldsANativeLibrary() {
        val jars = distribution.resolve("lib").listDirectoryEntries() + listOf(runnableJar)

        val native = jars.associate { it.name to entriesOf(it).filter(NATIVE_LIBRARY::containsMatchIn) }.filterValues { it.isNotEmpty() }

        assertEquals(emptyMap(), native)
    }

    /**
     * The requirement "Self-hosted assets", for the program as it is delivered: the jar of the UI holds the
     * stylesheet, the icon, and the script, and the license of the script comes with the script.
     */
    @Test
    fun theProgramComesWithTheFilesOfItsPagesAndWithTheLicenseOfTheScript() {
        val ofTheUi = distribution.resolve("lib/$PROGRAM_NAME-${UiVersion.text}.jar")
        val assets = "io/github/dnedrow/mockey/ui/assets"
        val files = listOf("favicon.svg", "htmx-LICENSE.txt", "htmx.min.js", "mockey.css").map { "$assets/$it" }

        for (jar in listOf(ofTheUi, runnableJar)) {
            assertEquals(files, entriesOf(jar).filter { it.startsWith("$assets/") }.sorted(), jar.name)
        }
        assertContains(entriesOf(runnableJar), "META-INF/LICENSE")
    }

    /** A jar of many libraries is no module, and the manifest, the index, and the signatures of a library are not its own. */
    @Test
    fun theRunnableJarHoldsWhatTheLibrariesHoldButForWhatDescribesTheirJars() {
        val ofTheLibraries =
            distribution
                .resolve("lib")
                .listDirectoryEntries()
                .flatMap(::entriesOf)
                .filterNot { DESCRIBES_ITS_JAR.matches(it) }
                .toSet()

        val ofTheRunnableJar = entriesOf(runnableJar).toSet()

        assertEquals(emptySet(), ofTheLibraries - ofTheRunnableJar, "What the runnable jar lacks")
        val ofItsOwn = ofTheRunnableJar - ofTheLibraries
        assertEquals(setOf("META-INF/MANIFEST.MF", "META-INF/LICENSE"), ofItsOwn, "What the runnable jar has beyond the libraries")
        assertEquals(emptyList(), entriesOf(runnableJar).filter { it.endsWith("module-info.class") })
    }

    /** The runtime looks for the main class in the manifest, and it takes the classes for newer runtimes only when the manifest says so. */
    @Test
    fun theManifestOfTheRunnableJarNamesTheMainClassAndSaysThatTheJarHoldsClassesForNewerRuntimes() {
        val manifest = JarFile(runnableJar.toFile()).use { it.manifest.mainAttributes }

        assertEquals("io.github.dnedrow.mockey.ui.MainKt", manifest.getValue("Main-Class"))
        assertEquals("true", manifest.getValue("Multi-Release"))
        assertTrue(entriesOf(runnableJar).any { it.startsWith("META-INF/versions/") }, "The jar holds classes for newer runtimes")
    }

    /**
     * A file that several libraries have under one name is in the runnable jar once, with the lines of all
     * of them: the file in which each part of Netty states its version, and the lists of what a library
     * provides for a service.
     */
    @Test
    fun aListThatSeveralLibrariesHaveUnderOneNameHoldsTheLinesOfAllOfThemInTheRunnableJar() {
        val libraries = distribution.resolve("lib").listDirectoryEntries()
        val shared =
            libraries
                .flatMap { jar -> entriesOf(jar).filterNot(DESCRIBES_ITS_JAR::matches).map { it to jar } }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.size > 1 }

        assertContains(shared.keys, "META-INF/io.netty.versions.properties")
        assertEquals(emptySet(), shared.keys.filterNot { JOINED.matches(it) }.toSet(), "Files that the build does not join")
        for ((name, jars) in shared) {
            val joined = linesOf(runnableJar, name)
            val missing = jars.flatMap { jar -> linesOf(jar, name).filterNot { it in joined }.map { "${jar.name}: $it" } }
            assertEquals(emptyList(), missing, "Lines of $name that the runnable jar lacks")
            assertEquals(1, entriesOf(runnableJar).count { it == name }, "Number of files with the name $name")
        }
    }

    /**
     * Runs [command], which is the program, on its own in an empty environment with [variables], as the UI
     * of a server that holds the samples. The UI shows the overview of the services and every page of the
     * help, with the files that the pages refer to, and it ends in order on the signal to end.
     */
    private fun assertShowsTheOverviewAndTheHelp(
        command: List<String>,
        variables: Map<String, String>,
    ) {
        assertEquals(JAVA_21, Runtime.version().feature(), "The Java runtime of the test")
        api.holdSamples()
        val arguments = arrayOf("--port", "0", "--admin-url", api.url)

        UiProgram.startDelivered(command, *arguments, variables = variables, starter = UiProgram.withSignals()).use { program ->
            val ui = "http://127.0.0.1:${program.listening()}"
            val overview = load("$ui/services")
            val help = load("$ui/help")
            val topics = help.page.select("main dl.topics dt a").map { load(ui + it.attr("href")) }
            val files = overview.page.select("head [href], head [src]").map { it.attr("href") + it.attr("src") }
            val served = files.associateWith { load(ui + it).status }
            program.signal("TERM")
            val outcome = program.awaitEnd()

            assertEquals(200 to "Services", overview.status to overview.heading)
            assertEquals(listOf("Customers", "Orders"), overview.page.select("main table.services tbody th a:not(.marker)").eachText())
            assertContains(overview.page.select("body > footer").text(), "Server at ${api.url}, version ${api.system.version}")
            assertEquals(200 to "Help", help.status to help.heading)
            assertEquals(HelpTopics.all.map { 200 to "Help: ${it.title}" }, topics.map { it.status to it.heading })
            assertEquals(mapOf("/assets/mockey.css" to 200, "/assets/favicon.svg" to 200, "/assets/htmx.min.js" to 200), served)
            assertEquals(0, outcome.status, "$outcome")
            assertEquals("", outcome.errors)
            assertContains(outcome.output, "Mockey UI ${UiVersion.text} starts with these settings:")
            assertContains(outcome.output, "The program got the signal SIGTERM.")
            assertContains(outcome.output, "The UI has stopped.")
        }
        assertEquals(emptyList(), api.violations)
    }

    /** The names of what the directory [directory] holds, in their order. */
    private fun namesIn(directory: Path): List<String> = directory.listDirectoryEntries().map { it.name }.sorted()

    /** The lines of the file [name] in the jar [jar] that say something: neither comments nor empty lines. */
    private fun linesOf(
        jar: Path,
        name: String,
    ): List<String> =
        ZipFile(jar.toFile()).use { zip ->
            zip
                .getInputStream(zip.getEntry(name))
                .bufferedReader()
                .readLines()
                .filterNot { it.isBlank() || it.startsWith("#") }
        }

    /** The names of the files in the jar [jar]. */
    private fun entriesOf(jar: Path): List<String> {
        check(jar.isRegularFile()) { "$jar is no file" }
        return ZipFile(jar.toFile()).use { zip ->
            zip
                .entries()
                .asSequence()
                .filterNot { it.isDirectory }
                .map { it.name }
                .toList()
        }
    }

    private companion object {
        const val JAVA_21 = 21
        val windows = System.getProperty("os.name").startsWith("Windows")
        val NATIVE_LIBRARY = Regex("""\.(so|dll|dylib|jnilib)$""")

        /** The jars of the engine, of the importer, and of the server program, which a UI has no use for. */
        val OF_THE_SERVER = Regex("""^mockey-(core|legacy|server)-""")

        /** The files that the build joins when several libraries have one under the same name. */
        val JOINED = Regex("""META-INF/(services/.+|io\.netty\.versions\.properties)""")

        /** What a jar says about itself: its manifest, its index, its signatures, and its module descriptors. */
        val DESCRIBES_ITS_JAR =
            Regex("""META-INF/(MANIFEST\.MF|INDEX\.LIST|[^/]*\.(SF|DSA|RSA|EC))|(META-INF/versions/\d+/)?module-info\.class""")
    }
}
