package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.ProblemDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.json
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import org.jsoup.Jsoup
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 13.2: the pages for a server that cannot be reached, that does not take the token of the UI, and that
 * is not compatible with the UI, and the page of the server when the server is back. The UI of a test
 * manages a stand-in for a server, see [FakeAdminApi], which a test stops and starts on the same port.
 */
class UiFailurePagesTest {
    private val fakes = mutableListOf<FakeAdminApi>()
    private val uis = mutableListOf<MockeyUi>()

    @AfterTest
    fun stopEverything() {
        uis.forEach(MockeyUi::close)
        fakes.forEach(FakeAdminApi::close)
    }

    /** Starts a stand-in for a server on [port] that asks for [token]. */
    private fun fake(
        port: Int = 0,
        token: String? = null,
    ): FakeAdminApi = FakeAdminApi(token, port).also { fakes += it }

    /** Returns a port of the loopback address on which nothing listens. */
    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    /** Starts a UI that manages the server at [url] and presents [token] to it, and returns a browser for it. */
    private fun browserFor(
        url: String,
        token: String? = null,
        answerTimeout: Duration = Duration.ofSeconds(30),
    ): Browser {
        val settings = UiSettings(port = 0, adminUrl = AdminUrl.of(url), adminToken = token?.let(::AdminToken), logLevel = LogLevel.WARN)
        val ui = MockeyUi.start(settings) { AdminClient(it.adminUrl, it.adminToken, Duration.ofSeconds(5), answerTimeout) }
        return Browser(ui.also { uis += it })
    }

    /** What a page says: its title, the matter in its marked paragraphs, and whether it shows anything that a server states. */
    private class Said(
        val status: Int,
        val title: String,
        val problems: List<String>,
        val foot: String,
        val showsData: Boolean,
        val again: List<String>,
    )

    private fun said(answer: Browser.Response): Said {
        val page = answer.page
        return Said(
            status = answer.status,
            title = page.select("main h1").text(),
            problems = page.select("main .problem").map { it.text() },
            foot = page.select("body > footer p").first()!!.text(),
            showsData = page.select("main dl, main table").isNotEmpty(),
            again = page.select("main a").map { "${it.text()} -> '${it.attr("href")}'" },
        )
    }

    private fun assertShowsTheServer(answer: Browser.Response) {
        assertEquals(200 to "Server", answer.status to answer.page.select("main h1").text())
        val stated = answer.page.select("main dl dd")
        assertEquals("3.0.0", stated.first()!!.text())
        assertEquals(emptyList(), answer.page.select("main .problem").map { it.text() })
    }

    // A server that cannot be reached

    /**
     * The scenarios "Server is down" and "Server comes back": while nothing listens at the address of the
     * server, a page says that the server at that address cannot be reached. The UI keeps running, and the
     * page shows the server as soon as there is one, every time.
     */
    @Test
    fun aServerThatIsDownIsReportedWithItsAddressAndThePageShowsTheServerWhenItIsBack() {
        val port = freePort()
        val url = "http://127.0.0.1:$port"
        val browser = browserFor(url)

        val down = browser.get("/system")
        val first = fake(port)
        val back = browser.get("/system")
        first.close()
        val downAgain = browser.get("/system")
        val second = fake(port)
        val backAgain = listOf(browser.get("/system"), browser.get("/system"))

        for (answer in listOf(down, downAgain)) {
            val page = said(answer)
            assertEquals(502 to "The server cannot be reached", page.status to page.title)
            assertEquals(listOf("The UI cannot reach the server at $url. Nothing accepts connections there."), page.problems)
            assertEquals("Server at $url" to false, page.foot to page.showsData)
            assertEquals(listOf("Load this page again -> ''"), page.again)
            assertEquals("no-store", answer.header("cache-control"))
        }
        (backAgain + back).forEach(::assertShowsTheServer)
        assertEquals(listOf("GET system"), first.requests.map { it.line })
        assertEquals(listOf("GET system", "GET system"), second.requests.map { it.line })
    }

    /** A page names the settings with which the UI is told where the server is, and it shows nothing that the UI holds of a request. */
    @Test
    fun thePageForAServerThatIsDownSaysHowTheUiIsToldWhereTheServerIs() {
        val browser = browserFor("http://127.0.0.1:${freePort()}")

        val answers = listOf(browser.get("/system?shown=%3Ci%3Eeverything%3C/i%3E"), browser.request("HEAD", "/system"))

        val page = answers.first().page
        val text = page.select("main").text()
        assertTrue("in --admin-url or MOCKEY_UI_ADMIN_URL." in text, text)
        assertEquals(listOf("--admin-url", "MOCKEY_UI_ADMIN_URL"), page.select("main code").map { it.text() })
        assertFalse("everything" in answers.first().body, answers.first().body)
        assertEquals(502 to "", answers.last().status to answers.last().body)
    }

    /** A server that takes too long to answer, and a host with a name that nobody knows, are servers that cannot be reached. */
    @Test
    fun aServerThatDoesNotAnswerInTimeAndAHostThatIsNotKnownAreReportedAsSuch() {
        val slow = fake()
        slow.on("GET", "system") {
            Thread.sleep(LONGER_THAN_THE_UI_WAITS)
            json(FakeAdminApi.SYSTEM)
        }
        val unknown = "http://no-such-host.invalid:8081"

        val late = said(browserFor(slow.url, answerTimeout = Duration.ofMillis(300)).get("/system"))
        val nowhere = said(browserFor(unknown).get("/system"))

        val lead = "The UI cannot reach the server at"
        assertEquals(502 to listOf("$lead ${slow.url}. The server did not answer in time."), late.status to late.problems)
        assertEquals(502 to listOf("$lead $unknown. The name of its host is not known."), nowhere.status to nowhere.problems)
        assertEquals("The server cannot be reached" to "The server cannot be reached", late.title to nowhere.title)
    }

    // A server that does not take the token

    /**
     * The requirement "Server unavailable", for a token that the server rejects: the page says so and names
     * the server, and it says whether the UI has a token, and never which. The page shows the server when
     * the server takes the token of the UI.
     */
    @Test
    fun aServerThatDoesNotTakeTheTokenOfTheUiIsReportedWithoutTheToken() {
        val port = freePort()
        val url = "http://127.0.0.1:$port"
        val guarded = fake(port, "the-token-of-the-server")
        val without = browserFor(url)
        val withAnother = browserFor(url, "the-token-of-the-ui")

        val asked = without.get("/system")
        val rejected = withAnother.get("/system")
        guarded.close()
        fake(port, "the-token-of-the-ui")
        val taken = withAnother.get("/system")

        val noToken = said(asked)
        val wrongToken = said(rejected)
        assertEquals(502 to "The server asks for an admin token", noToken.status to noToken.title)
        assertEquals(listOf("The server at $url asks for an admin token, and the UI was started without one."), noToken.problems)
        assertEquals(502 to "The server does not take the admin token of the UI", wrongToken.status to wrongToken.title)
        assertEquals(listOf("The server at $url does not take the admin token that the UI was started with."), wrongToken.problems)
        for (answer in listOf(asked, rejected)) {
            assertEquals(listOf("--admin-token", "MOCKEY_ADMIN_TOKEN"), answer.page.select("main code").map { it.text() })
            assertEquals("Server at $url" to false, said(answer).foot to said(answer).showsData)
            assertFalse("the-token-of" in answer.everything, answer.everything)
        }
        assertShowsTheServer(taken)
    }

    // A server that is not compatible

    /**
     * The scenario "Wrong API version": what answers at the address of the server without the admin API of
     * the version of the UI is reported on every page, with the version that the UI needs. The page shows
     * the server when the server offers that API.
     */
    @Test
    fun aServerWithoutTheAdminApiOfTheUiIsReportedOnEveryPageWithTheVersionThatTheUiNeeds() {
        val api = fake()
        val browser = browserFor(api.url)
        val who = "What answers at ${api.url}"
        val needed = "This UI needs the admin API of the version v1."
        val asNoApi = { status: Int -> "$who answered with the status $status, and not as an admin API does." }
        val welcome = FakeAdminApi.Answer(200, "<html><h1>Welcome</h1></html>", "text/html")
        val elsewhere = FakeAdminApi.Answer(301, headers = mapOf("Location" to "https://mockey.test/"))
        val cases =
            listOf(
                problem(404, "The admin API has no resource at the path of the request.") to "$who has no admin API of the version v1.",
                FakeAdminApi.Answer(404) to asNoApi(404),
                welcome to "$who answered with the status 200 and with what no admin API answers with.",
                elsewhere to asNoApi(301),
            )

        for ((answer, reason) in cases) {
            api.on("GET", "system") { answer }
            for (path in Html.sections.map { it.path }) {
                val page = said(browser.get(path))
                assertEquals(502 to "The server is not compatible with this UI", page.status to page.title, path)
                assertEquals(listOf("$reason $needed"), page.problems, path)
                assertEquals("Server at ${api.url}" to false, page.foot to page.showsData, path)
                assertEquals(listOf("Load this page again -> ''"), page.again)
            }
        }
        api.on("GET", "system") { json(FakeAdminApi.SYSTEM) }
        assertShowsTheServer(browser.get("/system"))
    }

    /** The requirement "Incompatible server": a page shows nothing of a server that states only a part of what the UI reads. */
    @Test
    fun aPageShowsNothingOfAServerThatStatesAPartOfWhatTheUiReads() {
        val api = fake()
        val partly = """{"version":"9.9.9","startTime":"2026-01-02T03:04:05.678Z","readOnly":true}"""
        api.on("GET", "system") { FakeAdminApi.Answer(200, partly) }

        val answer = browserFor(api.url).get("/system")

        val page = said(answer)
        assertEquals(502 to "The server is not compatible with this UI", page.status to page.title)
        assertEquals("Server at ${api.url}" to false, page.foot to page.showsData)
        assertFalse("9.9.9" in answer.body || "2026-01-02" in answer.body, answer.body)
    }

    // What a server says of a request

    /** A server that fails says why, and the page shows it as the text that it is. */
    @Test
    fun aFailureOfTheServerIsReportedWithWhatTheServerSaysAsText() {
        val api = fake()
        api.on("GET", "system") { problem(500, "The server could not read <b>its</b> definitions location & <script>alert(1)</script>.") }

        val answer = browserFor(api.url).get("/system")

        val page = said(answer)
        assertEquals(502 to "The server failed", page.status to page.title)
        assertEquals(listOf("The server could not read <b>its</b> definitions location & <script>alert(1)</script>."), page.problems)
        assertEquals(0, answer.page.select("main b, script").size)
        assertEquals(listOf("Load this page again -> ''"), page.again)
    }

    /** A request that the API refuses is reported with what the API says, and a request for what is not there as such. */
    @Test
    fun aRefusalIsReportedWithWhatTheApiSays() {
        val url = AdminUrl.of("http://mockey.test:8081")
        val pages = FailurePages(url, hasToken = false)
        val errors = listOf(FieldErrorDto("from", "The time 'yesterday' is not written as ISO 8601 has it."))
        val gone = ApiFailure.Refused(ProblemDto("about:blank", "Not Found", 404, "No service has the identifier svc-gone."))
        val invalid = ApiFailure.Refused(ProblemDto("about:blank", "Bad Request", 400, "The request is not valid.", errors))

        val shown =
            listOf(gone, invalid).map { failure ->
                val page = pages.of(failure, canBeRepeated = true)
                page.status.value to Jsoup.parse(Html.document(page, "/services/svc-gone", url, system = null))
            }

        val (missing, refused) = shown
        assertEquals(404 to "Not there", missing.first to missing.second.select("main h1").text())
        assertEquals(listOf("No service has the identifier svc-gone."), missing.second.select("main .problem").map { it.text() })
        assertEquals(502 to "The server refused the request", refused.first to refused.second.select("main h1").text())
        val said = listOf("The request is not valid.", "from: The time 'yesterday' is not written as ISO 8601 has it.")
        assertEquals(said, refused.second.select("main .problem").map { it.text() })
        assertEquals(emptyList(), shown.flatMap { it.second.select("main a") })
    }

    private companion object {
        /** A time in milliseconds that is longer than the one that the UI of a test waits for an answer. */
        const val LONGER_THAN_THE_UI_WAITS = 1_500L
    }
}
