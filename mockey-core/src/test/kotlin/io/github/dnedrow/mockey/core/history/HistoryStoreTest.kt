package io.github.dnedrow.mockey.core.history

import io.github.dnedrow.mockey.core.response.MockResponse
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HistoryStoreTest {
    private val gold = scenarioOf("gold")
    private val customers = serviceOf("Customers", "customer/{id}", gold)

    /** Records a request for `customer/<number>` and returns its entry. */
    private fun HistoryStore.recordRequest(number: Int): HistoryEntry =
        record(exchangeOf(served(customers, gold), requestFor("customer/$number"), NOON.plusSeconds(number.toLong())))

    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    // Bounded retention

    @Test
    fun anEntryThatExceedsTheLimitTakesThePlaceOfTheOldestOne() {
        val history = HistoryStore(maxEntries = 3)
        (1..3).forEach { history.recordRequest(it) }
        val before = history.entries().map { it.target }

        history.recordRequest(4)

        assertEquals(listOf("customer/3", "customer/2", "customer/1"), before)
        assertEquals(listOf("customer/4", "customer/3", "customer/2"), history.entries().map { it.target })
    }

    @Test
    fun theHistoryKeepsTheMostRecentEntriesHoweverManyRequestsAreHandled() {
        val history = HistoryStore(maxEntries = 3)

        (1..250).forEach { history.recordRequest(it) }

        assertEquals(listOf(HistoryEntryId(250), HistoryEntryId(249), HistoryEntryId(248)), history.entries().map { it.id })
    }

    @Test
    fun aHistoryKeepsAThousandEntriesUnlessAnotherNumberIsSet() {
        val history = HistoryStore()

        (1..1_001).forEach { history.recordRequest(it) }

        assertEquals(1_000, DEFAULT_HISTORY_LIMIT)
        assertEquals(1_000, history.entries().size)
        assertEquals(HistoryEntryId(1_001) to HistoryEntryId(2), history.entries().first().id to history.entries().last().id)
    }

    @Test
    fun aHistoryThatKeepsNoEntriesStillGivesEveryRequestItsEntry() {
        val history = HistoryStore(maxEntries = 0)

        val first = history.recordRequest(1)
        val second = history.recordRequest(2)

        assertEquals(emptyList(), history.entries())
        assertEquals(listOf(HistoryEntryId(1), HistoryEntryId(2)), listOf(first.id, second.id))
        assertEquals("customer/2", second.target)
    }

    @Test
    fun theLimitsOfAHistoryAreNotNegative() {
        assertFailsWith<IllegalArgumentException> { HistoryStore(maxEntries = -1) }
        assertFailsWith<IllegalArgumentException> { HistoryStore(maxBodyBytes = -1) }
    }

    @Test
    fun entriesThatAreRecordedAtTheSameTimeEachGetAnIdentifierOfTheirOwn() {
        val history = HistoryStore(maxEntries = 500)
        val start = CountDownLatch(1)

        val threads =
            (1..8).map {
                thread {
                    start.await()
                    repeat(100) { number -> history.recordRequest(number) }
                }
            }
        start.countDown()
        threads.forEach { it.join() }

        val ids = history.entries().map { it.id.value }
        assertEquals(500, ids.size)
        assertEquals(ids.sortedDescending(), ids)
        assertEquals(500, ids.toSet().size)
        assertEquals(800, ids.first())
    }

    // The body capture limit

    @Test
    fun aBodyThatIsLongerThanTheLimitIsCutInTheHistoryOnly() {
        val history = HistoryStore(maxBodyBytes = 1_024)
        val body = bytes(5_000)
        val response = MockResponse(200, headersOf("Content-Type" to "application/octet-stream"), body)

        val entry = history.record(exchangeOf(proxied(customers, response)))

        assertContentEquals(body.copyOf(1_024), entry.response.body.bytes)
        assertTrue(entry.response.body.isCut)
        assertEquals(5_000, entry.response.body.size)
        assertContentEquals(body, response.body)
        assertFalse(entry.request.body.isCut)
    }

    @Test
    fun theLimitHoldsForTheBodyOfTheRequestAndForTheBodyOfTheResponseEachOnItsOwn() {
        val history = HistoryStore(maxBodyBytes = 10)
        val request = requestFor("customer/7", "POST", body = "a request body of 28 bytes..".toByteArray())

        val entry = history.record(exchangeOf(proxied(customers, MockResponse(200, body = "short".toByteArray())), request))

        assertEquals("a request " to true, entry.request.bodyText to entry.request.body.isCut)
        assertEquals(28, entry.request.body.size)
        assertEquals("short" to false, entry.response.bodyText to entry.response.body.isCut)
        assertEquals("a request body of 28 bytes..", request.bodyText)
    }

    @Test
    fun aBodyThatIsAsLongAsTheLimitIsHeldInFull() {
        val history = HistoryStore(maxBodyBytes = 1_024)
        val exact = bytes(1_024)
        val oneMore = bytes(1_025)

        val atLimit = history.record(exchangeOf(proxied(customers, MockResponse(200, body = exact))))
        val overLimit = history.record(exchangeOf(proxied(customers, MockResponse(200, body = oneMore))))

        assertContentEquals(exact, atLimit.response.body.bytes)
        assertFalse(atLimit.response.body.isCut)
        assertEquals(1_024, overLimit.response.body.bytes.size)
        assertTrue(overLimit.response.body.isCut)
    }

    @Test
    fun anEntryHoldsOneMebibyteOfABodyUnlessAnotherLimitIsSet() {
        val history = HistoryStore()
        val large = bytes(DEFAULT_BODY_CAPTURE_LIMIT + 1)

        val entry = history.record(exchangeOf(proxied(customers, MockResponse(200, body = large))))

        assertEquals(1_048_576, DEFAULT_BODY_CAPTURE_LIMIT)
        assertEquals(1_048_576, entry.response.body.bytes.size)
        assertTrue(entry.response.body.isCut)
    }

    @Test
    fun aHistoryThatHoldsNoBodyBytesMarksEveryBodyThatHasSomeAsCut() {
        val history = HistoryStore(maxBodyBytes = 0)

        val entry = history.record(exchangeOf(proxied(customers, MockResponse(200, body = "answer".toByteArray()))))

        assertEquals(0 to true, entry.response.body.bytes.size to entry.response.body.isCut)
        assertEquals(0 to false, entry.request.body.bytes.size to entry.request.body.isCut)
    }

    @Test
    fun theParametersOfAFormAreRecordedInFullAlsoWhenItsBodyIsCut() {
        val history = HistoryStore(maxBodyBytes = 4)
        val form =
            requestFor(
                "customer/7",
                "POST",
                null,
                headersOf("Content-Type" to "application/x-www-form-urlencoded"),
                "a=1&b=2".toByteArray(),
            )

        val entry = history.record(exchangeOf(served(customers, gold), form))

        assertEquals(listOf("a" to "1", "b" to "2"), entry.request.parameters.map { it.name to it.value })
        assertEquals("a=1&", entry.request.bodyText)
    }

    // In memory only

    @Test
    fun aNewHistoryIsEmptyWhateverAnotherOneHolds() {
        val before = HistoryStore()
        before.recordRequest(1)

        val afterRestart = HistoryStore()

        assertEquals(1, before.entries().size)
        assertEquals(emptyList(), afterRestart.entries())
        assertEquals(HistoryEntryId(1), afterRestart.recordRequest(1).id)
    }

    @Test
    fun theListOfEntriesIsAValueOfItsOwn() {
        val history = HistoryStore()
        history.recordRequest(1)

        val listed = history.entries()
        history.recordRequest(2)

        assertEquals(1, listed.size)
        assertNotEquals(listed, history.entries())
    }
}
