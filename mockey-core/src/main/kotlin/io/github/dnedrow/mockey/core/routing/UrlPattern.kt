package io.github.dnedrow.mockey.core.routing

/**
 * A URL pattern in the form in which targets are matched against it. A URL pattern is the mock URL of a
 * service or one of its real URLs.
 *
 * A variable in a pattern is a name in braces, such as `{id}` in `customer/{id}`. The name is one or more
 * characters other than `/`, `{`, and `}`. Braces that hold no such name are literal text.
 *
 * A pattern matches a target only when it matches the whole target. Literal text matches the same text in any
 * letter case, and a variable matches one or more characters other than `/`, so that it never reaches beyond
 * one part of a path. A variable that is followed by literal text in its part of the path ends at the first
 * place where that text occurs. A variable that is followed by another variable takes one character.
 *
 * Pattern and target are compared in the form that [normalizedUrl] describes. The literal text of a pattern is
 * percent-decoded as a target is, so that a pattern that is the URL of a real service matches that URL. The
 * variables are found before that, so a brace that an escape stands for is literal text.
 */
internal class UrlPattern private constructor(
    private val segments: List<Segment>,
) {
    /** The names of the variables, in their order in the pattern. */
    val variableNames: List<String> = segments.flatMap { segment -> segment.parts.filterIsInstance<Part.Variable>().map { it.name } }

    /** How many variables the pattern has. Of two patterns that match a target, the one with fewer is more specific. */
    val variableCount: Int
        get() = variableNames.size

    /** How many characters of the pattern are literal text, counted in the form in which it is compared. */
    val literalLength: Int = segments.sumOf { it.literalLength } + segments.size - 1

    /**
     * Returns the value of each variable when the pattern matches [target], and `null` when it does not. The
     * values are in the letter case of the target and percent-decoded. A name that the pattern uses more than
     * once has the value of its first use.
     */
    fun match(target: Target): Map<String, String>? {
        val texts = target.segments
        if (texts.size != segments.size) return null
        val values = LinkedHashMap<String, String>()
        val matches =
            segments.indices.all { index ->
                segments[index].matches(texts[index]) { name, value -> values.putIfAbsent(name, value) }
            }
        return if (matches) values else null
    }

    companion object {
        private val VARIABLE = Regex("""\{([^/{}]+)\}""")

        /** Compiles [pattern]. Every text is a pattern: one without a variable matches the targets that equal it. */
        fun compile(pattern: String): UrlPattern {
            val scheme = schemeOf(pattern)
            val parts = parts(pattern).let { if (scheme == null) it else withoutDefaultPort(it, scheme) }
            return UrlPattern(segments(withoutOuterSlashes(parts, start = scheme?.let(::authorityStart) ?: 0)))
        }

        /** Takes [pattern] apart into its variables and the literal text between them, which is decoded. */
        private fun parts(pattern: String): List<Part> {
            val parts = mutableListOf<Part>()
            var literalStart = 0
            for (variable in VARIABLE.findAll(pattern)) {
                parts += Part.Literal(percentDecoded(pattern.substring(literalStart, variable.range.first)))
                parts += Part.Variable(variable.groupValues[1])
                literalStart = variable.range.last + 1
            }
            parts += Part.Literal(percentDecoded(pattern.substring(literalStart)))
            return parts.withoutEmptyLiterals()
        }

        /**
         * Leaves out the port of an absolute pattern when it is the default port of [scheme], as
         * [withoutDefaultPort] does for a URL. A port is found only where it is literal text that ends the host
         * and port: before the first slash after the scheme, or at the end of a pattern without such a slash.
         */
        private fun withoutDefaultPort(
            parts: List<Part>,
            scheme: String,
        ): List<Part> {
            var from = authorityStart(scheme)
            for ((index, part) in parts.withIndex()) {
                if (part is Part.Literal) {
                    val slash = part.text.indexOf('/', from)
                    if (slash >= 0 || index == parts.lastIndex) {
                        val end = if (slash < 0) part.text.length else slash
                        val port = defaultPort(part.text.substring(from, end), scheme) ?: return parts
                        val withoutPort = Part.Literal(part.text.removeRange(from + port.first, end))
                        return (parts.take(index) + withoutPort + parts.drop(index + 1)).withoutEmptyLiterals()
                    }
                }
                from = 0
            }
            return parts
        }

        /** Leaves out one slash at the start and one at the end, as [withoutOuterSlashes] does for a URL. */
        private fun withoutOuterSlashes(
            parts: List<Part>,
            start: Int,
        ): List<Part> {
            val first = parts.firstOrNull() as? Part.Literal
            val withoutFirst = if (first == null) parts else listOf(Part.Literal(first.text.removePrefix("/"))) + parts.drop(1)
            val last = withoutFirst.lastOrNull() as? Part.Literal
            val keepsScheme = withoutFirst.size == 1 && last != null && last.text.length <= start
            return if (last == null || keepsScheme) {
                withoutFirst.withoutEmptyLiterals()
            } else {
                (withoutFirst.dropLast(1) + Part.Literal(last.text.removeSuffix("/"))).withoutEmptyLiterals()
            }
        }

        /** Divides [parts] at the slashes of their literal text. */
        private fun segments(parts: List<Part>): List<Segment> {
            val segments = mutableListOf<Segment>()
            var current = mutableListOf<Part>()
            for (part in parts) {
                when (part) {
                    is Part.Variable -> {
                        current += part
                    }

                    is Part.Literal -> {
                        part.text.split('/').forEachIndexed { index, text ->
                            if (index > 0) {
                                segments += Segment(current)
                                current = mutableListOf()
                            }
                            if (text.isNotEmpty()) current += Part.Literal(text)
                        }
                    }
                }
            }
            return segments + Segment(current)
        }

        private fun List<Part>.withoutEmptyLiterals(): List<Part> = filterNot { it is Part.Literal && it.text.isEmpty() }
    }
}

/** A piece of a URL pattern. */
private sealed interface Part {
    data class Literal(
        val text: String,
    ) : Part

    data class Variable(
        val name: String,
    ) : Part
}

/** What a URL pattern holds between two slashes. Two literal parts never follow each other. */
private class Segment(
    val parts: List<Part>,
) {
    val literalLength: Int = parts.sumOf { (it as? Part.Literal)?.text?.length ?: 0 }

    /**
     * Returns whether the segment matches [text], which is what a target holds between two slashes, and
     * tells [capture] the name and the value of each variable. When the segment does not match, [capture] can
     * have been told of some variables all the same.
     *
     * The text that a literal part at the start and at the end takes is fixed. Between them, each variable
     * ends as early as it can. That finds a match whenever there is one, and it takes no more time than the
     * length of the text times the length of the pattern.
     */
    fun matches(
        text: String,
        capture: (name: String, value: String) -> Unit,
    ): Boolean {
        var first = 0
        var last = parts.size
        var start = 0
        var end = text.length
        (parts.firstOrNull() as? Part.Literal)?.let { literal ->
            if (!text.startsWith(literal.text, ignoreCase = true)) return false
            start = literal.text.length
            first = 1
        }
        (parts.lastOrNull().takeIf { last > first } as? Part.Literal)?.let { literal ->
            val length = literal.text.length
            if (end - start < length || !text.regionMatches(end - length, literal.text, 0, length, ignoreCase = true)) return false
            end -= length
            last--
        }
        if (first == last) return start == end
        while (first < last) {
            val variable = parts[first] as Part.Variable
            val next = if (first + 1 < last) parts[first + 1] else null
            val valueEnd =
                when (next) {
                    null -> end
                    is Part.Variable -> start + 1
                    is Part.Literal -> text.indexOf(next.text, start + 1, ignoreCase = true)
                }
            val nextEnd = if (next is Part.Literal) valueEnd + next.text.length else valueEnd
            if (valueEnd <= start || nextEnd > end) return false
            capture(variable.name, text.substring(start, valueEnd))
            start = nextEnd
            first += if (next is Part.Literal) 2 else 1
        }
        return true
    }
}
