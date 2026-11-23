package io.github.dnedrow.mockey.parity

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/** Puts a running legacy Mockey into the state that a parity case starts from. */
class LegacyFixtureLoader(
    private val baseUrl: URI,
    private val client: HttpClient = ParityHttp.newClient(),
) {
    /**
     * Replaces every definition in legacy Mockey with [fixture] and keeps legacy in read-only mode. This also
     * empties the history, clears the outbound proxy settings, the tag filter, and the designations, and
     * leaves no twist configuration enabled. Legacy reads the file itself, so it must run on this machine.
     */
    fun load(fixture: Path) {
        val file = URLEncoder.encode(fixture.toAbsolutePath().toString(), StandardCharsets.UTF_8)
        val target = "/home?action=init&transientState=true&type=json&file=$file"
        val response = send(HttpRequest.newBuilder(baseUrl.resolve(target)).GET())
        check(response.statusCode() == HTTP_OK && "\"success\"" in response.body()) {
            "Legacy Mockey did not load $fixture: HTTP ${response.statusCode()} ${response.body().trim()}"
        }
        disableTwist()
    }

    /**
     * Legacy forgets the twist configurations when it loads a fixture, but not the number of the one that was
     * enabled. A configuration of the new fixture that gets that number would start out enabled.
     */
    private fun disableTwist() {
        val response = send(HttpRequest.newBuilder(baseUrl.resolve("/configuration/info")).GET())
        check(response.statusCode() == HTTP_OK) {
            "Legacy Mockey did not report its configuration: HTTP ${response.statusCode()} ${response.body().trim()}"
        }
        val twistId = enabledTwistId.find(response.body())?.groupValues?.get(1) ?: return
        toggleTwist(twistId.toInt(), enable = false)
    }

    /** Empties the cookie store that legacy shares between all proxied requests. Loading a fixture keeps it. */
    fun resetUpstreamSession() {
        val response = send(HttpRequest.newBuilder(baseUrl.resolve("/configuration/reset_sticky_cookie_session")).GET())
        check(response.statusCode() == HTTP_OK && "\"reset\":true" in response.body()) {
            "Legacy Mockey did not reset its upstream session: HTTP ${response.statusCode()} ${response.body().trim()}"
        }
    }

    /**
     * Enables the outbound proxy at [proxyUrl] with credentials. A fixture carries the proxy URL and the enabled
     * flag but no credentials, which legacy keeps in memory only.
     */
    fun setOutboundProxy(
        proxyUrl: String,
        username: String,
        password: String,
    ) {
        val form =
            mapOf("proxyUrl" to proxyUrl, "proxyEnabled" to "true", "proxyUsername" to username, "proxyPassword" to password)
                .entries
                .joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }
        val request =
            HttpRequest
                .newBuilder(baseUrl.resolve("/proxy/settings"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
        val response = send(request)
        check(response.statusCode() == HTTP_OK && "\"success\"" in response.body()) {
            "Legacy Mockey did not accept the outbound proxy settings: HTTP ${response.statusCode()} ${response.body().trim()}"
        }
    }

    /**
     * Limits routing to the services and scenarios that carry [tag]. Loading a fixture clears the filter, also
     * one that is given together with the fixture.
     */
    fun setTagFilter(tag: String) {
        val target = "/taghelp?action=filter_tag_on&tag=${URLEncoder.encode(tag, StandardCharsets.UTF_8)}"
        val response = send(HttpRequest.newBuilder(baseUrl.resolve(target)).GET())
        check(response.statusCode() == HTTP_OK && "\"success\"" in response.body()) {
            "Legacy Mockey did not set the tag filter: HTTP ${response.statusCode()} ${response.body().trim()}"
        }
    }

    /**
     * Enables the twist configuration that legacy numbers [twistId]. Loading a fixture enables none, whatever
     * the fixture says.
     */
    fun enableTwist(twistId: Int) = toggleTwist(twistId, enable = true)

    private fun toggleTwist(
        twistId: Int,
        enable: Boolean,
    ) {
        val target = "/twisting/toggle?twist-id=$twistId&twist-enable=$enable&response-type=json"
        val response = send(HttpRequest.newBuilder(baseUrl.resolve(target)).GET())
        check(response.statusCode() == HTTP_OK && "\"success\"" in response.body()) {
            "Legacy Mockey did not switch twist configuration $twistId: HTTP ${response.statusCode()} ${response.body().trim()}"
        }
    }

    private fun send(request: HttpRequest.Builder): HttpResponse<String> =
        client.send(request.timeout(ParityHttp.requestTimeout).build(), HttpResponse.BodyHandlers.ofString())

    private companion object {
        const val HTTP_OK = 200
        val enabledTwistId = Regex("\"twist-id\"\\s*:\\s*\"?(\\d+)")
    }
}
