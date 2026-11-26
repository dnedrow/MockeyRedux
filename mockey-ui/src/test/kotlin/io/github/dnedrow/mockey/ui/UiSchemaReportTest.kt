package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.ScenarioConformanceDto
import io.github.dnedrow.mockey.api.SchemaReportDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import org.jsoup.nodes.Document
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 13.10: the page with the schema report of a service. It shows what the response schema of the
 * service says about the response body of each scenario, or why the server couldn't apply the schema.
 *
 * Each test loads the page over HTTP the way a browser without scripting would (see [Browser]), and then
 * checks which requests reached the admin API.
 */
class UiSchemaReportTest {
    private val started = Started()

    @AfterTest
    fun stopEverything() = started.close()

    private val withSchema = Samples.customers.copy(responseSchema = """{"type":"object"}""", responseSchemaEnabled = true)

    /** Starts a stand-in server whose service Customers has a response schema and the schema report [report]. */
    private fun holding(
        report: SchemaReportDto?,
        service: ServiceDto = withSchema,
    ): FakeAdminApi {
        val api = started.api()
        api.hold(listOf(service, Samples.orders), schemaReports = listOfNotNull(report?.let { service.id to it }).toMap())
        return api
    }

    /** Returns the conformance of the scenario [name] with [violations]. A scenario without violations conforms. */
    private fun conformance(
        name: String,
        vararg violations: String,
    ) = ScenarioConformanceDto("sc-$name", name, violations.isEmpty(), violations.toList())

    /** Returns the rows of the table on [page], each as the texts of its cells. */
    private fun rowsOf(page: Document): List<List<String>> =
        page.select("main table.conformance tbody tr").map { row -> row.select("th, td").map { it.text() } }

    // The report

    /** The page of a service leads to the report, which says whether each scenario conforms, and what the schema has against it. */
    @Test
    fun theReportSaysWhichScenariosConform() {
        val violations = arrayOf("/: Value is [string] but should be [object]", "/name: Required property is missing")
        val api = holding(SchemaReportDto.Checked(listOf(conformance("found"), conformance("missing", *violations))))
        val browser = started.browserFor(api)

        val ofService = browser.get("/service?id=svc-customers").linksOf("main dd a:not(.tag)")
        api.requests.clear()
        val answer = browser.get("/service/schema-report?id=svc-customers")

        val page = answer.page
        val rows =
            listOf(
                listOf("found", "conforms", ""),
                listOf("missing", "does not conform", violations.joinToString(" ")),
            )
        assertEquals(listOf("Check the scenarios against the schema" to "/service/schema-report?id=svc-customers"), ofService)
        assertEquals(200 to "Schema report of Customers - Mockey", answer.status to page.title())
        assertEquals("1 of 2 scenarios does not conform to the schema.", page.select("main p.count").text())
        assertEquals(listOf("Scenario", "Result", "Violations"), page.select("main table.conformance thead th").map { it.text() })
        assertEquals(rows, rowsOf(page))
        assertEquals(violations.toList(), page.select("main table.conformance tbody li").map { it.text() })
        val scenarios = listOf("/scenario?service=svc-customers&id=sc-found", "/scenario?service=svc-customers&id=sc-missing")
        assertEquals(scenarios, page.select("main table.conformance tbody a").map { it.attr("href") })
        val leading = listOf("Back to the service" to "/service?id=svc-customers", "Edit the service" to "/service/edit?id=svc-customers")
        assertEquals(leading, page.select("main p.actions a").map { it.text() to it.attr("href") })
        assertEquals(0, page.select("main .problem, main form").size)
        assertEquals(listOf("/services"), page.select("nav a[aria-current=page]").map { it.attr("href") })
        val asked = listOf("GET system", "GET services/svc-customers", "GET services/svc-customers/schema-report")
        assertEquals(asked, api.requests.map { it.line })
        assertEquals(emptyList(), api.violations)
    }

    /** The sentence above the table counts the scenarios that don't conform, in words that fit the numbers. */
    @Test
    fun theSummaryCountsTheScenariosThatDoNotConform() {
        val good = conformance("good")
        val bad = conformance("bad", "/: wrong")
        val cases =
            mapOf(
                listOf(good, good, good) to "All 3 scenarios conform to the schema.",
                listOf(good) to "The scenario conforms to the schema.",
                listOf(bad) to "The scenario does not conform to the schema.",
                listOf(bad, good, bad) to "2 of 3 scenarios do not conform to the schema.",
                listOf(bad, bad) to "2 of 2 scenarios do not conform to the schema.",
                emptyList<ScenarioConformanceDto>() to "The service has no scenario to check.",
            )

        for ((scenarios, summary) in cases) {
            val page = started.browserFor(holding(SchemaReportDto.Checked(scenarios))).get("/service/schema-report?id=svc-customers").page

            assertEquals(summary, page.select("main p.count").text())
            assertEquals(scenarios.size, rowsOf(page).size, summary)
            assertEquals(if (scenarios.isEmpty()) 0 else 1, page.select("main table").size, summary)
        }
    }

    /** The result of a scenario is what the server says it is, also when the server lists no violation for it. */
    @Test
    fun theResultIsWhatTheServerSays() {
        val unexplained = ScenarioConformanceDto("sc-found", "found", false, emptyList())
        val api = holding(SchemaReportDto.Checked(listOf(unexplained)))

        val page = started.browserFor(api).get("/service/schema-report?id=svc-customers").page

        assertEquals("The scenario does not conform to the schema.", page.select("main p.count").text())
        assertEquals(listOf(listOf("found", "does not conform", "")), rowsOf(page))
    }

    /** A schema that the server can't apply has no table. The page says why: the dialect isn't supported, or the schema is unusable. */
    @Test
    fun aSchemaThatTheServerCannotApplyIsReported() {
        val dialect = "http://json-schema.org/draft-03/schema"
        val problems = listOf("The schema cannot be read as JSON.", "The schema is not valid in its dialect.")
        val unsupported = started.browserFor(holding(SchemaReportDto.UnsupportedDialect(dialect)))
        val unusable = started.browserFor(holding(SchemaReportDto.UnusableSchema(problems)))

        val ofDialect = unsupported.get("/service/schema-report?id=svc-customers").page
        val ofProblems = unusable.get("/service/schema-report?id=svc-customers").page

        val notSupported = "The server did not check the scenarios, because the schema declares a dialect that it does not support:"
        assertEquals(notSupported, ofDialect.select("main .problem").text())
        assertEquals(dialect, ofDialect.select("main .problem + p code").text())
        val notUsable = "The server did not check the scenarios, because it cannot apply the schema:"
        assertEquals(notUsable, ofProblems.select("main .problem").text())
        assertEquals(problems, ofProblems.select("main .problem + ul li").map { it.text() })
        assertEquals(0, ofDialect.select("main table, main p.count").size + ofProblems.select("main table, main p.count").size)
        assertEquals(2, ofDialect.select("main p.actions a").size)
    }

    /** Without a report from the test, the stand-in says that every scenario conforms, which is what the page shows then. */
    @Test
    fun aServiceWhoseScenariosAllConformSaysSo() {
        val page = started.browserFor(holding(null)).get("/service/schema-report?id=svc-customers").page

        assertEquals("All 2 scenarios conform to the schema.", page.select("main p.count").text())
        assertEquals(listOf(listOf("found", "conforms", ""), listOf("missing", "conforms", "")), rowsOf(page))
    }

    // What is not there

    /**
     * A service without a response schema has no report, and the page says what the server answered. A
     * service that isn't there has none either.
     */
    @Test
    fun aServiceWithoutASchemaHasNoReport() {
        val api = holding(null, Samples.customers)
        api.on("GET", "services/svc-gone") { problem(404, "No service has the identifier svc-gone.") }
        val browser = started.browserFor(api)

        val withoutSchema = browser.get("/service/schema-report?id=svc-customers")
        val gone = browser.get("/service/schema-report?id=svc-gone")
        val withoutId = browser.get("/service/schema-report")

        assertEquals(listOf(404, 404, 404), listOf(withoutSchema, gone, withoutId).map { it.status })
        assertEquals("Not there", withoutSchema.page.select("main h1").text())
        assertEquals("The service 'Customers' has no response schema.", withoutSchema.page.select("main .problem").text())
        assertEquals("No service has the identifier svc-gone.", gone.page.select("main .problem").text())
        val ofService = browser.get("/service?id=svc-customers").linksOf("main dd a[href^=/service/schema-report]")
        assertEquals(emptyList(), ofService)
        assertEquals(emptyList(), api.violations)
    }

    // Stored content is text

    /**
     * The requirement "Stored content is shown as text" for the schema report: whatever the server reports
     * about the scenarios, the dialect, or the schema, the page shows it as plain text.
     */
    @Test
    fun theSchemaReportShowsStoredContentAsText() {
        val api = started.api()
        val browser = started.browserFor(api)

        for (text in Hostile.MARKUP + Hostile.ADDRESSES) {
            val service = withSchema.copy(id = text, name = text, responseSchema = text)
            val reports =
                listOf(
                    SchemaReportDto.Checked(listOf(ScenarioConformanceDto(text, text, conforms = false, listOf(text, text)))),
                    SchemaReportDto.UnsupportedDialect(text),
                    SchemaReportDto.UnusableSchema(listOf(text)),
                )
            val shown =
                reports.map { report ->
                    api.hold(listOf(service), paths = mapOf(text to Hostile.written(text)), schemaReports = mapOf(text to report))
                    browser.get(Address.of("/service/schema-report", "id" to text))
                }

            for (answer in shown) {
                assertEquals(200, answer.status, text)
                assertEquals(emptyList(), Hostile.moreThanText(answer), text)
                assertEquals("Schema report of $text", answer.wholeTextOf("main h1"), text)
            }
            val ofScenario = shown[0].page.select("main table.conformance tbody th, main table.conformance li")
            assertEquals(listOf(text, text, text), ofScenario.map { it.wholeText() }, text)
            assertEquals(text, shown[1].wholeTextOf("main code"), text)
            assertEquals(listOf(text), shown[2].page.select("main .problem + ul li").map { it.wholeText() }, text)
        }
        assertEquals(emptyList(), api.violations)
    }
}
