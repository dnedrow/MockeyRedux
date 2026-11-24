package io.github.dnedrow.mockey.core.request

import java.nio.charset.Charset

/**
 * Returns the media type that the value of a `Content-Type` header names, in lower case and without its
 * parameters, such as `text/plain` for `Text/Plain; charset=UTF-8`. Returns `null` when the value names none.
 */
internal fun mediaTypeOf(contentType: String?): String? =
    contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }

/**
 * Returns the charset that the value of a `Content-Type` header declares with its `charset` parameter. Returns
 * `null` when the value declares none, or one that this runtime does not know.
 */
internal fun charsetOf(contentType: String?): Charset? {
    val parameters = contentType?.split(';')?.drop(1).orEmpty()
    val declared = parameters.firstOrNull { it.substringBefore('=').trim().equals("charset", ignoreCase = true) } ?: return null
    val name = declared.substringAfter('=', missingDelimiterValue = "").trim().removeSurrounding("\"")
    return try {
        Charset.forName(name)
    } catch (e: IllegalArgumentException) {
        // The name is not the name of a charset, or the name of one that this runtime does not have.
        null
    }
}
