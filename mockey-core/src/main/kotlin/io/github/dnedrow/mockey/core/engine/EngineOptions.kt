package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.history.DEFAULT_BODY_CAPTURE_LIMIT
import io.github.dnedrow.mockey.core.history.DEFAULT_HISTORY_LIMIT
import io.github.dnedrow.mockey.core.inspection.Inspector
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.upstream.DEFAULT_CONNECT_TIMEOUT
import io.github.dnedrow.mockey.core.upstream.DEFAULT_RESPONSE_TIMEOUT
import io.github.dnedrow.mockey.core.upstream.UpstreamClient
import java.time.Clock
import kotlin.time.Duration

/**
 * What an engine is created with. Every part has a default, so only what differs from it needs to be set. An
 * engine reads the options once, when it is created: a change to them afterwards has no effect on it.
 */
internal class EngineOptions {
    /**
     * The client that sends forwarded requests to real services, or `null` for the client that the library
     * brings, which sends over HTTP.
     */
    var upstreamClient: UpstreamClient? = null

    /**
     * The inspectors of the engine, or `null` for the inspectors that are installed on the class path. An
     * empty list is an engine without inspectors.
     */
    var inspectors: List<Inspector>? = null

    /** The clock that tells the engine the time, such as the time of a history entry. */
    var clock: Clock = Clock.systemUTC()

    /** The tag filter that the engine starts with. It can be changed while the engine runs. */
    var tagFilter: Tags = Tags.EMPTY

    /**
     * Whether a request for an absolute target that no service knows is forwarded to that target, and a
     * service registered for it. Such a request gets the status 404 when this is off.
     */
    var forwardUnknownTargets: Boolean = true

    /** How many entries the history keeps. */
    var historyLimit: Int = DEFAULT_HISTORY_LIMIT

    /** How many bytes of a request body and of a response body a history entry holds. */
    var bodyCaptureLimit: Int = DEFAULT_BODY_CAPTURE_LIMIT

    /**
     * How long a connection to a real service may take to come about. It counts for the client that the
     * library brings: a client that is given here has its own.
     */
    var connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT

    /** How long the complete answer of a real service may take. */
    var responseTimeout: Duration = DEFAULT_RESPONSE_TIMEOUT
}
