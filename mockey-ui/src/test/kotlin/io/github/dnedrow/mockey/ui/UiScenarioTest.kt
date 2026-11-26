package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.MatchCriterionDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.ScenarioAttributesDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ScenarioReferenceDto
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
 * Task 13.7: the pages for a single scenario. The scenario page is the form that edits the status code,
 * headers, body, match criterion, method restriction, delay, and tags. There's also a form that creates a
 * scenario, buttons that assign and remove the default and error designations, and a confirmation step before
 * a scenario is deleted.
 *
 * Each test drives the UI over HTTP the way a browser without scripting would (see [Browser]), and then
 * checks which requests reached the admin API.
 */
class UiScenarioTest {
    private val fakes = mutableListOf<FakeAdminApi>()
    private val uis = mutableListOf<MockeyUi>()

    @AfterTest
    fun stopEverything() {
        uis.forEach(MockeyUi::close)
        fakes.forEach(FakeAdminApi::close)
    }

    private val cookies = listOf(HeaderDto("Set-Cookie", "a=1"), HeaderDto("Set-Cookie", "b=2"))

    /** A scenario that has a value for every attribute the form can edit. */
    private val gold =
        ScenarioDto(
            id = "sc-gold",
            name = "gold",
            responseBody = "{\n  \"plan\": \"gold\"\n}",
            statusCode = 201,
            headers = listOf(HeaderDto("Content-Type", "application/json")) + cookies,
            matchCriterion = MatchCriterionDto.Text("<tier>gold</tier>"),
            methodRestriction = "POST",
            delayMillis = 250,
            tags = listOf("happy", "nightly"),
            lastUsedTime = "2026-01-02T03:04:05Z",
        )
    private val basic = ScenarioDto("sc-basic", "basic", "{}", 200, emptyList(), MatchCriterionDto.None)
    private val ruled = ScenarioDto("sc-ruled", "ruled", "", 404, emptyList(), MatchCriterionDto.Rules("{\"rules\": []}"))

    /** A service in dynamic mode. Its default scenario is [gold], and its error scenario is [basic]. */
    private val plans =
        ServiceDto(
            id = "svc-plans",
            name = "Plans",
            mockUrl = "plans",
            responseMode = ResponseModeDto.DYNAMIC,
            effectiveResponseMode = ResponseModeDto.DYNAMIC,
            scenarios = listOf(gold, basic, ruled),
            defaultScenarioId = gold.id,
            errorScenarioId = basic.id,
        )

    /** Starts a stand-in server that holds [services] (see [hold]). [universal] is its universal error scenario. */
    private fun holding(
        services: List<ServiceDto> = listOf(plans, Samples.orders),
        paths: Map<String, String> = emptyMap(),
        universal: ScenarioReferenceDto? = null,
    ): FakeAdminApi = FakeAdminApi().also { fakes += it }.also { it.hold(services, paths = paths, universal = universal) }

    private fun browserFor(api: FakeAdminApi): Browser {
        val settings = UiSettings(port = 0, adminUrl = AdminUrl.of(api.url), logLevel = LogLevel.WARN)
        return Browser(MockeyUi.start(settings).also { uis += it })
    }

    /** Returns the address of the page at [path] for the scenario [scenarioId] of the service [serviceId]. */
    private fun at(
        path: String,
        scenarioId: String = gold.id,
        serviceId: String = plans.id,
    ): String = Address.of(path, "service" to serviceId, "id" to scenarioId)

    /** Returns the form in [answer] that edits or creates a scenario. */
    private fun scenarioForm(answer: Browser.Response): FormElement = answer.form("main > form")

    /** Returns the field values of [form] by name, the way a browser would submit them if nobody typed anything. */
    private fun shown(form: FormElement): Map<String, List<String>> {
        val areas = form.select("textarea").associate { it.attr("name") to it.wholeText() }
        val entered = form.formData().filter { it.key() != "_token" && !it.key().startsWith(Shown.PREFIX) }
        val fields = entered.map { it.key() to (areas[it.key()] ?: it.value()) }
        return fields.groupBy({ it.first }, { it.second })
    }

    /** Returns the requests that could change something, each as a pair of its request line and its body. */
    private fun changing(api: FakeAdminApi): List<Pair<String, String>> =
        api.requests.filter { it.method != "GET" }.map { it.line to it.body }

    /** Returns the names of the attributes in [body] along with the attributes themselves. [body] is the JSON body of a request. */
    private fun stated(body: String): Pair<Set<String>, ScenarioAttributesDto> {
        val document = AdminApiJson.instance.parseToJsonElement(body)
        return document.jsonObject.keys to AdminApiJson.instance.decodeFromString(ScenarioAttributesDto.serializer(), body)
    }

    /** Returns the designation forms on [page], each as its action, the value of its button, and the button's label. */
    private fun designationButtons(page: Document): List<Triple<String, String, String>> =
        page.select("main .designation form").map { Triple(it.attr("action"), it.select("button").attr("value"), it.text()) }

    // The page of a scenario

    /**
     * The scenario page is the form that edits the scenario. It shows the stored values, and it doesn't send
     * any attribute if it's submitted unchanged.
     */
    @Test
    fun thePageOfAScenarioShowsTheStoredValuesAndSendsNoAttributeIfNothingIsChanged() {
        val api = holding()
        val browser = browserFor(api)

        val answer = browser.get("/scenario?service=svc-plans&id=sc-gold")
        val form = scenarioForm(answer)
        val sent = browser.submit(form)
        val after = browser.follow(sent)

        val page = answer.page
        val fields =
            mapOf(
                "name" to listOf("gold"),
                "status" to listOf("201"),
                "headers" to listOf("Content-Type: application/json\nSet-Cookie: a=1\nSet-Cookie: b=2"),
                "body" to listOf("{\n  \"plan\": \"gold\"\n}"),
                "criterion" to listOf("<tier>gold</tier>"),
                "method" to listOf("POST"),
                "delay" to listOf("250"),
                "tags" to listOf("happy nightly"),
                "bodyLineEndings" to listOf("lf"),
                "criterionLineEndings" to listOf("lf"),
            )
        assertEquals(200, answer.status)
        assertEquals("Scenario gold - Mockey" to "Scenario gold", page.title() to page.select("main h1").text())
        val ofService = page.select("main > p").first()!!
        assertEquals("A scenario of the service Plans." to "/service?id=svc-plans", ofService.text() to ofService.select("a").attr("href"))
        assertEquals("post" to "/scenario?service=svc-plans&id=sc-gold", form.attr("method") to form.attr("action"))
        assertEquals(fields, shown(form))
        val methods = listOf("" to "Any method") + listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS").map { it to it }
        assertEquals(methods, form.select("[name=method] option").map { it.attr("value") to it.text() })
        val labels = listOf("Name", "Status code", "Headers", "Body", "Match criterion", RULES_LABEL, "Method", "Delay", "Tags")
        assertEquals(labels, form.select("label").map { it.text() })
        val hidden = listOf("_token") + ScenarioForm.FIELDS.map { "_shown.$it" } + ScenarioForm.LINE_ENDING_FIELDS
        assertEquals(hidden, form.select("input[type=hidden]").map { it.attr("name") })
        assertEquals(listOf("Cancel" to "/service?id=svc-plans"), form.select(".actions a").map { it.text() to it.attr("href") })
        assertEquals(listOf("/services"), page.select("nav a[aria-current=page]").map { it.attr("href") })
        assertEquals(303 to "/scenario?service=svc-plans&id=sc-gold", sent.status to sent.header("location"))
        assertEquals("The scenario gold was saved.", after.notice)
        assertEquals(listOf("PATCH services/svc-plans/scenarios/sc-gold" to "{}"), changing(api))
        val requests = listOf("GET system", "GET services/svc-plans", "GET universal-error-scenario")
        assertEquals(requests, api.requests.map { it.line }.take(requests.size))
        assertEquals(emptyList(), api.violations)
    }

    /** The form shows a rule set with the check box checked, and it leaves the field empty for a scenario without a criterion. */
    @Test
    fun theFormShowsARuleSetAndAScenarioWithoutACriterion() {
        val browser = browserFor(holding())

        val ofRuled = shown(scenarioForm(browser.get(at("/scenario", ruled.id))))
        val ofBasic = shown(scenarioForm(browser.get(at("/scenario", basic.id))))

        assertEquals(listOf("{\"rules\": []}") to listOf("on"), ofRuled["criterion"] to ofRuled["criterionIsRules"])
        assertEquals(listOf("") to null, ofBasic["criterion"] to ofBasic["criterionIsRules"])
        assertEquals(listOf("404") to listOf("0"), ofRuled["status"] to ofRuled["delay"])
        assertEquals(listOf(listOf(""), listOf(""), listOf("")), listOf("method", "headers", "tags").map(ofBasic::get))
    }

    // What the form sends

    /** The form only sends the attributes whose fields were changed. Each submission in this test changes one field of the same page. */
    @Test
    fun theFormSendsOnlyTheAttributesThatWereChanged() {
        val api = holding()
        val browser = browserFor(api)
        val form = scenarioForm(browser.get(at("/scenario")))
        val headers = "X-One: 1\n\n  X-One :  2  \nX-Empty:\nLocation: http://example.com/a:b"
        val asHeaders = """[{"name":"X-One","value":"1"},{"name":"X-One","value":"2"},{"name":"X-Empty","value":""},$LOCATION]"""

        val changes =
            listOf(
                mapOf("name" to "platinum") to """{"name":"platinum"}""",
                mapOf("status" to " 503 ") to """{"statusCode":503}""",
                mapOf("headers" to headers) to """{"headers":$asHeaders}""",
                mapOf("headers" to " \n ") to """{"headers":[]}""",
                mapOf("body" to "new\nbody\n") to """{"responseBody":"new\nbody\n"}""",
                mapOf("body" to "") to """{"responseBody":""}""",
                mapOf("criterion" to "silver") to """{"matchCriterion":{"type":"text","text":"silver"}}""",
                mapOf("criterionIsRules" to "on") to """{"matchCriterion":{"type":"rules","source":"<tier>gold</tier>"}}""",
                mapOf("criterion" to "{}", "criterionIsRules" to "on") to """{"matchCriterion":{"type":"rules","source":"{}"}}""",
                mapOf("criterion" to " \n ") to """{"matchCriterion":{"type":"none"}}""",
                mapOf("criterion" to "", "criterionIsRules" to "on") to """{"matchCriterion":{"type":"none"}}""",
                mapOf("method" to "") to """{"methodRestriction":null}""",
                mapOf("method" to "GET") to """{"methodRestriction":"GET"}""",
                mapOf("delay" to "") to """{"delayMillis":0}""",
                mapOf("delay" to " 40 ") to """{"delayMillis":40}""",
                mapOf("tags" to " beta  alpha\t") to """{"tags":["beta","alpha"]}""",
                mapOf("tags" to "") to """{"tags":[]}""",
                mapOf("name" to "", "tags" to "one") to """{"name":"","tags":["one"]}""",
            )
        val answers = changes.map { (edits, _) -> browser.submit(form, edits) }

        assertEquals(changes.map { 303 }, answers.map { it.status })
        assertEquals(changes.map { it.second }, changing(api).map { it.second })
        assertEquals(setOf("PATCH services/svc-plans/scenarios/sc-gold"), changing(api).map { it.first }.toSet())
        assertEquals(emptyList(), api.violations)
    }

    /**
     * The server sends a body exactly as it's stored, so an edited body has to keep the line endings of the
     * stored body. Browsers submit every line break as a carriage return plus a line feed, and the form
     * restores the stored style. The same goes for the match criterion.
     */
    @Test
    fun anEditedTextKeepsTheLineEndingsOfTheStoredText() {
        val windows = gold.copy(id = "sc-windows", responseBody = "a\r\nb\r\n", matchCriterion = MatchCriterionDto.Text("x\r\ny"))
        val mixed = gold.copy(id = "sc-mixed", responseBody = "a\r\nb\nc\rd")
        val bodyOnly = gold.copy(id = "sc-body-only", responseBody = "a\r\nb", matchCriterion = MatchCriterionDto.Text("x\ny"))
        val criterionOnly = gold.copy(id = "sc-criterion-only", responseBody = "a\nb", matchCriterion = MatchCriterionDto.Text("x\r\ny"))
        val api = holding(listOf(plans.copy(scenarios = listOf(gold, windows, mixed, bodyOnly, criterionOnly))))
        val browser = browserFor(api)
        val ofWindows = scenarioForm(browser.get(at("/scenario", windows.id)))
        val ofMixed = scenarioForm(browser.get(at("/scenario", mixed.id)))
        val ofUnix = scenarioForm(browser.get(at("/scenario", gold.id)))
        val ofBodyOnly = scenarioForm(browser.get(at("/scenario", bodyOnly.id)))
        val ofCriterionOnly = scenarioForm(browser.get(at("/scenario", criterionOnly.id)))
        val both = mapOf("body" to "a\nb\nc", "criterion" to "x\ny\nz")

        val answers =
            listOf(
                browser.submit(ofWindows),
                browser.submit(ofWindows, mapOf("body" to "a\nb\nc\n")),
                browser.submit(ofWindows, mapOf("criterion" to "x\ny\nz")),
                browser.submit(ofMixed),
                browser.submit(ofMixed, mapOf("body" to "a\nb\nc\nd\n")),
                browser.submit(ofUnix, mapOf("body" to "a\nb", "criterion" to "x\ny")),
                browser.submit(ofBodyOnly, both),
                browser.submit(ofCriterionOnly, both),
            )

        val styles = { form: FormElement -> ScenarioForm.LINE_ENDING_FIELDS.map { shown(form).getValue(it).single() } }
        val forms = listOf(ofWindows, ofMixed, ofUnix, ofBodyOnly, ofCriterionOnly)
        val crlf = listOf("crlf", "crlf")
        val lf = listOf("lf", "lf")
        assertEquals(listOf(crlf, lf, lf, listOf("crlf", "lf"), listOf("lf", "crlf")), forms.map(styles))
        assertEquals(listOf("a\nb\n") to listOf("a\nb\nc\nd"), shown(ofWindows)["body"] to shown(ofMixed)["body"])
        assertEquals(answers.map { 303 }, answers.map { it.status })
        val asked =
            listOf(
                "{}",
                """{"responseBody":"a\r\nb\r\nc\r\n"}""",
                """{"matchCriterion":{"type":"text","text":"x\r\ny\r\nz"}}""",
                "{}",
                """{"responseBody":"a\nb\nc\nd\n"}""",
                """{"responseBody":"a\nb","matchCriterion":{"type":"text","text":"x\ny"}}""",
                """{"responseBody":"a\r\nb\r\nc","matchCriterion":{"type":"text","text":"x\ny\nz"}}""",
                """{"responseBody":"a\nb\nc","matchCriterion":{"type":"text","text":"x\r\ny\r\nz"}}""",
            )
        assertEquals(asked, changing(api).map { it.second })
    }

    /** The body field shows the empty lines and the spaces of a body, and the form sends them along with an edited body. */
    @Test
    fun theBodyFieldKeepsEmptyLinesAndSpaces() {
        val body = "\n\n  indented \n\n"
        val api = holding(listOf(plans.copy(scenarios = listOf(gold.copy(responseBody = body)))))
        val browser = browserFor(api)
        val form = scenarioForm(browser.get(at("/scenario")))

        val unchanged = browser.submit(form)
        val edited = browser.submit(form, mapOf("body" to "$body  more"))

        assertEquals(listOf(body), shown(form)["body"])
        assertEquals(listOf(303, 303), listOf(unchanged, edited).map { it.status })
        assertEquals(listOf("{}", """{"responseBody":"\n\n  indented \n\n  more"}"""), changing(api).map { it.second })
    }

    /**
     * Browsers change some values on their own: they strip the line breaks from the value of a one-line
     * field, and they replace every NUL character with U+FFFD. The form doesn't treat such a value as
     * changed, so the server keeps the stored value until someone edits the field.
     */
    @Test
    fun theFormDoesNotSendAValueThatOnlyTheBrowserAltered() {
        val odd = "one\ntwo\u0000three\r\nfour"
        val criterion = MatchCriterionDto.Text(odd)
        val oddHeaders = listOf(HeaderDto("X-Odd", odd))
        val named = gold.copy(name = odd, tags = listOf(odd), methodRestriction = odd)
        val held = named.copy(responseBody = odd, headers = oddHeaders, matchCriterion = criterion)
        val api = holding(listOf(plans.copy(scenarios = listOf(held))))
        val browser = browserFor(api)
        val form = scenarioForm(browser.get(at("/scenario")))

        val unchanged = browser.submit(form)
        val changed = browser.submit(form, mapOf("delay" to "7"))

        val inOneLine = "onetwo\uFFFDthreefour"
        val inLines = "one\ntwo\uFFFDthree\nfour"
        val fields = shown(form)
        assertEquals(listOf(303, 303), listOf(unchanged, changed).map { it.status })
        assertEquals(listOf("{}", """{"delayMillis":7}"""), changing(api).map { it.second })
        assertEquals(listOf(inOneLine), listOf("name", "tags").flatMap(fields::getValue).distinct())
        assertEquals(listOf(inLines), listOf("body", "criterion", "method").flatMap(fields::getValue).distinct())
        assertEquals(listOf("X-Odd: $inLines"), fields["headers"])
    }

    /** A stored method restriction that isn't on the form's list stays available, so the form doesn't change it on its own. */
    @Test
    fun theFormOffersAStoredMethodThatIsNotInItsList() {
        val scenarios = listOf(gold.copy(methodRestriction = "*"), basic.copy(methodRestriction = "get"))
        val api = holding(listOf(plans.copy(scenarios = scenarios)))
        val browser = browserFor(api)

        val ofAny = scenarioForm(browser.get(at("/scenario")))
        val ofLowerCase = scenarioForm(browser.get(at("/scenario", basic.id)))
        val sent = listOf(browser.submit(ofAny), browser.submit(ofLowerCase))

        val offered = { form: FormElement -> form.select("[name=method] option").map { it.attr("value") } }
        assertEquals("*" to "get", offered(ofAny).last() to offered(ofLowerCase).last())
        assertEquals(listOf(9, 9), listOf(ofAny, ofLowerCase).map { offered(it).size })
        assertEquals(listOf("*") to listOf("get"), shown(ofAny)["method"] to shown(ofLowerCase)["method"])
        assertEquals(listOf(303, 303), sent.map { it.status })
        assertEquals(listOf("{}", "{}"), changing(api).map { it.second })
    }

    // A new scenario

    /**
     * The form for a new scenario sends every attribute, and the browser ends up on the page of the new
     * scenario. Submitting the form as it's displayed creates a scenario with the default values.
     */
    @Test
    fun theFormForANewScenarioCreatesTheScenario() {
        val api = holding()
        val browser = browserFor(api)

        val answer = browser.get("/scenario/new?service=svc-plans")
        val form = scenarioForm(answer)
        val entered =
            mapOf(
                "name" to "silver",
                "status" to "202",
                "headers" to "Content-Type: text/xml",
                "body" to "<plan>silver</plan>\n",
                "criterion" to "{\"rules\": []}",
                "criterionIsRules" to "on",
                "method" to "PUT",
                "delay" to "30",
                "tags" to "new metal",
            )
        val sent = browser.submit(form, entered)
        val created = browser.follow(sent)
        val ofDefaults = browser.submit(form)

        val lineEndings = ScenarioForm.LINE_ENDING_FIELDS.associateWith { listOf("lf") }
        val initial = listOf("name", "headers", "body", "criterion", "method", "tags").associateWith { listOf("") }
        assertEquals(200, answer.status)
        assertEquals("New scenario - Mockey" to "New scenario", answer.page.title() to answer.page.select("main h1").text())
        val ofService = answer.page.select("main > p").first()!!
        assertEquals("A scenario of the service Plans.", ofService.text())
        assertEquals("post" to "/scenario/new?service=svc-plans", form.attr("method") to form.attr("action"))
        assertEquals(initial + lineEndings + mapOf("status" to listOf("200"), "delay" to listOf("0")), shown(form))
        assertEquals(0, form.select("input[name^=_shown.]").size)
        assertEquals("Create the scenario", form.select("button").text())
        assertEquals(listOf("Cancel" to "/service?id=svc-plans"), form.select(".actions a").map { it.text() to it.attr("href") })
        assertEquals(0, answer.page.select("main .designation").size)
        assertEquals(303 to "/scenario?service=svc-plans&id=sc-new-1", sent.status to sent.header("location"))
        assertEquals("The scenario silver was created." to "Scenario silver", created.notice to created.page.select("main h1").text())
        assertEquals(entered.mapValues { listOf(it.value) } + lineEndings, shown(scenarioForm(created)))
        assertEquals(303 to "/scenario?service=svc-plans&id=sc-new-2", ofDefaults.status to ofDefaults.header("location"))
        val requests = changing(api)
        assertEquals(listOf("POST services/svc-plans/scenarios", "POST services/svc-plans/scenarios"), requests.map { it.first })
        val xml = listOf(HeaderDto("Content-Type", "text/xml"))
        val rules = MatchCriterionDto.Rules("{\"rules\": []}")
        val silver = ScenarioAttributesDto("silver", "<plan>silver</plan>\n", 202, xml, rules, "PUT", 30, listOf("new", "metal"))
        assertEquals(ATTRIBUTES to silver, stated(requests[0].second))
        assertEquals(ATTRIBUTES to ScenarioAttributesDto(), stated(requests[1].second))
        assertEquals(emptyList(), api.violations)
    }

    // A form that is rejected

    /**
     * The UI checks the status code, the delay, and the header lines itself. An invalid form is displayed
     * again with the entered values and the messages, and no request is sent. The form that's displayed again
     * still only sends the changed attributes.
     */
    @Test
    fun theUiRejectsAnInvalidStatusCodeDelayAndHeaderLineWithoutSendingARequest() {
        val api = holding()
        val browser = browserFor(api)
        val form = scenarioForm(browser.get(at("/scenario")))
        val headerLines = "Content-Type: text/plain\nnonsense\n: x\n\nX-Ok: 1"
        val entered = mapOf("status" to "abc", "headers" to headerLines, "delay" to "-1", "body" to "kept")

        val answer = browser.submit(form, entered)
        val again = scenarioForm(answer)
        val before = changing(api)
        val corrected = browser.submit(again, mapOf("status" to "204", "headers" to "X-Ok: 1", "delay" to ""))

        val format = "A header has the form Name: value, for example Content-Type: text/plain."
        val messages =
            mapOf(
                "status" to listOf("The status code is a whole number from 100 to 999."),
                "headers" to listOf("Line 2 is not a header. $format", "Line 3 is not a header. $format"),
                "delay" to listOf("The delay is a whole number of milliseconds, 0 or more."),
            )
        val marks = { ofForm: FormElement -> ofForm.select("input[name^=_shown.]").map { it.attr("name") to it.attr("value") } }
        assertEquals(422 to "Scenario gold", answer.status to answer.page.select("main h1").text())
        assertEquals(messages, answer.fieldProblems)
        assertEquals(entered.mapValues { listOf(it.value) }, shown(again).filterKeys { it in entered })
        assertEquals(1, answer.page.select("main > form > .problem[role=alert]").size)
        val ofStatus = again.select("#f-status")
        assertEquals("true" to "f-status-hint f-status-problem", ofStatus.attr("aria-invalid") to ofStatus.attr("aria-describedby"))
        assertEquals(marks(form) to ScenarioForm.FIELDS.size, marks(again) to marks(again).size)
        assertEquals(emptyList(), before)
        assertEquals(303, corrected.status)
        val asked = """{"statusCode":204,"headers":[{"name":"X-Ok","value":"1"}],"responseBody":"kept","delayMillis":0}"""
        assertEquals(listOf(asked), changing(api).map { it.second })
    }

    /** A status code is a whole number from 100 to 999, and a delay is a whole number that's 0 or greater. */
    @Test
    fun theUiAcceptsAStatusCodeFrom100To999AndADelayOf0OrMore() {
        val api = holding()
        val browser = browserFor(api)
        val form = scenarioForm(browser.get(at("/scenario")))
        val ofNew = scenarioForm(browser.get("/scenario/new?service=svc-plans"))

        val statuses = listOf("99", "1000", "", "20.0", "2 00", "-200", "two hundred")
        val delays = listOf("-1", "1.5", "soon", "99999999999999999999")
        val ofStatuses = statuses.map { browser.submit(form, mapOf("status" to it)) }
        val rejected = ofStatuses + delays.map { browser.submit(form, mapOf("delay" to it)) }
        val rejectedNew = browser.submit(ofNew, mapOf("name" to "kept", "status" to "x"))
        val before = changing(api)
        val valid = listOf("status" to "100", "status" to "999", "delay" to "0", "delay" to "9223372036854775807")
        val accepted = valid.map { (field, value) -> browser.submit(form, mapOf(field to value)) }

        assertEquals(rejected.map { 422 }, rejected.map { it.status })
        assertEquals(statuses.map { "status" } + delays.map { "delay" }, rejected.map { it.fieldProblems.keys.single() })
        assertEquals(422 to "New scenario", rejectedNew.status to rejectedNew.page.select("main h1").text())
        val keptName = scenarioForm(rejectedNew).select("[name=name]").attr("value")
        assertEquals("kept" to setOf("status"), keptName to rejectedNew.fieldProblems.keys)
        assertEquals(emptyList(), before)
        assertEquals(accepted.map { 303 }, accepted.map { it.status })
        val ofDelays = listOf("""{"delayMillis":0}""", """{"delayMillis":9223372036854775807}""")
        assertEquals(listOf("""{"statusCode":100}""", """{"statusCode":999}""") + ofDelays, changing(api).map { it.second })
    }

    /**
     * A form that the server rejects is displayed again with the entered values. Each message from the server
     * shows up next to the field it's about, and a message for a field the form doesn't have shows up at the
     * end of the form.
     */
    @Test
    fun aFormThatTheServerRejectsShowsTheMessagesNextToTheFields() {
        val api = holding()
        val browser = browserFor(api)
        val errors =
            listOf(
                FieldErrorDto("name", "The name of a scenario has at most 250 characters."),
                FieldErrorDto("statusCode", "About the status code."),
                FieldErrorDto("headers[1].name", "About a header."),
                FieldErrorDto("responseBody", "About the body."),
                FieldErrorDto("matchCriterion", "The rule set is not well-formed."),
                FieldErrorDto("matchCriterion.text", "The text of a text criterion is not blank."),
                FieldErrorDto("methodRestriction", "About the method."),
                FieldErrorDto("delayMillis", "About the delay."),
                FieldErrorDto("tags[0]", "About a tag."),
                FieldErrorDto("other", "About a field that the form does not have."),
            )
        api.on("PATCH", "services/svc-plans/scenarios/sc-gold") { problem(422, "The request is not valid.", errors) }
        api.on("POST", "services/svc-plans/scenarios") { problem(422, "The request is not valid.", errors.take(1)) }
        val entered = mapOf("name" to "x".repeat(251), "criterion" to "{ not a rule set", "criterionIsRules" to "on", "tags" to "a b")

        val answer = browser.submit(scenarioForm(browser.get(at("/scenario"))), entered)
        val ofNew = browser.submit(scenarioForm(browser.get("/scenario/new?service=svc-plans")), entered)

        val messages =
            mapOf(
                "name" to listOf("The name of a scenario has at most 250 characters."),
                "status" to listOf("About the status code."),
                "headers" to listOf("About a header."),
                "body" to listOf("About the body."),
                "criterion" to listOf("The rule set is not well-formed.", "The text of a text criterion is not blank."),
                "method" to listOf("About the method."),
                "delay" to listOf("About the delay."),
                "tags" to listOf("About a tag."),
            )
        val again = scenarioForm(answer)
        assertEquals(422 to "Scenario gold", answer.status to answer.page.select("main h1").text())
        assertEquals(messages, answer.fieldProblems)
        assertEquals(entered.mapValues { listOf(it.value) }, shown(again).filterKeys { it in entered })
        assertEquals(listOf("201") to listOf("POST"), shown(again).let { it["status"] to it["method"] })
        val others = answer.page.select("main > form > .field-problem p").map { it.text() }
        assertEquals(listOf("other: About a field that the form does not have."), others)
        assertEquals(1, answer.page.select("main > form > .problem[role=alert]").size)
        assertEquals(422 to "New scenario", ofNew.status to ofNew.page.select("main h1").text())
        assertEquals(mapOf("name" to messages.getValue("name")), ofNew.fieldProblems)
        assertEquals(entered.mapValues { listOf(it.value) }, shown(scenarioForm(ofNew)).filterKeys { it in entered })
        val lines = listOf("PATCH services/svc-plans/scenarios/sc-gold", "POST services/svc-plans/scenarios")
        assertEquals(lines, changing(api).map { it.first })
    }

    /** A stored value that the form would reject doesn't stop the form from saving a different field. */
    @Test
    fun anInvalidStoredValueDoesNotPreventSavingAnotherField() {
        val noHeaders = listOf(HeaderDto("", "no name"), HeaderDto("a:b", "c"))
        val held = gold.copy(statusCode = 0, delayMillis = -5, headers = noHeaders)
        val api = holding(listOf(plans.copy(scenarios = listOf(held))))
        val browser = browserFor(api)
        val form = scenarioForm(browser.get(at("/scenario")))

        val sent = browser.submit(form, mapOf("name" to "renamed"))

        val fields = shown(form)
        assertEquals(listOf(listOf("0"), listOf("-5"), listOf(": no name\na:b: c")), listOf("status", "delay", "headers").map(fields::get))
        assertEquals(303, sent.status)
        assertEquals(listOf("""{"name":"renamed"}"""), changing(api).map { it.second })
    }

    // Designations

    /**
     * The page says whether the scenario is the default scenario, the error scenario of its service, and the
     * universal error scenario. Each designation comes with the button that changes it, except that the
     * default designation has no button to remove it.
     */
    @Test
    fun thePageShowsTheDesignationsOfTheScenarioWithTheirButtons() {
        val api = holding(universal = ScenarioReferenceDto(plans.id, basic.id))
        val browser = browserFor(api)

        val ofGold = browser.get(at("/scenario")).page
        val ofBasic = browser.get(at("/scenario", basic.id)).page

        val textsOfGold =
            listOf(
                "This is the default scenario of the service. In static mode, the service answers every request with it.",
                "This is not the error scenario of the service. $ERROR_ANSWERS",
                "This is not the universal error scenario. $UNIVERSAL_SERVES",
            )
        val textsOfBasic =
            listOf(
                "This is not the default scenario of the service. $DEFAULT_ANSWERS",
                "This is the error scenario of the service. In dynamic mode, it answers every request that no scenario matches.",
                "This is the universal error scenario. It is the error scenario for every service that has none of its own.",
            )
        val buttonsOfGold =
            listOf(
                Triple("/scenario/error?service=svc-plans&id=sc-gold", "yes", "Make it the error scenario"),
                Triple("/scenario/universal-error?service=svc-plans&id=sc-gold", "yes", "Make it the universal error scenario"),
            )
        val buttonsOfBasic =
            listOf(
                Triple("/scenario/default?service=svc-plans&id=sc-basic", "yes", "Make it the default scenario"),
                Triple("/scenario/error?service=svc-plans&id=sc-basic", "no", "Stop using it as the error scenario"),
                Triple("/scenario/universal-error?service=svc-plans&id=sc-basic", "no", STOP_UNIVERSAL),
            )
        assertEquals("Default scenario and error scenarios", ofGold.select("main h2").text())
        val texts = { page: Document -> page.select("main .designation p").map { it.text() } }
        assertEquals(textsOfGold to textsOfBasic, texts(ofGold) to texts(ofBasic))
        assertEquals(buttonsOfGold to buttonsOfBasic, designationButtons(ofGold) to designationButtons(ofBasic))
        assertEquals(setOf("designate"), ofBasic.select("main .designation button").map { it.attr("name") }.toSet())
        val delete = ofGold.select("main > p.actions a").map { it.text() to it.attr("href") }
        assertEquals(listOf("Delete the scenario" to "/scenario/delete?service=svc-plans&id=sc-gold"), delete)
        assertEquals(emptyList(), changing(api))
    }

    /** Each designation button asks the admin API for its change, and the scenario page reports the result. */
    @Test
    fun theDesignationButtonsAssignAndRemoveTheDesignations() {
        val api = holding()
        val browser = browserFor(api)
        val press = { scenarioId: String, label: String ->
            val page = browser.get(at("/scenario", scenarioId)).page
            val sent = browser.submit(page.select("main .designation form").single { it.text() == label } as FormElement)
            Triple(sent.status, sent.header("location"), browser.follow(sent).notice)
        }

        val results =
            listOf(
                press(basic.id, "Make it the default scenario"),
                press(gold.id, "Make it the error scenario"),
                press(gold.id, "Stop using it as the error scenario"),
                press(gold.id, "Make it the universal error scenario"),
                press(gold.id, STOP_UNIVERSAL),
            )

        val ofBasic = at("/scenario", basic.id)
        val ofGold = at("/scenario")
        val expected =
            listOf(
                Triple(303, ofBasic, "The scenario basic is now the default scenario of the service Plans."),
                Triple(303, ofGold, "The scenario gold is now the error scenario of the service Plans."),
                Triple(303, ofGold, "The scenario gold is no longer the error scenario of the service Plans."),
                Triple(303, ofGold, "The scenario gold is now the universal error scenario."),
                Triple(303, ofGold, "The scenario gold is no longer the universal error scenario."),
            )
        val asked =
            listOf(
                "PATCH services/svc-plans" to """{"defaultScenarioId":"sc-basic"}""",
                "PATCH services/svc-plans" to """{"errorScenarioId":"sc-gold"}""",
                "PATCH services/svc-plans" to """{"errorScenarioId":null}""",
                "PUT universal-error-scenario" to """{"serviceId":"svc-plans","scenarioId":"sc-gold"}""",
                "DELETE universal-error-scenario" to "",
            )
        assertEquals<List<Any?>>(expected, results)
        assertEquals(asked, changing(api))
        val now = browser.get(ofBasic).page
        assertEquals(listOf("Make it the error scenario", "Make it the universal error scenario"), designationButtons(now).map { it.third })
        assertEquals(emptyList(), api.violations)
    }

    /**
     * A button only removes a designation from the scenario of its own page. If another scenario took over
     * the designation after the page was rendered, the button doesn't change anything.
     */
    @Test
    fun aStalePageDoesNotRemoveTheDesignationOfAnotherScenario() {
        val api = holding(universal = ScenarioReferenceDto(plans.id, basic.id))
        val browser = browserFor(api)
        val stale = browser.get(at("/scenario", basic.id)).page
        val removing = stale.select("main .designation form").filter { it.text().startsWith("Stop using") }
        val current = browser.get(at("/scenario")).page
        current.select("main .designation form").forEach { browser.submit(it as FormElement) }
        val before = changing(api)

        val notices = removing.map { browser.follow(browser.submit(it as FormElement)).notice }

        val unchanged =
            listOf(
                "The scenario basic was not the error scenario of the service Plans. Nothing was changed.",
                "The scenario basic was not the universal error scenario. Nothing was changed.",
            )
        assertEquals(2 to 2, removing.size to before.size)
        assertEquals(unchanged, notices)
        assertEquals(before, changing(api))
        val ofGold = designationButtons(browser.get(at("/scenario")).page)
        assertEquals(listOf("no", "no"), ofGold.map { it.second })
    }

    /** Scenarios of different services can share an identifier. A designation of one of them doesn't apply to the other. */
    @Test
    fun aDesignationAppliesOnlyToTheScenarioOfItsService() {
        val twin = plans.copy(id = "svc-twin", name = "Twin", mockUrl = "twin", errorScenarioId = null)
        val api = holding(listOf(plans, twin), universal = ScenarioReferenceDto(twin.id, gold.id))
        val browser = browserFor(api)

        val ofPlans = browser.get(at("/scenario")).page
        val ofTwin = browser.get(at("/scenario", gold.id, twin.id)).page
        val marks =
            listOf(plans.id, twin.id).map { serviceId ->
                val listed = browser.get("/service?id=$serviceId").page.select("main table.scenarios .mark")
                listed.map { it.text() }
            }
        val asked = browser.get(at("/scenario/delete")).page
        val consequences = asked.select("main > p").map { it.text() }
        val token = ofPlans.select("main > form [name=_token]").attr("value")
        val removed = browser.post(at("/scenario/universal-error"), listOf("_token" to token, "designate" to "no"))
        val notice = browser.follow(removed).notice

        val lastButtons = listOf(ofPlans, ofTwin).map { designationButtons(it).last().third }
        assertEquals(listOf("Make it the universal error scenario", STOP_UNIVERSAL), lastButtons)
        assertEquals(listOf(listOf("error scenario", "default"), listOf("default", "universal error scenario")), marks)
        assertEquals(2, consequences.size)
        assertEquals("The scenario gold was not the universal error scenario. Nothing was changed.", notice)
        assertEquals(emptyList(), changing(api))
    }

    /** A designation request that doesn't say whether to designate the scenario is rejected, and nothing changes. */
    @Test
    fun aDesignationRequestWithoutAChoiceIsRejected() {
        val api = holding()
        val browser = browserFor(api)
        val token = scenarioForm(browser.get(at("/scenario"))).select("[name=_token]").attr("value")
        val forms = listOf(listOf("_token" to token), listOf("_token" to token, "designate" to "maybe"))

        val answers = listOf("/scenario/error", "/scenario/universal-error").flatMap { path -> forms.map { browser.post(at(path), it) } }

        assertEquals(answers.map { 422 }, answers.map { it.status })
        val said = answers.map { it.page.select("main .problem").text() }.toSet()
        assertEquals(setOf("The form does not say whether to assign the designation or to remove it."), said)
        assertEquals(emptyList(), changing(api))
    }

    // Deleting a scenario

    /**
     * The requirement "Confirmation of destructive actions" for a scenario: the link leads to a page that
     * asks for confirmation, nothing is deleted until the form on that page is submitted, and the service
     * page then reports the deletion.
     */
    @Test
    fun theUiAsksForConfirmationBeforeItDeletesAScenario() {
        val api = holding()
        val browser = browserFor(api)

        val asked = browser.get(at("/scenario/delete", ruled.id))
        val token = asked.form().select("[name=_token]").attr("value")
        val notConfirmed = browser.post(at("/scenario/delete", ruled.id), listOf("_token" to token))
        val before = changing(api)
        val sent = browser.submit(asked.form())
        val after = browser.follow(sent)

        assertEquals(200 to "Delete the scenario ruled?", asked.status to asked.page.select("main h1").text())
        assertEquals(listOf("The scenario belongs to the service Plans."), asked.page.select("main > p").map { it.text() })
        val button = asked.form().select("button").single()
        assertEquals("Delete the scenario" to setOf("danger"), button.text() to button.classNames())
        val cancel = asked.page.select("main form a").map { it.text() to it.attr("href") }
        assertEquals(listOf("Cancel" to "/scenario?service=svc-plans&id=sc-ruled"), cancel)
        assertEquals(200 to "Delete the scenario ruled?", notConfirmed.status to notConfirmed.page.select("main h1").text())
        assertEquals(emptyList(), before)
        assertEquals(303 to "/service?id=svc-plans", sent.status to sent.header("location"))
        assertEquals("The scenario ruled was deleted.", after.notice)
        assertEquals(listOf("basic", "gold"), after.page.select("main table.scenarios tbody th a").map { it.text() })
        assertEquals(listOf("DELETE services/svc-plans/scenarios/sc-ruled" to ""), changing(api))
        assertEquals(emptyList(), api.violations)
    }

    /** The confirmation page lists the designations that go away with the scenario. */
    @Test
    fun theConfirmationPageListsTheDesignationsThatEndWithTheScenario() {
        val single = plans.copy(id = "svc-single", name = "Single", mockUrl = "single", scenarios = listOf(gold), errorScenarioId = null)
        val api = holding(listOf(plans, single), universal = ScenarioReferenceDto(plans.id, basic.id))
        val browser = browserFor(api)

        val consequences = { scenarioId: String, serviceId: String ->
            val asked = browser.get(at("/scenario/delete", scenarioId, serviceId)).page
            asked.select("main > p").map { it.text() }
        }

        val ofDefault = "It is the default scenario of the service. The oldest remaining scenario becomes the default scenario."
        val ofError = "It is the error scenario of the service. The service will have no error scenario."
        val ofUniversal = "It is the universal error scenario. The server will have no universal error scenario."
        val ofOnly = "It is the only scenario of the service. A service without a scenario forwards every request."
        assertEquals(listOf("The scenario belongs to the service Plans.", ofDefault), consequences(gold.id, plans.id))
        assertEquals(listOf("The scenario belongs to the service Plans.", ofError, ofUniversal), consequences(basic.id, plans.id))
        assertEquals(listOf("The scenario belongs to the service Single.", ofOnly), consequences(gold.id, single.id))
    }

    // Addresses

    /** The pages of a scenario or a service that doesn't exist say so, and nothing changes. */
    @Test
    fun thePagesOfAMissingScenarioReportThatItDoesNotExist() {
        val api = holding()
        val browser = browserFor(api)
        val token = scenarioForm(browser.get(at("/scenario"))).select("[name=_token]").attr("value")
        val noScenario = "The service 'Plans' has no scenario with the identifier sc-gone."
        val noService = "No service has the identifier svc-gone."
        for (method in listOf("GET", "PATCH", "DELETE")) api.on(method, "services/svc-plans/scenarios/sc-gone") { problem(404, noScenario) }
        api.on("GET", "services/svc-gone") { problem(404, noService) }
        api.on("POST", "services/svc-gone/scenarios") { problem(404, noService) }
        val fields = listOf("_token" to token, "confirmed" to "yes", "designate" to "yes", "name" to "x", "status" to "200")

        val paths = listOf("/scenario", "/scenario/delete", "/scenario/default", "/scenario/error", "/scenario/universal-error")
        val pages = listOf("/scenario", "/scenario/delete").map { browser.get(at(it, "sc-gone")) }
        val forms = paths.map { browser.post(at(it, "sc-gone"), fields) }
        val ofNoService = listOf(browser.get("/scenario/new?service=svc-gone"), browser.post("/scenario/new?service=svc-gone", fields))
        val incomplete = listOf("/scenario", "/scenario?service=svc-plans", "/scenario?id=sc-gold", "/scenario/new", "/scenario/delete")

        for (answer in pages + forms) {
            assertEquals(404 to "Not there", answer.status to answer.page.select("main h1").text())
            assertTrue(noScenario in answer.page.select("main .problem").text())
        }
        for (answer in ofNoService) {
            assertEquals(404 to "Not there", answer.status to answer.page.select("main h1").text())
            assertTrue(noService in answer.page.select("main .problem").text())
        }
        assertEquals(incomplete.map { 404 }, incomplete.map { browser.get(it).status })
        val lines = listOf("PATCH services/svc-plans/scenarios/sc-gone", "POST services/svc-gone/scenarios")
        assertEquals(lines, changing(api).map { it.first })
    }

    /** The scenario pages work with any identifier, because identifiers are passed as address parameters. */
    @Test
    fun thePagesOfAScenarioWorkWithAnyIdentifier() {
        val ids = mapOf("a/b c" to "a%2Fb%20c", ".." to "..", "x?y#z&w=1" to "x%3Fy%23z%26w%3D1")
        val services =
            ids.keys.mapIndexed { index, id ->
                val scenarios = listOf(gold.copy(id = id), basic.copy(id = "$id-other"))
                val service = plans.copy(id = id, name = "Service $index", mockUrl = "s$index", scenarios = scenarios)
                service.copy(defaultScenarioId = id, errorScenarioId = null)
            }
        val api = holding(services, paths = ids)
        val browser = browserFor(api)

        for ((id, written) in ids) {
            val page = browser.get(at("/scenario", id, id))
            val saved = browser.submit(scenarioForm(page), mapOf("delay" to "1"))
            val designation = page.page.select("main .designation form").first() as FormElement
            val designated = browser.submit(designation)
            val asked = browser.get(page.page.select("main > p.actions a").attr("href"))
            val deleted = browser.submit(asked.form())

            val leading = page.page.select("main [href], main [action]") + asked.page.select("main [href], main [action]")
            val addresses = leading.map { it.attr("href") + it.attr("action") }
            assertEquals(emptyList(), addresses.filterNot(Hostile.SAFE_ADDRESS::matches), id)
            assertEquals(listOf(200, 303, 303, 200, 303), listOf(page, saved, designated, asked, deleted).map { it.status }, id)
            val locations = saved.header("location") to deleted.header("location")
            assertEquals(at("/scenario", id, id) to Address.of("/service", "id" to id), locations, id)
            val ofScenario = "services/$written/scenarios/$written"
            val lines = listOf("PATCH $ofScenario", "PATCH services/$written", "DELETE $ofScenario")
            assertEquals(lines, changing(api).takeLast(lines.size).map { it.first }, id)
        }
        assertEquals(emptyList(), api.violations)
    }

    // Stored content is text

    /**
     * The requirement "Stored content is shown as text" for the scenario pages: the form fields hold the
     * stored texts exactly, and the headings show the scenario name as plain text.
     */
    @Test
    fun thePagesOfAScenarioShowStoredContentAsText() {
        val api = FakeAdminApi().also { fakes += it }
        val browser = browserFor(api)

        for (text in Hostile.MARKUP + Hostile.ADDRESSES) {
            val criterion = MatchCriterionDto.Text(text)
            val scenario = ScenarioDto(text, text, text, 200, listOf(HeaderDto(text, text)), criterion, text, 0, listOf(text), text)
            val service = plans.copy(id = text, name = text, scenarios = listOf(scenario), defaultScenarioId = text, errorScenarioId = text)
            api.hold(listOf(service), paths = mapOf(text to Hostile.written(text)), universal = ScenarioReferenceDto(text, text))

            val page = browser.get(at("/scenario", text, text))
            val asked = browser.get(at("/scenario/delete", text, text)).page

            val fields = shown(scenarioForm(page))
            // A one-line field holds its value without line breaks, just like in a browser.
            val inOneLine = text.replace("\n", "")
            assertEquals(listOf(inOneLine), listOf("name", "tags").flatMap(fields::getValue).distinct(), text)
            assertEquals(listOf(text), listOf("body", "criterion", "method").flatMap(fields::getValue).distinct(), text)
            assertEquals(listOf("$text: $text"), fields["headers"], text)
            val headings = listOf(page.page, asked).map { it.select("main h1").first()!!.wholeText() }
            assertEquals(listOf("Scenario $text", "Delete the scenario $text?"), headings, text)
            val ofService: Element = page.page.select("main > p a").first()!!
            assertEquals(text to Address.of("/service", "id" to text), ofService.wholeText() to ofService.attr("href"), text)
            assertEquals("The scenario belongs to the service $text.", asked.select("main > p").first()!!.wholeText(), text)
            assertEquals(emptyList(), Hostile.moreThanText(page), text)
        }
        assertEquals(emptyList(), api.violations)
    }

    private companion object {
        /** The attributes of a scenario. The form for a new scenario sends all of them. */
        val ATTRIBUTES =
            setOf("name", "responseBody", "statusCode", "headers", "matchCriterion", "methodRestriction", "delayMillis", "tags")

        const val RULES_LABEL = "The match criterion is a rule set"
        const val LOCATION = """{"name":"Location","value":"http://example.com/a:b"}"""
        const val UNIVERSAL_SERVES = "The universal error scenario is the error scenario for every service that has none of its own."
        const val ERROR_ANSWERS = "In dynamic mode, the error scenario answers every request that no scenario matches."
        const val DEFAULT_ANSWERS = "In static mode, the service answers every request with its default scenario."
        const val STOP_UNIVERSAL = "Stop using it as the universal error scenario"
    }
}
