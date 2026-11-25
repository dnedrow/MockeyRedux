package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import org.jsoup.nodes.Document
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 13.1: the skeleton of the UI: the frame of its pages, the files that the pages refer to, and what
 * stays with the UI. The UI of a test listens on a port that the system picks and manages a stand-in for a
 * server, see [FakeAdminApi]. A test reads the answers as a browser does, see [Browser].
 */
class UiPagesTest {
    private val fakes = mutableListOf<FakeAdminApi>()
    private val uis = mutableListOf<MockeyUi>()

    @AfterTest
    fun stopEverything() {
        uis.forEach(MockeyUi::close)
        fakes.forEach(FakeAdminApi::close)
    }

    private fun fake(token: String? = null): FakeAdminApi = FakeAdminApi(token).also { fakes += it }

    /** Starts a UI that manages [api] and presents [token] to it, and returns a browser for it. */
    private fun browserFor(
        api: FakeAdminApi,
        token: String? = null,
    ): Browser {
        val presented = token?.let(::AdminToken)
        val settings = UiSettings(port = 0, adminUrl = AdminUrl.of(api.url), adminToken = presented, logLevel = LogLevel.WARN)
        return Browser(MockeyUi.start(settings).also { uis += it })
    }

    /** Returns the terms of the description list of [page] with what the list says of each. */
    private fun described(page: Document): Map<String, String> =
        page.select("main dl dt").associate { it.text() to it.nextElementSibling()!!.text() }

    // The frame of the pages

    /**
     * A page begins with what the server states of itself. The page of the server shows it, and the foot of
     * every page names the server.
     */
    @Test
    fun thePageOfTheServerShowsWhatTheServerStatesOfItself() {
        val api = fake()
        api.system = FakeAdminApi.SYSTEM.copy(readOnly = false, tagFilter = listOf("team-a", "nightly"), enabledTwist = Samples.twist)
        val browser = browserFor(api)

        val answer = browser.get("/system")

        val page = answer.page
        val expected =
            mapOf(
                "Version" to "3.0.0",
                "Running since" to "2026-01-02T03:04:05.678Z",
                "Definitions location" to "/srv/mockey/mockey-definitions",
                "Mode" to "Writable: the server stores every change in its definitions location.",
                "Mock endpoint" to "http://mockey.test:8080/service/",
                "Tag filter" to "team-a nightly",
                "Enabled twist configuration" to "German paths",
            )
        assertEquals(200, answer.status)
        assertEquals("text/html; charset=UTF-8" to "no-store", answer.header("content-type") to answer.header("cache-control"))
        assertEquals("Server - Mockey" to "Server", page.title() to page.select("main h1").text())
        assertEquals("en", page.select("html").attr("lang"))
        assertEquals(expected, described(page))
        val foot = listOf("Server at ${api.url}, version 3.0.0, in writable mode", "Mockey UI ${System.getProperty("mockey.version")}")
        assertEquals(foot, page.select("body > footer p").map { it.text() })
        assertEquals(listOf("/system"), page.select("nav a[aria-current=page]").map { it.attr("href") })
        assertEquals(listOf("GET system"), api.requests.map { it.line })
        assertEquals(emptyList(), api.violations)
    }

    @Test
    fun aServerInItsDefaultModeIsShownAsReadOnlyAndWithoutAFilter() {
        val browser = browserFor(fake())

        val page = browser.get("/system").page

        val shown = described(page)
        assertEquals("Read-only: the server keeps changes in memory, and stores none of them.", shown["Mode"])
        assertEquals("none" to "none", shown["Tag filter"] to shown["Enabled twist configuration"])
        val ofTheServer = page.select("body > footer p").first()!!.text()
        assertTrue(ofTheServer.endsWith(", version 3.0.0, in read-only mode"), ofTheServer)
    }

    /** The first page of the UI leads to the page of the server, and a page answers a request with the method HEAD without a body. */
    @Test
    fun theFirstPageLeadsOnAndAPageAnswersARequestWithTheMethodHead() {
        val browser = browserFor(fake())

        val first = browser.get("/")
        val page = browser.get("/system")
        val ofHead = browser.request("HEAD", "/system")

        assertEquals(302 to "/system", first.status to first.header("location"))
        assertEquals(200 to "", ofHead.status to ofHead.body)
        val ofTheBody = listOf("content-length", "content-type")
        assertEquals(ofTheBody.map(page::header), ofTheBody.map(ofHead::header))
    }

    /** An address at which the UI has no page gets a page that says so, in the frame of every page. The server is not asked for it. */
    @Test
    fun anAddressWithoutAPageGetsAPageThatSaysSo() {
        val api = fake()
        val browser = browserFor(api)

        val answers = listOf("/nothing", "/system/more", "/assets", "/assets/", "/SYSTEM").map(browser::get)
        val posted = browser.request("POST", "/system")

        for (answer in answers + posted) {
            assertEquals(404, answer.status)
            val said = answer.page.select("main p").text()
            assertEquals("Not there - Mockey" to "The UI has no page at this address.", answer.page.title() to said)
            val foot = answer.page.select("body > footer p").map { it.text() }
            assertEquals("Server at ${api.url}", foot.first())
        }
        assertEquals(emptyList(), api.requests.map { it.line })
    }

    /**
     * An address that cannot be read gets a page of the UI that says so. Ktor would answer with a text that
     * repeats the address, and with it whatever the one who wrote the address put there.
     */
    @Test
    fun anAddressThatCannotBeReadGetsAPageThatRepeatsNothingOfIt() {
        val api = fake()
        val browser = browserFor(api)
        val addresses = listOf("/system/%zz?x=<script>alert(1)</script>", "/%zz", "/assets/%zz", "/system?shown=%zz", "/system?<b>=%")

        val log = TestLog.of { addresses.map(browser::raw).forEach { answer -> assertUnreadable(answer) } }

        assertEquals(emptyList(), TestLog.messages(log))
        assertEquals(emptyList(), api.requests.map { it.line })
    }

    private fun assertUnreadable(answer: Browser.Response) {
        val said = "The UI cannot read the address of this request: a percent sign in it does not begin an escape."
        assertEquals(400 to "text/html; charset=UTF-8", answer.status to answer.header("content-type"))
        assertEquals("Not an address - Mockey" to said, answer.page.title() to answer.page.select("main p").text())
        assertEquals(POLICY to "no-store", answer.header("content-security-policy") to answer.header("cache-control"))
        assertFalse("zz" in answer.body || "script" in answer.body || "<b>" in answer.body, answer.body)
    }

    // What stays with the UI

    /**
     * The scenario "Token stays on the UI side": the UI presents the admin token to the server with every
     * request, and nothing that reaches a browser holds it: no page, no header, and no cookie. That holds
     * for a page that reports a failure as well, and the log of the UI does not hold the token either.
     */
    @Test
    fun nothingThatReachesABrowserHoldsTheAdminToken() {
        val token = "the-admin-token-of-the-server"
        val api = fake(token)
        val failure = problem(500, "The server failed to handle the request.")
        api.on("GET", "system") { if (api.requests.size > FAILING_FROM) failure else FakeAdminApi.json(api.system) }
        val secrets = listOf("token-of-the-server", "another-token")
        val answers = mutableListOf<Browser.Response>()

        val log =
            TestLog.of {
                val browser = browserFor(api, token)
                val paths = listOf("/", "/system", "/nothing", "/assets/mockey.css", "/assets/favicon.svg", "/system?token=x", "/system")
                answers += paths.map(browser::get)
                api.close()
                answers += browser.get("/system")
                answers += browserFor(fake(token), "another-token").get("/system")
            }

        assertEquals(listOf(302, 200, 404, 200, 200, 200, 502, 502, 502), answers.map { it.status })
        for (answer in answers) assertFalse(secrets.any { it in answer.everything }, answer.everything)
        assertEquals(emptyList(), answers.flatMap { it.headers["set-cookie"].orEmpty() })
        assertFalse(secrets.any { it in log }, log)
        assertEquals(List(FAILING_FROM + 1) { "Bearer $token" }, api.requests.map { it.header("authorization") })
        val reported = answers.last().page.select("main h1")
        assertEquals("The server does not take the admin token of the UI", reported.text())
    }

    // The files of the UI

    /**
     * The scenario "No external requests", as far as a test without a browser shows it: everything that a
     * page refers to is a path of the UI, which the UI serves, and a page holds no script and no style of
     * its own, which could load something. Every answer tells the browser to load nothing from elsewhere.
     */
    @Test
    fun everythingThatAPageRefersToIsAFileOfTheUiItself() {
        val browser = browserFor(fake())

        val pages = listOf("/system", "/nothing").map(browser::get)

        val files = mutableSetOf<String>()
        for (answer in pages) {
            val page = answer.page
            val referred = page.allElements.flatMap { element -> REFERRING.filter(element::hasAttr).map(element::attr) }
            val elsewhere = referred.filterNot { it.startsWith("/") && !it.startsWith("//") }
            assertEquals(emptyList(), elsewhere, "What the page refers to elsewhere")
            assertEquals(0, page.select("script:not([src]), style, base, [style], meta[http-equiv=refresh]").size)
            val attributes = page.allElements.flatMap { it.attributes().map { attribute -> attribute.key } }
            assertEquals(emptyList(), attributes.filter { it.startsWith("on") }, "The attributes that run a script")
            assertEquals(POLICY, answer.header("content-security-policy"))
            assertEquals("nosniff" to "no-referrer", answer.header("x-content-type-options") to answer.header("referrer-policy"))
            files += page.select("link[href], script[src], img[src]").map { it.attr("href") + it.attr("src") }
        }
        assertEquals(setOf("/assets/mockey.css", "/assets/favicon.svg"), files)
        for (file in files) {
            val served = browser.get(file)
            assertEquals(200, served.status, file)
            assertEquals(POLICY, served.header("content-security-policy"))
            assertEquals(emptyList(), ELSEWHERE.findAll(served.body.replace(SVG_NAMESPACE, "")).map { it.value }.toList(), file)
        }
    }

    /** A file comes with its type and with a tag of its bytes, and a browser that has the file with that tag is not sent it again. */
    @Test
    fun aFileComesWithItsTypeAndIsNotSentAgainWhileItHolds() {
        val browser = browserFor(fake())

        val stylesheet = browser.get("/assets/mockey.css")
        val icon = browser.get("/assets/favicon.svg")
        val again = browser.get("/assets/mockey.css", mapOf("If-None-Match" to stylesheet.header("etag")!!))
        val changed = browser.get("/assets/mockey.css", mapOf("If-None-Match" to icon.header("etag")!!))

        assertEquals("text/css; charset=UTF-8" to "image/svg+xml", stylesheet.header("content-type") to icon.header("content-type"))
        assertTrue(stylesheet.body.startsWith("/* The stylesheet of the Mockey UI."), stylesheet.body.take(SHOWN))
        assertTrue(icon.body.startsWith("<svg xmlns="), icon.body.take(SHOWN))
        assertTrue(TAG.matches(stylesheet.header("etag")!!) && stylesheet.header("etag") != icon.header("etag"), stylesheet.header("etag"))
        assertEquals("no-cache" to "no-cache", stylesheet.header("cache-control") to icon.header("cache-control"))
        assertEquals(Triple(304, "", stylesheet.header("etag")), Triple(again.status, again.body, again.header("etag")))
        assertEquals(200 to stylesheet.body, changed.status to changed.body)
    }

    /** The UI serves its files and no other file of the program, whatever the name of a request is. */
    @Test
    fun noOtherFileOfTheProgramIsServed() {
        val browser = browserFor(fake())
        val names =
            listOf(
                "ui-version.properties",
                "..%2Fui-version.properties",
                "%2E%2E%2Fui-version.properties",
                "..%2F..%2F..%2F..%2F..%2Flogback.xml",
                "mockey.css%2F..%2F..%2Fui-version.properties",
                "MOCKEY.CSS",
                "mockey.css%00.svg",
            )

        val answers = names.map { browser.get("/assets/$it") } + browser.get("/assets/../ui-version.properties")

        for (answer in answers) {
            assertEquals(404, answer.status)
            assertFalse("version=" in answer.body || "<configuration>" in answer.body, answer.body)
        }
    }

    private companion object {
        /** After how many requests the stand-in for the server begins to fail, in the test of the token. */
        const val FAILING_FROM = 2

        const val SHOWN = 60

        /** The attributes with which an element of a page refers to something that a browser loads, or sends a form to. */
        val REFERRING = listOf("href", "src", "srcset", "action", "formaction", "data", "poster", "background", "manifest", "ping", "cite")

        /** What refers to another place in a stylesheet or in an image. */
        val ELSEWHERE = Regex("""https?:|//|url\(|@import|href""", RegexOption.IGNORE_CASE)

        /** The name of what an SVG image is written in. It is a name, and nothing that a browser loads. */
        const val SVG_NAMESPACE = "xmlns=\"http://www.w3.org/2000/svg\""

        val TAG = Regex("\"[0-9a-f]{24}\"")

        const val POLICY =
            "default-src 'none'; style-src 'self'; script-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'; " +
                "form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
    }
}
