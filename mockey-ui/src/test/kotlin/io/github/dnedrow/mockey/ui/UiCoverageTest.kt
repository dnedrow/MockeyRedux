package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.ResponseModeDto
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.getAllRoutes
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 13.11: the requirements "Usable without JavaScript" and "Coverage of management operations", checked
 * for the UI as a whole.
 *
 * One walk goes through the UI the way someone with a browser without scripting does (see [Journey]). It
 * opens every page and submits every form of the UI, and a stand-in server records which operations of the
 * admin API each step of the walk reaches. [COVERAGE] is the coverage list: it names, for each operation,
 * the step that reaches it.
 */
class UiCoverageTest {
    private val started = Started()

    @AfterTest
    fun stopEverything() = started.close()

    /** What a test needs of a walk: the walk itself, the stand-in server, and the routes of the UI. */
    private class Walked(
        val journey: Journey,
        val api: FakeAdminApi,
        val routes: List<String>,
    )

    /**
     * Starts a stand-in server that holds the samples, starts a UI for it, and walks through the whole UI.
     * The walk has to get everywhere with links, forms, and full page loads: it only opens what a page
     * refers to, it only submits forms that a page shows, and every answer is a page or a file. The UI only
     * sends requests that the description of the admin API allows.
     */
    private fun walked(): Walked {
        val api = started.api()
        api.holdSamples()
        val routes = mutableListOf<String>()
        // This area is installed after the areas of the UI, so it sees every route that they added.
        val listing = Area { routing, _ -> routes += (routing as RoutingNode).getAllRoutes().mapNotNull(::nameOf) }
        val journey = Journey(started.browserFor(api, UiApplication.AREAS + listing), api)
        journey.walkTheWholeUi()
        assertEquals(emptyList(), journey.failures)
        assertEquals(emptyList(), api.violations)
        return Walked(journey, api, routes)
    }

    /**
     * Returns the name of [route] the way a [Journey] names a form, such as `POST /service/edit`, or `null`
     * for a route that isn't for one method.
     */
    private fun nameOf(route: RoutingNode): String? {
        val method = (route.selector as? HttpMethodRouteSelector)?.method ?: return null
        return "${method.value} ${route.parent}"
    }

    /** Returns whether one of the paths in [opened] fits the path [route], which can have parameters in braces. */
    private fun isOpened(
        route: String,
        opened: Set<String>,
    ): Boolean {
        val pattern = route.split('/').joinToString("/") { if (it.startsWith("{")) "[^/]+" else Regex.escape(it) }
        return opened.any(Regex(pattern)::matches)
    }

    // The requirement "Usable without JavaScript"

    /**
     * The scenario "Core flow with scripting disabled": a user creates a service, adds a scenario, switches
     * the service to static mode, and applies a plan. Every step succeeds and shows its result.
     */
    @Test
    fun theCoreFlowWorksWithScriptingDisabled() {
        val api = started.api()
        val services = api.holdSamples()
        val journey = Journey(started.browserFor(api), api)

        val overview = journey.open("/")
        val newService = journey.follow(overview, "a[href=/service/new]")
        val created = journey.submit(newService.formTo("/service/new"), mapOf("name" to "Billing", "mockUrl" to "billing"))
        val newScenario = journey.follow(created, "a[href^=/scenario/new]")
        val added = journey.submit(newScenario.formTo("/scenario/new"), mapOf("name" to "paid", "body" to """{"paid":true}"""))
        val listed = journey.follow(added, "nav a[href=/services]")
        val switch = listed.form("form:has([aria-label='Response mode of Billing'])")
        val switched = journey.submit(switch, pressed = switch.select("button[value=static]").first())
        val plan = journey.follow(journey.follow(switched, "nav a[href=/plans]"), "a[href='/plan?id=plan-night']")
        val applied = journey.submit(plan.formTo("/plan/apply"))

        assertEquals(emptyList(), journey.failures)
        val notices =
            listOf(
                "The service Billing was created.",
                "The scenario paid was created.",
                "The service Billing is in static mode now.",
            )
        assertEquals(notices, listOf(created, added, switched).map { it.notice })
        assertEquals("Billing" to "Scenario paid", created.page.select("main h1").text() to added.page.select("main h1").text())
        val pressed = switched.page.select("[aria-label='Response mode of Billing'] button[aria-pressed=true]")
        assertEquals(listOf("static"), pressed.map { it.attr("value") })
        assertEquals("The plan Night was applied", applied.page.select("main h1").text())
        assertEquals("1 service now has the settings of the plan.", applied.page.select("main h1 + p").text())
        val billing = services.values.single { it.name == "Billing" }
        assertEquals(listOf("paid") to ResponseModeDto.STATIC, billing.scenarios.map { it.name } to billing.responseMode)
        assertEquals(ResponseModeDto.STATIC to 250L, services.getValue("svc-customers").let { it.responseMode to it.delayMillis })
        assertEquals(emptyList(), api.violations)
    }

    /**
     * The walk leaves out no page of the UI: it opens every address at which the UI answers a request for
     * a page or a file. The UI has no other addresses than those and the ones that take a form.
     */
    @Test
    fun theWalkOpensEveryPageOfTheUi() {
        val walked = walked()

        val pages = walked.routes.filter { it.startsWith("GET ") }.map { it.substringAfter(' ') }
        assertEquals(emptyList(), pages.filterNot { isOpened(it, walked.journey.opened) })
        assertEquals(PAGES, pages.size)
        // One address stands for all files of the UI. The walk loads each of them: the stylesheet, the icon, and the script.
        assertEquals(emptyList(), Assets.names.map(Assets::path).filterNot { it in walked.journey.opened })
        assertEquals(3, Assets.names.size)
        assertEquals(setOf("GET", "POST"), walked.routes.map { it.substringBefore(' ') }.toSet())
    }

    /**
     * The walk submits every form of the UI: each form that one of its pages shows, and a form for each
     * address at which the UI takes a form.
     */
    @Test
    fun theWalkSubmitsEveryFormOfTheUi() {
        val walked = walked()

        val takingForms = walked.routes.filter { it.startsWith("POST ") }
        assertEquals(emptySet(), walked.journey.shown - walked.journey.submitted)
        assertEquals(emptyList(), takingForms - walked.journey.submitted)
        assertEquals(FORMS to FORMS_WITH_GET, takingForms.size to walked.journey.submitted.count { it.startsWith("GET ") })
    }

    // The requirement "Coverage of management operations"

    /**
     * The coverage list has no gaps: every operation that the description of the admin API names is in
     * exactly one of the three lists.
     */
    @Test
    fun theCoverageListNamesEveryOperationOfTheAdminApi() {
        val listed = COVERAGE.map { it.first } + SHOWN_FROM_ANOTHER_ANSWER.keys + NOT_OFFERED.keys

        assertEquals(ApiContract.operations.map { it.id }.sorted(), listed.sorted())
        assertEquals(OPERATIONS, listed.size)
    }

    /**
     * Each operation of the coverage list is reached at the step that the list names for it. The UI asks
     * the server for no operation that the list says it doesn't ask for.
     */
    @Test
    fun theWalkReachesEveryOperationAtItsStep() {
        val walked = walked()

        val reached = walked.journey.reached
        assertEquals(emptyList(), COVERAGE.filterNot { (operation, step) -> operation in reached[step].orEmpty() })
        val asked = reached.values.flatten().toSet()
        assertEquals(COVERAGE.map { it.first }.toSet(), asked)
        assertEquals(emptyList(), COVERAGE.map { it.second }.distinct() - reached.keys)
    }

    /**
     * The UI never asks for the scenarios of a service or for the tag filter by themselves, because the
     * answers that it needs anyway hold both. The pages show exactly what those two operations return.
     */
    @Test
    fun whatTheUiReadsFromAnotherAnswerIsWhatItsOwnOperationReturns() {
        val api = started.api()
        api.holdSamples()
        api.system = api.system.copy(tagFilter = listOf("nightly", "happy"))
        val browser = started.browserFor(api)

        val service = browser.get("/service?id=svc-customers")
        val ofService = service.page.select("main table.scenarios tbody th a").map { it.text() }
        val filterState = browser.get("/tags").page.select("main > p:not(.notice)")[1]
        val ofTags = filterState.select(".tag").map { it.text() }
        val asked = api.operations.toSet()
        val client = AdminClient(AdminUrl.of(api.url), null)
        val (scenarios, filter) = client.use { runBlocking { it.listScenarios("svc-customers") to it.getTagFilter() } }

        assertEquals(scenarios.map { it.name }.sorted(), ofService)
        assertEquals(listOf("found", "missing"), ofService)
        assertEquals(filter.tags, ofTags)
        assertEquals(listOf("nightly", "happy"), ofTags)
        assertEquals(SHOWN_FROM_ANOTHER_ANSWER.values.toSet(), asked intersect SHOWN_FROM_ANOTHER_ANSWER.values.toSet())
        assertEquals(emptySet(), asked intersect SHOWN_FROM_ANOTHER_ANSWER.keys)
    }

    private companion object {
        /** The number of operations that the description of the admin API names. */
        const val OPERATIONS = 61

        /** The number of routes of the UI that answer a request for a page or a file. 19 of them are the pages of the help. */
        const val PAGES = 61

        /** The number of routes of the UI that take a form, and the number of forms that ask for a page. */
        const val FORMS = 40
        const val FORMS_WITH_GET = 5

        /**
         * The coverage list: each operation that the UI asks the server for, with the step of the walk that
         * reaches it. The operations are in the order of the description of the admin API.
         */
        val COVERAGE =
            listOf(
                // Services
                "listServices" to "the overview",
                "createService" to "the form for a new service",
                "getService" to "the page of a service",
                "updateService" to "the form that edits a service",
                "deleteService" to "the confirmation that deletes a service",
                "duplicateService" to "the button that duplicates a service",
                "mergeServices" to "the merge of services",
                "getSchemaReport" to "the schema report",
                "deriveRealUrls" to "the derivation of real URLs",
                // Scenarios
                "createScenario" to "the form for a new scenario",
                "getScenario" to "the confirmation that deletes a scenario",
                "updateScenario" to "the form that edits a scenario",
                "deleteScenario" to "the confirmation that deletes a scenario",
                "getUniversalErrorScenario" to "the page of a scenario",
                "setUniversalErrorScenario" to "the button that makes a scenario the universal error scenario",
                "clearUniversalErrorScenario" to "the button that ends the designation as the universal error scenario",
                // Plans
                "listPlans" to "the list of plans",
                "savePlan" to "the form for a new plan",
                "getPlan" to "the page of a plan",
                "updatePlan" to "the form that edits a plan",
                "deletePlan" to "the confirmation that deletes a plan",
                "saveIntoPlan" to "the form that saves settings into a plan",
                "applyPlan" to "the button that applies a plan",
                "getDefaultPlan" to "the list of plans",
                "setDefaultPlan" to "the button that makes a plan the default plan",
                "clearDefaultPlan" to "the button that ends the designation as the default plan",
                // Twist configurations
                "listTwists" to "the list of twist configurations",
                "createTwist" to "the form for a new twist configuration",
                "getTwist" to "the page of a twist configuration",
                "updateTwist" to "the form that edits a twist configuration",
                "deleteTwist" to "the confirmation that deletes a twist configuration",
                "enableTwist" to "the button that enables a twist configuration",
                "disableTwist" to "the button that disables a twist configuration",
                // The tag filter and the tags
                "setTagFilter" to "the form that sets the tag filter",
                "clearTagFilter" to "the button that turns the tag filter off",
                "listTags" to "the page for tags",
                "removeTag" to "the confirmation that removes a tag",
                // The outbound proxy and the upstream session
                "getUpstreamProxy" to "the page for the outbound proxy",
                "updateUpstreamProxy" to "the form that sets the outbound proxy",
                "clearUpstreamSession" to "the button that clears the upstream session",
                // The history and the statistics
                "listHistory" to "the history",
                "deleteHistory" to "the confirmation that deletes history entries",
                "getHistoryEntry" to "the page of a history entry",
                "updateHistoryEntry" to "the button that flags a history entry",
                "deleteHistoryEntry" to "the button that deletes a history entry",
                "replayHistoryEntry" to "the form that replays a history entry",
                "saveHistoryEntryAsScenario" to "the form that saves a history entry as a scenario",
                "getStatistics" to "the statistics",
                // The definitions as a whole
                "exportDefinitions" to "the download of the definitions",
                "importDefinitions" to "the form that imports a document",
                "listDiagnostics" to "the diagnostics",
                "search" to "the search",
                // The server
                "getSystem" to "the page of the server",
                "updateSystem" to "the confirmation that switches to writable mode",
                "reloadDefinitions" to "the confirmation that reloads the definitions",
                "resetServer" to "the confirmation that resets the server",
                "clearLastUsedTimes" to "the button that clears the last-used times",
                "listInspectors" to "the form for a new service",
            )

        /**
         * The operations whose answers the UI shows without asking for them: each one with the operation
         * whose answer holds the same information, and which a page needs anyway.
         *
         * - The page of a service lists the scenarios of the service, which are part of the service.
         * - The page for tags shows the tag filter, which is part of the status that every page asks for.
         */
        val SHOWN_FROM_ANOTHER_ANSWER = mapOf("listScenarios" to "getService", "getTagFilter" to "getSystem")

        /** The operations that the UI doesn't offer, each with the reason. */
        val NOT_OFFERED = mapOf("getDescription" to "It returns the description of the admin API, and it manages nothing.")
    }
}
