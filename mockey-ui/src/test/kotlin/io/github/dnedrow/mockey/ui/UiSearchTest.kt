package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.SearchResultDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.json
import io.ktor.http.parseQueryString
import org.jsoup.nodes.Document
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 13.10: the search page. Its form puts the search term into the address, and the page lists the
 * services, scenarios, and plans that the server found, each with a link to its page.
 *
 * Each test drives the UI over HTTP the way a browser without scripting would (see [Browser]), and then
 * checks which requests reached the admin API.
 */
class UiSearchTest {
    private val started = Started()

    @AfterTest
    fun stopEverything() = started.close()

    private val member = Samples.found.copy(id = "sc-member", name = "gold member", responseBody = "{}", tags = emptyList())
    private val gold = Samples.customers.copy(id = "svc-gold", name = "Gold customers", scenarios = listOf(member, Samples.missing))
    private val path = PlanDto("plan-gold", "Gold path")

    /** Starts a stand-in server that holds [services] and [plans]. With the defaults, the term "gold" finds one of each kind. */
    private fun holding(
        services: List<ServiceDto> = listOf(gold, Samples.orders),
        plans: List<PlanDto> = listOf(path, Samples.plan),
    ): FakeAdminApi {
        val api = started.api()
        val held = api.hold(services)
        api.holdDefinitions(held, api.holdPlans(held, plans))
        return api
    }

    /** Returns the rows of the result table on [page], each as the texts of its cells. */
    private fun rowsOf(page: Document): List<List<String>> =
        page.select("main table.results tbody tr").map { row -> row.select("th, td").map { it.text() } }

    /** Returns the terms that [api] was asked to search for. */
    private fun searched(api: FakeAdminApi): List<String?> =
        api.requests.filter { it.line.startsWith("GET search") }.map { parseQueryString(it.query.orEmpty())["term"] }

    // The results

    /** The page lists what the server found, in the order of the server: the name with a link, the kind, and the service of a scenario. */
    @Test
    fun thePageListsWhatTheServerFound() {
        val api = holding()
        val browser = started.browserFor(api)

        val answer = browser.get("/search?term=GOLD")

        val page = answer.page
        val form = answer.form()
        val rows =
            listOf(
                listOf("Gold path", "Plan", ""),
                listOf("Gold customers", "Service", ""),
                listOf("gold member", "Scenario", "Gold customers"),
            )
        val links =
            listOf(
                "Gold path" to "/plan?id=plan-gold",
                "Gold customers" to "/service?id=svc-gold",
                "gold member" to "/scenario?service=svc-gold&id=sc-member",
                "Gold customers" to "/service?id=svc-gold",
            )
        assertEquals(200 to "Search - Mockey", answer.status to page.title())
        assertEquals("3 results for \"GOLD\"", page.select("main p.count").text())
        assertEquals(listOf("Name", "Kind", "Service"), page.select("main table.results thead th").map { it.text() })
        assertEquals(rows, rowsOf(page))
        assertEquals(links, page.select("main table.results a").map { it.text() to it.attr("href") })
        assertEquals("get" to "/search", form.attr("method") to form.attr("action"))
        assertEquals(mapOf("term" to listOf("GOLD")), fieldsOf(form))
        assertEquals(listOf("/services"), page.select("nav a[aria-current=page]").map { it.attr("href") })
        assertEquals(listOf("GET system", "GET search?term=GOLD"), api.requests.map { it.line })
        assertEquals(emptyList(), api.violations)
    }

    /**
     * The requirement "View state lives in the address" for the search: the form of the overview and the
     * form of the search page both lead to an address that holds the term, and that address shows the same
     * results in another browser.
     */
    @Test
    fun theSearchTermIsInTheAddress() {
        val api = holding()
        val browser = started.browserFor(api)
        val elsewhere = Browser(browser.ui)

        val ofOverview = browser.get("/services").form("main form.search")
        val fromOverview = browser.submit(ofOverview, mapOf("term" to "gold member"))
        val fromThePage = browser.submit(fromOverview.form(), mapOf("term" to "a&b=c d#e+f%"))
        val shared = elsewhere.get("/search?term=gold+member")

        assertEquals("get" to "/search", ofOverview.attr("method") to ofOverview.attr("action"))
        assertEquals(mapOf("term" to listOf("")), fieldsOf(ofOverview))
        assertEquals(listOf(200, 200, 200), listOf(fromOverview, fromThePage, shared).map { it.status })
        assertEquals(listOf(listOf("gold member", "Scenario", "Gold customers")), rowsOf(fromOverview.page))
        assertEquals(rowsOf(fromOverview.page), rowsOf(shared.page))
        assertEquals("1 result for \"gold member\"", shared.page.select("main p.count").text())
        assertEquals(mapOf("term" to listOf("a&b=c d#e+f%")), fieldsOf(fromThePage.form()))
        assertEquals("Nothing was found for \"a&b=c d#e+f%\".", fromThePage.page.select("main p.count").text())
        assertEquals(listOf("gold member", "a&b=c d#e+f%", "gold member"), searched(api))
        assertEquals(emptyList(), api.violations)
    }

    /** The server finds nothing for a blank term, so the UI doesn't ask it. The page shows the form and nothing else. */
    @Test
    fun aBlankTermAsksTheServerForNothing() {
        val api = holding()
        val browser = started.browserFor(api)

        val answers = listOf("/search", "/search?term=", "/search?term=+%09+").map(browser::get)

        for (answer in answers) {
            assertEquals(200, answer.status)
            assertEquals(1, answer.page.select("main form").size)
            assertEquals(0, answer.page.select("main p.count, main table").size)
        }
        assertEquals(listOf("", "", " \t "), answers.map { it.form().select("[name=term]").attr("value") })
        assertEquals(emptyList(), searched(api))
    }

    /** A term that finds nothing gets a page that says so, without a table. The term is sent as it was entered. */
    @Test
    fun aTermThatFindsNothingSaysSo() {
        val api = holding()
        val browser = started.browserFor(api)

        val page = browser.get("/search?term=+platinum+").page

        assertEquals("Nothing was found for \"platinum\".", page.select("main p.count").text())
        assertEquals(0, page.select("main table").size)
        assertEquals(listOf(" platinum "), searched(api))
    }

    /** A long term is shortened in the sentence about the results, and the field keeps all of it. */
    @Test
    fun aLongTermIsShortenedInTheSentence() {
        val api = holding()
        val term = "x".repeat(200)

        val answer = started.browserFor(api).get("/search?term=$term")

        assertEquals("Nothing was found for \"${"x".repeat(79)}\u2026\".", answer.page.select("main p.count").text())
        assertEquals(term, answer.form().select("[name=term]").attr("value"))
    }

    /**
     * A result of a kind that the UI doesn't know is listed without a link, and so is a scenario whose
     * service the server didn't name. A service without a name is shown by its identifier.
     */
    @Test
    fun aResultWithoutAPageIsListedWithoutALink() {
        val api = holding()
        val found =
            listOf(
                SearchResultDto("widget", "w-1", "A widget"),
                SearchResultDto("scenario", "sc-1", "lost scenario"),
                SearchResultDto("scenario", "sc-2", "unnamed service", "svc-gold"),
            )
        api.on("GET", "search") { json(found) }

        val page = started.browserFor(api).get("/search?term=x").page

        val withoutPage = listOf(listOf("A widget", "widget", ""), listOf("lost scenario", "Scenario", ""))
        assertEquals(withoutPage + listOf(listOf("unnamed service", "Scenario", "svc-gold")), rowsOf(page))
        val links = listOf("unnamed service" to "/scenario?service=svc-gold&id=sc-2", "svc-gold" to "/service?id=svc-gold")
        assertEquals(links, page.select("main table.results a").map { it.text() to it.attr("href") })
        assertEquals("3 results for \"x\"", page.select("main p.count").text())
    }

    // Stored content is text

    /**
     * The requirement "Stored content is shown as text" for the search: whatever the server found, and
     * whatever the address names as the term, the page shows it as plain text.
     */
    @Test
    fun theSearchPageShowsStoredContentAsText() {
        val api = holding()
        var text = ""
        api.on("GET", "search") {
            val kinds = listOf("service", "scenario", "plan", text)
            json(kinds.map { SearchResultDto(it, text, text, text, text) })
        }
        val browser = started.browserFor(api)

        for (hostile in Hostile.MARKUP + Hostile.ADDRESSES) {
            text = hostile
            val answer = browser.get(Address.of("/search", "term" to hostile))

            assertEquals(200, answer.status, hostile)
            assertEquals(emptyList(), Hostile.moreThanText(answer), hostile)
            assertEquals(hostile, answer.form().select("[name=term]").attr("value"), hostile)
            val names = answer.page.select("main table.results tbody th").map { it.wholeText() }
            assertEquals(listOf(hostile, hostile, hostile, hostile), names, hostile)
            val ofUnknownKind = answer.page.select("main table.results tbody tr").last()!!
            assertEquals(hostile, ofUnknownKind.select("td").first()!!.wholeText(), hostile)
            assertEquals("4 results for \"${hostile.trim()}\"", answer.wholeTextOf("main p.count"), hostile)
        }
        assertEquals(emptyList(), api.violations)
    }
}
