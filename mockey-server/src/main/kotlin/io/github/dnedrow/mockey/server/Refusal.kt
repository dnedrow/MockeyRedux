package io.github.dnedrow.mockey.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent

/**
 * What the server answers on its own account to a request that it does not take: a status, and the reason
 * as plain text.
 */
internal object Refusal {
    /** The content type of the reason: plain text in UTF-8. */
    val PLAIN_TEXT: ContentType = ContentType.parse("text/plain; charset=utf-8")

    /** Returns the content of an answer with [status] that gives [reason]. */
    fun content(
        status: HttpStatusCode,
        reason: String,
    ): OutgoingContent = TextContent(reason, PLAIN_TEXT, status)
}
