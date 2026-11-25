package io.github.dnedrow.mockey.ui

import java.security.SecureRandom

/**
 * The reports that the server gave for forms, each as the page that shows it. After such a form the browser
 * is sent to the page of the report, which has an address of its own. The page can be loaded again, and
 * that does not send the form again.
 *
 * A report is nothing that the server keeps, so the UI keeps it, for a while: the newest reports, as many as
 * [capacity] says, and of them as many as together list no more than [budget] entries. A report that the UI
 * has dropped is gone. What it was about stays done.
 *
 * The identifier of a report is a number that nobody guesses, so a report is shown to the one who has its
 * address. The reports are gone when the UI stops.
 */
internal class Reports(
    private val capacity: Int = CAPACITY,
    private val budget: Int = BUDGET,
) {
    private class Kept(
        val page: Page,
        val entries: Int,
    )

    private val random = SecureRandom()
    private val kept = LinkedHashMap<String, Kept>()

    /**
     * Keeps [page], which shows a report that lists [entries] entries, and returns the identifier under
     * which [find] returns it.
     */
    @Synchronized
    fun keep(
        page: Page,
        entries: Int,
    ): String {
        val id = ProcessKey.encoded(ByteArray(ID_BYTES).also(random::nextBytes))
        kept[id] = Kept(page, entries)
        // The newest report stays, however long it is.
        while (kept.size > 1 && (kept.size > capacity || kept.values.sumOf { it.entries.toLong() } > budget)) {
            kept.remove(kept.keys.first())
        }
        return id
    }

    /** Returns the page of the report with the identifier [id], or `null` when the UI keeps no such report. */
    @Synchronized
    fun find(id: String): Page? = kept[id]?.page

    private companion object {
        const val CAPACITY = 32
        const val BUDGET = 100_000
        const val ID_BYTES = 18
    }
}
