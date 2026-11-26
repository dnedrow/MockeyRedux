package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.github.dnedrow.mockey.api.TwistConfigurationDto
import io.github.dnedrow.mockey.api.TwistPairDto
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.ThScope
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The pages for twist configurations: the list of all configurations, the page of a single configuration,
 * which doubles as the form that edits it, the form that creates a configuration, and the confirmation step
 * before a deletion. The area also handles the buttons that enable and disable a configuration.
 *
 * See `docs/decisions/ui-pages.md` for the reasoning behind these pages.
 */
internal val TwistsArea =
    Area { routing, ui ->
        with(routing) {
            get(TwistPages.LIST) {
                ui.show(call) { view, system -> TwistPages.list(view, ui.client.listTwists(), system.enabledTwist?.id) }
            }
            get(TwistPages.NEW) { ui.show(call) { view, _ -> TwistPages.new(view, TwistForm.blank()) } }
            post(TwistPages.NEW) {
                ui.submit(call, again = { view, _, state -> TwistPages.new(view, state) }) { input ->
                    val created = ui.client.createTwist(TwistForm.attributesOf(input))
                    val notice = "The twist configuration ${briefly(created.name)} was created."
                    Outcome.Done(TwistPages.addressOf(created.id), notice)
                }
            }
            get(TwistPages.PAGE) {
                val id = call.partOfAddress(TwistPages.ID)
                ui.show(call) { view, system ->
                    val twist = ui.client.getTwist(id)
                    TwistPages.twist(view, twist, system.enabledTwist?.id == id, TwistForm.of(twist))
                }
            }
            post(TwistPages.PAGE) {
                val id = call.partOfAddress(TwistPages.ID)
                val again: suspend (View, SystemStatusDto, FormState) -> Page = { view, system, state ->
                    TwistPages.twist(view, ui.client.getTwist(id), system.enabledTwist?.id == id, state)
                }
                ui.submit(call, again) { input ->
                    val saved = ui.client.updateTwist(id, TwistForm.attributesOf(input))
                    Outcome.Done(TwistPages.addressOf(id), "The twist configuration ${briefly(saved.name)} was saved.")
                }
            }
            post(TwistPages.ENABLED) {
                val id = call.partOfAddress(TwistPages.ID)
                val toList = call.request.queryParameters[TwistPages.FROM] == TwistPages.FROM_LIST
                ui.submit(call) { input ->
                    val enable = Designation.isAssigned(input)
                    val twist = if (enable) ui.client.enableTwist(id) else ui.client.disableTwist(id)
                    val state = if (enable) "enabled" else "disabled"
                    val notice = "The twist configuration ${briefly(twist.name)} is now $state."
                    Outcome.Done(if (toList) TwistPages.LIST else TwistPages.addressOf(id), notice)
                }
            }
            getAndPost(TwistPages.DELETE) {
                val id = call.partOfAddress(TwistPages.ID)
                val ask: suspend (View, SystemStatusDto) -> Page = { view, system ->
                    TwistPages.deletion(view, ui.client.getTwist(id), system.enabledTwist?.id == id)
                }
                ui.confirm(call, ask) {
                    val twist = ui.client.getTwist(id)
                    ui.client.deleteTwist(id)
                    Outcome.Done(TwistPages.LIST, "The twist configuration ${briefly(twist.name)} was deleted.")
                }
            }
        }
    }

/** The pages for twist configurations. */
internal object TwistPages {
    /** The path of the list of all twist configurations. */
    const val LIST = "/twists"

    /** The path of the page of a twist configuration, which is also the form that edits it. */
    const val PAGE = "/twist"

    /** The path of the form that creates a twist configuration. */
    const val NEW = "/twist/new"

    /** The path of the action that enables or disables a twist configuration. */
    const val ENABLED = "/twist/enabled"

    /** The path of the confirmation step that comes before a twist configuration is deleted. */
    const val DELETE = "/twist/delete"

    /** The address parameter that holds the identifier of a twist configuration. */
    const val ID = "id"

    /** The address parameter that says which page an action came from. [FROM_LIST] sends the browser back to the list. */
    const val FROM = "from"
    const val FROM_LIST = "list"

    private val COLUMNS = listOf("Twist configuration", "Replacements", "Status")

    private val BY_NAME = compareBy<TwistConfigurationDto, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }

    /** Returns the address of the page for the twist configuration [id]. */
    fun addressOf(id: String): String = Address.of(PAGE, ID to id)

    /**
     * Returns the list of [twists], sorted by name, each with a button that enables or disables it.
     * [enabledId] is the identifier of the enabled configuration, or `null` if none is enabled.
     */
    fun list(
        view: View,
        twists: List<TwistConfigurationDto>,
        enabledId: String?,
    ): Page =
        Page("Twist configurations") {
            p { +INTRO }
            p(classes = "actions") { a(href = NEW) { +"New twist configuration" } }
            if (twists.isEmpty()) {
                p { +"There are no twist configurations yet." }
                return@Page
            }
            div(classes = "scroll") {
                table(classes = "twists") {
                    thead {
                        tr { COLUMNS.forEach { th(scope = ThScope.col) { +it } } }
                    }
                    tbody {
                        for (twist in twists.sortedWith(BY_NAME)) {
                            val isEnabled = twist.id == enabledId
                            tr {
                                th(scope = ThScope.row) { a(href = addressOf(twist.id)) { +twist.name } }
                                td { +"${twist.pairs.size}" }
                                td {
                                    val action = Address.of(ENABLED, ID to twist.id, FROM to FROM_LIST)
                                    postForm(view, action, classes = "switch") {
                                        if (isEnabled) span(classes = "mark") { +"enabled" } else span(classes = "none") { +"disabled" }
                                        +" "
                                        button(type = ButtonType.submit, name = Designation.FIELD) {
                                            value = if (isEnabled) Designation.NO else Designation.YES
                                            attributes["aria-label"] = "${if (isEnabled) "Disable" else "Enable"} ${twist.name}"
                                            +(if (isEnabled) "Disable" else "Enable")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    /**
     * Returns the page of [twist]. The page contains the form that edits the configuration (displayed with
     * [state]), the button that enables or disables it, and a link to the deletion step. [isEnabled] says
     * whether the configuration is the enabled one.
     */
    fun twist(
        view: View,
        twist: TwistConfigurationDto,
        isEnabled: Boolean,
        state: FormState,
    ): Page =
        Page("Twist configuration ${twist.name}") {
            twistForm(view, addressOf(twist.id), state, marked = true, "Save the twist configuration")
            h2 { +"Status" }
            designationOf(view, AS_ENABLED, isEnabled, Address.of(ENABLED, ID to twist.id))
            p(classes = "actions") { a(href = Address.of(DELETE, ID to twist.id)) { +"Delete the twist configuration" } }
        }

    /** Returns the page with the form that creates a twist configuration. The form is displayed with [state]. */
    fun new(
        view: View,
        state: FormState,
    ): Page = Page("New twist configuration") { twistForm(view, NEW, state, marked = false, "Create the twist configuration") }

    /** Returns the page that asks whether to delete [twist]. [isEnabled] says whether it is the enabled configuration. */
    fun deletion(
        view: View,
        twist: TwistConfigurationDto,
        isEnabled: Boolean,
    ): Page {
        val title = "Delete the twist configuration ${twist.name}?"
        val consequences = listOf(if (isEnabled) ENABLED_ENDS else NOT_IN_USE)
        val action = Address.of(DELETE, ID to twist.id)
        return Confirmation.page(view, title, consequences, "Delete the twist configuration", action, addressOf(twist.id))
    }

    /**
     * Renders the form of a twist configuration. The browser submits it to [action], and it's displayed with
     * [state]. The form of an existing configuration is [marked], which means it tracks the initial values of
     * its fields (see [Shown]). The button is labeled [save].
     */
    private fun FlowContent.twistForm(
        view: View,
        action: String,
        state: FormState,
        marked: Boolean,
        save: String,
    ) {
        postForm(view, action, inPlace = true) {
            if (marked) shownOf(state, TwistForm.FIELDS)
            rejectionOf(state)
            textField(state, TwistForm.NAME, "Name", hint = NAME_HINT)
            pairRows(state)
            otherProblemsOf(state)
            p(classes = "actions") {
                submit(save)
                +" "
                a(href = LIST) { +"Cancel" }
            }
        }
    }

    /** Shows one row for each replacement: the text to replace, and the text that takes its place. */
    private fun FlowContent.pairRows(state: FormState) {
        val originations = state.all(TwistForm.ORIGINATION)
        val destinations = state.all(TwistForm.DESTINATION)
        fieldGroup(state, TwistForm.ORIGINATION, "Replacements", listOf("pairs"), PAIRS_HINT) {
            for (index in originations.indices) {
                div(classes = "row") {
                    span { +"Replace" }
                    textInput(name = TwistForm.ORIGINATION) {
                        attributes["aria-label"] = "Text to replace in row ${index + 1}"
                        value = originations[index]
                    }
                    span { +"with" }
                    textInput(name = TwistForm.DESTINATION) {
                        attributes["aria-label"] = "Replacement in row ${index + 1}"
                        value = destinations.getOrElse(index) { "" }
                    }
                }
            }
        }
    }

    private val AS_ENABLED =
        Designation(
            held = "This twist configuration is enabled. The server applies it to the target of every request.",
            notHeld = "This twist configuration is not enabled. Enabling it disables any other twist configuration.",
            assign = "Enable it",
            remove = "Disable it",
        )

    private const val INTRO =
        "A twist configuration rewrites the target of a request before the server looks for a service. " +
            "At most one twist configuration is enabled at a time."
    private const val ENABLED_ENDS = "It is the enabled twist configuration. The server will stop rewriting targets."
    private const val NOT_IN_USE = "It is not enabled, so deleting it does not change how the server routes requests."
    private const val NAME_HINT = "If the name is empty, the server generates one."
    private const val PAIRS_HINT =
        "The server uses the first row whose text occurs in the target of a request, and it replaces every occurrence. " +
            "Both texts are taken literally. A row without a text to replace is left out."
}

/**
 * The form of a twist configuration: the names of its fields, the state it displays, and the attributes it
 * asks the admin API to set. The form of an existing configuration only sends the attributes whose fields
 * were changed (see [Shown]). The form for a new configuration sends all of them.
 */
internal object TwistForm {
    const val NAME = "name"

    /** The field for the text to replace. The form has one for every row. */
    const val ORIGINATION = "origination"

    /** The field for the replacement. The form has one for every row. */
    const val DESTINATION = "destination"

    /** The fields whose initial values the form of an existing configuration tracks (see [Shown]). */
    val FIELDS = listOf(NAME, ORIGINATION, DESTINATION)

    /** The number of empty rows that the form offers for new replacements. */
    private const val FREE_ROWS = 3

    /** Returns the initial state of the form for a new twist configuration: empty rows only. */
    fun blank(): FormState {
        val rows = List(FREE_ROWS) { "" }
        return FormState(mapOf(NAME to listOf(""), ORIGINATION to rows, DESTINATION to rows))
    }

    /** Returns the initial state of the form for [twist]: its replacements, followed by a few empty rows. */
    fun of(twist: TwistConfigurationDto): FormState {
        val free = List(FREE_ROWS) { "" }
        val originations = twist.pairs.map { inOneLine(it.origination) } + free
        val destinations = twist.pairs.map { inOneLine(it.destination) } + free
        return FormState(mapOf(NAME to listOf(inOneLine(twist.name)), ORIGINATION to originations, DESTINATION to destinations))
    }

    /**
     * Returns the attributes that the form asks the admin API to set. For an existing configuration, that's
     * only the attributes whose fields were changed (see [Shown]). The replacements are sent in the order of
     * their rows, and a row without a text to replace is left out, which is what the server does as well.
     *
     * @throws InvalidInput if a changed row has a replacement but no text to replace.
     */
    fun attributesOf(input: FormInput): JsonObject {
        val destinations = input.all(DESTINATION)
        val rows = input.all(ORIGINATION).mapIndexed { index, origination -> origination to destinations.getOrElse(index) { "" } }
        val pairsChanged = input.isChanged(ORIGINATION, DESTINATION)
        val incomplete = rows.indices.filter { rows[it].first.isEmpty() && rows[it].second.isNotEmpty() }
        if (pairsChanged && incomplete.isNotEmpty()) {
            throw InvalidInput(incomplete.map { FieldErrorDto("pairs", "Row ${it + 1} has a replacement but no text to replace.") })
        }
        val pairs = rows.filter { it.first.isNotEmpty() }.map { TwistPairDto(it.first, it.second) }
        return buildJsonObject {
            if (input.isChanged(NAME)) put("name", input.text(NAME))
            if (pairsChanged) put("pairs", AdminApiJson.instance.encodeToJsonElement(ListSerializer(TwistPairDto.serializer()), pairs))
        }
    }
}
