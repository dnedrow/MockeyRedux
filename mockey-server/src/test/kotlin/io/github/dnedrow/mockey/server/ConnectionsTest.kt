package io.github.dnedrow.mockey.server

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.DefaultChannelId
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The count of the requests that are in progress on a connection, where a test over HTTP cannot say when
 * something happens: the connections here are ones of Netty that live in memory, which do each step when
 * the test tells them to.
 */
class ConnectionsTest {
    private val connections = Connections()

    /** Stands where the handler of Ktor stands in the pipeline of a connection, and notes what reaches it. */
    private class HandlerOfKtor : ChannelInboundHandlerAdapter() {
        val read = mutableListOf<Any>()
        var ended = false

        override fun channelRead(
            context: ChannelHandlerContext,
            message: Any,
        ) {
            read += message
        }

        override fun channelInactive(context: ChannelHandlerContext) {
            ended = true
        }
    }

    /** Returns a connection as the engine sets it up: with [handler] for the one of Ktor, before which the watching handler goes. */
    private fun connection(handler: ChannelInboundHandlerAdapter = HandlerOfKtor()): EmbeddedChannel {
        // A connection in memory has the same identifier as every other one, unless it is given its own.
        val channel = EmbeddedChannel(DefaultChannelId.newInstance())
        channel.pipeline().addLast(HANDLER_OF_KTOR, handler)
        connections.watch(channel.pipeline())
        return channel
    }

    /** Lets a request without a body arrive on this connection. */
    private fun EmbeddedChannel.request() {
        writeInbound(DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/status"), LastHttpContent.EMPTY_LAST_CONTENT)
    }

    /** Writes an answer as the handler of Ktor writes it, and leaves it with Netty: the system has taken nothing of it. */
    private fun EmbeddedChannel.answer(): DefaultFullHttpResponse {
        val response =
            DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer("done", Charsets.US_ASCII))
        pipeline().context(HANDLER_OF_KTOR).write(response)
        runPendingTasks()
        return response
    }

    @Test
    fun theHandlerOfKtorGetsWhatArrivesAndIsToldWhenTheConnectionEnds() {
        val handler = HandlerOfKtor()
        val connection = connection(handler)

        connection.request()
        val endedWhileOpen = handler.ended
        connection.close()

        assertEquals(listOf(true, false), handler.read.map { it is HttpRequest }, "The head of the request, and then its end")
        assertSame(LastHttpContent.EMPTY_LAST_CONTENT, handler.read.last())
        assertFalse(endedWhileOpen)
        assertTrue(handler.ended, "The handler was told that the connection ended")
    }

    @Test
    fun aConnectionThatIsMadeWhileTheListenerStopsIsClosedWhenItIsSetUp() {
        val before = connection()

        connections.closeWhenIdle()
        before.runPendingTasks()
        val after = connection()
        val openWhileSetUp = after.isOpen
        after.runPendingTasks()

        assertFalse(before.isOpen, "The connection that was there when the listener stopped")
        assertTrue(openWhileSetUp, "The engine sets the connection up before it is closed")
        assertFalse(after.isOpen, "The connection that was made after the listener stopped")
    }

    @Test
    fun theConnectionsThatAreAbandonedAreTheOnesWithARequestInProgress() {
        val unused = connection()
        val answered = connection().also { it.request() }
        val waiting = connection().also { it.request() }
        val left = connection().also { it.request() }
        answered.answer()
        answered.flush()
        left.close()

        val abandoned = connections.close()

        assertEquals(1, abandoned, "Of four connections, one has a request in progress: the one that waits for its answer")
        assertEquals(listOf(false, false, false), listOf(unused, answered, waiting).map { it.isOpen })
        assertEquals(0, connections.close(), "A connection that is closed has no request in progress")
    }

    @Test
    fun aConnectionStaysUntilTheSystemHasTakenTheAnswerAndNotOnlyUntilTheAnswerIsWritten() {
        val connection = connection()
        connection.request()
        connections.closeWhenIdle()
        connection.runPendingTasks()
        val openWithTheRequest = connection.isOpen

        val response = connection.answer()
        val openWithTheAnswerWritten = connection.isOpen
        connection.flush()
        connection.runPendingTasks()

        assertTrue(openWithTheRequest, "The connection has a request in progress")
        assertTrue(openWithTheAnswerWritten, "The answer is written, and the system has not taken it")
        assertFalse(connection.isOpen, "The system has taken the answer")
        assertEquals(listOf("close"), response.headers().getAll("Connection"))
    }

    private companion object {
        const val HANDLER_OF_KTOR = "http1"
    }
}
