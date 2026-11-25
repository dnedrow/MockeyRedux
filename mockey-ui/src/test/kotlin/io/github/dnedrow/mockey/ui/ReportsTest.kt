package io.github.dnedrow.mockey.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task 13.3: the reports that the UI keeps for a while, see [Reports]. */
class ReportsTest {
    private fun page(title: String): Page = Page(title) {}

    @Test
    fun aReportIsFoundUnderTheIdentifierThatItWasKeptUnder() {
        val reports = Reports()

        val ids = listOf("first", "second").map { reports.keep(page(it), entries = 1) }

        assertEquals(listOf("first", "second"), ids.map { reports.find(it)?.title })
        assertEquals(null, reports.find("another"))
        assertTrue(ids.all { Regex("[A-Za-z0-9_-]{24}").matches(it) } && ids.toSet().size == 2, "$ids")
    }

    /** The UI keeps the newest reports: as many as it is told, and of them as many as together list no more entries than it is told. */
    @Test
    fun theUiKeepsTheNewestReportsUpToANumberAndUpToANumberOfEntries() {
        val byNumber = Reports(capacity = 2, budget = 1_000)
        val byEntries = Reports(capacity = 10, budget = 100)

        val numbered = listOf("a", "b", "c").map { byNumber.keep(page(it), entries = 1) }
        val weighed = listOf("a" to 60, "b" to 30, "c" to 20, "d" to 500).map { (title, entries) -> byEntries.keep(page(title), entries) }
        val afterTheLong = weighed.map { byEntries.find(it)?.title }
        val next = byEntries.keep(page("e"), entries = 0)

        assertEquals(listOf(null, "b", "c"), numbered.map { byNumber.find(it)?.title })
        assertEquals(listOf(null, null, null, "d"), afterTheLong)
        assertEquals(listOf(null, "e"), listOf(weighed.last(), next).map { byEntries.find(it)?.title })
    }
}
