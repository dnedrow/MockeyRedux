package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/**
 * One operation of the admin API: a [method], the [path] of the resources that it is for, and what it
 * answers to a request, see [handle].
 *
 * The path is the one below the prefix of the API, without a slash at its ends, such as
 * `services/{serviceId}`. A segment in braces is a parameter: it stands for any one segment that is not
 * empty, and [AdminRequest.parameter] tells the operation what a request has there. Every other segment
 * stands for itself, as it is written here: a client that escapes a character of it writes another path.
 */
internal class AdminRoute(
    val method: HttpMethod,
    val path: String,
    val handle: suspend AdminRequest.() -> AdminResponse,
) {
    private val segments = path.split('/')

    /** The name of the parameter at each segment of the path, and `null` where the segment stands for itself. */
    private val names = segments.map { segment -> segment.removeSurrounding("{", "}").takeIf { it.length < segment.length } }

    /**
     * The path with every parameter as `{}`. Routes with the same form are for the same resources, whatever
     * their parameters are called.
     */
    val form: String = names.indices.joinToString("/") { if (names[it] == null) segments[it] else "{}" }

    /**
     * How much of a path the route names itself: a text that is the greater, the earlier a segment stands
     * for itself. Of two routes that are for the same path, the one with the greater text is the one that the
     * path means, so that `services/newest` is not taken for a service with the identifier `newest`.
     */
    val precedence: String = names.joinToString("") { if (it == null) "1" else "0" }

    init {
        require(segments.none { it.isEmpty() || it == "{}" }) { "The path '$path' of a route has a segment without a name." }
        require(names.filterNotNull().let { it.size == it.distinct().size }) { "The path '$path' of a route names a parameter twice." }
    }

    /**
     * Returns what a request for [resource] has at the parameters of the path, by their names, or `null`
     * when the route is not for that resource. [resource] holds the segments of a path below the prefix of
     * the API as the client wrote them, and so does the result: nothing is decoded.
     */
    fun parametersOf(resource: List<String>): Map<String, String>? {
        if (resource.size != segments.size) return null
        val parameters = mutableMapOf<String, String>()
        for ((index, segment) in resource.withIndex()) {
            val name = names[index]
            when {
                name == null -> if (segment != segments[index]) return null
                segment.isEmpty() -> return null
                else -> parameters[name] = segment
            }
        }
        return parameters
    }

    /** Returns the method and the path, as a message names the operation. Nothing of a request is in it. */
    override fun toString(): String = "${method.value} $path"
}

/**
 * A request to an operation of the admin API, as far as an operation has to know it. The request has
 * presented the admin token when the server has one, and its path and its method are those of the route.
 */
internal class AdminRequest(
    private val call: ApplicationCall,
    private val parameters: Map<String, String>,
    private val maxBody: Long,
) {
    /**
     * The host by which the client reached the listener, as a URL writes it and without a port: a name, an
     * address of IPv4, or an address of IPv6 in brackets. It is the host that the request names in its
     * `Host` header. Where the request names none there, as a request of HTTP/1.0 may, it is the address at
     * which the request arrived.
     */
    val host: String
        get() {
            val named = RequestHost.of(call).orEmpty()
            val host = if (named.startsWith('[')) named.substringBefore(']') + "]" else named.substringBefore(':')
            val arrivedAt = call.request.local.localAddress
            return host.ifEmpty { if (':' in arrivedAt) "[$arrivedAt]" else arrivedAt }
        }

    /** Returns what the path of the request has at the parameter [name] of the route, decoded. It is never empty. */
    fun parameter(name: String): String = parameters.getValue(name)

    /**
     * Returns the body of the request, which has to be one JSON object in UTF-8.
     *
     * The problem of a body that is no such object says what the body is not and nothing of what it is, and
     * the log gets nothing of it: a body can hold a secret, such as the password of the outbound proxy.
     *
     * @throws AdminProblem with the status 413 for a body that is longer than the server takes, and with the
     *   status 400 for a request without a body, and for a body that is no UTF-8 or no JSON object. A body
     *   that is nested more deeply than the parser goes, which is some hundred levels, counts as no object.
     */
    suspend fun jsonObject(): JsonObject {
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(body()))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw AdminProblem(HttpStatusCode.BadRequest, "The body of the request is not encoded as UTF-8.")
            }
        if (text.isBlank()) throw AdminProblem(HttpStatusCode.BadRequest, "The request has no body. The operation takes a JSON object.")
        val json =
            try {
                AdminApiJson.instance.parseToJsonElement(text)
            } catch (_: SerializationException) {
                null
            }
        return (json as? JsonObject)?.takeIf(::isJson)
            ?: throw AdminProblem(HttpStatusCode.BadRequest, "The body of the request is no JSON object.")
    }

    /**
     * Returns whether [element] is JSON throughout. The parser takes any text without quotes for a value,
     * such as `{"name": seven}`, where JSON has only numbers, `true`, `false`, and `null` without quotes.
     */
    private fun isJson(element: JsonElement): Boolean =
        when (element) {
            is JsonObject -> element.values.all(::isJson)
            is JsonArray -> element.all(::isJson)
            is JsonNull -> true
            is JsonPrimitive -> element.isString || element.content in BOOLEANS || NUMBER.matches(element.content)
        }

    /**
     * Returns the bytes of the body. A request that states a length beyond what the server takes is not read
     * at all, and any other is read as far as that limit goes. The body is read from the channel of the
     * request itself, as the mock endpoint reads it: on the way to the channel that Ktor gives a call for its
     * body, Ktor writes the request target with its query string to the log.
     */
    private suspend fun body(): ByteArray {
        val stated =
            call.request.headers[HttpHeaders.ContentLength]
                ?.trim()
                ?.toLongOrNull()
        val bytes =
            if (stated != null && stated > maxBody) {
                null
            } else {
                call.request
                    .receiveChannel()
                    .readBuffer(maxBody + 1)
                    .readByteArray()
            }
        if (bytes == null || bytes.size > maxBody) {
            val explanation = "The body of the request is longer than $maxBody bytes, which is the most that this server takes."
            throw AdminProblem(HttpStatusCode.PayloadTooLarge, explanation)
        }
        return bytes
    }

    private companion object {
        val BOOLEANS = setOf("true", "false")

        /** A number as JSON writes it. */
        val NUMBER = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")
    }
}

/**
 * What an operation of the admin API answers: a [status] and a [body], which is a JSON text unless
 * [contentType] says otherwise. The server sends the body in UTF-8.
 */
internal class AdminResponse(
    val status: HttpStatusCode,
    val body: String,
    val contentType: ContentType = ContentType.Application.Json,
) {
    internal companion object {
        /** Returns the answer with [status] whose body is [value] as JSON, in the form that the wire types of the API give it. */
        inline fun <reified T> json(
            value: T,
            status: HttpStatusCode = HttpStatusCode.OK,
        ): AdminResponse = AdminResponse(status, AdminApiJson.instance.encodeToString(value))
    }
}
