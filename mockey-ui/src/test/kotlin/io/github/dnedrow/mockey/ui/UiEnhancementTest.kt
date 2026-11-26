package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.FormElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 13.12: the requirement "Optional scripting enhancement", and what the requirement "Live history view"
 * asks of a browser that runs scripts.
 *
 * No test runs the script of the UI. A test sends the request that the script sends (see
 * [Browser.submitWithScript]), and it puts the answer into the page the way the script does (see [Swap]).
 * `docs/decisions/ui-stack.md` says how both were checked against the script in a browser.
 */
class UiEnhancementTest {
    private val started = Started()

    @AfterTest
    fun stopEverything() = started.close()

    /** A stand-in server that holds the samples, the live view of its services, and a browser of a UI for it. */
    private class Setup(
        val api: FakeAdminApi,
        val services: MutableMap<String, ServiceDto>,
        val browser: Browser,
    )

    private fun setup(): Setup {
        val api = started.api()
        val services = api.holdSamples()
        return Setup(api, services, started.browserFor(api))
    }

    /** Returns the elements that [answer] is made of, each as its name and its identifier. A part of a page is one element. */
    private fun partsOf(answer: Browser.Response): List<String> {
        val read = Jsoup.parse("<body><template>${answer.asRead}</template></body>").select("template > *")
        return read.map { "${it.tagName()}#${it.id()}" }
    }

    /**
     * Returns what the page of [answer] shows between its navigation and its foot, as HTML. The tokens of
     * the forms are left out, because every browser has its own.
     */
    private fun shownOf(answer: Browser.Response): String {
        val shown = answer.page.select("main").single()
        val main = shown.clone()
        main.select("input[name=_token]").forEach { it.attr("value", "") }
        return main.outerHtml()
    }

    /** Returns the headers of [answer] that tell the script where an answer goes, or which page to load. */
    private fun directionsOf(answer: Browser.Response): List<String?> =
        listOf("hx-retarget", "hx-reswap", "hx-redirect").map(answer::header)

    // The switches of the overview

    /**
     * Presses a switch of the overview in two browsers that show the same. The first one runs no script: it
     * sends the form and loads the page that the UI sends it to. In the second one, the script sends the
     * form and puts the answer into the page.
     *
     * The overview is the one at [address]. The switch is the form that [find] finds in the row [row], with
     * [edits] as what was chosen in it, and [press] finds the button that is pressed. Returns the page of
     * the second browser, after it's checked that:
     *
     * - The script sends the form to the address of the form.
     * - The answer is the row of the service alone, with what the page says of the switch.
     * - The second browser shows what the first one shows, and no other row changed.
     * - Both servers got the same request.
     */
    private fun switchedInPlace(
        address: String,
        row: String,
        find: String,
        edits: Map<String, String?> = emptyMap(),
        press: String = "button",
    ): Browser.Response {
        val plain = setup()
        val scripted = setup()

        val before = scripted.browser.get(address)
        val switch = before.form("#$row $find")
        val answer = scripted.browser.submitWithScript(switch, edits, pressed = switch.select(press).first())
        val after = Swap.shown(before.page, switch, answer)
        val plainSwitch = plain.browser.get(address).form("#$row $find")
        val sent = plain.browser.submit(plainSwitch, edits, pressed = plainSwitch.select(press).first())
        val reloaded = plain.browser.follow(sent)

        assertEquals(switch.attr("action"), switch.attr("hx-post"))
        assertEquals(303 to "$address#$row", sent.status to sent.header("location"))
        assertEquals(200 to "text/html; charset=UTF-8", answer.status to answer.header("content-type"))
        assertEquals(listOf("tr#$row", "div#notice"), partsOf(answer))
        assertEquals(listOf("closest tr", "outerHTML", null), directionsOf(answer))
        assertEquals("no-store", answer.header("cache-control"))
        assertEquals(shownOf(reloaded), shownOf(after))
        val others = { page: Browser.Response -> page.page.select("main tbody tr:not(#$row)").map { it.outerHtml() } }
        assertEquals(others(before), others(after))
        // What the page says of the switch is said once: the next page that the browser loads doesn't repeat it.
        assertEquals(null, scripted.browser.get(address).notice)
        assertEquals(plain.api.changes(), scripted.api.changes())
        assertEquals(1, scripted.api.changes().size)
        assertEquals(emptyList(), plain.api.violations + scripted.api.violations)
        return after
    }

    /**
     * The scenario "Mode switch updates in place": only the entry of the service is redrawn, and the server
     * is in the same state as after the full-page form submission.
     */
    @Test
    fun aModeSwitchRedrawsOnlyTheEntryOfItsService() {
        val after = switchedInPlace("/services", "service-svc-customers", "td.mode form", press = "button[value=proxy]")

        assertEquals("The service Customers is in proxy mode now.", after.notice)
        val rows = after.page.select("main tbody tr")
        assertEquals(listOf("service-svc-customers", "service-svc-orders"), rows.map { it.id() })
        assertEquals(listOf("proxy"), rows[0].select("td.mode button[aria-pressed=true]").map { it.attr("value") })
        assertEquals(listOf("1 diagnostic"), rows[0].select("a.marker").map { it.text() })
    }

    /**
     * The row that is redrawn is the row of its own service: it counts the diagnostics that concern that
     * service, and not the ones of another one.
     */
    @Test
    fun theRowThatIsRedrawnShowsTheDiagnosticsOfItsOwnService() {
        val after = switchedInPlace("/services", "service-svc-orders", "td.mode form", press = "button[value=static]")

        assertTrue(after.notice.orEmpty().startsWith("The service Orders is in static mode now."), "${after.notice}")
        val markers = after.page.select("main tbody tr").map { row -> row.id() to row.select("a.marker").map { it.text() } }
        assertEquals(listOf("service-svc-customers" to listOf("1 diagnostic"), "service-svc-orders" to emptyList()), markers)
    }

    /** The switch of the default scenario is redrawn in place the same way. */
    @Test
    fun aDefaultScenarioSwitchRedrawsOnlyTheEntryOfItsService() {
        val after = switchedInPlace("/services", "service-svc-customers", "td.default-scenario form", mapOf("scenario" to "sc-missing"))

        assertEquals("The default scenario of the service Customers is missing now.", after.notice)
        val row = after.page.getElementById("service-svc-customers")!!
        assertEquals(listOf("sc-missing"), row.select("td.default-scenario option[selected]").map { it.attr("value") })
    }

    /**
     * In an overview that is filtered by a tag, the switches of a row that is redrawn keep the filter in
     * their addresses, as the switches of the page do. So the next switch leads back to the same overview.
     */
    @Test
    fun theSwitchesOfARowThatIsRedrawnKeepTheFilterOfTheOverview() {
        val overview = "/services?tag=team-a"
        val ofMode = switchedInPlace(overview, "service-svc-customers", "td.mode form", press = "button[value=dynamic]")
        val ofScenario = switchedInPlace(overview, "service-svc-customers", "td.default-scenario form", mapOf("scenario" to "sc-missing"))

        val switches = listOf("/services/mode?id=svc-customers&tag=team-a", "/services/default-scenario?id=svc-customers&tag=team-a")
        for (after in listOf(ofMode, ofScenario)) {
            val forms = after.page.select("#service-svc-customers form")
            assertEquals(switches, forms.map { it.attr("action") })
            assertEquals(switches, forms.map { it.attr("hx-post") })
        }
    }

    /**
     * A switch that the server did is done, also when the server then fails to say what the row needs. The
     * UI doesn't answer with the page of a failure, which would say that nothing was changed. It sends the
     * browser to the overview, as without the script, and that page says what is the matter. The address
     * names no place in the page, because a browser that is sent to a place of the page that it shows
     * loads nothing.
     */
    @Test
    fun aSwitchWhoseRowCannotBeMadeSendsTheBrowserToTheOverview() {
        val setup = setup()
        val before = setup.browser.get("/services")
        val switch = before.form("#service-svc-customers td.mode form")
        setup.api.on("GET", "diagnostics") { problem(500, "The diagnostics can't be read.") }

        val answer = setup.browser.submitWithScript(switch, pressed = switch.select("button[value=proxy]").first())
        val loaded = setup.browser.get(answer.header("hx-redirect")!!)
        val plain = setup.browser.submit(switch, pressed = switch.select("button[value=dynamic]").first())

        assertEquals(200 to "", answer.status to answer.body)
        assertEquals(listOf(null, null, "/services"), directionsOf(answer))
        assertEquals(303 to "/services#service-svc-customers", plain.status to plain.header("location"))
        assertEquals(ResponseModeDto.DYNAMIC, setup.services.getValue("svc-customers").responseMode)
        assertEquals(listOf("proxy", "dynamic"), setup.api.changes().map { it.second.substringAfter(":\"").substringBefore('"') })
        assertEquals(502 to "The service Customers is in proxy mode now.", loaded.status to loaded.notice)
        assertTrue("Nothing was changed" !in loaded.body, loaded.body)
    }

    /**
     * The buttons and the choice of a switch have identifiers, which stay the same from one row to the row
     * that takes its place. By them, the script gives the focus back to the control that had it.
     */
    @Test
    fun theControlsOfASwitchKeepTheirIdentifiersInTheRowThatTakesThePlace() {
        val setup = setup()
        val before = setup.browser.get("/services")
        val switch = before.form("#service-svc-customers td.mode form")
        val controls = { page: Browser.Response -> page.page.select("#service-svc-customers form").select("button, select") }

        val answer = setup.browser.submitWithScript(switch, pressed = switch.select("button[value=proxy]").first())
        val after = Swap.shown(before.page, switch, answer)

        val ofModes = listOf("proxy", "static", "dynamic").map { "mode-$it-svc-customers" }
        val ofScenario = listOf("default-scenario-svc-customers", "set-default-scenario-svc-customers")
        assertEquals(ofModes + ofScenario, controls(before).map { it.id() })
        assertEquals(ofModes + ofScenario, controls(after).map { it.id() })
        val all = before.page.select("[id]").map { it.id() }
        assertEquals(all.distinct(), all)
    }

    /**
     * A switch that the UI or the server doesn't take is answered with a whole page, exactly as without the
     * script. The answer names no place, so the script shows the page in the place of the one that the
     * browser shows.
     */
    @Test
    fun aSwitchThatIsNotTakenIsAnsweredWithTheWholePageOfTheFailure() {
        val setup = setup()
        val before = setup.browser.get("/services")
        val switch = before.form("#service-svc-customers td.mode form")
        val unknown = listOf("_token" to switch.select("[name=_token]").attr("value"), "mode" to "sideways")

        val notNamed = setup.browser.post(switch.attr("hx-post"), unknown, Browser.SCRIPT_HEADERS)
        val plainNotNamed = setup.browser.post(switch.attr("action"), unknown)
        setup.services.remove("svc-customers")
        val gone = setup.browser.submitWithScript(switch)
        val plainGone = setup.browser.submit(switch)

        assertEquals(listOf(422, 404), listOf(notNamed, gone).map { it.status })
        for ((scripted, plain) in listOf(notNamed to plainNotNamed, gone to plainGone)) {
            assertEquals(plain.status to plain.body, scripted.status to scripted.body)
            assertEquals(listOf(null, null, null), directionsOf(scripted))
            val shown = Swap.shown(before.page, switch, scripted)
            assertEquals(plain.page.title() to plain.page.body().html(), shown.page.title() to shown.page.body().html())
        }
        val sent = setup.api.changes()
        assertEquals(1, sent.toSet().size)
    }

    // Validation feedback

    /**
     * A form that the UI doesn't take comes back as the part of the page between the navigation and the
     * foot, with the status 422. The part shows the form with its messages and with what was entered, as
     * the full page does, and the answer says that it goes in the place of that part.
     */
    @Test
    fun aFormThatIsNotTakenIsShownAgainInPlace() {
        val setup = setup()
        val page = setup.browser.get("/service/new")
        val form = page.formTo("/service/new")
        val entered = mapOf("name" to "Billing", "mockUrl" to "billing", "delay" to "later", "description" to "first\nsecond")

        val answer = setup.browser.submitWithScript(form, entered)
        val plain = setup.browser.submit(form, entered)
        val shown = Swap.shown(page.page, form, answer)

        assertEquals(form.attr("action"), form.attr("hx-post"))
        assertEquals(422 to listOf("main#"), answer.status to partsOf(answer))
        // A browser without the script gets the whole page, with nothing for the script.
        assertEquals("New service - Mockey" to listOf(null, null, null), plain.page.title() to directionsOf(plain))
        assertEquals(listOf(1, 0), listOf(plain, answer).map { it.page.select("body > header nav").size })
        assertEquals(listOf("main", "outerHTML show:window:top", null), directionsOf(answer))
        assertEquals("text/html; charset=UTF-8" to "no-store", answer.header("content-type") to answer.header("cache-control"))
        assertEquals(plain.status to shownOf(plain), answer.status to shownOf(shown))
        assertEquals(setOf("delay"), shown.fieldProblems.keys)
        val kept = listOf("name", "mockUrl", "delay").map { shown.page.select("main [name=$it]").attr("value") }
        assertEquals(listOf("Billing", "billing", "later"), kept)
        val description = shown.page.select("main [name=description]").single()
        assertEquals("first\nsecond", description.wholeText().removePrefix("\n"))
        // The message at the head of the form is one that a screen reader announces when it appears.
        assertEquals(1, shown.page.select("main form [role=alert]").size)
        assertEquals("New service - Mockey", shown.page.title())
        assertEquals(page.page.select("header").outerHtml(), shown.page.select("header").outerHtml())
        assertEquals(emptyList(), setup.api.changes())
    }

    /** What the server has against a form is shown in place the same way, at the field that it is about. */
    @Test
    fun aFormThatTheServerDoesNotTakeIsShownAgainInPlace() {
        val setup = setup()
        val taken = "A service with this mock URL exists already."
        setup.api.on("POST", "services") { problem(422, "The request is not valid.", listOf(FieldErrorDto("mockUrl", taken))) }
        val page = setup.browser.get("/service/new")
        val form = page.formTo("/service/new")
        val entered = mapOf("name" to "Billing", "mockUrl" to "customer/{id}")

        val answer = setup.browser.submitWithScript(form, entered)
        val plain = setup.browser.submit(form, entered)
        val shown = Swap.shown(page.page, form, answer)

        assertEquals(422 to listOf("main#"), answer.status to partsOf(answer))
        assertEquals(listOf("main", "outerHTML show:window:top", null), directionsOf(answer))
        assertEquals(shownOf(plain), shownOf(shown))
        assertEquals(mapOf("mockUrl" to listOf(taken)), shown.fieldProblems)
        val sent = setup.api.changes()
        assertEquals(2, sent.size)
        assertEquals(sent[0], sent[1])
    }

    /**
     * A form that the server did is answered with the address of the page that shows the result. The
     * script loads that page, as a browser does after the status 303, and the page says what the form did.
     */
    @Test
    fun aFormThatIsDoneSendsTheBrowserToThePageOfTheResult() {
        val plain = setup()
        val scripted = setup()
        val entered = mapOf("name" to "Billing", "mockUrl" to "billing")

        val answer = scripted.browser.submitWithScript(scripted.browser.get("/service/new").formTo("/service/new"), entered)
        val arrived = scripted.browser.get(answer.header("hx-redirect")!!)
        val redirect = plain.browser.submit(plain.browser.get("/service/new").formTo("/service/new"), entered)

        assertEquals(200 to "", answer.status to answer.body)
        assertEquals(303 to "", redirect.status to redirect.body)
        assertEquals(listOf(null, null, redirect.header("location")), directionsOf(answer))
        assertEquals(null to "no-store", answer.header("location") to answer.header("cache-control"))
        assertEquals(listOf(null, null, null), directionsOf(redirect))
        assertEquals("The service Billing was created.", arrived.notice)
        assertEquals(shownOf(plain.browser.follow(redirect)), shownOf(arrived))
        assertEquals(plain.api.changes(), scripted.api.changes())
        assertEquals(1, scripted.api.changes().size)
    }

    // The same state

    /**
     * An enhanced interaction leaves the server in the same state as its full-page equivalent. The walk
     * through the whole UI is taken twice: by a browser without scripting, and by one whose script sends
     * every form that is marked for it. Both servers get the same requests that change something, in the
     * same order, and both hold the same afterwards.
     */
    @Test
    fun aWalkWithTheScriptLeavesTheServerInTheSameStateAsAWalkWithoutIt() {
        val (plain, plainApi) = walked(withScript = false)
        val (scripted, scriptedApi) = walked(withScript = true)

        assertEquals(emptySet(), plain.sentByScript)
        assertEquals(SENT_BY_THE_SCRIPT, scripted.sentByScript)
        assertEquals(plain.submitted, scripted.submitted)
        // The address of a report holds an identifier that the UI draws at random.
        val pages = { journey: Journey -> journey.opened.map { if (it.startsWith("/reports/")) "/reports/*" else it }.toSet() }
        assertEquals(pages(plain), pages(scripted))
        assertEquals(plainApi.changes(), scriptedApi.changes())
        assertTrue(plainApi.changes().size >= scripted.submitted.count { it.startsWith("POST ") }, "${plainApi.changes().size}")
        assertEquals(heldBy(plainApi), heldBy(scriptedApi))
    }

    /** Takes the walk through the whole UI, with or without the script, and returns the walk and its stand-in server. */
    private fun walked(withScript: Boolean): Pair<Journey, FakeAdminApi> {
        val api = started.api()
        api.holdSamples()
        val journey = Journey(started.browserFor(api), api, withScript)
        journey.walkTheWholeUi()
        assertEquals(emptyList(), journey.failures)
        assertEquals(emptyList(), api.violations)
        return journey to api
    }

    /** Returns what [api] holds, as its answers to the requests that list something. */
    private fun heldBy(api: FakeAdminApi): List<Any?> =
        AdminClient(AdminUrl.of(api.url), null).use { client ->
            runBlocking {
                val definitions = listOf(client.listServices(), client.listPlans(), client.listTwists(), client.listTags())
                val designated = listOf(client.getDefaultPlan(), client.getUniversalErrorScenario())
                definitions + designated + listOf(client.getSystem(), client.getUpstreamProxy(), client.listHistory())
            }
        }

    /**
     * A field of several lines reaches the server the same way, whoever sends the form. A browser sends such
     * a field with a carriage return before every line feed, and the script sends it with line feeds alone.
     */
    @Test
    fun aFieldOfSeveralLinesReachesTheServerTheSameWayWithAndWithoutTheScript() {
        val plain = setup()
        val scripted = setup()
        val address = "/scenario/new?service=svc-customers"
        val entered = mapOf("name" to "lines", "body" to "first\nsecond\n\nfourth", "headers" to "X-One: 1\nX-Two: 2")

        val answer = scripted.browser.submitWithScript(scripted.browser.get(address).formTo("/scenario/new"), entered)
        val redirect = plain.browser.submit(plain.browser.get(address).formTo("/scenario/new"), entered)

        assertEquals(200 to 303, answer.status to redirect.status)
        assertEquals(redirect.header("location"), answer.header("hx-redirect"))
        assertEquals(plain.api.changes(), scripted.api.changes())
        val scenarios = scripted.services.getValue("svc-customers").scenarios
        val created = scenarios.single { it.name == "lines" }
        assertEquals("first\nsecond\n\nfourth", created.responseBody)
        assertEquals(listOf("X-One" to "1", "X-Two" to "2"), created.headers.map { it.name to it.value })
    }

    /**
     * A form with files reaches the server the same way, whoever sends it. For a file field without a
     * chosen file, a browser sends a file without a name and without content, and the script leaves the
     * field out. Both mean that no file was chosen.
     */
    @Test
    fun aFormWithFilesReachesTheServerTheSameWayWithAndWithoutTheScript() {
        val setup = setup()
        val page = setup.browser.get("/definitions")
        val form = page.formTo("/definitions/import")
        val tags = mapOf("tags" to "imported")
        val chosen = mapOf("document" to Browser.Chosen("definitions.json", """{"formatVersion":1}""".toByteArray()))

        val withoutFile = setup.browser.submitWithScript(form, tags)
        val plainWithoutFile = setup.browser.submit(form, tags)
        val withFile = setup.browser.submitWithScript(form, tags, chosen)
        val plainWithFile = setup.browser.submit(form, tags, chosen)

        val shown = Swap.shown(page.page, form, withoutFile)
        assertEquals(listOf(422, 422), listOf(withoutFile, plainWithoutFile).map { it.status })
        assertEquals(shownOf(plainWithoutFile), shownOf(shown))
        assertEquals(setOf("document"), shown.fieldProblems.keys)
        assertEquals(200 to 303, withFile.status to plainWithFile.status)
        val reports = listOf(withFile.header("hx-redirect"), plainWithFile.header("location")).map { it!!.substringBeforeLast('/') }
        assertEquals(listOf("/reports", "/reports"), reports)
        val imports = setup.api.changes()
        assertEquals(listOf("POST definitions/import?tag=imported" to """{"formatVersion":1}"""), imports.distinct())
        assertEquals(2, imports.size)
    }

    /**
     * What the tests send for the script is what the script was seen to send in a browser. In a form of
     * fields, a line break is a line feed alone, however the page or the person wrote it. A browser that
     * sends the form itself writes a carriage return before every line feed.
     */
    @Test
    fun theTestsWriteAFormOfFieldsTheWayTheScriptDoes() {
        val fields = listOf("name" to "a b", "body" to "first\r\nsecond\n\rfourth\rfifth")

        assertEquals("name=a+b&body=first%0Asecond%0A%0Afourth%0Afifth", Browser.writtenByScript(fields))
    }

    // The marks of the pages

    /**
     * An enhanced interaction uses the same address as its full-page equivalent: on every page of the walk,
     * a form that is marked for the script names the address of the form, and it says that it holds files
     * exactly when the form does. The forms that are marked are the ones that take what someone enters, and
     * the two switches of the overview.
     */
    @Test
    fun aFormThatIsMarkedForTheScriptNamesTheAddressOfTheForm() {
        val (journey, _) = walked(withScript = false)

        val forms = journey.pages.flatMap { it.page.select("form") }
        val marked = forms.filter { it.hasAttr("hx-post") }
        val elsewhere = marked.filter { it.attr("hx-post") != it.attr("action") || it.attr("method") != "post" }
        assertEquals(emptyList(), elsewhere.map { it.attr("action") })
        val holdsFiles = { form: Element -> form.attr("enctype") == "multipart/form-data" }
        val saysSo = { form: Element -> form.attr("hx-encoding") == "multipart/form-data" }
        assertEquals(emptyList(), marked.filter { holdsFiles(it) != saysSo(it) }.map { it.attr("action") })
        assertEquals(SENT_BY_THE_SCRIPT, marked.map { Journey.nameOf(it as FormElement) }.toSet())
        assertEquals(setOf("/definitions/import"), marked.filter(holdsFiles).map { it.attr("action") }.toSet())
        // A button that sends its form elsewhere would be sent to the address of the form by the script.
        assertEquals(emptyList(), forms.flatMap { it.select("[formaction], [formmethod], [formenctype]") })
        val forTheScript = journey.pages.flatMap { it.page.select("*") }.flatMap { element -> element.attributes().map { it.key } }
        assertEquals(setOf("hx-post", "hx-encoding"), forTheScript.filter { it.startsWith("hx-") }.toSet())
    }

    // The history

    /**
     * The scenario "New entry appears", for a browser that runs the script: with automatic refresh on, the
     * part of the page that depends on the history is marked for the script, which asks for the address of
     * the page every five seconds. The answer is that part alone, with the entry that the server recorded
     * in the meantime, and the page shows what a browser shows that loads it again.
     */
    @Test
    fun aNewEntryAppearsInTheListWithoutALoadOfThePage() {
        val api = started.api()
        val entries = api.holdHistory(listOf(Entries.of(1), Entries.of(2)), api.hold())
        val browser = started.browserFor(api)
        val address = "/history?tokens=customer&refresh=on"
        val marks = { part: Element -> part.attributes().filter { it.key.startsWith("hx-") }.associate { it.key to it.value } }
        val listed = { page: Browser.Response -> page.page.select("#entries tbody tr").map { it.id() } }

        val page = browser.get(address)
        val part = page.page.getElementById("entries")!!
        entries[3L] = Entries.of(3)
        val answer = browser.getWithScript(part.attr("hx-get"))
        val shown = Swap.shown(page.page, part, answer)
        val reloaded = browser.get(address)

        assertEquals(mapOf("hx-get" to address, "hx-trigger" to "every 5s"), marks(part))
        assertEquals(200 to listOf("div#entries"), answer.status to partsOf(answer))
        assertEquals(listOf("#entries", "outerHTML", null), directionsOf(answer))
        assertEquals("text/html; charset=UTF-8" to "no-store", answer.header("content-type") to answer.header("cache-control"))
        assertEquals(listOf("entry-2", "entry-1") to listOf("entry-3", "entry-2", "entry-1"), listed(page) to listed(shown))
        assertEquals(shownOf(reloaded), shownOf(shown))
        // The part that took the place is marked as the one before it, so the script goes on asking.
        assertEquals(marks(part), marks(shown.page.getElementById("entries")!!))
        assertEquals(emptyList(), api.changes())
        assertEquals(emptyList(), api.violations)
    }

    /**
     * The part that the script brings up to date holds everything of the page that depends on the history:
     * the link that deletes the entries is there as soon as there is an entry, and the line about the
     * service names the service as soon as an entry names it. The filter form stays out of the part, so
     * the script takes nothing away that someone types into it.
     */
    @Test
    fun thePartThatIsBroughtUpToDateHoldsEverythingThatDependsOnTheHistory() {
        val api = started.api()
        val entries = api.holdHistory(emptyList(), api.hold())
        val browser = started.browserFor(api)
        val address = "/history?service=svc-customers&refresh=on"
        val links = { page: Browser.Response -> page.page.select("#entries a").map { it.text() } }

        val empty = browser.get(address)
        val part = empty.page.getElementById("entries")!!
        entries[1L] = Entries.of(1)
        val shown = Swap.shown(empty.page, part, browser.getWithScript(address))

        val always = listOf("Show the entries of all services", "Statistics")
        val ofService = "Delete the entries of this service"
        assertEquals(always + listOf("Delete all entries", ofService, "Switch automatic refresh off"), links(empty))
        val allServices = "Show the entries of all services"
        assertEquals("Only the entries of one service are listed. $allServices", empty.page.select("#entries p.count").text())
        val ofTheEntry = listOf("Customers", "View")
        assertEquals(always + listOf("Delete all entries", ofService, "Switch automatic refresh off") + ofTheEntry, links(shown))
        assertEquals("Only the entries of the service Customers are listed. $allServices", shown.page.select("#entries p.count").text())
        assertEquals(shownOf(browser.get(address)), shownOf(shown))
        assertEquals(0, shown.page.select("#entries form[method=get], #entries input[type=text]").size)
        assertEquals(1, shown.page.select("main > form[method=get] input[name=tokens]").size)
    }

    /**
     * Every link and every button of the part that is brought up to date has an identifier of its own,
     * which stays the same from one answer to the next. By it, the script gives the focus back to the
     * control that had it, so someone who uses the keyboard isn't thrown out of the list every five seconds.
     */
    @Test
    fun theControlsOfThePartThatIsBroughtUpToDateKeepTheirIdentifiers() {
        val api = started.api()
        val many = (1L..120L).map { Entries.of(it) }
        api.holdHistory(many, api.hold())
        val browser = started.browserFor(api)

        val pages = listOf("/history?refresh=on", "/history?service=svc-customers&page=2&refresh=on", "/history?page=9&refresh=on")
        for (address in pages) {
            val first = browser.getWithScript(address).page
            val second = browser.getWithScript(address).page
            val controls = first.select("a, button")
            assertEquals(emptyList(), controls.filter { it.id().isEmpty() }.map { it.text() }, address)
            assertEquals(controls.map { it.id() }.distinct(), controls.map { it.id() }, address)
            assertEquals(controls.map { it.id() }, second.select("a, button").map { it.id() }, address)
            assertTrue(controls.isNotEmpty(), address)
        }
        val whole = browser.get("/history?refresh=on").page
        val identifiers = whole.select("[id]").map { it.id() }
        assertEquals(identifiers.distinct(), identifiers)
        val ofEntry = listOf("service-of-entry-120", "view-entry-120", "flag-entry-120", "delete-entry-120")
        assertEquals(ofEntry, whole.select("#entry-120 a, #entry-120 button").map { it.id() })
    }

    /**
     * Bringing the list up to date takes nothing from the page: what the UI says of a form that it has done
     * waits for the page that the browser loads next. A list without automatic refresh isn't marked, and it
     * answers the script with the whole page, as it answers everyone.
     */
    @Test
    fun bringingTheListUpToDateLeavesTheNoticeForThePageAndOnlyAListWithRefreshIsAPart() {
        val api = started.api()
        api.holdHistory(listOf(Entries.of(1), Entries.of(2)), api.hold())
        val browser = started.browserFor(api)

        val flagged = browser.submit(browser.get("/history?refresh=on").form("#entry-2 form[action^=/history/flag]"))
        val asked = browser.getWithScript("/history?refresh=on")
        val loaded = browser.follow(flagged)
        val off = browser.get("/history")
        val whole = browser.getWithScript("/history")

        assertEquals(303 to "/history?refresh=on", flagged.status to flagged.header("location"))
        assertEquals(listOf("div#entries"), partsOf(asked))
        assertTrue("is now flagged" !in asked.body, asked.body)
        assertEquals("The history entry 2 is now flagged.", loaded.notice)
        assertEquals(emptyList(), off.page.select("[hx-get], [hx-trigger]").map { it.id() })
        assertEquals(200 to "History - Mockey", whole.status to whole.page.title())
        assertEquals(listOf(null, null, null), directionsOf(whole))
        assertEquals(off.body, whole.body)
    }

    /**
     * A list that can't be brought up to date is answered with the whole page of the failure, which the
     * script shows in the place of the page. Nothing of that page asks again, as the page of a failure
     * doesn't load itself again in a browser without scripting.
     */
    @Test
    fun aListThatCannotBeBroughtUpToDateIsAnsweredWithTheWholePageOfTheFailure() {
        val api = started.api()
        api.holdHistory(listOf(Entries.of(1)), api.hold())
        val browser = started.browserFor(api)
        val page = browser.get("/history?refresh=on")
        val part = page.page.getElementById("entries")!!

        api.on("GET", "history") { problem(500, "The history can't be read.") }
        val answer = browser.getWithScript(part.attr("hx-get"))
        val plain = browser.get("/history?refresh=on")
        val shown = Swap.shown(page.page, part, answer)

        assertEquals(plain.status to plain.body, answer.status to answer.body)
        assertEquals(listOf(null, null, null), directionsOf(answer))
        assertEquals(plain.page.title() to plain.page.body().html(), shown.page.title() to shown.page.body().html())
        assertEquals(0, shown.page.select("[hx-get], [hx-trigger]").size)
        assertEquals(0, plain.page.select("meta[http-equiv=refresh]").size)
    }

    // The stylesheet

    /**
     * Every page has the notice element, also while it says nothing, so that the script has a place for a
     * notice. The stylesheet gives an empty one no room. Without that rule, every page would show an empty
     * box. The stylesheet also dims a form while the script waits for its answer. The script would do that
     * with a style of its own, which the content security policy forbids.
     */
    @Test
    fun theStylesheetGivesAnEmptyNoticeNoRoomAndDimsAFormThatWaits() {
        val setup = setup()

        val css = setup.browser.get("/assets/mockey.css").body
        val overview = setup.browser.get("/services").page
        val notices = overview.select("main > .notice")

        val ruleFor = { selector: String ->
            val body = Regex("(?m)^${Regex.escape(selector)} \\{([^}]*)\\}").find(css)?.groupValues?.get(1)
            body?.trim()?.lines()?.map { it.trim() }
        }
        assertEquals(listOf("margin: 0;", "padding: 0;", "border: 0;"), ruleFor(".notice:empty"))
        assertEquals(listOf("opacity: 0.6;"), ruleFor("form.htmx-request"))
        // An element is empty for a stylesheet when it holds nothing at all, not even a space.
        assertEquals(listOf("notice" to 0), notices.map { it.id() to it.childNodeSize() })
    }

    // What an answer to the script holds

    /**
     * Without the header of the script, no answer is a part of a page, and no answer has a header for the
     * script. So a browser without the script never gets an answer that only the script understands.
     */
    @Test
    fun aRequestWithoutTheHeaderOfTheScriptGetsNoAnswerForTheScript() {
        val (journey, _) = walked(withScript = false)

        val withHeaders = journey.pages.filter { answer -> answer.headers.keys.any { it.startsWith("hx-") } }
        val parts = journey.pages.filter { it.page.title().isEmpty() }
        assertEquals(emptyList(), (withHeaders + parts).map { it.page.location() })
        assertTrue(journey.pages.size > MANY_PAGES, "${journey.pages.size}")
        val setup = setup()
        val other = setup.browser.get("/history?refresh=on", mapOf("HX-Request" to "false"))
        assertEquals("History - Mockey" to listOf(null, null, null), other.page.title() to directionsOf(other))
    }

    /**
     * The requirement "Stored content is shown as text", for the answers to the script: whatever a server
     * states, a part of a page shows it as text, and so does the page that the part goes into. The server
     * of the test states one text wherever it states a text.
     */
    @Test
    fun whatTheScriptPutsIntoAPageShowsWhatAServerStatesAsText() {
        val api = started.api()
        val browser = started.browserFor(api)

        for (text in Hostile.MARKUP + Hostile.ADDRESSES) {
            api.holdHostile(text)
            val overview = browser.get("/services")
            val mode = overview.formTo("/services/mode")
            val history = browser.get("/history?refresh=on")
            val creation = browser.get("/service/new")
            val entered = mapOf("name" to text, "mockUrl" to text, "description" to text, "tags" to text, "delay" to text)

            val pressed = mode.select("button[value=static]").first()
            val switched = Swap.shown(overview.page, mode, browser.submitWithScript(mode, pressed = pressed))
            val chosen = switched.formTo("/services/default-scenario")
            val set = Swap.shown(switched.page, chosen, browser.submitWithScript(chosen, mapOf("scenario" to text)))
            val part = history.page.getElementById("entries")!!
            val listed = Swap.shown(history.page, part, browser.getWithScript(part.attr("hx-get")))
            val form = creation.formTo("/service/new")
            val again = Swap.shown(creation.page, form, browser.submitWithScript(form, entered))

            assertEquals(listOf(200, 200, 200, 422), listOf(switched, set, listed, again).map { it.status }, text)
            for (shown in listOf(switched, set, listed, again)) {
                assertEquals(emptyList(), Hostile.moreThanText(shown), text)
                assertEquals(null, shown.header("hx-redirect"), text)
            }
            val oneLine = text.filterNot { it == '\r' || it == '\n' }
            assertEquals(oneLine, again.page.select("main [name=name]").attr("value"), text)
            assertTrue(switched.notice.orEmpty().startsWith("The service "), text)
            assertTrue(text in listed.page.getElementById("entries")!!.wholeText(), text)
        }
        assertEquals(emptyList(), api.violations)
    }

    private companion object {
        /** A number of pages that the walk through the UI shows at least. */
        const val MANY_PAGES = 80

        /**
         * The forms that the script of the UI sends: every form that takes what someone enters, which the
         * UI can show again with its messages, and the two switches of the overview. Every other form is
         * a button, or a confirmation, and a browser sends it itself.
         */
        val SENT_BY_THE_SCRIPT =
            setOf(
                "POST /services/mode",
                "POST /services/default-scenario",
                "POST /service/new",
                "POST /service/edit",
                "POST /services/real-urls",
                "POST /scenario/new",
                "POST /scenario",
                "POST /plan/new",
                "POST /plan/edit",
                "POST /plan/save",
                "POST /twist/new",
                "POST /twist",
                "POST /tags/filter",
                "POST /history/replay",
                "POST /history/scenario",
                "POST /definitions/import",
                "POST /proxy",
            )
    }
}
