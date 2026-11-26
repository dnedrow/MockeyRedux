package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.DerivedRealUrlDto
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.json
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import org.jsoup.nodes.Document
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 13.10: the pages that derive real URLs for all services at once. A form takes a regular expression
 * and its replacements, and the page after the derivation lists the real URLs that the server added.
 *
 * Each test drives the UI over HTTP the way a browser without scripting would (see [Browser]), and then
 * checks which requests reached the admin API.
 */
class UiRealUrlsTest {
    private val started = Started()

    @AfterTest
    fun stopEverything() = started.close()

    private val billingUrls = listOf("http://qa1.example.com/billing", "http://dev/b")
    private val billing = ServiceDto("svc-billing", "Billing", "billing", realUrls = billingUrls)

    /** What a stand-in server holds for a test: the server itself and a live view of its services. */
    private class Holding(
        val api: FakeAdminApi,
        val services: Map<String, ServiceDto>,
    )

    /** Starts a stand-in server that holds [services]. With the defaults, two services have a real URL with `qa1` in it. */
    private fun holding(services: List<ServiceDto> = listOf(Samples.customers, Samples.orders, billing)): Holding {
        val api = started.api()
        return Holding(api, api.hold(services))
    }

    /** Returns the rows of the table on [page], each as the texts of its cells. */
    private fun rowsOf(page: Document): List<List<String>> =
        page.select("main table.derived tbody tr").map { row -> row.select("th, td").map { it.text() } }

    /** Returns the requests to [api] that derived real URLs, each as its request line and its body. */
    private fun derivations(api: FakeAdminApi): List<Pair<String, String>> = api.changes()

    /** Fills the form with [expression] and [replacements], submits it, and returns the answer. */
    private fun derive(
        browser: Browser,
        expression: String,
        replacements: String,
    ): Browser.Response {
        val form = browser.get("/services/real-urls").form()
        return browser.submit(form, mapOf("expression" to expression, "replacements" to replacements))
    }

    // The form

    /** The overview leads to the form, which takes a regular expression and its replacements. */
    @Test
    fun theOverviewLeadsToTheForm() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val ofOverview = browser.get("/services").linksOf("main p.actions a")
        held.api.requests.clear()
        val answer = browser.get("/services/real-urls")

        val page = answer.page
        val form = answer.form()
        assertTrue("Derive real URLs" to "/services/real-urls" in ofOverview, "$ofOverview")
        assertEquals(200 to "Derive real URLs - Mockey", answer.status to page.title())
        assertEquals("post" to "/services/real-urls", form.attr("method") to form.attr("action"))
        assertEquals(mapOf("expression" to listOf(""), "replacements" to listOf("")), fieldsOf(form))
        assertEquals(listOf("Regular expression", "Replacements"), form.select("label").map { it.text() })
        assertEquals(listOf("input", "textarea"), form.select("[name=expression], [name=replacements]").map { it.tagName() })
        assertEquals("Derive the real URLs", form.select("button").text())
        assertEquals(listOf("Cancel" to "/services"), form.select("a").map { it.text() to it.attr("href") })
        assertEquals(listOf("/services"), page.select("nav a[aria-current=page]").map { it.attr("href") })
        assertEquals(listOf("GET system"), held.api.requests.map { it.line })
        assertEquals(emptyList(), held.api.violations)
    }

    // The derivation

    /**
     * The form asks the server to derive real URLs with the expression and with one replacement per line,
     * and the browser ends up on the page that lists what the server added.
     */
    @Test
    fun theFormDerivesRealUrlsAndShowsWhatWasAdded() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val sent = derive(browser, "qa1", "qa2\n\n  \nqa3\n")
        val report = browser.follow(sent)
        val again = browser.follow(sent)

        val page = report.page
        val rows =
            listOf(
                listOf("Orders", "http://qa2.example.com/orders"),
                listOf("Orders", "http://qa3.example.com/orders"),
                listOf("Billing", "http://qa2.example.com/billing"),
                listOf("Billing", "http://qa3.example.com/billing"),
            )
        assertEquals(303, sent.status)
        assertTrue(sent.header("location")!!.startsWith("/reports/"), sent.header("location"))
        assertEquals(200 to "Derived real URLs - Mockey", report.status to page.title())
        assertEquals("The server added 4 real URLs.", page.select("main h1 + p").text())
        assertEquals(listOf("Service", "Added real URL"), page.select("main table.derived thead th").map { it.text() })
        assertEquals(rows, rowsOf(page))
        val services = listOf("/service?id=svc-orders", "/service?id=svc-orders", "/service?id=svc-billing", "/service?id=svc-billing")
        assertEquals(services, page.select("main table.derived tbody a").map { it.attr("href") })
        assertEquals(4 to 0, page.select("main table.derived code.url").size to page.select("main table.derived code.url a").size)
        val leading = listOf("Services" to "/services", "Derive more real URLs" to "/services/real-urls")
        assertEquals(leading, page.select("main p.actions a").map { it.text() to it.attr("href") })
        val asked = "POST services/real-urls/derive" to """{"expression":"qa1","replacements":["qa2","qa3"]}"""
        assertEquals(listOf(asked), derivations(held.api))
        val ofOrders = listOf("http://qa1.example.com/orders", "http://qa2.example.com/orders", "http://qa3.example.com/orders")
        assertEquals(ofOrders, held.services.getValue("svc-orders").realUrls)
        assertEquals(report.page.select("main").html(), again.page.select("main").html())
        assertEquals(1, derivations(held.api).size)
        assertEquals(emptyList(), held.api.violations)
    }

    /** The report counts in words that fit: one real URL, and none at all, which the page explains. */
    @Test
    fun theReportSaysHowManyRealUrlsWereAdded() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val one = browser.follow(derive(browser, "dev", "test")).page
        val none = browser.follow(derive(browser, "zzz", "y")).page

        assertEquals("The server added 1 real URL.", one.select("main h1 + p").text())
        assertEquals(listOf(listOf("Billing", "http://test/b")), rowsOf(one))
        val nothing =
            "The server added no real URL. Either the expression found nothing in the real URLs, " +
                "or the services already list every derived URL."
        assertEquals(nothing, none.select("main h1 + p").text())
        assertEquals(0, none.select("main table").size)
    }

    /**
     * The expression and the replacements are sent exactly as typed. Spaces count in a regular expression,
     * and a replacement can refer to a group. Only lines without any text are left out.
     */
    @Test
    fun theExpressionAndTheReplacementsAreSentAsTyped() {
        val held = holding()
        val browser = started.browserFor(held.api)

        derive(browser, " (qa)1 ", " \$12 \r\n\tx\\\$y\n")

        val body = """{"expression":" (qa)1 ","replacements":[" ${'$'}12 ","\tx\\${'$'}y"]}"""
        assertEquals(listOf("POST services/real-urls/derive" to body), derivations(held.api))
    }

    // What is not taken

    /**
     * The requirement "Validation feedback" for the derivation: what the server has against the expression
     * or against the replacements is shown next to that field, and the form keeps what was entered.
     */
    @Test
    fun whatTheServerRejectsIsReportedAtItsField() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val badExpression = derive(browser, "(", "qa2\nqa3")
        val noReplacement = browser.submit(badExpression.form(), mapOf("expression" to "qa1", "replacements" to " \n"))
        val nothing = browser.submit(browser.get("/services/real-urls").form())

        val rejected = listOf(badExpression, noReplacement, nothing)
        val expression = mapOf("expression" to listOf("The expression must be a regular expression that is not empty."))
        val replacements = mapOf("replacements" to listOf("At least one replacement that is not blank is needed."))
        assertEquals(listOf(422, 422, 422), rejected.map { it.status })
        assertEquals(listOf(expression, replacements, expression), rejected.map { it.fieldProblems })
        assertEquals(mapOf("expression" to listOf("("), "replacements" to listOf("qa2\nqa3")), fieldsOf(badExpression.form()))
        assertEquals(mapOf("expression" to listOf("qa1"), "replacements" to listOf(" \n")), fieldsOf(noReplacement.form()))
        assertEquals(1, badExpression.page.select("main form .problem[role=alert]").size)
        assertEquals(listOf("http://qa1.example.com/orders"), held.services.getValue("svc-orders").realUrls)
        val first = """{"expression":"(","replacements":["qa2","qa3"]}"""
        val second = """{"expression":"qa1","replacements":[]}"""
        assertEquals(listOf(first, second, EMPTY), derivations(held.api).map { it.second })
        assertEquals(emptyList(), held.api.violations)
    }

    /** A message of the server for a member that the form has no field for is shown where the form ends. */
    @Test
    fun aMessageForNoFieldIsShownWhereTheFormEnds() {
        val held = holding()
        val errors = listOf(FieldErrorDto("other", "Something else is wrong."))
        held.api.on("POST", "services/real-urls/derive") { problem(422, "The request is not valid.", errors) }

        val rejected = derive(started.browserFor(held.api), "qa1", "qa2")

        assertEquals(422 to emptyMap(), rejected.status to rejected.fieldProblems)
        assertEquals(listOf("other: Something else is wrong."), rejected.page.select("main form > .field-problem p").map { it.text() })
        assertEquals(mapOf("expression" to listOf("qa1"), "replacements" to listOf("qa2")), fieldsOf(rejected.form()))
    }

    // Stored content is text

    /**
     * The requirement "Stored content is shown as text" for the derivation: the report shows the names of
     * the services and the added URLs as plain text, and a rejected form holds what was entered as values.
     */
    @Test
    fun theDerivationPagesShowStoredContentAsText() {
        val held = holding()
        var text = ""
        held.api.on("POST", "services/real-urls/derive") { request ->
            val refusal = problem(422, text, listOf(FieldErrorDto("expression", text)))
            if ("refuse" in request.body) refusal else json(listOf(DerivedRealUrlDto(text, text, text)))
        }
        val browser = started.browserFor(held.api)

        for (hostile in Hostile.MARKUP + Hostile.ADDRESSES) {
            text = hostile
            val report = browser.follow(derive(browser, hostile, hostile))
            val rejected = derive(browser, hostile, "refuse $hostile")

            assertEquals(200 to 422, report.status to rejected.status, hostile)
            for (answer in listOf(report, rejected)) assertEquals(emptyList(), Hostile.moreThanText(answer), hostile)
            val cells = report.page.select("main table.derived tbody th, main table.derived tbody td")
            assertEquals(listOf(hostile, hostile), cells.map { it.wholeText() }, hostile)
            assertEquals(Address.of("/service", "id" to hostile), cells.select("a").attr("href"), hostile)
            assertEquals(hostile, rejected.wholeTextOf("main .field-problem p"), hostile)
        }
        assertEquals(emptyList(), held.api.violations)
    }

    private companion object {
        /** The request that a form without input makes. */
        const val EMPTY = """{"expression":"","replacements":[]}"""
    }
}
