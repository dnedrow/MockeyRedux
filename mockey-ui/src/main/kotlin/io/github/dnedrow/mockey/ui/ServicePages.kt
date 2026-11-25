package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.InspectorDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.radioInput
import kotlinx.html.span
import kotlinx.html.textInput
import kotlinx.html.ul
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The area of the pages about one service: the page of the service, the form that creates a service, the
 * form that changes one, the step that asks before a service is deleted, and the button that duplicates one.
 *
 * See `docs/decisions/ui-pages.md`.
 */
internal val ServiceArea =
    Area { routing, ui ->
        with(routing) {
            get(ServicePages.PAGE) {
                val id = call.partOfAddress(ServicesPages.ID)
                ui.show(call) { view, system ->
                    val service = ui.client.getService(id)
                    val concerning = ui.client.listDiagnostics().count { it.serviceId == service.id }
                    ServicePages.service(view, system, service, concerning)
                }
            }
            get(ServicePages.NEW) {
                ui.show(call) { view, system -> ServicePages.new(view, system, ServiceForm.blank(), ui.client.listInspectors()) }
            }
            post(ServicePages.NEW) {
                val again: suspend (View, SystemStatusDto, FormState) -> Page = { view, system, state ->
                    ServicePages.new(view, system, state, ui.client.listInspectors())
                }
                ui.submit(call, again) { input ->
                    val created = ui.client.createService(ServiceForm.attributesOf(input))
                    Outcome.Done(ServicePages.addressOf(created.id), "The service ${briefly(created.name)} was created.")
                }
            }
            get(ServicePages.EDIT) {
                val id = call.partOfAddress(ServicesPages.ID)
                ui.show(call) { view, system ->
                    val service = ui.client.getService(id)
                    ServicePages.edit(view, system, service, ServiceForm.of(service), ui.client.listInspectors())
                }
            }
            post(ServicePages.EDIT) {
                val id = call.partOfAddress(ServicesPages.ID)
                val again: suspend (View, SystemStatusDto, FormState) -> Page = { view, system, state ->
                    ServicePages.edit(view, system, ui.client.getService(id), state, ui.client.listInspectors())
                }
                ui.submit(call, again) { input ->
                    val saved = ui.client.updateService(id, ServiceForm.attributesOf(input))
                    Outcome.Done(ServicePages.addressOf(id), "The service ${briefly(saved.name)} was saved.")
                }
            }
            getAndPost(ServicePages.DELETE) {
                val id = call.partOfAddress(ServicesPages.ID)
                ui.confirm(call, ask = { view, _ -> ServicePages.deletion(view, ui.client.getService(id)) }) {
                    val service = ui.client.getService(id)
                    ui.client.deleteService(id)
                    Outcome.Done(ServicesPages.OVERVIEW, "The service ${briefly(service.name)} was deleted.")
                }
            }
            post(ServicePages.DUPLICATE) {
                val id = call.partOfAddress(ServicesPages.ID)
                ui.submit(call) {
                    val copy = ui.client.duplicateService(id)
                    Outcome.Done(ServicePages.addressOf(copy.id), "The service was duplicated. This is the page of the copy.")
                }
            }
        }
    }

/** The pages about one service. */
internal object ServicePages {
    /** The path of the page of a service. */
    const val PAGE = "/service"

    /** The path of the form that creates a service. */
    const val NEW = "/service/new"

    /** The path of the form that changes a service. */
    const val EDIT = "/service/edit"

    /** The path of the step that asks before a service is deleted. */
    const val DELETE = "/service/delete"

    /** The path that the button is sent to that duplicates a service. */
    const val DUPLICATE = "/service/duplicate"

    private const val NONE = "none"

    private val SCENARIOS_BY_NAME = compareBy<ScenarioDto, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }

    /** Returns the address of the page of the service [serviceId]. */
    fun addressOf(serviceId: String): String = Address.of(PAGE, ServicesPages.ID to serviceId)

    private fun addressOf(
        path: String,
        service: ServiceDto,
    ): String = Address.of(path, ServicesPages.ID to service.id)

    /**
     * The page of [service]: what the service is set to, with the URL at which a client calls it at the
     * server that states [system], its scenarios, and what leads to the forms that change it. The service
     * has [diagnostics] diagnostics, which a marker leads to.
     */
    fun service(
        view: View,
        system: SystemStatusDto,
        service: ServiceDto,
        diagnostics: Int,
    ): Page =
        Page(service.name) {
            if (diagnostics > 0) {
                p {
                    a(href = DiagnosticsPages.addressOf(service.id), classes = "marker") {
                        +(if (diagnostics == 1) "1 diagnostic" else "$diagnostics diagnostics")
                    }
                }
            }
            div(classes = "actions") {
                a(href = addressOf(EDIT, service)) { +"Edit" }
                postForm(view, addressOf(DUPLICATE, service), classes = "inline") { submit("Duplicate") }
                a(href = addressOf(DELETE, service)) { +"Delete" }
            }
            dl {
                dt { +"Mock URL" }
                dd { urlOf(callableUrlOf(system, service.mockUrl)) }
                service.description?.let { description ->
                    dt { +"Description" }
                    dd(classes = "lines") { +description }
                }
                dt { +"Response mode" }
                dd { +modeOf(service) }
                dt { +"Default scenario" }
                dd { +(service.scenarios.firstOrNull { it.id == service.defaultScenarioId }?.name ?: NONE) }
                dt { +"Delay" }
                dd { +"${service.delayMillis} ms" }
                dt { +"Tags" }
                dd { if (service.tags.isEmpty()) +NONE else tagLinksOf(service.tags) }
                dt { +"Real URLs" }
                dd { realUrlsOf(service) }
                dt { +"Request rules" }
                dd { +usedOrNot(service.requestRules, service.requestRulesEnabled, RULES_ON, RULES_OFF) }
                dt { +"Inspector" }
                dd { +(service.inspectorName ?: NONE) }
                dt { +"Response schema" }
                dd { +usedOrNot(service.responseSchema, service.responseSchemaEnabled, SCHEMA_ON, SCHEMA_OFF) }
                dt { +"Last used" }
                dd {
                    val used = service.lastUsedTime
                    if (used == null) +"never" else timeOf(used)
                }
            }
            h2 { +"Scenarios" }
            scenariosOf(service)
        }

    /** The page with the form that creates a service, which is shown with [state]. A service can name one of [inspectors]. */
    fun new(
        view: View,
        system: SystemStatusDto,
        state: FormState,
        inspectors: List<InspectorDto>,
    ): Page =
        Page("New service") {
            serviceForm(view, system, NEW, state, inspectors, null, "Create the service", ServicesPages.OVERVIEW)
        }

    /** The page with the form that changes [service], which is shown with [state]. A service can name one of [inspectors]. */
    fun edit(
        view: View,
        system: SystemStatusDto,
        service: ServiceDto,
        state: FormState,
        inspectors: List<InspectorDto>,
    ): Page =
        Page("Edit the service ${service.name}") {
            serviceForm(view, system, addressOf(EDIT, service), state, inspectors, service, "Save the service", addressOf(service.id))
        }

    /** The page that asks whether [service] is to be deleted. */
    fun deletion(
        view: View,
        service: ServiceDto,
    ): Page {
        val scenarios =
            when (service.scenarios.size) {
                0 -> "The service has no scenario."
                1 -> "The service has 1 scenario, which is deleted with it."
                else -> "The service has ${service.scenarios.size} scenarios, which are deleted with it."
            }
        val title = "Delete the service ${service.name}?"
        return Confirmation.page(view, title, listOf(scenarios), "Delete the service", addressOf(DELETE, service), addressOf(service.id))
    }

    /**
     * Returns what the page says of a text that a service has for the server to use, such as its request
     * rules: [used] when the service has [text] and the server uses it, as [enabled] says, [unused] when the
     * server does not use it, and that there is none when the service has no such text.
     */
    private fun usedOrNot(
        text: String?,
        enabled: Boolean,
        used: String,
        unused: String,
    ): String =
        when {
            text == null -> NONE
            enabled -> used
            else -> unused
        }

    /** Returns the mode that [service] is set to, and in which mode it answers where that is another one. */
    private fun modeOf(service: ServiceDto): String {
        val set = Modes.labelOf(service.responseMode)
        return if (service.effectiveResponseMode == service.responseMode) set else "$set. Answers ${ServicesPages.otherwiseOf(service)}."
    }

    /** Shows the real URLs of [service], of which it says which one is the default one where there are several. */
    private fun FlowContent.realUrlsOf(service: ServiceDto) {
        if (service.realUrls.isEmpty()) {
            +NONE
            return
        }
        ul(classes = "plain") {
            for ((index, url) in service.realUrls.withIndex()) {
                li {
                    urlOf(url)
                    if (service.realUrls.size > 1 && index == service.defaultRealUrlIndex) +" (default)"
                }
            }
        }
    }

    /** Shows the scenarios of [service] by their names, and says which one is the default one and which one the error scenario. */
    private fun FlowContent.scenariosOf(service: ServiceDto) {
        if (service.scenarios.isEmpty()) {
            p { +"The service has no scenario. It forwards every request while it has none." }
            return
        }
        ul(classes = "scenarios") {
            for (scenario in service.scenarios.sortedWith(SCENARIOS_BY_NAME)) {
                li {
                    +scenario.name
                    val asDefault = "default".takeIf { scenario.id == service.defaultScenarioId }
                    for (mark in listOfNotNull(asDefault, "error scenario".takeIf { scenario.id == service.errorScenarioId })) {
                        +" "
                        span(classes = "mark") { +mark }
                    }
                }
            }
        }
    }

    /**
     * The form of a service, which a browser sends to [action] and which is shown with [state]. It is the
     * form of [service], or that of a new service when there is none. A service can name one of
     * [inspectors], and one of its scenarios as its default scenario. The button of the form says [save],
     * and the link beside it leads to [back] and changes nothing.
     */
    private fun FlowContent.serviceForm(
        view: View,
        system: SystemStatusDto,
        action: String,
        state: FormState,
        inspectors: List<InspectorDto>,
        service: ServiceDto?,
        save: String,
        back: String,
    ) {
        val scenarios = service?.scenarios.orEmpty()
        postForm(view, action) {
            rejectionOf(state)
            textField(state, ServiceForm.NAME, "Name", hint = if (service == null) "$NAME_HINT $NAME_OF_NEW_HINT" else NAME_HINT)
            val callable = "$CALLABLE_HINT ${callableUrlOf(system, "")} and goes on as the pattern says."
            textField(state, ServiceForm.MOCK_URL, "Mock URL pattern", hint = "$MOCK_URL_HINT $callable")
            textAreaField(state, ServiceForm.DESCRIPTION, "Description", rows = DESCRIPTION_ROWS)
            textField(state, ServiceForm.TAGS, "Tags", hint = TAGS_HINT)
            choiceField(state, ServiceForm.MODE, "Response mode", MODES, api = "responseMode", hint = MODE_HINT)
            if (scenarios.isNotEmpty()) {
                val choices = defaultScenarioChoices(scenarios, state.value(ServiceForm.DEFAULT_SCENARIO))
                choiceField(state, ServiceForm.DEFAULT_SCENARIO, "Default scenario", choices, "defaultScenarioId", DEFAULT_HINT)
            }
            textField(state, ServiceForm.DELAY, "Delay", api = "delayMillis", hint = DELAY_HINT)
            realUrlRows(state)
            textAreaField(state, ServiceForm.RULES, "Request rules", api = "requestRules", hint = RULES_HINT, rows = TEXT_ROWS)
            checkField(state, ServiceForm.RULES_ENABLED, "Evaluate the request rules", api = "requestRulesEnabled")
            val named = inspectorChoices(inspectors, state.value(ServiceForm.INSPECTOR))
            choiceField(state, ServiceForm.INSPECTOR, "Inspector", named, api = "inspectorName", hint = INSPECTOR_HINT)
            textAreaField(state, ServiceForm.SCHEMA, "Response schema", api = "responseSchema", hint = SCHEMA_HINT, rows = TEXT_ROWS)
            checkField(state, ServiceForm.SCHEMA_ENABLED, SCHEMA_ENABLED_LABEL, api = "responseSchemaEnabled")
            otherProblemsOf(state)
            p(classes = "actions") {
                submit(save)
                +" "
                a(href = back) { +"Cancel" }
            }
        }
    }

    /**
     * The real URLs of the form: a row for each, with a field for the URL and a button that makes it the
     * default one. A row without a URL is none, so the rows that the form adds wait for the URLs that are
     * entered.
     */
    private fun FlowContent.realUrlRows(state: FormState) {
        val default = state.value(ServiceForm.DEFAULT_REAL_URL)
        fieldGroup(state, ServiceForm.REAL_URL, "Real URLs", listOf("realUrls", "defaultRealUrlIndex"), REAL_URLS_HINT) {
            for ((index, url) in state.all(ServiceForm.REAL_URL).withIndex()) {
                div(classes = "row") {
                    textInput(name = ServiceForm.REAL_URL) {
                        attributes["aria-label"] = "Real URL ${index + 1}"
                        value = url
                    }
                    radioInput(name = ServiceForm.DEFAULT_REAL_URL) {
                        id = "f-${ServiceForm.DEFAULT_REAL_URL}-$index"
                        value = "$index"
                        checked = default == "$index"
                    }
                    label {
                        htmlFor = "f-${ServiceForm.DEFAULT_REAL_URL}-$index"
                        +"Default"
                    }
                }
            }
        }
    }

    /**
     * Returns the scenarios that the form offers as the default one, by their names. Where [chosen] is none of
     * [scenarios], the first choice says so, and a form that is sent with it leaves the default scenario as it is.
     */
    private fun defaultScenarioChoices(
        scenarios: List<ScenarioDto>,
        chosen: String,
    ): List<Pair<String, String>> {
        val ofService = scenarios.sortedWith(SCENARIOS_BY_NAME).map { it.id to it.name }
        return if (scenarios.any { it.id == chosen }) ofService else listOf("" to NONE) + ofService
    }

    /**
     * Returns the inspectors that the form offers, by their names. A service that names an inspector that is
     * not among [installed] keeps it as a choice, so that a form that is sent as it is shown changes nothing.
     */
    private fun inspectorChoices(
        installed: List<InspectorDto>,
        named: String,
    ): List<Pair<String, String>> {
        val inOrder = installed.sortedBy { it.name.lowercase() }
        val known = inOrder.map { it.name to if (it.global) "${it.name} (runs for every service)" else it.name }
        val other = if (named.isEmpty() || installed.any { it.name == named }) emptyList() else listOf(named to "$named (not installed)")
        return listOf("" to NONE) + known + other
    }

    private val MODES = ResponseModeDto.entries.map { Modes.nameOf(it) to Modes.labelOf(it) }

    private const val DESCRIPTION_ROWS = 3
    private const val TEXT_ROWS = 10

    private const val RULES_ON = "A rule set, which the server evaluates"
    private const val RULES_OFF = "A rule set, which the server does not evaluate"
    private const val SCHEMA_ON = "A schema, to which the diagnostics hold the scenarios"
    private const val SCHEMA_OFF = "A schema, to which the diagnostics do not hold the scenarios"

    private const val NAME_HINT = "What the pages call the service."
    private const val NAME_OF_NEW_HINT = "A new service with a real URL can do without a name: the server names it after that URL."
    private const val MOCK_URL_HINT =
        "The pattern of the addresses under which clients call the service, such as customer/{id}. " +
            "A name in braces stands for one part of a path."
    private const val CALLABLE_HINT = "A client calls the service at an address that begins with"
    private const val SCHEMA_ENABLED_LABEL = "Report the scenarios that do not conform to the schema"
    private const val TAGS_HINT = "With spaces between them."
    private const val MODE_HINT =
        "Proxy forwards a request to the real service. Static answers with the default scenario. " +
            "Dynamic answers with the scenario that matches the request best."
    private const val DEFAULT_HINT = "The scenario that answers in static mode."
    private const val DELAY_HINT =
        "In milliseconds. The server waits this long before it sends the response of a scenario that has no delay of its own."
    private const val REAL_URLS_HINT =
        "The URLs of the real service. In proxy mode, the server forwards a request to the default one. " +
            "A row without a URL is left out."
    private const val RULES_HINT =
        "A rule set as a JSON text, by which the server inspects the requests to the service while the box below is checked."
    private const val INSPECTOR_HINT = "An inspector that is installed in the server, which looks at the requests to the service."
    private const val SCHEMA_HINT =
        "A JSON Schema for the response bodies of the scenarios. A scenario that does not conform is saved and answers all the same."
}

/**
 * The form of a service: the names of its fields, what it shows of a service, and what it asks the admin API
 * for. The form states every attribute of a service that it has a field for, so the server sets each to
 * what the form holds.
 */
internal object ServiceForm {
    const val NAME = "name"
    const val MOCK_URL = "mockUrl"
    const val DESCRIPTION = "description"
    const val TAGS = "tags"
    const val MODE = "mode"
    const val DEFAULT_SCENARIO = "defaultScenario"
    const val DELAY = "delay"

    /** The field of a real URL, which the form has once for every row. */
    const val REAL_URL = "realUrl"

    /** The field that names the row of the default real URL by its number, which begins at 0. */
    const val DEFAULT_REAL_URL = "defaultRealUrl"
    const val RULES = "rules"
    const val RULES_ENABLED = "rulesEnabled"
    const val INSPECTOR = "inspector"
    const val SCHEMA = "schema"
    const val SCHEMA_ENABLED = "schemaEnabled"

    /** The rows without a URL that the form has after the real URLs of a service, for the URLs that are entered. */
    private const val FREE_ROWS = 2

    private const val NO_MODE = "The form names no response mode."
    private const val NO_DELAY = "The delay is a whole number of milliseconds, 0 or more."

    /** Returns what the form of a new service is shown with: a service in proxy mode without a delay. */
    fun blank(): FormState {
        val proxy = Modes.nameOf(ResponseModeDto.PROXY)
        val rows = List(FREE_ROWS + 1) { "" }
        return FormState(mapOf(MODE to listOf(proxy), DELAY to listOf("0"), REAL_URL to rows, DEFAULT_REAL_URL to listOf("0")))
    }

    /** Returns what the form of [service] is shown with, which is what the server holds of the service. */
    fun of(service: ServiceDto): FormState {
        val texts =
            mapOf(
                NAME to service.name,
                MOCK_URL to service.mockUrl,
                DESCRIPTION to service.description.orEmpty(),
                TAGS to service.tags.joinToString(" "),
                MODE to Modes.nameOf(service.responseMode),
                DEFAULT_SCENARIO to service.defaultScenarioId.orEmpty(),
                DELAY to "${service.delayMillis}",
                DEFAULT_REAL_URL to "${service.defaultRealUrlIndex}",
                RULES to service.requestRules.orEmpty(),
                INSPECTOR to service.inspectorName.orEmpty(),
                SCHEMA to service.responseSchema.orEmpty(),
            )
        val ofRules = RULES_ENABLED.takeIf { service.requestRulesEnabled }
        val checked = listOfNotNull(ofRules, SCHEMA_ENABLED.takeIf { service.responseSchemaEnabled })
        val rows = service.realUrls + List(FREE_ROWS) { "" }
        return FormState(texts.mapValues { listOf(it.value) } + checked.associateWith { listOf(CHECKED) } + (REAL_URL to rows))
    }

    /**
     * Returns the attributes of a service as the admin API reads them, from what the form holds.
     *
     * @throws InvalidInput when the form names no response mode or no delay.
     */
    fun attributesOf(input: FormInput): JsonObject {
        val mode = Modes.named(input.text(MODE))
        val entered = input.text(DELAY).trim()
        val delay = entered.ifEmpty { "0" }.toLongOrNull()?.takeIf { it >= 0 }
        if (mode == null || delay == null) {
            val ofMode = FieldErrorDto("responseMode", NO_MODE).takeIf { mode == null }
            throw InvalidInput(listOfNotNull(ofMode, FieldErrorDto("delayMillis", NO_DELAY).takeIf { delay == null }))
        }
        val rows = input.all(REAL_URL)
        val stated = rows.indices.filter { rows[it].isNotBlank() }
        return buildJsonObject {
            put("name", input.text(NAME))
            put("mockUrl", input.text(MOCK_URL))
            put("description", input.lines(DESCRIPTION).takeIf { it.isNotBlank() })
            put("tags", JsonArray(tagsIn(input.text(TAGS)).map(::JsonPrimitive)))
            put("responseMode", Modes.nameOf(mode))
            if (input.text(DEFAULT_SCENARIO).isNotEmpty()) put("defaultScenarioId", input.text(DEFAULT_SCENARIO))
            put("delayMillis", delay)
            put("realUrls", JsonArray(stated.map { JsonPrimitive(rows[it].trim()) }))
            put("defaultRealUrlIndex", stated.indexOf(input.text(DEFAULT_REAL_URL).toIntOrNull()).coerceAtLeast(0))
            put("requestRules", input.lines(RULES).takeIf { it.isNotBlank() })
            put("requestRulesEnabled", input.isChecked(RULES_ENABLED))
            put("inspectorName", input.text(INSPECTOR).takeIf { it.isNotEmpty() })
            put("responseSchema", input.lines(SCHEMA).takeIf { it.isNotBlank() })
            put("responseSchemaEnabled", input.isChecked(SCHEMA_ENABLED))
        }
    }
}
