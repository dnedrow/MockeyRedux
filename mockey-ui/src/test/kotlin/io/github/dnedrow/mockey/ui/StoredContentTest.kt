package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.ProblemDto
import io.github.dnedrow.mockey.api.TwistConfigurationDto
import io.ktor.http.parseQueryString
import kotlinx.html.a
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.p
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 13.4: what a server holds is shown as the text that it is, wherever a page shows it: a name, a tag, a
 * URL, a header, a body, and what the server says of a request. A test makes a page of content that is
 * written to be read as markup, reads the page as a browser does, and finds the content as text and nothing
 * else in the page.
 */
class StoredContentTest {
    private val fakes = mutableListOf<FakeAdminApi>()
    private val uis = mutableListOf<MockeyUi>()

    @AfterTest
    fun stopEverything() {
        uis.forEach(MockeyUi::close)
        fakes.forEach(FakeAdminApi::close)
    }

    private val view = View("the-token-of-the-forms")
    private val adminUrl = AdminUrl.of("http://mockey.test:8081")

    /** Returns the page that [content] makes for [text], as a browser reads it, with the document as it was written. */
    private fun rendered(
        text: String,
        content: (String) -> Page,
    ): Pair<Document, String> {
        val document = Html.document(content(text), "/services", adminUrl, FakeAdminApi.SYSTEM, notice = text)
        return Jsoup.parse(document) to document
    }

    /** A page that shows [text] in every way in which the pages show what a server holds. */
    private fun everywhere(text: String): Page =
        Page("The service $text") {
            h2 {
                id = Address.anchorOf("service", text)
                +text
            }
            p(classes = "shown") { +text }
            p(classes = "tags") { tagsOf(listOf(text, "plain")) }
            p(classes = "url") { urlOf(text) }
            headersOf(listOf(HeaderDto(text, text), HeaderDto("Content-Type", "text/plain")))
            bodyOf(text)
            a(href = Address.of("/service", "id" to text, "tag" to text)) { +text }
            val values = mapOf("name" to listOf(text), "body" to listOf(text), "mode" to listOf(text))
            val state = FormState(values, listOf(FieldErrorDto("name", text)))
            postForm(view, Address.of("/service/delete", "id" to text)) {
                rejectionOf(FormState(emptyMap(), detail = text))
                textField(state, "name", text, hint = text)
                textAreaField(state, "body", text)
                choiceField(state, "mode", text, listOf(text to text, "other" to "Other"))
                checkField(state, "enabled", text)
                otherProblemsOf(FormState(emptyMap(), listOf(FieldErrorDto(text, text))))
                submit(text)
            }
        }

    /**
     * The scenario "Markup in a scenario name": the name appears as the text that it is, and so does every
     * other content, and the page has no element and no attribute that the UI did not write.
     */
    @Test
    fun contentThatIsWrittenAsMarkupIsShownAsTextWhereverAPageShowsIt() {
        for (text in MARKUP) {
            val (page, written) = rendered(text, ::everywhere)

            val heading = page.select("main h1").first()!!.wholeText()
            val shown =
                mapOf(
                    "the title" to page.title().removeSuffix(" - Mockey").removePrefix("The service "),
                    "the heading of the page" to heading.removePrefix("The service "),
                    "a heading" to page.select("main h2").first()!!.wholeText(),
                    "the notice" to page.select("main .notice").first()!!.wholeText(),
                    "a paragraph" to page.select("main p.shown").first()!!.wholeText(),
                    "a tag" to page.select("main .tags .tag").first()!!.wholeText(),
                    "a URL" to page.select("main p.url code").first()!!.wholeText(),
                    "the name of a header" to page.select("main table.headers tbody td").first()!!.wholeText(),
                    "the value of a header" to page.select("main table.headers tbody td")[1].wholeText(),
                    "a body" to page.select("main pre.body").first()!!.wholeText(),
                    "a link" to page.select("main > a").first()!!.wholeText(),
                    "the head of a form" to page.select("main form .problem").first()!!.wholeText(),
                    "a label" to page.select("main form label").first()!!.wholeText(),
                    "the value of a field" to page.select("main form [name=name]").attr("value"),
                    "a hint" to page.select("main form .hint").first()!!.wholeText(),
                    "a message at a field" to page.select("main form .field .field-problem p").first()!!.wholeText(),
                    "the lines of a field" to page.select("main form textarea").first()!!.wholeText(),
                    "a choice" to page.select("main form option").first()!!.wholeText(),
                    "the value of a choice" to page.select("main form option[selected]").attr("value"),
                    "a message of a form" to page.select("main form > .field-problem p").first()!!.wholeText(),
                    "a button" to page.select("main form button").first()!!.wholeText(),
                )
            assertEquals(shown.mapValues { (where, _) -> if (where == "a message of a form") "$text: $text" else text }, shown, text)
            assertEquals(ELEMENTS, page.select("main *").map { it.tagName() }.toSet() - ELEMENTS_OF_CONTENT, text)
            val attributes = page.allElements.flatMap { it.attributes().map { attribute -> attribute.key } }
            assertEquals(emptySet(), attributes.toSet() - ATTRIBUTES, text)
            assertEquals(emptyList(), UNWRITTEN.filter { it in written }, text)
        }
    }

    /**
     * A link of a page leads to a page of the UI whatever the identifier in its address is, and the page at
     * that address gets the identifier as it is. A URL that a server holds is no link.
     */
    @Test
    fun anIdentifierIsAParameterOfAnAddressAndAStoredUrlIsNoLink() {
        for (text in MARKUP + ADDRESSES) {
            val (page, _) = rendered(text, ::everywhere)

            val links = page.select("main a[href], main form[action]").map { it.attr("href") + it.attr("action") }
            assertEquals(listOf("/service", "/service/delete"), links.map { it.substringBefore('?') }, text)
            for (link in links) {
                assertTrue(SAFE_ADDRESS.matches(link), "$text -> $link")
                assertEquals(text, parseQueryString(link.substringAfter('?'))["id"], link)
            }
            assertEquals(0, page.select("main .url a, main .url [href]").size, text)
            assertTrue(SAFE_ANCHOR.matches(page.select("main h2").attr("id")), text)
        }
    }

    @Test
    fun anAddressHasAPathOfWordsAndEveryParameterAsItIs() {
        val addresses =
            mapOf(
                Address.of("/services") to "/services",
                Address.of("/service", "id" to "svc-1", "tab" to null) to "/service?id=svc-1",
                Address.of("/service", "id" to "..") to "/service?id=..",
                Address.of("/service", "id" to "a/b c") to "/service?id=a%2Fb%20c",
                Address.of("/scenario", "service" to "a?b#c%d&e=f", "id" to "+") to "/scenario?service=a%3Fb%23c%25d%26e%3Df&id=%2B",
                Address.of("/service", "id" to "K\u00fcche") to "/service?id=K%C3%BCche",
                Address.of("/history", "token" to "customer 7", "token" to "!gold") to "/history?token=customer%207&token=%21gold",
                Address.of("/reports/Ab_-9") to "/reports/Ab_-9",
            )
        val anchors = listOf("svc-1", "a b", "a/b", "<b>", "K\u00fcche", "").map { Address.anchorOf("service", it) }
        val noPaths = listOf("services", "/services/", "/services/../system", "/a b", "//evil.example", "/service?id=1", "", "/")

        assertEquals(addresses.values.toList(), addresses.keys.toList())
        val expected = listOf("service-svc-1", "service-a.20b", "service-a.2Fb", "service-.3Cb.3E", "service-K.C3.BCche", "service-")
        assertEquals(expected, anchors)
        assertEquals(emptyList(), noPaths.filter { runCatching { Address.of(it) }.isSuccess })
    }

    /** A page that is about something says so with a parameter of its address. Without the parameter, the UI has no such page. */
    @Test
    fun anAddressWithoutWhatThePageIsAboutIsNoPage() {
        val api = FakeAdminApi().also { fakes += it }
        api.on("GET", "services/..") { FakeAdminApi.json(Samples.customers.copy(id = "..", name = "Dots")) }
        api.on("GET", "services/a%2Fb%20c") { FakeAdminApi.json(Samples.customers.copy(id = "a/b c", name = "Slash")) }
        val settings = UiSettings(port = 0, adminUrl = AdminUrl.of(api.url), logLevel = LogLevel.WARN)
        val browser = Browser(MockeyUi.start(settings, areas = listOf(SampleArea)).also { uis += it })

        val without = listOf(browser.get(SampleArea.SERVICE), browser.get("${SampleArea.SERVICE}?other=1"))
        val shown = listOf("..", "a/b c").map { browser.get(Address.of(SampleArea.SERVICE, "id" to it)) }

        for (answer in without) assertEquals(404 to "Not there", answer.status to answer.page.select("main h1").text())
        assertEquals(listOf(200 to "Dots", 200 to "Slash"), shown.map { it.status to it.page.select("main h1").text() })
        val links = shown.map { it.page.select("main a").attr("href") }
        assertEquals(listOf("/sample/service/delete?id=..", "/sample/service/delete?id=a%2Fb%20c"), links)
        assertEquals(emptyList(), api.violations)
    }

    /** A page shows a body and the lines of a field as they are, also where they begin with an empty line or end with spaces. */
    @Test
    fun aBodyIsShownWithItsLineBreaksAndItsSpaces() {
        val bodies = listOf("\n\nbegins with two empty lines", "  indented\n\tand tabbed  \n", "\n", "", "one line", "a\r\nb")

        for (body in bodies) {
            val (page, _) = rendered(body) { text -> Page("Body") { bodyOf(text) } }

            assertEquals(body, page.select("main pre.body").first()!!.wholeText(), body)
        }
    }

    /** What the pages of the server and the pages for a failure show of the server is text as well. */
    @Test
    fun whatTheServerStatesOfItselfAndSaysOfARequestIsShownAsText() {
        val api = FakeAdminApi().also { fakes += it }
        val settings = UiSettings(port = 0, adminUrl = AdminUrl.of(api.url), logLevel = LogLevel.WARN)
        val browser = Browser(MockeyUi.start(settings).also { uis += it })
        val text = "<script>alert(1)</script><b>\"x\"</b>"
        api.system =
            FakeAdminApi.SYSTEM.copy(
                version = text,
                startTime = text,
                definitionsLocation = text,
                tagFilter = listOf(text, "plain"),
                enabledTwist = TwistConfigurationDto("twist-1", text, emptyList()),
                mockBaseUrl = text,
            )

        val status = browser.get("/system").page
        api.on("GET", "system") { FakeAdminApi.problem(500, text, listOf(FieldErrorDto(text, text))) }
        val failed = browser.get("/system").page
        val refusal = ApiFailure.Refused(ProblemDto("about:blank", text, 400, text, listOf(FieldErrorDto(text, text))))
        val refused = Jsoup.parse(Html.document(FailurePages(adminUrl, hasToken = false).of(refusal, true), "/x", adminUrl, null))

        val stated = status.select("main dl dd").map { it.wholeText() }
        val ofTheUi = setOf("Read-only", "Disabled")
        val ofTheServer = stated.filterNot { it.substringBefore(':') in ofTheUi }
        assertEquals(listOf(text, text, text, "$text/service/", "$text plain", text), ofTheServer)
        val foot = status.select("body > footer p").first()!!.wholeText()
        assertTrue(foot.endsWith(", version $text, in read-only mode"), foot)
        assertEquals(listOf(text), failed.select("main .problem").map { it.wholeText() })
        assertEquals(listOf(text, "$text: $text"), refused.select("main .problem").map { it.wholeText() })
        for (page in listOf(status, failed, refused)) assertEquals(0, page.select("script, b").size)
    }

    /**
     * The UI writes nothing into a page but through what writes text. `unsafe` of kotlinx.html writes markup
     * as it is given, and no source of the UI uses it.
     */
    @Test
    fun noSourceOfTheUiWritesMarkupAsItIsGiven() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()

        val using = sources.filter { source -> WRITES_MARKUP.containsMatchIn(source.readText()) }.map { it.name }

        assertTrue(sources.size > FEW && sources.any { it.name == "Stored.kt" }, "The sources of the UI are to be read: ${sources.size}")
        assertEquals(emptyList(), using)
        assertFalse(WRITES_MARKUP.containsMatchIn("nothing here uses `unsafe`, which writes markup"))
        assertTrue(listOf("unsafe { +text }", "unsafe{raw(text)}", "consumer.onTagContentUnsafe { }").all(WRITES_MARKUP::containsMatchIn))
    }

    private companion object {
        val MARKUP = Hostile.MARKUP
        val ADDRESSES = Hostile.ADDRESSES
        val UNWRITTEN = Hostile.UNWRITTEN
        val SAFE_ADDRESS = Hostile.SAFE_ADDRESS

        /** The elements of the page of the test, and the ones that hold nothing of the content. */
        val ELEMENTS =
            setOf("h1", "h2", "p", "span", "code", "table", "tbody", "tr", "td", "pre", "a", "form", "label", "input")
                .plus(setOf("div", "textarea", "select", "option", "button"))
        val ELEMENTS_OF_CONTENT = setOf("thead", "th")

        /** The attributes that the UI writes. */
        val ATTRIBUTES =
            setOf("lang", "charset", "name", "content", "rel", "href", "type", "class", "aria-label", "aria-current", "role", "id")
                .plus(setOf("action", "method", "value", "for", "rows", "selected", "aria-describedby", "aria-invalid"))

        val SAFE_ANCHOR = Regex("service-[A-Za-z0-9._~-]*")

        /** How a source uses what writes markup as it is given. */
        val WRITES_MARKUP = Regex("""\bunsafe\s*\{|onTagContentUnsafe|\bUnsafe\b""")

        /** A number of sources that the UI exceeds, so that a directory without sources does not pass for one without a use. */
        const val FEW = 15
    }
}
