package io.github.dnedrow.mockey.parity

import kotlin.test.Test

class ProxyCasesTest {
    @Test
    fun everyProxyCaseHasARecordingOfItsCurrentFixtureAndRequests() = RecordedGroups.assertRecorded(ProxyCases.groups)

    @Test
    fun everyProxyRecordingBelongsToACase() = RecordedGroups.assertNoOtherRecordings(ProxyCases.groups, "proxy-")

    /**
     * Records the proxy groups from legacy Mockey, see [RecordedGroups.recordFromLegacy]. "proxy" selects every
     * group. The stub upstream and both stub outbound proxies run for the duration of the recording.
     */
    @Test
    fun recordsGroupFromLegacy() = RecordedGroups.recordFromLegacy(ProxyCases.groups, ::withStubs)

    /** Replays the proxy recordings against legacy Mockey, see [RecordedGroups.replayAgainstLegacy]. */
    @Test
    fun replaysGroupAgainstLegacy() = RecordedGroups.replayAgainstLegacy(ProxyCases.groups, ::withStubs)

    private fun withStubs(action: () -> Unit) =
        StubHttpServer.upstream(ProxyCases.UPSTREAM_PORT).use {
            StubHttpServer.outboundProxy(ProxyCases.OUTBOUND_PROXY_PORT).use {
                StubHttpServer.outboundProxy(ProxyCases.AUTHENTICATING_PROXY_PORT, requireProxyCredentials = true).use {
                    action()
                }
            }
        }
}
