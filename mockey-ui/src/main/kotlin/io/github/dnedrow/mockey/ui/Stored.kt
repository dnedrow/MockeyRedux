package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.HeaderDto
import io.ktor.server.application.ApplicationCall
import kotlinx.html.FlowContent
import kotlinx.html.FlowOrPhrasingContent
import kotlinx.html.code
import kotlinx.html.pre
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/*
 * How the pages show what a server holds and what it recorded: names, tags, URLs, headers, and bodies. All
 * of it is content that someone else wrote, and a page shows it as the text that it is.
 *
 * The builders of kotlinx.html write a text, and the value of an attribute, so that a browser reads it as
 * text. The UI writes no content in another way: nothing here and nothing in a page uses `unsafe`, which
 * writes markup as it is given, and a test reads the sources for that.
 *
 * Text is safe in the text of an element and in the value of an attribute. It is not safe everywhere a
 * browser reads more than text, and the UI puts no content there:
 *
 * - The address of a link. A URL that a server holds is shown as text and is no link, so a URL that begins
 *   with `javascript:` runs nothing. A link of a page leads to a page of the UI, and holds content only as a
 *   parameter of its query string, see [Address].
 * - The name of an element or of an attribute, a script, and a style. A page has none that is made of
 *   content.
 */

/** Shows [tags], each as a tag. */
internal fun FlowOrPhrasingContent.tagsOf(tags: List<String>) {
    for ((index, tag) in tags.withIndex()) {
        if (index > 0) +" "
        span(classes = "tag") { +tag }
    }
}

/** Shows [url], which is a URL or a pattern for URLs that a server holds, as text. It is no link. */
internal fun FlowOrPhrasingContent.urlOf(url: String) {
    code(classes = "url") { +url }
}

/**
 * Shows [headers] in their order, each with its name and its value. A header that is there more than once
 * is shown as often.
 */
internal fun FlowContent.headersOf(headers: List<HeaderDto>) {
    table(classes = "headers") {
        thead {
            tr {
                th { +"Header" }
                th { +"Value" }
            }
        }
        tbody {
            for (header in headers) {
                tr {
                    td { +header.name }
                    td { +header.value }
                }
            }
        }
    }
}

/** Shows [text], which is the body of a request or of a response, with its line breaks and its spaces as they are. */
internal fun FlowContent.bodyOf(text: String) {
    pre(classes = "body") {
        // A browser drops a line feed that follows the start of the element, so one is written for it to drop.
        +"\n$text"
    }
}

/**
 * The addresses of the pages of the UI. The path of an address says which kind of page it is, in words of
 * the UI. What the page is about, such as the identifier of a service, and how the page shows it, such as
 * the tags that it filters by, are parameters of the query string.
 *
 * An identifier is content like any other: a server reads it from its definitions, and it can be any text.
 * As a segment of a path, an identifier of dots would lead a browser to another page, because a browser
 * takes such a segment for the directory above, however it is written. And a proxy in front of the UI may
 * take a slash in a segment for one of the path. A parameter holds every text as it is.
 */
internal object Address {
    /** The characters that a parameter and a fragment hold as they are. */
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    /** How a path of the UI is written: words of letters, digits, hyphens, and underscores, each after a slash. */
    private val PATH = Regex("(/[A-Za-z0-9_-]+)+")

    /**
     * Returns the address of the page at [path] with the parameters [query], in their order. A parameter with
     * the value `null` is left out.
     */
    fun of(
        path: String,
        vararg query: Pair<String, String?>,
    ): String {
        require(PATH.matches(path)) { "A path of the UI is made of words, and $path is none." }
        val stated = query.mapNotNull { (name, value) -> value?.let { "${escaped(name)}=${escaped(it)}" } }
        return if (stated.isEmpty()) path else stated.joinToString("&", "$path?")
    }

    /**
     * Returns the identifier of an element of a page that is about what a server holds under the identifier
     * [stored], such as the row of a service. It begins with [kind] and is one that an address can refer to
     * after a `#` as it is.
     */
    fun anchorOf(
        kind: String,
        stored: String,
    ): String = "$kind-${escaped(stored).replace('%', '.')}"

    /** Returns [text] with every character but the unreserved ones written as the escapes of its bytes in UTF-8. */
    private fun escaped(text: String): String =
        buildString {
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                val character = byte.toInt().toChar()
                if (byte >= 0 && character in UNRESERVED) append(character) else append("%%%02X".format(byte))
            }
        }
}

/** A request for a page whose address lacks what says which page it is, such as the identifier of a service. The UI has no such page. */
internal class NoSuchPage : Exception("The address of the request names no page.")

/**
 * Returns the parameter [name] of the address of the request, which says what the page is about, see
 * [Address].
 *
 * @throws NoSuchPage when the address has no such parameter.
 */
internal fun ApplicationCall.partOfAddress(name: String): String = request.queryParameters[name] ?: throw NoSuchPage()
