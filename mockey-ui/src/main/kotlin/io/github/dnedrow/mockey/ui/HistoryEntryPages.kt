package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.HistoryEntryDto
import io.github.dnedrow.mockey.api.NameValueDto
import io.github.dnedrow.mockey.api.ReplayRequestDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.FlowContent
import kotlinx.html.ThScope
import kotlinx.html.a
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.hiddenInput
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.util.Base64

/**
 * The pages for a single history entry: the page that shows everything the entry holds, the form that
 * replays its request with edits, and the form that saves its response as a scenario.
 *
 * See `docs/decisions/ui-pages.md` for the reasoning behind these pages.
 */
internal val HistoryEntryArea =
    Area { routing, ui ->
        with(routing) {
            get(HistoryEntryPages.PAGE) {
                val id = call.entryId()
                ui.show(call) { view, _ -> HistoryEntryPages.entry(view, ui.client.getHistoryEntry(id)) }
            }
            get(HistoryEntryPages.REPLAY) {
                val id = call.entryId()
                ui.show(call) { view, _ ->
                    val entry = ui.client.getHistoryEntry(id)
                    HistoryEntryPages.replay(view, entry, ReplayForm.of(entry))
                }
            }
            post(HistoryEntryPages.REPLAY) {
                val id = call.entryId()
                val again: suspend (View, SystemStatusDto, FormState) -> Page = { view, _, state ->
                    HistoryEntryPages.replay(view, ui.client.getHistoryEntry(id), state)
                }
                ui.submit(call, again) { input ->
                    val replayed = ui.client.replayHistoryEntry(id, ReplayForm.editsOf(input))
                    val notice = "The request of the history entry $id was replayed. This is the entry of the replay."
                    Outcome.Done(HistoryEntryPages.addressOf(replayed.id), notice)
                }
            }
            get(HistoryEntryPages.SCENARIO) {
                val id = call.entryId()
                ui.show(call) { view, _ -> HistoryEntryPages.scenario(view, ui.client.getHistoryEntry(id), FormState.EMPTY) }
            }
            post(HistoryEntryPages.SCENARIO) {
                val id = call.entryId()
                val again: suspend (View, SystemStatusDto, FormState) -> Page = { view, _, state ->
                    HistoryEntryPages.scenario(view, ui.client.getHistoryEntry(id), state)
                }
                ui.submit(call, again) { input ->
                    val entry = ui.client.getHistoryEntry(id)
                    val created = ui.client.saveHistoryEntryAsScenario(id, ScenarioOfEntryForm.attributesOf(input))
                    val notice = "The scenario ${briefly(created.name)} was created from the history entry $id."
                    val to = entry.service?.let { ScenarioPages.addressOf(it.id, created.id) } ?: HistoryEntryPages.addressOf(id)
                    Outcome.Done(to, notice)
                }
            }
        }
    }

/** The pages for a single history entry. */
internal object HistoryEntryPages {
    /** The path of the page of a history entry. */
    const val PAGE = "/history/entry"

    /** The path of the form that replays the request of a history entry. */
    const val REPLAY = "/history/replay"

    /** The path of the form that saves the response of a history entry as a scenario. */
    const val SCENARIO = "/history/scenario"

    private const val NONE = "none"
    private const val HEADER_ROWS = 6
    private const val PARAMETER_ROWS = 4
    private const val BODY_ROWS = 12

    /** Returns the address of the page for the history entry [id]. */
    fun addressOf(id: Long): String = addressOf(PAGE, id)

    private fun addressOf(
        path: String,
        id: Long,
    ): String = Address.of(path, HistoryPages.ID to "$id")

    /**
     * Returns the page of [entry]: when and how its request was handled, the request, the response, and the
     * links and buttons that replay, save, flag, and delete the entry.
     */
    fun entry(
        view: View,
        entry: HistoryEntryDto,
    ): Page =
        Page("History entry ${entry.id}") {
            div(classes = "actions") {
                a(href = addressOf(REPLAY, entry.id)) { +"Replay" }
                if (entry.service != null) a(href = addressOf(SCENARIO, entry.id)) { +"Save as a scenario" }
                postForm(view, addressOf(HistoryPages.FLAG, entry.id), classes = "inline") { flagButtonOf(entry, inList = false) }
                postForm(view, addressOf(HistoryPages.REMOVE, entry.id), classes = "inline") { submit("Delete", dangerous = true) }
                a(href = HistoryPages.LIST) { +"Back to the history" }
            }
            factsOf(entry)
            if (entry.inspectionMessages.isNotEmpty()) {
                h2 { +"Inspection messages" }
                ul { entry.inspectionMessages.forEach { li { +it } } }
            }
            h2 { +"Request" }
            p {
                +"${entry.request.method} "
                urlOf(HistoryPages.addressedOf(entry))
            }
            h3 { +"Headers" }
            headersOrNone(entry.request.headers, "The request had no headers.")
            if (entry.request.parameters.isNotEmpty()) {
                h3 { +"Parameters" }
                namedValuesOf("Parameter", entry.request.parameters)
            }
            if (entry.request.cookies.isNotEmpty()) {
                h3 { +"Cookies" }
                namedValuesOf("Cookie", entry.request.cookies)
            }
            h3 { +"Body" }
            recordedBodyOf(RecordedContent.of(entry.request.body, entry.request.headers), "The request had no body.")
            h2 { +"Response" }
            p { +"Status code ${entry.response.status}" }
            h3 { +"Headers" }
            headersOrNone(entry.response.headers, "The response had no headers.")
            h3 { +"Body" }
            recordedBodyOf(RecordedContent.of(entry.response.body, entry.response.headers), "The response had no body.")
        }

    /**
     * Returns the page with the form that replays the request of [entry]. The form is displayed with
     * [state], and it starts with the headers, the query parameters, and the body of the entry.
     */
    fun replay(
        view: View,
        entry: HistoryEntryDto,
        state: FormState,
    ): Page =
        Page("Replay the history entry ${entry.id}") {
            p { +REPLAY_INTRO }
            p {
                +"${entry.request.method} "
                urlOf(entry.request.url)
            }
            postForm(view, addressOf(REPLAY, entry.id)) {
                shownOf(state, ReplayForm.trackedFields(entry))
                hiddenInput(name = ReplayForm.BODY_LINE_ENDINGS) { value = state.value(ReplayForm.BODY_LINE_ENDINGS) }
                rejectionOf(state)
                textAreaField(state, ReplayForm.HEADERS, "Headers", hint = HEADERS_HINT, rows = HEADER_ROWS)
                if (ReplayForm.parameterLinesOf(entry) == null) {
                    p(classes = "hint") { +QUERY_STAYS }
                } else {
                    textAreaField(state, ReplayForm.PARAMETERS, "Query parameters", hint = PARAMETERS_HINT, rows = PARAMETER_ROWS)
                }
                textAreaField(state, ReplayForm.BODY, "Body", api = "bodyBase64", hint = ReplayForm.bodyHintOf(entry), rows = BODY_ROWS)
                otherProblemsOf(state)
                p(classes = "actions") {
                    submit("Replay the request")
                    +" "
                    a(href = addressOf(entry.id)) { +"Cancel" }
                }
            }
        }

    /**
     * Returns the page with the form that saves the response of [entry] as a scenario. The form is displayed
     * with [state]. If the server can't make a scenario from the entry, the page says why instead.
     */
    fun scenario(
        view: View,
        entry: HistoryEntryDto,
        state: FormState,
    ): Page =
        Page("Save the history entry ${entry.id} as a scenario") {
            val service = entry.service
            val refusal =
                when {
                    service == null -> NO_SERVICE
                    entry.response.body.isCut -> CUT_RESPONSE
                    else -> null
                }
            if (service == null || refusal != null) {
                p(classes = "problem") { +refusal.orEmpty() }
                p(classes = "actions") { a(href = addressOf(entry.id)) { +"Back to the entry" } }
                return@Page
            }
            p { +"$SCENARIO_INTRO It belongs to the service ${service.name}." }
            postForm(view, addressOf(SCENARIO, entry.id)) {
                rejectionOf(state)
                textField(state, ScenarioOfEntryForm.NAME, "Name", hint = NAME_HINT)
                otherProblemsOf(state)
                p(classes = "actions") {
                    submit("Create the scenario")
                    +" "
                    a(href = addressOf(entry.id)) { +"Cancel" }
                }
            }
        }

    /** Shows when and how the request of [entry] was handled. */
    private fun FlowContent.factsOf(entry: HistoryEntryDto) {
        val service = entry.service
        dl {
            dt { +"Time" }
            dd { timeOf(entry.time) }
            dt { +"Client" }
            dd { +(entry.clientAddress ?: "unknown") }
            dt { +"Service" }
            dd {
                if (service == null) {
                    +NONE
                } else {
                    a(href = ServicePages.addressOf(service.id)) { +service.name }
                    +" "
                    tagsOf(service.tags)
                }
            }
            entry.responseMode?.let { mode ->
                dt { +"Response mode" }
                dd { +Modes.labelOf(mode) }
            }
            dt { +"Outcome" }
            dd { +Outcomes.labelOf(entry.outcome) }
            entry.scenario?.let { scenario ->
                dt { +"Scenario" }
                dd {
                    if (service == null) +scenario.name else a(href = ScenarioPages.addressOf(service.id, scenario.id)) { +scenario.name }
                    +" "
                    tagsOf(scenario.tags)
                }
            }
            dt { +"Target" }
            dd { urlOf(entry.target) }
            entry.targetBeforeTwisting?.let { before ->
                dt { +"Target before twisting" }
                dd { urlOf(before) }
            }
            entry.explanation?.let { explanation ->
                dt { +"Explanation" }
                dd(classes = "lines") { +explanation }
            }
            dt { +"Flag" }
            dd { +(if (entry.flagged) "flagged" else "not flagged") }
        }
    }

    /** Shows [headers], or the sentence [none] if there aren't any. */
    private fun FlowContent.headersOrNone(
        headers: List<HeaderDto>,
        none: String,
    ) {
        if (headers.isEmpty()) p(classes = "none") { +none } else headersOf(headers)
    }

    /** Shows [pairs] in their order, each with its name and its value. [what] is the heading of the name column. */
    private fun FlowContent.namedValuesOf(
        what: String,
        pairs: List<NameValueDto>,
    ) {
        table(classes = "headers") {
            thead {
                tr {
                    th(scope = ThScope.col) { +what }
                    th(scope = ThScope.col) { +"Value" }
                }
            }
            tbody {
                for (pair in pairs) {
                    tr {
                        td { +pair.name }
                        td { +pair.value }
                    }
                }
            }
        }
    }

    private const val REPLAY_INTRO =
        "The server handles the request again, with the definitions as they are now, and records a new entry for it. " +
            "Change whatever the replay should send differently. Everything else is sent exactly as it was recorded."
    private const val HEADERS_HINT = "One header per line, in the form Name: value."
    private const val PARAMETERS_HINT =
        "The parameters of the query string, one per line, in the form name=value. They are shown decoded, " +
            "and the server encodes them again."
    private const val QUERY_STAYS =
        "The query string of this request cannot be shown as one parameter per line, so the replay sends it as it was recorded."
    private const val SCENARIO_INTRO = "The scenario gets the status code, the headers, and the body of this response."
    private const val NAME_HINT = "If the name is empty, the server generates one."
    private const val NO_SERVICE = "No service handled this request, so there is no service that the scenario could belong to."
    private const val CUT_RESPONSE =
        "The server kept only the start of this response body, so a scenario made from it would not give the same response."
}

/**
 * The form that replays the request of a history entry: the names of its fields, the state it displays, and
 * the edits it asks the admin API to apply. The form only sends the parts that were changed (see [Shown]),
 * so everything else is replayed byte for byte.
 */
internal object ReplayForm {
    const val HEADERS = "headers"
    const val PARAMETERS = "parameters"
    const val BODY = "body"

    /** The hidden field that remembers the line endings of the recorded body (see [LineEndings]). */
    const val BODY_LINE_ENDINGS = "bodyLineEndings"

    /** The member of a replay request that holds the body. Messages about the body name it. */
    private const val BODY_API = "bodyBase64"

    /**
     * Returns the fields whose initial values the form for [entry] tracks (see [Shown]). The query
     * parameters are only tracked if the form has a field for them. A body that was cut isn't tracked,
     * because the server needs a body for the replay either way, so the form always sends that field.
     */
    fun trackedFields(entry: HistoryEntryDto): List<String> =
        listOfNotNull(HEADERS, PARAMETERS.takeIf { parameterLinesOf(entry) != null }, BODY.takeUnless { entry.request.body.isCut })

    /** Returns the initial state of the form for [entry]: the headers, the query parameters, and the body of its request. */
    fun of(entry: HistoryEntryDto): FormState {
        val body = RecordedContent.of(entry.request.body, entry.request.headers).text.orEmpty()
        val values =
            mapOf(
                HEADERS to HeaderLines.textOf(entry.request.headers),
                PARAMETERS to parameterLinesOf(entry),
                BODY to body,
                BODY_LINE_ENDINGS to LineEndings.styleOf(body),
            )
        return FormState(values.mapNotNull { (name, value) -> value?.let { name to listOf(it) } }.toMap())
    }

    /**
     * Returns the query parameters of [entry] as the lines of a form field, or `null` if the query string
     * can't be shown that way (see [ParameterLines.textOf]). The parameters are decoded the way a form
     * decodes them: a plus sign is a space, and a percent escape is a byte of UTF-8 text.
     */
    fun parameterLinesOf(entry: HistoryEntryDto): String? {
        val query = entry.request.query.orEmpty()
        val parts = query.split('&').filter { it.isNotEmpty() }
        val parameters =
            try {
                parts.map { NameValueDto(decoded(it.substringBefore('=')), decoded(it.substringAfter('=', ""))) }
            } catch (_: IllegalArgumentException) {
                // A percent sign that doesn't start an escape can't be shown decoded.
                return null
            }
        return ParameterLines.textOf(parameters)
    }

    /** Returns the hint below the body field for [entry]. It says what the replay sends if the field stays as it is. */
    fun bodyHintOf(entry: HistoryEntryDto): String {
        val body = RecordedContent.of(entry.request.body, entry.request.headers)
        val kept = bytesOf(body.kept.toLong())
        return when {
            body.isCut -> "The server kept only the first $kept of this body, which has ${bytesOf(body.size)}. $SENDS_FIELD"
            body.text == null -> "The recorded body is not text, so this field starts empty. $EMPTY_KEEPS_BODY"
            else -> UNCHANGED_KEEPS_BODY
        }
    }

    /**
     * Returns the edits that the form asks the admin API to apply to the replay, or `null` if nothing was
     * changed. An edited body is encoded in the charset that the headers of the form declare, with the line
     * endings of the recorded body.
     *
     * @throws InvalidInput if the changed headers have a line that isn't a header, or if the charset can't
     *   encode the body.
     */
    fun editsOf(input: FormInput): ReplayRequestDto? {
        val entered = HeaderLines.read(input.lines(HEADERS), HEADERS)
        val headers = entered.headers.takeIf { input.isChanged(HEADERS) }
        if (headers != null && entered.errors.isNotEmpty()) throw InvalidInput(entered.errors)
        val parametersChanged = PARAMETERS in input.values && input.isChanged(PARAMETERS)
        val parameters = if (parametersChanged) ParameterLines.read(input.lines(PARAMETERS)) else null
        val body = if (input.isChanged(BODY)) bodyOf(input, entered.headers) else null
        if (headers == null && parameters == null && body == null) return null
        return ReplayRequestDto(headers, parameters, body)
    }

    /** Returns the body field of [input] in Base64, encoded in the charset that [headers] declare. */
    private fun bodyOf(
        input: FormInput,
        headers: List<HeaderDto>,
    ): String {
        val charset = RecordedContent.charsetOf(headers)
        val text = LineEndings.apply(input.text(BODY_LINE_ENDINGS), input.lines(BODY))
        val bytes = RecordedContent.encoded(text, charset)
        val refusal = "The body has a character that ${charset.name()} cannot encode. $CHANGE_BODY_OR_CHARSET"
        return Base64.getEncoder().encodeToString(bytes ?: throw InvalidInput(listOf(FieldErrorDto(BODY_API, refusal))))
    }

    private fun decoded(text: String): String = URLDecoder.decode(text, Charsets.UTF_8)

    private const val CHANGE_BODY_OR_CHARSET = "Change the body, or change the charset in the Content-Type header."
    private const val SENDS_FIELD = "The replay sends whatever this field holds."
    private const val EMPTY_KEEPS_BODY = "Leave it empty to send the recorded body again."
    private const val UNCHANGED_KEEPS_BODY = "If this field stays as it is, the replay sends the recorded body again."
}

/** The form that saves the response of a history entry as a scenario: its field, and what it asks the admin API for. */
internal object ScenarioOfEntryForm {
    const val NAME = "name"

    /**
     * Returns the attributes that the form states for the new scenario, or `null` if it doesn't state any.
     * The server takes everything else from the history entry.
     */
    fun attributesOf(input: FormInput): JsonObject? {
        val name = input.text(NAME).trim()
        return if (name.isEmpty()) null else buildJsonObject { put("name", name) }
    }
}
