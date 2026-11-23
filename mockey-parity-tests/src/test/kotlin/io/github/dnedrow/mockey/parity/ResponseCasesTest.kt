package io.github.dnedrow.mockey.parity

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ResponseCasesTest {
    private val resources = Path.of("src/test/resources")
    private val store = ParityCaseStore()

    @Test
    fun everyResponseCaseHasARecordingOfItsCurrentFixtureAndRequest() {
        ResponseCases.all.forEach { (name, case) ->
            val recorded = store.read(resources.resolve("parity/$name"))
            assertEquals(Files.readString(resources.resolve(case.fixtureXml)), recorded.fixtureXml, "fixture of $name")
            assertEquals(case.request.method, recorded.request.method, "method of $name")
            assertEquals(case.request.target, recorded.request.target, "target of $name")
            assertEquals(case.request.headers, recorded.request.headers, "request headers of $name")
            assertContentEquals(case.request.body, recorded.request.body, "request body of $name")
            assertNotNull(recorded.expectedResponse, "recorded response of $name")
            assertEquals(case.timing, recorded.timing?.copy(recordedMillis = null), "timing bounds of $name")
            if (case.timing != null) assertNotNull(recorded.timing?.recordedMillis, "recorded time of $name")
        }
    }

    @Test
    fun everyResponseRecordingBelongsToACase() {
        val recorded =
            resources
                .resolve("parity")
                .listDirectoryEntries("response-*")
                .filter { it.isDirectory() }
                .map { it.name }
                .toSet()
        assertEquals(ResponseCases.all.keys, recorded)
    }

    /**
     * Records the fixture groups selected by mockey.parity.recordGroup from legacy Mockey at
     * mockey.parity.legacy.baseUrl. The property names one group, or the part that several group names share
     * before a hyphen: "responses" selects every group. Each fixture is loaded through the legacy initialization
     * endpoint before its cases are recorded, so legacy needs no fixture at startup.
     */
    @Test
    fun recordsGroupFromLegacy() {
        val baseUrl = System.getProperty("mockey.parity.legacy.baseUrl")?.let(URI::create) ?: return
        val selection = System.getProperty("mockey.parity.recordGroup") ?: return
        val loader = LegacyFixtureLoader(baseUrl)
        val recorder = ParityRecorder()
        ResponseCases.groupsMatching(selection).forEach { (group, cases) ->
            val fixture = resources.resolve(ResponseCases.fixturePath(group))
            loader.load(fixture)
            val fixtureXml = Files.readString(fixture)
            cases.forEach { (name, case) ->
                recorder.record(case.copy(fixtureXml = fixtureXml), baseUrl, resources.resolve("parity/$name"))
            }
        }
    }
}
