package io.github.dnedrow.mockey.core.history

import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.request.Cookie
import io.github.dnedrow.mockey.core.request.Parameter
import io.github.dnedrow.mockey.core.request.charsetOf
import java.time.Instant

/**
 * The identifier of a [HistoryEntry]: a number that no other entry of the same engine has. The numbers grow
 * with the entries, so an entry that was recorded later has the greater number. The history is not stored, so
 * an engine that starts again counts from the beginning.
 */
@JvmInline
public value class HistoryEntryId(
    public val value: Long,
) : Comparable<HistoryEntryId> {
    override fun compareTo(other: HistoryEntryId): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()
}

/** What became of a request to the mock endpoint. Every history entry states exactly one outcome. */
public enum class Outcome {
    /** A scenario answered as the mode of its service has it: the default scenario, or the one that matched best. */
    SCENARIO_SERVED,

    /** No scenario matched, and the error scenario of the service or the universal error scenario answered. */
    ERROR_SCENARIO_SERVED,

    /** No scenario matched, and no error scenario is designated. The request got the status 404. */
    NO_SCENARIO_MATCHED,

    /** A real service answered, and its answer was relayed. */
    PROXIED,

    /**
     * The request was to be forwarded, and no real service gave a complete answer to it, or there was no URL
     * to forward it to. An error scenario answered in the place of the real service, or the system itself.
     */
    UPSTREAM_FAILED,

    /** No service handles the target of the request, and the request was not forwarded. It got the status 404. */
    NO_SERVICE_MATCHED,
}

/**
 * The record of one request that was handled at the mock endpoint.
 *
 * [time] is when the request arrived, and [clientAddress] is the address that it came from, when that is
 * known. [target] is the target that the request was handled as. [targetBeforeTwisting] is the target as the
 * client wrote it, and it is `null` unless twisting changed the target.
 *
 * [service] is the service that handled the request, and [responseMode] is the mode in which it did. A
 * request that was forwarded without a service has the mode proxy and no service, and a request that no service
 * matched has neither. [scenario] is the scenario that gave the response, which no request has that a real
 * service or the system itself answered.
 *
 * [inspectionMessages] are what the inspectors and the request rules of the service say about the request.
 * [explanation] says why the system answered in the place of a scenario or a real service, such as why a real
 * service gave no answer. [flagged] is a mark that a reader of the history sets and removes.
 */
public data class HistoryEntry(
    public val id: HistoryEntryId,
    public val time: Instant,
    public val clientAddress: String? = null,
    public val request: RecordedRequest,
    public val response: RecordedResponse,
    public val target: String,
    public val targetBeforeTwisting: String? = null,
    public val service: RecordedService? = null,
    public val responseMode: ResponseMode? = null,
    public val scenario: RecordedScenario? = null,
    public val outcome: Outcome,
    public val inspectionMessages: List<String> = emptyList(),
    public val explanation: String? = null,
    public val flagged: Boolean = false,
)

/**
 * A request as a history entry holds it. [url] is the URL that the client addressed, without its query string,
 * and [query] is the query string as the client wrote it. [parameters] are those of the query string and of a
 * URL-encoded form, decoded, and [cookies] are those of the `Cookie` headers.
 */
public data class RecordedRequest(
    public val method: String,
    public val url: String,
    public val query: String? = null,
    public val headers: List<Header> = emptyList(),
    public val parameters: List<Parameter> = emptyList(),
    public val cookies: List<Cookie> = emptyList(),
    public val body: RecordedBody = RecordedBody.EMPTY,
) {
    /** The value of the `Content-Type` header, or `null` for a request without that header. */
    public val contentType: String?
        get() = headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value

    /** What the entry holds of the body, as text, see [RecordedBody.text]. */
    public val bodyText: String by lazy(LazyThreadSafetyMode.PUBLICATION) { body.text(contentType) }
}

/** A response as a history entry holds it: what the client was sent, with its headers in their order. */
public data class RecordedResponse(
    public val status: Int,
    public val headers: List<Header> = emptyList(),
    public val body: RecordedBody = RecordedBody.EMPTY,
) {
    /** The value of the `Content-Type` header, or `null` for a response without that header. */
    public val contentType: String?
        get() = headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value

    /** What the entry holds of the body, as text, see [RecordedBody.text]. */
    public val bodyText: String by lazy(LazyThreadSafetyMode.PUBLICATION) { body.text(contentType) }
}

/** The service that handled a request, as it was then: its identifier, its name, and its tags. */
public data class RecordedService(
    public val id: ServiceId,
    public val name: String,
    public val tags: Tags = Tags.EMPTY,
)

/** The scenario that gave the response to a request, as it was then: its identifier, its name, and its tags. */
public data class RecordedScenario(
    public val id: ScenarioId,
    public val name: String,
    public val tags: Tags = Tags.EMPTY,
)

/**
 * A body as a history entry holds it. An entry holds at most as many bytes of a body as the capture limit of
 * its engine allows: the first ones. [size] is the number of bytes of the whole body, as the client or the
 * real service sent it, so a body that [isCut] is longer than what the entry holds of it.
 *
 * The body takes over the array that it is given. Whoever creates a body must not change that array
 * afterwards.
 */
public class RecordedBody
    @JvmOverloads
    constructor(
        bytes: ByteArray,
        public val size: Long = bytes.size.toLong(),
    ) {
        init {
            require(size >= bytes.size) { "A body is not shorter than what is held of it." }
        }

        private val held: ByteArray = bytes

        /** The bytes that the entry holds of the body, which are all of them unless the body [isCut]. Every read gives a new array. */
        public val bytes: ByteArray
            get() = held.copyOf()

        /** Whether the entry holds only the start of the body, because the body is longer than the capture limit. */
        public val isCut: Boolean
            get() = size > held.size

        /**
         * Returns what the entry holds of the body as text. The bytes are read in the charset that
         * [contentType] declares, which is the value of a `Content-Type` header, and as UTF-8 when it declares
         * none or one that is not known. Bytes that are no text in that charset become the replacement
         * character, as the bytes of a character can that a cut divides.
         */
        public fun text(contentType: String?): String = String(held, charsetOf(contentType) ?: Charsets.UTF_8)

        override fun equals(other: Any?): Boolean = other is RecordedBody && size == other.size && held.contentEquals(other.held)

        override fun hashCode(): Int = 31 * held.contentHashCode() + size.hashCode()

        /** Returns the sizes. The bytes are left out, because they can hold secrets. */
        override fun toString(): String = "RecordedBody(${held.size} of $size bytes)"

        public companion object {
            /** No body. */
            public val EMPTY: RecordedBody = RecordedBody(ByteArray(0))
        }
    }
