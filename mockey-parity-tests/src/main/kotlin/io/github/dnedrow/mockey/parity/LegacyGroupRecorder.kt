package io.github.dnedrow.mockey.parity

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Records case groups from a running legacy Mockey, and replays their recordings against it. Every case starts
 * from a freshly loaded fixture, the settings of its group, and an empty upstream session, so that no case
 * depends on what an earlier case left behind.
 */
class LegacyGroupRecorder(
    private val baseUrl: URI,
    private val resources: Path,
    private val loader: LegacyFixtureLoader = LegacyFixtureLoader(baseUrl),
    private val recorder: ParityRecorder = ParityRecorder(),
    private val replayer: ParityReplayer = ParityReplayer(),
) {
    /** Records every case of [groups] into the `parity` directory of [resources] and returns the case names. */
    fun record(groups: Collection<ParityCaseGroup>): List<String> =
        groups.flatMap { group ->
            val fixtureXml = Files.readString(resources.resolve(group.fixturePath))
            group.cases.map { (name, case) ->
                prepare(group)
                recorder.record(case.copy(fixtureXml = fixtureXml), baseUrl, recording(name))
                name
            }
        }

    /** Replays the recording of every case of [groups] and returns the results, the differences included. */
    fun replay(groups: Collection<ParityCaseGroup>): List<ReplayResult> =
        groups.flatMap { group ->
            group.cases.keys.map { name ->
                prepare(group)
                replayer.replay(recording(name), baseUrl, ParityServer.LEGACY)
            }
        }

    private fun recording(name: String): Path = resources.resolve("parity/$name")

    /** Puts legacy into the state in which a case of [group] starts. */
    private fun prepare(group: ParityCaseGroup) {
        loader.load(resources.resolve(group.fixturePath))
        group.outboundProxyCredentials?.let { loader.setOutboundProxy(it.proxyUrl, it.username, it.password) }
        group.tagFilter?.let(loader::setTagFilter)
        group.enabledTwist?.let { loader.enableTwist(it.legacyId) }
        loader.resetUpstreamSession()
    }
}
