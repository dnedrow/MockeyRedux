package io.github.dnedrow.mockey.server

import java.nio.file.Files
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
 * The program as the build delivers it: the distribution with its start scripts, and the runnable jar. The
 * build puts both where the system properties `mockey.distribution` and `mockey.runnableJar` say.
 *
 * The program runs on the Java runtime of this test, which the build takes from its toolchain and which is a
 * Java 21 runtime, and it gets nothing from its environment that tells a runtime which classes or options to
 * take. So it runs as it does on a machine that has a Java 21 runtime and nothing else.
 */
class DistributionTest {
    private val distribution = Path(System.getProperty("mockey.distribution"))
    private val runnableJar = Path(System.getProperty("mockey.runnableJar"))
    private val directory: Path = Files.createTempDirectory("mockey-distribution-")

    @AfterTest
    fun deleteWhatTheTestWrote() {
        directory.toFile().deleteRecursively()
    }

    /** The scenario "Plain Java runtime", for the server. */
    @Test
    fun theStartScriptOfTheDistributionRunsTheProgramOnAPlainJavaRuntime() {
        TestEnvironment.needs(!windows, "a shell, to run the start script of the distribution")

        assertImportsAndServes(
            listOf("${distribution.resolve("bin/$PROGRAM_NAME")}"),
            mapOf("JAVA_HOME" to System.getProperty("java.home")),
        )
    }

    @Test
    fun theRunnableJarRunsTheProgramOnAPlainJavaRuntime() {
        assertImportsAndServes(listOf(ServerProgram.java, "-jar", "$runnableJar"), emptyMap())
    }

    @Test
    fun theDistributionHasAStartScriptForUnixAndOneForWindowsAndTheLibrariesOfTheProgram() {
        val libraries = namesIn(distribution.resolve("lib"))

        assertEquals(listOf("LICENSE", "bin", "lib"), namesIn(distribution))
        assertEquals(listOf(PROGRAM_NAME, "$PROGRAM_NAME.bat"), namesIn(distribution.resolve("bin")))
        assertTrue(windows || distribution.resolve("bin/$PROGRAM_NAME").isExecutable(), "The start script can be executed")
        assertContains(libraries, "$PROGRAM_NAME-${ServerVersion.text}.jar")
        assertTrue(libraries.all { it.endsWith(".jar") }, "$libraries")
    }

    /** The requirement "Runtime platform": the program runs without native libraries. */
    @Test
    fun neitherTheDistributionNorTheRunnableJarHoldsANativeLibrary() {
        val jars = distribution.resolve("lib").listDirectoryEntries() + listOf(runnableJar)

        val native = jars.associate { it.name to entriesOf(it).filter(NATIVE_LIBRARY::containsMatchIn) }.filterValues { it.isNotEmpty() }

        assertEquals(emptyMap(), native)
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

        assertEquals("io.github.dnedrow.mockey.server.MainKt", manifest.getValue("Main-Class"))
        assertEquals("true", manifest.getValue("Multi-Release"))
        assertTrue(entriesOf(runnableJar).any { it.startsWith("META-INF/versions/") }, "The jar holds classes for newer runtimes")
    }

    /**
     * A file that several libraries have under one name is in the runnable jar once. The only such file is
     * the one in which each part of Netty states its version, and the runnable jar has the lines of them all.
     */
    @Test
    fun aListThatSeveralLibrariesHaveUnderOneNameHoldsTheLinesOfAllOfThemInTheRunnableJar() {
        val libraries = distribution.resolve("lib").listDirectoryEntries()
        val shared =
            libraries
                .flatMap { jar -> entriesOf(jar).filterNot(DESCRIBES_ITS_JAR::matches).map { it to jar } }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.size > 1 }

        assertEquals(setOf("META-INF/io.netty.versions.properties"), shared.keys)
        for ((name, jars) in shared) {
            val joined = linesOf(runnableJar, name)
            val missing = jars.flatMap { jar -> linesOf(jar, name).filterNot { it in joined }.map { "${jar.name}: $it" } }
            assertEquals(emptyList(), missing, "Lines of $name that the runnable jar lacks")
            assertEquals(1, entriesOf(runnableJar).count { it == name }, "Number of files with the name $name")
        }
    }

    /**
     * Runs [command], which is the program, on its own in an empty environment with [variables]: first with
     * the command that imports definitions of Mockey 2.x, and then as the server of what was imported, which
     * answers a mock request, says that it runs, and ends in order on the signal to end.
     */
    private fun assertImportsAndServes(
        command: List<String>,
        variables: Map<String, String>,
    ) {
        assertEquals(JAVA_21, Runtime.version().feature(), "The Java runtime of the test")
        val export = directory.resolve("export.xml").also { Files.writeString(it, LegacySources.export()) }
        val definitions = directory.resolve("definitions")

        val import =
            ServerProgram.startDelivered(command, "import-legacy", "$export", "--definitions", "$definitions", variables = variables).use {
                it.awaitEnd()
            }
        assertEquals(0, import.status, "$import")
        assertEquals("", import.errors)

        val arguments = arrayOf("--mock-host", "127.0.0.1", "--mock-port", "0", "--admin-port", "0", "--definitions", "$definitions")
        ServerProgram.startDelivered(command, *arguments, variables = variables, starter = ServerProgram.withSignals()).use { program ->
            val mock = program.listening().mock
            val status = RawHttp.exchange(mock, RawHttp.request("GET", "/status"))
            val orders = RawHttp.exchange(mock, RawHttp.request("GET", "/service/legacy/orders"))
            program.signal("TERM")
            val outcome = program.awaitEnd()

            assertEquals(200, status.status, "$status")
            assertContains(status.body, """"status":"running"""")
            assertEquals(200 to LegacySources.SILVER_ORDERS, orders.status to orders.body)
            assertEquals(0, outcome.status, "$outcome")
            assertEquals("", outcome.errors)
            assertContains(outcome.output, "$PROGRAM_NAME ${ServerVersion.text} starts with these settings:")
        }
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

        /** What a jar says about itself: its manifest, its index, its signatures, and its module descriptors. */
        val DESCRIBES_ITS_JAR =
            Regex("""META-INF/(MANIFEST\.MF|INDEX\.LIST|[^/]*\.(SF|DSA|RSA|EC))|(META-INF/versions/\d+/)?module-info\.class""")
    }
}
