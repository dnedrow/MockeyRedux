package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.DiagnosticDto
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.InspectorDto
import io.github.dnedrow.mockey.api.MatchCriterionDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ServiceAttributesDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import kotlinx.serialization.json.jsonObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.FormElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 13.6: the pages about one service. The page of a service shows what the service is set to, a form
 * creates a service and another one changes it, with its real URLs, its rule set, its inspector, its schema,
 * and its tags, a button duplicates a service, and the UI asks before it deletes one.
 *
 * The server is a stand-in that holds services as a server does, see [hold]. A test sends each form as a
 * browser without scripting does, see [Browser], and reads what the admin API was asked for.
 */
class UiServiceTest {
    private val fakes = mutableListOf<FakeAdminApi>()
    private val uis = mutableListOf<MockeyUi>()

    @AfterTest
    fun stopEverything() {
        uis.forEach(MockeyUi::close)
        fakes.forEach(FakeAdminApi::close)
    }

    private val paid = ScenarioDto("sc-paid", "paid", "{}", 200, emptyList(), MatchCriterionDto.None)
    private val due = ScenarioDto("sc-due", "due", "{}", 402, emptyList(), MatchCriterionDto.Text("due"))

    /** A service that has something of everything that the form of a service is about. */
    private val billing =
        ServiceDto(
            id = "svc-billing",
            name = "Billing",
            mockUrl = "billing/{account}",
            description = "What a customer owes.\nKept by the billing team.",
            realUrls = listOf("http://qa1.example.com/billing", "http://qa2.example.com/billing"),
            defaultRealUrlIndex = 1,
            responseMode = ResponseModeDto.DYNAMIC,
            effectiveResponseMode = ResponseModeDto.DYNAMIC,
            delayMillis = 250,
            tags = listOf("nightly", "team-b"),
            scenarios = listOf(paid, due),
            defaultScenarioId = due.id,
            errorScenarioId = paid.id,
            requestRules = "{\n  \"rules\": []\n}",
            requestRulesEnabled = true,
            inspectorName = "Audit",
            responseSchema = "{\"type\": \"object\"}",
            responseSchemaEnabled = false,
            lastUsedTime = "2026-01-02T03:04:05Z",
        )

    /** The attributes of [billing], as a form states them that is sent as the page shows it. */
    private val ofBilling =
        ServiceAttributesDto(
            name = billing.name,
            mockUrl = billing.mockUrl,
            description = billing.description,
            realUrls = billing.realUrls,
            defaultRealUrlIndex = 1,
            responseMode = ResponseModeDto.DYNAMIC,
            delayMillis = 250,
            tags = billing.tags,
            defaultScenarioId = due.id,
            requestRules = billing.requestRules,
            requestRulesEnabled = true,
            inspectorName = "Audit",
            responseSchema = billing.responseSchema,
        )

    private val inspectors = listOf(InspectorDto("Session check", global = false), Samples.inspector)

    /** Starts a stand-in for a server that holds [services] and reports [diagnostics], see [hold]. */
    private fun holding(
        services: List<ServiceDto> = listOf(billing, Samples.orders),
        diagnostics: List<DiagnosticDto> = emptyList(),
        paths: Map<String, String> = emptyMap(),
    ): FakeAdminApi = FakeAdminApi().also { fakes += it }.also { it.hold(services, diagnostics, paths, inspectors) }

    private fun browserFor(api: FakeAdminApi): Browser {
        val settings = UiSettings(port = 0, adminUrl = AdminUrl.of(api.url), logLevel = LogLevel.WARN)
        return Browser(MockeyUi.start(settings).also { uis += it })
    }

    /** Returns the terms of the description list of [page] with what the list says of each. */
    private fun described(page: Document): Map<String, String> =
        page.select("main dl dt").associate { it.text() to it.nextElementSibling()!!.text() }

    /** Returns what the fields of [form] hold, by their names, as a browser sends them when nothing is entered. */
    private fun shown(form: FormElement): Map<String, List<String>> {
        val areas = form.select("textarea").associate { it.attr("name") to it.wholeText() }
        val entered = form.formData().filter { it.key() != "_token" && !it.key().startsWith(Shown.PREFIX) }
        val fields = entered.map { it.key() to (areas[it.key()] ?: it.value()) }
        return fields.groupBy({ it.first }, { it.second })
    }

    private fun changing(api: FakeAdminApi): List<Pair<String, String>> =
        api.requests.filter { it.method != "GET" }.map { it.line to it.body }

    /** Returns the attributes that [body] states, which is the body of a request, with the names of the ones that it states. */
    private fun stated(body: String): Pair<Set<String>, ServiceAttributesDto> {
        val document = AdminApiJson.instance.parseToJsonElement(body)
        return document.jsonObject.keys to AdminApiJson.instance.decodeFromString(ServiceAttributesDto.serializer(), body)
    }

    // The page of a service

    /** The page of a service shows what the service is set to, its scenarios, and what leads to the forms that change it. */
    @Test
    fun thePageOfAServiceShowsWhatTheServiceIsSetTo() {
        val concerning = DiagnosticDto("unknownInspector", billing.id, billing.name, "The inspector 'Audit' is not installed.")
        val api = holding(diagnostics = listOf(concerning))
        val browser = browserFor(api)

        val answer = browser.get("/service?id=svc-billing")

        val page = answer.page
        val facts =
            mapOf(
                "Mock URL" to "http://mockey.test:8080/service/billing/{account}",
                "Description" to "What a customer owes. Kept by the billing team.",
                "Response mode" to "Dynamic",
                "Default scenario" to "due",
                "Delay" to "250 ms",
                "Tags" to "nightly team-b",
                "Real URLs" to "http://qa1.example.com/billing http://qa2.example.com/billing (default)",
                "Request rules" to "A rule set, which the server evaluates",
                "Inspector" to "Audit",
                "Response schema" to "A schema, to which the diagnostics do not hold the scenarios",
                "Last used" to "2026-01-02 03:04:05 UTC",
            )
        assertEquals(200, answer.status)
        assertEquals("Billing - Mockey" to "Billing", page.title() to page.select("main h1").text())
        assertEquals(facts, described(page))
        assertEquals("What a customer owes.\nKept by the billing team.", page.select("main dd.lines").first()!!.wholeText())
        assertEquals(listOf("due default", "paid error scenario"), page.select("main ul.scenarios li").map { it.text() })
        val links = page.select("main .actions a").associate { it.text() to it.attr("href") }
        assertEquals(mapOf("Edit" to "/service/edit?id=svc-billing", "Delete" to "/service/delete?id=svc-billing"), links)
        val duplicate = page.select("main .actions form").single()
        val ofDuplicate = Triple(duplicate.attr("method"), duplicate.attr("action"), duplicate.text())
        assertEquals(Triple("post", "/service/duplicate?id=svc-billing", "Duplicate"), ofDuplicate)
        val marker = page.select("main a.marker").single()
        assertEquals("1 diagnostic" to "/diagnostics#service-svc-billing", marker.text() to marker.attr("href"))
        assertEquals(listOf("/services?tag=nightly", "/services?tag=team-b"), page.select("main dd a.tag").map { it.attr("href") })
        assertEquals(0, page.select("main dd code.url a").size)
        assertEquals(listOf("/services"), page.select("nav a[aria-current=page]").map { it.attr("href") })
        assertEquals(listOf("GET system", "GET services/svc-billing", "GET diagnostics"), api.requests.map { it.line })
        assertEquals(emptyList(), api.violations)
    }

    /** A service that is set to little says so, and a service that answers in another mode than it is set to says in which. */
    @Test
    fun thePageOfAServiceSaysWhatTheServiceIsNotSetTo() {
        val static = Samples.orders.copy(responseMode = ResponseModeDto.STATIC)
        val waiting = static.copy(requestRules = "{}", responseSchema = "{}", responseSchemaEnabled = true)
        val browser = browserFor(holding(listOf(waiting)))

        val page = browser.get("/service?id=svc-orders").page

        val facts =
            mapOf(
                "Mock URL" to "http://mockey.test:8080/service/orders",
                "Response mode" to "Static. Answers as in proxy mode for now, because it has no scenario to answer with.",
                "Default scenario" to "none",
                "Delay" to "0 ms",
                "Tags" to "none",
                "Real URLs" to "http://qa1.example.com/orders",
                "Request rules" to "A rule set, which the server does not evaluate",
                "Inspector" to "none",
                "Response schema" to "A schema, to which the diagnostics hold the scenarios",
                "Last used" to "never",
            )
        assertEquals(facts, described(page))
        assertEquals(0, page.select("main a.marker, main ul.scenarios").size)
        assertEquals("The service has no scenario. It forwards every request while it has none.", page.select("main h2 + p").text())
        val without = browserFor(holding(listOf(Samples.customers))).get("/service?id=svc-customers").page
        val unset = listOf("Real URLs", "Request rules", "Response schema")
        assertEquals(listOf("none", "none", "none"), unset.map(described(without)::getValue))
    }

    /** The overview and the page of the diagnostics lead to the page of a service, and the overview leads to the form of a new one. */
    @Test
    fun theOverviewLeadsToThePageOfAServiceAndToTheFormOfANewOne() {
        val concerning = DiagnosticDto("unknownInspector", billing.id, billing.name, "The inspector 'Audit' is not installed.")
        val browser = browserFor(holding(diagnostics = listOf(concerning)))

        val overview = browser.get("/services").page
        val diagnostics = browser.get("/diagnostics").page

        val names = overview.select("main table.services th a:not(.marker)").associate { it.text() to it.attr("href") }
        assertEquals(mapOf("Billing" to "/service?id=svc-billing", "Orders" to "/service?id=svc-orders"), names)
        assertEquals(listOf("New service" to "/service/new"), overview.select("main p.actions a").map { it.text() to it.attr("href") })
        val headings = diagnostics.select("main section h2 a").map { it.text() to it.attr("href") }
        assertEquals(listOf("Billing" to "/service?id=svc-billing"), headings)
    }

    // The form of a new service

    /**
     * A user creates a service: the form states every attribute that it has a field for, the browser is sent
     * to the page of the new service, and that page says once that the service was created.
     */
    @Test
    fun theFormOfANewServiceCreatesTheServiceThatIsEntered() {
        val api = holding()
        val browser = browserFor(api)

        val page = browser.get("/service/new")
        val form = page.form()
        val entered =
            mapOf(
                "name" to "Shipping",
                "mockUrl" to "shipping/{parcel}",
                "description" to "Where a parcel is.\nAnd when it comes.",
                "tags" to " Team-C  nightly ",
                "mode" to "static",
                "delay" to " 750 ",
                "defaultRealUrl" to "2",
                "rules" to "{\n  \"rules\": []\n}",
                "rulesEnabled" to "on",
                "inspector" to "Session check",
                "schema" to "{\"type\": \"object\"}",
                "schemaEnabled" to "on",
            )
        val urls = listOf(" http://qa1.example.com/shipping ", "", "http://qa2.example.com/shipping")
        val sent = browser.submit(form, entered, rows = mapOf("realUrl" to urls))
        val shown = browser.follow(sent)
        val again = browser.follow(sent)

        assertEquals("New service - Mockey" to "post", page.page.title() to form.attr("method"))
        assertEquals(303 to "/service?id=svc-new-3", sent.status to sent.header("location"))
        assertEquals(200 to "The service Shipping was created.", shown.status to shown.notice)
        assertEquals("Shipping" to null, again.page.select("main h1").text() to again.notice)
        val (names, attributes) = stated(changing(api).single().second)
        val expected =
            ServiceAttributesDto(
                name = "Shipping",
                mockUrl = "shipping/{parcel}",
                description = "Where a parcel is.\nAnd when it comes.",
                realUrls = listOf("http://qa1.example.com/shipping", "http://qa2.example.com/shipping"),
                defaultRealUrlIndex = 1,
                responseMode = ResponseModeDto.STATIC,
                delayMillis = 750,
                tags = listOf("Team-C", "nightly"),
                requestRules = "{\n  \"rules\": []\n}",
                requestRulesEnabled = true,
                inspectorName = "Session check",
                responseSchema = "{\"type\": \"object\"}",
                responseSchemaEnabled = true,
            )
        assertEquals(listOf("POST services"), changing(api).map { it.first })
        assertEquals(expected, attributes)
        assertEquals(ATTRIBUTES, names)
        assertEquals(emptyList(), api.violations)
    }

    /** The form of a new service is that of a service in proxy mode, and a form that is sent with a name alone states nothing else. */
    @Test
    fun theFormOfANewServiceIsThatOfAServiceInProxyMode() {
        val api = holding()
        val browser = browserFor(api)

        val page = browser.get("/service/new")
        val sent = browser.submit(page.form(), mapOf("name" to "Plain", "mockUrl" to "plain"))

        val rows = mapOf("realUrl" to listOf("", "", ""), "defaultRealUrl" to listOf("0"))
        val fields = mapOf("mode" to listOf("proxy"), "delay" to listOf("0")) + rows
        val empty = listOf("name", "mockUrl", "description", "tags", "rules", "inspector", "schema").associateWith { listOf("") }
        assertEquals(empty + fields, shown(page.form()))
        assertEquals(0, page.page.select("main form [name=defaultScenario]").size)
        assertEquals(listOf("Cancel" to "/services"), page.page.select("main form .actions a").map { it.text() to it.attr("href") })
        assertEquals("Create the service", page.page.select("main form button").text())
        val hint = page.page.select("#f-mockUrl-hint").text()
        assertTrue(hint.endsWith("begins with http://mockey.test:8080/service/ and goes on as the pattern says."), hint)
        val ofTheName = listOf(page, browser.get("/service/edit?id=svc-orders")).map { it.page.select("#f-name-hint").text() }
        val withoutOne = "A new service with a real URL can do without a name: the server names it after that URL."
        assertEquals(listOf("What the pages call the service. $withoutOne", "What the pages call the service."), ofTheName)
        assertEquals(303, sent.status)
        assertEquals(ATTRIBUTES to ServiceAttributesDto(name = "Plain", mockUrl = "plain"), stated(changing(api).single().second))
    }

    // The form that changes a service

    /**
     * The form of a service shows what the server holds of the service. Sent as it is shown, it asks the
     * server to set nothing: it says of each field what its page gave it, and nothing was changed.
     */
    @Test
    fun theFormOfAServiceShowsWhatTheServerHoldsAndChangesNothingWhenItIsSentAsItIs() {
        val withoutUrls = billing.copy(id = "svc-other", mockUrl = "other", realUrls = emptyList())
        val other = withoutUrls.copy(requestRulesEnabled = false, responseSchemaEnabled = true)
        val api = holding(listOf(billing, other))
        val browser = browserFor(api)

        val ofOther = shown(browser.get("/service/edit?id=svc-other").form())
        api.requests.clear()
        val page = browser.get("/service/edit?id=svc-billing")
        val form = page.form()
        val sent = browser.submit(form)
        val shown = browser.follow(sent)

        val fields =
            mapOf(
                "name" to listOf("Billing"),
                "mockUrl" to listOf("billing/{account}"),
                "description" to listOf("What a customer owes.\nKept by the billing team."),
                "tags" to listOf("nightly team-b"),
                "mode" to listOf("dynamic"),
                "defaultScenario" to listOf("sc-due"),
                "delay" to listOf("250"),
                "realUrl" to listOf("http://qa1.example.com/billing", "http://qa2.example.com/billing", "", ""),
                "defaultRealUrl" to listOf("1"),
                "rules" to listOf("{\n  \"rules\": []\n}"),
                "rulesEnabled" to listOf("on"),
                "inspector" to listOf("Audit"),
                "schema" to listOf("{\"type\": \"object\"}"),
            )
        assertEquals("Edit the service Billing - Mockey" to "/service/edit?id=svc-billing", page.page.title() to form.attr("action"))
        assertEquals(fields, shown(form))
        val otherwise = mapOf("mockUrl" to listOf("other"), "realUrl" to listOf("", ""), "schemaEnabled" to listOf("on"))
        assertEquals(fields - "rulesEnabled" + otherwise, ofOther)
        assertEquals(listOf("due", "paid"), form.select("[name=defaultScenario] option").map { it.text() })
        assertEquals(listOf("Proxy", "Static", "Dynamic"), form.select("[name=mode] option").map { it.text() })
        assertEquals(listOf("Cancel" to "/service?id=svc-billing"), form.select(".actions a").map { it.text() to it.attr("href") })
        assertEquals(303 to "/service?id=svc-billing", sent.status to sent.header("location"))
        assertEquals("The service Billing was saved." to "Billing", shown.notice to shown.page.select("main h1").text())
        val hidden = form.select("input[type=hidden]").map { it.attr("name") }
        assertEquals(listOf("_token") + ServiceForm.FIELDS.map { "_shown.$it" }, hidden)
        assertEquals(listOf("PATCH services/svc-billing" to "{}"), changing(api))
        val requests = listOf("GET system", "GET services/svc-billing", "GET inspectors", "PATCH services/svc-billing")
        assertEquals(requests, api.requests.map { it.line }.take(requests.size))
        assertEquals(emptyList(), api.violations)
    }

    /**
     * What a user changes in the form of a service is what the server is asked to set, and the page of the
     * service shows it. The fields that are left as they are, here the mock URL and the schema, are not stated.
     */
    @Test
    fun theFormOfAServiceSetsWhatIsEntered() {
        val api = holding()
        val browser = browserFor(api)

        val form = browser.get("/service/edit?id=svc-billing").form()
        val entered =
            mapOf(
                "name" to "Invoices",
                "description" to "  ",
                "tags" to "",
                "mode" to "proxy",
                "defaultScenario" to "sc-paid",
                "delay" to "",
                "defaultRealUrl" to "3",
                "rules" to " \n ",
                "rulesEnabled" to null,
                "inspector" to "",
                "schemaEnabled" to "on",
            )
        val urls = listOf("", "http://qa2.example.com/billing", "", "http://qa3.example.com/billing")
        val sent = browser.submit(form, entered, rows = mapOf("realUrl" to urls))
        val shown = browser.follow(sent)

        val expected =
            ServiceAttributesDto(
                name = "Invoices",
                description = null,
                tags = emptyList(),
                responseMode = ResponseModeDto.PROXY,
                defaultScenarioId = "sc-paid",
                delayMillis = 0,
                realUrls = listOf("http://qa2.example.com/billing", "http://qa3.example.com/billing"),
                defaultRealUrlIndex = 1,
                requestRules = null,
                requestRulesEnabled = false,
                inspectorName = null,
                responseSchemaEnabled = true,
            )
        assertEquals(303, sent.status)
        assertEquals(ATTRIBUTES + "defaultScenarioId" - "mockUrl" - "responseSchema" to expected, stated(changing(api).single().second))
        assertEquals("The service Invoices was saved." to "Invoices", shown.notice to shown.page.select("main h1").text())
        assertEquals("http://qa2.example.com/billing http://qa3.example.com/billing (default)", described(shown.page)["Real URLs"])
        assertEquals(emptyList(), api.violations)
    }

    /** The row of the default real URL that is left out, and a form without one, make the first real URL the default one. */
    @Test
    fun theDefaultRealUrlIsTheFirstOneWhereTheFormNamesNone() {
        val api = holding()
        val browser = browserFor(api)
        val form = browser.get("/service/edit?id=svc-billing").form()
        val urls = listOf("http://qa1.example.com/billing", "", "http://qa3.example.com/billing")

        val ofAnEmptyRow = browser.submit(form, mapOf("defaultRealUrl" to "1"), rows = mapOf("realUrl" to urls))
        val ofNoRow = browser.submit(form, mapOf("defaultRealUrl" to null), rows = mapOf("realUrl" to urls))
        val ofNoUrl = browser.submit(form, mapOf("defaultRealUrl" to "0"), rows = mapOf("realUrl" to listOf(" ", "")))

        assertEquals(listOf(303, 303, 303), listOf(ofAnEmptyRow, ofNoRow, ofNoUrl).map { it.status })
        val asked = changing(api).map { stated(it.second).second }
        val both = listOf("http://qa1.example.com/billing", "http://qa3.example.com/billing")
        assertEquals(listOf(both to 0, both to 0, emptyList<String>() to 0), asked.map { it.realUrls to it.defaultRealUrlIndex })
    }

    /**
     * The form offers the scenarios of the service as its default one, and the inspectors that are installed.
     * What a service names that is not among them stays a choice, so that the form changes nothing by itself.
     */
    @Test
    fun theFormKeepsWhatAServiceNamesThatIsNotThere() {
        val astray = billing.copy(defaultScenarioId = "sc-gone", inspectorName = "Of old")
        val api = holding(listOf(astray, Samples.orders))
        val browser = browserFor(api)

        val form = browser.get("/service/edit?id=svc-billing").form()
        val ofOrders = browser.get("/service/edit?id=svc-orders").form()
        val sent = browser.submit(form)

        val inspectorsOffered = listOf("" to "none", "Audit" to "Audit (runs for every service)", "Session check" to "Session check")
        assertEquals(inspectorsOffered, ofOrders.select("[name=inspector] option").map { it.attr("value") to it.text() })
        assertEquals(0, ofOrders.select("[name=defaultScenario]").size)
        val offered = form.select("[name=inspector] option").map { it.attr("value") to it.text() }
        assertEquals(inspectorsOffered + ("Of old" to "Of old (not installed)"), offered)
        val scenariosOffered = form.select("[name=defaultScenario] option").map { it.attr("value") to it.text() }
        assertEquals(listOf("" to "none", "sc-due" to "due", "sc-paid" to "paid"), scenariosOffered)
        assertEquals(listOf("Of old") to listOf(""), shown(form).let { it.getValue("inspector") to it.getValue("defaultScenario") })
        assertEquals(303, sent.status)
        assertEquals(listOf("PATCH services/svc-billing" to "{}"), changing(api))
    }

    /**
     * The form states what was changed in it and nothing else, so that it leaves alone what someone else
     * changed since its page was made. The ends of the lines of a text are no change: a browser sends each
     * with a carriage return, whatever the page gave it.
     */
    @Test
    fun theFormOfAServiceStatesWhatWasChangedAndLeavesTheRestAsTheServerHoldsIt() {
        val held = billing.copy(description = "One line.\r\nAnother line.\rAnd a third.", delayMillis = -5)
        val api = holding(listOf(held, Samples.orders))
        val browser = browserFor(api)
        val form = browser.get("/service/edit?id=svc-billing").form()
        val other = Browser(uis.single())
        val overview = other.get("/services").page
        val ofOverview = overview.select("#service-svc-billing td.mode form").single() as FormElement

        val switched = other.submit(ofOverview, pressed = ofOverview.select("button[value=static]").single())
        val answers =
            listOf(
                browser.submit(form, mapOf("name" to "Invoices")),
                browser.submit(form, mapOf("defaultRealUrl" to "0")),
                browser.submit(form, mapOf("schemaEnabled" to "on")),
                browser.submit(form, mapOf("rulesEnabled" to null, "tags" to "nightly team-b ")),
                browser.submit(form, mapOf("description" to "One line.\nAnother line.\nAnd a third.")),
            )

        assertEquals(listOf(303) + answers.map { 303 }, (listOf(switched) + answers).map { it.status })
        val asked =
            listOf(
                """{"responseMode":"static"}""",
                """{"name":"Invoices"}""",
                """{"realUrls":["http://qa1.example.com/billing","http://qa2.example.com/billing"],"defaultRealUrlIndex":0}""",
                """{"responseSchemaEnabled":true}""",
                """{"tags":["nightly","team-b"],"requestRulesEnabled":false}""",
                "{}",
            )
        assertEquals(asked, changing(api).map { it.second })
        val now = browser.get("/service?id=svc-billing").page
        assertEquals("Static" to "-5 ms", described(now).let { it["Response mode"] to it["Delay"] })
        assertEquals(emptyList(), api.violations)
    }

    /** A form that does not say what its page gave its fields states every attribute, as the form of a new service does. */
    @Test
    fun aFormThatDoesNotSayWhatItWasShownWithStatesEveryAttribute() {
        val api = holding()
        val browser = browserFor(api)
        val form = browser.get("/service/edit?id=svc-billing").form()
        val token = form.select("[name=_token]").attr("value")

        val sent = browser.post("/service/edit?id=svc-billing", listOf("_token" to token, "name" to "Bare", "mode" to "static"))

        assertEquals(303, sent.status)
        val bare = ServiceAttributesDto(name = "Bare", responseMode = ResponseModeDto.STATIC)
        assertEquals(ATTRIBUTES to bare, stated(changing(api).single().second))
    }

    // A form that is not taken

    /**
     * The scenario "Duplicate mock URL": the form is shown again with everything that was entered, and with
     * what the server has against it next to the field that it concerns.
     */
    @Test
    fun aFormThatTheServerDoesNotTakeIsShownAgainWithWhatWasEnteredAndWithItsMessages() {
        val api = holding()
        val browser = browserFor(api)
        val errors =
            listOf(
                FieldErrorDto("mockUrl", "The service 'Orders' already uses the mock URL pattern 'orders'."),
                FieldErrorDto("name", "The name of a service must not be empty."),
                FieldErrorDto("realUrls", "The service 'Orders' already lists the real URL http://qa1.example.com/orders."),
                FieldErrorDto("requestRules", "The request rules are not a well-formed rule set."),
                FieldErrorDto("defaultScenarioId", "The service has no scenario with the identifier sc-paid."),
                FieldErrorDto("errorScenarioId", "Of a field that the form does not have."),
            )
        api.on("PATCH", "services/svc-billing") { problem(422, "The request is not valid.", errors) }

        val form = browser.get("/service/edit?id=svc-billing").form()
        val entered =
            mapOf(
                "name" to " ",
                "mockUrl" to "orders",
                "description" to "Another one.\nOf two lines.",
                "tags" to "Team-X",
                "mode" to "static",
                "defaultScenario" to "sc-paid",
                "delay" to "5",
                "defaultRealUrl" to "0",
                "rules" to "{ not a rule set",
                "rulesEnabled" to null,
                "inspector" to "Session check",
                "schema" to "",
                "schemaEnabled" to "on",
            )
        val urls = listOf("http://qa1.example.com/orders", "", "http://qa9.example.com/billing", "")
        val answer = browser.submit(form, entered, rows = mapOf("realUrl" to urls))

        val again = answer.form()
        val marks = { ofForm: FormElement -> ofForm.select("input[name^=_shown.]").map { it.attr("name") to it.attr("value") } }
        val kept = entered.filterValues { it != null }.mapValues { listOf(it.value!!) } + ("realUrl" to urls)
        assertEquals(422 to "Edit the service Billing", answer.status to answer.page.select("main h1").text())
        assertEquals(kept, shown(again))
        val messages =
            mapOf(
                "name" to listOf("The name of a service must not be empty."),
                "mockUrl" to listOf("The service 'Orders' already uses the mock URL pattern 'orders'."),
                "defaultScenario" to listOf("The service has no scenario with the identifier sc-paid."),
                "realUrl" to listOf("The service 'Orders' already lists the real URL http://qa1.example.com/orders."),
                "rules" to listOf("The request rules are not a well-formed rule set."),
            )
        assertEquals(messages, answer.fieldProblems)
        val others = answer.page.select("main form > .field-problem p").map { it.text() }
        assertEquals(listOf("errorScenarioId: Of a field that the form does not have."), others)
        assertEquals(1, answer.page.select("main form > .problem[role=alert]").size)
        val ofMockUrl = again.select("#f-mockUrl")
        assertEquals("true" to "f-mockUrl-hint f-mockUrl-problem", ofMockUrl.attr("aria-invalid") to ofMockUrl.attr("aria-describedby"))
        assertEquals("f-realUrl-hint f-realUrl-problem", again.select("fieldset").attr("aria-describedby"))
        assertEquals(listOf("PATCH services/svc-billing"), changing(api).map { it.first })
        assertEquals(marks(form) to ServiceForm.FIELDS.size, marks(again) to marks(again).size)
    }

    /** A delay that is no number of milliseconds, and a mode that is none, are not sent to the server: the form is shown again. */
    @Test
    fun aFormWithADelayThatIsNoNumberIsShownAgainAndNotSent() {
        val api = holding()
        val browser = browserFor(api)
        val form = browser.get("/service/edit?id=svc-billing").form()

        val delays = listOf("soon", "-1", "1.5", "99999999999999999999")
        val answers = delays.map { browser.submit(form, mapOf("delay" to it, "name" to "Kept")) }
        val ofBoth = browser.submit(form, mapOf("delay" to "x", "mode" to "turbo"))
        val created = browser.submit(browser.get("/service/new").form(), mapOf("name" to "Kept", "delay" to "later"))

        val message = listOf("The delay is a whole number of milliseconds, 0 or more.")
        for (answer in answers + created) {
            assertEquals(422 to mapOf("delay" to message), answer.status to answer.fieldProblems)
            assertEquals("Kept", answer.form().select("[name=name]").attr("value"))
        }
        assertEquals(delays, answers.map { it.form().select("[name=delay]").attr("value") })
        assertEquals(mapOf("mode" to listOf("The form names no response mode."), "delay" to message), ofBoth.fieldProblems)
        assertEquals(emptyList(), changing(api))
    }

    // Duplicating and deleting

    /** A button of the page of a service duplicates the service, and the browser is sent to the page of the copy. */
    @Test
    fun theButtonOfThePageOfAServiceDuplicatesTheService() {
        val api = holding()
        val browser = browserFor(api)

        val page = browser.get("/service?id=svc-billing")
        val sent = browser.submit(page.form("main .actions form"))
        val shown = browser.follow(sent)

        assertEquals(303 to "/service?id=svc-billing-copy", sent.status to sent.header("location"))
        assertEquals("The service was duplicated. This is the page of the copy.", shown.notice)
        assertEquals("Billing (copy)" to "none", shown.page.select("main h1").text() to described(shown.page)["Real URLs"])
        assertEquals(listOf("POST services/svc-billing/duplicate" to ""), changing(api))
        assertEquals(emptyList(), api.violations)
    }

    /**
     * The requirement "Confirmation of destructive actions" for a service: the link leads to a page that
     * asks, nothing is deleted before the form of that page is sent, and the overview says then that the
     * service was deleted.
     */
    @Test
    fun theUiAsksBeforeItDeletesAService() {
        val api = holding()
        val browser = browserFor(api)

        val asked = browser.get("/service/delete?id=svc-billing")
        val token = asked.form().select("[name=_token]").attr("value")
        val notConfirmed = browser.post("/service/delete?id=svc-billing", listOf("_token" to token))
        val before = changing(api)
        val sent = browser.submit(asked.form())
        val shown = browser.follow(sent)

        assertEquals(200 to "Delete the service Billing?", asked.status to asked.page.select("main h1").text())
        assertEquals(listOf("The service has 2 scenarios, which are deleted with it."), asked.page.select("main > p").map { it.text() })
        val button = asked.form().select("button").single()
        assertEquals("Delete the service" to setOf("danger"), button.text() to button.classNames())
        assertEquals(listOf("Cancel" to "/service?id=svc-billing"), asked.page.select("main form a").map { it.text() to it.attr("href") })
        assertEquals(200 to "Delete the service Billing?", notConfirmed.status to notConfirmed.page.select("main h1").text())
        assertEquals(emptyList(), before)
        assertEquals(303 to "/services", sent.status to sent.header("location"))
        assertEquals("The service Billing was deleted.", shown.notice)
        assertEquals(listOf("Orders"), shown.page.select("main table.services th a:not(.marker)").map { it.text() })
        assertEquals(listOf("DELETE services/svc-billing" to ""), changing(api))
        assertEquals(emptyList(), api.violations)
    }

    /** The page that asks says how many scenarios go with the service. */
    @Test
    fun thePageThatAsksSaysHowManyScenariosGoWithTheService() {
        val one = billing.copy(id = "svc-one", name = "One", mockUrl = "one", scenarios = listOf(paid), realUrls = emptyList())
        val browser = browserFor(holding(listOf(one, Samples.orders)))

        val pages = listOf("svc-one", "svc-orders").map { browser.get("/service/delete?id=$it").page }
        val said = pages.map { it.select("main > p").text() }

        assertEquals(listOf("The service has 1 scenario, which is deleted with it.", "The service has no scenario."), said)
    }

    // Addresses

    /** The pages of a service that is not there, and of no service, are pages that say so, and nothing is changed for them. */
    @Test
    fun thePagesOfAServiceThatIsNotThereSaySo() {
        val api = holding()
        val browser = browserFor(api)
        val form = browser.get("/service/new").form()
        val token = form.select("[name=_token]").attr("value")
        api.on("GET", "services/svc-gone") { problem(404, "No service has the identifier svc-gone.") }
        api.on("PATCH", "services/svc-gone") { problem(404, "No service has the identifier svc-gone.") }
        api.on("POST", "services/svc-gone/duplicate") { problem(404, "No service has the identifier svc-gone.") }

        val pages = listOf("/service", "/service/edit", "/service/delete").map { browser.get("$it?id=svc-gone") }
        val fields = listOf("_token" to token, "confirmed" to "yes", "mode" to "proxy")
        val forms = listOf("/service/edit", "/service/delete", "/service/duplicate").map { browser.post("$it?id=svc-gone", fields) }
        val ofNone = listOf("/service", "/service/edit", "/service/delete").map(browser::get)

        for (answer in pages + forms) {
            assertEquals(404 to "Not there", answer.status to answer.page.select("main h1").text())
            assertTrue("No service has the identifier svc-gone." in answer.page.select("main .problem").text())
        }
        assertEquals(listOf(404, 404, 404), ofNone.map { it.status })
        assertEquals(listOf("PATCH services/svc-gone", "POST services/svc-gone/duplicate"), changing(api).map { it.first })
    }

    /** The pages of a service work for a service with any identifier: the identifier is a parameter of their addresses. */
    @Test
    fun thePagesOfAServiceNameItInTheirAddressesWhateverItsIdentifierIs() {
        val ids = mapOf("a/b c" to "a%2Fb%20c", ".." to "..", "x?y#z&w=1" to "x%3Fy%23z%26w%3D1")
        val plain = billing.copy(realUrls = emptyList())
        val services = ids.keys.mapIndexed { index, id -> plain.copy(id = id, name = "Service $index", mockUrl = "s$index") }
        val api = holding(services, paths = ids)
        val browser = browserFor(api)

        for ((id, written) in ids) {
            val page = browser.get(Address.of("/service", "id" to id))
            val actions = page.page.select("main .actions a")
            val edit = browser.get(actions.first()!!.attr("href"))
            val saved = browser.submit(edit.form(), mapOf("delay" to "1"))
            val asked = browser.get(actions.last()!!.attr("href"))
            val deleted = browser.submit(asked.form())

            val leading = page.page.select("main .actions [href], main .actions [action]") + listOf<Element>(edit.form(), asked.form())
            val addresses = leading.map { it.attr("href") + it.attr("action") }
            assertEquals(emptyList(), addresses.filterNot(Hostile.SAFE_ADDRESS::matches), id)
            assertEquals(listOf(200, 200, 303, 200, 303), listOf(page, edit, saved, asked, deleted).map { it.status }, id)
            assertEquals(Address.of("/service", "id" to id), saved.header("location"), id)
            assertEquals(listOf("PATCH services/$written", "DELETE services/$written"), changing(api).takeLast(2).map { it.first }, id)
        }
        assertEquals(emptyList(), api.violations)
    }

    // What a server holds is text

    /**
     * The requirement "Stored content is shown as text" for the pages of a service: the page of the service
     * and the fields of its form hold what the server states as it is.
     */
    @Test
    fun whatAServerStatesOfAServiceIsShownAsTextOnItsPages() {
        val api = FakeAdminApi().also { fakes += it }
        val browser = browserFor(api)

        for (text in Hostile.MARKUP + Hostile.ADDRESSES) {
            val scenarios = listOf(paid.copy(id = text, name = text), due)
            val service =
                billing.copy(
                    id = text,
                    name = text,
                    mockUrl = text,
                    description = text,
                    realUrls = listOf(text),
                    tags = listOf(text),
                    scenarios = scenarios,
                    defaultScenarioId = text,
                    requestRules = text,
                    inspectorName = text,
                    responseSchema = text,
                    lastUsedTime = text,
                )
            api.hold(listOf(service), paths = mapOf(text to Hostile.written(text)), inspectors = emptyList())
            api.system = FakeAdminApi.SYSTEM.copy(mockBaseUrl = text)

            val page = browser.get(Address.of("/service", "id" to text)).page
            val edit = browser.get(Address.of("/service/edit", "id" to text))
            val asked = browser.get(Address.of("/service/delete", "id" to text)).page

            val facts = page.select("main dl dt").associate { it.text() to it.nextElementSibling()!!.wholeText() }
            val stated = listOf("Description", "Default scenario", "Tags", "Real URLs", "Inspector", "Last used")
            assertEquals(stated.associateWith { text }, facts.filterKeys { it in stated }, text)
            assertEquals(callableUrlOf(api.system, text), facts["Mock URL"], text)
            val scenarioNames = page.select("main ul.scenarios li").map { it.textNodes().first().wholeText }
            assertEquals(text to setOf("$text ", "due"), page.select("main h1").first()!!.wholeText() to scenarioNames.toSet(), text)
            val fields = shown(edit.form())
            val ofTexts = listOf("name", "mockUrl", "description", "tags", "defaultScenario", "rules", "inspector", "schema")
            assertEquals(ofTexts.associateWith { listOf(text) }, fields.filterKeys { it in ofTexts }, text)
            assertEquals(listOf(text, "", ""), fields["realUrl"], text)
            val headings = listOf(edit.page, asked).map { it.select("main h1").first()!!.wholeText() }
            assertEquals(listOf("Edit the service $text", "Delete the service $text?"), headings, text)
        }
        assertEquals(emptyList(), api.violations)
    }

    private companion object {
        /** The attributes of a service that the form of a service states, whatever is entered. */
        val ATTRIBUTES =
            setOf("name", "mockUrl", "description", "tags", "responseMode", "delayMillis", "realUrls", "defaultRealUrlIndex")
                .plus(setOf("requestRules", "requestRulesEnabled", "inspectorName", "responseSchema", "responseSchemaEnabled"))
    }
}
