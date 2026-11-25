package io.github.dnedrow.mockey.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.utils.io.discard

/**
 * The body of a request that is answered before it is read: a request that the server refuses, a request
 * for a path that nothing serves, and a request for `/status`, which takes no body.
 *
 * On a connection that stays, such an answer goes out at once, and Netty reads and drops the body
 * afterwards. On a connection that ends with the answer, Netty closes the connection as soon as the answer
 * is written, and a client that is still sending its body then finds the connection closed and gets no
 * answer. So for such a connection a listener reads and drops what is left of the body first.
 */
internal object UnreadBody {
    private const val HTTP_1_0 = "HTTP/1.0"

    /**
     * Makes [application] read and drop what is left of the body of a request before it writes the answer,
     * when the connection ends with that answer.
     */
    fun dropBeforeTheLastAnswer(application: Application) {
        application.sendPipeline.intercept(ApplicationSendPipeline.Before) {
            if (endsTheConnection(call)) call.request.receiveChannel().discard()
        }
    }

    /**
     * Returns whether the connection ends with the answer to the request of [call]: the client asked for
     * that, or it speaks HTTP/1.0 and did not ask to keep the connection.
     */
    private fun endsTheConnection(call: ApplicationCall): Boolean {
        val asked =
            call.request.headers
                .getAll(HttpHeaders.Connection)
                .orEmpty()
                .flatMap { it.split(',') }
                .map { it.trim().lowercase() }
        return if (call.request.local.version == HTTP_1_0) "keep-alive" !in asked else "close" in asked
    }
}
