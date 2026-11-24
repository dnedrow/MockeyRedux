package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.definitions.withoutProxyCredentials
import io.github.dnedrow.mockey.core.history.Exchange
import io.github.dnedrow.mockey.core.history.Handling
import io.github.dnedrow.mockey.core.history.HistoryEntry
import io.github.dnedrow.mockey.core.history.HistoryEntryId
import io.github.dnedrow.mockey.core.history.HistoryStore
import io.github.dnedrow.mockey.core.history.LastUsedTimes
import io.github.dnedrow.mockey.core.history.Replay
import io.github.dnedrow.mockey.core.history.ReplayEdits
import io.github.dnedrow.mockey.core.history.replay
import io.github.dnedrow.mockey.core.inspection.Inspectors
import io.github.dnedrow.mockey.core.inspection.RuleSets
import io.github.dnedrow.mockey.core.inspection.inspectionMessages
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.response.MockResponse
import io.github.dnedrow.mockey.core.response.ScenarioAnswer
import io.github.dnedrow.mockey.core.response.awaitResponseDelay
import io.github.dnedrow.mockey.core.response.dynamicAnswer
import io.github.dnedrow.mockey.core.response.staticAnswer
import io.github.dnedrow.mockey.core.routing.Route
import io.github.dnedrow.mockey.core.routing.RoutingTable
import io.github.dnedrow.mockey.core.routing.Target
import io.github.dnedrow.mockey.core.routing.TargetRegistration
import io.github.dnedrow.mockey.core.routing.targetOf
import io.github.dnedrow.mockey.core.routing.twisted
import io.github.dnedrow.mockey.core.storage.DefinitionStorage
import io.github.dnedrow.mockey.core.storage.StorageException
import io.github.dnedrow.mockey.core.upstream.ForwardTarget
import io.github.dnedrow.mockey.core.upstream.JdkUpstreamClient
import io.github.dnedrow.mockey.core.upstream.UpstreamClient
import io.github.dnedrow.mockey.core.upstream.UpstreamSession
import io.github.dnedrow.mockey.core.upstream.forwardTarget
import io.github.dnedrow.mockey.core.upstream.proxyAnswer
import java.io.IOException
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * A mock engine. It holds definitions, and it handles the requests of clients as they say: a service answers
 * a request with one of its scenarios, or it forwards the request to a real service.
 *
 * An engine opens no network listener and needs no HTTP server. A program hands it a request as a value and
 * gets the response as a value, see [handle]. A server does the same with every request that reaches its
 * mock endpoint, so a request is handled alike in a program and in a server.
 *
 * Any number of threads can use an engine at the same time. A request is handled under the definitions as
 * they are when it is routed: a change that is made meanwhile counts for the requests after it, and no
 * request sees a change that is half done.
 */
public class MockeyEngine internal constructor(
    options: EngineOptions = EngineOptions(),
    initial: Definitions = Definitions.EMPTY,
) {
    private val responseTimeout: Duration = options.responseTimeout

    init {
        require(responseTimeout.isPositive()) { "The response timeout must be a time that passes." }
    }

    internal val history = HistoryStore(options.historyLimit, options.bodyCaptureLimit)
    internal val lastUsedTimes = LastUsedTimes()

    private val storage: DefinitionStorage = options.storage

    @Volatile
    private var writable: Boolean = !options.readOnly

    /** The storage is told of a change before the change takes effect, so a change that cannot be saved does not. */
    private val state = EngineState(initial, listOf(ChangeObserver { _, after -> if (writable) save(after) }))
    private val snapshot = AtomicReference(Snapshot(initial))
    private val clock: Clock = options.clock
    private val inspectors: Inspectors = options.inspectors?.let(Inspectors::of) ?: Inspectors.installed()
    private val upstreamClient: UpstreamClient = options.upstreamClient ?: JdkUpstreamClient(options.connectTimeout)
    private val upstreamSession = UpstreamSession(clock::instant)

    /**
     * Whether a request for an absolute target that no service knows is forwarded to that target, and a
     * service registered for it. Such a request gets the status 404 when this is off.
     */
    public val forwardUnknownTargets: Boolean = options.forwardUnknownTargets

    /**
     * The tag filter. While it holds tags, a request goes only to a service that carries one of them, or one
     * of whose scenarios does, and a service that is registered for an unknown target gets these tags. A
     * filter without tags, which is the default, lets a request go to any service.
     *
     * A new filter counts for the requests that are routed after it was set.
     */
    @Volatile
    public var tagFilter: Tags = options.tagFilter

    /** The definitions as they are now. */
    public val definitions: Definitions
        get() = state.current

    /**
     * Whether the engine is in read-only mode. In that mode it keeps every change in memory and saves nothing
     * to its storage: what the storage holds stays as it is. In writable mode it saves every change before
     * the operation that made the change returns. See [setReadOnly].
     */
    public val readOnly: Boolean
        get() = !writable

    /**
     * Switches read-only mode on or off. Nothing happens when the engine is in that mode already.
     *
     * Switching the mode off saves the definitions as they are now, with every change that was made in
     * read-only mode. The engine is in writable mode when this function returns. When the definitions cannot
     * be saved, the engine stays in read-only mode.
     *
     * @throws StorageException when read-only mode is switched off and the definitions cannot be saved
     */
    public suspend fun setReadOnly(readOnly: Boolean) {
        state.exclusively { definitions ->
            if (readOnly != this.readOnly) {
                if (!readOnly) save(definitions)
                writable = !readOnly
            }
        }
    }

    /** The routing table of the definitions as they are now. */
    internal val routingTable: RoutingTable
        get() = snapshotOf(state.current).routes

    /**
     * Handles [request], which a client sent to the mock endpoint, and returns the response for that client
     * together with the history entry of the request. [clientAddress] is the address of the client, which the
     * history entry states.
     *
     * The URL of the request names its target: what follows `/service/` in the path of the URL. The request
     * takes these steps:
     *
     * 1. The twist configuration that is enabled rewrites the target.
     * 2. The request is routed to the service that the target reaches best, among the services that the
     *    [tagFilter] admits. A request for an absolute target that reaches no service is forwarded to that
     *    target, unless [forwardUnknownTargets] is off. When no service knows the target at all, a service
     *    is registered for it first. Every other request that reaches no service gets the status 404.
     * 3. The inspectors and the request rules of the service inspect the request. What they find is in the
     *    history entry, and it has no say in the response.
     * 4. The service answers in its mode: it forwards the request to its real service, it answers with its
     *    default scenario, or it answers with the scenario that matches the request best.
     * 5. The response waits for the delay of its scenario or of its service. The wait holds no thread.
     * 6. The history records the request with its response.
     *
     * @throws IllegalArgumentException when the path of the URL of [request] does not begin with `/service/`
     */
    public suspend fun handle(
        request: MockRequest,
        clientAddress: String? = null,
    ): HandledRequest {
        val time = clock.instant()
        val received = requireNotNull(targetOf(request.url)) { "$request is no request to the mock endpoint: $NO_MOCK_TRAFFIC" }
        val routing = routed(received)
        val answer = answered(routing, request)
        val exchange = Exchange(time, clientAddress, request, received, routing.target, answer.handling, answer.inspectionMessages)
        val entry = history.record(exchange)
        lastUsedTimes.used(answer.handling, time)
        return HandledRequest(answer.handling.response, entry)
    }

    /**
     * Applies [change] to the definitions and returns its result, see [EngineState.change]. In writable mode
     * the changed definitions are saved before they take effect.
     *
     * @throws StorageException when the changed definitions cannot be saved. They do not take effect then.
     */
    internal suspend fun <T> change(change: Change<T>): ChangeResult<T> = state.change(change)

    /**
     * Saves [definitions] to the storage, without the credentials of the outbound proxy, which are held in
     * memory only.
     */
    private suspend fun save(definitions: Definitions) {
        try {
            storage.save(definitions.withoutProxyCredentials())
        } catch (e: StorageException) {
            throw e
        } catch (e: IOException) {
            throw StorageException("The definitions could not be saved: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /**
     * Replays the history entry with the identifier [id]: handles a request like the one of that entry, with
     * what [edits] change of it. The replay is a request like any other: it is handled under the definitions
     * as they are now, and it gets a history entry of its own, which states [clientAddress].
     */
    internal suspend fun replay(
        id: HistoryEntryId,
        edits: ReplayEdits = ReplayEdits(),
        clientAddress: String? = null,
    ): Replay<HandledRequest> = history.replay(id, edits) { handle(it, clientAddress) }

    /**
     * Routes a request for the target [received], which is the target as the client wrote it. The route, the
     * target after twisting, and the definitions that the request is handled under belong together: all of
     * them are of one value of the definitions.
     */
    private suspend fun routed(received: Target): Routing {
        val filter = tagFilter
        val snapshot = snapshotOf(state.current)
        val target = snapshot.definitions.twisted(received)
        val route = snapshot.routes.route(target, filter, forwardUnknownTargets)
        val registers = route is Route.Forward && route.registers
        return (if (registers) registered(received, filter) else null) ?: Routing(snapshot, target, route)
    }

    /**
     * Registers a service for the unknown target [received] and returns how the request is routed after
     * that, or `null` when no service was registered and the request stays on the route that it has.
     *
     * The registration is a change to the definitions. It twists the target and routes the request again in
     * the definitions that it gets, because they can have changed since the request was routed: another
     * request for the same target can have registered the service by then.
     *
     * A service that cannot be saved is not registered. The request is forwarded all the same, because the
     * client has no part in what the storage fails at.
     */
    private suspend fun registered(
        received: Target,
        filter: Tags,
    ): Routing? {
        val registration: Change<Pair<Target, Route>> = { definitions ->
            val target = definitions.twisted(received)
            TargetRegistration.register(target, filter) { snapshotOf(it).routes }(definitions).map { route -> target to route }
        }
        val result =
            try {
                state.change(registration)
            } catch (_: StorageException) {
                return null
            }
        return (result as? ChangeResult.Changed)?.let { Routing(snapshotOf(it.definitions), it.value.first, it.value.second) }
    }

    /** Answers [request] as its [routing] has it, and returns how it was handled when it is time to send the response. */
    private suspend fun answered(
        routing: Routing,
        request: MockRequest,
    ): Answer {
        val definitions = routing.snapshot.definitions
        return when (val route = routing.route) {
            is Route.ToService -> {
                val messages = inspectionMessages(route.service, request, inspectors, routing.snapshot.ruleSets)
                Answer(answeredByService(routing, route, request), messages)
            }

            is Route.Forward -> {
                Answer(Handling.of(service = null, definitions.forwarded(null, request, forwardTarget(route.target, request.query))))
            }

            is Route.NotFound -> {
                Answer(Handling.noServiceMatched(route.explanation))
            }
        }
    }

    /** Lets the service of [route] answer [request] in its mode. */
    private suspend fun answeredByService(
        routing: Routing,
        route: Route.ToService,
        request: MockRequest,
    ): Handling {
        val definitions = routing.snapshot.definitions
        val service = route.service
        return when (service.effectiveResponseMode) {
            ResponseMode.PROXY -> {
                val target = forwardTarget(service, route.target, route.variables, request.query)
                Handling.of(service, definitions.forwarded(service, request, target))
            }

            ResponseMode.STATIC -> {
                afterDelay(service, staticAnswer(service))
            }

            ResponseMode.DYNAMIC -> {
                afterDelay(service, definitions.dynamicAnswer(service, route.variables, request, routing.snapshot.ruleSets))
            }
        }
    }

    private suspend fun Definitions.forwarded(
        service: Service?,
        request: MockRequest,
        target: ForwardTarget,
    ) = proxyAnswer(service, request, target, upstreamClient, upstreamSession, responseTimeout)

    /** Returns how [service] handled a request that it answers with [answer], after the delay of that answer. */
    private suspend fun afterDelay(
        service: Service,
        answer: ScenarioAnswer,
    ): Handling {
        awaitResponseDelay(service, answer.scenario)
        return Handling.of(service, answer)
    }

    /**
     * Returns [definitions] with what is compiled from them. The engine keeps that for the definitions that
     * were last asked for, so one value of the definitions is compiled once, however many requests are
     * handled under it.
     */
    private fun snapshotOf(definitions: Definitions): Snapshot {
        while (true) {
            val kept = snapshot.get()
            if (kept.definitions === definitions) return kept
            val fresh = Snapshot(definitions)
            if (snapshot.compareAndSet(kept, fresh)) return fresh
        }
    }

    /**
     * One value of the definitions with what is compiled from it: the routing table and the rule sets. Each
     * is compiled once, when a request first needs it.
     */
    private class Snapshot(
        val definitions: Definitions,
    ) {
        val routes: RoutingTable by lazy { RoutingTable(definitions) }
        val ruleSets: RuleSets by lazy { RuleSets(definitions) }
    }

    /** How a request is routed: its [target] after twisting and its [route], under the definitions of [snapshot]. */
    private class Routing(
        val snapshot: Snapshot,
        val target: Target,
        val route: Route,
    )

    /** How a request was handled, and what its inspection found. */
    private class Answer(
        val handling: Handling,
        val inspectionMessages: List<String> = emptyList(),
    )

    private companion object {
        const val NO_MOCK_TRAFFIC = "the path of its URL does not begin with /service/."
    }
}

/**
 * A request that an engine has handled: the [response] for the client, and the [entry] that the history holds
 * of the request. The entry says which service handled the request, in which mode, and with which scenario.
 * Its copy of the response body can be cut short. The body of [response] never is.
 */
public class HandledRequest internal constructor(
    public val response: MockResponse,
    public val entry: HistoryEntry,
) {
    /** Returns the identifier of the entry, the outcome, and the status. The rest is left out, because it can hold secrets. */
    override fun toString(): String = "HandledRequest(entry=${entry.id}, outcome=${entry.outcome}, status=${response.status})"
}
