package io.github.dnedrow.mockey.parity

import io.github.dnedrow.mockey.server.ServerUnderTest
import java.nio.file.Files
import java.nio.file.Path

/** How the fixture of a group gets into the servers of its cases. Both ways lead through the importer of legacy definitions. */
internal enum class FixtureLoading {
    /** Every server gets the fixture as its initial definitions, which it imports when it starts and keeps in memory. */
    INITIAL_DEFINITIONS,

    /**
     * The command `import-legacy` of the program stores the definitions of the fixture once for the group,
     * and every server loads what the command stored. This is how an installation of Mockey 2.x moves over.
     */
    IMPORT_COMMAND,
}

/**
 * Replays the recordings of case groups against the new server, as [LegacyGroupRecorder] replays them against
 * legacy.
 *
 * Every case gets a server of its own, which [ServerUnderTest] starts in the process of the test from the
 * arguments of the program. So every case starts from the definitions of the fixture and an empty upstream
 * session, and no case depends on what an earlier case left behind. The servers are in read-only mode, so
 * that none of them changes what another one loads.
 *
 * The settings of a group that its fixture cannot carry are applied as they are for legacy. The tag filter is a
 * setting of the program. The enabled twist configuration and the credentials of the outbound proxy are set in
 * the running server, through [ServerUnderTest] until the admin API sets them.
 *
 * A case that [needsLegacyShim] is not replayed.
 */
internal class NewServerReplay(
    private val resources: Path,
    private val loading: FixtureLoading,
    private val replayer: ParityReplayer = ParityReplayer(),
) {
    /** Replays the recording of every case of [groups] that the server can answer, and returns the results, the differences included. */
    fun replay(groups: Collection<ParityCaseGroup>): List<ReplayResult> {
        val work = Files.createTempDirectory("mockey-parity-")
        try {
            return groups.flatMap { group ->
                val replayed = group.cases.filterValues { !needsLegacyShim(it) }.keys
                if (replayed.isEmpty()) return@flatMap emptyList()
                val arguments = serverArguments(group, Files.createDirectory(work.resolve(group.name)))
                replayed.map { name ->
                    start(group, arguments).use { server -> replayer.replay(recording(name), server.mockUrl, ParityServer.NEW) }
                }
            }
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    private fun recording(name: String): Path = resources.resolve("parity/$name")

    /**
     * The arguments that start a server for a case of [group]. Its definitions location is [definitions], an
     * empty directory, which the import command fills where the fixture is [loading] through it.
     */
    private fun serverArguments(
        group: ParityCaseGroup,
        definitions: Path,
    ): List<String> {
        val fixture = resources.resolve(group.fixturePath).toString()
        val fixtureArguments =
            when (loading) {
                FixtureLoading.INITIAL_DEFINITIONS -> {
                    listOf("--initial-definitions", fixture)
                }

                FixtureLoading.IMPORT_COMMAND -> {
                    ServerUnderTest.run(listOf("import-legacy", fixture, "--definitions", definitions.toString()))
                    emptyList()
                }
            }
        val tagFilter = group.tagFilter?.let { listOf("--tag-filter", it) }.orEmpty()
        return listOf("--definitions", definitions.toString()) + fixtureArguments + tagFilter + listenerArguments
    }

    /** Starts a server for a case of [group] with [arguments] and gives it the settings that only a running server takes. */
    private fun start(
        group: ParityCaseGroup,
        arguments: List<String>,
    ): ServerUnderTest {
        val server = ServerUnderTest.start(arguments)
        try {
            group.outboundProxyCredentials?.let { server.useOutboundProxy(it.proxyUrl, it.username, it.password) }
            group.enabledTwist?.let { server.enableTwist(it.name) }
        } catch (failure: RuntimeException) {
            server.close()
            throw failure
        }
        return server
    }

    companion object {
        /**
         * The legacy automation endpoints, which the new server answers only when its legacy shim is enabled.
         * `/service_scenario`, the second path of legacy to `/config/service`, is none of them (DEV-26).
         */
        val legacyShimEndpoints = setOf("/config/service", "/plan/setup", "/definitions", "/home")

        /**
         * Whether [case] sends a request to an endpoint of the legacy shim, be it the recorded request or one
         * before it. The server has no such shim yet, so it cannot answer the case as its recording states.
         */
        fun needsLegacyShim(case: ParityCase): Boolean =
            (case.before + case.request).any { it.target.substringBefore('?') in legacyShimEndpoints }

        /**
         * The listeners of a server under test. The mock listener is on `127.0.0.1`, as legacy was when the cases
         * were recorded: some cases look at the address that the client wrote. Both listeners take a free port,
         * and the log holds what goes wrong and nothing else.
         */
        private val listenerArguments =
            listOf("--mock-host", "127.0.0.1", "--mock-port", "0", "--admin-port", "0", "--log-level", "warn")
    }
}
