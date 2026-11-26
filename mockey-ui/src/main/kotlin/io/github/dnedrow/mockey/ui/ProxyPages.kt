package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.OutboundProxyDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The page for how the server reaches real services: the form with the settings of the outbound proxy, and
 * the button that clears the upstream session.
 *
 * See `docs/decisions/ui-pages.md` for the reasoning behind this page.
 */
internal val ProxyArea =
    Area { routing, ui ->
        with(routing) {
            get(ProxyPages.PAGE) {
                ui.show(call) { view, _ ->
                    val proxy = ui.client.getUpstreamProxy()
                    ProxyPages.proxy(view, proxy, ProxyForm.of(proxy))
                }
            }
            post(ProxyPages.PAGE) {
                val again: suspend (View, SystemStatusDto, FormState) -> Page = { view, _, state ->
                    ProxyPages.proxy(view, ui.client.getUpstreamProxy(), state)
                }
                ui.submit(call, again) { input ->
                    val saved = ui.client.updateUpstreamProxy(ProxyForm.attributesOf(input))
                    Outcome.Done(ProxyPages.PAGE, ProxyPages.savedNotice(saved))
                }
            }
            post(ProxyPages.CLEAR_SESSION) {
                ui.submit(call) {
                    ui.client.clearUpstreamSession()
                    Outcome.Done(ProxyPages.PAGE, "The upstream session was cleared.")
                }
            }
        }
    }

/** The page for the outbound proxy and the upstream session. */
internal object ProxyPages {
    /** The path of the page, which is also the form with the settings of the outbound proxy. */
    const val PAGE = "/proxy"

    /** The path of the action that clears the upstream session. */
    const val CLEAR_SESSION = "/proxy/clear-session"

    /** Returns the notice to show after the settings were saved. [proxy] holds the settings as the server has them now. */
    fun savedNotice(proxy: OutboundProxyDto): String {
        val route = if (proxy.enabled) "go through ${briefly(proxy.url)}" else "go directly to the real services"
        return "The proxy settings were saved. Forwarded requests now $route."
    }

    /**
     * Returns the page with the settings of the outbound proxy and the button that clears the upstream
     * session. [proxy] holds the settings as the server has them, and the form is displayed with [state].
     */
    fun proxy(
        view: View,
        proxy: OutboundProxyDto,
        state: FormState,
    ): Page =
        Page("Outbound proxy") {
            p { +INTRO }
            postForm(view, PAGE, inPlace = true) {
                shownOf(state, ProxyForm.FIELDS)
                rejectionOf(state)
                textField(state, ProxyForm.URL, "Proxy URL", hint = URL_HINT)
                checkField(state, ProxyForm.ENABLED, "Send forwarded requests through the proxy")
                textField(state, ProxyForm.USERNAME, "User name", hint = USERNAME_HINT, autocomplete = "off")
                passwordField(state, ProxyForm.PASSWORD, "Password", hint = if (proxy.passwordSet) PASSWORD_SET else NO_PASSWORD)
                if (proxy.passwordSet) checkField(state, ProxyForm.REMOVE_PASSWORD, "Remove the stored password", api = "password")
                otherProblemsOf(state)
                submit("Save the proxy settings")
            }
            h2 { +"Upstream session" }
            p { +SESSION_INTRO }
            postForm(view, CLEAR_SESSION) { submit("Clear the upstream session") }
        }

    private const val INTRO =
        "While the outbound proxy is enabled, the server sends every forwarded request through it. " +
            "Otherwise, the server connects to real services directly."
    private const val URL_HINT = "The address of the proxy, such as http://proxy.example.com:3128."
    private const val USERNAME_HINT = "Only needed if the proxy asks for credentials."
    private const val PASSWORD_SET =
        "The server has a password for the proxy. Leave this field empty to keep it. " +
            "The password is kept in memory only, so it is gone after the server restarts."
    private const val NO_PASSWORD =
        "The server has no password for the proxy. A password is kept in memory only, so it is gone after the server restarts."
    private const val SESSION_INTRO =
        "The server keeps the cookies that real services set, and it sends them with later forwarded requests. " +
            "Clearing the upstream session throws these cookies away."
}

/**
 * The form with the settings of the outbound proxy: the names of its fields, the state it displays, and the
 * attributes it asks the admin API to set. The form only sends the attributes whose fields were changed (see
 * [Shown]).
 */
internal object ProxyForm {
    const val URL = "url"
    const val ENABLED = "enabled"
    const val USERNAME = "username"
    const val PASSWORD = "password"

    /** The check box that removes the stored password. */
    const val REMOVE_PASSWORD = "removePassword"

    /**
     * The fields whose initial values the form tracks (see [Shown]). The password fields aren't among them:
     * the password field always starts empty, so anything typed into it is an edit.
     */
    val FIELDS = listOf(URL, ENABLED, USERNAME)

    /**
     * Returns the initial state of the form for [proxy]. The password field starts empty, because the server
     * never reveals the password.
     */
    fun of(proxy: OutboundProxyDto): FormState {
        val texts = mapOf(URL to inOneLine(proxy.url), USERNAME to inOneLine(proxy.username.orEmpty()))
        val checked = if (proxy.enabled) mapOf(ENABLED to listOf(CHECKED)) else emptyMap()
        return FormState(texts.mapValues { listOf(it.value) } + checked)
    }

    /**
     * Returns the attributes that the form asks the admin API to set: the ones whose fields were changed (see
     * [Shown]). An empty user name means there is none. A password is only sent if one was typed in, and the
     * check box that removes the stored password wins over a typed one.
     */
    fun attributesOf(input: FormInput): JsonObject =
        buildJsonObject {
            if (input.isChanged(URL)) put("url", input.text(URL))
            if (input.isChanged(ENABLED)) put("enabled", input.isChecked(ENABLED))
            if (input.isChanged(USERNAME)) put("username", input.text(USERNAME).takeIf { it.isNotEmpty() })
            when {
                input.isChecked(REMOVE_PASSWORD) -> put("password", null as String?)
                input.text(PASSWORD).isNotEmpty() -> put("password", input.text(PASSWORD))
            }
        }
}
