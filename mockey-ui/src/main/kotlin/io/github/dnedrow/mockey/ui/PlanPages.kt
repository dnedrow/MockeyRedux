package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.PlanApplyReportDto
import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.PlanReferenceDto
import io.github.dnedrow.mockey.api.PlanSaveRequestDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.FlowContent
import kotlinx.html.ThScope
import kotlinx.html.a
import kotlinx.html.checkBoxInput
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The pages for plans: the list of all plans, the page of a single plan, the form that creates a plan from
 * the current settings of services, the form that edits a plan's name, description, and tags, the form that
 * saves the current settings into an existing plan, and the confirmation step before a deletion. The area
 * also handles the buttons that apply a plan and that make it the default plan.
 *
 * See `docs/decisions/ui-pages.md` for the reasoning behind these pages.
 */
internal val PlansArea =
    Area { routing, ui ->
        with(routing) {
            get(PlanPages.LIST) {
                ui.show(call) { _, _ -> PlanPages.list(ui.client.listPlans(), ui.client.getDefaultPlan()) }
            }
            get(PlanPages.NEW) {
                ui.show(call) { view, _ ->
                    val services = ui.client.listServices()
                    PlanPages.new(view, services, PlanForm.blank(services))
                }
            }
            post(PlanPages.NEW) {
                val again: suspend (View, SystemStatusDto, FormState) -> Page = { view, _, state ->
                    PlanPages.new(view, ui.client.listServices(), state)
                }
                ui.submit(call, again) { input ->
                    val saved = ui.client.savePlan(PlanForm.saveRequestOf(input, withAttributes = true))
                    Outcome.Done(PlanPages.addressOf(saved.id), PlanPages.savedNotice(saved))
                }
            }
            get(PlanPages.PAGE) {
                val id = call.partOfAddress(PlanPages.ID)
                ui.show(call) { view, _ -> PlanPages.plan(view, ui.client.getPlan(id), ui.client.getDefaultPlan()) }
            }
            get(PlanPages.EDIT) {
                val id = call.partOfAddress(PlanPages.ID)
                ui.show(call) { view, _ ->
                    val plan = ui.client.getPlan(id)
                    PlanPages.edit(view, plan, PlanForm.of(plan))
                }
            }
            post(PlanPages.EDIT) {
                val id = call.partOfAddress(PlanPages.ID)
                val again: suspend (View, SystemStatusDto, FormState) -> Page = { view, _, state ->
                    PlanPages.edit(view, ui.client.getPlan(id), state)
                }
                ui.submit(call, again) { input ->
                    val saved = ui.client.updatePlan(id, PlanForm.attributesOf(input))
                    Outcome.Done(PlanPages.addressOf(id), "The plan ${briefly(saved.name)} was saved.")
                }
            }
            get(PlanPages.SAVE) {
                val id = call.partOfAddress(PlanPages.ID)
                ui.show(call) { view, _ ->
                    val plan = ui.client.getPlan(id)
                    val services = ui.client.listServices()
                    PlanPages.save(view, plan, services, PlanForm.servicesOf(plan, services))
                }
            }
            post(PlanPages.SAVE) {
                val id = call.partOfAddress(PlanPages.ID)
                val again: suspend (View, SystemStatusDto, FormState) -> Page = { view, _, state ->
                    PlanPages.save(view, ui.client.getPlan(id), ui.client.listServices(), state)
                }
                ui.submit(call, again) { input ->
                    val saved = ui.client.saveIntoPlan(id, PlanForm.saveRequestOf(input, withAttributes = false))
                    Outcome.Done(PlanPages.addressOf(id), PlanPages.savedNotice(saved))
                }
            }
            post(PlanPages.APPLY) {
                val id = call.partOfAddress(PlanPages.ID)
                ui.submit(call) {
                    val report = ui.client.applyPlan(id)
                    Outcome.Report(PlanPages.applied(report), report.skippedItems.size + report.unresolvedScenarios.size)
                }
            }
            post(PlanPages.DEFAULT) {
                val id = call.partOfAddress(PlanPages.ID)
                ui.submit(call) { input ->
                    val assign = Designation.isAssigned(input)
                    val plan = ui.client.getPlan(id)
                    val wasDefault = ui.client.getDefaultPlan()?.planId == id
                    // A page only removes the designation from its own plan, never from another one.
                    when {
                        assign -> ui.client.setDefaultPlan(id)
                        wasDefault -> ui.client.clearDefaultPlan()
                    }
                    val notice = Designation.notice("The plan ${briefly(plan.name)}", "the default plan", assign, wasDefault)
                    Outcome.Done(PlanPages.addressOf(id), notice)
                }
            }
            getAndPost(PlanPages.DELETE) {
                val id = call.partOfAddress(PlanPages.ID)
                val ask: suspend (View, SystemStatusDto) -> Page = { view, _ ->
                    PlanPages.deletion(view, ui.client.getPlan(id), ui.client.getDefaultPlan())
                }
                ui.confirm(call, ask) {
                    val plan = ui.client.getPlan(id)
                    ui.client.deletePlan(id)
                    Outcome.Done(PlanPages.LIST, "The plan ${briefly(plan.name)} was deleted.")
                }
            }
        }
    }

/** The pages for plans. */
internal object PlanPages {
    /** The path of the list of all plans. */
    const val LIST = "/plans"

    /** The path of the page of a single plan. */
    const val PAGE = "/plan"

    /** The path of the form that creates a plan. */
    const val NEW = "/plan/new"

    /** The path of the form that edits the name, the description, and the tags of a plan. */
    const val EDIT = "/plan/edit"

    /** The path of the form that saves the current settings of services into a plan. */
    const val SAVE = "/plan/save"

    /** The path of the action that applies a plan. */
    const val APPLY = "/plan/apply"

    /** The path of the action that makes a plan the default plan, or undoes that. */
    const val DEFAULT = "/plan/default"

    /** The path of the confirmation step that comes before a plan is deleted. */
    const val DELETE = "/plan/delete"

    /** The address parameter that holds the identifier of a plan. */
    const val ID = "id"

    private const val NONE = "none"
    private const val DESCRIPTION_ROWS = 3

    private val COLUMNS = listOf("Plan", "Description", "Tags", "Services", "Last applied")
    private val ITEM_COLUMNS = listOf("Service", "Default scenario", "Response mode", "Delay")

    private val BY_NAME = compareBy<PlanDto, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }
    private val SERVICES_BY_NAME = compareBy<ServiceDto, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }

    /** Returns the address of the page for the plan [id]. */
    fun addressOf(id: String): String = Address.of(PAGE, ID to id)

    private fun addressOf(
        path: String,
        plan: PlanDto,
    ): String = Address.of(path, ID to plan.id)

    /** Returns the notice to show after the current settings of services were saved into [plan]. */
    fun savedNotice(plan: PlanDto): String {
        val services = if (plan.items.size == 1) "1 service" else "${plan.items.size} services"
        return "The plan ${briefly(plan.name)} now holds the current settings of $services."
    }

    /** Returns the list of [plans], sorted by name. [default] is the default plan, or `null` if there isn't one. */
    fun list(
        plans: List<PlanDto>,
        default: PlanReferenceDto?,
    ): Page =
        Page("Plans") {
            p { +INTRO }
            p(classes = "actions") { a(href = NEW) { +"New plan" } }
            if (plans.isEmpty()) {
                p { +"There are no plans yet." }
                return@Page
            }
            div(classes = "scroll") {
                table(classes = "plans") {
                    thead {
                        tr { COLUMNS.forEach { th(scope = ThScope.col) { +it } } }
                    }
                    tbody {
                        for (plan in plans.sortedWith(BY_NAME)) {
                            tr {
                                th(scope = ThScope.row) {
                                    a(href = addressOf(plan.id)) { +plan.name }
                                    if (plan.id == default?.planId) {
                                        +" "
                                        span(classes = "mark") { +"default" }
                                    }
                                }
                                td { +plan.description.orEmpty() }
                                td { tagsOf(plan.tags) }
                                td { +"${plan.items.size}" }
                                td { lastAppliedOf(plan) }
                            }
                        }
                    }
                }
            }
        }

    /**
     * Returns the page of [plan]: its description, its tags, the settings it holds for each service, and the
     * links and buttons that edit, apply, and delete it. [default] is the default plan, or `null` if there
     * isn't one.
     */
    fun plan(
        view: View,
        plan: PlanDto,
        default: PlanReferenceDto?,
    ): Page =
        Page("Plan ${plan.name}") {
            div(classes = "actions") {
                a(href = addressOf(EDIT, plan)) { +"Edit" }
                postForm(view, addressOf(APPLY, plan), classes = "inline") { submit("Apply") }
                a(href = addressOf(SAVE, plan)) { +"Save current settings" }
                a(href = addressOf(DELETE, plan)) { +"Delete" }
            }
            dl {
                plan.description?.let { description ->
                    dt { +"Description" }
                    dd(classes = "lines") { +description }
                }
                dt { +"Tags" }
                dd { if (plan.tags.isEmpty()) +NONE else tagsOf(plan.tags) }
                dt { +"Last applied" }
                dd { lastAppliedOf(plan) }
            }
            h2 { +"Services" }
            itemsOf(plan)
            h2 { +"Default plan" }
            designationOf(view, AS_DEFAULT, plan.id == default?.planId, addressOf(DEFAULT, plan))
        }

    /**
     * Returns the page with the form that creates a plan from the current settings of [services]. The form
     * is displayed with [state].
     */
    fun new(
        view: View,
        services: List<ServiceDto>,
        state: FormState,
    ): Page =
        Page("New plan") {
            if (services.isEmpty()) {
                p { +NO_SERVICES }
                p(classes = "actions") { a(href = ServicePages.NEW) { +"New service" } }
                return@Page
            }
            postForm(view, NEW) {
                rejectionOf(state)
                textField(state, PlanForm.NAME, "Name", hint = NAME_OF_NEW_HINT)
                textAreaField(state, PlanForm.DESCRIPTION, "Description", rows = DESCRIPTION_ROWS)
                textField(state, PlanForm.TAGS, "Tags", hint = TAGS_HINT)
                servicesOf(state, services, SERVICES_HINT)
                otherProblemsOf(state)
                p(classes = "actions") {
                    submit("Create the plan")
                    +" "
                    a(href = LIST) { +"Cancel" }
                }
            }
        }

    /** Returns the page with the form that edits the name, the description, and the tags of [plan]. */
    fun edit(
        view: View,
        plan: PlanDto,
        state: FormState,
    ): Page =
        Page("Edit the plan ${plan.name}") {
            postForm(view, addressOf(EDIT, plan)) {
                shownOf(state, PlanForm.FIELDS)
                rejectionOf(state)
                textField(state, PlanForm.NAME, "Name", hint = NAME_HINT)
                textAreaField(state, PlanForm.DESCRIPTION, "Description", rows = DESCRIPTION_ROWS)
                textField(state, PlanForm.TAGS, "Tags", hint = TAGS_HINT)
                otherProblemsOf(state)
                p(classes = "actions") {
                    submit("Save the plan")
                    +" "
                    a(href = addressOf(plan.id)) { +"Cancel" }
                }
            }
        }

    /**
     * Returns the page with the form that saves the current settings of the chosen [services] into [plan].
     * The form is displayed with [state].
     */
    fun save(
        view: View,
        plan: PlanDto,
        services: List<ServiceDto>,
        state: FormState,
    ): Page =
        Page("Save current settings into the plan ${plan.name}") {
            p { +REPLACES_ITEMS }
            if (services.isEmpty()) {
                p { +NO_SERVICES }
                return@Page
            }
            postForm(view, addressOf(SAVE, plan)) {
                rejectionOf(state)
                servicesOf(state, services, null)
                otherProblemsOf(state)
                p(classes = "actions") {
                    submit("Save the current settings")
                    +" "
                    a(href = addressOf(plan.id)) { +"Cancel" }
                }
            }
        }

    /**
     * Returns the page that reports what applying a plan did: how many services got their settings, which
     * items were skipped, and which scenario names the server couldn't find.
     */
    fun applied(report: PlanApplyReportDto): Page {
        val plan = report.plan
        val applied = plan.items.size - report.skippedItems.size
        return Page("The plan ${plan.name} was applied") {
            p { +appliedTo(applied) }
            if (report.skippedItems.isNotEmpty()) {
                h2 { +"Skipped services" }
                p { +SKIPPED }
                ul { report.skippedItems.forEach { li { +it.serviceName } } }
            }
            if (report.unresolvedScenarios.isNotEmpty()) {
                h2 { +"Scenarios that were not found" }
                p { +UNRESOLVED }
                ul { report.unresolvedScenarios.forEach { li { +"${it.serviceName}: ${it.scenarioName}" } } }
            }
            p(classes = "actions") {
                a(href = addressOf(plan.id)) { +"Back to the plan" }
                a(href = ServicesPages.OVERVIEW) { +"Services" }
            }
        }
    }

    /**
     * Returns the page that asks whether to delete [plan]. [default] is the default plan, or `null` if there
     * isn't one.
     */
    fun deletion(
        view: View,
        plan: PlanDto,
        default: PlanReferenceDto?,
    ): Page {
        val consequences = listOfNotNull(KEEPS_SERVICES, DEFAULT_ENDS.takeIf { plan.id == default?.planId })
        val title = "Delete the plan ${plan.name}?"
        return Confirmation.page(view, title, consequences, "Delete the plan", addressOf(DELETE, plan), addressOf(plan.id))
    }

    /** Shows when [plan] was last applied. */
    private fun FlowContent.lastAppliedOf(plan: PlanDto) {
        val applied = plan.lastUsedTime
        if (applied == null) span(classes = "none") { +"never" } else timeOf(applied)
    }

    /** Shows the settings that [plan] holds for each service, in a table. */
    private fun FlowContent.itemsOf(plan: PlanDto) {
        if (plan.items.isEmpty()) {
            p { +"The plan holds no settings. Applying it changes nothing." }
            return
        }
        div(classes = "scroll") {
            table(classes = "items") {
                thead {
                    tr { ITEM_COLUMNS.forEach { th(scope = ThScope.col) { +it } } }
                }
                tbody {
                    for (item in plan.items) {
                        tr {
                            th(scope = ThScope.row) { +item.serviceName }
                            td {
                                val scenario = item.scenarioName
                                if (scenario == null) span(classes = "none") { +"left as it is" } else +scenario
                            }
                            td { +Modes.labelOf(item.responseMode) }
                            td { +"${item.delayMillis} ms" }
                        }
                    }
                }
            }
        }
    }

    /**
     * Shows a check box for each of [services], sorted by name. The boxes that [state] names are checked.
     * [hint] is shown below the boxes.
     */
    private fun FlowContent.servicesOf(
        state: FormState,
        services: List<ServiceDto>,
        hint: String?,
    ) {
        val chosen = state.all(PlanForm.SERVICE).toSet()
        fieldGroup(state, PlanForm.SERVICE, "Services", listOf("serviceIds"), hint) {
            ul(classes = "plain") {
                for ((index, service) in services.sortedWith(SERVICES_BY_NAME).withIndex()) {
                    li {
                        checkBoxInput(name = PlanForm.SERVICE) {
                            id = "f-${PlanForm.SERVICE}-$index"
                            value = service.id
                            checked = service.id in chosen
                        }
                        +" "
                        label {
                            htmlFor = "f-${PlanForm.SERVICE}-$index"
                            +service.name
                        }
                    }
                }
            }
        }
    }

    /** Returns the sentence that says how many services got their settings from a plan. */
    private fun appliedTo(services: Int): String =
        when (services) {
            0 -> "No service was changed."
            1 -> "1 service now has the settings of the plan."
            else -> "$services services now have the settings of the plan."
        }

    private val AS_DEFAULT =
        Designation(
            held = "This is the default plan. The server applies it when it starts.",
            notHeld = "This is not the default plan. The server applies the default plan when it starts.",
            assign = "Make it the default plan",
            remove = "Stop using it as the default plan",
        )

    private const val INTRO =
        "A plan is a snapshot of the response mode, the delay, and the default scenario of several services. " +
            "Applying a plan puts those services back into that state."
    private const val NO_SERVICES = "There are no services yet, so there is nothing to save in a plan."
    private const val REPLACES_ITEMS =
        "This replaces everything the plan holds with the current response mode, delay, and default scenario " +
            "of the services that are checked below."
    private const val SKIPPED = "The plan names these services, but they do not exist. Their settings were skipped."
    private const val UNRESOLVED =
        "These services have no scenario with the name in the plan. Their default scenario was left as it is."
    private const val KEEPS_SERVICES = "Deleting a plan does not change any service."
    private const val DEFAULT_ENDS = "It is the default plan. The server will have no default plan."

    private const val NAME_HINT = "If the name is empty, the server generates one."
    private const val NAME_OF_NEW_HINT =
        "If the name is empty, the server generates one. If a plan with this name already exists, " +
            "that plan is updated instead, and its settings are replaced."
    private const val TAGS_HINT = "Separate the tags with spaces."
    private const val SERVICES_HINT =
        "The plan takes the current response mode, delay, and default scenario of the services that are checked."
}

/**
 * The plan forms: the names of their fields, the state they display, and what they ask the admin API to do.
 * The form that edits a plan only sends the attributes whose fields were changed (see [Shown]).
 */
internal object PlanForm {
    const val NAME = "name"
    const val DESCRIPTION = "description"
    const val TAGS = "tags"

    /** The check box of a service. The form has one for every service, and its value is the service's identifier. */
    const val SERVICE = "service"

    /** The fields whose initial values the form that edits a plan tracks (see [Shown]). */
    val FIELDS = listOf(NAME, DESCRIPTION, TAGS)

    private const val NO_SERVICE_CHOSEN = "Choose at least one service."

    /** Returns the initial state of the form for a new plan, with every one of [services] checked. */
    fun blank(services: List<ServiceDto>): FormState = FormState(mapOf(SERVICE to services.map { it.id }))

    /** Returns the initial state of the form that edits [plan], filled with the values the server holds. */
    fun of(plan: PlanDto): FormState =
        FormState.of(
            NAME to inOneLine(plan.name),
            DESCRIPTION to plan.description.orEmpty(),
            TAGS to inOneLine(plan.tags.joinToString(" ")),
        )

    /**
     * Returns the initial state of the form that saves current settings into [plan]. The services that the
     * plan already names are checked. A plan refers to a service by name, without regard to letter case or
     * surrounding whitespace, just like the server does when it applies a plan.
     */
    fun servicesOf(
        plan: PlanDto,
        services: List<ServiceDto>,
    ): FormState {
        val named = services.filter { service -> plan.items.any { it.serviceName.trim().equals(service.name.trim(), ignoreCase = true) } }
        return FormState(mapOf(SERVICE to named.map { it.id }))
    }

    /** Returns the attributes that the form that edits a plan asks the admin API to set: the ones whose fields were changed. */
    fun attributesOf(input: FormInput): JsonObject =
        buildJsonObject {
            if (input.isChanged(NAME)) put("name", input.text(NAME))
            if (input.isChanged(DESCRIPTION)) put("description", input.lines(DESCRIPTION).takeIf { it.isNotBlank() })
            if (input.isChanged(TAGS)) put("tags", JsonArray(tagsIn(input.text(TAGS)).map(::JsonPrimitive)))
        }

    /**
     * Returns the request that saves the current settings of the checked services into a plan. The form for
     * a new plan also sends a name, a description, and tags ([withAttributes]). A field that's left empty
     * isn't sent, so an existing plan with the same name keeps its own value.
     *
     * @throws InvalidInput if no service is checked.
     */
    fun saveRequestOf(
        input: FormInput,
        withAttributes: Boolean,
    ): PlanSaveRequestDto {
        val services = input.all(SERVICE)
        if (services.isEmpty()) throw InvalidInput("serviceIds", NO_SERVICE_CHOSEN)
        if (!withAttributes) return PlanSaveRequestDto(services)
        val name = input.text(NAME).trim().takeIf { it.isNotEmpty() }
        val description = input.lines(DESCRIPTION).takeIf { it.isNotBlank() }
        val tags = tagsIn(input.text(TAGS)).takeIf { it.isNotEmpty() }
        return PlanSaveRequestDto(services, name, description, tags)
    }
}
