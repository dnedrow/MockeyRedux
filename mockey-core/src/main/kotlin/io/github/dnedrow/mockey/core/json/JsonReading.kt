package io.github.dnedrow.mockey.core.json

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What reading a text as JSON gave. */
internal sealed interface JsonReading {
    /** The text is JSON, and [element] is its value. */
    data class Valid(
        val element: JsonElement,
    ) : JsonReading

    /** The text is not JSON. [reason] is a sentence that says what is wrong with it, and where the parser can tell. */
    data class Invalid(
        val reason: String,
    ) : JsonReading
}

/**
 * Reads [text] as JSON. The text must be one JSON value and nothing else, and it must not be nested more deeply
 * than the parser accepts. The parser alone would let a bare word pass as a value, such as a text in single
 * quotes.
 */
internal fun readJson(text: String): JsonReading =
    try {
        val element = Json.parseToJsonElement(text)
        val bareWord = element.firstBareWord()
        if (bareWord == null) JsonReading.Valid(element) else JsonReading.Invalid("$bareWord is not a JSON value.")
    } catch (e: SerializationException) {
        JsonReading.Invalid(sentence(e.message))
    }

/** A JSON number as RFC 8259 writes it. */
internal val JSON_NUMBER: Regex = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")

private val LITERALS = setOf("true", "false", "null")

/** Returns the first value of this JSON that is neither a string, a number, `true`, `false`, nor `null`. */
private fun JsonElement.firstBareWord(): String? =
    when (this) {
        is JsonObject -> values.firstNotNullOfOrNull { it.firstBareWord() }
        is JsonArray -> firstNotNullOfOrNull { it.firstBareWord() }
        is JsonPrimitive -> content.takeUnless { isString || it in LITERALS || JSON_NUMBER.matches(it) }
    }

/**
 * Returns the first line of the [message] of the parser as a sentence. The lines after it repeat the text, and
 * the path at its end is that of the whole text, because the parser follows no path when it reads a value.
 */
private fun sentence(message: String?): String {
    val firstLine = message.orEmpty().substringBefore('\n').trim()
    return if (firstLine.isEmpty()) "The text cannot be parsed." else firstLine.removeSuffix(" at path: $").removeSuffix(".") + "."
}
