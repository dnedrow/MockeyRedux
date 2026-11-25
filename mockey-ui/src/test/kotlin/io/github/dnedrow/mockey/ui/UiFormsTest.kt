package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.done
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.json
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 13.3: what the forms of the UI are made with: the token that keeps another site from sending one, the
 * page that the browser is sent to after a form, the notice and the report of what was done, the form that
 * is shown again with what was sent, and the page that asks before something is deleted.
 *
 * The pages of these tests are those of [SampleArea], which uses all of it as the areas of the UI do. The
 * server is a stand-in, see [FakeAdminApi], and a test sends a form as a browser sends it, see [Browser].
 */
class UiFormsTest {
    private val fakes = mutableListOf<FakeAdminApi>()
    private val uis = mutableListOf<MockeyUi>()

    @AfterTest
    fun stopEverything() {
        uis.forEach(MockeyUi::close)
        fakes.forEach(FakeAdminApi::close)
    }

    /** Starts a stand-in for a server that takes the service of the form, and that has that service then. */
    private fun fake(): FakeAdminApi {
        val api = FakeAdminApi().also { fakes += it }
        api.on("POST", "services") { json(Samples.customers, 201) }
        api.on("GET", "services/svc-customers") { json(Samples.customers) }
        api.on("DELETE", "services/svc-customers") { done() }
        return api
    }

    /** Starts a UI with the pages of [SampleArea] that manages [api], on [port]. */
    private fun uiFor(
        api: FakeAdminApi,
        port: Int = 0,
        formLimit: Long = FormReader.LIMIT,
        answerTimeout: Duration = Duration.ofSeconds(30),
    ): MockeyUi {
        val settings = UiSettings(port = port, adminUrl = AdminUrl.of(api.url), logLevel = LogLevel.WARN)
        val clientOf = { told: UiSettings -> AdminClient(told.adminUrl, told.adminToken, Duration.ofSeconds(5), answerTimeout) }
        return MockeyUi.start(settings, clientOf, listOf(SampleArea), formLimit).also { uis += it }
    }

    private fun tokenOf(page: Browser.Response): String = page.form().select("[name=_token]").attr("value")

    /** What a person enters into the form of a service that the server takes. */
    private val entered = mapOf("name" to "Customers", "mockUrl" to "customer/{id}", "tags" to "team-a nightly", "delay" to "5")

    private fun changing(api: FakeAdminApi): List<String> = api.requests.filter { it.method != "GET" }.map { it.line }

    // A form that the UI takes

    /**
     * The scenario "Reload after saving": the UI does what a form of one of its pages asks for and sends the
     * browser to a page that says so. Loading that page again says nothing more, and changes nothing.
     */
    @Test
    fun aFormOfAPageIsDoneAndTheBrowserIsSentToAPageThatSaysSoOnce() {
        val api = fake()
        val browser = Browser(uiFor(api))

        val page = browser.get(SampleArea.NEW)
        val sent = browser.submit(page.form(), entered)
        val shown = browser.follow(sent)
        val again = browser.follow(sent)

        assertEquals(Triple(303, "/sample/service?id=svc-customers", ""), Triple(sent.status, sent.header("location"), sent.body))
        assertEquals(200 to "The service Customers was created.", shown.status to shown.notice)
        assertEquals("status", shown.page.select("main .notice").attr("role"))
        assertEquals(200 to null, again.status to again.notice)
        val asked =
            """{"name":"Customers","mockUrl":"customer/{id}","description":"","tags":["team-a","nightly"],""" +
                """"responseMode":"static","delayMillis":5,"requestRulesEnabled":false}"""
        assertEquals(listOf("POST services" to asked), api.requests.filter { it.method == "POST" }.map { it.line to it.body })
        assertEquals(listOf("POST services", "GET services/svc-customers", "GET services/svc-customers"), api.calls)
        assertEquals(emptyList(), api.violations)
    }

    /** A browser gets a value of its own in a cookie that no page reads, and every form of a page holds the token for that value. */
    @Test
    fun aBrowserGetsItsValueOnceAndEveryFormHoldsTheTokenForIt() {
        val ui = uiFor(fake())
        val browser = Browser(ui)
        val other = Browser(ui)

        val first = browser.get(SampleArea.NEW)
        val second = browser.get("/sample/plans")
        val ofOther = other.get(SampleArea.NEW)

        val cookie = first.header("set-cookie")!!
        assertTrue(Regex("mockey-ui-${ui.address.port}-form=[A-Za-z0-9_-]{24}; Path=/; HttpOnly; SameSite=Lax").matches(cookie), cookie)
        assertNull(second.headers["set-cookie"])
        val tokens = second.page.select("main form [name=_token]").map { it.attr("value") }
        assertEquals(listOf(tokenOf(first), tokenOf(first)), tokens)
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(tokenOf(first)), tokenOf(first))
        assertNotEquals(tokenOf(first), tokenOf(ofOther))
        assertFalse(browser.cookies.values.single() in first.body, "The page holds the token, and not the value of the browser.")
        assertEquals(listOf("post", "post"), second.page.select("main form").map { it.attr("method") })
    }

    // A form that the UI does not take

    /**
     * The scenario "Cross-site form post": a form that does not come from a page of the UI in the browser
     * that the page was loaded in is refused, whatever it holds, and the server is asked nothing for it.
     */
    @Test
    fun aFormThatIsNotOfAPageOfTheUiInThisBrowserIsRefusedAndTheServerIsAskedNothing() {
        val api = fake()
        val ui = uiFor(api)
        val (browser, other, stranger) = List(3) { Browser(ui) }
        val page = browser.get(SampleArea.NEW)
        val token = tokenOf(page)
        val ofOther = tokenOf(other.get(SampleArea.NEW))
        val (cookie, value) = browser.cookies.entries.single()
        val almost = token.dropLast(1) + if (token.endsWith("A")) "E" else "A"
        val before = api.requests.size
        val fields = entered.map { (name, text) -> name to text }
        val made = "A".repeat(VALUE_LENGTH)
        val delete = "/sample/service/delete?id=svc-customers"

        val forged =
            listOf(
                stranger.post("/sample/services", fields),
                stranger.post("/sample/services", fields + ("_token" to token)),
                stranger.post("/sample/services", fields + ("_token" to made), mapOf("Cookie" to "$cookie=$made")),
                browser.post("/sample/services", fields),
                browser.post("/sample/services", fields + ("_token" to "")),
                browser.post("/sample/services", fields + ("_token" to ofOther)),
                browser.post("/sample/services", fields + ("_token" to value)),
                browser.post("/sample/services", fields + ("_token" to almost)),
                stranger.post(delete, listOf("confirmed" to "yes")),
                browser.post(delete, listOf("confirmed" to "yes", "_token" to ofOther)),
                // What a request without the value of a browser holds is not looked at, so it need not be a form.
                stranger.request("POST", "/sample/services", body = "_token=$token".toByteArray(), contentType = "text/plain"),
            )

        for (answer in forged) {
            assertEquals(403 to "The form was not taken", answer.status to answer.page.select("main h1").text())
            val problem = answer.page.select("main .problem").text()
            assertTrue(problem.endsWith("Nothing was changed."), answer.body)
            val foot = answer.page.select("body > footer p")
            assertEquals("Server at ${api.url}", foot.first()!!.text())
        }
        assertEquals(before, api.requests.size)
        assertEquals(303, browser.submit(page.form(), entered).status)
    }

    /**
     * A UI that is started anew has another key, so it takes the forms of its own pages only. The browser
     * keeps its value, and a page that is loaded again has a form that the UI takes.
     */
    @Test
    fun aUiThatIsStartedAnewTakesTheFormsOfItsOwnPagesOnly() {
        val api = fake()
        val first = uiFor(api)
        val before = Browser(first)
        val old = before.get(SampleArea.NEW)
        first.close()
        val after = Browser(uiFor(api, first.address.port))
        after.cookies += before.cookies

        val refused = after.submit(old.form(), entered)
        val loadedAgain = after.get(SampleArea.NEW)
        val taken = after.submit(loadedAgain.form(), entered)

        assertEquals(403 to "The form was not taken", refused.status to refused.page.select("main h1").text())
        assertTrue("when the UI was started anew after the page of the form was loaded" in refused.page.select("main").text())
        assertNull(loadedAgain.headers["set-cookie"])
        assertNotEquals(tokenOf(old), tokenOf(loadedAgain))
        assertEquals(303, taken.status)
        assertEquals(listOf("POST services"), changing(api))
    }

    /** The UI takes the requests of a browser that asks for a page or sends a form, and no other. */
    @Test
    fun aRequestThatIsNeitherForAPageNorAFormIsRefused() {
        val api = fake()
        val browser = Browser(uiFor(api))
        val token = tokenOf(browser.get(SampleArea.NEW))
        val before = api.requests.size

        val others = listOf("PUT", "DELETE", "PATCH", "OPTIONS").map { browser.request(it, "/sample/service?id=svc-customers") }
        val nowhere = browser.post("/system", listOf("_token" to token))

        for (answer in others) {
            assertEquals(405 to "GET, HEAD, POST", answer.status to answer.header("allow"))
            assertEquals("Not a request for a page", answer.page.select("main h1").text())
        }
        assertEquals(404 to "Not there", nowhere.status to nowhere.page.select("main h1").text())
        assertEquals(before, api.requests.size)
    }

    // A form that is not valid

    /**
     * The scenario "Duplicate mock URL": a form that the server does not take is shown again with everything
     * that was entered, and with every message of the server next to the field that it concerns.
     */
    @Test
    fun aFormThatTheServerDoesNotTakeIsShownAgainAsItWasSentWithEveryMessageAtItsField() {
        val api = fake()
        val errors =
            listOf(
                FieldErrorDto("mockUrl", "The service 'Clients' already uses the mock URL pattern 'customer/{id}'."),
                FieldErrorDto("tags[1]", "A tag has no <spaces>."),
                FieldErrorDto("responseMode", "The mode needs a scenario."),
                FieldErrorDto("requestRulesEnabled", "The service has no request rules."),
                FieldErrorDto("realUrls", "The service 'Clients' already lists the real URL http://qa1.example.com."),
            )
        api.on("POST", "services") { problem(422, "The request is not valid.", errors) }
        val browser = Browser(uiFor(api))
        val lines = "\nfirst line\n\n  third \"line\" & more\n"
        val typed =
            mapOf(
                "name" to "Customers & <b>Co</b>",
                "mockUrl" to "customer/{id}",
                "description" to lines,
                "tags" to "team-a two words",
                "mode" to "dynamic",
                "delay" to "250",
                "rules" to "on",
                "verbose" to null,
            )

        val answer = browser.submit(browser.get(SampleArea.NEW).form(), typed)

        val form = answer.form()
        val shown = listOf("name", "mockUrl", "tags", "delay").associateWith { form.select("[name=$it]").attr("value") }
        assertEquals(422 to "New service", answer.status to answer.page.select("main h1").text())
        assertEquals(typed.filterKeys { it in shown }, shown)
        assertEquals(lines, form.select("[name=description]").first()!!.wholeText())
        assertEquals(listOf("dynamic"), form.select("[name=mode] option[selected]").map { it.attr("value") })
        assertEquals(true to false, form.select("[name=rules]").hasAttr("checked") to form.select("[name=verbose]").hasAttr("checked"))
        val atTheFields =
            mapOf(
                "mockUrl" to listOf("The service 'Clients' already uses the mock URL pattern 'customer/{id}'."),
                "tags" to listOf("A tag has no <spaces>."),
                "mode" to listOf("The mode needs a scenario."),
                "rules" to listOf("The service has no request rules."),
            )
        assertEquals(atTheFields, answer.fieldProblems)
        val others = form.select("> .field-problem p").map { it.text() }
        assertEquals(listOf("realUrls: The service 'Clients' already lists the real URL http://qa1.example.com."), others)
        val head = form.select(".problem[role=alert]").text()
        assertEquals("Nothing was changed: the form holds what the server does not take. See below.", head)
        assertEquals(0, answer.page.select("main b, main spaces").size)
    }

    /** A field with a message says so to a program that reads the page to a person, and names the element with the message. */
    @Test
    fun aFieldSaysThatItIsNotValidAndWhereItsMessageAndItsHintAre() {
        val api = fake()
        api.on("POST", "services") { problem(422, "The request is not valid.", listOf(FieldErrorDto("name", "A service has a name."))) }
        val browser = Browser(uiFor(api))

        val fresh = browser.get(SampleArea.NEW)
        val answer = browser.submit(fresh.form(), mapOf("mockUrl" to "customer/{id}"))

        val before = fresh.form().select("[name=name]").first()!!
        val name = answer.form().select("[name=name]").first()!!
        val mockUrl = answer.form().select("[name=mockUrl]").first()!!
        assertEquals("f-name-hint" to "", before.attr("aria-describedby") to before.attr("aria-invalid"))
        assertEquals("f-name-hint f-name-problem" to "true", name.attr("aria-describedby") to name.attr("aria-invalid"))
        assertEquals("" to "", mockUrl.attr("aria-describedby") to mockUrl.attr("aria-invalid"))
        assertEquals("What the pages call the service.", answer.page.select("#f-name-hint").text())
        assertEquals("A service has a name.", answer.page.select("#f-name-problem").text())
        assertEquals("f-name" to "Name", name.id() to answer.page.select("label[for=f-name]").text())
        assertEquals(null, fresh.page.select("main .problem").first())
    }

    /** What was entered goes to the server as it was entered: a form that is shown again and sent again asks for the same. */
    @Test
    fun aFormThatIsShownAgainAndSentAgainAsksTheServerForWhatWasEntered() {
        val api = fake()
        api.on("POST", "services") { problem(422, "The request is not valid.", listOf(FieldErrorDto("mockUrl", "Taken."))) }
        val browser = Browser(uiFor(api))
        val lines = "\nfirst line\n\n  third \"line\" \u00e4\u2713\n"

        val again = browser.submit(browser.get(SampleArea.NEW).form(), entered + ("description" to lines) + ("rules" to "on"))
        api.on("POST", "services") { json(Samples.customers, 201) }
        val taken = browser.submit(again.form(), mapOf("mockUrl" to "client/{id}"))

        val (refused, done) = api.requests.filter { it.method == "POST" }.map { it.body }
        val escaped = lines.replace("\n", "\\n").replace("\"", "\\\"")
        val asked =
            """{"name":"Customers","mockUrl":"client/{id}","description":"$escaped","tags":["team-a","nightly"],""" +
                """"responseMode":"static","delayMillis":5,"requestRulesEnabled":true}"""
        assertEquals(303, taken.status)
        assertEquals(asked, done)
        assertEquals(asked.replace("client/{id}", "customer/{id}"), refused)
        assertEquals(emptyList(), api.violations)
    }

    /**
     * What the UI itself does not take of a form is shown as what the server does not take, and the server
     * is not asked to change anything.
     */
    @Test
    fun whatTheUiItselfDoesNotTakeIsShownAtItsFieldAndTheServerIsNotAskedToChangeAnything() {
        val api = fake()
        val browser = Browser(uiFor(api))

        val answer = browser.submit(browser.get(SampleArea.NEW).form(), entered + ("delay" to "soon"))

        assertEquals(422, answer.status)
        assertEquals(mapOf("delay" to listOf("The delay is a number of milliseconds.")), answer.fieldProblems)
        val form = answer.form()
        assertEquals("soon" to "Customers", form.select("[name=delay]").attr("value") to form.select("[name=name]").attr("value"))
        assertEquals(emptyList(), changing(api))
    }

    // Asking first

    /**
     * The requirement "Confirmation of destructive actions": a link leads to a page that asks, and nothing is
     * deleted before the form of that page is sent. The step is a page and a form, and needs no scripting.
     */
    @Test
    fun whatDeletesIsAskedAboutAndDoneOnlyForTheFormOfThePageThatAsks() {
        val api = fake()
        val browser = Browser(uiFor(api))
        val path = "/sample/service/delete?id=svc-customers"

        val service = browser.get("/sample/service?id=svc-customers")
        val link = service.page.select("main a").attr("href")
        val asked = browser.get(link)
        val notAsked = browser.post(path, listOf("_token" to tokenOf(asked)))
        val deletedBefore = changing(api)
        val confirmed = browser.submit(asked.form())
        val after = browser.follow(confirmed)

        assertEquals(path, link)
        for (answer in listOf(asked, notAsked)) {
            assertEquals(200 to "Delete the service Customers?", answer.status to answer.page.select("main h1").text())
            val consequences = answer.page.select("main > p").map { it.text() }
            assertEquals(listOf("The service has 2 scenarios, which are deleted with it."), consequences)
            val button = answer.form().select("button")
            assertEquals("Delete the service" to "danger", button.text() to button.attr("class"))
            assertEquals(path to "yes", answer.form().attr("action") to answer.form().select("[name=confirmed]").attr("value"))
            assertEquals(listOf("Cancel -> /sample/services/new"), answer.form().select("a").map { "${it.text()} -> ${it.attr("href")}" })
            assertEquals(0, answer.page.select("script, [onclick], [onsubmit]").size)
        }
        assertEquals(emptyList(), deletedBefore)
        assertEquals(303 to SampleArea.NEW, confirmed.status to confirmed.header("location"))
        assertEquals("The service was deleted.", after.notice)
        assertEquals(listOf("DELETE services/svc-customers"), changing(api))
    }

    // Reports

    /**
     * The scenario "Plan apply report": the report that the server gives for a form is shown on a page of
     * its own. Loading that page again shows the report again, and applies no plan again.
     */
    @Test
    fun aReportOfTheServerIsShownOnAPageOfItsOwnThatCanBeLoadedAgain() {
        val api = fake()
        api.on("POST", "plans/plan-night/apply") { json(Samples.applyReport) }
        val ui = uiFor(api)
        val browser = Browser(ui)

        val sent = browser.submit(browser.get("/sample/plans").form("main form[action*=apply]"))
        val address = sent.header("location")!!
        val shown = listOf(browser.follow(sent), browser.follow(sent), Browser(ui).get(address))
        val unknown = browser.get("/reports/${"A".repeat(VALUE_LENGTH)}")

        assertEquals(303, sent.status)
        assertTrue(Regex("/reports/[A-Za-z0-9_-]{24}").matches(address), address)
        for (answer in shown) {
            assertEquals(200 to "The plan Night was applied", answer.status to answer.page.select("main h1").text())
            assertEquals(listOf("Gone"), answer.page.select("main li").map { it.text() })
            assertEquals("no-store", answer.header("cache-control"))
        }
        assertEquals(404 to "The report is gone", unknown.status to unknown.page.select("main h1").text())
        assertEquals(listOf("POST plans/plan-night/apply"), changing(api))
    }

    /** The UI keeps a report itself, so it shows the report whatever the server says, and says what is the matter with the server. */
    @Test
    fun aReportIsShownAlsoWhenTheServerIsGone() {
        val api = fake()
        api.on("POST", "plans/plan-night/apply") { json(Samples.applyReport) }
        val browser = Browser(uiFor(api))
        val sent = browser.submit(browser.get("/sample/plans").form("main form[action*=apply]"))
        api.close()

        val answer = browser.follow(sent)

        assertEquals(200 to "The plan Night was applied", answer.status to answer.page.select("main h1").text())
        assertEquals("The UI cannot reach the server at ${api.url}. More about it.", answer.page.select("main > .problem").text())
        assertEquals(listOf("/system"), answer.page.select("main > .problem a").map { it.attr("href") })
        val foot = answer.page.select("body > footer p")
        assertEquals("Server at ${api.url}", foot.first()!!.text())
    }

    // Files

    /** A form with a file is sent in parts. The handler gets the file with its name and its bytes as they are, and the fields beside it. */
    @Test
    fun aFormWithAFileComesWithTheBytesOfTheFile() {
        val api = fake()
        api.on("POST", "definitions/import") { json(Samples.importReport) }
        val browser = Browser(uiFor(api))
        val document = "<mockservice name=\"K\u00fcche\"/>\r\n".toByteArray(Charsets.ISO_8859_1)
        val form = browser.get("/sample/plans").form("main form[action$=import]")

        val sent = browser.submit(form, mapOf("tags" to "imported legacy"), mapOf("document" to Browser.Chosen("export.xml", document)))
        val report = browser.follow(sent)
        val withoutFile = browser.submit(form, mapOf("tags" to "imported"))

        val imported = api.requests.single { it.method == "POST" }
        assertEquals("multipart/form-data", form.attr("enctype"))
        assertEquals(303 to "Imported export.xml", sent.status to report.page.select("main h1").text())
        val asked = "POST definitions/import?tag=imported&tag=legacy"
        assertEquals(asked to "application/xml", imported.line to imported.header("content-type"))
        assertEquals(String(document, Charsets.UTF_8), imported.body)
        assertEquals(422 to mapOf("document" to listOf("No file was chosen.")), withoutFile.status to withoutFile.fieldProblems)
        assertEquals("imported", withoutFile.form("main form[action$=import]").select("[name=tags]").attr("value"))
    }

    /**
     * A request whose form the UI cannot read is refused before anything else happens: what is no form, what
     * is too long, and what is not written as a form.
     */
    @Test
    fun aFormThatCannotBeReadIsRefused() {
        val api = fake()
        val browser = Browser(uiFor(api, formLimit = SMALL_LIMIT))
        val token = tokenOf(browser.get(SampleArea.NEW))
        val before = api.requests.size
        val long = "x".repeat(SMALL_LIMIT.toInt())
        val chunked = "${Integer.toHexString(long.length + LEAD.length)}\r\n$LEAD$long\r\n0\r\n\r\n"
        val ofTheBrowser = listOf("Host: ${browser.address.removePrefix("http://")}", "Cookie: ${browser.cookies.entries.single()}")
        val form = "application/x-www-form-urlencoded"

        val json = """{"_token":"$token"}""".toByteArray()
        val noForm = browser.request("POST", "/sample/services", body = json, contentType = "application/json")
        val asText = browser.request("POST", "/sample/services", body = "_token=$token".toByteArray(), contentType = "text/plain")
        val tooLong = browser.post("/sample/services", listOf("_token" to token, "description" to long))
        val ofChunks = ofTheBrowser + "Content-Type: $form" + "Transfer-Encoding: chunked"
        val inChunks = browser.raw("/sample/services", ofChunks, "POST", chunked, statesLength = false)
        val inParts = browser.submit(browser.get("/sample/plans").form("main form[action$=import]"), mapOf("tags" to long))
        val part = "--b\r\nContent-Disposition: form-data; name=\"tags\"\r\n\r\n$long and more\r\n--b--\r\n"
        val partsInChunks = "${Integer.toHexString(part.length)}\r\n$part\r\n0\r\n\r\n"
        val ofParts = ofTheBrowser + "Content-Type: multipart/form-data; boundary=b" + "Transfer-Encoding: chunked"
        val inPartsInChunks = browser.raw("/sample/import", ofParts, "POST", partsInChunks, statesLength = false)
        // A request that states a length beyond the limit is answered before its body has arrived.
        val stated = ofTheBrowser + "Content-Type: $form" + "Content-Length: 5000000000"
        val notRead = browser.raw("/sample/services", stated, "POST", "_token=$token&name=", statesLength = false)
        val notAForm = browser.request("POST", "/sample/services", body = "_token=$token&name=%zz".toByteArray(), contentType = form)

        val limit = "The form is longer than $SMALL_LIMIT bytes, which is the most that the UI takes. Nothing was changed."
        val expected =
            listOf(
                noForm to (415 to "The request holds no form. A browser sends a form of a page as one. Nothing was changed."),
                asText to (415 to "The request holds no form. A browser sends a form of a page as one. Nothing was changed."),
                tooLong to (413 to limit),
                inChunks to (413 to limit),
                inParts to (413 to limit),
                inPartsInChunks to (413 to limit),
                notRead to (413 to limit),
                notAForm to (400 to "The form of the request is not written as a browser writes a form. Nothing was changed."),
            )
        for ((answer, said) in expected) {
            assertEquals(said, answer.status to answer.page.select("main .problem").text())
            assertEquals("The form cannot be read", answer.page.select("main h1").text())
        }
        assertEquals(before + 1, api.requests.size)
    }

    // Notices

    /** A notice says what the UI wrote, as text. A browser cannot make the UI say something else. */
    @Test
    fun aNoticeIsShownAsTextAndOnlyWhenTheUiWroteIt() {
        val api = fake()
        api.on("POST", "services") { json(Samples.customers.copy(name = "<b>Bold</b> & ${"long ".repeat(LONG)}"), 201) }
        val ui = uiFor(api)
        val browser = Browser(ui)
        val name = "mockey-ui-${ui.address.port}-notice"

        val sent = browser.submit(browser.get(SampleArea.NEW).form(), entered)
        val written = browser.cookies.getValue(name)
        val shown = browser.follow(sent)
        browser.cookies[name] = written.replaceFirst(written.take(4), "QUJD")
        val changed = browser.get(SampleArea.NEW)
        browser.cookies[name] = written
        val handedBack = browser.get(SampleArea.NEW)
        val (cookieOfForms, value) = browser.cookies.entries.single { it.key.endsWith("-form") }
        browser.cookies[name] = "$value.${tokenOf(handedBack)}"
        val forAnotherUse = browser.get(SampleArea.NEW)

        val cookie = sent.headers["set-cookie"]!!.single { it.startsWith(name) }
        assertTrue(cookie.endsWith("; Max-Age=60; Path=/; HttpOnly; SameSite=Lax"), cookie)
        assertTrue(shown.notice!!.startsWith("The service <b>Bold</b> & long long"), shown.notice)
        assertEquals(NOTICE_LENGTH to 0, shown.notice!!.length to shown.page.select("main b").size)
        assertEquals(listOf("$name=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax"), shown.headers["set-cookie"])
        assertEquals(null to listOf("$name=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax"), changed.notice to changed.headers["set-cookie"])
        assertEquals(shown.notice, handedBack.notice)
        assertEquals(null to "${cookieOfForms.dropLast(FORM.length)}notice", forAnotherUse.notice to name)
        assertFalse(name in browser.cookies)
    }

    // A server that does not do what a form asks for

    /**
     * When the server does not do what a form asks for, the page says what the UI knows of whether something
     * was changed, and it offers no link that sends the form again.
     */
    @Test
    fun aFormThatTheServerDoesNotDoIsReportedWithWhatIsKnownOfTheChange() {
        val api = fake()
        val noSuchPlan = listOf(FieldErrorDto("planId", "No plan has this identifier."))
        api.on("POST", "plans/plan-night/apply") { problem(422, "The request is not valid.", noSuchPlan) }
        val browser = Browser(uiFor(api, answerTimeout = Duration.ofMillis(SHORT)))
        val service = browser.get(SampleArea.NEW).form()
        val plan = browser.get("/sample/plans").form("main form[action*=apply]")

        val refused = browser.submit(plan)
        api.on("POST", "services") { problem(500, "The server could not write its definitions location.") }
        val failed = browser.submit(service, entered)
        api.on("POST", "services") { problem(404, "No service has the identifier svc-gone.") }
        val missing = browser.submit(service, entered)
        api.on("POST", "services") {
            Thread.sleep(LONGER_THAN_SHORT)
            json(Samples.customers, 201)
        }
        val late = browser.submit(service, entered)
        api.close()
        val down = browser.submit(service, entered)

        val unchanged = "Nothing was changed."
        val unknown =
            "The UI does not know whether the server did what the form asks for. The pages of the UI show what the server holds now."
        val ofThePlan = listOf("The request is not valid.", "planId: No plan has this identifier.", unchanged)
        val unreachable = "The UI cannot reach the server at ${api.url}."
        val expected =
            listOf(
                refused to Triple(502, "The server refused the request", ofThePlan),
                failed to Triple(502, "The server failed", listOf("The server could not write its definitions location.", unknown)),
                missing to Triple(404, "Not there", listOf("No service has the identifier svc-gone.", unchanged)),
                late to Triple(502, "The server cannot be reached", listOf("$unreachable The server did not answer in time.", unknown)),
                down to Triple(502, "The server cannot be reached", listOf("$unreachable Nothing accepts connections there.", unchanged)),
            )
        for ((answer, said) in expected) {
            val problems = answer.page.select("main .problem").map { it.text() }
            assertEquals(said, Triple(answer.status, answer.page.select("main h1").text(), problems))
            assertEquals(emptyList(), answer.page.select("main a").map { it.text() })
        }
    }

    /** A form leads to a page of the UI. A handler that would send the browser elsewhere fails. */
    @Test
    fun aFormLeadsToNoOtherSite() {
        val browser = Browser(uiFor(fake()))
        val token = tokenOf(browser.get(SampleArea.NEW))

        val answers = mutableListOf<Browser.Response>()

        val log = TestLog.of { answers += browser.post("/sample/elsewhere", listOf("_token" to token)) }

        assertEquals(listOf(500 to null), answers.map { it.status to it.header("location") })
        assertTrue("A form leads to a path of the UI, and not to //elsewhere.example/." in log, log)
    }

    private companion object {
        /** The number of characters of the value of a browser, and of the identifier of a report. */
        const val VALUE_LENGTH = 24

        /** A number of bytes that a form of a test exceeds without being long. */
        const val SMALL_LIMIT = 4_000L
        const val LEAD = "description="

        /** How often a word is repeated so that a notice is longer than the UI lets it be, and how long the UI lets it be. */
        const val LONG = 200
        const val NOTICE_LENGTH = 600

        /** How the name of the cookie with the value of a browser ends. */
        const val FORM = "form"

        /** The milliseconds that the UI of a test waits for an answer, and a time that is longer. */
        const val SHORT = 300L
        const val LONGER_THAN_SHORT = 1_500L
    }
}
