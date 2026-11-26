package io.github.dnedrow.mockey.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

/**
 * Returns the body of the request of [call], or `null` when it is longer than [limit] bytes. A request that
 * states a length beyond the limit is not read at all, and any other is read as far as the limit goes. What
 * is not read here is read and dropped before the answer or after it, see [UnreadBody].
 *
 * The body is read from the channel of the request itself. The channel that Ktor gives a call for its
 * body is the same one, but on the way to it Ktor writes the request target with its query string to
 * the log, at the most verbose level.
 */
internal suspend fun bodyOf(
    call: ApplicationCall,
    limit: Long,
): ByteArray? {
    val stated =
        call.request.headers[HttpHeaders.ContentLength]
            ?.trim()
            ?.toLongOrNull()
    if (stated != null && stated > limit) return null
    val bytes =
        call.request
            .receiveChannel()
            .readBuffer(limit + 1)
            .readByteArray()
    return bytes.takeUnless { it.size > limit }
}
