package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.DiagnosticDto
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.MatchCriterionDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.json
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import io.ktor.http.parseQueryString
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.FormElement
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 13.5: the overview of the services. It lists every service with what the requirement "Services
 * overview" names, it filters by the tags of its address, it marks a service that diagnostics concern, and
 * its switches give a service another response mode and another default scenario.
 *
 * The server is a stand-in that holds services as a server does, see [hold]. A test reads a page as a
 * browser without scripting does and presses its buttons, see [Browser].
 */
class UiServicesTest {
    private val fakes = mutableListOf<FakeAdminApi>()
    private val uis = mutableListOf<MockeyUi>()

    @AfterTest
    fun stopEverything() {
        uis.forEach(MockeyUi::close)
        fakes.forEach(FakeAdminApi::close)
    }

    /** Starts a stand-in for a server without telling it what it holds. */
    private fun fake(): FakeAdminApi = FakeAdminApi().also { fakes += it }

    /** Starts a stand-in for a server that holds [services] and reports [diagnostics], see [hold]. */
    private fun holding(
        services: List<ServiceDto> = listOf(Samples.customers, Samples.orders),
        diagnostics: List<DiagnosticDto> = emptyList(),
        paths: Map<String, String> = emptyMap(),
    ): FakeAdminApi = fake().also { it.hold(services, diagnostics, paths) }

    /** Starts a UI that manages [api]. */
    private fun uiFor(api: FakeAdminApi): MockeyUi {
        val settings = UiSettings(port = 0, adminUrl = AdminUrl.of(api.url), logLevel = LogLevel.WARN)
        return MockeyUi.start(settings).also { uis += it }
    }

    private fun browserFor(api: FakeAdminApi): Browser = Browser(uiFor(api))

    /** A row of the overview, as someone who reads the page takes it in. */
    private class Row(
        private val row: Element,
    ) {
        private val cells = row.select("td")
        private val head: Element = row.select("th").first()!!
        val name: String = head.ownText().trim()
        val marker: Element? = row.select("th a.marker").first()
        val url: String = cells[0].text()
        val setMode: List<String> = cells[1].select("button[aria-pressed=true]").map { it.text() }
        val note: String? = cells[1].select(".effective").first()?.text()
        val scenarios: List<String> = cells[2].select("option").map { it.text() }
        val defaultScenario: String = cells[2].select("option[selected]").first()?.text() ?: cells[2].text()
        val delay: String = cells[3].text()
        val tags: List<String> = cells[4].select(".tag").map { it.text() }
        val lastUsed: String = cells[5].text()

        /** Returns the form of the switch in the cell with the class [of]. */
        fun switch(of: String): FormElement = row.select("td.$of form").single() as FormElement

        /** Returns the button of the response mode [mode]. */
        fun button(mode: String): Element = row.select("td.mode button[value=$mode]").single()
    }

    private fun rows(page: Document): List<Row> = page.select("main table.services tbody tr").map(::Row)

    /** Returns the row of the service with the identifier [id]. */
    private fun row(
        page: Document,
        id: String,
    ): Row = Row(page.getElementById(Address.anchorOf("service", id))!!)

    /** Returns what the line says that counts the services, without its link. */
    private fun counted(page: Document): String {
        val line = page.select("main .count").first()!!
        return line.ownText().trim()
    }

    /** Returns where the links of the line that counts the services lead. */
    private fun leadingToAll(page: Document): List<String> = page.select("main .count a").map { it.attr("href") }

    private fun changing(api: FakeAdminApi): List<Pair<String, String>> =
        api.requests.filter { it.method != "GET" }.map { it.line to it.body }

    private fun scenario(
        id: String,
        name: String,
    ): ScenarioDto = ScenarioDto(id, name, "", 200, emptyList(), MatchCriterionDto.None)

    // What the overview lists

    /** The requirement "Services overview": each service is listed with everything that the requirement names. */
    @Test
    fun theOverviewListsEachServiceWithEverythingThatTheRequirementNames() {
        val customers = Samples.customers.copy(lastUsedTime = "2026-01-02T03:04:05.678901Z")
        val api = holding(listOf(customers, Samples.orders))
        val browser = browserFor(api)

        val answer = browser.get("/services")

        val page = answer.page
        val (ofCustomers, ofOrders) = rows(page)
        assertEquals(200, answer.status)
        assertEquals("Services - Mockey" to "Services", page.title() to page.select("main h1").text())
        val columns = listOf("Service", "Mock URL", "Response mode", "Default scenario", "Delay", "Tags", "Last used")
        assertEquals(columns, page.select("main table.services thead th").map { it.text() })
        assertEquals("Customers" to "http://mockey.test:8080/service/customer/{id}", ofCustomers.name to ofCustomers.url)
        assertEquals(listOf("Static") to null, ofCustomers.setMode to ofCustomers.note)
        assertEquals("found" to listOf("found", "missing"), ofCustomers.defaultScenario to ofCustomers.scenarios)
        val used = "2026-01-02 03:04:05 UTC"
        assertEquals(Triple("5 ms", listOf("team-a"), used), ofCustomers.let { Triple(it.delay, it.tags, it.lastUsed) })
        assertEquals(listOf(customers.lastUsedTime), page.select("main table.services time").map { it.attr("datetime") })
        assertEquals("Orders" to "http://mockey.test:8080/service/orders", ofOrders.name to ofOrders.url)
        assertEquals(listOf("Proxy") to null, ofOrders.setMode to ofOrders.note)
        assertEquals("none" to emptyList(), ofOrders.defaultScenario to ofOrders.scenarios)
        assertEquals(Triple("0 ms", emptyList(), "never"), ofOrders.let { Triple(it.delay, it.tags, it.lastUsed) })
        assertEquals(listOf(null, null), listOf(ofCustomers.marker, ofOrders.marker))
        assertEquals("2 services", page.select("main .count").text())
        assertEquals(listOf("/services"), page.select("nav a[aria-current=page]").map { it.attr("href") })
        assertEquals(listOf("GET system", "GET services", "GET diagnostics"), api.requests.map { it.line })
        assertEquals(emptyList(), api.violations)
    }

    /** The services are listed by their names, and the scenarios of a switch by theirs, whatever the letter case is. */
    @Test
    fun theServicesAndTheScenariosOfASwitchAreListedByTheirNames() {
        val scenarios = listOf(scenario("s1", "zulu"), scenario("s2", "Bravo"), scenario("s3", "alpha"), scenario("s0", "bravo"))
        val named = { id: String, name: String -> ServiceDto(id, name, id) }
        val beta = named("1", "beta").copy(scenarios = scenarios, defaultScenarioId = "s2")
        val api = holding(listOf(beta, named("2", "Zeta"), named("4", "alpha"), named("3", "Alpha"), named("5", "\u00e4rger")))

        val listed = rows(browserFor(api).get("/services").page)

        assertEquals(listOf("Alpha", "alpha", "beta", "Zeta", "\u00e4rger"), listed.map { it.name })
        assertEquals(listOf("alpha", "bravo", "Bravo", "zulu") to "Bravo", listed[2].scenarios to listed[2].defaultScenario)
    }

    /**
     * The requirement "Callable mock URL": the overview shows the whole URL at which a client calls a
     * service, made of the URL of the mock endpoint that the server reports. The URL is text, and no link.
     */
    @Test
    fun theOverviewShowsTheUrlAtWhichAClientCallsAService() {
        val patterns = listOf("customer/{id}", "/orders/", "  padded/{a}/{b} ", "http://api.example.com/v1/customers", "")
        val api = holding(patterns.mapIndexed { index, pattern -> ServiceDto("svc-$index", "Service $index", pattern) })
        val browser = browserFor(api)

        val shown = rows(browser.get("/services").page).map { it.url }
        api.system = FakeAdminApi.SYSTEM.copy(mockBaseUrl = "https://mocks.example.com/base/")
        val page = browser.get("/services").page

        val base = "http://mockey.test:8080/service/"
        val ends = listOf("customer/{id}", "orders/", "padded/{a}/{b}", "http://api.example.com/v1/customers", "")
        assertEquals(ends.map { base + it }, shown)
        assertEquals(ends.map { "https://mocks.example.com/base/service/$it" }, rows(page).map { it.url })
        assertEquals(patterns.size to 0, page.select("main td code.url").size to page.select("main td code.url a, main td:eq(1) a").size)
    }

    /** A time is shown to the second and in UTC, with the time as the server states it, and a text that is no time as it is. */
    @Test
    fun theOverviewShowsWhenAServiceWasLastUsed() {
        val times = listOf("2026-11-25T19:43:33Z", "2026-11-25T20:43:33.999999+01:00", "2026-01-02T03:04:05.678Z", "yesterday", null)
        val api = holding(times.mapIndexed { index, time -> ServiceDto("svc-$index", "Service $index", "s$index", lastUsedTime = time) })

        val page = browserFor(api).get("/services").page

        val expected = listOf("2026-11-25 19:43:33 UTC", "2026-11-25 19:43:33 UTC", "2026-01-02 03:04:05 UTC", "yesterday", "never")
        assertEquals(expected, rows(page).map { it.lastUsed })
        assertEquals(times.take(3), page.select("main table.services time").map { it.attr("datetime") })
        assertTrue("The server keeps these times while it runs" in page.select("main > .hint").text())
    }

    /** A server without a service, and a filter that no service passes, get a page that says so and has no list. */
    @Test
    fun anOverviewWithoutAServiceSaysSo() {
        val browser = browserFor(holding(emptyList()))
        val filtering = browserFor(holding())

        val empty = browser.get("/services").page
        val none = filtering.get("/services?tag=nightly").page
        val neither = filtering.get("/services?tag=nightly&tag=weekly").page

        val one = browserFor(holding(listOf(Samples.orders))).get("/services").page
        val said = listOf(empty, none, neither).map(::counted)
        assertEquals("1 service" to 1, counted(one) to rows(one).size)
        val ofTags = listOf("No service carries the tag nightly.", "No service carries one of the tags nightly, weekly.")
        assertEquals(listOf("The server holds no service.") + ofTags, said)
        assertEquals(listOf(0, 0, 0), listOf(empty, none, neither).map { it.select("main table, main > .hint").size })
        assertEquals(listOf(emptyList(), listOf("/services"), listOf("/services")), listOf(empty, none, neither).map(::leadingToAll))
    }

    // The mode in which a service answers

    /**
     * The overview lists the effective response mode: a service that does not answer in the mode that it is
     * set to says in which mode it answers, beside the mode that it is set to.
     */
    @Test
    fun aServiceThatDoesNotAnswerInTheModeThatItIsSetToSaysSo() {
        val waiting = Samples.orders.copy(responseMode = ResponseModeDto.STATIC, effectiveResponseMode = ResponseModeDto.PROXY)
        val held = Samples.customers.copy(id = "svc-held", name = "Held", mockUrl = "held", effectiveResponseMode = ResponseModeDto.PROXY)
        val api = holding(listOf(Samples.customers, held, waiting))

        val (ofCustomers, ofHeld, ofOrders) = rows(browserFor(api).get("/services").page)

        assertEquals(listOf("Static") to null, ofCustomers.setMode to ofCustomers.note)
        assertEquals(listOf("Static") to "Answers as in proxy mode for now.", ofHeld.setMode to ofHeld.note)
        val because = "Answers as in proxy mode for now, because it has no scenario to answer with."
        assertEquals(listOf("Static") to because, ofOrders.setMode to ofOrders.note)
    }

    // The diagnostics

    /**
     * The scenario "Diagnostics marker": the entry of a service that diagnostics concern shows a marker, and
     * the marker leads to where the page of the diagnostics shows them.
     */
    @Test
    fun aServiceThatDiagnosticsConcernHasAMarkerThatLeadsToThem() {
        val unknown = DiagnosticDto("unknownInspector", "svc-customers", "Customers", "The inspector 'Audit' is not installed.")
        val sameUrl = "The service 'Store' has the same mock URL pattern 'shop'."
        val shared = DiagnosticDto("sharedMockUrl", "svc-shop", "Shop", sameUrl, "svc-store")
        val shop = ServiceDto("svc-shop", "Shop", "shop")
        val api = holding(listOf(Samples.customers, Samples.orders, shop), listOf(shared, Samples.diagnostic, unknown))
        val browser = browserFor(api)

        val overview = browser.get("/services").page
        val markers = rows(overview).associate { it.name to it.marker?.let { marker -> marker.text() to marker.attr("href") } }
        val answer = browser.get(markers.getValue("Customers")!!.second.substringBefore('#'))

        val expected =
            mapOf(
                "Customers" to ("2 diagnostics" to "/diagnostics#service-svc-customers"),
                "Orders" to null,
                "Shop" to ("1 diagnostic" to "/diagnostics#service-svc-shop"),
            )
        assertEquals(expected, markers)
        val page = answer.page
        val ofCustomers = page.getElementById("service-svc-customers")!!
        assertEquals(200 to "Diagnostics", answer.status to page.select("main h1").text())
        assertEquals("Customers", ofCustomers.select("h2").text())
        val found =
            listOf(
                "Duplicate scenarios: The scenarios 'found' and 'missing' answer alike.",
                "Unknown inspector: The inspector 'Audit' is not installed.",
            )
        assertEquals(found, ofCustomers.select("li").map { it.text() })
        assertEquals(listOf("Customers", "Shop"), page.select("main section h2").map { it.text() })
        assertEquals(listOf("Shared mock URL"), page.getElementById("service-svc-shop")!!.select(".kind").map { it.text() })
        assertEquals(listOf("/services"), page.select("nav a[aria-current=page]").map { it.attr("href") })
        assertEquals(listOf("GET system", "GET diagnostics"), api.requests.takeLast(2).map { it.line })
        assertEquals(emptyList(), api.violations)
    }

    /** The page of the diagnostics names every kind that the admin API has, and shows another kind by its name. An empty page says so. */
    @Test
    fun thePageOfTheDiagnosticsNamesEveryKindAndSaysWhenThereIsNothing() {
        val ofUrls = listOf("sharedMockUrl", "sharedRealUrl")
        val kinds = ofUrls + listOf("duplicateScenarios", "unknownInspector", "unreadableRuleSet", "schemaViolation", "ofALaterServer")
        val diagnostics = kinds.map { DiagnosticDto(it, "svc-1", "One", "About $it.") }
        val nothing = browserFor(holding(emptyList()))
        val everything = browserFor(holding(emptyList(), diagnostics))

        val empty = nothing.get("/diagnostics").page
        val full = everything.get("/diagnostics").page

        assertEquals(1 to "The server found nothing.", empty.select("main p.count").size to empty.select("main p.count").text())
        assertEquals(0, empty.select("main section").size)
        val names =
            listOf("Shared mock URL", "Shared real URL", "Duplicate scenarios", "Unknown inspector")
                .plus(listOf("Unreadable rule set", "Schema violation", "ofALaterServer"))
        assertEquals(names, full.select("main section .kind").map { it.text() })
        assertEquals(0, full.select("main p.count").size)
    }

    /** The page of the diagnostics keeps two services with one name apart, as the overview does: each has a place of its own. */
    @Test
    fun theDiagnosticsOfTwoServicesWithOneNameStayApart() {
        val twins = listOf("svc-t2", "svc-t1").map { ServiceDto(it, "Twin", it) }
        val diagnostics = twins.map { DiagnosticDto("unknownInspector", it.id, it.name, "The inspector of ${it.id} is not installed.") }
        val browser = browserFor(holding(twins, diagnostics))

        val overview = rows(browser.get("/services").page)
        val sections = browser.get("/diagnostics").page.select("main section")

        val places = listOf("/diagnostics#service-svc-t1", "/diagnostics#service-svc-t2")
        assertEquals(places, overview.map { it.marker!!.attr("href") })
        val said = listOf("svc-t1", "svc-t2").map { "service-$it" to listOf("Unknown inspector: The inspector of $it is not installed.") }
        assertEquals(said, sections.map { section -> section.id() to section.select("li").map { it.text() } })
    }

    // The filter

    /**
     * The requirement "View state lives in the address", with the scenario "Shared link": the overview lists
     * the services that carry one of the tags of its address, and the same address shows the same list in
     * another browser.
     */
    @Test
    fun theOverviewListsTheServicesThatCarryOneOfTheTagsOfItsAddress() {
        val api = holding(listOf(Samples.customers, Samples.orders.copy(tags = listOf("nightly")), ServiceDto("svc-3", "Shop", "shop")))
        val ui = uiFor(api)
        val address = "/services?tag=team-a&tag=nightly"

        val pages = listOf(Browser(ui), Browser(ui)).map { it.get(address).page }
        val ofOneTag = Browser(ui).get("/services?tag=nightly").page

        for (page in pages) {
            assertEquals(listOf("Customers", "Orders"), rows(page).map { it.name })
            assertEquals("team-a nightly", page.select("main form.filter [name=tag]").attr("value"))
            assertEquals("2 services carry one of the tags team-a, nightly.", counted(page))
            assertEquals(listOf("/services"), page.select("main .count a").map { it.attr("href") })
        }
        assertEquals(listOf("Orders"), rows(ofOneTag).map { it.name })
        assertEquals("1 service carries the tag nightly.", counted(ofOneTag))
        val asked = api.requests.map { it.line }.filter { it.startsWith("GET services") }
        val ofBoth = "GET services?tag=team-a&tag=nightly"
        assertEquals(listOf(ofBoth, ofBoth, "GET services?tag=nightly"), asked)
        assertEquals(emptyList(), api.violations)
    }

    /**
     * The form of the filter leads to the address of the list that it asks for, and so does the tag of a
     * service. Tags are taken in lower case and each once, as a server holds them, and an empty filter is none.
     */
    @Test
    fun theFormOfTheFilterAndTheTagOfAServiceLeadToTheAddressOfAFilteredList() {
        val api = holding()
        val browser = browserFor(api)

        val all = browser.get("/services")
        val form = all.form("main form.filter")
        val filtered = browser.submit(form, mapOf("tag" to " Nightly  TEAM-A\tnightly "))
        val unfiltered = browser.submit(filtered.form("main form.filter"), mapOf("tag" to "  "))

        assertEquals("get" to "/services", form.attr("method") to form.attr("action"))
        assertEquals(listOf("tag"), form.select("input, select, textarea").map { it.attr("name") })
        assertEquals(listOf(""), all.page.select("main form.filter [name=tag]").map { it.attr("value") })
        assertEquals(0, all.page.select("main .count a").size)
        assertEquals(listOf("/services?tag=team-a"), all.page.select("main td a.tag").map { it.attr("href") })
        assertEquals(200 to "nightly team-a", filtered.status to filtered.page.select("main form.filter [name=tag]").attr("value"))
        assertEquals(listOf("Customers"), rows(filtered.page).map { it.name })
        assertEquals(listOf("Customers", "Orders"), rows(unfiltered.page).map { it.name })
        val asked = api.requests.map { it.line }.filter { it.startsWith("GET services") }
        assertEquals(listOf("GET services", "GET services?tag=nightly&tag=team-a", "GET services"), asked)
        assertEquals(emptyList(), api.violations)
    }

    // The switches

    /**
     * A user switches the response mode of a service from the overview: the server is asked to change the
     * mode and nothing else, and the browser is sent back to the overview as it was, at the service, where
     * the page says that the mode was switched. Loading that page again switches nothing again.
     */
    @Test
    fun aButtonOfTheOverviewSwitchesTheResponseModeOfAService() {
        val api = holding()
        val browser = browserFor(api)

        val before = row(browser.get("/services?tag=team-a").page, "svc-customers")
        val sent = browser.submit(before.switch("mode"), pressed = before.button("dynamic"))
        val shown = browser.follow(sent)
        val again = browser.follow(sent)

        assertEquals(listOf("Proxy", "Static", "Dynamic"), before.switch("mode").select("button").map { it.text() })
        assertEquals("Response mode of Customers", before.switch("mode").select("[role=group]").attr("aria-label"))
        assertEquals(Triple(303, "/services?tag=team-a#service-svc-customers", ""), Triple(sent.status, sent.header("location"), sent.body))
        assertEquals(200 to "The service Customers is in dynamic mode now.", shown.status to shown.notice)
        assertEquals(listOf("Dynamic"), row(shown.page, "svc-customers").setMode)
        assertEquals(null to listOf("Dynamic"), again.notice to row(again.page, "svc-customers").setMode)
        assertEquals(listOf("PATCH services/svc-customers" to """{"responseMode":"dynamic"}"""), changing(api))
        assertEquals(emptyList(), api.violations)
    }

    /** Each button of the switch asks for its mode, also the one of the mode that the service is set to. */
    @Test
    fun eachButtonOfTheSwitchAsksForItsMode() {
        val api = holding()
        val browser = browserFor(api)

        val notices =
            listOf("proxy", "static", "dynamic").map { mode ->
                val shown = row(browser.get("/services").page, "svc-orders")
                browser.follow(browser.submit(shown.switch("mode"), pressed = shown.button(mode))).notice
            }

        val because = "It answers as in proxy mode for now, because it has no scenario to answer with."
        val expected =
            listOf(
                "The service Orders is in proxy mode now.",
                "The service Orders is in static mode now. $because",
                "The service Orders is in dynamic mode now. $because",
            )
        assertEquals(expected, notices)
        val asked = listOf("proxy", "static", "dynamic").map { "PATCH services/svc-orders" to """{"responseMode":"$it"}""" }
        assertEquals(asked, changing(api))
        assertEquals(emptyList(), api.violations)
    }

    /** A user switches the default scenario of a service from the overview, as its response mode. */
    @Test
    fun theChoiceOfTheOverviewSwitchesTheDefaultScenarioOfAService() {
        val api = holding()
        val browser = browserFor(api)

        val before = row(browser.get("/services").page, "svc-customers")
        val switch = before.switch("default-scenario")
        val sent = browser.submit(switch, mapOf("scenario" to "sc-missing"))
        val shown = browser.follow(sent)

        assertEquals("Default scenario of Customers", switch.select("select").attr("aria-label"))
        val buttons = switch.select("button").map { it.text() to it.attr("aria-label") }
        assertEquals(listOf("Set" to "Set the default scenario of Customers"), buttons)
        assertEquals(listOf("sc-found", "sc-missing"), switch.select("option").map { it.attr("value") })
        assertEquals(303 to "/services#service-svc-customers", sent.status to sent.header("location"))
        assertEquals("The default scenario of the service Customers is missing now.", shown.notice)
        assertEquals("missing", row(shown.page, "svc-customers").defaultScenario)
        assertEquals(listOf("PATCH services/svc-customers" to """{"defaultScenarioId":"sc-missing"}"""), changing(api))
        assertEquals(emptyList(), api.violations)
    }

    /** What the page says of a switch repeats the start of a long name, so that it stays a sentence that the browser keeps whole. */
    @Test
    fun whatThePageSaysOfASwitchRepeatsTheStartOfALongName() {
        val long = "A name that goes on and on. ".repeat(REPEATED).trim()
        val scenarios = listOf(scenario("s1", long), scenario("s2", "short"))
        val api = holding(listOf(ServiceDto("svc-long", long, "long", scenarios = scenarios, defaultScenarioId = "s2")))
        val browser = browserFor(api)

        val shown = row(browser.get("/services").page, "svc-long")
        val ofMode = browser.follow(browser.submit(shown.switch("mode"), pressed = shown.button("static")))
        val ofScenario = browser.follow(browser.submit(shown.switch("default-scenario"), mapOf("scenario" to "s1")))

        val start = long.take(BRIEF - 1) + "\u2026"
        assertEquals("The service $start is in static mode now.", ofMode.notice)
        assertEquals("The default scenario of the service $start is $start now.", ofScenario.notice)
        assertEquals(long to long, row(ofScenario.page, "svc-long").let { it.name to it.defaultScenario })
        assertEquals("The service short is in proxy mode now.", ServicesPages.noticeOfMode(ServiceDto("s", "short", "s")))
        assertEquals("x".repeat(BRIEF), briefly("x".repeat(BRIEF)))
    }

    /**
     * A service with one scenario has nothing to switch to, and one without a scenario has no default one. A
     * default scenario that is none of the scenarios of the service is not shown as one that is.
     */
    @Test
    fun theDefaultScenarioIsASwitchOnlyWhereThereIsAChoice() {
        val one = Samples.customers.copy(id = "svc-one", name = "One", scenarios = listOf(Samples.found))
        val astray = Samples.customers.copy(id = "svc-astray", name = "Astray", defaultScenarioId = "sc-gone")
        val alone = one.copy(id = "svc-alone", name = "Alone astray", defaultScenarioId = "sc-gone")
        val api = holding(listOf(one, astray, alone, Samples.orders))
        val browser = browserFor(api)

        val page = browser.get("/services").page
        val (ofAlone, ofAstray, ofOne, ofOrders) = rows(page)
        val sent = browser.submit(ofAstray.switch("default-scenario"), mapOf("scenario" to null))

        assertEquals(listOf("found", "none"), listOf(ofOne.defaultScenario, ofOrders.defaultScenario))
        assertEquals(0, page.select("#service-svc-one td.default-scenario form, #service-svc-orders td.default-scenario form").size)
        assertEquals("none" to listOf("none", "found", "missing"), ofAstray.defaultScenario to ofAstray.scenarios)
        assertEquals("none" to listOf("none", "found"), ofAlone.defaultScenario to ofAlone.scenarios)
        val placeholder = ofAstray.switch("default-scenario").select("option").first()!!
        assertEquals("" to listOf(true, true), placeholder.attr("value") to listOf("disabled", "selected").map(placeholder::hasAttr))
        assertEquals(422 to "The form names no scenario.", sent.status to sent.page.select("main .problem").text())
        assertEquals(emptyList(), changing(api))
    }

    /** A switch works for a service with any identifier: the identifier is a parameter of the address of the form. */
    @Test
    fun aSwitchNamesItsServiceInTheAddressOfItsFormWhateverTheIdentifierIs() {
        val ids = mapOf("a/b c" to "a%2Fb%20c", ".." to "..", "x?y#z&w=1" to "x%3Fy%23z%26w%3D1")
        val services = ids.keys.mapIndexed { index, id -> Samples.customers.copy(id = id, name = "Service $index") }
        val api = holding(services, paths = ids)
        val browser = browserFor(api)

        val answers =
            ids.keys.map { id ->
                val shown = row(browser.get("/services").page, id)
                val ofMode = browser.submit(shown.switch("mode"), pressed = shown.button("proxy"))
                listOf(ofMode, browser.submit(shown.switch("default-scenario")))
            }

        val forms = browser.get("/services").page.select("main td form")
        val actions = forms.map { it.attr("action") }
        assertEquals(emptyList(), actions.filterNot(Hostile.SAFE_ADDRESS::matches))
        assertEquals(ids.keys.flatMap { listOf(it, it) }, actions.map { parseQueryString(it.substringAfter('?'))["id"] })
        val back = listOf("/services#service-a.2Fb.20c", "/services#service-..", "/services#service-x.3Fy.23z.26w.3D1")
        assertEquals(back.flatMap { listOf(303 to it, 303 to it) }, answers.flatten().map { it.status to it.header("location") })
        val asked = ids.values.flatMap { listOf("PATCH services/$it" to """{"responseMode":"proxy"}""", "PATCH services/$it" to FOUND) }
        assertEquals(asked, changing(api))
        assertEquals(emptyList(), api.violations)
    }

    /**
     * The requirement "Request forgery protection" for the switches: each is a form with the method POST
     * that holds the token of the page, and a form without it switches nothing.
     */
    @Test
    fun aSwitchIsAFormOfThePageAndAFormOfAnotherPageSwitchesNothing() {
        val api = holding()
        val browser = browserFor(api)

        val page = browser.get("/services").page
        val forms = page.select("main td form")
        val token = forms.first()!!.select("[name=_token]").attr("value")
        val forged = Browser(uis.single()).post("/services/mode?id=svc-customers", listOf("_token" to token, "mode" to "proxy"))
        val without = browser.post("/services/mode?id=svc-customers", listOf("mode" to "proxy"))

        assertEquals(listOf("post", "post", "post"), forms.map { it.attr("method") })
        assertEquals(listOf(token, token, token), forms.map { it.select("[name=_token]").attr("value") })
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(token), token)
        assertEquals(listOf(403, 403), listOf(forged.status, without.status))
        assertEquals(emptyList(), changing(api))
    }

    /** A form that names no mode that there is, or no service, switches nothing, and the server is asked nothing for it. */
    @Test
    fun aFormThatNamesNoModeOrNoServiceSwitchesNothing() {
        val api = holding()
        val browser = browserFor(api)
        val tokens = browser.get("/services").page.select("main td.mode [name=_token]")
        val token = tokens.first()!!.attr("value")
        val asked = api.requests.size

        val ofCustomers = "/services/mode?id=svc-customers"
        val noMode = listOf("turbo", "", "STATIC", "proxy ").map { browser.post(ofCustomers, listOf("_token" to token, "mode" to it)) }
        val notSent = browser.post(ofCustomers, listOf("_token" to token))
        val ofNone = listOf("/services/mode", "/services/default-scenario")
        val noService = ofNone.map { browser.post(it, listOf("_token" to token, "mode" to "proxy", "scenario" to "sc-found")) }

        for (answer in noMode + notSent) {
            assertEquals(422 to "The form was not taken", answer.status to answer.page.select("main h1").text())
            assertEquals(listOf("The form names no response mode.", "Nothing was changed."), answer.page.select("main p").map { it.text() })
            assertEquals(listOf("/services"), answer.page.select("nav a[aria-current=page]").map { it.attr("href") })
        }
        assertEquals(listOf(404, 404), noService.map { it.status })
        assertEquals(asked, api.requests.size)
    }

    /** What the server has against a switch is shown as the server says it, and the page says that nothing was changed. */
    @Test
    fun aSwitchThatTheServerRefusesSaysWhatTheServerSays() {
        val api = holding()
        val browser = browserFor(api)
        val shown = row(browser.get("/services").page, "svc-customers")
        val gone = FieldErrorDto("defaultScenarioId", "The service has no such scenario.")

        api.on("PATCH", "services/svc-customers") { problem(404, "There is no service 'svc-customers'.") }
        val missing = browser.submit(shown.switch("mode"), pressed = shown.button("proxy"))
        api.on("PATCH", "services/svc-customers") { problem(422, "The request is not valid.", listOf(gone)) }
        val invalid = browser.submit(shown.switch("default-scenario"), mapOf("scenario" to "sc-missing"))

        assertEquals(404 to "Not there", missing.status to missing.page.select("main h1").text())
        val ofMissing = listOf("There is no service 'svc-customers'.", "Nothing was changed.")
        assertEquals(ofMissing, missing.page.select("main .problem").map { it.text() })
        assertEquals(502 to "The server refused the request", invalid.status to invalid.page.select("main h1").text())
        val said = listOf("The request is not valid.", "defaultScenarioId: The service has no such scenario.", "Nothing was changed.")
        assertEquals(said, invalid.page.select("main .problem").map { it.text() })
    }

    /** A page that the server gives only a part of shows nothing of the server, and says what is the matter. */
    @Test
    fun anOverviewThatTheServerGivesOnlyAPartOfShowsNothingOfIt() {
        val api = holding()
        val browser = browserFor(api)
        api.on("GET", "diagnostics") { problem(500, "The diagnostics failed.") }

        val overview = browser.get("/services")
        val diagnostics = browser.get("/diagnostics")

        for (answer in listOf(overview, diagnostics)) {
            assertEquals(502 to "The server failed", answer.status to answer.page.select("main h1").text())
            assertEquals(0, answer.page.select("main table, main form, main section").size)
        }
    }

    // What a server holds is text

    /**
     * The requirement "Stored content is shown as text" for the overview and for the page of the
     * diagnostics: whatever a server states is on the page as the text that it is, and the page has no
     * element and no attribute that the UI did not write, and no address that leads elsewhere.
     */
    @Test
    fun whatAServerStatesOfItsServicesIsShownAsText() {
        val api = fake()
        val services = AtomicReference(emptyList<ServiceDto>())
        val reported = AtomicReference(emptyList<DiagnosticDto>())
        api.on("GET", "services") { json(services.get()) }
        api.on("GET", "diagnostics") { json(reported.get()) }
        val browser = browserFor(api)

        for (text in Hostile.MARKUP + Hostile.ADDRESSES) {
            val scenarios = listOf(scenario(text, text), scenario("$text-2", text))
            val service = ServiceDto(text, text, text, tags = listOf(text), scenarios = scenarios, defaultScenarioId = text)
            services.set(listOf(service.copy(lastUsedTime = text)))
            reported.set(listOf(DiagnosticDto(text, text, text, text, text, listOf(text))))
            api.system = FakeAdminApi.SYSTEM.copy(mockBaseUrl = text)

            val overview = browser.get("/services")
            val diagnostics = browser.get("/diagnostics")

            val cells = overview.page.select("main table.services tbody tr").single()
            val head = cells.select("th").first()!!
            val name = head.textNodes().first()
            val scenarioNames = cells.select("option").map { it.wholeText() }
            val ofDiagnostics = diagnostics.page.select("main section").single()
            val diagnostic = ofDiagnostics.select("li").single()
            val shown =
                mapOf(
                    "the name" to name.wholeText.removeSuffix(" "),
                    "the scenarios" to scenarioNames.toSet().single(),
                    "the tag" to cells.select("a.tag").single().wholeText(),
                    "the time" to cells.select("td").last()!!.wholeText(),
                    "the name of the group" to cells.select("[role=group]").attr("aria-label").removePrefix("Response mode of "),
                    "the name of the choice" to cells.select("select").attr("aria-label").removePrefix("Default scenario of "),
                    "the heading of the diagnostics" to ofDiagnostics.select("h2").single().wholeText(),
                    "the kind of the diagnostic" to ofDiagnostics.select(".kind").single().wholeText(),
                    "the diagnostic" to diagnostic.wholeText().removePrefix("$text: "),
                )
            assertEquals(shown.mapValues { text }, shown, text)
            assertEquals(callableUrlOf(api.system, text), cells.select("code.url").single().wholeText(), text)
            assertEquals(text, parseQueryString(cells.select("a.tag").attr("href").substringAfter('?'))["tag"], text)
            for (page in listOf(overview, diagnostics)) {
                val addresses = page.page.select("main [href], main [action]").map { it.attr("href") + it.attr("action") }
                assertEquals(emptyList(), addresses.filterNot { ADDRESS.matches(it) }, text)
                val elements = page.page.select("main *").map { it.tagName() }
                assertEquals(emptySet(), elements.toSet() - ELEMENTS, text)
                val attributes = page.page.select("main *").flatMap { it.attributes().map { attribute -> attribute.key } }
                assertEquals(emptySet(), attributes.toSet() - ATTRIBUTES, text)
                assertEquals(emptyList(), Hostile.UNWRITTEN.filter { it in page.body }, text)
                assertEquals(1, page.page.select("main [id^=service-]").size, text)
                assertTrue(ANCHOR.matches(page.page.select("main [id^=service-]").attr("id")), text)
            }
            assertNull(overview.page.select("main td a:not(.tag)").first(), text)
        }
        assertEquals(emptyList(), api.violations)
    }

    /** What the address of the overview names as a tag is shown as text as well, in the field of the filter and in the line below it. */
    @Test
    fun whatAnAddressNamesAsATagIsShownAsText() {
        val api = holding()
        val browser = browserFor(api)

        for (text in Hostile.MARKUP + Hostile.ADDRESSES) {
            val answer = browser.get(Address.of("/services", "tag" to text, "tag" to "plain"))

            val tags = (text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() } + "plain").distinct()
            val page = answer.page
            assertEquals(200, answer.status, text)
            assertEquals(tags.joinToString(" "), page.select("main form.filter [name=tag]").attr("value"), text)
            assertEquals("No service carries one of the tags ${tags.joinToString(", ")}.", counted(page), text)
            val addresses = page.select("main [href], main [action]").map { it.attr("href") + it.attr("action") }
            assertEquals(listOf("/services", "/services"), addresses, text)
            val elements = page.select("main *").map { it.tagName() }
            assertEquals(setOf("h1", "form", "div", "label", "input", "p", "button", "a"), elements.toSet(), text)
            assertEquals(emptyList(), Hostile.UNWRITTEN.filter { it in answer.body }, text)
        }
        assertEquals(emptyList(), api.violations)
    }

    private companion object {
        /** How often a sentence is repeated in a name that is long, and how many characters of a name the UI repeats. */
        const val REPEATED = 20
        const val BRIEF = 80

        /** The request that makes the scenario `found` the default one. */
        const val FOUND = """{"defaultScenarioId":"sc-found"}"""

        /** The elements that the two pages are made of. */
        val ELEMENTS =
            setOf("h1", "h2", "p", "a", "form", "div", "label", "input", "button", "table", "thead", "tbody", "tr", "th", "td")
                .plus(setOf("code", "select", "option", "span", "time", "section", "ul", "li"))

        /** The attributes that the two pages are written with. */
        val ATTRIBUTES =
            setOf("class", "id", "href", "action", "method", "type", "name", "value", "for", "scope", "role", "selected", "disabled")
                .plus(setOf("aria-label", "aria-pressed", "aria-describedby", "datetime"))

        /** How an address of the two pages is written: an address of the UI, and after it a place of a page of the UI. */
        val ADDRESS = Regex("/[a-z/-]*(\\?[A-Za-z0-9._~%=&-]*)?(#service-[A-Za-z0-9._~-]*)?")
        val ANCHOR = Regex("service-[A-Za-z0-9._~-]*")
    }
}
