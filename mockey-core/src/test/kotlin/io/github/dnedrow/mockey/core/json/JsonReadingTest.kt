package io.github.dnedrow.mockey.core.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JsonReadingTest {
    private fun reason(text: String): String = assertIs<JsonReading.Invalid>(readJson(text), text).reason

    @Test
    fun aJsonTextIsReadAsItsValue() {
        val read = assertIs<JsonReading.Valid>(readJson(""" {"a": [1, -2.5e3, "x", true, false, null], "b": {}} """))

        val members = assertIs<JsonObject>(read.element)
        assertEquals(setOf("a", "b"), members.keys)
        val values = assertIs<JsonArray>(members["a"]).map { assertIs<JsonPrimitive>(it).content }
        assertEquals(listOf("1", "-2.5e3", "x", "true", "false", "null"), values)
        assertEquals(JsonObject(emptyMap()), members["b"])
    }

    @Test
    fun aValueOfAnyKindIsJson() {
        listOf("{}", "[]", "\"text\"", "7", "-0.5", "1E+2", "true", "false", "null").forEach {
            assertIs<JsonReading.Valid>(readJson(it), it)
        }
    }

    @Test
    fun aBareWordIsNoJsonValue() {
        assertEquals("abc is not a JSON value.", reason("""{"a": abc}"""))
        assertEquals("'b' is not a JSON value.", reason("""{"a": ["x", 'b']}"""))
        assertEquals("NaN is not a JSON value.", reason("NaN"))
        assertEquals("01 is not a JSON value.", reason("[01]"))
    }

    @Test
    fun aTextThatTheParserRejectsIsNoJson() {
        val texts = listOf("", "  ", """{"a": 1""", """{"a": 1,}""", """{'a': 1}""", """{a: 1}""", """{"a": 1} x""", """{"a": 1 // no}""")

        texts.forEach { text ->
            val reason = reason(text)
            assertTrue(reason.endsWith("."), "$text: $reason")
            assertFalse('\n' in reason, "$text: $reason")
            assertFalse(reason.endsWith("$."), "$text: $reason")
        }
    }

    @Test
    fun theReasonSaysWhereTheParserStopped() {
        assertContains(reason("""{"a": 1 "b": 2}"""), "offset")
    }

    @Test
    fun aTextThatIsNestedTooDeeplyIsNoJson() {
        assertIs<JsonReading.Invalid>(readJson("[".repeat(100_000) + "]".repeat(100_000)))
    }
}
