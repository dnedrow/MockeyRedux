package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.HistoryEntryDto
import io.github.dnedrow.mockey.api.NameValueDto
import io.github.dnedrow.mockey.api.RecordedBodyDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 13.9: the pages of a single history entry. The page of an entry shows everything the entry holds, and
 * it has the buttons that flag and delete the entry. One form replays the request of the entry with edits,
 * and another one saves its response as a scenario.
 *
 * Each test drives the UI over HTTP the way a browser without scripting would (see [Browser]), and then
 * checks which requests reached the admin API.
 */
class UiHistoryEntryTest {
    private val started = Started()

    @AfterTest
    fun stopEverything() = started.close()

    /** What a stand-in server holds for a test: the server itself and live views of its history and its services. */
    private class Holding(
        val api: FakeAdminApi,
        val entries: MutableMap<Long, HistoryEntryDto>,
        val services: MutableMap<String, ServiceDto>,
    )

    /** Starts a stand-in server that holds [entries] and the sample services. */
    private fun holding(vararg entries: HistoryEntryDto = arrayOf(Entries.of(7))): Holding {
        val api = started.api()
        val services = api.hold()
        return Holding(api, api.holdHistory(entries.toList(), services), services)
    }

    private val plainText = listOf(HeaderDto("Content-Type", "text/plain"), HeaderDto("Accept", "*/*"))

    /** An entry for a `POST` request with a query string, two headers, and a body of two lines. */
    private val posted =
        Entries.of(7, method = "POST", query = "q=a+b&x=%C3%A9", headers = plainText, body = Samples.body("line one\nline two"))

    /** Returns [text] in Base64, the way a replay request holds a body. */
    private fun base64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    /**
     * Returns the body of a replay request with [headers] and [parameters], each as a JSON array, and with
     * the body [bodyBase64]. A part that's `null` isn't replaced by the replay, and the request says so with a
     * JSON null.
     */
    private fun edits(
        headers: String? = null,
        parameters: String? = null,
        bodyBase64: String? = null,
    ): String = """{"headers":$headers,"parameters":$parameters,"bodyBase64":${bodyBase64?.let { "\"$it\"" }}}"""

    /** Returns the facts on the page of an entry, each as its term and its description. */
    private fun factsOf(page: Document): List<Pair<String, String>> =
        page.select("main dl dt").map { it.text() to it.nextElementSibling()!!.text() }

    /** Returns what [page] shows below each of its headings, each as the heading and the texts that follow it. */
    private fun sectionsOf(page: Document): List<Pair<String, List<String>>> =
        page.select("main h2, main h3").map { heading ->
            val texts = mutableListOf<String>()
            var next: Element? = heading.nextElementSibling()
            while (next != null && next.tagName() !in setOf("h2", "h3")) {
                texts += next.text()
                next = next.nextElementSibling()
            }
            heading.text() to texts.toList()
        }

    /** Returns the names of the scenarios that the service Customers has now. */
    private fun scenarioNamesOf(held: Holding): List<String> {
        val customers = held.services.getValue("svc-customers")
        return customers.scenarios.map { it.name }
    }

    /** Returns the requests to [api] that replayed an entry or made a scenario from one, each as its request line and its body. */
    private fun posts(api: FakeAdminApi): List<Pair<String, String>> = api.changes().filter { it.first.startsWith("POST history/") }

    // The page of an entry

    /** The page of an entry shows when and how the request was handled, the request, and the response. */
    @Test
    fun thePageOfAnEntryShowsWhatTheEntryHolds() {
        val parameters = listOf(NameValueDto("q", "a b"), NameValueDto("x", "é"))
        val cookies = listOf(NameValueDto("session", "abc"))
        val request = posted.request.copy(parameters = parameters, cookies = cookies)
        val entry =
            posted.copy(
                request = request,
                targetBeforeTwisting = "kunde/7",
                inspectionMessages = listOf("no session", "no token"),
                explanation = "The scenario was the default one.",
                flagged = true,
            )
        val held = holding(entry)
        val browser = started.browserFor(held.api)

        val answer = browser.get("/history/entry?id=7")

        val page = answer.page
        val facts =
            listOf(
                "Time" to Entries.shownTimeOf(7),
                "Client" to "192.0.2.7",
                "Service" to "Customers team-a",
                "Response mode" to "Static",
                "Outcome" to "Scenario served",
                "Scenario" to "found happy",
                "Target" to "customer/7",
                "Target before twisting" to "kunde/7",
                "Explanation" to "The scenario was the default one.",
                "Flag" to "flagged",
            )
        val sections =
            listOf(
                "Inspection messages" to listOf("no session no token"),
                "Request" to listOf("POST http://mockey.test:8080/service/customer/7?q=a+b&x=%C3%A9"),
                "Headers" to listOf("Header Value Content-Type text/plain Accept */*"),
                "Parameters" to listOf("Parameter Value q a b x é"),
                "Cookies" to listOf("Cookie Value session abc"),
                "Body" to listOf("line one\nline two"),
                "Response" to listOf("Status code 200"),
                "Headers" to listOf("Header Value Content-Type application/json"),
                "Body" to listOf("""{"name":"Ada"}"""),
            )
        assertEquals(200 to "History entry 7 - Mockey", answer.status to page.title())
        assertEquals(facts, factsOf(page))
        assertEquals(sections, sectionsOf(page))
        assertEquals(listOf("line one\nline two", """{"name":"Ada"}"""), page.select("main pre.body").map { it.wholeText() })
        val links =
            listOf(
                "Replay" to "/history/replay?id=7",
                "Save as a scenario" to "/history/scenario?id=7",
                "Back to the history" to "/history",
                "Customers" to "/service?id=svc-customers",
                "found" to "/scenario?service=svc-customers&id=sc-found",
            )
        assertEquals(links, page.select("main a").map { it.text() to it.attr("href") })
        val forms = page.select("main .actions form").map { it.attr("action") to it.select("button").text() }
        assertEquals(listOf("/history/flag?id=7" to "Unflag", "/history/remove?id=7" to "Delete"), forms)
        assertEquals(0, page.select("main .actions button[aria-label]").size)
        assertEquals(listOf("/history"), page.select("nav a[aria-current=page]").map { it.attr("href") })
        assertEquals(listOf("GET system", "GET history/7"), held.api.requests.map { it.line })
        assertEquals(emptyList(), held.api.violations)
    }

    /** A body that isn't plain text in UTF-8 is shown for what it is: bytes, a cut text, or text in another charset. */
    @Test
    fun thePageSaysWhatABodyHoldsIfItIsNotPlainText() {
        val latin = listOf(HeaderDto("content-type", "text/plain; charset=\"ISO-8859-1\""))
        val unknownCharset = listOf(HeaderDto("Content-Type", "text/plain; charset=nonsense"))
        val splitCharacter = "caf".toByteArray() + byteArrayOf(0xC3.toByte())
        val notText = { bytes: String -> "The body is not text in UTF-8. It has $bytes." }
        val keptOnly = { kept: String, size: String -> "The server kept only the first $kept of this body, which has $size." }
        val bodies =
            listOf(
                Triple(Entries.bytes(0x89, 0x50, 0x4E, 0x47), emptyList(), listOf(notText("4 bytes"))),
                Triple(Entries.bytes(0x61, 0x00, 0x62), emptyList(), listOf(notText("3 bytes"))),
                Triple(Entries.bytes(0xE9), latin, listOf("é")),
                Triple(Entries.bytes(0xE9), emptyList(), listOf(notText("1 byte"))),
                Triple(Entries.bytes(0xE9), unknownCharset, listOf(notText("1 byte"))),
                Triple(Entries.cut(splitCharacter, 5000), emptyList(), listOf("caf", keptOnly("4 bytes", "5,000 bytes"))),
                Triple(Entries.cut(ByteArray(0), 12), emptyList(), listOf("The body has 12 bytes, and the server kept none of them.")),
                Triple(Entries.cut(byteArrayOf(-1, -2), 9), emptyList(), listOf(notText("9 bytes"), keptOnly("2 bytes", "9 bytes"))),
                Triple(RecordedBodyDto("not Base64!", 3, isCut = false), emptyList(), listOf(UNREADABLE_BODY)),
                Triple(Samples.body(""), emptyList(), listOf("The request had no body.")),
            )
        val entries = bodies.mapIndexed { index, (body, headers, _) -> Entries.of(index + 1L, headers = headers, body = body) }
        val held = holding(*entries.toTypedArray())
        val browser = started.browserFor(held.api)

        val pages = entries.map { browser.get("/history/entry?id=${it.id}").page }
        val shown = pages.map { page -> sectionsOf(page).first { it.first == "Body" }.second }

        assertEquals(bodies.map { it.third }, shown)
        assertEquals(emptyList(), held.api.violations)
    }

    /** An entry for a request that no service handled says so, and it doesn't offer to save a scenario. */
    @Test
    fun anEntryThatNoServiceHandledSaysSo() {
        val bare = Entries.of(7, headers = emptyList(), responseHeaders = emptyList())
        val noService = bare.copy(service = null, scenario = null, responseMode = null)
        val unhandled = noService.copy(outcome = "noServiceMatched", clientAddress = null)
        val outcomes = listOf("errorScenarioServed", "noScenarioMatched", "proxied", "upstreamFailed", "somethingNew")
        val others = outcomes.mapIndexed { index, outcome -> Entries.of(index + 10L).copy(outcome = outcome) }
        val held = holding(unhandled, *others.toTypedArray())
        val browser = started.browserFor(held.api)

        val page = browser.get("/history/entry?id=7").page
        val pages = others.map { browser.get("/history/entry?id=${it.id}").page }
        val labels = pages.map { other -> factsOf(other).first { it.first == "Outcome" }.second }

        val facts = listOf("Time" to Entries.shownTimeOf(7), "Client" to "unknown", "Service" to "none", "Outcome" to "No service matched")
        assertEquals(facts, factsOf(page).take(4))
        assertEquals(listOf("Replay", "Back to the history"), page.select("main a").map { it.text() })
        assertEquals(listOf("Request", "Headers", "Body", "Response", "Headers", "Body"), sectionsOf(page).map { it.first })
        val noHeaders = sectionsOf(page).filter { it.first == "Headers" }.map { it.second }
        assertEquals(listOf(listOf("The request had no headers."), listOf("The response had no headers.")), noHeaders)
        assertEquals(0, page.select("main table").size)
        assertEquals(listOf("Target" to "customer/7", "Flag" to "not flagged"), factsOf(page).drop(4))
        assertEquals(listOf("Error scenario served", "No scenario matched", "Proxied", "Upstream failed", "somethingNew"), labels)
    }

    /** The buttons of the page flag the entry, remove the flag, and delete the entry. */
    @Test
    fun theButtonsOfThePageFlagAndDeleteTheEntry() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val page = "/history/entry?id=7"

        val flagged = browser.submit(browser.get(page).form("main form[action^=/history/flag]"))
        val afterFlag = browser.follow(flagged)
        val unflagged = browser.submit(afterFlag.form("main form[action^=/history/flag]"))
        val afterUnflag = browser.follow(unflagged)
        val deleted = browser.submit(afterUnflag.form("main form[action^=/history/remove]"))
        val afterDelete = browser.follow(deleted)
        val gone = browser.get(page)

        val sent = listOf(flagged, unflagged, deleted).map { it.status to it.header("location") }
        assertEquals(listOf(303 to page, 303 to page, 303 to "/history"), sent)
        assertEquals("The history entry 7 is now flagged." to "flagged", afterFlag.notice to factsOf(afterFlag.page).last().second)
        val unflaggedFact = factsOf(afterUnflag.page).last().second
        assertEquals("The history entry 7 is no longer flagged." to "not flagged", afterUnflag.notice to unflaggedFact)
        assertEquals("The history entry 7 was deleted.", afterDelete.notice)
        assertEquals(404 to "Not there", gone.status to gone.page.select("main h1").text())
        val flags = listOf("PATCH history/7" to """{"flagged":true}""", "PATCH history/7" to """{"flagged":false}""")
        val changes = flags + ("DELETE history/7" to "")
        assertEquals(changes, held.api.changes())
        assertEquals(emptyList(), held.api.violations)
    }

    /** The pages of an entry that the history doesn't keep say so, and an address without an identifier is no page at all. */
    @Test
    fun thePagesOfAMissingEntrySaySo() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val token = browser.token("/history/delete")
        val notKept = "The history keeps no entry with the identifier 99."
        for (method in listOf("GET", "PATCH", "DELETE")) held.api.on(method, "history/99") { problem(404, notKept) }
        for (action in listOf("replay", "scenario")) held.api.on("POST", "history/99/$action") { problem(404, notKept) }
        val paths = listOf("/history/entry", "/history/replay", "/history/scenario")
        val fields = listOf("_token" to token, "designate" to "yes", "name" to "x", "headers" to "A: b")

        val pages = paths.map { browser.get("$it?id=99") }
        val forms = (paths.drop(1) + listOf("/history/flag", "/history/remove")).map { browser.post("$it?id=99", fields) }
        val incomplete = paths.flatMap { path -> listOf(path, "$path?id=x", "$path?id=1.5", "$path?id=") }.map { browser.get(it).status }

        for (answer in pages + forms) {
            assertEquals(404 to "Not there", answer.status to answer.page.select("main h1").text())
            assertTrue(notKept in answer.page.select("main .problem").text())
        }
        assertEquals(List(12) { 404 }, incomplete)
        assertEquals(listOf("POST history/99/replay", "PATCH history/99", "DELETE history/99"), held.api.changes().map { it.first })
    }

    // Replaying an entry

    /** The replay form starts with the headers, the query parameters, and the body of the entry. */
    @Test
    fun theReplayFormStartsWithTheRequestOfTheEntry() {
        val held = holding(posted)
        val browser = started.browserFor(held.api)

        val answer = browser.get("/history/replay?id=7")

        val page = answer.page
        val form = answer.form()
        val fields =
            mapOf(
                "bodyLineEndings" to listOf("lf"),
                "headers" to listOf("Content-Type: text/plain\nAccept: */*"),
                "parameters" to listOf("q=a b\nx=é"),
                "body" to listOf("line one\nline two"),
            )
        assertEquals(200 to "Replay the history entry 7 - Mockey", answer.status to page.title())
        assertEquals("POST http://mockey.test:8080/service/customer/7", page.select("main > p")[1].text())
        assertEquals("post" to "/history/replay?id=7", form.attr("method") to form.attr("action"))
        assertEquals(fields, fieldsOf(form))
        assertEquals(listOf("_shown.headers", "_shown.parameters", "_shown.body"), form.select("[name^=_shown.]").map { it.attr("name") })
        assertEquals(listOf("Headers", "Query parameters", "Body"), form.select("label").map { it.text() })
        assertEquals("If this field stays as it is, the replay sends the recorded body again.", form.select("#f-body-hint").text())
        assertEquals("Replay the request", form.select("button").text())
        assertEquals(listOf("Cancel" to "/history/entry?id=7"), form.select("a").map { it.text() to it.attr("href") })
        assertEquals(listOf("/history"), page.select("nav a[aria-current=page]").map { it.attr("href") })
        assertEquals(emptyList(), held.api.violations)
    }

    /**
     * The query parameters are read from the query string the way the server reads them: an empty part isn't a
     * parameter, and a part without an equals sign is a name with an empty value.
     */
    @Test
    fun theReplayFormReadsTheQueryStringTheWayTheServerDoes() {
        val queries = listOf("&a=1&&flag&b=2&" to "a=1\nflag=\nb=2", "" to "", "=" to "=", "a=b=c&d" to "a=b=c\nd=")
        val entries = queries.mapIndexed { index, (query, _) -> Entries.of(index + 1L, query = query) } + Entries.of(9, query = null)
        val held = holding(*entries.toTypedArray())
        val browser = started.browserFor(held.api)

        val shown = entries.map { fieldsOf(browser.get("/history/replay?id=${it.id}").form()).getValue("parameters").single() }

        assertEquals(queries.map { it.second } + "", shown)
    }

    /** A replay without edits asks the server to replay the entry as it is, and the browser ends up on the entry of the replay. */
    @Test
    fun aReplayWithoutEditsReplaysTheEntryAsItIs() {
        val held = holding(posted)
        val browser = started.browserFor(held.api)

        val sent = browser.submit(browser.get("/history/replay?id=7").form())
        val after = browser.follow(sent)

        assertEquals(303 to "/history/entry?id=8", sent.status to sent.header("location"))
        assertEquals("The request of the history entry 7 was replayed. This is the entry of the replay.", after.notice)
        assertEquals("History entry 8", after.page.select("main h1").text())
        assertEquals(listOf("Status code 202"), sectionsOf(after.page).first { it.first == "Response" }.second)
        assertEquals(listOf("POST history/7/replay" to ""), posts(held.api))
        assertEquals(emptyList(), held.api.violations)
    }

    /**
     * The scenario "Replay with edits": the form only sends the parts that were changed, and the browser ends
     * up on the new history entry that the replay produced.
     */
    @Test
    fun aReplaySendsOnlyTheEditedParts() {
        val held = holding(posted)
        val browser = started.browserFor(held.api)
        val replay = { edits: Map<String, String> -> browser.submit(browser.get("/history/replay?id=7").form(), edits) }

        val ofBody = replay(mapOf("body" to "changed\nbody"))
        val shown = browser.follow(ofBody)
        val ofHeaders = replay(mapOf("headers" to "Content-Type: text/plain\n\n X-Test :  1 \nX-Empty:"))
        browser.follow(ofHeaders)
        val ofParameters = replay(mapOf("parameters" to "q=a b\nflag\n\nempty=\n=odd\n two = 2 =3"))
        browser.follow(ofParameters)
        val ofAll = replay(mapOf("headers" to "A: b", "parameters" to "", "body" to ""))

        val sent = listOf(ofBody, ofHeaders, ofParameters, ofAll).map { it.status to it.header("location") }
        assertEquals(listOf(8L, 9L, 10L, 11L).map { 303 to "/history/entry?id=$it" }, sent)
        assertEquals("History entry 8", shown.page.select("main h1").text())
        assertEquals(listOf("changed\nbody", "replayed"), shown.page.select("main pre.body").map { it.wholeText() })
        val headers = """[{"name":"Content-Type","value":"text/plain"},{"name":"X-Test","value":"1"},{"name":"X-Empty","value":""}]"""
        val pairs = listOf("q" to "a b", "flag" to "", "empty" to "", "" to "odd", " two " to " 2 =3")
        val parameters = pairs.joinToString(",", "[", "]") { (name, value) -> """{"name":"$name","value":"$value"}""" }
        val bodies =
            listOf(
                edits(bodyBase64 = base64("changed\nbody")),
                edits(headers = headers),
                edits(parameters = parameters),
                edits("""[{"name":"A","value":"b"}]""", "[]", ""),
            )
        assertEquals(bodies.map { "POST history/7/replay" to it }, posts(held.api))
        assertEquals(emptyList(), held.api.violations)
    }

    /** An edited body keeps the line endings of the recorded body, and it's encoded in the charset that the headers declare. */
    @Test
    fun anEditedBodyKeepsItsLineEndingsAndItsCharset() {
        val latin = listOf(HeaderDto("Content-Type", "text/plain; charset=ISO-8859-1"))
        val withCarriageReturns = Entries.of(1, method = "PUT", body = Samples.body("a\r\nb"))
        val inLatin = Entries.of(2, method = "PUT", headers = latin, body = Entries.bytes(0xE9))
        val held = holding(withCarriageReturns, inLatin)
        val browser = started.browserFor(held.api)

        val first = browser.get("/history/replay?id=1").form()
        browser.follow(browser.submit(first, mapOf("body" to "a\nb\nc")))
        val second = browser.get("/history/replay?id=2").form()
        browser.follow(browser.submit(second, mapOf("body" to "é!")))
        val withoutCharset = mapOf("headers" to "Content-Type: text/plain", "body" to "é!")
        val asUtf8 = browser.submit(browser.get("/history/replay?id=2").form(), withoutCharset)
        val rejected = browser.submit(browser.get("/history/replay?id=2").form(), mapOf("body" to "€"))

        assertEquals(listOf("crlf") to listOf("a\nb"), fieldsOf(first).getValue("bodyLineEndings") to fieldsOf(first).getValue("body"))
        assertEquals(listOf("é"), fieldsOf(second).getValue("body"))
        val message =
            "The body has a character that ISO-8859-1 cannot encode. " +
                "Change the body, or change the charset in the Content-Type header."
        assertEquals(303, asUtf8.status)
        assertEquals(422 to mapOf("body" to listOf(message)), rejected.status to rejected.fieldProblems)
        assertEquals(listOf("€"), fieldsOf(rejected.form()).getValue("body"))
        val latinBytes = Base64.getEncoder().encodeToString(byteArrayOf(0xE9.toByte(), 0x21))
        val bodies =
            listOf(
                "POST history/1/replay" to edits(bodyBase64 = base64("a\r\nb\r\nc")),
                "POST history/2/replay" to edits(bodyBase64 = latinBytes),
                "POST history/2/replay" to edits("""[{"name":"Content-Type","value":"text/plain"}]""", bodyBase64 = base64("é!")),
            )
        assertEquals(bodies, posts(held.api))
        assertEquals(emptyList(), held.api.violations)
    }

    /** A body that isn't text can't be edited, so the field starts empty. The replay sends the recorded bytes unless something is typed. */
    @Test
    fun aBodyThatIsNotTextIsReplayedAsRecordedUnlessItIsReplaced() {
        val held = holding(Entries.of(7, method = "PUT", body = Entries.bytes(0x00, 0xFF, 0x10)))
        val browser = started.browserFor(held.api)

        val form = browser.get("/history/replay?id=7").form()
        browser.follow(browser.submit(form))
        browser.submit(browser.get("/history/replay?id=7").form(), mapOf("body" to "text"))

        assertEquals(listOf(""), fieldsOf(form).getValue("body"))
        val hint = "The recorded body is not text, so this field starts empty. Leave it empty to send the recorded body again."
        assertEquals(hint, form.select("#f-body-hint").text())
        assertEquals(listOf("POST history/7/replay" to "", "POST history/7/replay" to edits(bodyBase64 = base64("text"))), posts(held.api))
    }

    /** The server needs a body to replay an entry whose body was cut, so the form always sends what the body field holds. */
    @Test
    fun aCutBodyIsSentAsTheFieldHoldsIt() {
        val held = holding(Entries.of(7, method = "PUT", body = Entries.cut("hello".toByteArray(), 5000)))
        val browser = started.browserFor(held.api)

        val form = browser.get("/history/replay?id=7").form()
        val sent = browser.submit(form)

        val hint = "The server kept only the first 5 bytes of this body, which has 5,000 bytes. The replay sends whatever this field holds."
        assertEquals(hint, form.select("#f-body-hint").text())
        assertEquals(listOf("hello"), fieldsOf(form).getValue("body"))
        assertEquals(listOf("_shown.headers", "_shown.parameters"), form.select("[name^=_shown.]").map { it.attr("name") })
        assertEquals(303, sent.status)
        assertEquals(listOf("POST history/7/replay" to edits(bodyBase64 = base64("hello"))), posts(held.api))
    }

    /**
     * A query string that the form can't show as one parameter per line has no field. The replay keeps such a
     * query string as it was recorded.
     */
    @Test
    fun aQueryStringThatCannotBeShownAsLinesStaysAsItIs() {
        val queries = listOf("a=%0Ab", "a%0Db=c", "a%3Db=c", "a=%zz", "%")
        val held = holding(*queries.mapIndexed { index, query -> Entries.of(index + 1L, query = query) }.toTypedArray())
        val browser = started.browserFor(held.api)

        val forms = queries.indices.map { browser.get("/history/replay?id=${it + 1}").form() }
        val sent = browser.submit(forms.first(), mapOf("headers" to "A: b"))

        val stays = "The query string of this request cannot be shown as one parameter per line, so the replay sends it as it was recorded."
        for (form in forms) {
            assertEquals(listOf("bodyLineEndings", "headers", "body"), fieldsOf(form).keys.toList())
            assertEquals(listOf("_shown.headers", "_shown.body"), form.select("[name^=_shown.]").map { it.attr("name") })
            assertEquals(listOf(stays), form.select("form > p.hint").map { it.text() })
        }
        assertEquals(303, sent.status)
        assertEquals(listOf("POST history/1/replay" to edits(headers = """[{"name":"A","value":"b"}]""")), posts(held.api))
    }

    /** A line that isn't a header is a mistake. The form is displayed again with what was entered, and nothing is replayed. */
    @Test
    fun theReplayFormRejectsALineThatIsNotAHeader() {
        val held = holding(posted)
        val browser = started.browserFor(held.api)

        val edits = mapOf("headers" to "Accept: */*\nnonsense\n: x", "body" to "kept")
        val rejected = browser.submit(browser.get("/history/replay?id=7").form(), edits)

        val format = "A header has the form Name: value, for example Content-Type: text/plain."
        val messages = listOf(2, 3).map { "Line $it is not a header. $format" }
        assertEquals(422 to mapOf("headers" to messages), rejected.status to rejected.fieldProblems)
        val kept = fieldsOf(rejected.form())
        assertEquals(edits, mapOf("headers" to kept.getValue("headers").single(), "body" to kept.getValue("body").single()))
        assertEquals(0, rejected.page.select("meta[http-equiv]").size)
        assertEquals(emptyList(), posts(held.api))
    }

    /** A form that the server rejects is displayed again with the entered values and the messages of the server next to the fields. */
    @Test
    fun aReplayThatTheServerRejectsShowsTheMessagesNextToTheFields() {
        val held = holding(posted)
        val browser = started.browserFor(held.api)
        val errors =
            listOf(
                FieldErrorDto("bodyBase64", "The body is too long."),
                FieldErrorDto("headers[1].name", "This is not a header name."),
                FieldErrorDto("parameters[0].name", "This is not a parameter name."),
                FieldErrorDto("other", "Something else is wrong."),
            )
        held.api.on("POST", "history/7/replay") { problem(422, "The request is not valid.", errors) }

        val edits = mapOf("headers" to "A: b\nC d: e", "parameters" to "x=1", "body" to "new")
        val rejected = browser.submit(browser.get("/history/replay?id=7").form(), edits)
        val again = browser.submit(rejected.form())

        val problems =
            mapOf(
                "headers" to listOf("This is not a header name."),
                "parameters" to listOf("This is not a parameter name."),
                "body" to listOf("The body is too long."),
            )
        assertEquals(422 to problems, rejected.status to rejected.fieldProblems)
        assertEquals(listOf("other: Something else is wrong."), rejected.page.select("main form > .field-problem p").map { it.text() })
        assertEquals(edits.mapValues { listOf(it.value) }, fieldsOf(rejected.form()) - "bodyLineEndings")
        val sent = edits("""[{"name":"A","value":"b"},{"name":"C d","value":"e"}]""", """[{"name":"x","value":"1"}]""", base64("new"))
        assertEquals(listOf("POST history/7/replay" to sent, "POST history/7/replay" to sent), posts(held.api))
        assertEquals(422, again.status)
    }

    /** Browsers change some values on their own. The replay form doesn't treat such a value as changed. */
    @Test
    fun theReplayFormDoesNotSendAValueThatOnlyTheBrowserAltered() {
        val headers = listOf(HeaderDto("X-Odd", "a\rb"))
        val body = Samples.body("one\rtwo\r\nthree\n")
        val held = holding(Entries.of(7, method = "PUT", query = "x=%EF%BF%BD", headers = headers, body = body))
        val browser = started.browserFor(held.api)

        val sent = browser.submit(browser.get("/history/replay?id=7").form())

        assertEquals(303, sent.status)
        assertEquals(listOf("POST history/7/replay" to ""), posts(held.api))
    }

    // Saving an entry as a scenario

    /**
     * The form saves the response of an entry as a scenario of the entry's service, and the browser ends up
     * on the page of that scenario.
     */
    @Test
    fun theFormSavesTheResponseOfAnEntryAsAScenario() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val answer = browser.get("/history/scenario?id=7")
        val sent = browser.submit(answer.form(), mapOf("name" to "  recorded order "))
        val after = browser.follow(sent)

        val page = answer.page
        val form = answer.form()
        val intro = "The scenario gets the status code, the headers, and the body of this response. It belongs to the service Customers."
        assertEquals(200 to "Save the history entry 7 as a scenario - Mockey", answer.status to page.title())
        assertEquals(listOf(intro), page.select("main > p").map { it.text() })
        assertEquals("post" to "/history/scenario?id=7", form.attr("method") to form.attr("action"))
        assertEquals(mapOf("name" to listOf("")), fieldsOf(form))
        assertEquals("If the name is empty, the server generates one.", form.select("#f-name-hint").text())
        assertEquals("Create the scenario", form.select("button").text())
        assertEquals(listOf("Cancel" to "/history/entry?id=7"), form.select("a").map { it.text() to it.attr("href") })
        assertEquals(303 to "/scenario?service=svc-customers&id=sc-of-entry-7", sent.status to sent.header("location"))
        assertEquals("The scenario recorded order was created from the history entry 7.", after.notice)
        assertEquals("Scenario recorded order", after.page.select("main h1").text())
        assertEquals(listOf("POST history/7/scenario" to """{"name":"recorded order"}"""), posts(held.api))
        assertEquals(listOf("found", "missing", "recorded order"), scenarioNamesOf(held))
        assertEquals(emptyList(), held.api.violations)
    }

    /** Without a name, the form leaves the name to the server. */
    @Test
    fun aScenarioWithoutANameLeavesTheNameToTheServer() {
        val held = holding()
        val browser = started.browserFor(held.api)

        val sent = browser.submit(browser.get("/history/scenario?id=7").form(), mapOf("name" to "   "))
        val after = browser.follow(sent)

        assertEquals(303, sent.status)
        assertEquals("The scenario Scenario (auto-generated) was created from the history entry 7.", after.notice)
        assertEquals(listOf("POST history/7/scenario" to ""), posts(held.api))
    }

    /**
     * The server can't make a scenario from an entry without a service, or from one whose response body was cut.
     * The page says why instead of showing a form, and a request that's sent anyway doesn't create anything.
     */
    @Test
    fun anEntryThatCannotBecomeAScenarioSaysWhy() {
        val unhandled = Entries.of(1).copy(service = null, scenario = null, outcome = "noServiceMatched")
        val cut = Entries.of(2, responseBody = Entries.cut("start".toByteArray(), 5000))
        val held = holding(unhandled, cut)
        val browser = started.browserFor(held.api)
        val token = browser.token("/history/delete")

        val pages = listOf(1, 2).map { browser.get("/history/scenario?id=$it") }
        val forced = listOf(1, 2).map { browser.post("/history/scenario?id=$it", listOf("_token" to token, "name" to "x")) }

        val reasons =
            listOf(
                "No service handled this request, so there is no service that the scenario could belong to.",
                "The server kept only the start of this response body, so a scenario made from it would not give the same response.",
            )
        assertEquals(listOf(200, 200), pages.map { it.status })
        assertEquals(listOf(422, 422), forced.map { it.status })
        for (answers in listOf(pages, forced)) {
            assertEquals(reasons, answers.map { it.page.select("main p.problem").text() })
            assertEquals(listOf(0, 0), answers.map { it.page.select("main form").size })
        }
        val first = pages.first().page
        val back = first.select("main a").map { it.text() to it.attr("href") }
        assertEquals(listOf("Back to the entry" to "/history/entry?id=1"), back)
        assertEquals(listOf("found", "missing"), scenarioNamesOf(held))
    }

    /** A name that the server rejects is reported next to the name field, and the form keeps what was entered. */
    @Test
    fun aNameThatTheServerRejectsIsShownAtItsField() {
        val held = holding()
        val browser = started.browserFor(held.api)
        val errors = listOf(FieldErrorDto("name", "The service has a scenario with this name already."))
        held.api.on("POST", "history/7/scenario") { problem(422, "The request is not valid.", errors) }

        val rejected = browser.submit(browser.get("/history/scenario?id=7").form(), mapOf("name" to "found"))

        val problems = mapOf("name" to listOf("The service has a scenario with this name already."))
        assertEquals(422 to problems, rejected.status to rejected.fieldProblems)
        assertEquals(mapOf("name" to listOf("found")), fieldsOf(rejected.form()))
    }

    // Stored content is text

    /**
     * The requirement "Stored content is shown as text" for the pages of an entry: whatever a client sent
     * and a service answered, the pages show it as plain text, and the forms hold it as a value.
     */
    @Test
    fun theEntryPagesShowStoredContentAsText() {
        val api = started.api()
        val browser = started.browserFor(api)
        for (text in Hostile.MARKUP + Hostile.ADDRESSES) {
            api.holdHistory(listOf(Hostile.entry(1, text)), api.hold())

            val pages = listOf("/history/entry?id=1", "/history/replay?id=1", "/history/scenario?id=1").map { browser.get(it) }

            for (answer in pages) {
                assertEquals(200, answer.status, text)
                assertEquals(emptyList(), Hostile.moreThanText(answer), text)
            }
            val bodies = pages.first().page.select("main pre.body")
            assertEquals(listOf(text, text), bodies.map { it.wholeText() }, text)
        }
        assertEquals(emptyList(), api.violations)
    }

    private companion object {
        const val UNREADABLE_BODY = "The server sent this body in a form that the UI cannot read."
    }
}
