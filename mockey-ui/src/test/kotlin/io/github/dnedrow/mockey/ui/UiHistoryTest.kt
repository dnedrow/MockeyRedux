package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.HistoryEntryDto
import io.github.dnedrow.mockey.api.RecordedServiceDto
import io.github.dnedrow.mockey.api.ServiceDto
import org.jsoup.nodes.Document
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 13.9: the history list. It shows the entries newest first, filters them by tokens and by service,
 * pages through them, reloads itself on request, flags and deletes single entries, and deletes all entries
 * after a confirmation. The filter tokens, the service, the page, and the automatic refresh are all in the
 * address.
 *
 * Each test drives the UI over HTTP the way a browser without scripting would (see [Browser]), and then
 * checks which requests reached the admin API.
 */
class UiHistoryTest {
    private val started = Started()

    @AfterTest
    fun stopEverything() = started.close()

    /** What a stand-in server holds for a test: the server itself and a live view of its history. */
    private class Holding(
        val api: FakeAdminApi,
        val entries: MutableMap<Long, HistoryEntryDto>,
    )

    /** Starts a stand-in server that holds [entries] and [services]. */
    private fun holding(
        entries: List<HistoryEntryDto> = listOf(Entries.of(1), Entries.of(2), Entries.of(3)),
        services: List<ServiceDto> = listOf(Samples.customers, Samples.orders),
    ): Holding {
        val api = started.api()
        return Holding(api, api.holdHistory(entries, api.hold(services)))
    }

    /** Returns the entry [id] as one that the service Orders handled by forwarding it. */
    private fun ofOrders(id: Long): HistoryEntryDto {
        val orders = RecordedServiceDto(Samples.orders.id, Samples.orders.name)
        val forwarded = Entries.of(id, "http://mockey.test:8080/service/orders").copy(target = "http://qa1.example.com/orders")
        return forwarded.copy(service = orders, scenario = null, outcome = "proxied", responseMode = Samples.orders.responseMode)
    }

    /** Returns the rows of the history table on [page], each as the texts of its cells. */
    private fun rowsOf(page: Document): List<List<String>> = page.select(ROWS).map { row -> row.select("td").map { it.text() } }

    /** Returns the identifiers of the entries that [page] lists, in the order of the list. */
    private fun idsOf(page: Document): List<Long> = page.select(ROWS).map { it.id().removePrefix("entry-").toLong() }

    /** Returns the last paragraph of [page], which says why the page lists nothing. */
    private fun lastLineOf(page: Document): String = page.select("main > p").last()!!.text()

    /** Returns the requests to [api] that listed history entries, each as its request line. */
    private fun asked(api: FakeAdminApi): List<String> = api.requests.map { it.line }.filter { it.startsWith("GET history") }

    /** Returns the links of [page] outside its table and its navigation, each as its text and its address. */
    private fun linksOf(page: Document): List<Pair<String, String>> =
        page.select("main a").filter { it.parents().none { parent -> parent.tagName() == "table" } }.map { it.text() to it.attr("href") }

    // The list

    /** The list shows the newest entry first, with what was asked for, which service answered, and how. */
    @Test
    fun theListShowsTheEntriesNewestFirst() {
        val twisted = Entries.of(3).copy(targetBeforeTwisting = "kunde/3", inspectionMessages = listOf("no session", "no token"))
        val inspected = Entries.of(1, method = "POST", query = "a=1&b=2").copy(inspectionMessages = listOf("no session"))
        val held = holding(listOf(inspected, ofOrders(2).copy(flagged = true), twisted))
        val browser = started.browserFor(held.api)

        val answer = browser.get("/history")

        val page = answer.page
        val served = listOf("Customers (Static)", "200 Scenario served: found")
        val rows =
            listOf(
                listOf(Entries.shownTimeOf(3), "192.0.2.7", "GET customer/3 twisted 2 inspection messages") + served,
                listOf(Entries.shownTimeOf(2), "192.0.2.7", "GET http://qa1.example.com/orders flagged", "Orders (Proxy)", "200 Proxied"),
                listOf(Entries.shownTimeOf(1), "192.0.2.7", "POST customer/1?a=1&b=2 1 inspection message") + served,
            )
        val columns = listOf("Time", "Client", "Request", "Service", "Answer", "Actions")
        assertEquals(200 to "History - Mockey", answer.status to page.title())
        assertEquals(columns, page.select("main table.history thead th").map { it.text() })
        assertEquals(rows, rowsOf(page).map { it.dropLast(1) })
        assertEquals(listOf("View Flag Delete", "View Unflag Delete", "View Flag Delete"), rowsOf(page).map { it.last() })
        assertEquals(listOf("entry-3", "entry-2", "entry-1"), page.select(ROWS).map { it.id() })
        assertEquals(listOf("", "flagged", ""), page.select(ROWS).map { it.className() })
        val firstRow = page.select(ROWS).first()!!
        val ofFirstRow = firstRow.select("a").map { it.text() to it.attr("href") }
        assertEquals(listOf("Customers" to "/service?id=svc-customers", "View" to "/history/entry?id=3"), ofFirstRow)
        val named = firstRow.select("[aria-label]").map { it.attr("aria-label") }
        assertEquals(listOf("View the entry 3", "Flag the entry 3", "Delete the entry 3"), named)
        val refresh = "Switch automatic refresh on" to "/history?refresh=on"
        assertEquals(listOf("Statistics" to "/statistics", refresh, "Delete all entries" to "/history/delete"), linksOf(page))
        assertEquals(0, page.select("meta[http-equiv]").size)
        assertEquals(0, page.select("main p.paging").size)
        assertEquals(listOf("/history"), page.select("nav a[aria-current=page]").map { it.attr("href") })
        assertEquals(listOf("GET system", "GET history?max=51"), held.api.requests.map { it.line })
        assertEquals(emptyList(), held.api.violations)
    }

    /** An empty history says so, and it has nothing to delete. */
    @Test
    fun theListSaysWhenTheHistoryIsEmpty() {
        val browser = started.browserFor(holding(emptyList()).api)

        val page = browser.get("/history").page

        assertEquals(0, page.select("main table").size)
        assertEquals("The history is empty.", page.select("main > p").last()!!.text())
        assertEquals(listOf("Statistics" to "/statistics", "Switch automatic refresh on" to "/history?refresh=on"), linksOf(page))
    }

    // The filter tokens

    /**
     * The scenario "Shared link": the filter tokens are in the address, so the same address shows the same
     * filtered list in another browser. The form of the list leads to that address.
     */
    @Test
    fun theFilterTokensAreInTheAddress() {
        val gold = Entries.of(1, "http://mockey.test:8080/service/orders/1", responseBody = Samples.body("gold"))
        val plain = Entries.of(2, "http://mockey.test:8080/service/orders/2", responseBody = Samples.body("silver"))
        val held = holding(listOf(gold, plain, Entries.of(3)))
        val browser = started.browserFor(held.api)

        val filtered = browser.submit(browser.get("/history").form("main form.filter"), mapOf("tokens" to " orders   !gold "))
        val address = "/history?tokens=orders+%21gold"
        val elsewhere = Browser(browser.ui).get(address)
        val nothing = browser.get("/history?tokens=Orders")

        assertEquals(200 to listOf(2L), filtered.status to idsOf(filtered.page))
        assertEquals(listOf(2L), idsOf(elsewhere.page))
        val form = elsewhere.form("main form.filter")
        assertEquals("get" to "/history", form.attr("method") to form.attr("action"))
        assertEquals(mapOf("tokens" to listOf("orders !gold")), fieldsOf(form))
        assertEquals(0, form.select("[name=_token]").size)
        val limited = elsewhere.page.select("main p.count")
        assertEquals("Only the entries that satisfy every filter token are listed. Clear the filter tokens", limited.text())
        assertEquals(listOf("/history"), limited.select("a").map { it.attr("href") })
        assertTrue("Delete all entries" to "/history/delete" in linksOf(nothing.page))
        assertEquals(0 to "No entry matches.", nothing.page.select("main table").size to lastLineOf(nothing.page))
        val asked = asked(held.api)
        val ofTokens = "GET history?token=orders&token=%21gold&max=51"
        assertEquals(listOf("GET history?max=51", ofTokens, ofTokens, "GET history?token=Orders&max=51"), asked)
        assertEquals(emptyList(), held.api.violations)
    }

    // The entries of one service

    /** The list can be limited to the entries of one service. The service is in the address, like the tokens. */
    @Test
    fun theListOfAServiceShowsOnlyItsEntries() {
        val held = holding(listOf(Entries.of(1), ofOrders(2), Entries.of(3)))
        val browser = started.browserFor(held.api)

        val answer = browser.get("/history?service=svc-customers")
        val filtered = browser.submit(answer.form("main form.filter"), mapOf("tokens" to "customer/3"))
        val unknown = browser.get("/history?service=a%2Fb%20c").page
        val servicePage = browser.get("/service?id=svc-customers").page
        val ofTheServicePage = servicePage.select("main .actions a").map { it.text() to it.attr("href") }

        val page = answer.page
        val all = "Show the entries of all services"
        assertEquals(listOf(3L, 1L), idsOf(page))
        assertEquals(listOf("Only the entries of the service Customers are listed. $all"), page.select("main p.count").map { it.text() })
        assertTrue(all to "/history" in linksOf(page))
        assertTrue("Delete the entries of this service" to "/history/delete?service=svc-customers" in linksOf(page))
        assertEquals(mapOf("tokens" to listOf(""), "service" to listOf("svc-customers")), fieldsOf(answer.form("main form.filter")))
        assertEquals(listOf(3L), idsOf(filtered.page))
        assertTrue(all to "/history?tokens=customer%2F3" in linksOf(filtered.page))
        assertTrue("Clear the filter tokens" to "/history?service=svc-customers" in linksOf(filtered.page))
        assertEquals(listOf("Only the entries of one service are listed. $all"), unknown.select("main p.count").map { it.text() })
        assertEquals("No entry matches.", unknown.select("main > p").last()!!.text())
        assertTrue("History of this service" to "/history?service=svc-customers" in ofTheServicePage)
        val asked = asked(held.api)
        val expected =
            listOf(
                "GET history?serviceId=svc-customers&max=51",
                "GET history?token=customer%2F3&serviceId=svc-customers&max=51",
                "GET history?serviceId=a%2Fb+c&max=51",
            )
        assertEquals(expected, asked)
        assertEquals(emptyList(), held.api.violations)
    }

    // Paging

    /** The list shows fifty entries on a page. The page is in the address, and the links keep the rest of the view. */
    @Test
    fun theListHasPages() {
        val held = holding((1L..120L).map { Entries.of(it) })
        val browser = started.browserFor(held.api)

        val pages = listOf("/history", "/history?page=2", "/history?page=3", "/history?page=4").map { browser.get(it).page }
        val withView = browser.get("/history?tokens=customer&service=svc-customers&page=2&refresh=on").page
        val unreadable = listOf("x", "0", "-3", "99999999999", "").map { idsOf(browser.get("/history?page=$it").page).first() }
        browser.get("/history?page=2000000")

        val paging = { page: Document -> page.select("main p.paging").text() to page.select("main p.paging a").map { it.attr("href") } }
        assertEquals((120L downTo 71L).toList(), idsOf(pages[0]))
        assertEquals((70L downTo 21L).toList(), idsOf(pages[1]))
        assertEquals((20L downTo 1L).toList(), idsOf(pages[2]))
        assertEquals("Page 1 Older entries" to listOf("/history?page=2"), paging(pages[0]))
        assertEquals("Newer entries Page 2 Older entries" to listOf("/history", "/history?page=3"), paging(pages[1]))
        assertEquals("Newer entries Page 3" to listOf("/history?page=2"), paging(pages[2]))
        assertEquals(0, pages[3].select("main table").size)
        val last = pages[3].select("main > p").last()!!
        assertEquals("There are no entries on this page. Go to the first page", last.text())
        assertEquals(listOf("/history"), last.select("a").map { it.attr("href") })
        val kept = "tokens=customer&service=svc-customers"
        assertEquals(listOf("/history?$kept&refresh=on", "/history?$kept&page=3&refresh=on"), paging(withView).second)
        val lifting = withView.select("main p.count a").map { it.text() to it.attr("href") }
        val toFirstPage =
            listOf(
                "Clear the filter tokens" to "/history?service=svc-customers&refresh=on",
                "Show the entries of all services" to "/history?tokens=customer&refresh=on",
            )
        assertEquals(toFirstPage, lifting)
        assertTrue("Switch automatic refresh off" to "/history?$kept&page=2" in linksOf(withView))
        assertEquals(listOf(120L, 120L, 120L, 120L, 120L), unreadable)
        val asked = asked(held.api)
        assertEquals(listOf("GET history?max=51", "GET history?max=101", "GET history?max=151", "GET history?max=201"), asked.take(4))
        assertEquals("GET history?token=customer&serviceId=svc-customers&max=101", asked[4])
        assertEquals("GET history?max=50000001", asked.last())
        assertEquals(emptyList(), held.api.violations)
    }

    /** A history that fills its pages exactly has no page after the last one. */
    @Test
    fun aFullPageIsTheLastOneIfThereAreNoOlderEntries() {
        val browser = started.browserFor(holding((1L..100L).map { Entries.of(it) }).api)
        val single = started.browserFor(holding((1L..50L).map { Entries.of(it) }).api)

        val second = browser.get("/history?page=2").page
        val only = single.get("/history").page

        assertEquals((50L downTo 1L).toList(), idsOf(second))
        assertEquals("Newer entries Page 2", second.select("main p.paging").text())
        assertEquals(50 to 0, idsOf(only).size to only.select("main p.paging").size)
    }

    // Automatic refresh

    /**
     * The requirement "Live history view" without scripting: the automatic refresh is switched on and off
     * with a link, and while it's on, the page tells the browser to load it again every few seconds. A new
     * entry is on the page after the next load.
     */
    @Test
    fun automaticRefreshReloadsThePageWithoutScripting() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val off = browser.get("/history?tokens=customer")
        val switch = off.page.select("main a").first { it.text() == "Switch automatic refresh on" }
        val on = browser.get(switch.attr("href"))
        held.entries[4L] = Entries.of(4)
        val reloaded = browser.get("/history?tokens=customer&refresh=on")

        val refresh = { page: Document -> page.select("head meta[http-equiv=refresh]").map { it.attr("content") } }
        assertEquals(emptyList(), refresh(off.page))
        assertEquals(listOf("5"), refresh(on.page))
        assertEquals(listOf(3L, 2L, 1L), idsOf(on.page))
        assertEquals(listOf(4L, 3L, 2L, 1L), idsOf(reloaded.page))
        val note = on.page.select("main p.note")
        assertEquals("Automatic refresh is on, so this page reloads every 5 seconds. Switch automatic refresh off", note.text())
        assertEquals(listOf("/history?tokens=customer"), note.select("a").map { it.attr("href") })
        assertTrue(linksOf(on.page).none { it.first == "Switch automatic refresh on" })
        val form = on.form("main form.filter")
        assertEquals(mapOf("tokens" to listOf("customer"), "refresh" to listOf("on")), fieldsOf(form))
        val others = listOf("/services", "/history/entry?id=1", "/history/delete", "/statistics").map { refresh(browser.get(it).page) }
        assertEquals(listOf(emptyList(), emptyList(), emptyList(), emptyList<String>()), others)
    }

    // Flagging and deleting an entry

    /**
     * The buttons of a row flag an entry, remove the flag, and delete the entry. The browser gets back to the
     * list as it was, with its filter, and to the row of the entry.
     */
    @Test
    fun theButtonsOfARowFlagAndDeleteAnEntry() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val list = "/history?tokens=customer"
        val formsOf = { answer: Browser.Response -> answer.page.select("#entry-2 form").map { it.attr("action") } }

        val before = browser.get(list)
        val flagged = browser.submit(before.form("#entry-2 form[action^=/history/flag]"))
        val afterFlag = browser.follow(flagged)
        val unflagged = browser.submit(afterFlag.form("#entry-2 form[action^=/history/flag]"))
        val afterUnflag = browser.follow(unflagged)
        val deleted = browser.submit(afterUnflag.form("#entry-2 form[action^=/history/remove]"))
        val afterDelete = browser.follow(deleted)

        assertEquals(listOf("/history/flag?id=2&from=list&tokens=customer", "/history/remove?id=2&tokens=customer"), formsOf(before))
        assertEquals(303 to "$list#entry-2", flagged.status to flagged.header("location"))
        assertEquals("The history entry 2 is now flagged.", afterFlag.notice)
        val flagButton = { answer: Browser.Response -> answer.page.select("#entry-2 button").first()!! }
        assertEquals("flagged" to "Unflag", afterFlag.page.select("#entry-2").attr("class") to flagButton(afterFlag).text())
        assertEquals("Remove the flag of the entry 2", flagButton(afterFlag).attr("aria-label"))
        assertEquals(303 to "$list#entry-2", unflagged.status to unflagged.header("location"))
        assertEquals("The history entry 2 is no longer flagged.", afterUnflag.notice)
        assertEquals("" to "Flag", afterUnflag.page.select("#entry-2").attr("class") to flagButton(afterUnflag).text())
        assertEquals(303 to list, deleted.status to deleted.header("location"))
        assertEquals("The history entry 2 was deleted.", afterDelete.notice)
        assertEquals(listOf(3L, 1L), idsOf(afterDelete.page))
        val flags = listOf("PATCH history/2" to """{"flagged":true}""", "PATCH history/2" to """{"flagged":false}""")
        val changes = flags + ("DELETE history/2" to "")
        assertEquals(changes, held.api.changes())
        assertEquals(emptyList(), held.api.violations)
    }

    /** A page that reloads itself would jump to the row of an entry every time, so its address doesn't name the row. */
    @Test
    fun aListThatReloadsItselfDoesNotJumpToARow() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val flagged = browser.submit(browser.get("/history?page=1&refresh=on").form("#entry-2 form[action^=/history/flag]"))
        val token = browser.token("/history/delete")
        val fromElsewhere = browser.post("/history/flag?id=2&from=elsewhere&tokens=x", listOf("_token" to token, "designate" to "no"))

        assertEquals(303 to "/history?refresh=on", flagged.status to flagged.header("location"))
        assertEquals(303 to "/history/entry?id=2", fromElsewhere.status to fromElsewhere.header("location"))
        val flags = listOf("PATCH history/2" to """{"flagged":true}""", "PATCH history/2" to """{"flagged":false}""")
        assertEquals(flags, held.api.changes())
    }

    /** A flag request that doesn't say whether to flag or to remove the flag is rejected, and nothing changes. */
    @Test
    fun aFlagRequestWithoutAChoiceIsRejected() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val token = browser.token("/history/delete")

        val choices = listOf(emptyList(), listOf("designate" to "maybe"))
        val rejected = choices.map { browser.post("/history/flag?id=2", listOf("_token" to token) + it) }
        val unnamed = listOf("/history/flag", "/history/flag?id=x", "/history/remove?id=-1", "/history/remove?id=")
        val noEntry = unnamed.map { browser.post(it, listOf("_token" to token)) }

        assertEquals(listOf(422, 422), rejected.map { it.status })
        assertEquals(listOf(404, 404, 404, 404), noEntry.map { it.status })
        assertEquals(emptyList(), held.api.changes())
    }

    // Deleting all entries

    /**
     * The requirement "Confirmation of destructive actions" for the history: the link leads to a page that
     * asks for confirmation, nothing is deleted until the form on that page is submitted, and the list then
     * reports the deletion.
     */
    @Test
    fun theUiAsksForConfirmationBeforeItDeletesAllHistory() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val asked = browser.get("/history/delete")
        val token = asked.form().select("[name=_token]").attr("value")
        val notConfirmed = browser.post("/history/delete", listOf("_token" to token))
        val withEmptyService = browser.get("/history/delete?service=")
        val before = held.api.changes()
        val askedBefore = asked(held.api)
        val sent = browser.submit(asked.form())
        val after = browser.follow(sent)

        assertEquals(200 to "Delete all history entries?", asked.status to asked.page.select("main h1").text())
        val consequences = listOf("This deletes every entry that the history keeps.", "Services and scenarios stay as they are.")
        assertEquals(consequences, asked.page.select("main > p").map { it.text() })
        val button = asked.form().select("button").single()
        assertEquals("Delete the entries" to setOf("danger"), button.text() to button.classNames())
        assertEquals(listOf("Cancel" to "/history"), asked.page.select("main form a").map { it.text() to it.attr("href") })
        assertEquals(listOf("/history"), asked.page.select("nav a[aria-current=page]").map { it.attr("href") })
        assertEquals(200 to emptyList(), notConfirmed.status to before)
        assertEquals("Delete all history entries?", withEmptyService.page.select("main h1").text())
        assertEquals(setOf("GET history?max=1"), askedBefore.toSet())
        assertEquals(303 to "/history", sent.status to sent.header("location"))
        assertEquals("All history entries were deleted.", after.notice)
        assertEquals("The history is empty.", lastLineOf(after.page))
        assertEquals(listOf("DELETE history" to ""), held.api.changes())
        assertEquals(emptyList(), held.api.violations)
    }

    /** The entries of one service are deleted after the same confirmation step, and the other entries stay. */
    @Test
    fun theUiAsksForConfirmationBeforeItDeletesTheHistoryOfAService() {
        val held = holding(listOf(Entries.of(1), ofOrders(2), Entries.of(3)))
        val browser = started.browserFor(held.api)

        val asked = browser.get("/history/delete?service=svc-customers")
        val before = held.api.changes()
        val sent = browser.submit(asked.form())
        val after = browser.follow(sent)

        assertEquals(200 to "Delete the history entries of the service Customers?", asked.status to asked.page.select("main h1").text())
        val ofService = "This deletes every entry that the history keeps of this service."
        assertEquals(listOf(ofService, "Services and scenarios stay as they are."), asked.page.select("main > p").map { it.text() })
        assertEquals("/history/delete?service=svc-customers", asked.form().attr("action"))
        val cancel = asked.page.select("main form a").map { it.text() to it.attr("href") }
        assertEquals(listOf("Cancel" to "/history?service=svc-customers"), cancel)
        assertEquals(emptyList(), before)
        assertEquals(303 to "/history", sent.status to sent.header("location"))
        assertEquals("The history entries of the service Customers were deleted.", after.notice)
        assertEquals(listOf(2L), idsOf(after.page))
        assertEquals(listOf("DELETE history?serviceId=svc-customers" to ""), held.api.changes())
        val requests = held.api.requests
        assertEquals(listOf("GET system", "GET history?serviceId=svc-customers&max=1"), requests.take(2).map { it.line })
        assertEquals(emptyList(), held.api.violations)
    }

    /** Without an entry to delete, the page says so instead of asking. */
    @Test
    fun aHistoryWithoutEntriesHasNothingToDelete() {
        val held = holding(listOf(ofOrders(2)))
        val browser = started.browserFor(held.api)

        val ofService = browser.get("/history/delete?service=svc-customers")
        held.entries.clear()
        val ofAll = browser.get("/history/delete")

        for ((answer, title) in listOf(ofService to "The history has no entries of this service", ofAll to "The history is empty")) {
            assertEquals(200 to title, answer.status to answer.page.select("main h1").text())
            val firstLine = answer.page.select("main > p").first()!!
            assertEquals("There is nothing to delete.", firstLine.text())
            assertEquals(0, answer.page.select("main form").size)
            assertEquals(listOf("Back to the history" to "/history"), linksOf(answer.page))
        }
        assertEquals(emptyList(), held.api.changes())
    }

    // Stored content is text

    /**
     * The requirement "Stored content is shown as text" for the history list: whatever a client sent and a
     * service answered, the list and the confirmation page show it as plain text.
     */
    @Test
    fun theHistoryListShowsStoredContentAsText() {
        val api = started.api()
        val browser = started.browserFor(api)
        for (text in Hostile.MARKUP + Hostile.ADDRESSES) {
            val service = Samples.customers.copy(id = text, name = text, tags = listOf(text))
            api.holdHistory(listOf(Hostile.entry(1, text)), api.hold(listOf(service), paths = mapOf(text to Hostile.written(text))))

            val address = Address.of("/history", "tokens" to text, "service" to text)
            val pages = listOf("/history", address, Address.of("/history/delete", "service" to text)).map { browser.get(it) }

            for (answer in pages) {
                assertEquals(200, answer.status, text)
                assertEquals(emptyList(), Hostile.moreThanText(answer), text)
            }
            val listed = pages.first().page.select(ROWS)
            assertEquals(1, listed.size, text)
        }
        assertEquals(emptyList(), api.violations)
    }

    private companion object {
        /** The selector of the rows of the history table. */
        const val ROWS = "main table.history tbody tr"
    }
}
