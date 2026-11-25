package io.github.dnedrow.mockey.ui

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pages of the UI as its links lead to them, from the first page on, see [Crawl]. The server is a
 * stand-in that holds what the pages show, see [hold], so that a page has the links that it has with content.
 *
 * The test names every address that the links lead to. So a page that a task adds is in it, and a link that
 * leads to a page that the UI does not have fails it.
 */
class UiCrawlTest {
    private val fakes = mutableListOf<FakeAdminApi>()
    private val uis = mutableListOf<MockeyUi>()

    @AfterTest
    fun stopEverything() {
        uis.forEach(MockeyUi::close)
        fakes.forEach(FakeAdminApi::close)
    }

    /**
     * Every link of a page leads to a page that the UI has. A link that leads to a place in a page finds
     * that place there, as the marker of a service finds what the page of the diagnostics says of the service.
     */
    @Test
    fun everyLinkOfAPageLeadsToAPageOfTheUiAndToAPlaceThatThePageHas() {
        val api = FakeAdminApi().also { fakes += it }
        api.hold(diagnostics = listOf(Samples.diagnostic))
        val settings = UiSettings(port = 0, adminUrl = AdminUrl.of(api.url), logLevel = LogLevel.WARN)
        val browser = Browser(MockeyUi.start(settings).also { uis += it })

        val found = Crawl.from(browser)

        val first = found.pages.getValue("/")
        assertEquals(302 to "/services", first.status to first.header("location"))
        assertEquals(setOf("/", "/services", "/system", "/diagnostics", "/services?tag=team-a"), found.pages.keys)
        assertEquals(emptyMap(), (found.pages - "/").mapValues { it.value.status }.filterValues { it != OK })
        val toPlaces = found.links.filter { it.place != null }
        val astray = toPlaces.filter { link -> found.pages.getValue(link.address).let { it.page.getElementById(link.place!!) } == null }
        assertEquals(emptyList(), astray.map { "$it" })
        assertTrue(toPlaces.any { it.to == "/diagnostics#service-svc-customers" }, "$toPlaces")
        assertEquals(emptyList(), api.violations)
    }

    private companion object {
        const val OK = 200
    }
}
