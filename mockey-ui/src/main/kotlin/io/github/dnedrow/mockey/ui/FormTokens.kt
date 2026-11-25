package io.github.dnedrow.mockey.ui

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import java.security.SecureRandom

/**
 * The anti-forgery tokens of the forms of the UI.
 *
 * The UI gives every browser a value of its own, in a cookie. Every form of a page holds a token in a
 * hidden field, which is the mark of that value, see [ProcessKey]. The UI takes a form only when the token
 * of the form is the mark of the value that the request comes with. So a form is taken from a page that
 * this UI made for this browser, and from nothing else:
 *
 * - A page of another site cannot read a page of the UI, so it does not know a token.
 * - It cannot make one either, also where it can give the browser a value of its choice, because only the UI
 *   makes the mark of a value.
 * - A browser does not send the cookie with a form that a page of another site sends, so such a request
 *   comes with no value at all.
 *
 * The UI keeps nothing of a browser. The value is the browser's to keep, and it holds for as long as the UI
 * runs: a UI that is started anew has another key, and takes the forms of its own pages only.
 *
 * See `docs/decisions/ui-forms.md`.
 */
internal class FormTokens(
    private val key: ProcessKey,
) {
    private val random = SecureRandom()

    /**
     * Returns the token for the forms of a page that answers the request of [call]. A browser that came
     * without a value gets one with the answer.
     */
    fun tokenFor(call: ApplicationCall): String {
        val value = valueOf(call) ?: fresh().also { call.response.header(HttpHeaders.SetCookie, "${cookieOf(call)}=$it; $ATTRIBUTES") }
        return key.markOf(PURPOSE, value)
    }

    /** Returns whether the request of [call] comes with a value at all. A request without one is not taken, whatever its form holds. */
    fun hasValue(call: ApplicationCall): Boolean = valueOf(call) != null

    /** Returns whether [token], which a form holds, is the token for the browser that the request of [call] comes from. */
    fun accepts(
        call: ApplicationCall,
        token: String,
    ): Boolean {
        val value = valueOf(call) ?: return false
        return key.isMarkOf(PURPOSE, value, token)
    }

    private fun valueOf(call: ApplicationCall): String? {
        val cookies = call.request.cookies.rawCookies
        return cookies[cookieOf(call)]?.takeIf(VALUE::matches)
    }

    private fun fresh(): String = ProcessKey.encoded(ByteArray(VALUE_BYTES).also(random::nextBytes))

    internal companion object {
        /** The name of the field of a form that holds the token. */
        const val FIELD = "_token"

        private const val PURPOSE = "form"
        private const val VALUE_BYTES = 18
        private val VALUE = Regex("[A-Za-z0-9_-]{24}")

        /**
         * A page does not read the cookie, and the browser sends it with the requests of the UI's own pages
         * and when a link of another site leads to the UI, and with no form of another site.
         */
        private const val ATTRIBUTES = "Path=/; HttpOnly; SameSite=Lax"

        /**
         * Returns the name of the cookie. It holds the port of the UI: a browser keeps cookies for a host
         * and not for a port, so two UIs on one machine would take each other's cookie for their own.
         */
        fun cookieOf(call: ApplicationCall): String = "mockey-ui-${call.request.local.localPort}-form"
    }
}
