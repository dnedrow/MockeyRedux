package io.github.dnedrow.mockey.core.history

import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.ServiceId

/**
 * Which entries of a history are asked for. A query without anything asks for all of them.
 *
 * - [tokens] are filter tokens, each of which an entry must satisfy, see [satisfies].
 * - [serviceId] asks for the entries of the requests that this service handled.
 * - [after] asks for the entries that were recorded after the entry with this identifier, also when that entry
 *   is no longer kept.
 * - [limit] is the greatest number of entries to return: the newest ones of those that are asked for.
 */
public data class HistoryQuery(
    public val tokens: List<String> = emptyList(),
    public val serviceId: ServiceId? = null,
    public val after: HistoryEntryId? = null,
    public val limit: Int? = null,
) {
    init {
        require(limit == null || limit >= 0) { "The number of entries to return must not be negative." }
    }

    /** Returns whether [entry] is one of the entries that this query asks for, whatever its [limit] is. */
    internal fun admits(entry: HistoryEntry): Boolean =
        (after == null || entry.id > after) &&
            (serviceId == null || entry.service?.id == serviceId) &&
            tokens.all(entry::satisfies)
}

/** What begins a filter token that an entry satisfies by not holding the rest of the token. */
private const val NEGATION = "!"

/**
 * Returns whether the entry satisfies the filter token [token].
 *
 * An entry satisfies a token that occurs in one of its texts, exactly as the token is written: in the same
 * letter case, and with every space of it. These are the texts of an entry:
 *
 * - the identifier, the name, and the tags of its service, and the tags of its scenario
 * - the address of the client
 * - the URL of the request with its query string, as the client wrote it
 * - each header of the request and of the response, as `Name: value`
 * - each parameter of the request, decoded, as `name=value`
 * - the body of the request and the body of the response, as far as the entry holds them
 *
 * An entry satisfies a token that begins with `!` when the rest of the token occurs in none of these texts.
 * Every entry satisfies a token that is blank, and no entry satisfies the token `!`, whose rest occurs in
 * every text.
 */
internal fun HistoryEntry.satisfies(token: String): Boolean =
    when {
        token.isBlank() -> true
        token.startsWith(NEGATION) -> !holds(token.removePrefix(NEGATION))
        else -> holds(token)
    }

/** Returns whether [text] occurs in one of the texts of the entry that a filter token is looked for in. */
private fun HistoryEntry.holds(text: String): Boolean =
    shortTexts().any { text in it } || text in request.bodyText || text in response.bodyText

/** The texts of the entry that a filter token is looked for in, without the two bodies. */
private fun HistoryEntry.shortTexts(): Sequence<String> =
    sequence {
        service?.let { yieldAll(listOf(it.id.value, it.name, it.tags.toString())) }
        scenario?.let { yield(it.tags.toString()) }
        clientAddress?.let { yield(it) }
        yield(request.query?.let { "${request.url}?$it" } ?: request.url)
        yieldAll(request.headers.map(::line))
        yieldAll(request.parameters.map { "${it.name}=${it.value}" })
        yieldAll(response.headers.map(::line))
    }

private fun line(header: Header): String = "${header.name}: ${header.value}"
