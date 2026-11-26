package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import org.jsoup.nodes.Document
import java.security.MessageDigest
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
                "Running since" to "2026-01-02 03:04:05 UTC",
                "Definitions location" to "/srv/mockey/mockey-definitions",
                "Mode" to "Writable: the server stores every change in its definitions location.",
                "Mock endpoint" to "http://mockey.test:8080/service/",
                "Tag filter" to "team-a nightly",
                "Enabled twist configuration" to "German paths",
                "Legacy shim" to "Disabled: the server does not answer the automation endpoints of Mockey 2.x.",
            )
        assertEquals(200, answer.status)
        assertEquals("text/html; charset=UTF-8" to "no-store", answer.header("content-type") to answer.header("cache-control"))
        assertEquals("Server - Mockey" to "Server", page.title() to page.select("main h1").text())
        assertEquals("en", page.select("html").attr("lang"))
        assertEquals(expected, described(page))
        assertEquals(listOf("2026-01-02T03:04:05.678Z"), page.select("main dl time").map { it.attr("datetime") })
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

    /** The first page of the UI leads to the overview of the services, and a page answers a request with the method HEAD without a body. */
    @Test
    fun theFirstPageLeadsOnAndAPageAnswersARequestWithTheMethodHead() {
        val browser = browserFor(fake())

        val first = browser.get("/")
        val page = browser.get("/system")
        val ofHead = browser.request("HEAD", "/system")

        assertEquals(302 to "/services", first.status to first.header("location"))
        assertEquals(200 to "", ofHead.status to ofHead.body)
        val ofTheBody = listOf("content-length", "content-type")
        assertEquals(ofTheBody.map(page::header), ofTheBody.map(ofHead::header))
    }

    /** An address at which the UI has no page gets a page that says so, in the frame of every page. The server is not asked for it. */
    @Test
    fun anAddressWithoutAPageGetsAPageThatSaysSo() {
        val api = fake()
        val browser = browserFor(api)

        val ofNoSection = listOf("/nothing", "/assets", "/assets/", "/SYSTEM", "/systems", "/services-of-old")
        val answers = (ofNoSection + listOf("/system/more", "/diagnostics/1")).map(browser::get)

        val marked = answers.map { answer -> answer.page.select("nav a[aria-current=page]").map { it.attr("href") } }
        assertEquals(ofNoSection.map { emptyList<String>() } + listOf(listOf("/system"), listOf("/services")), marked)
        for (answer in answers) {
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
        assertFalse("zz" in answer.body || "alert" in answer.body || "<b>" in answer.body, answer.body)
        assertEquals(emptyList(), Hostile.unwrittenIn(answer.body))
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
        assertTrue(answers.any { it.headers["set-cookie"] != null }, "The answers are to come with a cookie, which is looked into as well.")
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
     *
     * The pages are the ones that the links of the UI lead to, see [Crawl], with what a server holds in
     * them, and a page that the UI does not have.
     */
    @Test
    fun everythingThatAPageRefersToIsAFileOfTheUiItself() {
        val browser = browserFor(fake().also { it.hold(diagnostics = listOf(Samples.diagnostic)) })

        val reached = Crawl.from(browser).pages.values
        val pages = reached.filter { it.header("location") == null } + browser.get("/nothing")

        assertTrue(pages.size > FEW_PAGES, "The pages of the UI are to be read: ${pages.size}")
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
        assertEquals(setOf("/assets/mockey.css", "/assets/favicon.svg", SCRIPT), files)
        for (file in files) {
            val served = browser.get(file)
            assertEquals(200, served.status, file)
            assertEquals(POLICY, served.header("content-security-policy"))
            // The script is a file that others wrote. Another test holds it to the file that was vendored.
            val ofTheUi = if (file == SCRIPT) "" else served.body.replace(SVG_NAMESPACE, "")
            assertEquals(emptyList(), ELSEWHERE.findAll(ofTheUi).map { it.value }.toList(), file)
        }
    }

    /**
     * The script of the UI is htmx as its makers published it: the file has the checksum that
     * `docs/third-party.md` states, so no byte of it was changed. Every page loads it from the UI itself,
     * after the page is read, and states the settings of the UI for it.
     */
    @Test
    fun theScriptIsTheFileThatWasVendoredAndEveryPageLoadsItFromTheUi() {
        val browser = browserFor(fake().also { it.hold() })

        val script = browser.get(SCRIPT)
        val pages = listOf("/services", "/service/new", "/nothing").map { browser.get(it).page }

        val digest = MessageDigest.getInstance("SHA-256").digest(script.body.toByteArray(Charsets.UTF_8))
        assertEquals(SCRIPT_SHA_256, digest.joinToString("") { "%02x".format(it) })
        assertEquals(200 to "text/javascript; charset=UTF-8", script.status to script.header("content-type"))
        assertTrue("version:\"$SCRIPT_VERSION\"" in script.body, "The script states another version than $SCRIPT_VERSION.")
        for (page in pages) {
            val loaded = page.select("script").map { element -> element.attributes().map { it.key to it.value } to element.data() }
            assertEquals(listOf(listOf("src" to SCRIPT, "defer" to "defer") to ""), loaded)
            assertEquals(1, page.select("head > script").size)
            assertEquals(listOf(SCRIPT_SETTINGS), page.select("head > meta[name=htmx-config]").map { it.attr("content") })
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

        /** A number of pages that the links of the UI lead to at least. A UI without links is not to pass for one without a fault. */
        const val FEW_PAGES = 4

        /** The attributes with which an element of a page refers to something that a browser loads, or sends a form to. */
        val REFERRING = listOf("href", "src", "srcset", "action", "formaction", "data", "poster", "background", "manifest", "ping", "cite")

        /** What refers to another place in a stylesheet or in an image. */
        val ELSEWHERE = Regex("""https?:|//|url\(|@import|href""", RegexOption.IGNORE_CASE)

        /** The path of the script of the UI, its version, and the checksum of the file as `docs/third-party.md` states it. */
        const val SCRIPT = "/assets/htmx.min.js"
        const val SCRIPT_VERSION = "2.0.11"
        const val SCRIPT_SHA_256 = "d6fdc75f204e6bdefa99b69bf1e6d4ac69b8a364f77929f45c13476b4000f717"

        /**
         * The settings that every page states for the script, word for word: the script adds no style,
         * evaluates nothing, runs no script of an answer, and keeps no copy of a page. It changes nothing
         * for an answer without content, and it puts every other answer in the place of the page.
         */
        const val SCRIPT_SETTINGS =
            """{"includeIndicatorStyles":false,"allowEval":false,"allowScriptTags":false,"historyCacheSize":0,""" +
                """"responseHandling":[{"code":"204","swap":false},""" +
                """{"code":"...","swap":true,"target":"body","swapOverride":"innerHTML"}]}"""

        /** The name of what an SVG image is written in. It is a name, and nothing that a browser loads. */
        const val SVG_NAMESPACE = "xmlns=\"http://www.w3.org/2000/svg\""

        val TAG = Regex("\"[0-9a-f]{24}\"")

        const val POLICY =
            "default-src 'none'; style-src 'self'; script-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'; " +
                "form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
    }
}
