package io.github.dnedrow.mockey.parity

import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Path

/** The server that a recording is replayed against, which decides what its response is compared with. */
enum class ParityServer {
    /** Legacy Mockey. Its response is compared with the recorded one. */
    LEGACY,

    /**
     * The new server. Its response is compared with the recorded one when the recording is labeled `same`, and
     * with the stated new response when the recording is labeled with deviations.
     */
    NEW,
}

class ParityReplayer(
    private val client: HttpClient = ParityHttp.newClient(),
    private val store: ParityCaseStore = ParityCaseStore(),
) {
    /** Sends the requests of [recording] to [baseUrl] and compares the response as [ResponseComparison] describes. */
    fun replay(
        recording: Path,
        baseUrl: URI,
        server: ParityServer = ParityServer.LEGACY,
    ): ReplayResult {
        val case = store.read(recording)
        val legacyResponse = requireNotNull(case.expectedResponse)
        val newResponse = if (server == ParityServer.NEW) newResponseOf(case, recording) else null
        case.before.forEach { client.exchange(it, baseUrl) }
        val exchange = client.exchange(case.request, baseUrl)
        val differences =
            if (newResponse == null) {
                ResponseComparison.differences(case.request, legacyResponse, exchange.response, case.snapshot)
            } else {
                ResponseComparison.differences(case.request, newResponse, exchange.response)
            }
        val timing = newResponse?.timing ?: case.timing
        return ReplayResult(recording.fileName.toString(), differences + compareTiming(timing, exchange.elapsedMillis))
    }

    /** The response that is stated for the new server, or null when the new server answers as legacy does. */
    private fun newResponseOf(
        case: ParityCase,
        recording: Path,
    ): NewResponse? =
        when (ParityLabel.parse(case.label)) {
            ParityLabel.Same -> null
            ParityLabel.Unreviewed -> error("$recording is not reviewed, so it says nothing about the new server.")
            is ParityLabel.Deviations -> checkNotNull(case.newResponse) { "$recording deviates but states no new response." }
        }

    private fun compareTiming(
        expected: ParityTiming?,
        elapsedMillis: Long,
    ): List<String> =
        if (expected == null || expected.allows(elapsedMillis)) {
            emptyList()
        } else {
            listOf("elapsed time: expected ${expected.describe()}, got $elapsedMillis ms")
        }
}
