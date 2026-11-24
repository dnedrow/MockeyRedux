package io.github.dnedrow.mockey.core.history

import io.github.dnedrow.mockey.core.model.ServiceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading one entry, deleting entries, and flagging them. */
class HistoryMaintenanceTest {
    private val gold = scenarioOf("gold")
    private val customers = serviceOf("Customers", "customer/{id}", gold)
    private val orders = serviceOf("Orders", "orders")
    private val history = HistoryStore()

    private fun record(
        target: String,
        handling: Handling = proxied(orders),
    ): HistoryEntry = history.record(exchangeOf(handling, requestFor(target)))

    private fun targets(): List<String> = history.entries().map { it.target }

    // Reading one entry

    @Test
    fun anEntryIsReadByItsIdentifier() {
        val (a, b) = listOf("a", "b").map { record(it) }

        assertEquals(a, history.entry(a.id))
        assertEquals(b, history.entry(b.id))
        assertNull(history.entry(HistoryEntryId(99)))
    }

    // Deleting

    @Test
    fun oneEntryIsDeleted() {
        val (_, b, _) = listOf("a", "b", "c").map { record(it) }

        val deleted = history.delete(b.id)

        assertTrue(deleted)
        assertEquals(listOf("c", "a"), targets())
        assertNull(history.entry(b.id))
    }

    @Test
    fun anEntryThatTheHistoryDoesNotKeepIsNotDeleted() {
        val a = record("a")
        history.delete(a.id)

        assertFalse(history.delete(a.id))
        assertFalse(history.delete(HistoryEntryId(99)))
        assertEquals(emptyList(), targets())
    }

    @Test
    fun theEntriesOfOneServiceAreDeleted() {
        record("customer/1", served(customers, gold))
        record("orders/1")
        record("customer/2", served(customers, gold))
        record("unknown", Handling.noServiceMatched("No service matches the target 'unknown'."))
        record("forwarded", proxied(null))

        val deleted = history.deleteOfService(customers.id)

        assertEquals(2, deleted)
        assertEquals(listOf("forwarded", "unknown", "orders/1"), targets())
        assertEquals(0, history.deleteOfService(customers.id))
        assertEquals(0, history.deleteOfService(ServiceId("no-such-service")))
    }

    @Test
    fun allEntriesAreDeleted() {
        listOf("a", "b", "c").forEach { record(it) }

        val deleted = history.clear()

        assertEquals(3, deleted)
        assertEquals(emptyList(), targets())
        assertEquals(0, history.clear())
    }

    @Test
    fun anEntryThatIsRecordedAfterADeletionGetsAnIdentifierThatNoEntryHadBefore() {
        val before = listOf("a", "b").map { record(it).id }
        history.clear()

        val after = record("c")

        assertEquals(listOf(HistoryEntryId(1), HistoryEntryId(2)), before)
        assertEquals(HistoryEntryId(3), after.id)
    }

    @Test
    fun aDeletedEntryMakesRoomForAnotherOne() {
        val limited = HistoryStore(maxEntries = 2)
        val first = limited.record(exchangeOf(proxied(orders), requestFor("a")))
        limited.record(exchangeOf(proxied(orders), requestFor("b")))

        limited.delete(first.id)
        limited.record(exchangeOf(proxied(orders), requestFor("c")))

        assertEquals(listOf("c", "b"), limited.entries().map { it.target })
    }

    // Flagging

    @Test
    fun anEntryIsFlaggedAndUnflagged() {
        val entry = record("a")

        val flagged = history.flag(entry.id, flagged = true)
        val readWhileFlagged = history.entry(entry.id)
        val unflagged = history.flag(entry.id, flagged = false)

        assertFalse(entry.flagged)
        assertEquals(entry.copy(flagged = true), flagged)
        assertEquals(flagged, readWhileFlagged)
        assertEquals(entry, unflagged)
        assertEquals(entry, history.entry(entry.id))
    }

    @Test
    fun aFlagIsReportedWithTheEntryInTheListAndLeavesItInItsPlace() {
        val (_, b, _) = listOf("a", "b", "c").map { record(it) }

        history.flag(b.id, flagged = true)
        history.flag(b.id, flagged = true)

        assertEquals(listOf("c" to false, "b" to true, "a" to false), history.entries().map { it.target to it.flagged })
    }

    @Test
    fun anEntryThatTheHistoryDoesNotKeepIsNotFlagged() {
        record("a")

        assertNull(history.flag(HistoryEntryId(99), flagged = true))
        assertEquals(listOf(false), history.entries().map { it.flagged })
    }
}
