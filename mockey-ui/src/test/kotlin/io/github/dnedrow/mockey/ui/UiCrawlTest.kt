package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.DiagnosticDto
import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.InspectorDto
import io.github.dnedrow.mockey.api.MatchCriterionDto
import io.github.dnedrow.mockey.api.OutboundProxyDto
import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.PlanItemDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.ScenarioConformanceDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ScenarioReferenceDto
import io.github.dnedrow.mockey.api.SchemaReportDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.github.dnedrow.mockey.api.TwistConfigurationDto
import io.github.dnedrow.mockey.api.TwistPairDto
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
        api.holdSamples()
        val settings = UiSettings(port = 0, adminUrl = AdminUrl.of(api.url), logLevel = LogLevel.WARN)
        val browser = Browser(MockeyUi.start(settings).also { uis += it })

        val found = Crawl.from(browser)

        val first = found.pages.getValue("/")
        assertEquals(302 to "/services", first.status to first.header("location"))
        val ofService = listOf("/service", "/service/edit", "/service/delete", "/service/merge")
        val ofServices = listOf("svc-customers", "svc-orders").flatMap { id -> ofService.map { "$it?id=$id" } }
        val ofScenario = listOf("/scenario", "/scenario/delete")
        val ofScenarios = listOf("sc-found", "sc-missing").flatMap { id -> ofScenario.map { "$it?service=svc-customers&id=$id" } }
        val ofNewScenarios = listOf("svc-customers", "svc-orders").map { "/scenario/new?service=$it" }
        val ofPlan = listOf("/plan", "/plan/edit", "/plan/save", "/plan/delete")
        val ofPlans = listOf("/plans", "/plan/new") + ofPlan.map { "$it?id=plan-night" }
        val ofTwists = listOf("/twists", "/twist/new", "/twist?id=twist-german", "/twist/delete?id=twist-german")
        val tags = listOf("happy", "nightly", "team-a")
        val ofTags = listOf("/tags") + tags.map { "/tag/remove?tag=$it" } + tags.map { "/services?tag=$it" }
        val ofTheServer = listOf("/system", "/system/writable", "/system/reload", "/system/reset", "/proxy")
        val ofDefinitions = listOf("/definitions", "/definitions/export")
        val general = setOf("/", "/services", "/service/new", "/services/real-urls", "/diagnostics") + ofTheServer + ofDefinitions
        val lists = listOf("/history", "/history?service=svc-customers", "/history?service=svc-orders")
        val reloading = listOf("/history?refresh=on") + lists.drop(1).map { "$it&refresh=on" }
        val deletions = listOf("/history/delete", "/history/delete?service=svc-customers", "/history/delete?service=svc-orders")
        val ofEntry = listOf("/history/entry", "/history/replay", "/history/scenario").map { "$it?id=7" }
        val ofStatistics = listOf("/statistics", "/statistics/download?format=csv", "/statistics/download?format=json")
        val ofHistory = lists + reloading + deletions + ofEntry + ofStatistics
        val reached = general + ofServices + ofScenarios + ofNewScenarios + ofPlans + ofTwists + ofTags + ofHistory
        assertEquals(reached, found.pages.keys)
        val downloads = ofStatistics.drop(1) + ofDefinitions.last()
        assertEquals(downloads.toSet(), found.pages.filterValues(Hostile::isDownload).keys)
        assertEquals(emptyMap(), (found.pages - "/").mapValues { it.value.status }.filterValues { it != OK })
        val toPlaces = found.links.filter { it.place != null }
        val astray = toPlaces.filter { link -> found.pages.getValue(link.address).let { it.page.getElementById(link.place!!) } == null }
        assertEquals(emptyList(), astray.map { "$it" })
        assertTrue(toPlaces.any { it.to == "/diagnostics#service-svc-customers" }, "$toPlaces")
        assertTrue(toPlaces.any { it.to == "/system#last-used-times" }, "$toPlaces")
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
        api.holdSamples()
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
            val headers = listOf(HeaderDto(text, text))
            val asText = ScenarioDto(text, text, text, OK, headers, MatchCriterionDto.Text(text), text, 5, listOf(text), text)
            val asRules = Samples.missing.copy(name = text, matchCriterion = MatchCriterionDto.Rules(text))
            val scenarios = listOf(asText, asRules)
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
            val paths = mapOf(text to Hostile.written(text))
            val inspectors = listOf(InspectorDto(text, global = true))
            val conformance = ScenarioConformanceDto(text, text, conforms = false, listOf(text))
            val schemaReports = mapOf(text to SchemaReportDto.Checked(listOf(conformance)))
            val held = api.hold(listOf(service), listOf(diagnostic), paths, inspectors, ScenarioReferenceDto(text, text), schemaReports)
            val twist = TwistConfigurationDto(text, text, listOf(TwistPairDto(text, text)))
            api.system = SystemStatusDto(text, text, text, readOnly = false, listOf(text), twist, text, legacyShimEnabled = true)
            val plan = PlanDto(text, text, text, listOf(text), listOf(PlanItemDto(text, text, ResponseModeDto.STATIC, 5)), text)
            val plans = api.holdPlans(held, listOf(plan), default = text, paths = paths)
            api.holdTwists(listOf(twist), enabled = text, paths = paths)
            api.holdTags(held, plans, paths)
            api.holdProxy(OutboundProxyDto(text, enabled = true, username = text, passwordSet = true))
            api.holdHistory(listOf(Hostile.entry(1, text)), held)
            api.holdDefinitions(held, plans)
            api.holdSystem(held, plans)

            val found = Crawl.from(browser)

            val addresses = found.pages.keys
            val reached = addresses.map { it.substringBefore('?') }.toSet()
            val aboutService = setOf("/service/merge", "/service/schema-report")
            val ofService = setOf("/service", "/service/new", "/service/edit", "/service/delete") + aboutService
            val ofScenario = setOf("/scenario", "/scenario/new", "/scenario/delete")
            val ofPlan = setOf("/plans", "/plan", "/plan/new", "/plan/edit", "/plan/save", "/plan/delete")
            val ofRouting = setOf("/twists", "/twist", "/twist/new", "/twist/delete", "/tags", "/tag/remove")
            val ofTheServer = setOf("/system", "/system/reload", "/system/reset", "/proxy", "/definitions", "/definitions/export")
            val general = setOf("/", "/services", "/services/real-urls", "/diagnostics") + ofTheServer
            val ofEntry = setOf("/history/entry", "/history/replay", "/history/scenario")
            val ofHistory = setOf("/history", "/history/delete", "/statistics", "/statistics/download") + ofEntry
            assertEquals(general + ofService + ofScenario + ofPlan + ofRouting + ofHistory, reached, text)
            for ((address, answer) in found.pages - "/") {
                assertEquals(OK, answer.status, "$text at $address")
                // A download isn't read as a page, so it can hold content exactly as the server states it.
                val isDownload = address.startsWith("/statistics/download") || address == "/definitions/export"
                assertEquals(isDownload, Hostile.isDownload(answer), "$text at $address")
                if (!isDownload) assertEquals(emptyList(), Hostile.moreThanText(answer), "$text at $address")
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
