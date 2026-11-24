package io.github.dnedrow.mockey.core.history

import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.response.MockResponse
import io.github.dnedrow.mockey.core.routing.Target
import java.time.Instant

/** How many entries a history keeps, where no other number is set. */
internal const val DEFAULT_HISTORY_LIMIT: Int = 1_000

/** How many bytes of a body a history entry holds, where no other number is set: 1 MiB. */
internal const val DEFAULT_BODY_CAPTURE_LIMIT: Int = 1_048_576

/**
 * A request that was handled at the mock endpoint, as the history records it. [time] is when the request
 * arrived. [receivedTarget] is the target as the client wrote it, and [target] is the target that the request
 * was handled as, which differs from it when twisting changed it.
 */
internal class Exchange(
    val time: Instant,
    val clientAddress: String?,
    val request: MockRequest,
    val receivedTarget: Target,
    val target: Target,
    val handling: Handling,
    val inspectionMessages: List<String> = emptyList(),
)

/**
 * The history of one engine: the entries of the requests that were handled at its mock endpoint.
 *
 * The history keeps at most [maxEntries] entries. An entry that would exceed that number takes the place of
 * the oldest one. An entry holds at most [maxBodyBytes] bytes of the body of its request and as many of the
 * body of its response, and it marks a body of which it holds less than all. What a client or a real service
 * gets is not the business of that limit.
 *
 * The history is held in memory and nowhere else, so it ends with its engine. Any number of threads can use
 * it at the same time.
 */
internal class HistoryStore(
    private val maxEntries: Int = DEFAULT_HISTORY_LIMIT,
    private val maxBodyBytes: Int = DEFAULT_BODY_CAPTURE_LIMIT,
) {
    init {
        require(maxEntries >= 0) { "The number of history entries to keep must not be negative." }
        require(maxBodyBytes >= 0) { "The number of bytes of a body to hold must not be negative." }
    }

    /** The entries, with the oldest one first. */
    private val entries = ArrayDeque<HistoryEntry>()
    private var recorded = 0L

    /**
     * Records [exchange] and returns its entry. The entry gets an identifier that no entry of this history had
     * before. A history that keeps no entries returns the entry all the same.
     */
    @Synchronized
    fun record(exchange: Exchange): HistoryEntry {
        val entry = entryOf(HistoryEntryId(++recorded), exchange)
        entries.addLast(entry)
        while (entries.size > maxEntries) entries.removeFirst()
        return entry
    }

    /** Returns the entries that the history keeps, with the newest one first. */
    @Synchronized
    fun entries(): List<HistoryEntry> = entries.reversed()

    private fun entryOf(
        id: HistoryEntryId,
        exchange: Exchange,
    ): HistoryEntry {
        val handling = exchange.handling
        return HistoryEntry(
            id = id,
            time = exchange.time,
            clientAddress = exchange.clientAddress,
            request = recorded(exchange.request),
            response = recorded(handling.response),
            target = exchange.target.text,
            targetBeforeTwisting = exchange.receivedTarget.text.takeIf { it != exchange.target.text },
            service = handling.service?.let { RecordedService(it.id, it.name, it.tags) },
            responseMode = handling.responseMode,
            scenario = handling.scenario?.let { RecordedScenario(it.id, it.name, it.tags) },
            outcome = handling.outcome,
            inspectionMessages = exchange.inspectionMessages.toList(),
            explanation = handling.explanation,
        )
    }

    private fun recorded(request: MockRequest): RecordedRequest =
        RecordedRequest(
            method = request.method,
            url = request.url,
            query = request.query,
            headers = request.headers,
            parameters = request.parameters,
            cookies = request.cookies,
            body = RecordedBody(request.bodyStart(maxBodyBytes), request.bodySize.toLong()),
        )

    private fun recorded(response: MockResponse): RecordedResponse =
        RecordedResponse(
            status = response.status,
            headers = response.headers,
            body = RecordedBody(response.bodyStart(maxBodyBytes), response.bodySize.toLong()),
        )
}
