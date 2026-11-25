package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.server.TestDefinitions.orders
import io.github.dnedrow.mockey.server.TestDefinitions.staticService
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The server program as a process, and the definitions that it starts with: what its clients get, what it
 * leaves in its definitions location, and with which status and which words it ends when it does not start.
 */
class ServerProgramDefinitionsTest {
    private val onFreePorts = arrayOf("--mock-host", "127.0.0.1", "--mock-port", "0", "--admin-port", "0")

    /** The definitions location of a program that is told none: `mockey-definitions` in its working directory. */
    private fun locationOf(program: ServerProgram.Running): Path = program.workingDirectory.resolve("mockey-definitions")

    /** What the definitions location of a program held when the program was started. */
    private var storedBefore: Map<String, String> = emptyMap()

    /**
     * Fills the working directory of a program before the program starts: its definitions location gets the
     * service `Customers`, and the file `export.xml` gets an export document of Mockey 2.x.
     */
    private val withStoredDefinitionsAndAnExportDocument: (Path) -> Unit = { directory ->
        StoredDefinitions.customers(directory.resolve("mockey-definitions"))
        Files.writeString(directory.resolve("export.xml"), LegacySources.export(withDefaultPlan = true))
        storedBefore = TestDefinitions.contentOf(directory.resolve("mockey-definitions"))
    }

    /**
     * The scenario "Default location". The program has no admin API yet, so the service is one that the
     * program creates itself: for a request to a real service that no service knows.
     */
    @Test
    fun inWritableModeAServiceIsStoredUnderMockeyDefinitionsInTheWorkingDirectory() {
        StubUpstream().use { real ->
            ServerProgram.start("--writable", *onFreePorts).use { program ->
                val listening = program.listening()
                val storedAtStart = TestDefinitions.contentOf(locationOf(program))

                val answer = RawHttp.get(listening.mock, "/service/${real.origin}/files/1")

                val stored = TestDefinitions.contentOf(locationOf(program)).keys
                val serviceFile = stored.single { it.endsWith("service.json") }
                assertEquals(200 to "answered by the real service", answer.status to answer.body)
                assertEquals(emptyMap(), storedAtStart, "A program without definitions stores nothing")
                assertTrue(serviceFile.startsWith("services/"), "$stored")
                assertContains(Files.readString(locationOf(program).resolve(serviceFile)), "${real.origin}/files/1")
                assertContains(stored, "mockey.json")
                assertContains(
                    TestLog.messages(program.output),
                    "  definitions = ${program.workingDirectory.toRealPath().resolve("mockey-definitions")}",
                )
                assertContains(TestLog.messages(program.output), "  read-only = false")
                assertEquals("", program.errors)
            }
        }
    }

    /** The scenario "Start from a legacy document". The first request that the program gets is the one for the service of the document. */
    @Test
    fun inReadOnlyModeTheProgramStartsWithTheDefinitionsOfAnExportDocumentOfMockey2() {
        val arguments = arrayOf("--initial-definitions", "export.xml", *onFreePorts)

        ServerProgram.start(*arguments, prepare = withStoredDefinitionsAndAnExportDocument).use { program ->
            val listening = program.listening()

            val first = RawHttp.get(listening.mock, "/service/legacy/orders")
            val ofStoredService = RawHttp.get(listening.mock, "/service/customer/7")

            val messages = TestLog.messages(program.output)
            assertEquals(200 to LegacySources.GOLD_ORDERS, first.status to first.body)
            assertEquals(404 to "No service matches the target 'customer/7'.", ofStoredService.status to ofStoredService.body)
            assertEquals(storedBefore, TestDefinitions.contentOf(locationOf(program)), "The definitions location stays as it is")
            assertContains(messages, "  read-only = true")
            assertContains(messages, "  initial-definitions = export.xml")
            assertContains(
                messages,
                "The server starts with the initial definitions of export.xml, which it keeps in memory: " +
                    "1 service with 2 scenarios, 1 plan, and 0 twist configurations.",
            )
            assertContains(messages, "The default plan 'Gold path' is applied.")
            assertEquals("", program.errors)
        }
    }

    /** The scenario "Source cannot be read". */
    @Test
    fun aSourceOfInitialDefinitionsThatDoesNotExistEndsTheProgramWithTheSourceNamed() {
        val outcome = ServerProgram.run("--initial-definitions", "no-such-file.xml", *onFreePorts)

        assertEquals(1, outcome.status, "$outcome")
        assertEquals(
            "Error: The definitions cannot be read from no-such-file.xml. There is no such file or directory. " +
                "The source is the setting --initial-definitions or MOCKEY_INITIAL_DEFINITIONS.\n",
            outcome.errors,
        )
        assertFalse("accepts connections" in outcome.output, outcome.output)
    }

    /** The scenario "Stored definitions are protected", with the settings as options and as environment variables. */
    @Test
    fun inWritableModeInitialDefinitionsEndTheProgramWhileItsDefinitionsLocationHoldsDefinitions() {
        val asOptions = arrayOf("--writable", "--initial-definitions", "export.xml")
        val asVariables = mapOf("MOCKEY_READ_ONLY" to "false", "MOCKEY_INITIAL_DEFINITIONS" to "export.xml")
        val programs =
            listOf(
                { ServerProgram.start(*asOptions, *onFreePorts, prepare = withStoredDefinitionsAndAnExportDocument) },
                { ServerProgram.start(*onFreePorts, variables = asVariables, prepare = withStoredDefinitionsAndAnExportDocument) },
            )

        for (start in programs) {
            start().use { program ->
                val outcome = program.awaitEnd()

                val location = program.workingDirectory.toRealPath().resolve("mockey-definitions")
                assertEquals(1, outcome.status, "$outcome")
                assertEquals(
                    "Error: The definitions location $location holds definitions, and the server is in writable mode, " +
                        "in which the initial definitions would be stored in their place. " +
                        "The server takes initial definitions in writable mode only while the definitions location holds no definitions. " +
                        "To start with the initial definitions and leave the stored definitions as they are, " +
                        "start the server in read-only mode, which is the default: leave out --writable, or set MOCKEY_READ_ONLY=true. " +
                        "To go on with the stored definitions, leave out --initial-definitions and MOCKEY_INITIAL_DEFINITIONS.\n",
                    outcome.errors,
                )
                assertEquals(storedBefore, TestDefinitions.contentOf(locationOf(program)), "The stored definitions stay as they are")
                assertFalse("accepts connections" in outcome.output, outcome.output)
                assertTrue(storedBefore.keys.any { it.endsWith("service.json") }, "${storedBefore.keys}")
            }
        }
    }

    /** The scenario "Corrupt file". */
    @Test
    fun aDefinitionFileThatCannotBeReadEndsTheProgramWithTheFileAndWhatIsWrongWithIt() {
        val scenarioFile = "mockey-definitions/services/orders/scenarios/gold.json"
        val withACorruptFile: (Path) -> Unit = { directory ->
            TestDefinitions.store(directory.resolve("mockey-definitions")) { orders() }
            Files.writeString(directory.resolve(scenarioFile), """{"name": """)
        }

        ServerProgram.start(*onFreePorts, prepare = withACorruptFile).use { program ->
            val outcome = program.awaitEnd()

            val start = "Error: The definitions cannot be loaded. The definition file $scenarioFile cannot be read: it is not valid JSON: "
            assertEquals(1, outcome.status, "$outcome")
            assertTrue(outcome.errors.startsWith(start), outcome.errors)
            assertEquals(1, outcome.errors.lines().count { it.isNotEmpty() }, outcome.errors)
            assertFalse("accepts connections" in outcome.output, outcome.output)
        }
    }

    /** The scenario "Definitions from a newer release". */
    @Test
    fun definitionsOfANewerFormatVersionEndTheProgramWithTheVersionThatItFoundAndTheOnesThatItReads() {
        val ofANewerVersion: (Path) -> Unit = { directory ->
            TestDefinitions.store(directory.resolve("mockey-definitions")) { orders(withDefaultPlan = true) }
            val mainFile = directory.resolve("mockey-definitions/mockey.json")
            Files.writeString(mainFile, Files.readString(mainFile).replace(""""formatVersion": 1""", """"formatVersion": 7"""))
        }

        ServerProgram.start(*onFreePorts, prepare = ofANewerVersion).use { program ->
            val outcome = program.awaitEnd()

            assertEquals(1, outcome.status, "$outcome")
            assertEquals(
                "Error: The definitions cannot be loaded. The definition file mockey-definitions/mockey.json cannot be read: " +
                    "it has the format version 7, and this version of Mockey reads the format version 1 only.\n",
                outcome.errors,
            )
        }
    }

    /** The scenario "Startup tag filter", as far as a client of the mock listener can tell. */
    @Test
    fun theTagFilterOfTheProgramIsInEffectFromTheFirstRequest() {
        val withATaggedService: (Path) -> Unit = { directory ->
            TestDefinitions.store(directory.resolve("mockey-definitions")) {
                staticService("Tagged", "tagged", "only" to "tagged", tags = Tags.of("regression"))
                staticService("Untagged", "untagged", "only" to "untagged")
            }
        }

        ServerProgram.start("--tag-filter", "regression", *onFreePorts, prepare = withATaggedService).use { program ->
            val listening = program.listening()

            val answers = listOf("tagged", "untagged").map { RawHttp.get(listening.mock, "/service/$it") }

            assertEquals(listOf(200 to "tagged", 404 to "No service matches the target 'untagged'."), answers.map { it.status to it.body })
            assertEquals("", program.errors)
        }
    }
}
