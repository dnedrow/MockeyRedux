package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.MatchCriterionDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ScenarioReferenceDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.hiddenInput
import kotlinx.html.p
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The pages for a single scenario: the page of the scenario, which is also the form that edits it, the form
 * that creates a scenario, the confirmation step before a deletion, and the actions that designate a
 * scenario as the default scenario or the error scenario of its service, or as the universal error scenario.
 *
 * See `docs/decisions/ui-pages.md`.
 */
internal val ScenarioArea =
    Area { routing, ui ->
        with(routing) {
            get(ScenarioPages.NEW) {
                val serviceId = call.partOfAddress(ScenarioPages.SERVICE)
                ui.show(call) { view, _ -> ScenarioPages.new(view, ui.client.getService(serviceId), ScenarioForm.blank()) }
            }
            post(ScenarioPages.NEW) {
                val serviceId = call.partOfAddress(ScenarioPages.SERVICE)
                val again: suspend (View, SystemStatusDto, FormState) -> Page = { view, _, state ->
                    ScenarioPages.new(view, ui.client.getService(serviceId), state)
                }
                ui.submit(call, again) { input ->
                    val created = ui.client.createScenario(serviceId, ScenarioForm.attributesOf(input))
                    Outcome.Done(ScenarioPages.addressOf(serviceId, created.id), "The scenario ${briefly(created.name)} was created.")
                }
            }
            get(ScenarioPages.PAGE) {
                val (serviceId, id) = call.scenarioOfAddress()
                ui.show(call) { view, _ ->
                    val (service, scenario) = ui.client.scenarioWithService(serviceId, id)
                    ScenarioPages.scenario(view, service, scenario, ui.client.getUniversalErrorScenario(), ScenarioForm.of(scenario))
                }
            }
            post(ScenarioPages.PAGE) {
                val (serviceId, id) = call.scenarioOfAddress()
                val again: suspend (View, SystemStatusDto, FormState) -> Page = { view, _, state ->
                    val (service, scenario) = ui.client.scenarioWithService(serviceId, id)
                    ScenarioPages.scenario(view, service, scenario, ui.client.getUniversalErrorScenario(), state)
                }
                ui.submit(call, again) { input ->
                    val saved = ui.client.updateScenario(serviceId, id, ScenarioForm.attributesOf(input))
                    Outcome.Done(ScenarioPages.addressOf(serviceId, id), "The scenario ${briefly(saved.name)} was saved.")
                }
            }
            getAndPost(ScenarioPages.DELETE) {
                val (serviceId, id) = call.scenarioOfAddress()
                val ask: suspend (View, SystemStatusDto) -> Page = { view, _ ->
                    val (service, scenario) = ui.client.scenarioWithService(serviceId, id)
                    ScenarioPages.deletion(view, service, scenario, ui.client.getUniversalErrorScenario())
                }
                ui.confirm(call, ask) {
                    val scenario = ui.client.getScenario(serviceId, id)
                    ui.client.deleteScenario(serviceId, id)
                    Outcome.Done(ServicePages.addressOf(serviceId), "The scenario ${briefly(scenario.name)} was deleted.")
                }
            }
            post(ScenarioPages.DEFAULT) {
                val (serviceId, id) = call.scenarioOfAddress()
                ui.submit(call) {
                    val (service, scenario) = ui.client.scenarioWithService(serviceId, id)
                    ui.client.updateService(serviceId, buildJsonObject { put("defaultScenarioId", id) })
                    val role = "the default scenario of the service ${briefly(service.name)}"
                    Outcome.Done(ScenarioPages.addressOf(serviceId, id), "The scenario ${briefly(scenario.name)} is now $role.")
                }
            }
            post(ScenarioPages.ERROR) {
                val (serviceId, id) = call.scenarioOfAddress()
                ui.submit(call) { input ->
                    val designate = ScenarioPages.designates(input)
                    val (service, scenario) = ui.client.scenarioWithService(serviceId, id)
                    val wasDesignated = service.errorScenarioId == id
                    // A page removes the designation only from its own scenario, never from another one.
                    if (designate || wasDesignated) {
                        ui.client.updateService(serviceId, buildJsonObject { put("errorScenarioId", id.takeIf { designate }) })
                    }
                    val role = "the error scenario of the service ${briefly(service.name)}"
                    val notice = ScenarioPages.designationNotice(scenario, role, designate, wasDesignated)
                    Outcome.Done(ScenarioPages.addressOf(serviceId, id), notice)
                }
            }
            post(ScenarioPages.UNIVERSAL_ERROR) {
                val (serviceId, id) = call.scenarioOfAddress()
                ui.submit(call) { input ->
                    val designate = ScenarioPages.designates(input)
                    val scenario = ui.client.getScenario(serviceId, id)
                    val wasDesignated = ui.client.getUniversalErrorScenario() == ScenarioReferenceDto(serviceId, id)
                    // A page removes the designation only from its own scenario, never from another one.
                    when {
                        designate -> ui.client.setUniversalErrorScenario(serviceId, id)
                        wasDesignated -> ui.client.clearUniversalErrorScenario()
                    }
                    val notice = ScenarioPages.designationNotice(scenario, "the universal error scenario", designate, wasDesignated)
                    Outcome.Done(ScenarioPages.addressOf(serviceId, id), notice)
                }
            }
        }
    }

/** Returns the identifiers of the service and of the scenario that the address of the request names. */
private fun ApplicationCall.scenarioOfAddress(): Pair<String, String> =
    partOfAddress(ScenarioPages.SERVICE) to partOfAddress(ScenarioPages.ID)

/**
 * Returns the service [serviceId] and its scenario [id]. If the service has no such scenario, the function
 * requests the scenario from the server, so that the request fails with the problem that the server reports.
 */
private suspend fun AdminClient.scenarioWithService(
    serviceId: String,
    id: String,
): Pair<ServiceDto, ScenarioDto> {
    val service = getService(serviceId)
    return service to (service.scenarios.firstOrNull { it.id == id } ?: getScenario(serviceId, id))
}

/** The pages for a single scenario. */
internal object ScenarioPages {
    /** The path of the page of a scenario, which is also the form that edits the scenario. */
    const val PAGE = "/scenario"

    /** The path of the form that creates a scenario. */
    const val NEW = "/scenario/new"

    /** The path of the confirmation step before a scenario is deleted. */
    const val DELETE = "/scenario/delete"

    /** The path of the action that makes a scenario the default scenario of its service. */
    const val DEFAULT = "/scenario/default"

    /** The path of the action that designates a scenario as the error scenario of its service, or removes that designation. */
    const val ERROR = "/scenario/error"

    /** The path of the action that designates a scenario as the universal error scenario, or removes that designation. */
    const val UNIVERSAL_ERROR = "/scenario/universal-error"

    /** The address parameter that holds the identifier of the service of a scenario. */
    const val SERVICE = "service"

    /** The address parameter that holds the identifier of a scenario. */
    const val ID = "id"

    /** The field of a designation button. Its value is [YES] to designate the scenario, or [NO] to remove the designation. */
    const val DESIGNATE = "designate"
    const val YES = "yes"
    const val NO = "no"

    /** Returns the address of the page of the scenario [id] of the service [serviceId]. */
    fun addressOf(
        serviceId: String,
        id: String,
    ): String = Address.of(PAGE, SERVICE to serviceId, ID to id)

    /** Returns the address of the form that creates a scenario of the service [serviceId]. */
    fun addressOfNew(serviceId: String): String = Address.of(NEW, SERVICE to serviceId)

    private fun addressOf(
        path: String,
        service: ServiceDto,
        scenario: ScenarioDto,
    ): String = Address.of(path, SERVICE to service.id, ID to scenario.id)

    /**
     * Returns whether the form of a designation button asks to designate its scenario.
     *
     * @throws InvalidInput if the form asks neither to designate the scenario nor to remove the designation.
     */
    fun designates(input: FormInput): Boolean =
        when (input.text(DESIGNATE)) {
            YES -> true
            NO -> false
            else -> throw InvalidInput(DESIGNATE, "The form does not say whether to designate the scenario.")
        }

    /**
     * Returns the notice for a designation action on [scenario]. [role] names the designation, such as "the
     * universal error scenario". The action asked to [designate] the scenario or to remove the designation,
     * and the scenario [wasDesignated] before the action.
     */
    fun designationNotice(
        scenario: ScenarioDto,
        role: String,
        designate: Boolean,
        wasDesignated: Boolean,
    ): String {
        val name = briefly(scenario.name)
        return when {
            designate -> "The scenario $name is now $role."
            wasDesignated -> "The scenario $name is no longer $role."
            else -> "The scenario $name was not $role. Nothing was changed."
        }
    }

    /**
     * Returns the page of [scenario], which belongs to [service]. The page holds the form that edits the
     * scenario, displayed with [state], the designations of the scenario with the buttons that change them,
     * and a link to the deletion step. [universal] is the universal error scenario of the server, or `null`
     * if the server has none.
     */
    fun scenario(
        view: View,
        service: ServiceDto,
        scenario: ScenarioDto,
        universal: ScenarioReferenceDto?,
        state: FormState,
    ): Page =
        Page("Scenario ${scenario.name}") {
            serviceOf(service)
            val back = ServicePages.addressOf(service.id)
            scenarioForm(view, addressOf(PAGE, service, scenario), state, marked = true, "Save the scenario", back)
            h2 { +"Default scenario and error scenarios" }
            val isDefault = service.defaultScenarioId == scenario.id
            designationOf(view, AS_DEFAULT, isDefault, addressOf(DEFAULT, service, scenario))
            val isError = service.errorScenarioId == scenario.id
            designationOf(view, AS_ERROR, isError, addressOf(ERROR, service, scenario))
            val isUniversal = universal == ScenarioReferenceDto(service.id, scenario.id)
            designationOf(view, AS_UNIVERSAL_ERROR, isUniversal, addressOf(UNIVERSAL_ERROR, service, scenario))
            p(classes = "actions") { a(href = addressOf(DELETE, service, scenario)) { +"Delete the scenario" } }
        }

    /** Returns the page with the form that creates a scenario of [service]. The form is displayed with [state]. */
    fun new(
        view: View,
        service: ServiceDto,
        state: FormState,
    ): Page =
        Page("New scenario") {
            serviceOf(service)
            val back = ServicePages.addressOf(service.id)
            scenarioForm(view, addressOfNew(service.id), state, marked = false, "Create the scenario", back)
        }

    /**
     * Returns the page that asks whether to delete [scenario], which belongs to [service]. The page lists the
     * designations that end with the scenario. [universal] is the universal error scenario of the server, or
     * `null` if the server has none.
     */
    fun deletion(
        view: View,
        service: ServiceDto,
        scenario: ScenarioDto,
        universal: ScenarioReferenceDto?,
    ): Page {
        val asDefault = if (service.scenarios.size == 1) ONLY_SCENARIO else DEFAULT_PASSES_ON
        val consequences =
            listOfNotNull(
                "The scenario belongs to the service ${service.name}.",
                asDefault.takeIf { service.defaultScenarioId == scenario.id },
                ERROR_ENDS.takeIf { service.errorScenarioId == scenario.id },
                UNIVERSAL_ERROR_ENDS.takeIf { universal == ScenarioReferenceDto(service.id, scenario.id) },
            )
        val title = "Delete the scenario ${scenario.name}?"
        val action = addressOf(DELETE, service, scenario)
        return Confirmation.page(view, title, consequences, "Delete the scenario", action, addressOf(service.id, scenario.id))
    }

    /** Names the service that the scenario of the page belongs to, with a link to the page of the service. */
    private fun FlowContent.serviceOf(service: ServiceDto) {
        p {
            +"A scenario of the service "
            a(href = ServicePages.addressOf(service.id)) { +service.name }
            +"."
        }
    }

    /**
     * Shows whether the scenario of the page holds [designation], and the button that changes this. The
     * scenario holds the designation if [isHeld] is true. The button submits its form to [action].
     */
    private fun FlowContent.designationOf(
        view: View,
        designation: Designation,
        isHeld: Boolean,
        action: String,
    ) {
        div(classes = "designation") {
            p { +(if (isHeld) designation.held else designation.notHeld) }
            val label = if (isHeld) designation.remove else designation.assign
            if (label != null) {
                postForm(view, action, classes = "inline") {
                    button(type = ButtonType.submit, name = DESIGNATE) {
                        value = if (isHeld) NO else YES
                        +label
                    }
                }
            }
        }
    }

    /**
     * The form of a scenario. A browser submits it to [action], and it is displayed with [state]. The form
     * of an existing scenario is [marked]: it records the initial values of its fields, see [Shown]. The
     * button of the form is labeled [save], and the link next to it leads to [back].
     */
    private fun FlowContent.scenarioForm(
        view: View,
        action: String,
        state: FormState,
        marked: Boolean,
        save: String,
        back: String,
    ) {
        postForm(view, action) {
            if (marked) shownOf(state, ScenarioForm.FIELDS)
            for (name in ScenarioForm.LINE_ENDING_FIELDS) hiddenInput(name = name) { value = state.value(name) }
            rejectionOf(state)
            textField(state, ScenarioForm.NAME, "Name", hint = NAME_HINT)
            textField(state, ScenarioForm.STATUS, "Status code", api = "statusCode", hint = STATUS_HINT)
            textAreaField(state, ScenarioForm.HEADERS, "Headers", hint = HEADERS_HINT, rows = HEADER_ROWS)
            textAreaField(state, ScenarioForm.BODY, "Body", api = "responseBody", hint = BODY_HINT, rows = BODY_ROWS)
            textAreaField(state, ScenarioForm.CRITERION, "Match criterion", api = "matchCriterion", hint = CRITERION_HINT)
            checkField(state, ScenarioForm.CRITERION_IS_RULES, "The match criterion is a rule set")
            val methods = methodChoices(state.value(ScenarioForm.METHOD))
            choiceField(state, ScenarioForm.METHOD, "Method", methods, api = "methodRestriction", hint = METHOD_HINT)
            textField(state, ScenarioForm.DELAY, "Delay", api = "delayMillis", hint = DELAY_HINT)
            textField(state, ScenarioForm.TAGS, "Tags", hint = TAGS_HINT)
            otherProblemsOf(state)
            p(classes = "actions") {
                submit(save)
                +" "
                a(href = back) { +"Cancel" }
            }
        }
    }

    /**
     * Returns the methods that the form offers, after the choice of any method. If the scenario names a
     * method that is not among them, the form offers that method as well, so that it does not change the
     * method restriction on its own.
     */
    private fun methodChoices(named: String): List<Pair<String, String>> {
        val known = listOf("" to "Any method") + METHODS.map { it to it }
        return if (named.isEmpty() || named in METHODS) known else known + (named to named)
    }

    /**
     * A designation that a scenario can hold: the texts that describe it on the page of a scenario, and the
     * labels of the buttons that assign it and remove it. A designation without a [remove] label cannot be
     * removed. It ends only when another scenario receives it.
     */
    private class Designation(
        val held: String,
        val notHeld: String,
        val assign: String,
        val remove: String?,
    )

    private val AS_DEFAULT =
        Designation(
            held = "This is the default scenario of the service. In static mode, the service answers every request with it.",
            notHeld =
                "This is not the default scenario of the service. " +
                    "In static mode, the service answers every request with its default scenario.",
            assign = "Make it the default scenario",
            remove = null,
        )

    private val AS_ERROR =
        Designation(
            held = "This is the error scenario of the service. In dynamic mode, it answers every request that no scenario matches.",
            notHeld =
                "This is not the error scenario of the service. " +
                    "In dynamic mode, the error scenario answers every request that no scenario matches.",
            assign = "Make it the error scenario",
            remove = "Stop using it as the error scenario",
        )

    private val AS_UNIVERSAL_ERROR =
        Designation(
            held = "This is the universal error scenario. It is the error scenario for every service that has none of its own.",
            notHeld =
                "This is not the universal error scenario. " +
                    "The universal error scenario is the error scenario for every service that has none of its own.",
            assign = "Make it the universal error scenario",
            remove = "Stop using it as the universal error scenario",
        )

    private val METHODS = listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")

    private const val HEADER_ROWS = 4
    private const val BODY_ROWS = 16

    private const val ONLY_SCENARIO = "It is the only scenario of the service. A service without a scenario forwards every request."
    private const val DEFAULT_PASSES_ON =
        "It is the default scenario of the service. The oldest remaining scenario becomes the default scenario."
    private const val ERROR_ENDS = "It is the error scenario of the service. The service will have no error scenario."
    private const val UNIVERSAL_ERROR_ENDS = "It is the universal error scenario. The server will have no universal error scenario."

    private const val NAME_HINT = "If the name is empty, the server generates one."
    private const val STATUS_HINT = "The status code of the response, such as 200 or 404."
    private const val HEADERS_HINT =
        "The headers of the response, one per line, in the form Name: value, such as Content-Type: text/plain. " +
            "A header name can appear more than once."
    private const val BODY_HINT = "The server sends the body exactly as it is entered here."
    private const val CRITERION_HINT =
        "In dynamic mode, the scenario answers a request that matches this criterion. The criterion is a text that " +
            "the request must contain, or a rule set in JSON. A scenario without a criterion never matches."
    private const val METHOD_HINT = "In dynamic mode, the scenario answers only requests with this method."
    private const val DELAY_HINT = "The delay before the response, in milliseconds. If it is 0, the delay of the service applies."
    private const val TAGS_HINT = "Separate the tags with spaces."
}

/**
 * The form of a scenario: the names of its fields, the state that it displays for a scenario, and the
 * attributes that it asks the admin API to set. The form of an existing scenario states the attributes whose
 * fields were changed, see [Shown]. The form of a new scenario states every attribute.
 */
internal object ScenarioForm {
    const val NAME = "name"
    const val STATUS = "status"
    const val HEADERS = "headers"
    const val BODY = "body"
    const val CRITERION = "criterion"
    const val CRITERION_IS_RULES = "criterionIsRules"
    const val METHOD = "method"
    const val DELAY = "delay"
    const val TAGS = "tags"

    /** The hidden field that holds the line endings of the stored body, see [LineEndings]. */
    const val BODY_LINE_ENDINGS = "bodyLineEndings"

    /** The hidden field that holds the line endings of the stored match criterion, see [LineEndings]. */
    const val CRITERION_LINE_ENDINGS = "criterionLineEndings"

    /** The fields whose initial values the form of an existing scenario records, see [Shown]. */
    val FIELDS = listOf(NAME, STATUS, HEADERS, BODY, CRITERION, CRITERION_IS_RULES, METHOD, DELAY, TAGS)

    /** The hidden fields that hold line endings. */
    val LINE_ENDING_FIELDS = listOf(BODY_LINE_ENDINGS, CRITERION_LINE_ENDINGS)

    private val STATUS_CODES = 100..999

    private const val INVALID_STATUS = "The status code is a whole number from 100 to 999."
    private const val INVALID_DELAY = "The delay is a whole number of milliseconds, 0 or more."
    private const val HEADER_FORMAT = "A header has the form Name: value, for example Content-Type: text/plain."

    /** Returns the initial state of the form for a new scenario: the status code 200 and no delay. */
    fun blank(): FormState {
        val lineEndings = LINE_ENDING_FIELDS.map { it to LineEndings.DEFAULT }
        return FormState.of(STATUS to "200", DELAY to "0", *lineEndings.toTypedArray())
    }

    /** Returns the initial state of the form for [scenario], which holds the values that the server holds. */
    fun of(scenario: ScenarioDto): FormState {
        val criterion = scenario.matchCriterion
        val criterionText =
            when (criterion) {
                is MatchCriterionDto.None -> ""
                is MatchCriterionDto.Text -> criterion.text
                is MatchCriterionDto.Rules -> criterion.source
            }
        val texts =
            mapOf(
                NAME to inOneLine(scenario.name),
                STATUS to "${scenario.statusCode}",
                HEADERS to scenario.headers.joinToString("\n") { "${it.name}: ${it.value}" },
                BODY to scenario.responseBody,
                BODY_LINE_ENDINGS to LineEndings.styleOf(scenario.responseBody),
                CRITERION to criterionText,
                CRITERION_LINE_ENDINGS to LineEndings.styleOf(criterionText),
                METHOD to scenario.methodRestriction.orEmpty(),
                DELAY to "${scenario.delayMillis}",
                TAGS to inOneLine(scenario.tags.joinToString(" ")),
            )
        val checked = if (criterion is MatchCriterionDto.Rules) mapOf(CRITERION_IS_RULES to listOf(CHECKED)) else emptyMap()
        return FormState(texts.mapValues { listOf(it.value) } + checked)
    }

    /**
     * Returns the attributes that the form asks the admin API to set. The form of an existing scenario
     * states the attributes whose fields were changed, see [Shown]. The form of a new scenario records no
     * initial values, so it states every attribute.
     *
     * @throws InvalidInput if a changed field holds an invalid status code, an invalid delay, or a line that
     *   is not a header.
     */
    fun attributesOf(input: FormInput): JsonObject {
        val enteredStatus = input.text(STATUS).trim()
        val status = enteredStatus.toIntOrNull()?.takeIf { it in STATUS_CODES }
        val enteredDelay = input.text(DELAY).trim()
        val delay = enteredDelay.ifEmpty { "0" }.toLongOrNull()?.takeIf { it >= 0 }
        val lines = input.lines(HEADERS).split('\n').withIndex()
        val headerLines = lines.filter { it.value.isNotBlank() }
        val invalidLines = headerLines.filter { it.value.substringBefore(':', "").isBlank() }
        val errors =
            buildList {
                if (status == null && input.isChanged(STATUS)) add(FieldErrorDto("statusCode", INVALID_STATUS))
                if (input.isChanged(HEADERS)) {
                    invalidLines.forEach { add(FieldErrorDto("headers", "Line ${it.index + 1} is not a header. $HEADER_FORMAT")) }
                }
                if (delay == null && input.isChanged(DELAY)) add(FieldErrorDto("delayMillis", INVALID_DELAY))
            }
        if (errors.isNotEmpty()) throw InvalidInput(errors)
        val json = AdminApiJson.instance
        return buildJsonObject {
            if (input.isChanged(NAME)) put("name", input.text(NAME))
            if (status != null && input.isChanged(STATUS)) put("statusCode", status)
            if (input.isChanged(HEADERS)) {
                val headers = headerLines.map { headerOf(it.value) }
                put("headers", json.encodeToJsonElement(ListSerializer(HeaderDto.serializer()), headers))
            }
            if (input.isChanged(BODY)) put("responseBody", LineEndings.apply(input.text(BODY_LINE_ENDINGS), input.lines(BODY)))
            if (input.isChanged(CRITERION, CRITERION_IS_RULES)) {
                put("matchCriterion", json.encodeToJsonElement(MatchCriterionDto.serializer(), criterionOf(input)))
            }
            if (input.isChanged(METHOD)) put("methodRestriction", input.text(METHOD).takeIf { it.isNotEmpty() })
            if (delay != null && input.isChanged(DELAY)) put("delayMillis", delay)
            if (input.isChanged(TAGS)) put("tags", JsonArray(tagsIn(input.text(TAGS)).map(::JsonPrimitive)))
        }
    }

    /** Returns the header that [line] holds: the name before the first colon, and the value after it. */
    private fun headerOf(line: String): HeaderDto = HeaderDto(line.substringBefore(':').trim(), line.substringAfter(':').trim())

    /**
     * Returns the match criterion that the form holds: none if the field is blank, a rule set if the check
     * box is checked, and a text otherwise.
     */
    private fun criterionOf(input: FormInput): MatchCriterionDto {
        val entered = LineEndings.apply(input.text(CRITERION_LINE_ENDINGS), input.lines(CRITERION))
        return when {
            entered.isBlank() -> MatchCriterionDto.None
            input.isChecked(CRITERION_IS_RULES) -> MatchCriterionDto.Rules(entered)
            else -> MatchCriterionDto.Text(entered)
        }
    }
}
