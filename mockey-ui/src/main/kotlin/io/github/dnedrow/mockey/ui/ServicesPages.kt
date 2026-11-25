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
import kotlinx.html.TBODY
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
 * the form is about, and sends the browser back to the overview as the form showed it, at the service.
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
                val back = ServicesPages.addressOf(call.tagsOfAddress(), id)
                ui.submit(call) { input ->
                    val mode = Modes.named(input.text(ServicesPages.MODE_FIELD)) ?: throw InvalidInput("responseMode", NO_MODE)
                    val service = ui.client.updateService(id, buildJsonObject { put("responseMode", Modes.nameOf(mode)) })
                    Outcome.Done(back, ServicesPages.noticeOfMode(service))
                }
            }
            post(ServicesPages.DEFAULT_SCENARIO) {
                val id = call.partOfAddress(ServicesPages.ID)
                val back = ServicesPages.addressOf(call.tagsOfAddress(), id)
                ui.submit(call) { input ->
                    val scenario = input.text(ServicesPages.SCENARIO_FIELD)
                    if (scenario.isEmpty()) throw InvalidInput("defaultScenarioId", NO_SCENARIO)
                    val service = ui.client.updateService(id, buildJsonObject { put("defaultScenarioId", scenario) })
                    Outcome.Done(back, ServicesPages.noticeOfDefaultScenario(service))
                }
            }
        }
    }

private const val NO_MODE = "The form names no response mode."
private const val NO_SCENARIO = "The form names no scenario."

/** What separates the tags of a text, as a server reads tags: whitespace of any kind. */
private val BETWEEN_TAGS = Regex("""[\s\p{Z}]+""")

/**
 * Returns the tags that the address of the request names, see [ServicesPages.TAG]: each in lower case, as a
 * server holds a tag, and each once. A parameter can hold several tags with spaces between them, which is
 * how the form of the filter states them.
 */
private fun ApplicationCall.tagsOfAddress(): List<String> {
    val stated = request.queryParameters.getAll(ServicesPages.TAG).orEmpty()
    return stated.flatMap { it.lowercase().split(BETWEEN_TAGS) }.filter { it.isNotEmpty() }.distinct()
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
            getForm(OVERVIEW, classes = "filter") {
                textField(FormState.of(TAG to tags.joinToString(" ")), TAG, "Tags", hint = FILTER_HINT)
                submit("Filter")
            }
            p(classes = "count") {
                +countOf(services.size, tags)
                if (tags.isNotEmpty()) {
                    +" "
                    a(href = OVERVIEW) { +"Show all services" }
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
                        for (service in services.sortedWith(BY_NAME)) row(view, system, service, concerning[service.id] ?: 0, tags)
                    }
                }
            }
            p(classes = "hint") { +LAST_USED_HINT }
        }

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
    private fun otherwiseOf(service: ServiceDto): String {
        val now = "as in ${Modes.nameOf(service.effectiveResponseMode)} mode for now"
        return if (service.scenarios.isEmpty()) "$now, because it has no scenario to answer with" else now
    }

    /** The row of [service] in the overview. The service has [diagnostics] diagnostics. */
    private fun TBODY.row(
        view: View,
        system: SystemStatusDto,
        service: ServiceDto,
        diagnostics: Int,
        tags: List<String>,
    ) {
        tr {
            id = Address.anchorOf(KIND, service.id)
            th(scope = ThScope.row) {
                +service.name
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
        postForm(view, Address.of(MODE, ID to service.id, *filterOf(tags)), classes = "switch") {
            div {
                role = "group"
                attributes["aria-label"] = "Response mode of ${service.name}"
                for (mode in ResponseModeDto.entries) {
                    button(type = ButtonType.submit, name = MODE_FIELD) {
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
            postForm(view, Address.of(DEFAULT_SCENARIO, ID to service.id, *filterOf(tags)), classes = "switch") {
                select {
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
                    attributes["aria-label"] = "Set the default scenario of ${service.name}"
                    +"Set"
                }
            }
        }
    }

    /** Shows [tags], each as a link to the overview of the services that carry it. */
    private fun FlowOrPhrasingContent.tagLinksOf(tags: List<String>) {
        for ((index, tag) in tags.withIndex()) {
            if (index > 0) +" "
            a(href = Address.of(OVERVIEW, TAG to tag), classes = "tag") { +tag }
        }
    }

    private val COLUMNS = listOf("Service", "Mock URL", "Response mode", "Default scenario", "Delay", "Tags", "Last used")

    private const val FILTER_HINT = "The overview lists the services that carry one of these tags. Tags have spaces between them."

    private const val LAST_USED_HINT =
        "A service was last used when it last answered a request. " +
            "The server keeps these times while it runs, and it can be told to forget them."
}
