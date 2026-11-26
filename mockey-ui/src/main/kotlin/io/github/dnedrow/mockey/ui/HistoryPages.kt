package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.HistoryEntryDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.TBODY
import kotlinx.html.ThScope
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/**
 * The pages for the history: the list of the entries, and the confirmation step before all entries, or all
 * entries of one service, are deleted. The area also handles the buttons that flag and delete a single
 * entry.
 *
 * See `docs/decisions/ui-pages.md` for the reasoning behind these pages.
 */
internal val HistoryArea =
    Area { routing, ui ->
        with(routing) {
            get(HistoryPages.LIST) {
                val shown = HistoryView.of(call.request.queryParameters)
                ui.show(call) { view, _ ->
                    // One entry more than the pages hold shows whether there are older entries.
                    val entries = ui.client.listHistory(shown.tokens, shown.service, max = shown.page * HistoryPages.PAGE_SIZE + 1)
                    HistoryPages.list(view, shown, entries)
                }
            }
            post(HistoryPages.FLAG) {
                val id = call.entryId()
                val shown = HistoryView.of(call.request.queryParameters)
                val fromList = call.request.queryParameters[HistoryPages.FROM] == HistoryPages.FROM_LIST
                ui.submit(call) { input ->
                    val flag = Designation.isAssigned(input)
                    ui.client.flagHistoryEntry(id, flag)
                    val notice = if (flag) "The history entry $id is now flagged." else "The history entry $id is no longer flagged."
                    Outcome.Done(if (fromList) shown.addressOfRow(id) else HistoryEntryPages.addressOf(id), notice)
                }
            }
            post(HistoryPages.REMOVE) {
                val id = call.entryId()
                val shown = HistoryView.of(call.request.queryParameters)
                ui.submit(call) {
                    ui.client.deleteHistoryEntry(id)
                    Outcome.Done(shown.address(), "The history entry $id was deleted.")
                }
            }
            getAndPost(HistoryPages.DELETE) {
                val service = call.request.queryParameters[HistoryView.SERVICE]?.takeIf { it.isNotEmpty() }
                val ask: suspend (View, SystemStatusDto) -> Page = { view, _ ->
                    HistoryPages.deletion(view, service, ui.client.listHistory(serviceId = service, max = 1).firstOrNull())
                }
                ui.confirm(call, ask) {
                    // The entries are gone after the deletion, so the name for the notice is read first.
                    val newest = service?.let { ui.client.listHistory(serviceId = it, max = 1).firstOrNull() }
                    ui.client.deleteHistory(service)
                    Outcome.Done(HistoryPages.LIST, HistoryPages.deletedNotice(service, newest?.service?.name))
                }
            }
        }
    }

/**
 * Returns the identifier of the history entry that the address of the request names.
 *
 * @throws NoSuchPage if the address doesn't name an entry.
 */
internal fun ApplicationCall.entryId(): Long =
    partOfAddress(HistoryPages.ID).takeIf(HistoryPages.ENTRY_ID::matches)?.toLongOrNull() ?: throw NoSuchPage()

/**
 * How the history list is displayed: the filter [tokens], the [service] whose entries are listed, the [page]
 * of the list, and whether the page reloads itself ([refresh]). All of it is in the address of the list, so
 * the same address shows the same list in any browser.
 */
internal data class HistoryView(
    val tokens: List<String> = emptyList(),
    val service: String? = null,
    val page: Int = 1,
    val refresh: Boolean = false,
) {
    /** Whether the list is limited to some of the entries. */
    val isFiltered: Boolean
        get() = tokens.isNotEmpty() || service != null

    /**
     * Returns the address of the page at [path] with this view in its parameters, after the parameters
     * [first]. An action of the list carries the view this way, so the browser gets back to the same list.
     */
    fun address(
        path: String = HistoryPages.LIST,
        vararg first: Pair<String, String?>,
    ): String {
        val ofTheView =
            arrayOf(
                TOKENS to tokens.joinToString(" ").ifEmpty { null },
                SERVICE to service,
                PAGE to "$page".takeIf { page > 1 },
                REFRESH to ON.takeIf { refresh },
            )
        return Address.of(path, *first, *ofTheView)
    }

    /**
     * Returns the address of the list at the row of the entry [id]. A page that reloads itself would jump
     * back to that row every time, so its address doesn't name the row.
     */
    fun addressOfRow(id: Long): String = if (refresh) address() else "${address()}#${HistoryPages.anchorOf(id)}"

    internal companion object {
        /** The address parameter that holds the filter tokens, with spaces between them. */
        const val TOKENS = "tokens"

        /** The address parameter that holds the identifier of the service whose entries are listed. */
        const val SERVICE = "service"

        /** The address parameter that holds the number of the page, starting at 1. */
        const val PAGE = "page"

        /** The address parameter that switches the automatic refresh on, with the value [ON]. */
        const val REFRESH = "refresh"
        const val ON = "on"

        /** The last page that an address can name. It keeps the number of entries that the UI asks for within bounds. */
        private const val LAST_PAGE = 1_000_000

        /** Returns the view that [parameters] describe. A parameter that's missing or unreadable gets its default. */
        fun of(parameters: Parameters): HistoryView =
            HistoryView(
                tokens = tagsIn(parameters[TOKENS].orEmpty()),
                service = parameters[SERVICE]?.takeIf { it.isNotEmpty() },
                page = parameters[PAGE]?.toIntOrNull()?.coerceIn(1, LAST_PAGE) ?: 1,
                refresh = parameters[REFRESH] == ON,
            )
    }
}

/** What the pages call the outcomes of a handled request. */
internal object Outcomes {
    /** Returns the label for [outcome], which is an outcome as the admin API names it. An unknown name is its own label. */
    fun labelOf(outcome: String): String =
        when (outcome) {
            "scenarioServed" -> "Scenario served"
            "errorScenarioServed" -> "Error scenario served"
            "noScenarioMatched" -> "No scenario matched"
            "proxied" -> "Proxied"
            "upstreamFailed" -> "Upstream failed"
            "noServiceMatched" -> "No service matched"
            else -> outcome
        }
}

/** The pages for the history list. */
internal object HistoryPages {
    /** The path of the list. */
    const val LIST = "/history"

    /** The path of the action that flags a history entry, or removes the flag. */
    const val FLAG = "/history/flag"

    /** The path of the action that deletes a single history entry. */
    const val REMOVE = "/history/remove"

    /** The path of the confirmation step that comes before all entries, or all entries of one service, are deleted. */
    const val DELETE = "/history/delete"

    /** The address parameter that holds the identifier of a history entry. */
    const val ID = "id"

    /** The address parameter that says which page an action came from. [FROM_LIST] sends the browser back to the list. */
    const val FROM = "from"
    const val FROM_LIST = "list"

    /** The number of entries on a page of the list. */
    const val PAGE_SIZE = 50

    /** The number of seconds between two reloads of a list with automatic refresh. */
    const val REFRESH_SECONDS = 5

    /** How the identifier of a history entry is written: a whole number without a sign. */
    val ENTRY_ID = Regex("[0-9]{1,18}")

    private val COLUMNS = listOf("Time", "Client", "Request", "Service", "Answer", "Actions")

    /** Returns the identifier of the row of the entry [id] in the list, which an address can refer to after a `#`. */
    fun anchorOf(id: Long): String = Address.anchorOf("entry", "$id")

    /** Returns the address of the list that only shows the entries of the service [serviceId]. */
    fun addressOfService(serviceId: String): String = HistoryView(service = serviceId).address()

    /**
     * Returns the notice to show after history entries were deleted: all of them if [service] is `null`, and
     * the ones of that service otherwise. [name] is the name of the service, if an entry held one.
     */
    fun deletedNotice(
        service: String?,
        name: String?,
    ): String =
        when {
            service == null -> "All history entries were deleted."
            name == null -> "The history entries of the service were deleted."
            else -> "The history entries of the service ${briefly(name)} were deleted."
        }

    /**
     * Returns the list for the view [shown]. [entries] are the newest entries that match the view, starting
     * with the newest one: everything up to the end of the page, plus one more entry if there are older ones.
     */
    fun list(
        view: View,
        shown: HistoryView,
        entries: List<HistoryEntryDto>,
    ): Page {
        val onPage = entries.drop((shown.page - 1) * PAGE_SIZE).take(PAGE_SIZE)
        val hasOlder = entries.size > shown.page * PAGE_SIZE
        return Page("History", refresh = REFRESH_SECONDS.takeIf { shown.refresh }) {
            p { +INTRO }
            filterOf(shown, entries)
            p(classes = "actions") {
                a(href = StatisticsPages.PAGE) { +"Statistics" }
                if (!shown.refresh) a(href = shown.copy(refresh = true).address()) { +"Switch automatic refresh on" }
                if (entries.isNotEmpty() || shown.isFiltered) a(href = DELETE) { +"Delete all entries" }
                shown.service?.let { a(href = Address.of(DELETE, HistoryView.SERVICE to it)) { +"Delete the entries of this service" } }
            }
            if (shown.refresh) {
                p(classes = "note") {
                    +"Automatic refresh is on, so this page reloads every $REFRESH_SECONDS seconds. "
                    a(href = shown.copy(refresh = false).address()) { +"Switch automatic refresh off" }
                }
            }
            if (onPage.isEmpty()) {
                emptyLineOf(shown)
                return@Page
            }
            div(classes = "scroll") {
                table(classes = "history") {
                    thead {
                        tr { COLUMNS.forEach { th(scope = ThScope.col) { +it } } }
                    }
                    tbody { onPage.forEach { row(view, shown, it) } }
                }
            }
            if (shown.page > 1 || hasOlder) {
                p(classes = "paging") {
                    if (shown.page > 1) {
                        a(href = shown.copy(page = shown.page - 1).address()) { +"Newer entries" }
                        +" "
                    }
                    span { +"Page ${shown.page}" }
                    if (hasOlder) {
                        +" "
                        a(href = shown.copy(page = shown.page + 1).address()) { +"Older entries" }
                    }
                }
            }
        }
    }

    /**
     * Returns the page that asks whether to delete all history entries, or the entries of [service] if it
     * isn't `null`. [newest] is the newest entry that would be deleted. Without one, there's nothing to
     * delete, and the page says so instead of asking.
     */
    fun deletion(
        view: View,
        service: String?,
        newest: HistoryEntryDto?,
    ): Page {
        if (newest == null) {
            return Page(if (service == null) "The history is empty" else "The history has no entries of this service") {
                p { +"There is nothing to delete." }
                p(classes = "actions") { a(href = LIST) { +"Back to the history" } }
            }
        }
        val consequences = listOf(if (service == null) DELETES_ALL else DELETES_OF_SERVICE, KEEPS_DEFINITIONS)
        if (service == null) return Confirmation.page(view, "Delete all history entries?", consequences, CONFIRM, DELETE, LIST)
        val title = "Delete the history entries of the service ${newest.service?.name ?: service}?"
        val action = Address.of(DELETE, HistoryView.SERVICE to service)
        return Confirmation.page(view, title, consequences, CONFIRM, action, addressOfService(service))
    }

    /**
     * The form that sets the filter tokens. It leads to the first page of the filtered list and keeps the
     * rest of the view. Below it, a line says when the tokens limit the list, and another one says when the
     * list is limited to one service. [entries] are the listed entries, which may hold the name of that service.
     */
    private fun FlowContent.filterOf(
        shown: HistoryView,
        entries: List<HistoryEntryDto>,
    ) {
        val state = FormState.of(HistoryView.TOKENS to shown.tokens.joinToString(" "))
        getForm(LIST, classes = "filter") {
            textField(state, HistoryView.TOKENS, "Filter tokens", hint = TOKENS_HINT)
            shown.service?.let { service -> hiddenInput(name = HistoryView.SERVICE) { value = service } }
            if (shown.refresh) hiddenInput(name = HistoryView.REFRESH) { value = HistoryView.ON }
            submit("Filter")
        }
        if (shown.tokens.isNotEmpty()) {
            p(classes = "count") {
                +"Only the entries that satisfy every filter token are listed. "
                a(href = shown.copy(tokens = emptyList(), page = 1).address()) { +"Clear the filter tokens" }
            }
        }
        if (shown.service != null) serviceLineOf(shown, entries)
    }

    /** Says that the list only shows the entries of one service, and names the service if an entry holds its name. */
    private fun FlowContent.serviceLineOf(
        shown: HistoryView,
        entries: List<HistoryEntryDto>,
    ) {
        val name = entries.firstNotNullOfOrNull { it.service?.name }
        p(classes = "count") {
            +(if (name == null) "Only the entries of one service are listed. " else "Only the entries of the service $name are listed. ")
            a(href = shown.copy(service = null, page = 1).address()) { +"Show the entries of all services" }
        }
    }

    /** Says why the page lists nothing. */
    private fun FlowContent.emptyLineOf(shown: HistoryView) {
        if (shown.page == 1) {
            p { +(if (shown.isFiltered) "No entry matches." else "The history is empty.") }
            return
        }
        p {
            +"There are no entries on this page. "
            a(href = shown.copy(page = 1).address()) { +"Go to the first page" }
        }
    }

    /** The row of [entry] in the list, with the buttons that flag and delete the entry. */
    private fun TBODY.row(
        view: View,
        shown: HistoryView,
        entry: HistoryEntryDto,
    ) {
        tr(classes = "flagged".takeIf { entry.flagged }) {
            id = anchorOf(entry.id)
            td { timeOf(entry.time) }
            td { +entry.clientAddress.orEmpty() }
            td(classes = "request") { requestOf(entry) }
            td {
                val service = entry.service
                if (service == null) {
                    span(classes = "none") { +"none" }
                } else {
                    a(href = ServicePages.addressOf(service.id)) { +service.name }
                    entry.responseMode?.let { +" (${Modes.labelOf(it)})" }
                }
            }
            td(classes = "answer") { +answerOf(entry) }
            td(classes = "entry-actions") {
                a(href = HistoryEntryPages.addressOf(entry.id)) {
                    attributes["aria-label"] = "View the entry ${entry.id}"
                    +"View"
                }
                postForm(view, shown.address(FLAG, ID to "${entry.id}", FROM to FROM_LIST), classes = "inline") {
                    flagButtonOf(entry, inList = true)
                }
                postForm(view, shown.address(REMOVE, ID to "${entry.id}"), classes = "inline") {
                    button(type = ButtonType.submit, classes = "danger") {
                        attributes["aria-label"] = "Delete the entry ${entry.id}"
                        +"Delete"
                    }
                }
            }
        }
    }

    /**
     * Shows the method and the target of [entry], along with the marks for a flag, for a twisted target, and
     * for inspection messages. The target is shorter than the URL that the client addressed, which the page
     * of the entry shows.
     */
    private fun FlowContent.requestOf(entry: HistoryEntryDto) {
        +"${entry.request.method} "
        urlOf(entry.target + queryOf(entry))
        val messages = entry.inspectionMessages.size
        val marks =
            listOfNotNull(
                "flagged".takeIf { entry.flagged },
                "twisted".takeIf { entry.targetBeforeTwisting != null },
                (if (messages == 1) "1 inspection message" else "$messages inspection messages").takeIf { messages > 0 },
            )
        for (mark in marks) {
            +" "
            span(classes = "mark") { +mark }
        }
    }

    /** Returns the URL that the client of [entry] addressed, with its query string. */
    fun addressedOf(entry: HistoryEntryDto): String = entry.request.url + queryOf(entry)

    /** Returns the query string of [entry] with the question mark in front of it, or an empty text if there is none. */
    private fun queryOf(entry: HistoryEntryDto): String {
        val query = entry.request.query
        return if (query == null) "" else "?$query"
    }

    /** Returns a short description of the answer to [entry]: the status code, the outcome, and the scenario. */
    private fun answerOf(entry: HistoryEntryDto): String {
        val scenario = entry.scenario?.let { ": ${it.name}" }.orEmpty()
        return "${entry.response.status} ${Outcomes.labelOf(entry.outcome)}$scenario"
    }

    private const val INTRO =
        "The server records every request that reaches its mock endpoint, and it keeps the newest records in memory. " +
            "The newest entry comes first."
    private const val TOKENS_HINT =
        "Separate the tokens with spaces. An entry is listed if it holds every token, in exactly that letter case. " +
            "Put ! in front of a token to list only the entries that do not hold it."
    private const val CONFIRM = "Delete the entries"
    private const val DELETES_ALL = "This deletes every entry that the history keeps."
    private const val DELETES_OF_SERVICE = "This deletes every entry that the history keeps of this service."
    private const val KEEPS_DEFINITIONS = "Services and scenarios stay as they are."
}

/**
 * The button that flags [entry], or removes the flag if the entry has one. In the list, every row has such a
 * button, so the button also names its entry for a screen reader ([inList]).
 */
internal fun FlowContent.flagButtonOf(
    entry: HistoryEntryDto,
    inList: Boolean,
) {
    button(type = ButtonType.submit, name = Designation.FIELD) {
        value = if (entry.flagged) Designation.NO else Designation.YES
        val named = if (entry.flagged) "Remove the flag of the entry ${entry.id}" else "Flag the entry ${entry.id}"
        if (inList) attributes["aria-label"] = named
        +(if (entry.flagged) "Unflag" else "Flag")
    }
}
