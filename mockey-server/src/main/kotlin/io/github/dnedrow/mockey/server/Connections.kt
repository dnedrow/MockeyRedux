package io.github.dnedrow.mockey.server

import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.channel.ChannelPromise
import io.netty.channel.group.DefaultChannelGroup
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.LastHttpContent
import io.netty.util.concurrent.GlobalEventExecutor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * The connections of a listener, and the requests that are in progress on them. With it a listener that
 * stops lets every request finish that it has taken: it closes a connection when no request is in progress
 * on it, and not before.
 *
 * A request is in progress from the moment that its head has arrived, until the request has arrived whole
 * and the last byte of the answer to it is handed to the system. The engine does not tell when that is: it
 * calls a request done when its handler is, which is before the answer is written. So a handler in the
 * pipeline of each connection counts the requests and the answers as Netty reads and writes them. It stands
 * right before the handler of Ktor for HTTP/1.1, where it sees every answer that the server writes.
 */
internal class Connections {
    private val open = DefaultChannelGroup(GlobalEventExecutor.INSTANCE)

    /** The number of connections on which a request is in progress. */
    private val busy = AtomicInteger()

    @Volatile
    private var closing = false

    /** Puts the handler that watches the requests into [pipeline], which the engine has set up for a new connection. */
    fun watch(pipeline: ChannelPipeline) {
        pipeline.addBefore(HANDLER_OF_KTOR, "requests-in-progress", Requests())
    }

    /**
     * Closes every connection as soon as no request is in progress on it, which is at once for a connection
     * without one. A connection that is made from now on is closed at once.
     *
     * The answer to the last request of a connection says that the connection closes, so that its client
     * sends no further request on it.
     */
    fun closeWhenIdle() {
        closing = true
        for (channel in open) {
            channel.eventLoop().execute { channel.pipeline().get(Requests::class.java)?.settle() }
        }
    }

    /** Waits until every connection is closed, for [timeout] at most. */
    fun awaitClosed(timeout: Duration) {
        open.newCloseFuture().await(timeout.inWholeMilliseconds)
    }

    /**
     * Closes every connection at once, also one with a request in progress, which gets no answer. Returns the
     * number of connections that had one.
     */
    fun close(): Int {
        val abandoned = busy.get()
        open.close().awaitUninterruptibly()
        return abandoned
    }

    /** Watches the requests of one connection. Netty calls it on the thread of that connection alone. */
    private inner class Requests : ChannelDuplexHandler() {
        private lateinit var connection: ChannelHandlerContext

        /** The number of requests whose head has arrived and whose answer the server has not written to its end. */
        private var unwritten = 0

        /** The number of requests whose head has arrived and whose answer the system has not taken to its end. */
        private var unanswered = 0

        /** Whether the request that arrived last has not arrived whole. */
        private var arriving = false

        /** Whether the connection is among the [busy] ones. */
        private var counted = false

        override fun handlerAdded(context: ChannelHandlerContext) {
            connection = context
            open.add(context.channel())
            // A connection that was made while the listener stopped has no request yet. It is closed when the engine has set it up.
            if (closing) context.executor().execute(::settle)
        }

        override fun channelRead(
            context: ChannelHandlerContext,
            message: Any,
        ) {
            if (message is HttpRequest) {
                unwritten++
                unanswered++
                arriving = true
                count(true)
            }
            val last = message is LastHttpContent
            context.fireChannelRead(message)
            if (last) {
                arriving = false
                settle()
            }
        }

        override fun write(
            context: ChannelHandlerContext,
            message: Any,
            promise: ChannelPromise,
        ) {
            // The answer to the only request that is left is the last one of the connection, which the listener closes after it.
            if (message is HttpResponse && closing && unwritten == 1) HttpUtil.setKeepAlive(message, false)
            if (message !is LastHttpContent) {
                context.write(message, promise)
                return
            }
            if (unwritten > 0) unwritten--
            // The promise is kept when the system has taken the last byte of the answer, or when it cannot be written.
            val taken = promise.unvoid()
            taken.addListener {
                if (unanswered > 0) unanswered--
                settle()
            }
            context.write(message, taken)
        }

        override fun channelInactive(context: ChannelHandlerContext) {
            count(false)
            context.fireChannelInactive()
        }

        /** Sees whether a request is in progress, and closes the connection when none is and the listener is closing them. */
        fun settle() {
            if (unanswered > 0 || arriving) return
            count(false)
            if (closing) connection.close()
        }

        /** Counts the connection among the busy ones or takes it out of them, as [inProgress] says. */
        private fun count(inProgress: Boolean) {
            if (counted == inProgress) return
            counted = inProgress
            if (inProgress) busy.incrementAndGet() else busy.decrementAndGet()
        }
    }

    private companion object {
        /** The name that the Netty engine of Ktor gives its handler for HTTP/1.1, which every listener speaks. */
        const val HANDLER_OF_KTOR = "http1"
    }
}
