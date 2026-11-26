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
 *
 * Task 14.6: the suite replays every recording. A recording that sends a request to an automation endpoint
 * of legacy is replayed against a server with the legacy shim, and every other one against a server without
 * it, as the program starts a server by default.
 */
class NewServerParityTest {
    private val resources = Path.of("src/test/resources")

    @Test
    fun theNewServerAnswersEveryRecordingAsItsLabelSays() = assertNoDifferences(FixtureLoading.INITIAL_DEFINITIONS)

    /** What the import command stores is what a server serves after a migration, so it has to answer the same. */
    @Test
    fun theNewServerAnswersTheSameFromTheDefinitionsThatTheImportCommandStored() = assertNoDifferences(FixtureLoading.IMPORT_COMMAND)

    /**
     * The legacy shim changes no answer to a request that isn't for one of its endpoints. So a server with
     * the shim answers every recording as its label says as well, also the ones that need no shim.
     */
    @Test
    fun aServerWithTheLegacyShimAnswersTheOtherRecordingsAsBefore() =
        assertNoDifferences(FixtureLoading.INITIAL_DEFINITIONS, ShimUse.EVERYWHERE)

    /**
     * The recordings that need the legacy shim are the ones that send a request to one of its endpoints:
     * every automation case but the two for `/status` and for the second path of legacy to `/config/service`,
     * and the three routing cases that read `/definitions` to see which services a request left behind.
     */
    @Test
    fun theRecordingsThatNeedTheLegacyShimAreTheOnesThatAddressItsEndpoints() {
        val needTheShim = ParitySuite.all.filterValues(NewServerReplay::needsLegacyShim).keys

        val automation = AutomationCases.all.keys - setOf("automation-status", "automation-config-second-path")
        val routing =
            setOf(
                "routing-unknown-target-registered",
                "routing-unknown-target-with-escape-registered",
                "routing-tag-hidden-absolute-registered",
            )
        assertEquals(automation + routing, needTheShim)
        assertEquals(NEED_THE_SHIM, needTheShim.size)
    }

    /**
     * A replay gives the legacy shim to the server of a recording that needs it, and to no other server. On
     * request, it gives the shim to every server, or to none.
     */
    @Test
    fun aReplayGivesTheLegacyShimToTheServersThatAreToHaveIt() {
        fun withTheShim(shim: ShimUse): Set<String> {
            val replay = NewServerReplay(resources, FixtureLoading.INITIAL_DEFINITIONS, shim)
            val arguments = ParitySuite.all.mapValues { (_, case) -> replay.shimArguments(case) }
            assertEquals(emptyList(), arguments.values.filter { it.isNotEmpty() && it != listOf("--legacy-shim") }, "$shim")
            return arguments.filterValues { it.isNotEmpty() }.keys
        }

        val needTheShim = ParitySuite.all.filterValues(NewServerReplay::needsLegacyShim).keys
        assertEquals(needTheShim, withTheShim(ShimUse.WHERE_A_CASE_NEEDS_IT))
        assertEquals(ParitySuite.all.keys, withTheShim(ShimUse.EVERYWHERE))
        assertEquals(emptySet(), withTheShim(ShimUse.NOWHERE))
    }

    /**
     * A server without the shim does nothing for a request to one of its endpoints, so it answers the
     * recordings that need the shim otherwise than their labels say. That shows that their replay is about
     * the shim.
     *
     * Two recordings can't tell such a server from one with the shim. Each states the answer of the mock
     * endpoint after requests that leave no trace there: an init with a tag filter that hides the service of
     * the request, and a plan that isn't saved, because the request names its service by a number of legacy.
     */
    @Test
    fun aServerWithoutTheLegacyShimAnswersTheRecordingsThatNeedItOtherwise() {
        val replay = NewServerReplay(resources, FixtureLoading.INITIAL_DEFINITIONS, ShimUse.NOWHERE)

        val results = withStubs { replay.replay(ParitySuite.groups, NewServerReplay::needsLegacyShim) }

        val asLabeled = results.filter { it.matches }.map { it.caseName }
        assertEquals(listOf("automation-plan-saved-plan-applies", "automation-home-init-filter-tag"), asLabeled)
        assertEquals(NEED_THE_SHIM, results.size)
    }

    /**
     * Replays every recording, with the fixtures [loading] as given and with the legacy shim in the servers
     * that [shim] names, and expects no difference.
     */
    private fun assertNoDifferences(
        loading: FixtureLoading,
        shim: ShimUse = ShimUse.WHERE_A_CASE_NEEDS_IT,
    ) {
        val results = withStubs { NewServerReplay(resources, loading, shim).replay(ParitySuite.groups) }

        val differing = results.filterNot { it.matches }.map { "${it.caseName}: ${it.differences.joinToString("; ")}" }
        assertTrue(differing.isEmpty(), "The server answers ${differing.size} recordings otherwise:\n" + differing.joinToString("\n"))
        assertEquals(ParitySuite.all.size, results.size, "number of recordings that were replayed")
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
        /** The number of recordings that send a request to an endpoint of the legacy shim. */
        const val NEED_THE_SHIM = 44
    }
}
