package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.history.HistoryEntry
import io.ktor.http.HttpMethod

/**
 * The operations of the admin API on the history of [engine]: the entries of the requests that the mock
 * endpoint handled. The entries are stated with the newest one first.
 */
internal class AdminHistory(
    private val engine: MockeyEngine,
) {
    /** The routes of the operations. */
    val routes: List<AdminRoute> =
        listOf(
            AdminRoute(HttpMethod.Get, "history") { AdminResponse.json(engine.history.entries().map(HistoryEntry::toAdminDto)) },
        )
}
