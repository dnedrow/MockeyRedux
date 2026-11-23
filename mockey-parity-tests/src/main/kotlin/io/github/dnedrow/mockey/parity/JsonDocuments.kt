package io.github.dnedrow.mockey.parity

import java.math.BigDecimal

/**
 * A reader for JSON documents that is just large enough to compare the results of the automation endpoints.
 * An object becomes a [Map], an array a [List], a number a [BigDecimal], and `null` becomes [Null].
 */
internal object JsonDocuments {
    object Null {
        override fun toString() = "null"
    }

    /** Reads [text] as one JSON document. Fails with an [IllegalArgumentException] when it is none. */
    fun parse(text: String): Any = Parser(text).document()

    /**
     * The differences between [actual] and [expected]. Objects are compared member by member whatever the order,
     * arrays element by element. Where [expected] holds the string [anyValue], every value is accepted.
     */
    fun differences(
        expected: Any,
        actual: Any,
        anyValue: String? = null,
        path: String = "$",
    ): List<String> =
        when {
            anyValue != null && expected == anyValue -> emptyList()
            expected is Map<*, *> && actual is Map<*, *> -> objectDifferences(expected, actual, anyValue, path)
            expected is List<*> && actual is List<*> -> arrayDifferences(expected, actual, anyValue, path)
            expected is BigDecimal && actual is BigDecimal && expected.compareTo(actual) == 0 -> emptyList()
            expected == actual -> emptyList()
            else -> listOf("$path: expected ${render(expected)}, got ${render(actual)}")
        }

    private fun objectDifferences(
        expected: Map<*, *>,
        actual: Map<*, *>,
        anyValue: String?,
        path: String,
    ): List<String> =
        (expected.keys - actual.keys).map { "$path.$it is missing" } +
            (actual.keys - expected.keys).map { "$path.$it is not expected" } +
            expected.keys.intersect(actual.keys).flatMap { name ->
                differences(expected[name]!!, actual[name]!!, anyValue, "$path.$name")
            }

    private fun arrayDifferences(
        expected: List<*>,
        actual: List<*>,
        anyValue: String?,
        path: String,
    ): List<String> =
        if (expected.size != actual.size) {
            listOf("$path: expected ${expected.size} elements, got ${actual.size}")
        } else {
            expected.indices.flatMap { index -> differences(expected[index]!!, actual[index]!!, anyValue, "$path[$index]") }
        }

    private fun render(value: Any): String =
        when (value) {
            is String -> "\"$value\""
            is Map<*, *> -> "an object"
            is List<*> -> "an array"
            else -> value.toString()
        }

    private class Parser(
        private val text: String,
    ) {
        private var position = 0

        fun document(): Any {
            val value = value()
            skipWhitespace()
            require(position == text.length) { "unexpected text after the JSON document at offset $position" }
            return value
        }

        private fun value(): Any {
            skipWhitespace()
            return when (val next = peek()) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", Null)
                else -> if (next == '-' || next.isDigit()) number() else fail("unexpected '$next'")
            }
        }

        private fun objectValue(): Map<String, Any> {
            val members = linkedMapOf<String, Any>()
            expect('{')
            skipWhitespace()
            if (peek() == '}') {
                position++
                return members
            }
            while (true) {
                skipWhitespace()
                val name = string()
                skipWhitespace()
                expect(':')
                members[name] = value()
                skipWhitespace()
                if (peek() == ',') position++ else break
            }
            expect('}')
            return members
        }

        private fun arrayValue(): List<Any> {
            val elements = mutableListOf<Any>()
            expect('[')
            skipWhitespace()
            if (peek() == ']') {
                position++
                return elements
            }
            while (true) {
                elements += value()
                skipWhitespace()
                if (peek() == ',') position++ else break
            }
            expect(']')
            return elements
        }

        private fun string(): String {
            expect('"')
            val result = StringBuilder()
            while (true) {
                when (val next = take()) {
                    '"' -> return result.toString()
                    '\\' -> result.append(escaped())
                    else -> result.append(next)
                }
            }
        }

        private fun escaped(): Char =
            when (val next = take()) {
                '"', '\\', '/' -> next
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> unicodeEscape()
                else -> fail("unknown escape '\\$next'")
            }

        private fun unicodeEscape(): Char {
            require(position + UNICODE_DIGITS <= text.length) { "incomplete escape at offset $position" }
            val code = text.substring(position, position + UNICODE_DIGITS).toInt(HEXADECIMAL)
            position += UNICODE_DIGITS
            return code.toChar()
        }

        private fun number(): BigDecimal {
            val start = position
            while (position < text.length && text[position] in NUMBER_CHARACTERS) position++
            return text.substring(start, position).toBigDecimalOrNull() ?: fail("malformed number")
        }

        private fun literal(
            word: String,
            value: Any,
        ): Any {
            require(text.startsWith(word, position)) { "unexpected text at offset $position" }
            position += word.length
            return value
        }

        private fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        private fun peek(): Char = if (position < text.length) text[position] else fail("unexpected end")

        private fun take(): Char = peek().also { position++ }

        private fun expect(character: Char) {
            if (take() != character) fail("expected '$character'")
        }

        private fun fail(reason: String): Nothing = throw IllegalArgumentException("$reason at offset $position")
    }

    private const val UNICODE_DIGITS = 4
    private const val HEXADECIMAL = 16
    private const val NUMBER_CHARACTERS = "+-.0123456789eE"
}
