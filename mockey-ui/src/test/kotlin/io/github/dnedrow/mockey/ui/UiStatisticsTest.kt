package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.HistoryEntryDto
import io.github.dnedrow.mockey.api.HistoryStatisticsDto
import io.github.dnedrow.mockey.api.RecordedServiceDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.ServiceStatisticsDto
import org.jsoup.nodes.Document
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 13.9: the statistics page and its downloads. The page says how many history entries the server keeps
 * for each service, optionally within a time range that's in the address. The downloads hand out the same
 * numbers as CSV and as JSON.
 *
 * Each test drives the UI over HTTP the way a browser without scripting would (see [Browser]), and then
 * checks which requests reached the admin API.
 */
class UiStatisticsTest {
    private val started = Started()

    @AfterTest
    fun stopEverything() = started.close()

    /**
     * Starts a stand-in server that holds [entries] and [services]. With the defaults, the service Customers
     * has three entries, one second apart, and the service Orders has none. The services aren't in the order
     * of their names, so a test can tell whether the page sorts them.
     */
    private fun holding(
        entries: List<HistoryEntryDto> = listOf(Entries.of(1), Entries.of(2), Entries.of(3)),
        services: List<ServiceDto> = listOf(Samples.orders, Samples.customers),
    ): FakeAdminApi {
        val api = started.api()
        api.holdHistory(entries, api.hold(services))
        return api
    }

    /** Returns the rows of the statistics table on [page], each as the texts of its cells. */
    private fun rowsOf(page: Document): List<List<String>> =
        page.select("main table.statistics tbody tr").map { row -> row.select("th, td").map { it.text() } }

    /** Returns the requests to [api] for the statistics, each as its request line. */
    private fun asked(api: FakeAdminApi): List<String> = api.requests.map { it.line }.filter { it.startsWith("GET stats") }

    // The page

    /** The page lists every service by name, with the number of its entries, a bar, and the time of its earliest entry. */
    @Test
    fun thePageShowsHowManyEntriesTheHistoryKeepsOfEachService() {
        val api = holding()
        val browser = started.browserFor(api)

        val answer = browser.get("/statistics")

        val page = answer.page
        val form = answer.form()
        val first = Entries.shownTimeOf(1)
        assertEquals(200 to "History statistics - Mockey", answer.status to page.title())
        val columns = page.select("main table.statistics thead th").map { it.text() }
        assertEquals(listOf("Service", "Entries", "Share", "Earliest entry"), columns)
        assertEquals(listOf(listOf("Customers", "3", "3 of 3", first), listOf("Orders", "0", "0 of 3", "none")), rowsOf(page))
        val bars = page.select("main table.statistics meter").map { listOf("min", "max", "value").map(it::attr) }
        assertEquals(listOf(listOf("0", "3", "3"), listOf("0", "3", "0")), bars)
        val ofCustomers = listOf("Customers" to "/service?id=svc-customers", "3" to "/history?service=svc-customers")
        val ofTheTable = ofCustomers + ("Orders" to "/service?id=svc-orders")
        assertEquals(ofTheTable, page.select("main table.statistics a").map { it.text() to it.attr("href") })
        assertTrue("The oldest entry that the history keeps is from $first." in page.select("main > p").map { it.text() })
        val formats = listOf("CSV" to "csv", "JSON" to "json")
        val downloads = formats.map { (name, format) -> "Download as $name" to "/statistics/download?format=$format" }
        assertEquals(downloads + ("History" to "/history"), page.select("main p.actions a").map { it.text() to it.attr("href") })
        assertEquals("get" to "/statistics", form.attr("method") to form.attr("action"))
        assertEquals(mapOf("from" to listOf(""), "to" to listOf("")), fieldsOf(form))
        val inputs = form.select("input").map { it.attr("type") to it.attr("step") }
        assertEquals(listOf("datetime-local" to "1", "datetime-local" to "1"), inputs)
        assertEquals(listOf("From (UTC)", "To (UTC)"), form.select("label").map { it.text() })
        assertEquals(0, page.select("main p.count").size)
        assertEquals(listOf("/history"), page.select("nav a[aria-current=page]").map { it.attr("href") })
        assertEquals(listOf("GET system", "GET stats"), api.requests.map { it.line })
        assertEquals(emptyList(), api.violations)
    }

    /** The longest bar stands for the service with the most entries, and the other bars compare with it. */
    @Test
    fun theBarsCompareWithTheHighestCount() {
        val orders = RecordedServiceDto(Samples.orders.id, Samples.orders.name)
        val ofOrders = listOf(4L, 5L).map { Entries.of(it).copy(service = orders) }
        val browser = started.browserFor(holding(listOf(Entries.of(1), Entries.of(2), Entries.of(3)) + ofOrders))

        val page = browser.get("/statistics").page

        assertEquals(listOf("3 of 3", "2 of 3"), rowsOf(page).map { it[2] })
        assertEquals(listOf("3" to "3", "3" to "2"), page.select("main meter").map { it.attr("max") to it.attr("value") })
    }

    /** A history without entries has statistics too: every service has a count of 0, and there are no bars. */
    @Test
    fun anEmptyHistoryHasStatisticsToo() {
        val browser = started.browserFor(holding(emptyList()))
        val withoutServices = started.browserFor(holding(emptyList(), emptyList()))

        val page = browser.get("/statistics").page
        val nothing = withoutServices.get("/statistics").page

        assertEquals(listOf(listOf("Customers", "0", "", "none"), listOf("Orders", "0", "", "none")), rowsOf(page))
        assertEquals(0, page.select("main meter").size)
        assertTrue("The history is empty." in page.select("main > p").map { it.text() })
        assertEquals(0, nothing.select("main table").size)
        assertTrue("There are no services, so there is nothing to count." in nothing.select("main > p").map { it.text() })
    }

    // The time range

    /**
     * The requirement "View state lives in the address" for the statistics: the time range is in the address,
     * so the same address shows the same numbers in another browser. The form of the page leads to that
     * address, and the downloads keep the range.
     */
    @Test
    fun theTimeRangeIsInTheAddress() {
        val api = holding()
        val browser = started.browserFor(api)
        val second = "2026-01-02T03:00:02"
        val inAddress = "2026-01-02T03%3A00%3A02"

        val submitted = browser.submit(browser.get("/statistics").form(), mapOf("from" to second, "to" to second))
        val address = "/statistics?from=$inAddress&to=$inAddress"
        val elsewhere = Browser(browser.ui).get(address)
        val open = browser.get("/statistics?to=2026-01-02T03%3A00%3A02").page

        val expected = listOf(listOf("Customers", "1", "1 of 1", Entries.shownTimeOf(2)), listOf("Orders", "0", "0 of 1", "none"))
        assertEquals(200 to expected, submitted.status to rowsOf(submitted.page))
        assertEquals(expected, rowsOf(elsewhere.page))
        val form = elsewhere.form()
        assertEquals(mapOf("from" to listOf(second), "to" to listOf(second)), fieldsOf(form))
        val limited = elsewhere.page.select("main p.count")
        assertEquals("Only the requests within the time range are counted. Count the whole history", limited.text())
        assertEquals(listOf("/statistics"), limited.select("a").map { it.attr("href") })
        val actions = elsewhere.page.select("main p.actions a").map { it.attr("href") }
        val downloads = actions.filter { it.startsWith("/statistics/download") }
        assertEquals(listOf("csv", "json").map { "/statistics/download?format=$it&from=$inAddress&to=$inAddress" }, downloads)
        val untilTheSecond = listOf(listOf("Customers", "2", "2 of 2", Entries.shownTimeOf(1)), listOf("Orders", "0", "0 of 2", "none"))
        assertEquals(untilTheSecond, rowsOf(open))
        assertEquals(listOf("/statistics"), open.select("main p.count a").map { it.attr("href") })
        val oldest = "The oldest entry that the history keeps is from ${Entries.shownTimeOf(1)}."
        assertTrue(oldest in elsewhere.page.select("main > p").map { it.text() })
        val ofTheRange = "GET stats?from=2026-01-02T03%3A00%3A02Z&to=2026-01-02T03%3A00%3A02Z"
        assertEquals(listOf("GET stats", ofTheRange, ofTheRange, "GET stats?to=2026-01-02T03%3A00%3A02Z"), asked(api))
        assertEquals(emptyList(), api.violations)
    }

    /** A time that can't be read is reported next to its field. The page shows no numbers, and the server isn't asked for any. */
    @Test
    fun aTimeThatCannotBeReadIsReportedAtItsField() {
        val api = holding()
        val browser = started.browserFor(api)

        val answer = browser.get("/statistics?from=yesterday&to=2026-01-02T03%3A00")
        val download = browser.get("/statistics/download?format=csv&to=%3Cb%3Esoon")

        val message = "yesterday is not a time that the UI can read. Write it like 2026-01-02T03:04:05, in UTC."
        assertEquals(400 to mapOf("from" to listOf(message)), answer.status to answer.fieldProblems)
        assertEquals(mapOf("from" to listOf(""), "to" to listOf("2026-01-02T03:00:00")), fieldsOf(answer.form()))
        assertEquals(0, answer.page.select("main table").size)
        assertEquals(listOf("History" to "/history"), answer.page.select("main p.actions a").map { it.text() to it.attr("href") })
        assertEquals("Count the whole history", answer.page.select("main p.count").text())
        val ofTheDownload = "<b>soon is not a time that the UI can read. Write it like 2026-01-02T03:04:05, in UTC."
        assertEquals(400 to mapOf("to" to listOf(ofTheDownload)), download.status to download.fieldProblems)
        assertEquals(null, download.header("content-disposition"))
        assertEquals(emptyList(), Hostile.moreThanText(download))
        assertEquals(emptyList(), asked(api))
    }

    // The downloads

    /** The downloads hand out what the server sends, as files that the browser saves. */
    @Test
    fun theDownloadsHandOutWhatTheServerSends() {
        val api = holding()
        val browser = started.browserFor(api)

        val csv = browser.get("/statistics/download?format=csv")
        val json = browser.get("/statistics/download?format=json")
        val ofRange = browser.get("/statistics/download?format=csv&from=2026-01-02+03%3A00%3A02+UTC&to=2026-01-02T04%3A00%3A03%2B01%3A00")
        browser.get("/statistics/download?format=json&from=2026-01-02T03%3A00")

        val rows = listOf("svc-orders,Orders,0,", "svc-customers,Customers,3,${Entries.timeOf(1)}")
        val lines = listOf("Service identifier,Service name,Entries,Earliest entry") + rows
        assertEquals(200 to lines.joinToString("\r\n"), csv.status to csv.body)
        assertEquals("text/csv; charset=UTF-8", csv.header("content-type"))
        assertEquals("attachment; filename=\"mockey-statistics.csv\"", csv.header("content-disposition"))
        val ofCustomers = ServiceStatisticsDto("svc-customers", "Customers", 3, Entries.timeOf(1))
        val counted = listOf(ServiceStatisticsDto("svc-orders", "Orders", 0), ofCustomers)
        val stated = AdminApiJson.instance.decodeFromString(HistoryStatisticsDto.serializer(), json.body)
        assertEquals(200 to HistoryStatisticsDto(counted, Entries.timeOf(1)), json.status to stated)
        assertEquals("application/json; charset=UTF-8", json.header("content-type"))
        assertEquals("attachment; filename=\"mockey-statistics.json\"", json.header("content-disposition"))
        for (download in listOf(csv, json, ofRange)) {
            assertEquals("no-store" to "nosniff", download.header("cache-control") to download.header("x-content-type-options"))
            assertTrue(Hostile.isDownload(download))
        }
        assertEquals("svc-customers,Customers,2,${Entries.timeOf(2)}", ofRange.body.split("\r\n").last())
        val ofTheRange = "GET stats?from=2026-01-02T03%3A00%3A02Z&to=2026-01-02T03%3A00%3A03Z&format=csv"
        assertEquals(listOf("GET stats?format=csv", "GET stats", ofTheRange, "GET stats?from=2026-01-02T03%3A00%3A00Z"), asked(api))
        assertEquals(emptyList(), api.violations)
    }

    /** A download needs a format that the UI knows, and a server that answers. Otherwise the answer is a page, and no file. */
    @Test
    fun aDownloadNeedsAFormatAndAServerThatAnswers() {
        val api = holding()
        val browser = started.browserFor(api)

        val unknown = listOf("", "?format=", "?format=xml", "?format=CSV")
        val notFound = unknown.map { browser.get("/statistics/download$it") }
        val before = asked(api)
        api.close()
        val unreachable = browser.get("/statistics/download?format=csv")

        assertEquals(listOf(404, 404, 404, 404), notFound.map { it.status })
        assertEquals(emptyList(), before)
        assertEquals(502 to "The server cannot be reached", unreachable.status to unreachable.page.select("main h1").text())
        assertEquals(null, unreachable.header("content-disposition"))
    }

    /** A server that isn't compatible with the UI gets the page that says so, and the UI doesn't hand out a file from it. */
    @Test
    fun anIncompatibleServerGivesNoDownload() {
        val api = holding()
        val browser = started.browserFor(api)
        api.on("GET", "system") { FakeAdminApi.problem(404, "The admin API has no resource at the path of the request.") }

        val answers = listOf("csv", "json").map { browser.get("/statistics/download?format=$it") }

        for (answer in answers) {
            assertEquals(502 to "The server is not compatible with this UI", answer.status to answer.page.select("main h1").text())
            assertEquals(null, answer.header("content-disposition"))
        }
        assertEquals(emptyList(), asked(api))
    }

    // Stored content is text

    /**
     * The requirement "Stored content is shown as text" for the statistics: the page shows the name of a
     * service as plain text. A download holds the name as the server sends it, and the browser saves it as
     * a file instead of reading it as a page.
     */
    @Test
    fun theStatisticsShowStoredContentAsText() {
        val api = started.api()
        val browser = started.browserFor(api)
        for (text in Hostile.MARKUP + Hostile.ADDRESSES) {
            val service = Samples.customers.copy(id = text, name = text)
            api.holdHistory(emptyList(), api.hold(listOf(service), paths = mapOf(text to Hostile.written(text))))
            api.on("GET", "stats") { request ->
                val statistics = HistoryStatisticsDto(listOf(ServiceStatisticsDto(text, text, 1, text)), text, text, text)
                val asCsv = "format=csv" in request.query.orEmpty()
                if (asCsv) FakeAdminApi.Answer(200, "$text,$text", "text/csv") else FakeAdminApi.json(statistics)
            }

            val page = browser.get("/statistics")
            val (csv, json) = listOf("csv", "json").map { browser.get("/statistics/download?format=$it") }

            assertEquals(200 to emptyList(), page.status to Hostile.moreThanText(page), text)
            val cells = page.page.select("main table.statistics tbody tr > *").map { it.wholeText() }
            assertEquals(listOf(text, "1", "1 of 1", text), cells, text)
            assertEquals(true to "$text,$text", Hostile.isDownload(csv) to csv.body, text)
            val stated = AdminApiJson.instance.decodeFromString(HistoryStatisticsDto.serializer(), json.body)
            assertEquals(true to text, Hostile.isDownload(json) to stated.services.single().serviceName, text)
        }
        assertEquals(emptyList(), api.violations)
    }
}
