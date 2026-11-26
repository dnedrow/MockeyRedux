package io.github.dnedrow.mockey.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** The time range of the statistics: how a bound is read from the address, and how the form shows it. */
class TimeRangeTest {
    /**
     * A bound can be written the way a browser submits it, the way the pages display a time, or with an
     * offset. A time without an offset is in UTC.
     */
    @Test
    fun aBoundIsReadInSeveralForms() {
        val written =
            mapOf(
                "2026-01-02T03:04" to "2026-01-02T03:04:00Z",
                "2026-01-02T03:04:05" to "2026-01-02T03:04:05Z",
                "2026-01-02T03:04:05.678" to "2026-01-02T03:04:05.678Z",
                "2026-01-02 03:04:05 UTC" to "2026-01-02T03:04:05Z",
                "2026-01-02 03:04:05" to "2026-01-02T03:04:05Z",
                "2026-01-02 03:04" to "2026-01-02T03:04:00Z",
                "2026-01-02T03:04:05Z" to "2026-01-02T03:04:05Z",
                "2026-01-02T03:04:05+01:00" to "2026-01-02T02:04:05Z",
                "2026-01-02T03:04:05.5-08:00" to "2026-01-02T11:04:05.500Z",
                "  2026-01-02T03:04:05  " to "2026-01-02T03:04:05Z",
                " 2026-01-02T03:04:05Z\t" to "2026-01-02T03:04:05Z",
            )

        for ((text, time) in written) {
            val range = TimeRange(text, text)
            assertEquals(listOf(time, time, true), listOf(range.from, range.to, range.isValid), text)
        }
    }

    /** A blank bound leaves its end of the range open. */
    @Test
    fun aBlankBoundLeavesTheRangeOpen() {
        val open = TimeRange("", "  ")
        val half = TimeRange("2026-01-02T03:04", "")
        val otherHalf = TimeRange(" ", "2026-01-02T03:04")

        assertEquals(listOf(null, null, true, false), listOf(open.from, open.to, open.isValid, open.isSet))
        assertEquals(listOf("2026-01-02T03:04:00Z", null, true, true), listOf(half.from, half.to, half.isValid, half.isSet))
        val ofOtherHalf = listOf(otherHalf.from, otherHalf.to, otherHalf.isValid, otherHalf.isSet)
        assertEquals(listOf(null, "2026-01-02T03:04:00Z", true, true), ofOtherHalf)
        assertEquals(emptyList(), open.errors + half.errors)
    }

    /** A bound that isn't a time has a message for its field, and the range isn't valid. */
    @Test
    fun aBoundThatIsNotATimeIsReported() {
        for (text in listOf("yesterday", "2026-01-02", "2026-13-01T00:00", "03:04", "2026-01-02T25:00", "2026-01-02T03:04:05 CET", "UTC")) {
            val range = TimeRange(text, "2026-01-02T03:04")

            assertEquals(listOf(null, "2026-01-02T03:04:00Z", false, true), listOf(range.from, range.to, range.isValid, range.isSet), text)
            val message = "$text is not a time that the UI can read. Write it like 2026-01-02T03:04:05, in UTC."
            assertEquals(listOf("from" to message), range.errors.map { it.field to it.message }, text)
        }
        assertEquals(listOf("from", "to"), TimeRange("x", "y").errors.map { it.field })
    }

    /** The form shows a bound the way a `datetime-local` field expects it: in UTC, to the second, without an offset. */
    @Test
    fun theFormShowsABoundToTheSecondInUtc() {
        val state = TimeRange("2026-01-02T03:04:05.678+01:00", "2026-01-02 03:04").formState()
        val unreadable = TimeRange("soon", "").formState()

        assertEquals("2026-01-02T02:04:05" to "2026-01-02T03:04:00", state.value("from") to state.value("to"))
        assertEquals(false, state.isRejected)
        assertEquals("" to "", unreadable.value("from") to unreadable.value("to"))
        assertEquals(1, unreadable.messagesAt("from").size)
    }
}
