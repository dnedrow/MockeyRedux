package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.MergeReportDto
import io.github.dnedrow.mockey.api.MergedScenarioDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.json
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import org.jsoup.nodes.Document
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 13.10: the pages that merge other services into a service. A form chooses the services to merge, a
 * confirmation step asks about exactly that choice, and the page after the merge shows the merge report.
 *
 * Each test drives the UI over HTTP the way a browser without scripting would (see [Browser]), and then
 * checks which requests reached the admin API.
 */
class UiMergeTest {
    private val started = Started()

    @AfterTest
    fun stopEverything() = started.close()

    /** A service with the scenarios of Customers, so a merge into Customers skips all of them. */
    private val clients = Samples.customers.copy(id = "svc-clients", name = "Clients", mockUrl = "clients", tags = listOf("team-c"))

    /** A service with one scenario that Customers doesn't have, a tag, and a real URL. */
    private val vip = Samples.found.copy(id = "sc-vip", name = "vip", responseBody = """{"vip":true}""", tags = emptyList())
    private val buyers =
        ServiceDto(
            id = "svc-buyers",
            name = "Buyers",
            mockUrl = "buyers",
            realUrls = listOf("http://qa1.example.com/buyers"),
            tags = listOf("team-b"),
            scenarios = listOf(vip),
        )

    /** What a stand-in server holds for a test: the server itself and a live view of its services. */
    private class Holding(
        val api: FakeAdminApi,
        val services: MutableMap<String, ServiceDto>,
    )

    /** Starts a stand-in server that holds [services]. */
    private fun holding(
        services: List<ServiceDto> = listOf(Samples.customers, Samples.orders, clients, buyers),
        paths: Map<String, String> = emptyMap(),
    ): Holding {
        val api = started.api()
        return Holding(api, api.hold(services, paths = paths))
    }

    /** Returns the check boxes of the form on [page], each as its value, its label with the note behind it, and whether it's checked. */
    private fun boxesOf(page: Document): List<Triple<String, String, Boolean>> =
        page.select("main form input[type=checkbox]").map { box ->
            Triple(box.attr("value"), box.parent()!!.text(), box.hasAttr("checked"))
        }

    /** Returns the requests to [api] that merged services, each as its request line and its body. */
    private fun merges(api: FakeAdminApi): List<Pair<String, String>> = api.changes()

    // Choosing

    /** The page of a service leads to the form, which lists the other services by name, each with a check box. */
    @Test
    fun theFormListsTheOtherServices() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val ofService = browser.get("/service?id=svc-customers").linksOf("main .actions a")
        val answer = browser.get("/service/merge?id=svc-customers")

        val page = answer.page
        val form = answer.form()
        val boxes =
            listOf(
                Triple("svc-buyers", "Buyers (1 scenario)", false),
                Triple("svc-clients", "Clients (2 scenarios)", false),
                Triple("svc-orders", "Orders (no scenario)", false),
            )
        assertTrue("Merge services into this one" to "/service/merge?id=svc-customers" in ofService, "$ofService")
        assertEquals(200 to "Merge services into Customers - Mockey", answer.status to page.title())
        assertEquals(boxes, boxesOf(page))
        assertEquals("get" to "/service/merge", form.attr("method") to form.attr("action"))
        val hidden = form.select("input[type=hidden]").map { it.attr("name") to it.attr("value") }
        assertEquals(listOf("id" to "svc-customers", "step" to "confirm"), hidden)
        assertEquals("Services to merge", form.select("legend").text())
        assertEquals("Continue", form.select("button").text())
        assertEquals(listOf("Cancel" to "/service?id=svc-customers"), form.select("a").map { it.text() to it.attr("href") })
        assertEquals(0, page.select("main .field-problem").size)
        assertEquals(listOf("/services"), page.select("nav a[aria-current=page]").map { it.attr("href") })
        val ofTheForm = held.api.requests.drop(REQUESTS_OF_THE_SERVICE_PAGE)
        assertEquals(listOf("GET system", "GET services/svc-customers", "GET services"), ofTheForm.map { it.line })
        assertEquals(emptyList(), held.api.violations)
    }

    /** The labels of the boxes belong to their boxes, so a click on a name checks its box. */
    @Test
    fun everyBoxHasALabelOfItsOwn() {
        val page = started.browserFor(holding().api).get("/service/merge?id=svc-customers").page

        val ids = page.select("main form input[type=checkbox]").map { it.id() }
        assertEquals(listOf("f-source-0", "f-source-1", "f-source-2"), ids)
        assertEquals(ids, page.select("main form label").map { it.attr("for") })
        assertEquals(listOf("Buyers", "Clients", "Orders"), page.select("main form label").map { it.text() })
    }

    /** The form lists the services by name without regard to letter case, and services with the same name by identifier. */
    @Test
    fun theServicesAreListedByNameWhateverTheirLetterCaseIs() {
        val lower = buyers.copy(id = "svc-lower", name = "accounts", mockUrl = "accounts", realUrls = emptyList())
        val twin = buyers.copy(id = "svc-b2", mockUrl = "buyers-2", realUrls = emptyList())
        val held = holding(listOf(Samples.customers, Samples.orders, buyers, twin, lower))

        val page = started.browserFor(held.api).get("/service/merge?id=svc-customers").page

        assertEquals(listOf("svc-lower", "svc-b2", "svc-buyers", "svc-orders"), boxesOf(page).map { it.first })
    }

    /** A service that is the only one has nothing to merge, and the page says so instead of showing a form. */
    @Test
    fun aServiceWithoutOthersHasNothingToMerge() {
        val held = holding(listOf(Samples.customers))

        val page = started.browserFor(held.api).get("/service/merge?id=svc-customers").page

        assertEquals(0, page.select("main form").size)
        assertEquals("There is no other service to merge into this one.", page.select("main > p").eq(2).text())
        val back = page.select("main p.actions a").map { it.text() to it.attr("href") }
        assertEquals(listOf("Back to the service" to "/service?id=svc-customers"), back)
    }

    // The confirmation step

    /**
     * The requirement "Confirmation of destructive actions" for a merge, which can delete the merged
     * services: the choice goes into the address of the confirmation step, the step names the chosen
     * services, and nothing is merged until its form is submitted.
     */
    @Test
    fun theUiAsksForConfirmationBeforeItMergesServices() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val choice = browser.get("/service/merge?id=svc-customers").form()

        val asked = browser.submit(choice, rows = mapOf("source" to listOf("svc-buyers", "svc-orders")))

        val page = asked.page
        val form = asked.form()
        val address = "/service/merge?id=svc-customers&step=confirm&source=svc-buyers&source=svc-orders"
        val consequences =
            listOf(
                "The scenarios of these services are copied into Customers: Buyers, Orders.",
                "A scenario is skipped if Customers already has a scenario with the same name and response body. " +
                    "The tags of the merged services are added to Customers.",
                "If every scenario is copied, the merged services are deleted, and their real URLs move to Customers. " +
                    "If a scenario is skipped, the merged services stay as they are.",
            )
        assertEquals(200 to "Merge 2 services into Customers? - Mockey", asked.status to page.title())
        assertEquals(consequences, page.select("main > p").map { it.text() })
        assertEquals("post" to address, form.attr("method") to form.attr("action"))
        assertEquals(mapOf("confirmed" to listOf("yes")), fieldsOf(form))
        assertEquals("Merge the services" to "danger", form.select("button").text() to form.select("button").attr("class"))
        assertEquals(listOf("Cancel" to "/service?id=svc-customers"), form.select("a").map { it.text() to it.attr("href") })
        assertEquals(emptyList(), merges(held.api))
        assertEquals(4, held.services.size)
    }

    /** The question names a single service that is to be merged. */
    @Test
    fun theQuestionNamesASingleService() {
        val browser = started.browserFor(holding().api)

        val page = browser.get("/service/merge?id=svc-customers&step=confirm&source=svc-buyers").page

        assertEquals("Merge Buyers into Customers?", page.select("main h1").text())
        assertEquals("The scenarios of these services are copied into Customers: Buyers.", page.select("main > p").first()!!.text())
    }

    /** A service that the address names twice is chosen once, so the question is about that one service. */
    @Test
    fun aServiceThatTheAddressNamesTwiceIsChosenOnce() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val asked = browser.get("/service/merge?id=svc-customers&step=confirm&source=svc-buyers&source=svc-buyers")
        browser.submit(asked.form())

        assertEquals("Merge Buyers into Customers?", asked.page.select("main h1").text())
        assertEquals("/service/merge?id=svc-customers&step=confirm&source=svc-buyers", asked.form().attr("action"))
        assertEquals(listOf("POST services/svc-customers/merge" to """{"sourceIds":["svc-buyers"]}"""), merges(held.api))
    }

    /** Without a choice, the confirmation step shows the form again and says that a service has to be chosen. */
    @Test
    fun theFormSaysWhenNoServiceIsChosen() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val nothing = browser.submit(browser.get("/service/merge?id=svc-customers").form())
        val itself = browser.get("/service/merge?id=svc-customers&step=confirm&source=svc-customers")

        for (answer in listOf(nothing, itself)) {
            assertEquals(200 to "Merge services into Customers", answer.status to answer.page.select("main h1").text())
            assertEquals(listOf("Choose at least one service to merge."), answer.page.select("main .field-problem p").map { it.text() })
            assertEquals(listOf(false, false, false), boxesOf(answer.page).map { it.third })
            assertEquals("get", answer.form().attr("method"))
        }
        assertEquals(emptyList(), merges(held.api))
    }

    /**
     * A chosen service that isn't there any longer sends the browser back to the form, which says so and
     * keeps the rest of the choice. A choice without the confirmation step in its address is the form with
     * the chosen boxes checked.
     */
    @Test
    fun aChosenServiceThatIsGoneLeadsBackToTheForm() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val chosen = "source=svc-buyers&source=svc-gone"

        val gone = browser.get("/service/merge?id=svc-customers&step=confirm&$chosen")
        val choosing = browser.get("/service/merge?id=svc-customers&$chosen")
        val otherStep = browser.get("/service/merge?id=svc-customers&step=other&source=svc-buyers")

        val message = "A chosen service is not there any longer. Check the choice, and continue."
        assertEquals(listOf(message), gone.page.select("main .field-problem p").map { it.text() })
        assertEquals(listOf(true, false, false), boxesOf(gone.page).map { it.third })
        assertEquals(0, choosing.page.select("main .field-problem").size)
        assertEquals(listOf(true, false, false), boxesOf(choosing.page).map { it.third })
        assertEquals(listOf("get", "get", "get"), listOf(gone, choosing, otherStep).map { it.form().attr("method") })
        assertEquals(emptyList(), merges(held.api))
    }

    // The merge

    /**
     * After the confirmation, the UI asks the server to merge the chosen services, and the browser ends up
     * on the page that shows the merge report. Here every scenario was copied, so the merged services are
     * deleted.
     */
    @Test
    fun theMergeShowsTheReportOfTheServer() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val asked = browser.get("/service/merge?id=svc-customers&step=confirm&source=svc-buyers&source=svc-orders")

        val sent = browser.submit(asked.form())
        val report = browser.follow(sent)
        val again = browser.follow(sent)

        val page = report.page
        assertEquals(303, sent.status)
        assertTrue(sent.header("location")!!.startsWith("/reports/"), sent.header("location"))
        assertEquals(200 to "Merge into Customers - Mockey", report.status to page.title())
        val summary =
            "The merge copied 1 scenario and skipped 0. The merged services were deleted. " +
                "Their tags and real URLs now belong to Customers."
        assertEquals(summary, page.select("main h1 + p").text())
        assertEquals(listOf("Copied scenarios"), page.select("main h2").map { it.text() })
        assertEquals(listOf("Buyers: vip"), page.select("main ul li").map { it.text() })
        val leading = listOf("Back to the service" to "/service?id=svc-customers", "Services" to "/services")
        assertEquals(leading, page.select("main p.actions a").map { it.text() to it.attr("href") })
        assertEquals(listOf("POST services/svc-customers/merge" to """{"sourceIds":["svc-buyers","svc-orders"]}"""), merges(held.api))
        assertEquals(setOf("svc-customers", "svc-clients"), held.services.keys)
        val merged = held.services.getValue("svc-customers")
        assertEquals(listOf("found", "missing", "vip"), merged.scenarios.map { it.name })
        assertEquals(listOf("team-a", "team-b"), merged.tags)
        assertEquals(listOf("http://qa1.example.com/buyers", "http://qa1.example.com/orders"), merged.realUrls)
        assertEquals(report.page.select("main").html(), again.page.select("main").html())
        assertEquals(1, merges(held.api).size)
        assertEquals(emptyList(), held.api.violations)
    }

    /** A merge that skips a scenario keeps the merged services, and the report lists what was skipped. */
    @Test
    fun aMergeThatSkipsAScenarioKeepsTheMergedServices() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val asked = browser.get("/service/merge?id=svc-customers&step=confirm&source=svc-buyers&source=svc-clients")

        val page = browser.follow(browser.submit(asked.form())).page

        val summary =
            "The merge copied 1 scenario and skipped 2. The merged services were kept, because not every scenario was copied. " +
                "Their tags were added to Customers."
        assertEquals(summary, page.select("main h1 + p").text())
        assertEquals(listOf("Copied scenarios", "Skipped scenarios"), page.select("main h2").map { it.text() })
        val skipped = "Customers already has a scenario with the same name and response body as each of these."
        assertEquals(skipped, page.select("main h2 + p").text())
        assertEquals(listOf("Buyers: vip", "Clients: found", "Clients: missing"), page.select("main ul li").map { it.text() })
        assertEquals(4, held.services.size)
        assertEquals(listOf("team-a", "team-b", "team-c"), held.services.getValue("svc-customers").tags)
    }

    /** The report counts in words that fit: several copied scenarios, and a merge that merged nothing. */
    @Test
    fun theReportCountsWhatWasCopied() {
        val held = holding()
        val copied = listOf(MergedScenarioDto("Buyers", "vip"), MergedScenarioDto("Buyers", "regular"))
        val nothingMerged = MergeReportDto(emptyList(), emptyList(), sourcesDeleted = false)
        val reports = ArrayDeque(listOf(MergeReportDto(copied, emptyList(), sourcesDeleted = true), nothingMerged))
        held.api.on("POST", "services/svc-customers/merge") { json(reports.removeFirst()) }
        val browser = started.browserFor(held.api)
        val address = "/service/merge?id=svc-customers&step=confirm&source=svc-buyers"

        val several = browser.follow(browser.submit(browser.get(address).form())).page
        val nothing = browser.follow(browser.submit(browser.get(address).form())).page

        val deleted = "The merged services were deleted. Their tags and real URLs now belong to Customers."
        assertEquals("The merge copied 2 scenarios and skipped 0. $deleted", several.select("main h1 + p").text())
        assertEquals("The merge copied 0 scenarios and skipped 0. No service was merged.", nothing.select("main h1 + p").text())
        assertEquals(0, nothing.select("main h2, main ul").size)
    }

    // What is not taken

    /** A request that isn't the form of the confirmation step merges nothing, with or without a choice in its address. */
    @Test
    fun aRequestWithoutTheConfirmationMergesNothing() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val token = browser.token("/service/merge?id=svc-customers&step=confirm&source=svc-buyers")
        val address = "/service/merge?id=svc-customers&step=confirm&source=svc-buyers"

        val unconfirmed = browser.post(address, listOf("_token" to token))
        val withoutToken = browser.post(address, listOf("confirmed" to "yes"))
        val withoutChoice = browser.post("/service/merge?id=svc-customers&step=confirm", listOf("_token" to token, "confirmed" to "yes"))

        assertEquals(200 to "Merge Buyers into Customers?", unconfirmed.status to unconfirmed.page.select("main h1").text())
        assertEquals(403, withoutToken.status)
        assertEquals(422 to "The form was not taken", withoutChoice.status to withoutChoice.page.select("main h1").text())
        assertEquals("Choose at least one service to merge.", withoutChoice.wholeTextOf("main .problem"))
        assertEquals(emptyList(), merges(held.api))
        assertEquals(4, held.services.size)
    }

    /** A merge that the server refuses is reported, and so is a merge into a service that isn't there. */
    @Test
    fun aMergeThatTheServerRefusesIsReported() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val asked = browser.get("/service/merge?id=svc-customers&step=confirm&source=svc-buyers")
        held.api.requests.clear()
        held.api.on("GET", "services/svc-gone") { problem(404, "No service has the identifier svc-gone.") }

        held.services.remove("svc-buyers")
        val refused = browser.submit(asked.form())
        val ofNone = listOf("/service/merge?id=svc-gone", "/service/merge?id=svc-gone&step=confirm&source=svc-buyers").map(browser::get)
        val withoutId = browser.get("/service/merge")

        assertEquals(502 to "The server refused the request", refused.status to refused.page.select("main h1").text())
        val said = refused.page.select("main .problem").map { it.text() }
        assertEquals(listOf("The request is not valid.", "sourceIds[0]: No service has this identifier.", "Nothing was changed."), said)
        for (answer in ofNone) {
            assertEquals(404 to "Not there", answer.status to answer.page.select("main h1").text())
            assertEquals("No service has the identifier svc-gone.", answer.page.select("main .problem").text())
        }
        assertEquals(404, withoutId.status)
        assertEquals(listOf("POST services/svc-customers/merge"), merges(held.api).map { it.first })
        assertEquals(emptyList(), held.api.violations)
    }

    // Identifiers and content

    /** The pages work for services with any identifiers: the identifiers are parameters of the address. */
    @Test
    fun thePagesNameServicesInTheirAddressesWhateverTheirIdentifiersAre() {
        val ids = mapOf("a/b c" to "a%2Fb%20c", "x?y#z&w=1" to "x%3Fy%23z%26w%3D1")
        val plain = buyers.copy(realUrls = emptyList())
        val services = ids.keys.mapIndexed { index, id -> plain.copy(id = id, name = "Service $index", mockUrl = "s$index") }
        val held = holding(services, ids)
        val browser = started.browserFor(held.api)
        val (into, source) = ids.keys.toList()

        val choice = browser.get(Address.of("/service/merge", "id" to into))
        val asked = browser.submit(choice.form(), rows = mapOf("source" to listOf(source)))
        val sent = browser.submit(asked.form())

        assertEquals(listOf(200, 200, 303), listOf(choice, asked, sent).map { it.status })
        assertEquals(Address.of("/service/merge", "id" to into, "step" to "confirm", "source" to source), asked.form().attr("action"))
        assertTrue(Hostile.SAFE_ADDRESS.matches(asked.form().attr("action")))
        assertEquals(listOf("POST services/${ids.getValue(into)}/merge" to """{"sourceIds":["x?y#z&w=1"]}"""), merges(held.api))
        assertEquals(emptyList(), held.api.violations)
    }

    /**
     * The requirement "Stored content is shown as text" for the merge pages: whatever the services are
     * called, and whatever the server reports, the form, the confirmation step, and the report show it as
     * plain text.
     *
     * The test opens the confirmation step by its address. A browser changes the line breaks of every value
     * that a form sends, so a service whose identifier has a line break can't be chosen in the form.
     */
    @Test
    fun theMergePagesShowStoredContentAsText() {
        val api = started.api()
        val browser = started.browserFor(api)

        for (text in Hostile.MARKUP + Hostile.ADDRESSES) {
            val scenario = Samples.found.copy(id = text, name = text)
            val into = ServiceDto("$text-into", text, text, tags = listOf(text), scenarios = listOf(scenario))
            val source = into.copy(id = text)
            val paths = mapOf(text to Hostile.written(text), into.id to Hostile.written(into.id))
            api.hold(listOf(into, source), paths = paths)
            val item = MergedScenarioDto(text, text)
            api.on("POST", "services/${paths.getValue(into.id)}/merge") { json(MergeReportDto(listOf(item), listOf(item), false)) }

            val choice = browser.get(Address.of("/service/merge", "id" to into.id))
            val asked = browser.get(Address.of("/service/merge", "id" to into.id, "step" to "confirm", "source" to text))
            val report = browser.follow(browser.submit(asked.form()))

            assertEquals(listOf(200, 200, 200), listOf(choice, asked, report).map { it.status }, text)
            for (answer in listOf(choice, asked, report)) assertEquals(emptyList(), Hostile.moreThanText(answer), text)
            assertEquals(text, choice.wholeTextOf("main form label"), text)
            assertEquals(text, choice.page.select("main form input[type=checkbox]").attr("value"), text)
            assertEquals("Merge $text into $text?", asked.wholeTextOf("main h1"), text)
            assertEquals(listOf("$text: $text", "$text: $text"), report.page.select("main ul li").map { it.wholeText() }, text)
        }
        assertEquals(emptyList(), api.violations)
    }

    private companion object {
        /** The number of requests that the UI sends to the admin API for the page of a service. */
        const val REQUESTS_OF_THE_SERVICE_PAGE = 4
    }
}
