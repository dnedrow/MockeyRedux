package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.storage.FileDefinitionStorage
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The command of the server program that imports the definitions of Mockey 2.x, as a process: its exit
 * status, its two output streams, the definitions location that it has by default, and that it opens no
 * listener.
 */
class ImportLegacyProgramTest {
    /**
     * The scenario "Migrate a legacy directory". The settings name ports that are in use, with which no
     * server starts: the command needs none, because it opens no listener.
     */
    @Test
    fun theProgramImportsADefinitionsDirectoryPrintsTheReportAndEndsWithStatusZeroWithoutAListener() {
        var before = emptyMap<String, String>()
        ServerSocket(0, 1, RawHttp.loopback(0).address).use { mock ->
            ServerSocket(0, 1, RawHttp.loopback(0).address).use { admin ->
                val settings =
                    mapOf(
                        "MOCKEY_MOCK_HOST" to "127.0.0.1",
                        "MOCKEY_MOCK_PORT" to "${mock.localPort}",
                        "MOCKEY_ADMIN_PORT" to "${admin.localPort}",
                    )
                val server = ServerProgram.run(variables = settings)
                val prepare = { directory: Path ->
                    before = TestDefinitions.contentOf(LegacySources.directory(directory.resolve("legacy")))
                }

                ServerProgram.start("import-legacy", "legacy", variables = settings, prepare = prepare).use { program ->
                    val outcome = program.awaitEnd()

                    val location = program.workingDirectory.toRealPath().resolve("mockey-definitions")
                    val report =
                        """
                        Imported legacy into the definitions location $location.

                        Additions: 2
                          The service 'Directory Orders' was added with 1 scenario.
                          The scenario 'list' was added with its service 'Directory Orders'.
                        Conflicts: none
                        Issues of the source: none
                        Rule sets that are not well formed: none

                        The definitions location now holds 1 service with 1 scenario, 0 plans, and 0 twist configurations.

                        """.trimIndent()
                    assertEquals(report, outcome.output)
                    assertEquals(0, outcome.status, "$outcome")
                    assertEquals("", outcome.errors)
                    val service = runBlocking { FileDefinitionStorage(location).load() }.services.single()
                    assertEquals("Directory Orders" to "directory/orders", service.name to service.mockUrl)
                    assertEquals(listOf("list" to LegacySources.DIRECTORY_ORDERS), service.scenarios.map { it.name to it.responseBody })
                    assertContains(before.keys, "mock_service_definitions.xml", "The source was there before the program started")
                    assertEquals(
                        before,
                        TestDefinitions.contentOf(program.workingDirectory.resolve("legacy")),
                        "Nothing of the source has changed",
                    )
                }
                assertEquals(1, server.status, "A server does not start with these settings: $server")
                assertContains(server.errors, "${mock.localPort}")
            }
        }
    }

    @Test
    fun aSourceThatCannotBeReadEndsTheProgramWithAnotherStatusAndNoDefinitionsLocationIsMade() {
        ServerProgram.start("import-legacy", "no-such-directory").use { program ->
            val outcome = program.awaitEnd()

            assertEquals(1, outcome.status, "$outcome")
            assertEquals(
                "Error: The definitions cannot be read from no-such-directory. There is no such file or directory.\n",
                outcome.errors,
            )
            assertEquals("", outcome.output)
            assertFalse(Files.exists(program.workingDirectory.resolve("mockey-definitions")))
        }
    }
}
