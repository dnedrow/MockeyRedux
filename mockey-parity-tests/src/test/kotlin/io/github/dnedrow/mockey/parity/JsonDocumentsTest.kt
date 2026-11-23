package io.github.dnedrow.mockey.parity

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonDocumentsTest {
    @Test
    fun readsEveryKindOfValue() {
        val text = """ {"text": "a\"b\\c\/d\n\u00e9", "number": -12.5e1, "flags": [true, false, null], "empty": {}, "none": []} """
        val document = JsonDocuments.parse(text)

        assertEquals(
            mapOf(
                "text" to "a\"b\\c/d\n\u00e9",
                "number" to BigDecimal("-12.5e1"),
                "flags" to listOf(true, false, JsonDocuments.Null),
                "empty" to emptyMap<String, Any>(),
                "none" to emptyList<Any>(),
            ),
            document,
        )
    }

    @Test
    fun rejectsTextThatIsNoJsonDocument() {
        listOf("", "{", "{\"a\":}", "[1,]", "{\"a\":1} trailing", "'single'", "{\"a\":tru}", "\"open", "{\"a\":\"\\x\"}", "-")
            .forEach { text -> assertFailsWith<IllegalArgumentException>(text) { JsonDocuments.parse(text) } }
    }

    @Test
    fun comparesObjectsWhateverTheOrderOfTheirMembers() {
        assertEquals(emptyList(), differences("""{"a":1,"b":{"c":"x","d":[1,2]}}""", """{ "b": {"d": [1, 2], "c": "x"}, "a": 1.0 }"""))
    }

    @Test
    fun reportsEveryDifferenceWithItsPath() {
        assertEquals(
            listOf(
                "$.missing is missing",
                "$.extra is not expected",
                "$.name: expected \"Gold path\", got \"gold path\"",
                "$.items: expected 2 elements, got 1",
                "$.nested.flag: expected true, got \"true\"",
                "$.list[1]: expected 2, got 3",
            ),
            differences(
                """{"name":"Gold path","missing":1,"items":[1,2],"nested":{"flag":true},"list":[1,2]}""",
                """{"name":"gold path","extra":1,"items":[1],"nested":{"flag":"true"},"list":[1,3]}""",
            ),
        )
    }

    @Test
    fun acceptsEveryValueWhereTheExpectedDocumentSaysSo() {
        val expected = """{"result":{"planid":"<any>","planName":"Gold path","items":"<any>"}}"""

        assertEquals(emptyList(), differences(expected, """{"result":{"planid":7,"planName":"Gold path","items":[{"a":1}]}}""", ANY_VALUE))
        assertEquals(
            listOf("$.result.planid is missing"),
            differences(expected, """{"result":{"planName":"Gold path","items":null}}""", ANY_VALUE),
        )
        assertEquals(
            listOf("$.result.planid: expected \"<any>\", got 7"),
            differences("""{"result":{"planid":"<any>"}}""", """{"result":{"planid":7}}"""),
        )
    }

    private fun differences(
        expected: String,
        actual: String,
        anyValue: String? = null,
    ) = JsonDocuments.differences(JsonDocuments.parse(expected), JsonDocuments.parse(actual), anyValue)
}
