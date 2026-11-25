package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.FieldErrorDto
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Returns the body of the request as the wire type of [serializer]. The body has to be a JSON object with
 * every member that the wire type has no default for, and with no member that the wire type has not.
 *
 * @throws AdminProblem with the status 422 when the body is no such object. The problem names every member
 *   that is missing, that the wire type has not, or that has a value of another kind, see [WireType].
 *   Besides, it is thrown as [AdminRequest.jsonObject] says.
 */
internal suspend fun <T> AdminRequest.body(serializer: KSerializer<T>): T {
    val stated = jsonObject()
    WireType.requireValid(stated, serializer.descriptor, complete = true)
    return WireType.read(serializer, stated)
}

/**
 * Returns the body of the request as the wire type of [serializer], see [body], or `null` for a request
 * without a body. An operation whose body can be left out takes its body so.
 *
 * @throws AdminProblem as [body] says, for a request that has a body.
 */
internal suspend fun <T> AdminRequest.bodyOrNull(serializer: KSerializer<T>): T? = if (hasBody) body(serializer) else null

/**
 * Returns the attributes that the body of the request states, which are attributes of the wire type of
 * [serializer]. The body has to be a JSON object whose members are members of that wire type. It can hold
 * any of them, and none.
 *
 * @throws AdminProblem with the status 422 when the body has a member that the wire type has not, or one
 *   whose value is of another kind, see [WireType]. Besides, it is thrown as [AdminRequest.jsonObject] says.
 */
internal suspend fun <T> AdminRequest.attributes(serializer: KSerializer<T>): StatedAttributes<T> {
    val stated = jsonObject()
    WireType.requireValid(stated, serializer.descriptor, complete = false)
    return StatedAttributes(serializer, stated)
}

/**
 * The attributes that a request states of what it creates or updates: some of the members of the wire type
 * [T], with values that such members can have. An attribute that the request does not state is not among
 * them, which is not the same as one that it states as `null`.
 */
internal class StatedAttributes<T>(
    private val serializer: KSerializer<T>,
    private val stated: JsonObject,
) {
    /**
     * Returns [current] with the stated attributes in the place of its own. Every attribute that the request
     * does not state stays as [current] has it. A stated attribute takes the place of the whole attribute:
     * a list is the stated list, and not the list of [current] with something added.
     */
    fun on(current: T): T {
        val held = AdminApiJson.instance.encodeToJsonElement(serializer, current).jsonObject
        return WireType.read(serializer, JsonObject(held + stated))
    }
}

/**
 * Checks a JSON object against a wire type of the admin API, by what the serializer of the wire type says of
 * it, and names every member that is not as the wire type has it. The serialization library would take some
 * of what is refused here, such as a number in quotes, and it reports one fault where there are several.
 *
 * An error names its member by the path to it: `delayMillis`, `headers[1].name`, `matchCriterion.type`. A
 * message says what kind of value the member takes, and nothing of the value that the request has there.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object WireType {
    private const val UNKNOWN = "The request can have no member of this name here."
    private const val MISSING = "The member is missing."
    private const val NULL = "The member must have a value, and null is none."
    private val BOOLEANS = setOf("true", "false")
    private val WHOLE_NUMBER = Regex("-?(0|[1-9][0-9]*)")

    /** The name of the member that says which kind of a wire type with several kinds an object is. */
    private val kindMember: String = AdminApiJson.instance.configuration.classDiscriminator

    /**
     * Returns [checked] as a value of the wire type of [serializer]. [checked] is an object that is as the
     * wire type has it, see [requireValid], so this does not fail. Should it fail all the same, the failure
     * that is thrown says nothing of the object: the failure of the serialization library quotes what it
     * read, the log gets what an operation fails with, and a request can hold a secret.
     *
     * @throws IllegalStateException when the serialization library does not take the object.
     */
    fun <T> read(
        serializer: KSerializer<T>,
        checked: JsonObject,
    ): T =
        try {
            AdminApiJson.instance.decodeFromJsonElement(serializer, checked)
        } catch (_: SerializationException) {
            error("An object that is as the wire type ${serializer.descriptor.serialName} has it was not read as that type.")
        }

    /**
     * Checks [stated] as an object of the wire type that [descriptor] describes. A [complete] object has
     * every member that the wire type has no default for. One that is not can lack any of them.
     *
     * @throws AdminProblem with the status 422 when a member is not as the wire type has it.
     */
    fun requireValid(
        stated: JsonObject,
        descriptor: SerialDescriptor,
        complete: Boolean,
    ) {
        val errors = errorsOfMembers(stated, descriptor, path = "", complete)
        if (errors.isNotEmpty()) throw AdminProblem.invalid(errors)
    }

    /**
     * Returns what is wrong with the members of [stated], which is to be an object of the wire type that
     * [descriptor] describes. [path] is what the name of a member follows in the path to it. The member
     * [besides] is none of the wire type and is there all the same.
     */
    private fun errorsOfMembers(
        stated: JsonObject,
        descriptor: SerialDescriptor,
        path: String,
        complete: Boolean,
        besides: String? = null,
    ): List<FieldErrorDto> {
        val names = List(descriptor.elementsCount, descriptor::getElementName)
        val unknown = stated.keys.filter { it !in names && it != besides }.map { FieldErrorDto(path + it, UNKNOWN) }
        val known =
            names.flatMapIndexed { index, name ->
                val value = stated[name]
                when {
                    value != null -> errorsOfValue(value, descriptor.getElementDescriptor(index), path + name)
                    complete && !descriptor.isElementOptional(index) -> listOf(FieldErrorDto(path + name, MISSING))
                    else -> emptyList()
                }
            }
        return unknown + known
    }

    /** Returns what is wrong with [value] as a value of the kind that [descriptor] describes, which is the member at [path]. */
    private fun errorsOfValue(
        value: JsonElement,
        descriptor: SerialDescriptor,
        path: String,
    ): List<FieldErrorDto> {
        if (value is JsonNull) return if (descriptor.isNullable) emptyList() else listOf(FieldErrorDto(path, NULL))
        return when (descriptor.kind) {
            PrimitiveKind.STRING -> {
                expected(value is JsonPrimitive && value.isString, path, "a text")
            }

            PrimitiveKind.BOOLEAN -> {
                expected(value is JsonPrimitive && !value.isString && value.content in BOOLEANS, path, "true or false")
            }

            PrimitiveKind.INT -> {
                expected(wholeNumber(value)?.let { it >= Int.MIN_VALUE && it <= Int.MAX_VALUE } == true, path, "a whole number")
            }

            PrimitiveKind.LONG -> {
                expected(wholeNumber(value) != null, path, "a whole number")
            }

            SerialKind.ENUM -> {
                val names = List(descriptor.elementsCount, descriptor::getElementName)
                expected(value is JsonPrimitive && value.isString && value.content in names, path, "one of ${names.joinToString()}")
            }

            StructureKind.LIST -> {
                if (value !is JsonArray) return expected(false, path, "a list")
                value.flatMapIndexed { index, item -> errorsOfValue(item, descriptor.getElementDescriptor(0), "$path[$index]") }
            }

            StructureKind.CLASS, StructureKind.OBJECT -> {
                if (value !is JsonObject) return expected(false, path, "an object")
                errorsOfMembers(value, descriptor, "$path.", complete = true)
            }

            PolymorphicKind.SEALED -> {
                errorsOfKinds(value, descriptor, path)
            }

            else -> {
                error("The wire type ${descriptor.serialName} has a part of the kind ${descriptor.kind}, which is not checked.")
            }
        }
    }

    /**
     * Returns what is wrong with [value] as a value of a wire type with several kinds, which [descriptor]
     * describes. Such a value is an object that names its kind in the member [kindMember], and that has the
     * members of that kind besides.
     */
    private fun errorsOfKinds(
        value: JsonElement,
        descriptor: SerialDescriptor,
        path: String,
    ): List<FieldErrorDto> {
        if (value !is JsonObject) return expected(false, path, "an object")
        val kinds = descriptor.getElementDescriptor(1)
        val names = List(kinds.elementsCount, kinds::getElementName)
        val named = (value[kindMember] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val kind = names.indexOf(named)
        if (kind < 0) return expected(false, "$path.$kindMember", "one of ${names.joinToString()}")
        return errorsOfMembers(value, kinds.getElementDescriptor(kind), "$path.", complete = true, besides = kindMember)
    }

    /** Returns the number that [value] is when it is a whole number as JSON writes one, without quotes, and `null` otherwise. */
    private fun wholeNumber(value: JsonElement): Long? =
        (value as? JsonPrimitive)?.takeIf { !it.isString && WHOLE_NUMBER.matches(it.content) }?.content?.toLongOrNull()

    /** Returns no error when the member at [path] [isAsExpected], and otherwise the error that says [what] the member takes. */
    private fun expected(
        isAsExpected: Boolean,
        path: String,
        what: String,
    ): List<FieldErrorDto> = if (isAsExpected) emptyList() else listOf(FieldErrorDto(path, "The value has to be $what."))
}
