package io.github.dnedrow.mockey.parity

import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Path

class ParityRecorder(
    private val client: HttpClient = ParityHttp.newClient(),
    private val store: ParityCaseStore = ParityCaseStore(),
) {
    /**
     * Sends the requests of [case] to [baseUrl] and stores the response to the last one in [destination].
     *
     * A recording that was reviewed keeps its label and its stated new response when nothing that the review
     * rested on has changed: the fixture, the requests, the timing bounds, and the response as far as it is
     * compared. Otherwise the recording is stored as unreviewed.
     */
    fun record(
        case: ParityCase,
        baseUrl: URI,
        destination: Path,
    ): ParityCase {
        case.before.forEach { client.exchange(it, baseUrl) }
        val exchange = client.exchange(case.request, baseUrl)
        val timing =
            case.timing?.let { expected ->
                check(expected.allows(exchange.elapsedMillis)) {
                    "${destination.fileName} was answered in ${exchange.elapsedMillis} ms, expected ${expected.describe()}"
                }
                expected.copy(recordedMillis = exchange.elapsedMillis)
            }
        val response = exchange.response
        val review = store.readOrNull(destination)?.takeIf { it.label != ParityCase.UNREVIEWED && it.restsOn(case, response) }
        val recorded =
            case.copy(
                label = review?.label ?: ParityCase.UNREVIEWED,
                expectedResponse = response,
                timing = timing,
                newResponse = review?.newResponse,
            )
        store.write(recorded, destination)
        return recorded
    }

    /** Whether this reviewed recording was made of the same [case] and holds a response like [response]. */
    private fun ParityCase.restsOn(
        case: ParityCase,
        response: ParityResponse,
    ): Boolean =
        fixtureXml == case.fixtureXml &&
            request.sameAs(case.request) &&
            before.size == case.before.size &&
            before.zip(case.before).all { (stored, current) -> stored.sameAs(current) } &&
            timing?.copy(recordedMillis = null) == case.timing &&
            snapshot == case.snapshot &&
            expectedResponse != null &&
            ResponseComparison.differences(request, expectedResponse, response, snapshot).isEmpty()
}
