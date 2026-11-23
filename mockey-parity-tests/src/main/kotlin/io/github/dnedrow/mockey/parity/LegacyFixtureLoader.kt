package io.github.dnedrow.mockey.parity

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/** Loads fixtures into a running legacy Mockey through its initialization endpoint. */
class LegacyFixtureLoader(
    private val baseUrl: URI,
    private val client: HttpClient = HttpClient.newHttpClient(),
) {
    /**
     * Replaces every definition in legacy Mockey with [fixture] and keeps legacy in read-only mode.
     * Legacy reads the file itself, so it must run on this machine.
     */
    fun load(fixture: Path) {
        val file = URLEncoder.encode(fixture.toAbsolutePath().toString(), StandardCharsets.UTF_8)
        val target = "/home?action=init&transientState=true&type=json&file=$file"
        val response = client.send(HttpRequest.newBuilder(baseUrl.resolve(target)).GET().build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == HTTP_OK && "\"success\"" in response.body()) {
            "Legacy Mockey did not load $fixture: HTTP ${response.statusCode()} ${response.body().trim()}"
        }
    }

    private companion object {
        const val HTTP_OK = 200
    }
}
