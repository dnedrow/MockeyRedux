package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.NameValueDto

/*
 * Lists that a form edits in a multi-line field, with one item per line. A page without scripting can't add
 * rows to a form, so a field of lines is the simplest way to edit a list of any length.
 */

/** Headers as a form field holds them: one header per line, in the form `Name: value`. */
internal object HeaderLines {
    private const val FORMAT = "A header has the form Name: value, for example Content-Type: text/plain."

    /** The headers that a field holds, along with a message for every line that isn't a header. */
    class Read(
        val headers: List<HeaderDto>,
        val errors: List<FieldErrorDto>,
    )

    /** Returns [headers] as the lines of a form field. */
    fun textOf(headers: List<HeaderDto>): String = headers.joinToString("\n") { "${it.name}: ${it.value}" }

    /**
     * Reads the headers from [text], whose lines end with a line feed. A blank line is skipped. A line
     * without a name in front of a colon isn't a header, and it gets a message for the field [apiField] that
     * says which line it is.
     */
    fun read(
        text: String,
        apiField: String,
    ): Read {
        val lines = text.split('\n').withIndex().filter { it.value.isNotBlank() }
        val invalid = lines.filter { it.value.substringBefore(':', "").isBlank() }
        val errors = invalid.map { FieldErrorDto(apiField, "Line ${it.index + 1} is not a header. $FORMAT") }
        return Read((lines - invalid.toSet()).map { headerOf(it.value) }, errors)
    }

    /** Returns the header in [line]: the name is everything before the first colon, and the value is the rest. */
    private fun headerOf(line: String): HeaderDto = HeaderDto(line.substringBefore(':').trim(), line.substringAfter(':').trim())
}

/** Request parameters as a form field holds them: one parameter per line, in the form `name=value`. */
internal object ParameterLines {
    /**
     * Returns [parameters] as the lines of a form field, or `null` if the lines couldn't be read back the
     * same way. That's the case for a name or a value with a line break in it, and for a name with an equals
     * sign in it.
     */
    fun textOf(parameters: List<NameValueDto>): String? {
        val unreadable = parameters.any { hasLineBreak(it.name) || hasLineBreak(it.value) || '=' in it.name }
        return if (unreadable) null else parameters.joinToString("\n") { "${it.name}=${it.value}" }
    }

    /**
     * Reads the parameters from [text], whose lines end with a line feed. A blank line is skipped. The name
     * is everything before the first equals sign, and the value is the rest, exactly as typed. A line
     * without an equals sign is a name with an empty value.
     */
    fun read(text: String): List<NameValueDto> =
        text.split('\n').filter { it.isNotBlank() }.map { NameValueDto(it.substringBefore('='), it.substringAfter('=', "")) }

    private fun hasLineBreak(text: String): Boolean = '\n' in text || '\r' in text
}
