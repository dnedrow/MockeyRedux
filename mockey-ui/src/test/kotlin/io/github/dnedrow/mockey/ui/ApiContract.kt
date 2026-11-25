package io.github.dnedrow.mockey.ui

import dev.harrel.jsonschema.ValidatorFactory
import dev.harrel.jsonschema.providers.KotlinxJsonNode
import io.github.dnedrow.mockey.api.AdminApiDescription
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.URLDecoder

/**
 * What the admin API takes, as its description says it, see [AdminApiDescription]. The tests of the UI hold
 * every request that the UI sends against it: the server that the UI manages is no part of these tests, and
 * what stands in for it takes whatever a test tells it to take.
 *
 * A request is as the description says when the API has an operation with its method for its path, when
 * the operation takes every parameter of its query string, and when its body is what the operation reads: a
 * JSON object with the members of the wire type, each as the wire type has it, and with no other member.
 */
internal object ApiContract {
    /**
     * An operation of the API: its name in the description, its [method], its path below the prefix of the
     * API with its parameters in braces, the parameters that it takes in a query string, and what the
     * description says of the body of a request.
     */
    class Operation(
        val id: String,
        val method: String,
        val path: String,
        val query: Set<String>,
        private val body: JsonObject?,
    ) {
        private val segments = path.split('/')

        /** How many segments of the path stand for themselves. Of two operations for one path, the one with more of them is meant. */
        val literals: Int = segments.count { !it.startsWith("{") }

        /** Whether a request has to have a body, and whether it can have one. */
        val needsBody: Boolean = body?.get("required") == JsonPrimitive(true)
        val takesBody: Boolean = body != null

        /** Returns the schema of a body of [mediaType], or `null` when the operation takes no such body. */
        fun schemaOf(mediaType: String): JsonElement? {
            val content = body?.getValue("content")?.jsonObject
            return content?.get(mediaType)?.jsonObject?.get("schema")
        }

        /** Returns whether the operation is for [requested], which is a path below the prefix as a client wrote it. */
        fun isFor(requested: String): Boolean {
            val asked = requested.split('/')
            if (asked.size != segments.size) return false
            return asked.indices.all { (segments[it].startsWith("{") && asked[it].isNotEmpty()) || segments[it] == asked[it] }
        }

        override fun toString(): String = "$id ($method $path)"
    }

    private val document: JsonObject = AdminApiDescription.document

    /** The operations of the API. */
    val operations: List<Operation> =
        document.getValue("paths").jsonObject.flatMap { (path, ofPath) ->
            ofPath.jsonObject.map { (method, said) ->
                val says = said.jsonObject
                val parameters = says["parameters"]?.jsonArray.orEmpty().map { it.jsonObject }
                val query = parameters.filter { text(it, "in") == "query" }.map { text(it, "name") }
                Operation(text(says, "operationId"), method.uppercase(), path, query.toSet(), says["requestBody"]?.jsonObject)
            }
        }

    private val validator = ValidatorFactory().withJsonNodeFactory(KotlinxJsonNode.Factory()).createValidator()
    private val closed = mapOf("additionalProperties" to JsonPrimitive(false))
    private val defined = strict(document.getValue("components").jsonObject.getValue("schemas"))
    private var registered = 0

    /**
     * Returns the operation that a request with [method] for [path] asks for, or `null` when the API has
     * none. [path] is the path of the request as the client wrote it, with the prefix of the API.
     */
    fun operationFor(
        method: String,
        path: String,
    ): Operation? {
        if (!path.startsWith(AdminApiDescription.PREFIX + "/")) return null
        val below = path.removePrefix(AdminApiDescription.PREFIX)
        return operations.filter { it.method == method && it.isFor(below) }.maxByOrNull { it.literals }
    }

    /**
     * Returns what the description has against a request for [operation] with the query string [query], as
     * the client wrote it, and with [body], which the request declares as [contentType]. A request that is
     * as the description says gives nothing.
     */
    fun violations(
        operation: Operation,
        query: String?,
        body: ByteArray,
        contentType: String?,
    ): List<String> {
        val parameters = query.orEmpty().split('&').filter { it.isNotEmpty() }
        val names = parameters.map { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) }
        val unknown = names.filter { it !in operation.query }.map { "The operation $operation takes no parameter $it in the query string." }
        return unknown + violationsOfBody(operation, body, contentType?.substringBefore(';')?.trim())
    }

    private fun violationsOfBody(
        operation: Operation,
        body: ByteArray,
        mediaType: String?,
    ): List<String> {
        if (body.isEmpty()) return if (operation.needsBody) listOf("The operation $operation needs a body.") else emptyList()
        if (!operation.takesBody) return listOf("The operation $operation takes no body.")
        val schema = mediaType?.let(operation::schemaOf) ?: return listOf("The operation $operation takes no body of the type $mediaType.")
        if (mediaType != JSON) return emptyList()
        val value =
            try {
                Json.parseToJsonElement(String(body, Charsets.UTF_8))
            } catch (_: SerializationException) {
                return listOf("The body of the request for $operation is no JSON.")
            }
        return violations(schema, value).map { "The body of the request for $operation is not as the description says: $it" }
    }

    /**
     * Returns what [schema], which is a schema of the description, has against [value]. An object is taken
     * strictly: it has no member that its schema does not name.
     */
    private fun violations(
        schema: JsonElement,
        value: JsonElement,
    ): List<String> =
        synchronized(validator) {
            val whole =
                buildJsonObject {
                    put("\$schema", "https://json-schema.org/draft/2020-12/schema")
                    put("\$defs", defined)
                    putJsonArray("allOf") { add(strict(schema)) }
                }
            val standingAlone = Json.parseToJsonElement("$whole".replace("\"#/components/schemas/", "\"#/\$defs/"))
            val uri = validator.registerSchema(URI.create("mockey:/admin-api/${registered++}"), standingAlone)
            validator.validate(uri, value).errors.map { "${it.instanceLocation}: ${it.error}" }
        }

    /** Returns [schema] with every schema of an object in it closed: the object has the members that the schema names, and no other. */
    private fun strict(schema: JsonElement): JsonElement =
        when (schema) {
            is JsonArray -> JsonArray(schema.map(::strict))
            is JsonObject -> JsonObject(schema.mapValues { strict(it.value) } + closed.filter { "properties" in schema })
            else -> schema
        }

    private fun text(
        holder: JsonObject,
        name: String,
    ): String = holder.getValue(name).jsonPrimitive.content

    private const val JSON = "application/json"
}
