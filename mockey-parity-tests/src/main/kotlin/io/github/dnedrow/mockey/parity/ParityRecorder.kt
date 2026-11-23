package io.github.dnedrow.mockey.parity

import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Path

class ParityRecorder(
    private val client: HttpClient = HttpClient.newHttpClient(),
    private val store: ParityCaseStore = ParityCaseStore(),
) {
    fun record(
        case: ParityCase,
        baseUrl: URI,
        destination: Path,
    ): ParityCase {
        val exchange = client.exchange(case.request, baseUrl)
        val timing =
            case.timing?.let { expected ->
                check(expected.allows(exchange.elapsedMillis)) {
                    "${destination.fileName} was answered in ${exchange.elapsedMillis} ms, expected ${expected.describe()}"
                }
                expected.copy(recordedMillis = exchange.elapsedMillis)
            }
        val recorded = case.copy(expectedResponse = exchange.response, timing = timing)
        store.write(recorded, destination)
        return recorded
    }
}
