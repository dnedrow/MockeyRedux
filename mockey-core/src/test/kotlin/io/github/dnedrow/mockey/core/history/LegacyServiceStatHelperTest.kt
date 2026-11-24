package io.github.dnedrow.mockey.core.history

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cases of the legacy `com.mockey.util.ServiceStatHelperTest`, with the names of its test methods. The
 * legacy helper told whether a time lies in a range, and it read the bounds of a range from texts. The
 * statistics of the history take the place of both: they count the entries whose time lies in a range, and
 * they take the bounds as times.
 */
class LegacyServiceStatHelperTest {
    private val service = serviceOf("Customers", "customer/{id}")
    private val minute: Duration = Duration.ofMinutes(1)

    /** Returns how many entries of a history with one entry at [time] the statistics count for the range from [from] to [to]. */
    private fun counted(
        time: Instant,
        from: Instant?,
        to: Instant?,
    ): Int {
        val history = HistoryStore()
        history.record(exchangeOf(proxied(service), time = time))
        return history
            .statistics(listOf(service), from, to)
            .services
            .single()
            .count
    }

    /** Legacy took a time between the bounds and a time that equals both bounds as in between, and a time before the start as not. */
    @Test
    fun testInBetween() {
        val now = NOON
        val nowPlusMinute = now + minute
        val nowMinusMinute = now - minute

        assertEquals(1, counted(now, nowMinusMinute, nowPlusMinute))
        assertEquals(1, counted(now, now, now))
        assertEquals(0, counted(nowMinusMinute, now, nowPlusMinute))
    }

    /**
     * Legacy read a bound from a text and took a default time for a text that was missing: the time of the
     * earliest entry for the start, and a minute from now for the end. A bound that is not given leaves the
     * range open here, which counts the same entries as those defaults.
     */
    @Test
    fun testGetDateFromString() {
        val history = HistoryStore()
        listOf(0L, 30L, 60L).forEach { history.record(exchangeOf(proxied(service), time = NOON.plusSeconds(it))) }
        val earliest = NOON
        val aMinuteFromNow = NOON.plusSeconds(60) + minute

        val withoutBounds = history.statistics(listOf(service))
        val withDefaults = history.statistics(listOf(service), from = earliest, to = aMinuteFromNow)

        assertEquals(withDefaults.services, withoutBounds.services)
        assertEquals(3, withoutBounds.services.single().count)
        assertEquals(earliest, withoutBounds.oldestEntryTime)
    }
}
