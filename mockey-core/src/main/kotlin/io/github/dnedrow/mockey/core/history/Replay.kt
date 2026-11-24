package io.github.dnedrow.mockey.core.history

import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.request.Parameter
import io.github.dnedrow.mockey.core.routing.percentEncoded

/**
 * What a replay changes of the request of the history entry that it replays. A part that is `null` stays as
 * the entry has it.
 *
 * - [headers] take the place of all headers of the request.
 * - [parameters] take the place of the query string of the request: the replay gets a query string that holds
 *   these parameters, in their order. The fields of a form are no part of them, because they are in the body.
 * - [body] takes the place of the body of the request.
 *
 * The edits take over the array of their body. Whoever creates edits must not change that array afterwards.
 */
public class ReplayEdits
    @JvmOverloads
    constructor(
        headers: List<Header>? = null,
        parameters: List<Parameter>? = null,
        body: ByteArray? = null,
    ) {
        /** The headers of the replay, or `null` for those of the entry. */
        public val headers: List<Header>? = headers?.toList()

        /** The parameters of the query string of the replay, or `null` for the query string of the entry. */
        public val parameters: List<Parameter>? = parameters?.toList()

        private val bodyBytes: ByteArray? = body

        /** The body of the replay, or `null` for the body of the entry. Every read gives a new array. */
        public val body: ByteArray?
            get() = bodyBytes?.copyOf()

        /** Returns which parts are replaced. Their content is left out, because it can hold secrets. */
        override fun toString(): String {
            val replaced = listOf("headers" to headers, "parameters" to parameters, "body" to bodyBytes).filter { it.second != null }
            return "ReplayEdits(${replaced.joinToString { it.first }})"
        }
    }

/** What became of the replay of a history entry. */
internal sealed interface Replay<out T> {
    /** The [request] of the replay was handled, and [result] is what handling it gave. */
    data class Handled<out T>(
        val request: MockRequest,
        val result: T,
    ) : Replay<T>

    /** The entry cannot be replayed as it was asked for, and nothing was handled. */
    data class Refused(
        val explanation: String,
    ) : Replay<Nothing>

    /** The history keeps no entry with the identifier that was given. */
    data class NotFound(
        val message: String,
    ) : Replay<Nothing>
}

/**
 * Replays the entry with the identifier [id]: lets [handle] handle a request like the one of that entry, with
 * what [edits] change of it, see [replayRequest].
 *
 * [handle] is what handles a request at the mock endpoint. The replay is therefore a request like any other:
 * it is handled under the definitions as they are now, which need not be those under which the entry was
 * recorded, and it gets a history entry of its own. The entry that is replayed stays as it is.
 */
internal suspend fun <T> HistoryStore.replay(
    id: HistoryEntryId,
    edits: ReplayEdits,
    handle: suspend (MockRequest) -> T,
): Replay<T> {
    val entry = entry(id) ?: return Replay.NotFound("The history keeps no entry with the identifier $id.")
    val request = entry.replayRequest(edits) ?: return Replay.Refused(cutBodyRefusal(entry))
    return Replay.Handled(request, handle(request))
}

/**
 * Returns the request that replays the entry, or `null` when there is none.
 *
 * The request has the method of the request of the entry and its URL, which holds the target as the client
 * wrote it, before twisting. Its headers, its query string, and its body are those of the entry, unless
 * [edits] replace them. A request with another body states the length of that body in its `Content-Length`
 * header, where it has such a header.
 *
 * There is no request for an entry whose request body was cut when it was recorded, unless [edits] give the
 * body: the entry does not hold the body that the client sent.
 */
internal fun HistoryEntry.replayRequest(edits: ReplayEdits): MockRequest? {
    val newBody = edits.body
    if (newBody == null && request.body.isCut) return null
    val headers = edits.headers ?: request.headers
    return MockRequest(
        method = request.method,
        url = request.url,
        query = if (edits.parameters == null) request.query else queryOf(edits.parameters),
        headers = if (newBody == null) headers else headers.withContentLength(newBody.size),
        body = newBody ?: request.body.bytes,
    )
}

private fun cutBodyRefusal(entry: HistoryEntry): String =
    "The request body of the history entry ${entry.id} was cut when it was recorded, so the entry can be replayed only with a body " +
        "that is given for the replay."

/** Returns the query string that holds [parameters] in their order, or `null` when there are none. */
private fun queryOf(parameters: List<Parameter>): String? =
    parameters.takeIf { it.isNotEmpty() }?.joinToString("&") { "${percentEncoded(it.name)}=${percentEncoded(it.value)}" }

/** Returns the headers with [length] as the value of every `Content-Length` header among them. */
private fun List<Header>.withContentLength(length: Int): List<Header> =
    map { if (it.name.equals("Content-Length", ignoreCase = true)) Header(it.name, length.toString()) else it }
