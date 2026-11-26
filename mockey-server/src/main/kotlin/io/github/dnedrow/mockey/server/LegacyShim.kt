package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.request.Parameter
import io.github.dnedrow.mockey.core.storage.StorageException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * The legacy shim: the automation endpoints of Mockey 2.x on the mock listener, for the scripts that were
 * written for them. A server has the shim only when it's started with the setting `--legacy-shim`. Without
 * the shim, the paths of the endpoints get the status 404, like every path that the mock listener doesn't
 * serve.
 *
 * An endpoint takes a request as Mockey 2.x did: with any method, and with its parameters in the query
 * string or in the fields of a URL-encoded form, under the names that Mockey 2.x gave them. Where Mockey 2.x
 * identified a service, a scenario, or a plan by a number, the shim takes and states the identifiers of this
 * server.
 *
 * Every answer of an endpoint is a JSON document with the status 200, whatever came of the request,
 * because that's what the scripts read. A cache must not store an answer, and no header of an answer lets
 * a page of another origin read it.
 *
 * The shim asks for no admin token: Mockey 2.x had none, and neither has the mock listener. So whoever
 * reaches the mock listener of a server with the shim can read and change its definitions.
 *
 * [maxRequestBody] is the number of bytes that the body of a request may have, and [endpoints] are the
 * endpoints by their paths.
 */
internal class LegacyShim(
    private val maxRequestBody: Long,
    private val endpoints: Map<String, ShimEndpoint>,
) {
    /** Creates the shim of a server with [engine], which takes a body of [maxRequestBody] bytes at most. */
    constructor(engine: MockeyEngine, maxRequestBody: Long) : this(maxRequestBody, endpointsOf(engine))

    /** Answers the request of [call] when it's for an endpoint of the shim, and returns whether it is. */
    suspend fun handle(call: ApplicationCall): Boolean {
        val target = RequestTarget.parse(call.request.local.uri)
        val endpoint = endpoints[target.path] ?: return false
        // The answer of a moment ago says nothing about now.
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val body = bodyOf(call, maxRequestBody)
        if (body == null) {
            call.respond(Refusal.content(HttpStatusCode.PayloadTooLarge, tooLarge()))
            return true
        }
        val document =
            try {
                endpoint.answer(parametersOf(call, target, body))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: StorageException) {
                // What the storage says is about the definitions location, and it is for whoever looks after it.
                log.error("The request for {} of the legacy shim failed at the definitions location.", target.path, failure)
                ShimResult.fail(NOT_STORED)
            } catch (failure: Exception) {
                // The query string is left out, because it can hold a secret.
                log.error("The request for {} of the legacy shim failed.", target.path, failure)
                call.respond(Refusal.content(HttpStatusCode.InternalServerError, FAILED))
                return true
            }
        call.respond(TextContent(document.toString(), ContentType.Application.Json, HttpStatusCode.OK))
        return true
    }

    /**
     * Returns the parameters of the request of [call] for [target], whose body is [body]. The engine reads
     * the parameters of a mock request by the same rules, so the shim lets a mock request read them.
     */
    private fun parametersOf(
        call: ApplicationCall,
        target: RequestTarget,
        body: ByteArray,
    ): ShimParameters {
        val contentType = call.request.headers[HttpHeaders.ContentType]
        val headers = listOfNotNull(contentType?.let { Header(HttpHeaders.ContentType, it) })
        val request = MockRequest(call.request.local.method.value, ANY_URL, target.query, headers, body)
        return ShimParameters(request.parameters)
    }

    private fun tooLarge(): String =
        "The body of the request is longer than $maxRequestBody bytes, which is the most that this server takes."

    internal companion object {
        /** Returns the endpoints of the shim for [engine], by their paths. */
        fun endpointsOf(engine: MockeyEngine): Map<String, ShimEndpoint> =
            mapOf(ShimDefinitions.PATH to ShimDefinitions(engine), ShimServices.PATH to ShimServices(engine))

        private val log = LoggerFactory.getLogger(LegacyShim::class.java)

        /** A URL for the request that reads the parameters. Nothing of it counts. */
        private const val ANY_URL = "http://localhost/"

        private const val FAILED = "The server failed to handle the request. Its log says why."
        private const val NOT_STORED =
            "The server could not write its definitions location, and nothing was changed. The log of the server says why."
    }
}

/** An endpoint of the legacy shim. */
internal fun interface ShimEndpoint {
    /** Returns the document that answers a request with [parameters]. */
    suspend fun answer(parameters: ShimParameters): JsonObject
}

/**
 * The result envelope of Mockey 2.x, with which the endpoints of the shim that change something answer: a
 * JSON object with the member `result`. That member has the member `success` when the request did what it
 * asked for, and the member `fail` when it did not. Either one is a text for a person to read.
 */
internal object ShimResult {
    /** Returns the answer to a request that succeeded: it says so in [text], and it states what [state] puts. */
    fun success(
        text: String,
        state: JsonObjectBuilder.() -> Unit = {},
    ): JsonObject =
        buildJsonObject {
            putJsonObject(RESULT) {
                put("success", text)
                state()
            }
        }

    /** Returns the answer to a request that did not succeed. [text] says why. */
    fun fail(text: String): JsonObject = buildJsonObject { putJsonObject(RESULT) { put("fail", text) } }

    private const val RESULT = "result"
}

/**
 * The parameters of a request to the legacy shim, as Mockey 2.x read them: those of the query string, and
 * after them the fields of the body when the request says that its body is a URL-encoded form. Names and
 * values are decoded. A name counts in the letter case in which it's written.
 */
internal class ShimParameters(
    private val parameters: List<Parameter>,
) {
    /** Returns the first value of the parameter [name], or `null` when the request has no such parameter. */
    operator fun get(name: String): String? = parameters.firstOrNull { it.name == name }?.value

    /** Returns every value of the parameter [name], in the order of the request. */
    fun all(name: String): List<String> = parameters.filter { it.name == name }.map { it.value }
}

/**
 * Returns the service that [parameters] name, or `null` when they name none that is there. The parameter
 * `serviceId` names a service by its identifier. When it names none, the parameter `serviceName` names one
 * by its name, as Mockey 2.x compared names: without regard to letter case and to the spaces around a name.
 * Of several services with the name, it's the one that was created first.
 */
internal fun Definitions.serviceNamedBy(parameters: ShimParameters): Service? {
    val byIdentifier = parameters[SERVICE_ID]?.let { id -> services.firstOrNull { it.id.value == id } }
    return byIdentifier ?: parameters[SERVICE_NAME]?.let { name -> services.firstOrNull { sameName(it.name, name) } }
}

/** Returns whether [one] and [other] are the same name for Mockey 2.x: without regard to letter case and to the spaces around them. */
internal fun sameName(
    one: String,
    other: String,
): Boolean = one.trim().equals(other.trim(), ignoreCase = true)

/** The parameter that names a service by its identifier, and the member of an answer that states the identifier. */
internal const val SERVICE_ID = "serviceId"

/** The parameter that names a service by its name, and the member of an answer that states the name. */
internal const val SERVICE_NAME = "serviceName"
