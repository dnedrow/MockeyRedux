package io.github.dnedrow.mockey.core.history

import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.response.MockResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryQueryTest {
    private val gold = scenarioOf("gold", tags = Tags.of("vip"))
    private val customers = serviceOf("Customers", "customer/{id}", gold, tags = Tags.of("crm regression"))
    private val orders = serviceOf("Orders", "orders")
    private val history = HistoryStore()

    /** Records a request for [target] that [handling] answers, and returns its entry. */
    private fun record(
        target: String,
        handling: Handling = proxied(orders),
        query: String? = null,
    ): HistoryEntry = history.record(exchangeOf(handling, requestFor(target, query = query)))

    private fun answer(body: String) = proxied(orders, MockResponse(200, body = body.toByteArray()))

    private fun targets(query: HistoryQuery): List<String> = history.entries(query).map { it.target }

    private fun targets(vararg tokens: String): List<String> = targets(HistoryQuery(tokens = tokens.toList()))

    // Listing

    @Test
    fun entriesAreListedWithTheNewestOneFirst() {
        listOf("a", "b", "c").forEach { record(it) }

        assertEquals(listOf("c", "b", "a"), targets(HistoryQuery()))
        assertEquals(history.entries(), history.entries(HistoryQuery()))
    }

    @Test
    fun onlyTheEntriesThatWereRecordedAfterAGivenOneAreListed() {
        val (a, b, c) = listOf("a", "b", "c").map { record(it) }

        assertEquals(listOf("c", "b"), targets(HistoryQuery(after = a.id)))
        assertEquals(listOf("c"), targets(HistoryQuery(after = b.id)))
        assertEquals(emptyList(), targets(HistoryQuery(after = c.id)))
        assertEquals(emptyList(), targets(HistoryQuery(after = HistoryEntryId(99))))
        assertEquals(listOf("c", "b", "a"), targets(HistoryQuery(after = HistoryEntryId(0))))
    }

    @Test
    fun theEntriesAfterOneThatIsNoLongerKeptAreListed() {
        val limited = HistoryStore(maxEntries = 2)
        val entries = listOf("a", "b", "c", "d").map { limited.record(exchangeOf(proxied(orders), requestFor(it))) }

        val afterDiscarded = limited.entries(HistoryQuery(after = entries[0].id)).map { it.target }
        val afterDeleted = limited.also { it.delete(entries[2].id) }.entries(HistoryQuery(after = entries[2].id)).map { it.target }

        assertEquals(listOf("d", "c"), afterDiscarded)
        assertEquals(listOf("d"), afterDeleted)
    }

    @Test
    fun atMostAsManyEntriesAsAskedForAreListedAndTheseAreTheNewest() {
        listOf("a", "b", "c").forEach { record(it) }

        assertEquals(listOf("c", "b"), targets(HistoryQuery(limit = 2)))
        assertEquals(listOf("c", "b", "a"), targets(HistoryQuery(limit = 10)))
        assertEquals(emptyList(), targets(HistoryQuery(limit = 0)))
        assertFailsWith<IllegalArgumentException> { HistoryQuery(limit = -1) }
    }

    @Test
    fun theNewestOfTheEntriesThatSatisfyATokenAreListed() {
        (1..50).forEach { number -> record(if (number % 4 == 0) "orders/$number" else "customer/$number") }

        val listed = targets(HistoryQuery(tokens = listOf("orders"), limit = 5))

        assertEquals(12, targets("orders").size)
        assertEquals(listOf("orders/48", "orders/44", "orders/40", "orders/36", "orders/32"), listed)
    }

    @Test
    fun theEntriesOfOneServiceAreListed() {
        record("customer/1", served(customers, gold))
        record("orders")
        record("customer/2", served(customers, gold))
        record("unknown", Handling.noServiceMatched("No service matches the target 'unknown'."))

        assertEquals(listOf("customer/2", "customer/1"), targets(HistoryQuery(serviceId = customers.id)))
        assertEquals(listOf("orders"), targets(HistoryQuery(serviceId = orders.id)))
        assertEquals(emptyList(), targets(HistoryQuery(serviceId = ServiceId("no-such-service"))))
    }

    @Test
    fun everythingThatAQueryAsksForHoldsAtOnce() {
        val first = record("customer/1", served(customers, gold))
        record("orders/2")
        record("customer/3", served(customers, gold))
        record("customer/4", served(customers, gold), query = "tier=gold")
        record("customer/5", served(customers, gold))

        val listed = targets(HistoryQuery(tokens = listOf("customer", "!tier"), serviceId = customers.id, after = first.id, limit = 1))

        assertEquals(listOf("customer/5"), listed)
    }

    // Filtering by tokens

    @Test
    fun anEntryMustSatisfyEveryToken() {
        record("orders/1", answer("gold member"))
        record("orders/2", answer("silver member"))

        assertEquals(listOf("orders/1"), targets("orders", "gold"))
        assertEquals(listOf("orders/2", "orders/1"), targets("orders", "member"))
        assertEquals(emptyList(), targets("orders", "gold", "silver"))
    }

    @Test
    fun aNegatedTokenIsSatisfiedByAnEntryThatDoesNotHoldItsRest() {
        record("orders/1", answer("gold member"))
        record("orders/2", answer("silver member"))

        assertEquals(listOf("orders/2"), targets("orders", "!gold"))
        assertEquals(listOf("orders/1"), targets("!silver"))
        assertEquals(emptyList(), targets("!member"))
        assertEquals(emptyList(), targets("gold", "!gold"))
        assertEquals(listOf("orders/2", "orders/1"), targets("!platinum", "!bronze"))
    }

    @Test
    fun theLetterCaseOfATokenMatters() {
        record("orders/1", answer("Gold member"))

        assertEquals(emptyList(), targets("gold"))
        assertEquals(listOf("orders/1"), targets("Gold"))
        assertEquals(listOf("orders/1"), targets("!gold"))
        assertEquals(emptyList(), targets("!Gold"))
        assertEquals(emptyList(), targets("ORDERS"))
    }

    @Test
    fun aTokenIsLookedForExactlyAsItIsWritten() {
        record("orders/1", answer("gold  member"))
        record("orders/2", answer("gold member"))

        assertEquals(listOf("orders/2"), targets("gold member"))
        assertEquals(listOf("orders/1"), targets("gold  member"))
        assertEquals(listOf("orders/2", "orders/1"), targets("gold "))
        assertEquals(emptyList(), targets(" gold"))
        assertEquals(listOf("orders/2", "orders/1"), targets("orders/"))
    }

    @Test
    fun aBlankTokenIsSatisfiedByEveryEntryAndTheTokenOfOnlyAnExclamationMarkByNone() {
        record("orders/1")

        assertEquals(listOf("orders/1"), targets("", "  ", "orders"))
        assertEquals(emptyList(), targets("!"))
        assertEquals(listOf("orders/1"), targets("!!"))
        assertEquals(listOf("orders/1"), targets("! "))
    }

    @Test
    fun aTokenIsLookedForInEveryTextOfAnEntryThatIsSearched() {
        val service = serviceOf("Customers", "customer/{id}", gold, tags = Tags.of("crm regression"))
        val sent = headersOf("X-Request-Id" to "req-7", "Content-Type" to "application/x-www-form-urlencoded")
        val request = requestFor("customer/7", "POST", "expand=orders&name=jane%20doe", sent, "field=form+value".toByteArray())
        val response = MockResponse(201, headersOf("X-Tier" to "golden"), "answered body".toByteArray())
        val handling = Handling(service, null, gold, Outcome.SCENARIO_SERVED, response)
        val entry = history.record(exchangeOf(handling, request, clientAddress = "192.0.2.10"))

        val found =
            listOf(
                service.id.value,
                "Customers",
                "crm regression",
                "vip",
                "192.0.2.10",
                "mockey.test:8080/service/customer/7",
                "customer/7?expand=orders",
                "name=jane%20doe",
                "X-Request-Id: req-7",
                "req-7",
                "expand=orders",
                "name=jane doe",
                "field=form value",
                "field=form+value",
                "X-Tier: golden",
                "answered body",
            )

        assertEquals(emptyList(), found.filterNot { entry.satisfies(it) })
        assertEquals(emptyList(), found.filter { entry.satisfies("!$it") })
    }

    @Test
    fun aTokenIsNotLookedForInWhatIsNotSearched() {
        // The identifier of a service is searched. One that is made at random can hold the digits of the status.
        val service = customers.copy(id = ServiceId("customers"))
        val response = MockResponse(201, body = "answered".toByteArray())
        val handling = Handling(service, null, gold, Outcome.SCENARIO_SERVED, response, explanation = "explained")
        val request = requestFor("customer/7", "PATCH")
        val entry = history.record(exchangeOf(handling, request, inspectionMessages = listOf("inspected"), target = targetOf(request)))

        val notSearched = listOf("gold", gold.id.value, "PATCH", "201", "SCENARIO_SERVED", "inspected", "explained", entry.time.toString())

        assertEquals(emptyList(), notSearched.filter { entry.satisfies(it) })
    }

    @Test
    fun anEntryWithoutAServiceAndWithoutAClientAddressIsSearchedInWhatItHas() {
        val entry =
            history.record(
                exchangeOf(Handling.noServiceMatched("No service matches the target 'foo'."), requestFor("foo"), clientAddress = null),
            )

        assertTrue(entry.satisfies("foo"))
        assertTrue(entry.satisfies("No service matches"))
        assertFalse(entry.satisfies("null"))
        assertTrue(entry.satisfies("!Customers"))
    }

    @Test
    fun aBodyIsSearchedAsFarAsTheEntryHoldsIt() {
        val limited = HistoryStore(maxBodyBytes = 4)
        val entry = limited.record(exchangeOf(answer("gold member")))

        assertTrue(entry.satisfies("gold"))
        assertFalse(entry.satisfies("member"))
    }

    @Test
    fun aBodyIsSearchedAsTheTextThatItsCharsetMakesOfIt() {
        val latin1 =
            MockResponse(200, headersOf("Content-Type" to "text/plain; charset=ISO-8859-1"), byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte()))
        val entry = history.record(exchangeOf(proxied(orders, latin1)))

        assertTrue(entry.satisfies("caf\u00e9"))
    }
}
