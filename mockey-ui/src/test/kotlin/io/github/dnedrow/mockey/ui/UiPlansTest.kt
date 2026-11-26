package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.PlanItemDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import org.jsoup.nodes.Document
import org.jsoup.nodes.FormElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 13.8: the pages for plans. A plan is created from the current settings of services, and the pages
 * list the plans, show a single plan, edit its name, description, and tags, save the current settings into
 * it again, apply it, make it the default plan, and delete it after a confirmation.
 *
 * Each test drives the UI over HTTP the way a browser without scripting would (see [Browser]), and then
 * checks which requests reached the admin API.
 */
class UiPlansTest {
    private val started = Started()

    @AfterTest
    fun stopEverything() = started.close()

    private val night = Samples.plan
    private val alpha = PlanDto("plan-alpha", "alpha", null, emptyList(), emptyList(), "2026-01-02T03:04:05Z")

    /** What a stand-in server holds for a test: the server itself and live views of its services and plans. */
    private class Held(
        val api: FakeAdminApi,
        val services: MutableMap<String, ServiceDto>,
        val plans: MutableMap<String, PlanDto>,
    )

    /**
     * Starts a stand-in server that holds [plans] and [services]. [default] is the default plan. The sample
     * services aren't in the order of their names, so a test can tell whether a page sorts them.
     */
    private fun holding(
        plans: List<PlanDto> = listOf(night, alpha),
        default: String? = null,
        services: List<ServiceDto> = listOf(Samples.orders, Samples.customers),
        paths: Map<String, String> = emptyMap(),
    ): Held {
        val api = started.api()
        val held = api.hold(services)
        return Held(api, held, api.holdPlans(held, plans, default, paths))
    }

    /** Returns the rows of the table that [selector] finds on [page], each as the texts of its cells. */
    private fun rows(
        page: Document,
        selector: String,
    ): List<List<String>> = page.select("$selector tbody tr").map { row -> row.select("th, td").map { it.text() } }

    // The list

    /** The list shows the plans sorted by name, marks the default plan, and links to the page of each plan. */
    @Test
    fun theListShowsThePlansByNameAndMarksTheDefaultPlan() {
        val held = holding(default = night.id)
        val browser = started.browserFor(held.api)

        val answer = browser.get("/plans")

        val page = answer.page
        val plans =
            listOf(
                listOf("alpha", "", "", "0", "2026-01-02 03:04:05 UTC"),
                listOf("Night default", "What the night shift needs", "nightly", "1", "never"),
            )
        assertEquals(200 to "Plans - Mockey", answer.status to page.title())
        assertEquals(listOf("Plan", "Description", "Tags", "Services", "Last applied"), page.select("main thead th").map { it.text() })
        assertEquals(plans, rows(page, "main table.plans"))
        assertEquals(listOf("/plan?id=plan-alpha", "/plan?id=plan-night"), page.select("main tbody th a").map { it.attr("href") })
        assertEquals(listOf("New plan" to "/plan/new"), page.select("main p.actions a").map { it.text() to it.attr("href") })
        assertEquals(listOf("/plans"), page.select("nav a[aria-current=page]").map { it.attr("href") })
        assertEquals(listOf("GET system", "GET plans", "GET default-plan"), held.api.requests.map { it.line })
        assertEquals(emptyList(), held.api.violations)
    }

    /** Without any plans, the list says so and still offers the form for a new plan. */
    @Test
    fun theListSaysWhenThereAreNoPlans() {
        val browser = started.browserFor(holding(emptyList()).api)

        val page = browser.get("/plans").page

        assertEquals(0, page.select("main table").size)
        assertEquals("There are no plans yet.", page.select("main > p").last()!!.text())
        assertEquals(listOf("/plan/new"), page.select("main p.actions a").map { it.attr("href") })
    }

    // The page of a plan

    /** The page of a plan shows what the plan holds for each service, and it leads to everything that can be done with the plan. */
    @Test
    fun thePageOfAPlanShowsItsSettingsAndItsActions() {
        val items = night.items + PlanItemDto("Orders")
        val held = holding(listOf(night.copy(description = "First line.\nSecond line.", items = items)))
        val browser = started.browserFor(held.api)

        val answer = browser.get("/plan?id=plan-night")

        val page = answer.page
        val facts = page.select("main dl dt").associate { it.text() to it.nextElementSibling()!!.wholeText() }
        assertEquals(200, answer.status)
        assertEquals("Plan Night - Mockey" to "Plan Night", page.title() to page.select("main h1").text())
        assertEquals(mapOf("Description" to "First line.\nSecond line.", "Tags" to "nightly", "Last applied" to "never"), facts)
        assertEquals(listOf("Service", "Default scenario", "Response mode", "Delay"), page.select("main thead th").map { it.text() })
        val settings = listOf(listOf("Customers", "missing", "Static", "250 ms"), listOf("Orders", "left as it is", "Proxy", "0 ms"))
        assertEquals(settings, rows(page, "main table.items"))
        val links =
            mapOf(
                "Edit" to "/plan/edit?id=plan-night",
                "Save current settings" to "/plan/save?id=plan-night",
                "Delete" to "/plan/delete?id=plan-night",
            )
        assertEquals(links, page.select("main .actions a").associate { it.text() to it.attr("href") })
        val apply = page.select("main .actions form").single()
        assertEquals(Triple("post", "/plan/apply?id=plan-night", "Apply"), Triple(apply.attr("method"), apply.attr("action"), apply.text()))
        assertEquals(listOf("Services", "Default plan"), page.select("main h2").map { it.text() })
        val designation = page.select("main .designation").single()
        assertEquals("This is not the default plan. The server applies the default plan when it starts.", designation.select("p").text())
        assertEquals("yes" to "Make it the default plan", designation.select("button").let { it.attr("value") to it.text() })
        assertEquals("/plan/default?id=plan-night", designation.select("form").attr("action"))
        assertEquals(listOf("/plans"), page.select("nav a[aria-current=page]").map { it.attr("href") })
        assertEquals(listOf("GET system", "GET plans/plan-night", "GET default-plan"), held.api.requests.map { it.line })
        assertEquals(emptyList(), held.api.violations)
    }

    /** A plan without settings says so, and a plan without a description leaves the description out. */
    @Test
    fun thePageOfAPlanWithoutSettingsSaysSo() {
        val browser = started.browserFor(holding(default = alpha.id).api)

        val page = browser.get("/plan?id=plan-alpha").page

        assertEquals(listOf("Tags", "Last applied"), page.select("main dl dt").map { it.text() })
        assertEquals(listOf("none", "2026-01-02 03:04:05 UTC"), page.select("main dl dd").map { it.text() })
        assertEquals("The plan holds no settings. Applying it changes nothing.", page.select("main h2 + p").first()!!.text())
        val designation = page.select("main .designation").single()
        assertEquals("This is the default plan. The server applies it when it starts.", designation.select("p").text())
        assertEquals("no" to "Stop using it as the default plan", designation.select("button").let { it.attr("value") to it.text() })
    }

    // A new plan

    /**
     * The form for a new plan lists every service with a checked box. Submitting it saves the current
     * settings of the checked services as a plan, and the browser ends up on the page of that plan.
     */
    @Test
    fun theFormForANewPlanSavesTheCurrentSettingsOfTheCheckedServices() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val answer = browser.get("/plan/new")
        val form = answer.form()
        val entered = mapOf("name" to " Evening ", "description" to "After hours", "tags" to "late shift")
        val sent = browser.submit(form, entered, rows = mapOf("service" to listOf("svc-customers")))
        val created = browser.follow(sent)
        val ofDefaults = browser.submit(form)

        assertEquals(200 to "New plan", answer.status to answer.page.select("main h1").text())
        assertEquals("post" to "/plan/new", form.attr("method") to form.attr("action"))
        val initial = mapOf("name" to listOf(""), "description" to listOf(""), "tags" to listOf(""))
        assertEquals(initial + ("service" to listOf("svc-customers", "svc-orders")), fieldsOf(form))
        assertEquals(listOf("Name", "Description", "Tags", "Customers", "Orders"), form.select("label").map { it.text() })
        assertEquals(0, form.select("input[name^=_shown.]").size)
        assertEquals(listOf("Cancel" to "/plans"), form.select(".actions a").map { it.text() to it.attr("href") })
        assertEquals(303 to "/plan?id=plan-new-1", sent.status to sent.header("location"))
        assertEquals("The plan Evening now holds the current settings of 1 service.", created.notice)
        assertEquals("Plan Evening", created.page.select("main h1").text())
        assertEquals(listOf(listOf("Customers", "found", "Static", "5 ms")), rows(created.page, "main table.items"))
        assertEquals(303 to "/plan?id=plan-new-2", ofDefaults.status to ofDefaults.header("location"))
        val asked =
            listOf(
                "POST plans" to """{"serviceIds":["svc-customers"],"name":"Evening","description":"After hours","tags":["late","shift"]}""",
                "POST plans" to """{"serviceIds":["svc-customers","svc-orders"],"name":null,"description":null,"tags":null}""",
            )
        assertEquals(asked, held.api.changes())
        assertEquals("The plan Plan (auto-generated-name) now holds the current settings of 2 services.", browser.follow(ofDefaults).notice)
        assertEquals(emptyList(), held.api.violations)
    }

    /** The server updates an existing plan if a new plan uses its name, and the browser ends up on the page of that plan. */
    @Test
    fun aNewPlanWithTheNameOfAnExistingPlanUpdatesThatPlan() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val sent = browser.submit(browser.get("/plan/new").form(), mapOf("name" to "night"))
        val after = browser.follow(sent)

        assertEquals(303 to "/plan?id=plan-night", sent.status to sent.header("location"))
        assertEquals("The plan night now holds the current settings of 2 services.", after.notice)
        assertEquals(listOf("Customers", "Orders"), rows(after.page, "main table.items").map { it.first() })
        assertEquals(listOf("What the night shift needs"), after.page.select("main dl dd.lines").map { it.text() })
    }

    /** A plan needs at least one service. Without a checked box, the form is displayed again and no request is sent. */
    @Test
    fun theFormsRejectASaveWithoutAService() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val none = mapOf("service" to emptyList<String>())

        val ofNew = browser.submit(browser.get("/plan/new").form(), mapOf("name" to "Kept"), rows = none)
        val ofSave = browser.submit(browser.get("/plan/save?id=plan-night").form(), rows = none)

        val message = mapOf("service" to listOf("Choose at least one service."))
        assertEquals(listOf(422 to message, 422 to message), listOf(ofNew, ofSave).map { it.status to it.fieldProblems })
        assertEquals("New plan" to "Kept", ofNew.page.select("main h1").text() to ofNew.form().select("[name=name]").attr("value"))
        assertEquals("Save current settings into the plan Night", ofSave.page.select("main h1").text())
        assertEquals(0, ofNew.form().select("[name=service][checked]").size)
        assertEquals(emptyList(), held.api.changes())
    }

    /** A message from the server about a service shows up next to the check boxes. */
    @Test
    fun aRejectedSaveShowsTheMessageOfTheServerNextToTheServices() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val errors = listOf(FieldErrorDto("serviceIds[1]", "No service has this identifier."))
        held.api.on("POST", "plans") { problem(422, "The request is not valid.", errors) }

        val answer = browser.submit(browser.get("/plan/new").form(), mapOf("name" to "Kept", "tags" to "a b"))

        assertEquals(422 to "New plan", answer.status to answer.page.select("main h1").text())
        assertEquals(mapOf("service" to listOf("No service has this identifier.")), answer.fieldProblems)
        val again = fieldsOf(answer.form())
        assertEquals(listOf("Kept") to listOf("a b"), again["name"] to again["tags"])
        assertEquals(listOf("svc-customers", "svc-orders"), again["service"])
    }

    /** Without any services, there's nothing to save, and the page says so instead of showing a form. */
    @Test
    fun theFormForANewPlanNeedsServices() {
        val browser = started.browserFor(holding(services = emptyList()).api)

        val pages = listOf("/plan/new", "/plan/save?id=plan-night").map { browser.get(it).page }

        assertEquals(listOf(0, 0), pages.map { it.select("main form").size })
        val said = "There are no services yet, so there is nothing to save in a plan."
        assertEquals(listOf(said, said), pages.map { it.select("main > p:not(.actions)").last()!!.text() })
        assertEquals(listOf("/service/new"), pages.first().select("main p.actions a").map { it.attr("href") })
    }

    // Editing a plan

    /** The form that edits a plan only sends the attributes whose fields were changed. */
    @Test
    fun theEditFormOnlySendsTheChangedAttributes() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val answer = browser.get("/plan/edit?id=plan-night")
        val form = answer.form()
        val changes =
            listOf(
                emptyMap<String, String>() to "{}",
                mapOf("name" to "Dawn") to """{"name":"Dawn"}""",
                mapOf("description" to " \n ") to """{"description":null}""",
                mapOf("description" to "Two\nlines") to """{"description":"Two\nlines"}""",
                mapOf("tags" to " b  a ") to """{"tags":["b","a"]}""",
                mapOf("tags" to "") to """{"tags":[]}""",
            )
        val answers = changes.map { (edits, _) -> browser.submit(form, edits) }
        val after = browser.follow(answers.last())

        val fields = mapOf("name" to listOf("Night"), "description" to listOf("What the night shift needs"), "tags" to listOf("nightly"))
        assertEquals("Edit the plan Night" to "/plan/edit?id=plan-night", answer.page.select("main h1").text() to form.attr("action"))
        assertEquals(fields, fieldsOf(form))
        val hidden = listOf("_token") + PlanForm.FIELDS.map { "_shown.$it" }
        assertEquals(hidden, form.select("input[type=hidden]").map { it.attr("name") })
        assertEquals(listOf("Cancel" to "/plan?id=plan-night"), form.select(".actions a").map { it.text() to it.attr("href") })
        assertEquals(changes.map { 303 to "/plan?id=plan-night" }, answers.map { it.status to it.header("location") })
        assertEquals(changes.map { "PATCH plans/plan-night" to it.second }, held.api.changes())
        assertEquals("The plan Dawn was saved.", after.notice)
        assertEquals(emptyList(), held.api.violations)
    }

    /** Browsers change some values on their own. The edit form doesn't treat such a value as changed. */
    @Test
    fun theEditFormDoesNotSendAValueThatOnlyTheBrowserAltered() {
        val odd = "one\ntwo\u0000three\r\nfour"
        val held = holding(listOf(night.copy(name = odd, description = odd, tags = listOf(odd))))
        val browser = started.browserFor(held.api)
        val form = browser.get("/plan/edit?id=plan-night").form()

        val sent = browser.submit(form)

        val fields = fieldsOf(form)
        assertEquals(listOf("onetwo\uFFFDthreefour"), listOf("name", "tags").flatMap(fields::getValue).distinct())
        assertEquals(listOf("one\ntwo\uFFFDthree\nfour"), fields["description"])
        assertEquals(303 to listOf("{}"), sent.status to held.api.changes().map { it.second })
    }

    // Saving current settings into a plan

    /**
     * The form checks the services that the plan already names, without regard to letter case or surrounding
     * whitespace. Submitting it replaces what the plan holds with the current settings of the checked
     * services.
     */
    @Test
    fun savingCurrentSettingsReplacesWhatThePlanHolds() {
        val items = listOf(PlanItemDto(" customers ", "missing", ResponseModeDto.DYNAMIC, 250), PlanItemDto("Gone"))
        val held = holding(listOf(night.copy(items = items)))
        val browser = started.browserFor(held.api)

        val answer = browser.get("/plan/save?id=plan-night")
        val form = answer.form()
        val sent = browser.submit(form)
        val after = browser.follow(sent)
        browser.submit(form, rows = mapOf("service" to listOf("svc-orders", "svc-customers")))

        assertEquals("Save current settings into the plan Night", answer.page.select("main h1").text())
        assertEquals("post" to "/plan/save?id=plan-night", form.attr("method") to form.attr("action"))
        assertEquals(mapOf("service" to listOf("svc-customers")), fieldsOf(form))
        assertEquals(listOf("Customers", "Orders"), form.select("label").map { it.text() })
        assertEquals(listOf("Cancel" to "/plan?id=plan-night"), form.select(".actions a").map { it.text() to it.attr("href") })
        assertEquals(303 to "/plan?id=plan-night", sent.status to sent.header("location"))
        assertEquals("The plan Night now holds the current settings of 1 service.", after.notice)
        assertEquals(listOf(listOf("Customers", "found", "Static", "5 ms")), rows(after.page, "main table.items"))
        val rest = """"name":null,"description":null,"tags":null}"""
        val asked =
            listOf(
                "POST plans/plan-night/save" to """{"serviceIds":["svc-customers"],$rest""",
                "POST plans/plan-night/save" to """{"serviceIds":["svc-orders","svc-customers"],$rest""",
            )
        assertEquals(asked, held.api.changes())
        assertEquals(emptyList(), held.api.violations)
    }

    // Applying a plan

    /**
     * The scenario "Plan apply report": the UI says that the plan was applied and names the item that was
     * skipped. The report has a page of its own, so loading it again doesn't apply the plan again.
     */
    @Test
    fun applyingAPlanShowsItsReport() {
        val items = night.items + listOf(PlanItemDto("Gone"), PlanItemDto("Orders", "lost", ResponseModeDto.DYNAMIC, 40))
        val held = holding(listOf(night.copy(items = items)))
        val browser = started.browserFor(held.api)
        val page = browser.get("/plan?id=plan-night")

        val sent = browser.submit(page.form("main .actions form"))
        val report = browser.follow(sent)
        val again = browser.follow(sent)

        val shown = report.page
        assertEquals(303, sent.status)
        assertTrue(sent.header("location")!!.startsWith("/reports/"), "${sent.header("location")}")
        assertEquals(200 to "The plan Night was applied", report.status to shown.select("main h1").text())
        assertEquals("2 services now have the settings of the plan.", shown.select("main > p").first()!!.text())
        assertEquals(listOf("Skipped services", "Scenarios that were not found"), shown.select("main h2").map { it.text() })
        assertEquals(listOf("Gone", "Orders: lost"), shown.select("main li").map { it.text() })
        val links = mapOf("Back to the plan" to "/plan?id=plan-night", "Services" to "/services")
        assertEquals(links, shown.select("main p.actions a").associate { it.text() to it.attr("href") })
        assertEquals(shown.select("main").text(), again.page.select("main").text())
        assertEquals(listOf("POST plans/plan-night/apply" to ""), held.api.changes())
        val customers = held.services.getValue("svc-customers")
        assertEquals("sc-missing" to 250L, customers.defaultScenarioId to customers.delayMillis)
        assertEquals(ResponseModeDto.DYNAMIC to 40L, held.services.getValue("svc-orders").let { it.responseMode to it.delayMillis })
        val applied = browser.get("/plan?id=plan-night").page
        assertEquals("2026-03-04 05:06:07 UTC", applied.select("main dl dd").last()!!.text())
        assertEquals(emptyList(), held.api.violations)
    }

    /** The report says how many services got their settings, and it leaves out the sections that have nothing to list. */
    @Test
    fun theReportSaysHowManyServicesGotTheirSettings() {
        val gone = night.copy(id = "plan-gone", name = "Gone", items = listOf(PlanItemDto("Nobody")))
        val held = holding(listOf(night, alpha, gone))
        val browser = started.browserFor(held.api)

        val reports =
            listOf(night, alpha, gone).map { plan ->
                val sent = browser.submit(browser.get("/plan?id=${plan.id}").form("main .actions form"))
                browser.follow(sent).page
            }

        val said = listOf("1 service now has the settings of the plan.", "No service was changed.", "No service was changed.")
        assertEquals(said, reports.map { it.select("main > p").first()!!.text() })
        val sections = reports.map { page -> page.select("main h2").map { it.text() } }
        assertEquals(listOf(emptyList(), emptyList(), listOf("Skipped services")), sections)
    }

    // The default plan

    /** The button makes a plan the default plan, and it removes that designation again. */
    @Test
    fun theButtonMakesAPlanTheDefaultPlanAndRemovesThatAgain() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val press = { id: String ->
            val form = browser.get("/plan?id=$id").form("main .designation form")
            val sent = browser.submit(form)
            Triple(sent.status, sent.header("location"), browser.follow(sent).notice)
        }

        val results = listOf(press(night.id), press(night.id))

        val expected =
            listOf(
                Triple(303, "/plan?id=plan-night", "The plan Night is now the default plan."),
                Triple(303, "/plan?id=plan-night", "The plan Night is no longer the default plan."),
            )
        assertEquals<List<Any?>>(expected, results)
        assertEquals(listOf("PUT default-plan" to """{"planId":"plan-night"}""", "DELETE default-plan" to ""), held.api.changes())
        assertEquals(emptyList(), held.api.violations)
    }

    /** A button only removes the designation from the plan of its own page. A stale page doesn't take it away from another plan. */
    @Test
    fun aStalePageDoesNotRemoveTheDesignationOfAnotherPlan() {
        val held = holding(default = night.id)
        val browser = started.browserFor(held.api)
        val stale = browser.get("/plan?id=plan-night").form("main .designation form")
        browser.submit(browser.get("/plan?id=plan-alpha").form("main .designation form"))
        val before = held.api.changes()

        val notice = browser.follow(browser.submit(stale)).notice

        assertEquals("The plan Night was not the default plan. Nothing was changed.", notice)
        assertEquals(listOf("PUT default-plan" to """{"planId":"plan-alpha"}"""), before)
        assertEquals(before, held.api.changes())
    }

    // Deleting a plan

    /**
     * The requirement "Confirmation of destructive actions" for a plan: the link leads to a page that asks
     * for confirmation, nothing is deleted until the form on that page is submitted, and the list then
     * reports the deletion.
     */
    @Test
    fun theUiAsksForConfirmationBeforeItDeletesAPlan() {
        val held = holding(default = alpha.id)
        val browser = started.browserFor(held.api)

        val asked = browser.get("/plan/delete?id=plan-night")
        val ofDefault = browser.get("/plan/delete?id=plan-alpha").page
        val token = asked.form().select("[name=_token]").attr("value")
        val notConfirmed = browser.post("/plan/delete?id=plan-night", listOf("_token" to token))
        val before = held.api.changes()
        val sent = browser.submit(asked.form())
        val after = browser.follow(sent)

        val keeps = "Deleting a plan does not change any service."
        assertEquals(200 to "Delete the plan Night?", asked.status to asked.page.select("main h1").text())
        assertEquals(listOf(keeps), asked.page.select("main > p").map { it.text() })
        val ofDefaultSaid = listOf(keeps, "It is the default plan. The server will have no default plan.")
        assertEquals(ofDefaultSaid, ofDefault.select("main > p").map { it.text() })
        val button = asked.form().select("button").single()
        assertEquals("Delete the plan" to setOf("danger"), button.text() to button.classNames())
        assertEquals(listOf("Cancel" to "/plan?id=plan-night"), asked.page.select("main form a").map { it.text() to it.attr("href") })
        assertEquals(200 to "Delete the plan Night?", notConfirmed.status to notConfirmed.page.select("main h1").text())
        assertEquals(emptyList(), before)
        assertEquals(303 to "/plans", sent.status to sent.header("location"))
        assertEquals("The plan Night was deleted.", after.notice)
        assertEquals(listOf("alpha default"), rows(after.page, "main table.plans").map { it.first() })
        assertEquals(listOf("DELETE plans/plan-night" to ""), held.api.changes())
        assertEquals(emptyList(), held.api.violations)
    }

    // Addresses

    /** The pages of a plan that doesn't exist say so, and nothing changes. */
    @Test
    fun thePagesOfAMissingPlanSaySo() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val token = browser.token("/plan/new")
        val noPlan = "No plan has the identifier plan-gone."
        for (method in listOf("GET", "PATCH", "DELETE")) held.api.on(method, "plans/plan-gone") { problem(404, noPlan) }
        for (action in listOf("save", "apply")) held.api.on("POST", "plans/plan-gone/$action") { problem(404, noPlan) }
        val fields = listOf("_token" to token, "confirmed" to "yes", "designate" to "yes", "service" to "svc-orders")

        val paths = listOf("/plan", "/plan/edit", "/plan/save", "/plan/delete")
        val pages = paths.map { browser.get("$it?id=plan-gone") }
        val forms = (paths.drop(1) + listOf("/plan/apply", "/plan/default")).map { browser.post("$it?id=plan-gone", fields) }
        val incomplete = paths.map { browser.get(it).status }

        for (answer in pages + forms) {
            assertEquals(404 to "Not there", answer.status to answer.page.select("main h1").text())
            assertTrue(noPlan in answer.page.select("main .problem").text())
        }
        assertEquals(listOf(404, 404, 404, 404), incomplete)
        val lines = listOf("PATCH plans/plan-gone", "POST plans/plan-gone/save", "POST plans/plan-gone/apply")
        assertEquals(lines, held.api.changes().map { it.first })
    }

    /** The plan pages work with any identifier, because identifiers are passed as address parameters. */
    @Test
    fun thePlanPagesWorkWithAnyIdentifier() {
        val ids = mapOf("a/b c" to "a%2Fb%20c", ".." to "..", "x?y#z&w=1" to "x%3Fy%23z%26w%3D1")
        val plans = ids.keys.mapIndexed { index, id -> night.copy(id = id, name = "Plan $index") }
        val held = holding(plans, paths = ids)
        val browser = started.browserFor(held.api)

        for ((id, written) in ids) {
            val page = browser.get(Address.of("/plan", "id" to id))
            val links = page.page.select("main div.actions a").map { it.attr("href") }
            val edited = browser.submit(browser.get(links[0]).form(), mapOf("tags" to "x"))
            val saved = browser.submit(browser.get(links[1]).form())
            val applied = browser.submit(page.form("main .actions form"))
            val designated = browser.submit(page.form("main .designation form"))
            val deleted = browser.submit(browser.get(links[2]).form())

            val leading = page.page.select("main [href], main [action]").map { it.attr("href") + it.attr("action") }
            assertEquals(emptyList(), leading.filterNot(Hostile.SAFE_ADDRESS::matches), id)
            val answers = listOf(page, edited, saved, applied, designated, deleted)
            assertEquals(listOf(200, 303, 303, 303, 303, 303), answers.map { it.status }, id)
            assertEquals(Address.of("/plan", "id" to id), edited.header("location"), id)
            val ofPlan = "plans/$written"
            val lines = listOf("PATCH $ofPlan", "POST $ofPlan/save", "POST $ofPlan/apply", "PUT default-plan", "DELETE $ofPlan")
            val last = held.api.changes().takeLast(lines.size)
            assertEquals(lines, last.map { it.first }, id)
        }
        assertEquals(emptyList(), held.api.violations)
    }

    // Stored content is text

    /**
     * The requirement "Stored content is shown as text" for the plan pages: the pages show the name, the
     * description, the tags, and the settings of a plan as plain text, and so does the report of applying it.
     */
    @Test
    fun thePlanPagesShowStoredContentAsText() {
        val api = started.api()
        val browser = started.browserFor(api)

        for (text in Hostile.MARKUP + Hostile.ADDRESSES) {
            val items = listOf(PlanItemDto(text, text, ResponseModeDto.STATIC, 5))
            val plan = PlanDto(text, text, text, listOf(text), items, text)
            val paths = mapOf(text to Hostile.written(text))
            val services = api.hold(listOf(Samples.customers.copy(id = text, name = "$text!")), paths = paths)
            api.holdPlans(services, listOf(plan), default = text, paths = paths)

            val page = browser.get(Address.of("/plan", "id" to text))
            val list = browser.get("/plans")
            val edit = browser.get(Address.of("/plan/edit", "id" to text))
            val save = browser.get(Address.of("/plan/save", "id" to text))
            val asked = browser.get(Address.of("/plan/delete", "id" to text))
            val report = browser.follow(browser.submit(page.form("main .actions form")))

            assertEquals("Plan $text", page.wholeTextOf("main h1"), text)
            val item = page.page.select("main table.items tbody tr").first()!!
            assertEquals(listOf(text, text), item.select("th, td").take(2).map { it.wholeText() }, text)
            assertEquals(text, list.wholeTextOf("main table.plans tbody th a"), text)
            val fields = fieldsOf(edit.form())
            assertEquals(listOf(text.replace("\n", "")), listOf("name", "tags").flatMap(fields::getValue).distinct(), text)
            assertEquals(listOf(text), fields["description"], text)
            assertEquals("The plan $text was applied", report.wholeTextOf("main h1"), text)
            assertEquals(listOf(text), report.page.select("main li").map { it.wholeText() }, text)
            for (answer in listOf(page, list, edit, save, asked, report)) {
                assertEquals(200, answer.status, text)
                assertEquals(emptyList(), Hostile.moreThanText(answer), text)
            }
        }
        assertEquals(emptyList(), api.violations)
    }
}
