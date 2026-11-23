package io.github.dnedrow.mockey.parity

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Checks and recording steps that the test classes of the case groups share. */
internal object RecordedGroups {
    private val resources = Path.of("src/test/resources")
    private val store = ParityCaseStore()

    /** Asserts that every case of [groups] has a recording made from its current fixture and requests. */
    fun assertRecorded(groups: Collection<ParityCaseGroup>) {
        groups.forEach { group ->
            val fixtureXml = Files.readString(resources.resolve(group.fixturePath))
            group.cases.forEach { (name, case) ->
                val recorded = store.read(resources.resolve("parity/$name"))
                assertEquals(fixtureXml, recorded.fixtureXml, "fixture of $name")
                assertSameRequest(case.request, recorded.request, "request of $name")
                assertEquals(case.before.size, recorded.before.size, "number of requests before $name")
                case.before.zip(recorded.before).forEachIndexed { index, (expected, actual) ->
                    assertSameRequest(expected, actual, "request ${index + 1} before $name")
                }
                assertNotNull(recorded.expectedResponse, "recorded response of $name")
                assertEquals(case.timing, recorded.timing?.copy(recordedMillis = null), "timing bounds of $name")
                if (case.timing != null) assertNotNull(recorded.timing?.recordedMillis, "recorded time of $name")
                assertEquals(case.snapshot, recorded.snapshot, "snapshot mark of $name")
            }
        }
    }

    /** Asserts that the recordings whose names start with [prefix] are exactly the cases of [groups]. */
    fun assertNoOtherRecordings(
        groups: Collection<ParityCaseGroup>,
        prefix: String,
    ) {
        val recorded =
            resources
                .resolve("parity")
                .listDirectoryEntries("$prefix*")
                .filter { it.isDirectory() }
                .map { it.name }
                .toSet()
        assertEquals(casesOf(groups).keys, recorded)
    }

    /**
     * Records the groups selected by mockey.parity.recordGroup from legacy Mockey at mockey.parity.legacy.baseUrl.
     * The property names one group, or the part that several group names share before a hyphen, or "all" for
     * every group. Does nothing unless both properties are set and the selection matches a group.
     */
    fun recordFromLegacy(
        groups: Collection<ParityCaseGroup>,
        whileRecording: (record: () -> Unit) -> Unit = { it() },
    ) {
        val baseUrl = legacyBaseUrl() ?: return
        val selected = selected(groups, "mockey.parity.recordGroup").ifEmpty { return }
        whileRecording { LegacyGroupRecorder(baseUrl, resources).record(selected) }
    }

    /**
     * Replays the recordings of the groups selected by mockey.parity.replayGroup against legacy Mockey at
     * mockey.parity.legacy.baseUrl and fails when a response differs from its recording. The property selects
     * groups as mockey.parity.recordGroup does. Does nothing unless both properties are set and the selection
     * matches a group.
     */
    fun replayAgainstLegacy(
        groups: Collection<ParityCaseGroup>,
        whileReplaying: (replay: () -> Unit) -> Unit = { it() },
    ) {
        val baseUrl = legacyBaseUrl() ?: return
        val selected = selected(groups, "mockey.parity.replayGroup").ifEmpty { return }
        whileReplaying {
            val differing = LegacyGroupRecorder(baseUrl, resources).replay(selected).filterNot { it.matches }
            assertEquals(emptyList(), differing.map { "${it.caseName}: ${it.differences.joinToString("; ")}" })
        }
    }

    private fun legacyBaseUrl(): URI? = System.getProperty("mockey.parity.legacy.baseUrl")?.let(URI::create)

    /** The groups that the system property [property] selects, which are none when the property is not set. */
    private fun selected(
        groups: Collection<ParityCaseGroup>,
        property: String,
    ): Collection<ParityCaseGroup> {
        val selection = System.getProperty(property) ?: return emptyList()
        return if (selection == ALL) groups else groupsMatching(groups, selection)
    }

    private const val ALL = "all"

    private fun assertSameRequest(
        expected: ParityRequest,
        actual: ParityRequest,
        what: String,
    ) {
        assertEquals(expected.method, actual.method, "method of $what")
        assertEquals(expected.target, actual.target, "target of $what")
        assertEquals(expected.headers, actual.headers, "headers of $what")
        assertContentEquals(expected.body, actual.body, "body of $what")
    }
}
