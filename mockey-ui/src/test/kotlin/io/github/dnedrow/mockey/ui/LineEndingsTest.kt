package io.github.dnedrow.mockey.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Checks how a form detects and restores the line endings of an edited text (see [LineEndings]). */
class LineEndingsTest {
    /** The style is CRLF only if every line break in the text is a CRLF. Everything else counts as plain line feeds. */
    @Test
    fun theStyleOfATextFollowsItsLineBreaks() {
        val withBoth = listOf("a\r\nb", "a\r\nb\r\n", "\r\n", "a\r\n\r\nb")
        val otherwise = listOf("", "one line", "a\nb", "a\rb", "a\r\nb\nc", "a\r\nb\rc", "a\n\rb", "\r\r\n")

        assertEquals(withBoth.map { "crlf" }, withBoth.map(LineEndings::styleOf))
        assertEquals(otherwise.map { "lf" }, otherwise.map(LineEndings::styleOf))
        assertEquals("lf", LineEndings.DEFAULT)
    }

    /** Applying the stored style to a submitted text brings back the line endings of the stored text. */
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
