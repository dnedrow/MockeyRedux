package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.DiagnosticDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FlowOrPhrasingContent
import kotlinx.html.TagConsumer
import kotlinx.html.ThScope
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.role
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The area of the pages about the services: the overview of them, and the switches of the overview, which
 * give a service another response mode and another default scenario.
 *
 * A switch is a form of the overview. The UI asks the server to change the one attribute of the service that
 * the form is about, and sends the browser back to the overview as the form showed it, at the service. For
 * a browser that runs the script of the UI, the answer is the row of the service alone, which the script
 * puts in the place of the row that the page shows, see [Enhancement].
 *
 * See `docs/decisions/ui-pages.md`.
 */
internal val ServicesArea =
    Area { routing, ui ->
        with(routing) {
            get(ServicesPages.OVERVIEW) {
                val tags = call.tagsOfAddress()
                ui.show(call) { view, system ->
                    val services = ui.client.listServices(tags)
                    ServicesPages.overview(view, system, services, ui.client.listDiagnostics(), tags)
                }
            }
            post(ServicesPages.MODE) {
                val id = call.partOfAddress(ServicesPages.ID)
                val tags = call.tagsOfAddress()
                ui.submit(call) { input ->
                    val mode = Modes.named(input.text(ServicesPages.MODE_FIELD)) ?: throw InvalidInput("responseMode", NO_MODE)
                    val service = ui.client.updateService(id, buildJsonObject { put("responseMode", Modes.nameOf(mode)) })
                    Outcome.Done(ServicesPages.addressOf(tags, id), ServicesPages.noticeOfMode(service)) { view, system ->
                        ServicesPages.rowOf(view, system, service, ui.client.listDiagnostics(), tags)
                    }
                }
            }
            post(ServicesPages.DEFAULT_SCENARIO) {
                val id = call.partOfAddress(ServicesPages.ID)
                val tags = call.tagsOfAddress()
                ui.submit(call) { input ->
                    val scenario = input.text(ServicesPages.SCENARIO_FIELD)
                    if (scenario.isEmpty()) throw InvalidInput("defaultScenarioId", NO_SCENARIO)
                    val service = ui.client.updateService(id, buildJsonObject { put("defaultScenarioId", scenario) })
                    Outcome.Done(ServicesPages.addressOf(tags, id), ServicesPages.noticeOfDefaultScenario(service)) { view, system ->
                        ServicesPages.rowOf(view, system, service, ui.client.listDiagnostics(), tags)
                    }
                }
            }
        }
    }

private const val NO_MODE = "The form names no response mode."
private const val NO_SCENARIO = "The form names no scenario."

/**
 * Returns the tags that the address of the request names, see [ServicesPages.TAG]: each in lower case, as a
 * server holds a tag, and each once. A parameter can hold several tags with spaces between them, which is
 * how the form of the filter states them.
 */
private fun ApplicationCall.tagsOfAddress(): List<String> {
    val stated = request.queryParameters.getAll(ServicesPages.TAG).orEmpty()
    return stated.flatMap { tagsIn(it.lowercase()) }.distinct()
}

/** Shows [tags], each as a link to the overview of the services that carry it. */
internal fun FlowOrPhrasingContent.tagLinksOf(tags: List<String>) {
    for ((index, tag) in tags.withIndex()) {
        if (index > 0) +" "
        a(href = Address.of(ServicesPages.OVERVIEW, ServicesPages.TAG to tag), classes = "tag") { +tag }
    }
}

/** The pages about the services. */
internal object ServicesPages {
    /** The path of the overview. */
    const val OVERVIEW = "/services"

    /** The path that the form is sent to that gives a service another response mode. */
    const val MODE = "/services/mode"

    /** The path that the form is sent to that gives a service another default scenario. */
    const val DEFAULT_SCENARIO = "/services/default-scenario"

    /** The parameter of an address that names a service by its identifier. */
    const val ID = "id"

    /**
     * The parameter of the address of the overview that names a tag to filter by. An address can have it
     * more than once, and the overview lists the services that carry one of the tags.
     */
    const val TAG = "tag"

    /** The field of a form that names a response mode, see [Modes.nameOf]. */
    const val MODE_FIELD = "mode"

    /** The field of a form that names a scenario by its identifier. */
    const val SCENARIO_FIELD = "scenario"

    private const val KIND = "service"

    private val BY_NAME = compareBy<ServiceDto, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }
    private val SCENARIOS_BY_NAME = compareBy<ScenarioDto, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }

    /**
     * Returns the address of the overview of the services that carry one of [tags], or of all services when
     * there are none, at the service [serviceId] when one is given.
     */
    fun addressOf(
        tags: List<String>,
        serviceId: String? = null,
    ): String {
        val overview = Address.of(OVERVIEW, *filterOf(tags))
        return if (serviceId == null) overview else "$overview#${Address.anchorOf(KIND, serviceId)}"
    }

    private fun filterOf(tags: List<String>): Array<Pair<String, String?>> = tags.map { TAG to it }.toTypedArray()

    /**
     * The overview: [services] by their names, each with the URL at which a client calls it at the server
     * that states [system], its response mode, its default scenario, its delay, its tags, and when it was
     * last used. A service that some of [diagnostics] concern has a marker that leads to them.
     *
     * The services are the ones that carry one of [tags], or all services of the server when there are no
     * tags. The tags are in the address of the page, so the address shows the same list wherever it is
     * opened.
     */
    fun overview(
        view: View,
        system: SystemStatusDto,
        services: List<ServiceDto>,
        diagnostics: List<DiagnosticDto>,
        tags: List<String>,
    ): Page =
        Page("Services") {
            div(classes = "tools") {
                getForm(OVERVIEW, classes = "filter") {
                    textField(FormState.of(TAG to tags.joinToString(" ")), TAG, "Tags", hint = FILTER_HINT)
                    submit("Filter")
                }
                searchFormOf("", SearchPages.BRIEF_HINT)
            }
            p(classes = "count") {
                +countOf(services.size, tags)
                if (tags.isNotEmpty()) {
                    +" "
                    a(href = OVERVIEW) { +"Show all services" }
                }
            }
            p(classes = "actions") {
                a(href = ServicePages.NEW) { +"New service" }
                a(href = RealUrlPages.PAGE) { +"Derive real URLs" }
                a(href = DiagnosticsPages.PATH) { +"Diagnostics" }
            }
            if (system.tagFilter.isNotEmpty()) {
                p(classes = "note") {
                    +"$TAG_FILTER_IS_ON "
                    tagsOf(system.tagFilter)
                    +". "
                    a(href = TagPages.PAGE) { +"Change the tag filter" }
                }
            }
            if (services.isEmpty()) return@Page
            div(classes = "scroll") {
                table(classes = "services") {
                    thead {
                        tr { COLUMNS.forEach { th(scope = ThScope.col) { +it } } }
                    }
                    tbody {
                        val concerning = diagnostics.groupingBy { it.serviceId }.eachCount()
                        for (service in services.sortedWith(BY_NAME)) consumer.row(view, system, service, concerning[service.id] ?: 0, tags)
                    }
                }
            }
            p(classes = "hint") {
                +"$LAST_USED_HINT "
                a(href = "${SystemPages.PAGE}#${SystemPages.LAST_USED_ANCHOR}") { +"Clear the last-used times" }
            }
        }

    /**
     * Returns the row of [service] in the overview as a part of its own, as the overview of the services
     * that carry one of [tags] shows it. [diagnostics] are the diagnostics of the server, of which the row
     * counts the ones that concern the service.
     */
    fun rowOf(
        view: View,
        system: SystemStatusDto,
        service: ServiceDto,
        diagnostics: List<DiagnosticDto>,
        tags: List<String>,
    ): Part = Part(Place.ROW) { row(view, system, service, diagnostics.count { it.serviceId == service.id }, tags) }

    /** Returns what the form says that gave [service] another response mode, where [service] is the service as it is now. */
    fun noticeOfMode(service: ServiceDto): String {
        val now = "The service ${briefly(service.name)} is in ${Modes.nameOf(service.responseMode)} mode now."
        return if (service.effectiveResponseMode == service.responseMode) now else "$now It answers ${otherwiseOf(service)}."
    }

    /** Returns what the form says that gave [service] another default scenario, where [service] is the service as it is now. */
    fun noticeOfDefaultScenario(service: ServiceDto): String {
        val scenario = service.scenarios.firstOrNull { it.id == service.defaultScenarioId }
        val default = "The default scenario of the service ${briefly(service.name)}"
        return if (scenario == null) "$default was set." else "$default is ${briefly(scenario.name)} now."
    }

    /** Returns how many services the overview lists, which are [count] services with one of [tags], or all services. */
    private fun countOf(
        count: Int,
        tags: List<String>,
    ): String {
        val carried = if (tags.size == 1) "the tag ${tags.single()}" else "one of the tags ${tags.joinToString(", ")}"
        return when {
            tags.isEmpty() && count == 0 -> "The server holds no service."
            tags.isEmpty() -> if (count == 1) "1 service" else "$count services"
            count == 0 -> "No service carries $carried."
            count == 1 -> "1 service carries $carried."
            else -> "$count services carry $carried."
        }
    }

    /**
     * Returns how [service] answers while it does not answer in the mode that it is set to, as the end of a
     * sentence. A service without a scenario forwards every request, whatever its mode is.
     */
    fun otherwiseOf(service: ServiceDto): String {
        val now = "as in ${Modes.nameOf(service.effectiveResponseMode)} mode for now"
        return if (service.scenarios.isEmpty()) "$now, because it has no scenario to answer with" else now
    }

    /** The row of [service] in the overview. The service has [diagnostics] diagnostics. */
    private fun TagConsumer<*>.row(
        view: View,
        system: SystemStatusDto,
        service: ServiceDto,
        diagnostics: Int,
        tags: List<String>,
    ) {
        tr {
            id = Address.anchorOf(KIND, service.id)
            th(scope = ThScope.row) {
                a(href = ServicePages.addressOf(service.id)) { +service.name }
                if (diagnostics > 0) {
                    +" "
                    a(href = DiagnosticsPages.addressOf(service.id), classes = "marker") {
                        +(if (diagnostics == 1) "1 diagnostic" else "$diagnostics diagnostics")
                    }
                }
            }
            td { urlOf(callableUrlOf(system, service.mockUrl)) }
            td(classes = "mode") { modeOf(view, service, tags) }
            td(classes = "default-scenario") { defaultScenarioOf(view, service, tags) }
            td { +"${service.delayMillis} ms" }
            td { tagLinksOf(service.tags) }
            td {
                val used = service.lastUsedTime
                if (used == null) span(classes = "none") { +"never" } else timeOf(used)
            }
        }
    }

    /**
     * The response mode of [service], as the switch of it: a button for every mode, of which the one of the
     * mode that the service is set to says so. Where the service answers in another mode than that one, the
     * cell says in which.
     */
    private fun FlowContent.modeOf(
        view: View,
        service: ServiceDto,
        tags: List<String>,
    ) {
        postForm(view, Address.of(MODE, ID to service.id, *filterOf(tags)), classes = "switch", inPlace = true) {
            div {
                role = "group"
                attributes["aria-label"] = "Response mode of ${service.name}"
                for (mode in ResponseModeDto.entries) {
                    button(type = ButtonType.submit, name = MODE_FIELD) {
                        // With an identifier, the button that was pressed has the focus again in the row that takes the place of this one.
                        id = Address.anchorOf("mode-${Modes.nameOf(mode)}", service.id)
                        value = Modes.nameOf(mode)
                        attributes["aria-pressed"] = "${mode == service.responseMode}"
                        +Modes.labelOf(mode)
                    }
                }
            }
        }
        if (service.effectiveResponseMode != service.responseMode) {
            p(classes = "hint effective") { +"Answers ${otherwiseOf(service)}." }
        }
    }

    /**
     * The default scenario of [service]: its name, or the switch of it where the service has other scenarios
     * to choose from, which lists the scenarios by their names.
     */
    private fun FlowContent.defaultScenarioOf(
        view: View,
        service: ServiceDto,
        tags: List<String>,
    ) {
        val scenarios = service.scenarios.sortedWith(SCENARIOS_BY_NAME)
        val default = scenarios.firstOrNull { it.id == service.defaultScenarioId }
        if (scenarios.isEmpty()) {
            span(classes = "none") { +"none" }
        } else if (scenarios.size == 1 && default != null) {
            +default.name
        } else {
            val action = Address.of(DEFAULT_SCENARIO, ID to service.id, *filterOf(tags))
            postForm(view, action, classes = "switch", inPlace = true) {
                select {
                    id = Address.anchorOf("default-scenario", service.id)
                    attributes["name"] = SCENARIO_FIELD
                    attributes["aria-label"] = "Default scenario of ${service.name}"
                    // A browser shows the first choice as the chosen one where none is, so a choice says that none is.
                    if (default == null) {
                        option {
                            value = ""
                            selected = true
                            disabled = true
                            +"none"
                        }
                    }
                    for (scenario in scenarios) {
                        option {
                            value = scenario.id
                            selected = scenario.id == default?.id
                            +scenario.name
                        }
                    }
                }
                +" "
                button(type = ButtonType.submit) {
                    id = Address.anchorOf("set-default-scenario", service.id)
                    attributes["aria-label"] = "Set the default scenario of ${service.name}"
                    +"Set"
                }
            }
        }
    }

    private val COLUMNS = listOf("Service", "Mock URL", "Response mode", "Default scenario", "Delay", "Tags", "Last used")

    private const val FILTER_HINT = "The overview lists the services that carry one of these tags. Tags have spaces between them."

    private const val TAG_FILTER_IS_ON =
        "The tag filter of the server is on. A service answers requests only if the service or one of its scenarios " +
            "carries one of these tags:"

    private const val LAST_USED_HINT =
        "A service was last used when it last answered a request. The server keeps these times in memory until it stops."
}
