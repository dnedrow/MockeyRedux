package io.github.dnedrow.mockey.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The liveness endpoint: `/status` on the mock listener. It answers with a JSON object that says that the
 * server is running and since when, and nothing else, so that it discloses nothing about the machine. As
 * in Mockey 2.x, it gives that answer to a request of every method, and it takes nothing from a request.
 *
 * [since] is the time at which the server started. It is stated in ISO 8601, to the millisecond.
 */
internal class StatusEndpoint(
    since: Instant,
) {
    private val document =
        buildJsonObject {
            put("status", "running")
            put("since", since.truncatedTo(ChronoUnit.MILLIS).toString())
        }.toString()

    /** Answers the request of [call] when it is for `/status`, and returns whether it is. */
    suspend fun handle(call: ApplicationCall): Boolean {
        if (RequestTarget.parse(call.request.local.uri).path != PATH) return false
        // The answer of a moment ago says nothing about now.
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respond(TextContent(document, ContentType.Application.Json, HttpStatusCode.OK))
        return true
    }

    internal companion object {
        /** The path of the liveness endpoint. */
        const val PATH = "/status"
    }
}
