package io.github.dnedrow.mockey.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How a form says what its page gave its fields, see [Shown]: the mark of a value is the same for the same
 * value, however its lines end, and another one for every other value.
 */
class ShownTest {
    @Test
    fun theMarkOfAValueIsTheSameHoweverItsLinesEnd() {
        val written = listOf("one\ntwo\nthree", "one\r\ntwo\r\nthree", "one\rtwo\rthree", "one\r\ntwo\nthree")

        assertEquals(1, written.map { Shown.markOf(listOf(it)) }.toSet().size)
        assertTrue(Regex("[A-Za-z0-9_-]{16}").matches(Shown.markOf(listOf("one"))))
    }

    @Test
    fun everyOtherValueHasAnotherMark() {
        val values =
            listOf(
                emptyList(),
                listOf(""),
                listOf("", ""),
                listOf("abc"),
                listOf("ab", "c"),
                listOf("a", "bc"),
                listOf("c", "ab"),
                listOf("abc "),
                listOf("ABC"),
                listOf("one\ntwo"),
                listOf("one\n\ntwo"),
                listOf("one two"),
                listOf("1:a"),
                listOf("1", "a"),
            )

        assertEquals(values.size, values.map(Shown::markOf).toSet().size)
    }

    /** A field counts as changed when it holds another value than its mark says, and when the form has no mark of it. */
    @Test
    fun aFieldIsChangedWhenItHoldsAnotherValueThanItsMarkSays() {
        val state = FormState(mapOf("name" to listOf("Billing"), "row" to listOf("a", "b"), "text" to listOf("one\ntwo")))
        val marks = listOf("name", "row", "text", "box").associate { Shown.PREFIX + it to listOf(state.shownMark(it)) }
        val asShown = FormInput(marks + mapOf("name" to listOf("Billing"), "row" to listOf("a", "b"), "text" to listOf("one\r\ntwo")))
        val otherwise = mapOf("name" to listOf("billing"), "row" to listOf("b", "a"), "text" to listOf("one two"), "box" to listOf("on"))
        val changed = FormInput(marks + otherwise)
        val again = FormState(changed.values)

        assertEquals(listOf(false, false, false, false), listOf("name", "row", "text", "box").map { asShown.isChanged(it) })
        assertEquals(listOf(true, true, true, true), listOf("name", "row", "text", "box").map { changed.isChanged(it) })
        assertFalse(asShown.isChanged("name", "row"))
        assertTrue(FormInput(marks + mapOf("name" to listOf("Billing"), "row" to listOf("a"))).isChanged("name", "row"))
        assertTrue(FormInput(mapOf("name" to listOf("Billing"))).isChanged("name"))
        assertEquals(listOf("name", "row", "text", "box").map(state::shownMark), listOf("name", "row", "text", "box").map(again::shownMark))
    }
}
