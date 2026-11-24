package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.history.DEFAULT_BODY_CAPTURE_LIMIT
import io.github.dnedrow.mockey.core.history.DEFAULT_HISTORY_LIMIT
import io.github.dnedrow.mockey.core.inspection.Inspector
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.storage.DefinitionStorage
import io.github.dnedrow.mockey.core.storage.InMemoryDefinitionStorage
import io.github.dnedrow.mockey.core.upstream.DEFAULT_CONNECT_TIMEOUT
import io.github.dnedrow.mockey.core.upstream.DEFAULT_RESPONSE_TIMEOUT
import io.github.dnedrow.mockey.core.upstream.UpstreamClient
import java.time.Clock
import kotlin.time.Duration

/**
 * What an engine is created with, see [MockeyEngine.create]. Every part has a default, so only what differs
 * from it needs to be set. An engine reads the options once, when it is created: a change to them afterwards
 * has no effect on it.
 *
 * Four parts are what the engine works with, and a program can put its own in their places: the [storage],
 * the [upstreamClient], the [inspectors], and the [clock].
 */
public class EngineOptions {
    /**
     * Where the engine keeps its definitions between runs. Without another storage, that is in memory only.
     * The engine starts with the definitions that the storage holds.
     */
    public var storage: DefinitionStorage = InMemoryDefinitionStorage()

    /**
     * Whether the engine starts in read-only mode, in which it keeps every change in memory and saves nothing
     * to its storage. The mode can be switched while the engine runs.
     */
    public var readOnly: Boolean = true

    /**
     * The client that sends forwarded requests to real services, or `null` for the client that the library
     * brings, which sends over HTTP. The engine closes its client when it is closed, also a client that is
     * given here.
     */
    public var upstreamClient: UpstreamClient? = null

    /**
     * The inspectors of the engine, or `null` for the inspectors that are installed on the class path. An
     * empty list is an engine without inspectors.
     */
    public var inspectors: List<Inspector>? = null

    /** The clock that tells the engine the time, such as the time of a history entry. Without another one, it is the system clock. */
    public var clock: Clock = Clock.systemUTC()

    /** The tag filter that the engine starts with. It can be changed while the engine runs. */
    public var tagFilter: Tags = Tags.EMPTY

    /**
     * Whether a request for an absolute target that no service knows is forwarded to that target, and a
     * service registered for it. Such a request gets the status 404 when this is off.
     */
    public var forwardUnknownTargets: Boolean = true

    /** How many entries the history keeps: 1,000 unless another number is set. With 0, the history keeps nothing. */
    public var historyLimit: Int = DEFAULT_HISTORY_LIMIT

    /**
     * How many bytes of a request body and of a response body a history entry holds: 1 MiB unless another
     * number is set. A body that is longer is cut in its entry. The response that a client gets never is.
     */
    public var bodyCaptureLimit: Int = DEFAULT_BODY_CAPTURE_LIMIT

    /**
     * How long a connection to a real service may take to come about: 10 seconds unless another time is
     * set. It counts for the client that the library brings: a client that is given here has its own.
     */
    public var connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT

    /** How long the complete answer of a real service may take: 60 seconds unless another time is set. */
    public var responseTimeout: Duration = DEFAULT_RESPONSE_TIMEOUT
}
