package io.github.dnedrow.mockey.ui

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header

/**
 * What the UI says once about a form that it has done: after the form, the browser is sent to a page, and
 * that page says that the change was made.
 *
 * The UI keeps no notice. It gives the notice to the browser in a cookie with the answer to the form, and
 * takes it back with the next request for a page, which shows it. The browser drops the cookie then, so a
 * page that is loaded again says nothing of a change that is not made again, and the address of a page
 * holds nothing of it. The cookie carries the mark of its text, see [ProcessKey]: the UI shows only what
 * it wrote itself.
 */
internal class Notices(
    private val key: ProcessKey,
) {
    /** Lets the next page that the browser of [call] asks for say [text]. */
    fun leave(
        call: ApplicationCall,
        text: String,
    ) {
        val written = ProcessKey.encoded(text.take(LONGEST).toByteArray(Charsets.UTF_8))
        val cookie = "${cookieOf(call)}=$written.${key.markOf(PURPOSE, written)}"
        call.response.header(HttpHeaders.SetCookie, "$cookie; Max-Age=$KEPT_SECONDS; $ATTRIBUTES")
    }

    /**
     * Returns what the page that answers the request of [call] is to say, or `null` when there is nothing.
     * The browser is told to drop the notice with the answer.
     */
    fun take(call: ApplicationCall): String? {
        val cookie = call.request.cookies.rawCookies[cookieOf(call)] ?: return null
        call.response.header(HttpHeaders.SetCookie, "${cookieOf(call)}=; Max-Age=0; $ATTRIBUTES")
        val written = cookie.substringBefore('.')
        if (!key.isMarkOf(PURPOSE, written, cookie.substringAfter('.', ""))) return null
        return ProcessKey.decoded(written)?.let { String(it, Charsets.UTF_8) }
    }

    private companion object {
        const val PURPOSE = "notice"

        /** The number of characters of a notice that the cookie carries. A browser takes no cookie beyond 4,096 bytes. */
        const val LONGEST = 600

        /** For how long the browser keeps a notice that no page took. The page that takes it follows the form at once. */
        const val KEPT_SECONDS = 60
        const val ATTRIBUTES = "Path=/; HttpOnly; SameSite=Lax"

        fun cookieOf(call: ApplicationCall): String = "mockey-ui-${call.request.local.localPort}-notice"
    }
}
