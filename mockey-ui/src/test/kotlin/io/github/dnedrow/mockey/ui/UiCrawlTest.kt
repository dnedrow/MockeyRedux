package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.DiagnosticDto
import io.github.dnedrow.mockey.api.InspectorDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.github.dnedrow.mockey.api.TwistConfigurationDto
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
        val ofService = listOf("/service", "/service/edit", "/service/delete")
        val ofServices = listOf("svc-customers", "svc-orders").flatMap { id -> ofService.map { "$it?id=$id" } }
        val reached = setOf("/", "/services", "/services?tag=team-a", "/service/new", "/diagnostics", "/system") + ofServices
        assertEquals(reached, found.pages.keys)
        assertEquals(emptyMap(), (found.pages - "/").mapValues { it.value.status }.filterValues { it != OK })
        val toPlaces = found.links.filter { it.place != null }
        val astray = toPlaces.filter { link -> found.pages.getValue(link.address).let { it.page.getElementById(link.place!!) } == null }
        assertEquals(emptyList(), astray.map { "$it" })
        assertTrue(toPlaces.any { it.to == "/diagnostics#service-svc-customers" }, "$toPlaces")
        assertEquals(emptyList(), api.violations)
    }

    /**
     * The requirement "Safe methods have no side effects", as the UI has it: following the links of the UI
     * changes nothing, because what changes something is a form with the method POST that holds the token
     * of its page. Every other form asks for a page.
     */
    @Test
    fun followingTheLinksChangesNothingAndEveryFormThatChangesSomethingHoldsTheToken() {
        val api = FakeAdminApi().also { fakes += it }
        api.hold(diagnostics = listOf(Samples.diagnostic))
        val settings = UiSettings(port = 0, adminUrl = AdminUrl.of(api.url), logLevel = LogLevel.WARN)
        val browser = Browser(MockeyUi.start(settings).also { uis += it })

        val found = Crawl.from(browser)

        val forms = found.pages.values.flatMap { it.page.select("form") }
        val methods = forms.groupBy { it.attr("method") }
        assertEquals(setOf("get", "post"), methods.keys)
        assertEquals(emptyList(), methods.getValue("post").filter { it.select("[name=_token]").size != 1 }.map { it.attr("action") })
        assertEquals(emptyList(), methods.getValue("get").filter { it.select("[name=_token]").isNotEmpty() }.map { it.attr("action") })
        assertTrue(methods.getValue("post").size > FEW_FORMS, "The forms of the UI are to be read: ${forms.size}")
        assertEquals(emptyList(), api.requests.filter { it.method != "GET" }.map { it.line })
    }

    /**
     * The requirement "Stored content is shown as text", for every page that the links lead to: whatever a
     * server states, a page has no element and no attribute that the UI did not write, and no address that
     * is not one of the UI. The server of the test states one text wherever it states a text, for each of
     * the texts that are written to be read as markup or as an address.
     */
    @Test
    fun noPageShowsWhatAServerStatesAsMoreThanText() {
        val api = FakeAdminApi().also { fakes += it }
        val settings = UiSettings(port = 0, adminUrl = AdminUrl.of(api.url), logLevel = LogLevel.WARN)
        val browser = Browser(MockeyUi.start(settings).also { uis += it })

        for (text in Hostile.MARKUP + Hostile.ADDRESSES) {
            val scenarios = listOf(Samples.found.copy(id = text, name = text, tags = listOf(text)), Samples.missing.copy(name = text))
            val service =
                ServiceDto(
                    id = text,
                    name = text,
                    mockUrl = text,
                    description = text,
                    realUrls = listOf(text, "$text-2"),
                    tags = listOf(text),
                    scenarios = scenarios,
                    defaultScenarioId = text,
                    errorScenarioId = text,
                    requestRules = text,
                    inspectorName = text,
                    responseSchema = text,
                    lastUsedTime = text,
                )
            val diagnostic = DiagnosticDto(text, text, text, text, text, listOf(text))
            api.hold(listOf(service), listOf(diagnostic), mapOf(text to Hostile.written(text)), listOf(InspectorDto(text, global = true)))
            val twist = TwistConfigurationDto(text, text, emptyList())
            api.system = SystemStatusDto(text, text, text, readOnly = false, listOf(text), twist, text, legacyShimEnabled = true)

            val found = Crawl.from(browser)

            val addresses = found.pages.keys
            val reached = addresses.map { it.substringBefore('?') }.toSet()
            val ofService = setOf("/service", "/service/new", "/service/edit", "/service/delete")
            assertEquals(setOf("/", "/services", "/diagnostics", "/system") + ofService, reached, text)
            for ((address, answer) in found.pages - "/") {
                assertEquals(OK, answer.status, "$text at $address")
                assertEquals(emptyList(), Hostile.moreThanText(answer), "$text at $address")
            }
        }
        assertEquals(emptyList(), api.violations)
    }

    private companion object {
        const val OK = 200

        /** A number of forms that the pages of the UI have at least. */
        const val FEW_FORMS = 8
    }
}
