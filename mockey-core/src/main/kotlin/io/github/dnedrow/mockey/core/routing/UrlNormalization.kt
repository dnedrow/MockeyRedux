package io.github.dnedrow.mockey.core.routing

import java.io.ByteArrayOutputStream

/** The schemes of absolute URLs, each with its default port. */
private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443)

/** A port at the end of the host and port of a URL. The port after the colon can be empty. */
private val PORT = Regex(""":([0-9]*)$""")

/**
 * Returns [url] in the form in which targets and URL patterns are compared:
 *
 * - Percent escapes are decoded, see [percentDecoded].
 * - A URL that begins with `http://` or `https://` loses its port when that is the default port of the
 *   scheme, so that `http://host:80/a` and `http://host/a` are the same.
 * - One slash at the start and one slash at the end are left out.
 *
 * The letter case stays as it is. Whoever compares two normalized URLs disregards it.
 */
internal fun normalizedUrl(url: String): String {
    val scheme = schemeOf(url)
    val decoded = percentDecoded(url)
    return if (scheme == null) {
        withoutOuterSlashes(decoded, start = 0)
    } else {
        withoutOuterSlashes(withoutDefaultPort(decoded, scheme), start = authorityStart(scheme))
    }
}

/** Returns the scheme of [url] in lower case when the URL is absolute, and `null` for any other text. */
internal fun schemeOf(url: String): String? = DEFAULT_PORTS.keys.firstOrNull { url.startsWith("$it://", ignoreCase = true) }

/** Returns where the host of an absolute URL with [scheme] begins. */
internal fun authorityStart(scheme: String): Int = scheme.length + "://".length

/**
 * Returns [text] with every percent escape replaced by the character that it stands for in UTF-8. A `%` that
 * starts no escape stays as it is, and so does a `+`: it stands for a space in form data only. Bytes that are
 * not UTF-8 become the replacement character.
 */
internal fun percentDecoded(text: String): String {
    if ('%' !in text) return text
    val decoded = StringBuilder(text.length)
    val bytes = ByteArrayOutputStream()
    var index = 0
    while (index < text.length) {
        val byte = escapedByte(text, index)
        if (byte == null) {
            decoded.appendBytes(bytes).append(text[index])
            index++
        } else {
            bytes.write(byte)
            index += ESCAPE_LENGTH
        }
    }
    return decoded.appendBytes(bytes).toString()
}

private const val ESCAPE_LENGTH = 3
private const val HEX = 16

/** Returns the byte that the escape at [index] of [text] stands for, or `null` when no escape starts there. */
private fun escapedByte(
    text: String,
    index: Int,
): Int? {
    if (text[index] != '%' || index + ESCAPE_LENGTH > text.length) return null
    val high = hexDigit(text[index + 1]) ?: return null
    val low = hexDigit(text[index + 2]) ?: return null
    return high * HEX + low
}

private fun hexDigit(char: Char): Int? =
    when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> null
    }

/** Appends the characters that [bytes] hold as UTF-8, and empties [bytes]. */
private fun StringBuilder.appendBytes(bytes: ByteArrayOutputStream): StringBuilder {
    if (bytes.size() > 0) {
        append(bytes.toString(Charsets.UTF_8))
        bytes.reset()
    }
    return this
}

/**
 * Returns the decoded absolute [url] without its port when that is the default port of [scheme]. The port is
 * what follows the last colon of the text between the scheme and the first slash, and an empty port is the
 * default port.
 */
internal fun withoutDefaultPort(
    url: String,
    scheme: String,
): String {
    val start = authorityStart(scheme)
    val end = url.indexOf('/', start).let { if (it < 0) url.length else it }
    val port = defaultPort(url.substring(start, end), scheme) ?: return url
    return url.removeRange(start + port.first, end)
}

/** Returns where the default port of [scheme] stands at the end of [hostAndPort], or `null` when it does not. */
internal fun defaultPort(
    hostAndPort: String,
    scheme: String,
): IntRange? {
    val port = PORT.find(hostAndPort) ?: return null
    val number = port.groupValues[1]
    return port.range.takeIf { number.isEmpty() || number.toIntOrNull() == DEFAULT_PORTS.getValue(scheme) }
}

/**
 * Returns [url] without one slash at its start and without one slash at its end. A slash at the end counts
 * only when it follows [start], which keeps the slashes after the scheme of an absolute URL.
 */
internal fun withoutOuterSlashes(
    url: String,
    start: Int,
): String {
    val withoutFirst = url.removePrefix("/")
    return if (withoutFirst.length > start) withoutFirst.removeSuffix("/") else withoutFirst
}
