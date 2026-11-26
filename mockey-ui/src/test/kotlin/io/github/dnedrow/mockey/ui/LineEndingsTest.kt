package io.github.dnedrow.mockey.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** The line endings that a form restores in an edited text, see [LineEndings]. */
class LineEndingsTest {
    /** The style is a carriage return and a line feed only if every line break of the text has this form. */
    @Test
    fun theStyleOfATextFollowsItsLineBreaks() {
        val withBoth = listOf("a\r\nb", "a\r\nb\r\n", "\r\n", "a\r\n\r\nb")
        val otherwise = listOf("", "one line", "a\nb", "a\rb", "a\r\nb\nc", "a\r\nb\rc", "a\n\rb", "\r\r\n")

        assertEquals(withBoth.map { "crlf" }, withBoth.map(LineEndings::styleOf))
        assertEquals(otherwise.map { "lf" }, otherwise.map(LineEndings::styleOf))
        assertEquals("lf", LineEndings.DEFAULT)
    }

    /** Applying a style to a submitted text restores the line endings of the stored text. */
    @Test
    fun applyingAStyleRestoresTheLineEndings() {
        assertEquals("a\r\nb\r\n\r\nc", LineEndings.apply("crlf", "a\nb\n\nc"))
        assertEquals("a\nb\n\nc", LineEndings.apply("lf", "a\nb\n\nc"))
        assertEquals("a\nb", LineEndings.apply("unknown", "a\nb"))
        assertEquals("a\nb", LineEndings.apply("", "a\nb"))
        for (stored in listOf("a\r\nb\r\n", "a\nb\n", "no line break", "")) {
            val submitted = stored.replace("\r\n", "\n")
            assertEquals(stored, LineEndings.apply(LineEndings.styleOf(stored), submitted))
        }
    }
}
