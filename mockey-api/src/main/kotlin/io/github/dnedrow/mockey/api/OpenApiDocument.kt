package io.github.dnedrow.mockey.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The description of the admin API as a document of OpenAPI 3.1, which is a JSON object.
 *
 * The document has a path for every resource of the API and under it an operation for every method, with
 * what the description says of that operation, see [DescribedOperation]. It names the prefix of the API
 * as its server, so its paths are the paths of the routes with a slash before each.
 *
 * With which statuses an operation reports a failure follows from its form:
 *
 * - Every operation reports a request that is not written as it takes one with the status 400, a request
 *   without the admin token with the status 401, and a failure of the server with the status 500.
 * - An operation with a method other than `GET` reports the request of a web page with the status 403.
 * - An operation whose path has a parameter reports what is not there with the status 404.
 * - An operation that takes a body reports a body that is too long with the status 413, one that the
 *   request does not declare as what it is with the status 415, and one that is not valid with the
 *   status 422.
 *
 * The description of an operation names the statuses that do not follow from its form, see
 * [DescribedOperation.problems]. Every such answer is a problem document, and the document describes each
 * of them once, among its components.
 */
internal object OpenApiDocument {
    /** The version of OpenAPI that the document is written in. A schema of that version is JSON Schema of the dialect 2020-12. */
    const val VERSION = "3.1.0"

    private const val SCHEMAS = "#/components/schemas/"
    private const val RESPONSES = "#/components/responses/"
    private const val REFERENCE = "\$ref"
    private const val TOKEN = "adminToken"
    private const val PROBLEM_DOCUMENT = "application/problem+json"

    private const val ABOUT =
        "The admin API of a Mockey server states and changes what the server holds: the services with their scenarios, " +
            "the plans, the twist configurations, the tag filter, the tags, the outbound proxy, the history, and the " +
            "definitions as a whole.\n\n" +
            "A body is JSON in UTF-8, and a request with a body says so with the header `Content-Type: application/json`. " +
            "A request body has no member that its schema does not name. " +
            "An operation with the method GET takes the method HEAD as well, and answers it without a body. " +
            "An answer that reports a failure is a problem document.\n\n" +
            "A change takes effect before its request is answered."

    private const val ABOUT_THE_TOKEN =
        "The admin token of the server, which a request presents as a bearer token. " +
            "A server without an admin token takes a request without one."

    /** The answers that report a failure, by their statuses: the name of each among the components, and what it says. */
    private val problems: Map<Int, Pair<String, String>> =
        linkedMapOf(
            Status.BAD_REQUEST to
                (
                    "BadRequest" to
                        "The request is not written as the operation takes one. Its path or its query string has a malformed " +
                        "escape, its query string has a parameter or a value that the operation does not take, or its body is " +
                        "missing or is no JSON object."
                ),
            Status.UNAUTHORIZED to ("Unauthorized" to "The server has an admin token, and the request does not present it."),
            Status.FORBIDDEN to
                (
                    "Forbidden" to
                        "The request carries an Origin header, as the request of a web page does. " +
                        "The API takes a request that can change something only from a program."
                ),
            Status.NOT_FOUND to ("NotFound" to "What the request names is not there."),
            Status.PAYLOAD_TOO_LARGE to ("PayloadTooLarge" to "The body of the request is longer than the server takes."),
            Status.UNSUPPORTED_MEDIA_TYPE to
                ("UnsupportedMediaType" to "The request does not declare its body as what the operation takes."),
            Status.UNPROCESSABLE to
                ("Unprocessable" to "The request is not valid. The problem names every invalid member with a message."),
            Status.SERVER_ERROR to
                (
                    "ServerError" to
                        "The server failed to handle the request. The problem says why when the definitions location could " +
                        "not be read or written."
                ),
        )

    /** Returns the document that describes the operations of [areas], in their order. */
    fun of(areas: List<DescribedArea>): JsonObject {
        val schemas = WireSchemas(SCHEMAS)
        val paths = pathsOf(areas, schemas)
        val responses = problems.values.associate { (name, says) -> name to problemOf(name, says, schemas) }
        return buildJsonObject {
            put("openapi", VERSION)
            putJsonObject("info") {
                put("title", "Mockey admin API")
                put("version", AdminApiDescription.PREFIX.substringAfterLast("/v"))
                put("description", ABOUT)
                putJsonObject("license") {
                    put("name", "GNU General Public License v2.0 or later")
                    put("identifier", "GPL-2.0-or-later")
                }
            }
            putJsonArray("servers") {
                addJsonObject {
                    put("url", AdminApiDescription.PREFIX)
                    put("description", "The admin listener of the server that gave this document.")
                }
            }
            putJsonArray("security") {
                addJsonObject { }
                addJsonObject { putJsonArray(TOKEN) { } }
            }
            putJsonArray("tags") {
                for (area in areas) {
                    addJsonObject {
                        put("name", area.name)
                        put("description", area.description)
                    }
                }
            }
            put("paths", paths)
            putJsonObject("components") {
                putJsonObject("securitySchemes") {
                    putJsonObject(TOKEN) {
                        put("type", "http")
                        put("scheme", "bearer")
                        put("description", ABOUT_THE_TOKEN)
                    }
                }
                put("responses", JsonObject(responses))
                put("schemas", JsonObject(schemas.components))
            }
        }
    }

    /** Returns the paths of the operations of [areas], each with its operations by their methods. */
    private fun pathsOf(
        areas: List<DescribedArea>,
        schemas: WireSchemas,
    ): JsonObject {
        val paths = linkedMapOf<String, MutableMap<String, JsonElement>>()
        for (area in areas) {
            for (operation in area.operations) {
                val ofPath = paths.getOrPut("/${operation.path}") { linkedMapOf() }
                val before = ofPath.put(operation.method.lowercase(), operationOf(area, operation, schemas))
                check(before == null) { "The description of the admin API has the operation $operation twice." }
            }
        }
        return JsonObject(paths.mapValues { (_, operations) -> JsonObject(operations) })
    }

    private fun operationOf(
        area: DescribedArea,
        operation: DescribedOperation,
        schemas: WireSchemas,
    ): JsonObject {
        val parameters = operation.pathParameters.map(::pathParameter) + operation.query.map { parameterOf(it, "query") }
        return buildJsonObject {
            putJsonArray("tags") { add(area.name) }
            put("operationId", operation.id)
            put("summary", operation.summary)
            operation.details?.let { put("description", it) }
            if (parameters.isNotEmpty()) put("parameters", JsonArray(parameters))
            operation.body?.let { body ->
                putJsonObject("requestBody") {
                    put("required", body.isRequired)
                    put("content", contentOf(body.content, schemas))
                }
            }
            putJsonObject("responses") {
                for (answer in operation.answers) put("${answer.status}", answerOf(answer, schemas))
                for (status in problemsOf(operation)) {
                    putJsonObject("$status") { put(REFERENCE, RESPONSES + problems.getValue(status).first) }
                }
            }
        }
    }

    /** Returns the statuses with which [operation] reports a failure, in their order, as the description of this object says. */
    private fun problemsOf(operation: DescribedOperation): List<Int> {
        val ofEvery = listOf(Status.BAD_REQUEST, Status.UNAUTHORIZED, Status.SERVER_ERROR)
        val ofAChange = listOf(Status.FORBIDDEN).filter { operation.method != "GET" }
        val ofAResource = listOf(Status.NOT_FOUND).filter { operation.pathParameters.isNotEmpty() }
        val ofAnyBody = listOf(Status.PAYLOAD_TOO_LARGE, Status.UNSUPPORTED_MEDIA_TYPE, Status.UNPROCESSABLE)
        val ofABody = ofAnyBody.filter { operation.body != null }
        return (ofEvery + ofAChange + ofAResource + ofABody + operation.problems).distinct().sorted()
    }

    /** Returns the parameter [name] of a path, which every request has. */
    private fun pathParameter(name: String): JsonObject {
        val described =
            AdminApiDescription.pathParameters[name]
                ?: error("The description of the admin API says nothing of the path parameter $name.")
        return parameterOf(described, "path")
    }

    private fun parameterOf(
        parameter: DescribedParameter,
        place: String,
    ): JsonObject =
        buildJsonObject {
            put("name", parameter.name)
            put("in", place)
            put("description", parameter.description)
            if (parameter.isRequired) put("required", true)
            put("schema", parameter.schema)
        }

    private fun answerOf(
        answer: DescribedAnswer,
        schemas: WireSchemas,
    ): JsonObject =
        buildJsonObject {
            put("description", answer.description)
            if (answer.isLocated) {
                putJsonObject("headers") {
                    putJsonObject("Location") {
                        put("description", "The path of the resource that the operation created.")
                        putJsonObject("schema") { put("type", "string") }
                    }
                }
            }
            if (answer.content.isNotEmpty()) put("content", contentOf(answer.content, schemas))
        }

    /**
     * Returns what a body is in each of its media types. A body without a wire type is a document that the
     * description does not take apart: any JSON object, or a text.
     */
    private fun contentOf(
        content: List<DescribedContent>,
        schemas: WireSchemas,
    ): JsonObject =
        buildJsonObject {
            for (body in content) {
                val schema =
                    body.wireType?.let { schemas.of(it.descriptor) }
                        ?: buildJsonObject { put("type", if (body.mediaType == DescribedContent.JSON) "object" else "string") }
                putJsonObject(body.mediaType) { put("schema", schema) }
            }
        }

    /** Returns the answer that reports a failure and that [says] what failed. It is a problem document. */
    private fun problemOf(
        name: String,
        says: String,
        schemas: WireSchemas,
    ): JsonObject =
        buildJsonObject {
            put("description", says)
            if (name == problems.getValue(Status.UNAUTHORIZED).first) {
                putJsonObject("headers") {
                    putJsonObject("WWW-Authenticate") {
                        put("description", "The scheme by which a request presents the admin token.")
                        putJsonObject("schema") { put("type", "string") }
                    }
                }
            }
            putJsonObject("content") {
                putJsonObject(PROBLEM_DOCUMENT) { put("schema", schemas.of(ProblemDto.serializer().descriptor)) }
            }
        }
}

/**
 * The schemas of wire types of the admin API, as JSON Schema of the dialect that OpenAPI 3.1 has. A schema
 * is made from what the serializer of its wire type says, so it states what the API reads and writes: the
 * members of an object with what each of them is, the members that a request has to state, and the
 * members that can be `null`.
 *
 * A wire type that is an object, one of several words, or one of several kinds has a schema with a name,
 * which is one of [components]: the name of the wire type without its ending `Dto`. What holds a value of
 * such a wire type refers to that schema, with [prefix] before the name.
 *
 * A wire type with several kinds is an object that names its kind in one member and has the members of
 * that kind besides. Each kind has a schema with a name as well: the name of the wire type, followed by the
 * word for the kind.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class WireSchemas(
    private val prefix: String,
) {
    private val named = sortedMapOf<String, JsonObject>()

    /** The wire type of each schema with a name, as its serializer names it. */
    private val wireTypes = mutableMapOf<String, String>()

    /** The name of the member that says which kind of a wire type with several kinds an object is. */
    private val kindMember: String = AdminApiJson.instance.configuration.classDiscriminator

    /** The schemas with a name of the wire types that were asked for, and of the wire types that those hold, by their names. */
    val components: Map<String, JsonObject>
        get() = named

    /** Returns the schema of a value that [descriptor] describes. */
    fun of(descriptor: SerialDescriptor): JsonObject {
        val schema =
            when (descriptor.kind) {
                PrimitiveKind.STRING -> {
                    typed("string")
                }

                PrimitiveKind.BOOLEAN -> {
                    typed("boolean")
                }

                PrimitiveKind.INT -> {
                    typed("integer", "int32")
                }

                PrimitiveKind.LONG -> {
                    typed("integer", "int64")
                }

                StructureKind.LIST -> {
                    buildJsonObject {
                        put("type", "array")
                        put("items", of(descriptor.getElementDescriptor(0)))
                    }
                }

                SerialKind.ENUM -> {
                    referenceTo(nameOf(descriptor), descriptor) { words(List(descriptor.elementsCount, descriptor::getElementName)) }
                }

                StructureKind.CLASS, StructureKind.OBJECT -> {
                    referenceTo(nameOf(descriptor), descriptor) { objectOf(descriptor, kind = null) }
                }

                PolymorphicKind.SEALED -> {
                    referenceTo(nameOf(descriptor), descriptor) { kindsOf(descriptor) }
                }

                else -> {
                    error("The wire type ${descriptor.serialName} has a part of the kind ${descriptor.kind}, which has no schema.")
                }
            }
        return if (descriptor.isNullable) orNull(schema) else schema
    }

    /** Returns the name of the schema of the wire type that [descriptor] describes. */
    private fun nameOf(descriptor: SerialDescriptor): String =
        descriptor.serialName
            .removeSuffix("?")
            .substringAfterLast('.')
            .removeSuffix("Dto")

    /**
     * Returns a reference to the schema with [name], which is the one of the wire type that [descriptor]
     * describes. [schema] makes that schema when it is not there yet.
     */
    private fun referenceTo(
        name: String,
        descriptor: SerialDescriptor,
        schema: () -> JsonObject,
    ): JsonObject {
        val wireType = descriptor.serialName.removeSuffix("?")
        val known = wireTypes.putIfAbsent(name, wireType)
        check(known == null || known == wireType) { "The wire types $known and $wireType have one name for their schemas: $name." }
        if (known == null) named[name] = schema()
        return buildJsonObject { put("\$ref", prefix + name) }
    }

    /**
     * Returns the schema of an object with the members that [descriptor] describes. An object of the kind
     * [kind] names that kind besides, in the member [kindMember].
     */
    private fun objectOf(
        descriptor: SerialDescriptor,
        kind: String?,
    ): JsonObject {
        val indices = List(descriptor.elementsCount) { it }
        val ofTheKind = listOfNotNull(kind?.let { kindMember to words(listOf(it)) })
        val members = indices.map { descriptor.getElementName(it) to of(descriptor.getElementDescriptor(it)) }
        val required = ofTheKind.map { it.first } + indices.filterNot(descriptor::isElementOptional).map(descriptor::getElementName)
        return buildJsonObject {
            put("type", "object")
            put("properties", JsonObject((ofTheKind + members).toMap()))
            if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
        }
    }

    /**
     * Returns the schema of a wire type with several kinds, which [descriptor] describes: a value is as the
     * schema of one of the kinds says, and the member [kindMember] says of which.
     */
    private fun kindsOf(descriptor: SerialDescriptor): JsonObject {
        val kinds = descriptor.getElementDescriptor(1)
        val references =
            List(kinds.elementsCount) { index ->
                val kind = kinds.getElementName(index)
                val ofKind = kinds.getElementDescriptor(index)
                kind to referenceTo(nameOf(descriptor) + kind.replaceFirstChar(Char::uppercaseChar), ofKind) { objectOf(ofKind, kind) }
            }
        return buildJsonObject {
            putJsonArray("oneOf") { references.forEach { (_, reference) -> add(reference) } }
            putJsonObject("discriminator") {
                put("propertyName", kindMember)
                putJsonObject("mapping") { references.forEach { (kind, reference) -> put(kind, reference.getValue("\$ref")) } }
            }
        }
    }

    /** Returns the schema of a value that is as [schema] says, or `null`. */
    private fun orNull(schema: JsonObject): JsonObject {
        val type = schema["type"]
        if (type != null) return JsonObject(schema + ("type" to JsonArray(listOf(type, JsonPrimitive("null")))))
        return buildJsonObject {
            putJsonArray("anyOf") {
                add(schema)
                addJsonObject { put("type", "null") }
            }
        }
    }

    private fun typed(
        type: String,
        format: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("type", type)
            format?.let { put("format", it) }
        }

    /** Returns the schema of a text that is one of [words]. */
    private fun words(words: List<String>): JsonObject =
        buildJsonObject {
            put("type", "string")
            putJsonArray("enum") { words.forEach { add(it) } }
        }
}
