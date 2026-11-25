package io.github.dnedrow.mockey.parity

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 11.8: the parity suite against the new server. A recording that is labeled `same` has to be answered
 * as legacy answered it, and one that is labeled with deviations as its stated new response says, see
 * [ParityReplayer]. The fixtures get into the servers through the importer of legacy definitions, see
 * [NewServerReplay].
 */
class NewServerParityTest {
    private val resources = Path.of("src/test/resources")

    @Test
    fun theNewServerAnswersEveryRecordingAsItsLabelSays() = assertNoDifferences(FixtureLoading.INITIAL_DEFINITIONS)

    /** What the import command stores is what a server serves after a migration, so it has to answer the same. */
    @Test
    fun theNewServerAnswersTheSameFromTheDefinitionsThatTheImportCommandStored() = assertNoDifferences(FixtureLoading.IMPORT_COMMAND)

    /**
     * The recordings that are not replayed are the ones that send a request to an endpoint of the legacy shim,
     * which the server does not have yet: every automation case but the two for `/status` and for the second
     * path of legacy to `/config/service`, and the three routing cases that read `/definitions` to see which
     * services a request left behind. Task 14.6 replays them against a server with the shim.
     */
    @Test
    fun theRecordingsThatAreNotReplayedAreTheOnesThatNeedTheLegacyShim() {
        val notReplayed = ParitySuite.all.filterValues(NewServerReplay::needsLegacyShim).keys

        val automation = AutomationCases.all.keys - setOf("automation-status", "automation-config-second-path")
        val routing =
            setOf(
                "routing-unknown-target-registered",
                "routing-unknown-target-with-escape-registered",
                "routing-tag-hidden-absolute-registered",
            )
        assertEquals(automation + routing, notReplayed)
        assertEquals(NOT_REPLAYED, notReplayed.size)
    }

    /** Replays every recording that the server can answer, with the fixtures [loading] as given, and expects no difference. */
    private fun assertNoDifferences(loading: FixtureLoading) {
        val results = withStubs { NewServerReplay(resources, loading).replay(ParitySuite.groups) }

        val differing = results.filterNot { it.matches }.map { "${it.caseName}: ${it.differences.joinToString("; ")}" }
        assertTrue(differing.isEmpty(), "The server answers ${differing.size} recordings otherwise:\n" + differing.joinToString("\n"))
        assertEquals(ParitySuite.all.size - NOT_REPLAYED, results.size, "number of recordings that were replayed")
    }

    /** The stub upstream and both stub outbound proxies run while [replay] does, on the ports that the fixtures name. */
    private fun <T> withStubs(replay: () -> T): T =
        StubHttpServer.upstream(ProxyCases.UPSTREAM_PORT).use {
            StubHttpServer.outboundProxy(ProxyCases.OUTBOUND_PROXY_PORT).use {
                StubHttpServer.outboundProxy(ProxyCases.AUTHENTICATING_PROXY_PORT, requireProxyCredentials = true).use {
                    replay()
                }
            }
        }

    private companion object {
        /** The number of recordings that wait for the legacy shim. */
        const val NOT_REPLAYED = 44
    }
}
