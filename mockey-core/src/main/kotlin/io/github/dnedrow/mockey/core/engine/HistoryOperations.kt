package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.history.HistoryEntry
import io.github.dnedrow.mockey.core.history.HistoryEntryId
import io.github.dnedrow.mockey.core.history.HistoryQuery
import io.github.dnedrow.mockey.core.history.HistoryStatistics
import io.github.dnedrow.mockey.core.history.HistoryStore
import io.github.dnedrow.mockey.core.history.Replay
import io.github.dnedrow.mockey.core.history.ReplayEdits
import io.github.dnedrow.mockey.core.history.asScenario
import io.github.dnedrow.mockey.core.history.replay
import io.github.dnedrow.mockey.core.history.statistics
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.request.MockRequest
import java.time.Instant

/**
 * The history of an engine: the entries of the requests that it handled, and what a program does with them.
 * The history is held in memory only, and it keeps as many entries as the engine was created to keep. An entry
 * that would exceed that number takes the place of the oldest one.
 */
public class HistoryOperations internal constructor(
    private val engine: MockeyEngine,
    private val store: HistoryStore,
) {
    /**
     * Returns the entries that [query] asks for, with the newest one first. A query without anything, which
     * is the default, asks for every entry that the history keeps.
     */
    public fun entries(query: HistoryQuery = HistoryQuery()): List<HistoryEntry> = store.entries(query)

    /** Returns the entry with the identifier [id], or `null` when the history keeps no such entry. */
    public fun entry(id: HistoryEntryId): HistoryEntry? = store.entry(id)

    /** Deletes the entry with the identifier [id], and returns whether the history kept such an entry. */
    public fun delete(id: HistoryEntryId): Boolean = store.delete(id)

    /** Deletes the entries of the requests that the service [serviceId] handled, and returns how many these were. */
    public fun deleteOfService(serviceId: ServiceId): Int = store.deleteOfService(serviceId)

    /** Deletes every entry, and returns how many these were. */
    public fun clear(): Int = store.clear()

    /**
     * Sets the mark of the entry with the identifier [id] when [flagged] is on, and removes it otherwise.
     * Returns the entry as it is after that, or `null` when the history keeps no such entry.
     */
    public fun flag(
        id: HistoryEntryId,
        flagged: Boolean,
    ): HistoryEntry? = store.flag(id, flagged)

    /**
     * Replays the entry with the identifier [id]: lets the engine handle a request like the one of that
     * entry, with what [edits] change of it. The replay is a request like any other: it is handled under the
     * definitions as they are now, and it gets a history entry of its own, which states [clientAddress].
     *
     * An entry whose request body was cut when it was recorded is replayed only with a body that [edits]
     * give, because the entry does not hold the body that the client sent.
     */
    public suspend fun replay(
        id: HistoryEntryId,
        edits: ReplayEdits = ReplayEdits(),
        clientAddress: String? = null,
    ): ReplayResult =
        when (val replay = store.replay(id, edits) { engine.handle(it, clientAddress) }) {
            is Replay.Handled -> ReplayResult.Replayed(replay.request, replay.result)
            is Replay.Refused -> ReplayResult.Refused(replay.explanation)
            is Replay.NotFound -> ReplayResult.NotFound(replay.message)
        }

    /**
     * Creates a scenario from the entry with the identifier [id] for the service that handled its request:
     * a scenario that gives the response of the entry. [define] gets that scenario and returns it with its
     * name and whatever else it is to have.
     *
     * The change is rejected for an entry without a service, and for an entry whose response body was cut
     * when it was recorded. It finds nothing when the history keeps no such entry, or when the service of
     * the entry no longer exists.
     */
    public suspend fun saveAsScenario(
        id: HistoryEntryId,
        define: (Scenario) -> Scenario = { it },
    ): ChangeResult<Scenario> {
        val entry = store.entry(id) ?: return ChangeResult.NotFound("The history keeps no entry with the identifier $id.")
        return engine.change(entry.asScenario(define))
    }

    /**
     * Returns the statistics of the history: for every service that is defined, how many entries the history
     * keeps of it, and when the earliest of them arrived. Only the entries whose time is between [from] and
     * [to] count, the two bounds included, and a bound that is `null` leaves the range open at that end.
     */
    public fun statistics(
        from: Instant? = null,
        to: Instant? = null,
    ): HistoryStatistics = store.statistics(engine.definitions.services, from, to)
}

/** What became of the replay of a history entry. */
public sealed interface ReplayResult {
    /** The [request] of the replay was handled, and [handled] is what became of it. */
    public class Replayed(
        public val request: MockRequest,
        public val handled: HandledRequest,
    ) : ReplayResult

    /** The entry cannot be replayed as it was asked for, and nothing was handled. [explanation] says why. */
    public data class Refused(
        public val explanation: String,
    ) : ReplayResult

    /** The history keeps no entry with the identifier that was given. */
    public data class NotFound(
        public val message: String,
    ) : ReplayResult
}
