package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.NameValueDto
import kotlin.test.Test
import kotlin.test.assertEquals

/** The lists that a form edits in a multi-line field: headers and request parameters, one per line. */
class LinesTest {
    /** Headers are written one per line, and they're read back the same way. A blank line is skipped. */
    @Test
    fun headersAreReadFromTheLinesThatTheyAreWrittenTo() {
        val cookies = listOf(HeaderDto("Set-Cookie", "a=1"), HeaderDto("Set-Cookie", "b=2"))
        val headers = listOf(HeaderDto("Content-Type", "text/plain; charset=utf-8")) + cookies

        val text = HeaderLines.textOf(headers)
        val read = HeaderLines.read("$text\n\n  \n X-Padded :  value with: a colon  \nX-Empty:", "headers")

        assertEquals("Content-Type: text/plain; charset=utf-8\nSet-Cookie: a=1\nSet-Cookie: b=2", text)
        assertEquals(headers + HeaderDto("X-Padded", "value with: a colon") + HeaderDto("X-Empty", ""), read.headers)
        assertEquals(emptyList(), read.errors)
        assertEquals("", HeaderLines.textOf(emptyList()))
        assertEquals(emptyList(), HeaderLines.read("", "headers").headers)
    }

    /** A line without a name in front of a colon isn't a header. Its message says which line it is and names the field. */
    @Test
    fun aLineWithoutANameIsNotAHeader() {
        val read = HeaderLines.read("Accept: */*\nnonsense\n\n: no name\n  : \nX: y", "requestHeaders")

        val format = "A header has the form Name: value, for example Content-Type: text/plain."
        assertEquals(listOf(HeaderDto("Accept", "*/*"), HeaderDto("X", "y")), read.headers)
        assertEquals(listOf(2, 4, 5).map { "Line $it is not a header. $format" }, read.errors.map { it.message })
        assertEquals(setOf("requestHeaders"), read.errors.map { it.field }.toSet())
    }

    /** Parameters are written one per line, exactly as they are, and they're read back the same way. */
    @Test
    fun parametersAreReadFromTheLinesThatTheyAreWrittenTo() {
        val plain = listOf("q" to "a b", "empty" to "", "" to "odd", " padded " to " value = with = signs ")
        val pairs = plain + listOf("é" to "世界", "same" to "1", "same" to "2")
        val parameters = pairs.map { (name, value) -> NameValueDto(name, value) }

        val text = ParameterLines.textOf(parameters)

        assertEquals("q=a b\nempty=\n=odd\n padded = value = with = signs \né=世界\nsame=1\nsame=2", text)
        assertEquals(parameters, ParameterLines.read(text!!))
        assertEquals(listOf(NameValueDto("flag", ""), NameValueDto("x", "1")), ParameterLines.read("flag\n\n   \nx=1\n"))
        assertEquals("", ParameterLines.textOf(emptyList()))
        assertEquals(emptyList(), ParameterLines.read(""))
    }

    /** A parameter that wouldn't be read back the same way can't be written as a line. */
    @Test
    fun aParameterThatBreaksALineCannotBeWritten() {
        val unreadable = listOf("a" to "x\ny", "a" to "x\ry", "a\nb" to "x", "a\rb" to "x", "a=b" to "x")

        for ((name, value) in unreadable) {
            assertEquals(null, ParameterLines.textOf(listOf(NameValueDto("ok", "1"), NameValueDto(name, value))), "$name=$value")
        }
        assertEquals("a=x=y", ParameterLines.textOf(listOf(NameValueDto("a", "x=y"))))
    }
}
