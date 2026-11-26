package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.HistoryEntryDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FlowOrPhrasingContent
import kotlinx.html.TBODY
import kotlinx.html.TagConsumer
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
 * of the list, and whether the list is brought up to date by itself ([refresh]). All of it is in the address
 * of the list, so the same address shows the same list in any browser.
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

    /** The number of seconds after which a list with automatic refresh is brought up to date. */
    const val REFRESH_SECONDS = 5

    /** The identifier of the part of the list page that depends on what the history holds, see [entriesOf]. */
    const val ENTRIES_ID = "entries"

    /** How the identifier of a history entry is written: a whole number without a sign. */
    val ENTRY_ID = Regex("[0-9]{1,18}")

    private val COLUMNS = listOf("Time", "Client", "Request", "Service", "Answer", "Actions")

    /** Returns the identifier of the row of the entry [id] in the list, which an address can refer to after a `#`. */
    fun anchorOf(id: Long): String = Address.anchorOf("entry", "$id")

    /** Returns the identifier of the link or the button in the row of [entry] that is for [what], see [link]. */
    fun controlOf(
        what: String,
        entry: HistoryEntryDto,
    ): String = "$what-${anchorOf(entry.id)}"

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
        val listed = Part(Place.of(ENTRIES_ID)) { entriesOf(view, shown, entries) }
        return Page("History", live = Live(REFRESH_SECONDS, listed).takeIf { shown.refresh }) {
            p { +INTRO }
            filterOf(shown)
            listed.content(consumer)
        }
    }

    /**
     * Writes everything of the list page that depends on what the history holds: the line about the
     * service of the view, the links of the page, the entries of [entries] that are on the page of the
     * view, and the links to the pages before and after, or the line that says why the page lists nothing.
     *
     * With automatic refresh, this is the part of the page that the script brings up to date (see [Live]).
     * The filter form isn't in it, so the script doesn't take away what someone types there.
     */
    private fun TagConsumer<*>.entriesOf(
        view: View,
        shown: HistoryView,
        entries: List<HistoryEntryDto>,
    ) {
        val onPage = entries.drop((shown.page - 1) * PAGE_SIZE).take(PAGE_SIZE)
        val hasOlder = entries.size > shown.page * PAGE_SIZE
        div {
            id = ENTRIES_ID
            if (shown.refresh) Enhancement.refresh(this, shown.address(), REFRESH_SECONDS)
            if (shown.service != null) serviceLineOf(shown, entries)
            p(classes = "actions") {
                link(StatisticsPages.PAGE, "statistics", "Statistics")
                if (!shown.refresh) link(shown.copy(refresh = true).address(), "refresh-on", "Switch automatic refresh on")
                if (entries.isNotEmpty() || shown.isFiltered) link(DELETE, "delete-all", "Delete all entries")
                val ofService = shown.service?.let { Address.of(DELETE, HistoryView.SERVICE to it) }
                if (ofService != null) link(ofService, "delete-of-service", "Delete the entries of this service")
            }
            if (shown.refresh) {
                p(classes = "note") {
                    +"Automatic refresh is on, so the list is brought up to date every $REFRESH_SECONDS seconds. "
                    link(shown.copy(refresh = false).address(), "refresh-off", "Switch automatic refresh off")
                }
            }
            if (onPage.isEmpty()) {
                emptyLineOf(shown)
                return@div
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
                        link(shown.copy(page = shown.page - 1).address(), "newer-entries", "Newer entries")
                        +" "
                    }
                    span { +"Page ${shown.page}" }
                    if (hasOlder) {
                        +" "
                        link(shown.copy(page = shown.page + 1).address(), "older-entries", "Older entries")
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
     * rest of the view. Below it, a line says when the tokens limit the list.
     */
    private fun FlowContent.filterOf(shown: HistoryView) {
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
    }

    /**
     * Says that the list only shows the entries of one service, and names the service if one of [entries]
     * holds its name.
     */
    private fun FlowContent.serviceLineOf(
        shown: HistoryView,
        entries: List<HistoryEntryDto>,
    ) {
        val name = entries.firstNotNullOfOrNull { it.service?.name }
        p(classes = "count") {
            +(if (name == null) "Only the entries of one service are listed. " else "Only the entries of the service $name are listed. ")
            link(shown.copy(service = null, page = 1).address(), "all-services", "Show the entries of all services")
        }
    }

    /**
     * A link of the part that the script brings up to date, to [address], which says [text]. Every link and
     * every button of that part has an identifier, here [id]: by it, the script finds the one that had the
     * focus before, and gives it the focus again.
     */
    private fun FlowOrPhrasingContent.link(
        address: String,
        id: String,
        text: String,
    ) {
        a(href = address) {
            this.id = id
            +text
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
            link(shown.copy(page = 1).address(), "first-page", "Go to the first page")
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
                    link(ServicePages.addressOf(service.id), controlOf("service-of", entry), service.name)
                    entry.responseMode?.let { +" (${Modes.labelOf(it)})" }
                }
            }
            td(classes = "answer") { +answerOf(entry) }
            td(classes = "entry-actions") {
                a(href = HistoryEntryPages.addressOf(entry.id)) {
                    id = controlOf("view", entry)
                    attributes["aria-label"] = "View the entry ${entry.id}"
                    +"View"
                }
                postForm(view, shown.address(FLAG, ID to "${entry.id}", FROM to FROM_LIST), classes = "inline") {
                    flagButtonOf(entry, inList = true)
                }
                postForm(view, shown.address(REMOVE, ID to "${entry.id}"), classes = "inline") {
                    button(type = ButtonType.submit, classes = "danger") {
                        id = controlOf("delete", entry)
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
        id = HistoryPages.controlOf("flag", entry)
        value = if (entry.flagged) Designation.NO else Designation.YES
        val named = if (entry.flagged) "Remove the flag of the entry ${entry.id}" else "Flag the entry ${entry.id}"
        if (inList) attributes["aria-label"] = named
        +(if (entry.flagged) "Unflag" else "Flag")
    }
}
