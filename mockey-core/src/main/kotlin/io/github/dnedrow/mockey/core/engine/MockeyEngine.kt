package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.definitions.DefinitionImport
import io.github.dnedrow.mockey.core.definitions.Diagnostic
import io.github.dnedrow.mockey.core.definitions.ImportReport
import io.github.dnedrow.mockey.core.definitions.OutboundProxyChanges
import io.github.dnedrow.mockey.core.definitions.PlanApplyReport
import io.github.dnedrow.mockey.core.definitions.PlanChanges
import io.github.dnedrow.mockey.core.definitions.SearchResult
import io.github.dnedrow.mockey.core.definitions.TagChanges
import io.github.dnedrow.mockey.core.definitions.diagnostics
import io.github.dnedrow.mockey.core.definitions.search
import io.github.dnedrow.mockey.core.definitions.tagsInUse
import io.github.dnedrow.mockey.core.definitions.withoutProxyCredentials
import io.github.dnedrow.mockey.core.history.Exchange
import io.github.dnedrow.mockey.core.history.Handling
import io.github.dnedrow.mockey.core.history.HistoryEntry
import io.github.dnedrow.mockey.core.history.HistoryStore
import io.github.dnedrow.mockey.core.history.LastUsedTimes
import io.github.dnedrow.mockey.core.inspection.InspectorInfo
import io.github.dnedrow.mockey.core.inspection.Inspectors
import io.github.dnedrow.mockey.core.inspection.RuleSets
import io.github.dnedrow.mockey.core.inspection.inspectionMessages
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.OutboundProxy
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
import io.github.dnedrow.mockey.core.storage.DefinitionFiles
import io.github.dnedrow.mockey.core.storage.DefinitionStorage
import io.github.dnedrow.mockey.core.storage.FileFault
import io.github.dnedrow.mockey.core.storage.StorageException
import io.github.dnedrow.mockey.core.upstream.ForwardTarget
import io.github.dnedrow.mockey.core.upstream.UpstreamClient
import io.github.dnedrow.mockey.core.upstream.UpstreamSession
import io.github.dnedrow.mockey.core.upstream.forwardTarget
import io.github.dnedrow.mockey.core.upstream.proxyAnswer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * A mock engine. It holds definitions, and it handles the requests of clients as they say: a service answers
 * a request with one of its scenarios, or it forwards the request to a real service.
 *
 * An engine opens no network listener and needs no HTTP server. A program hands it a request as a value and
 * gets the response as a value, see [handle]. A server does the same with every request that reaches its
 * mock endpoint, so a request is handled alike in a program and in a server.
 *
 * A program creates an engine with [create], defines services, scenarios, plans, and twist configurations
 * with the operations of [services], [scenarios], [plans], and [twists], and reads what the engine did in
 * its [history]. An engine shares nothing with another engine: each has its own definitions, its own
 * history, its own settings, and its own cookies of real services.
 *
 * Any number of threads can use an engine at the same time. A request is handled under the definitions as
 * they are when it is routed: a change that is made meanwhile counts for the requests after it, and no
 * request sees a change that is half done.
 *
 * An engine is closed when it is no longer needed, see [close].
 */
public class MockeyEngine internal constructor(
    options: EngineOptions = EngineOptions(),
    initial: Definitions = Definitions.EMPTY,
) : AutoCloseable {
    private val responseTimeout: Duration = options.responseTimeout

    init {
        require(responseTimeout.isPositive()) { "The response timeout must be a time that passes." }
    }

    private val historyStore = HistoryStore(options.historyLimit, options.bodyCaptureLimit)

    /** The history of the requests that the engine handled. */
    public val history: HistoryOperations = HistoryOperations(this, historyStore)

    /** When each service, each scenario, and each plan of the engine was last used. */
    public val lastUsedTimes: LastUsedTimes = LastUsedTimes()

    /** The operations on the services of the engine. */
    public val services: ServiceOperations = ServiceOperations(this)

    /** The operations on the scenarios of the services of the engine. */
    public val scenarios: ScenarioOperations = ScenarioOperations(this)

    /** The operations on the plans of the engine. */
    public val plans: PlanOperations = PlanOperations(this)

    /** The operations on the twist configurations of the engine. */
    public val twists: TwistOperations = TwistOperations(this)

    private val storage: DefinitionStorage = options.storage

    @Volatile
    private var writable: Boolean = !options.readOnly

    /** Guards [closed] and [saving], so that a save either begins before the engine is closed or not at all. */
    private val gate = ReentrantLock()
    private val noSaveUnderWay = gate.newCondition()

    @Volatile
    private var closed = false
    private var saving = 0

    /** The storage is told of a change before the change takes effect, so a change that cannot be saved does not. */
    private val state =
        EngineState(
            initial,
            listOf(
                ChangeObserver { _, after ->
                    ensureOpen()
                    if (writable) save(after)
                },
            ),
        )
    private val snapshot = AtomicReference(Snapshot(initial))
    private val clock: Clock = options.clock
    private val installedInspectors: Inspectors = options.inspectors?.let(Inspectors::of) ?: Inspectors.installed()
    private val upstreamClient: UpstreamClient = options.upstreamClient ?: UpstreamClient.overHttp(options.connectTimeout)

    /** Whether the upstream client is the one of the library, which the engine made, and not one that the program gave. */
    private val ownsUpstreamClient: Boolean = options.upstreamClient == null
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
        ensureOpen()
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
     * @throws IllegalStateException when the engine is closed
     */
    public suspend fun handle(
        request: MockRequest,
        clientAddress: String? = null,
    ): HandledRequest {
        ensureOpen()
        val time = clock.instant()
        val received = requireNotNull(targetOf(request.url)) { "$request is no request to the mock endpoint: $NO_MOCK_TRAFFIC" }
        val routing = routed(received)
        val answer = answered(routing, request)
        val exchange = Exchange(time, clientAddress, request, received, routing.target, answer.handling, answer.inspectionMessages)
        val entry = historyStore.record(exchange)
        lastUsedTimes.used(answer.handling, time)
        return HandledRequest(answer.handling.response, entry)
    }

    /**
     * Loads the definitions from the storage and puts them in the place of the definitions in memory. What
     * was changed in memory and not saved is gone after that, and so are the credentials of the outbound
     * proxy, which are in memory only. The history, the last-used times, and the tag filter stay as they are.
     *
     * The default plan of the loaded definitions is applied, as it is when an engine is created. In writable
     * mode, what the plan changes is saved.
     *
     * @throws StorageException when the definitions cannot be loaded, such as when a file of them cannot be
     *   read. The definitions in memory stay as they are then.
     */
    public suspend fun reload() {
        ensureOpen()
        var applied: PlanApplyReport? = null
        state.replace {
            val loaded = load()
            val withPlan = PlanChanges.withDefaultPlanApplied(loaded)
            if (writable && withPlan.definitions != loaded) save(withPlan.definitions)
            applied = withPlan.value
            withPlan.definitions
        }
        applied?.let { lastUsedTimes.planApplied(it.plan.id, clock.instant()) }
    }

    /**
     * Makes a clean slate: removes every service with its scenarios, every plan, and every twist
     * configuration, clears the settings of the outbound proxy and the designations of the universal error
     * scenario and of the default plan, clears the tag filter, and empties the history and the last-used
     * times. In writable mode, the storage holds no definitions after that either.
     *
     * @throws StorageException when the empty definitions cannot be saved. Nothing is cleared then.
     */
    public suspend fun reset() {
        change { ChangeResult.Changed(Definitions.EMPTY, Unit) }
        tagFilter = Tags.EMPTY
        historyStore.clear()
        lastUsedTimes.clear()
    }

    /**
     * Returns the definitions as one document: a JSON text with everything that a storage holds of them. The
     * credentials of the outbound proxy are no part of it. [importDocument] takes such a document.
     */
    public fun exportDefinitions(): String = DefinitionFiles.documentText(state.current)

    /**
     * Imports the definitions of [document], which is a text as [exportDefinitions] gives it, and returns
     * the report of the import, see [importDefinitions]. A text that is no such document is rejected, and
     * the definitions stay as they are.
     *
     * @throws StorageException when the engine is in writable mode and the definitions cannot be saved
     */
    public suspend fun importDocument(
        document: String,
        tags: Tags = Tags.EMPTY,
    ): ChangeResult<ImportReport> {
        val definitions =
            try {
                DefinitionFiles.document(document)
            } catch (e: FileFault) {
                return ChangeResult.Rejected(
                    ValidationFailure(listOf(FieldError("document", "The document cannot be imported: ${e.problem}"))),
                )
            }
        return imported(definitions, tags)
    }

    /**
     * Imports [definitions] into the definitions of the engine and returns the report, which names every
     * part of [definitions] as an addition or as a conflict.
     *
     * - A service whose name no service has is added with its scenarios, unless another service has its
     *   mock URL pattern. Names are compared without regard to letter case and surrounding whitespace.
     * - A service whose name a service has is merged into that service, which gets the scenarios that it
     *   does not have with the same name and response body, the real URLs, and the tags. Nothing else of
     *   the existing service changes.
     * - A plan and a twist configuration are added when their name is not in use.
     * - The settings of the outbound proxy, the universal error scenario, the default plan, and the
     *   enabled twist configuration are taken where the engine has none. A default plan that the import
     *   designates is applied.
     *
     * What is added keeps its identifier, unless a definition of the engine has it already: then it gets a
     * new one. [tags] are added to every service, scenario, and plan that is added, and to every service
     * that is merged into.
     *
     * @throws StorageException when the engine is in writable mode and the definitions cannot be saved.
     *   Nothing is imported then.
     */
    public suspend fun importDefinitions(
        definitions: Definitions,
        tags: Tags = Tags.EMPTY,
    ): ImportReport = imported(definitions, tags).value

    private suspend fun imported(
        definitions: Definitions,
        tags: Tags,
    ): ChangeResult.Changed<ImportReport> {
        val result = change(DefinitionImport.import(definitions, tags))
        check(result is ChangeResult.Changed) { "An import changes the definitions or leaves them as they are. It does not give $result." }
        result.value.appliedDefaultPlan?.let { lastUsedTimes.planApplied(it.plan.id, clock.instant()) }
        return result
    }

    /**
     * Changes the settings of the outbound proxy, through which forwarded requests go while it is enabled.
     * [update] gets the settings as they are, with their credentials, or `null` when there are none. It
     * returns what the settings are to be, or `null` for none.
     *
     * The credentials are held in memory only: they are never saved, and no export holds them. Settings
     * with a URL that names no proxy are rejected.
     */
    public suspend fun updateOutboundProxy(update: (OutboundProxy?) -> OutboundProxy?): ChangeResult<OutboundProxy?> =
        change(OutboundProxyChanges.update(update))

    /** Returns every tag that a service, a scenario, or a plan carries. */
    public fun tagsInUse(): Tags = state.current.tagsInUse()

    /** Removes [tag] from every service, scenario, and plan. A text with several tags removes each of them. */
    public suspend fun removeTag(tag: String) {
        change(TagChanges.remove(tag))
    }

    /**
     * Returns the plans, the services, and the scenarios that hold [term] in their names, their tags, their
     * URLs, or their response bodies, without regard to letter case. The plans come first, and each service
     * is followed by its scenarios.
     */
    public fun search(term: String): List<SearchResult> = state.current.search(term)

    /**
     * Returns what deserves attention in the definitions and blocks nothing: services that share a URL,
     * scenarios of one service with the same name and response body, a service that names an inspector
     * which the engine does not have, a rule set that is not well formed, and a response body that does not
     * conform to the response schema of its service while that schema is enabled.
     */
    public fun diagnostics(): List<Diagnostic> = state.current.diagnostics(installedInspectors.names)

    /** The inspectors of the engine in the order of their names, each with its name and whether it inspects every service. */
    public val inspectors: List<InspectorInfo>
        get() = installedInspectors.list()

    /** What kept an inspector from being installed, one text for each inspector that the engine does without. */
    public val inspectorProblems: List<String>
        get() = installedInspectors.problems

    /**
     * Empties the upstream session: the cookies that real services have set, which go with every forwarded
     * request that they apply to. No request carries a cookie until a real service sets one again.
     */
    public fun clearUpstreamSession() {
        upstreamSession.clear()
    }

    /**
     * Closes the engine. A save that is under way is completed first: in writable mode, every change that
     * takes effect is in the storage when this function returns. Then the upstream client is closed, which
     * releases its connections. The function blocks its thread until all of that is done.
     *
     * A closed engine handles no request and leaves its definitions as they are: [handle] and every
     * operation that changes, saves, or loads definitions fail with an [IllegalStateException]. What the
     * engine holds can still be read. Closing an engine that is closed does nothing.
     */
    override fun close() {
        shutDown(closesClient = true)
    }

    /**
     * Ends the work of the engine: lets no save begin from now on and waits for every save that is under
     * way. The first call closes the upstream client as well, when [closesClient] says so.
     */
    private fun shutDown(closesClient: Boolean) {
        val first =
            gate.withLock {
                val wasOpen = !closed
                closed = true
                while (saving > 0) noSaveUnderWay.awaitUninterruptibly()
                wasOpen
            }
        if (first && closesClient) upstreamClient.close()
    }

    /**
     * Applies [change] to the definitions and returns its result, see [EngineState.change]. In writable mode
     * the changed definitions are saved before they take effect.
     *
     * @throws StorageException when the changed definitions cannot be saved. They do not take effect then.
     * @throws IllegalStateException when the engine is closed
     */
    internal suspend fun <T> change(change: Change<T>): ChangeResult<T> {
        ensureOpen()
        return state.change(change)
    }

    /** Returns the time that the clock of the engine tells. */
    internal fun now(): Instant = clock.instant()

    private fun ensureOpen() {
        check(!closed) { CLOSED }
    }

    /** Returns the definitions that the storage holds. */
    private suspend fun load(): Definitions =
        try {
            storage.load()
        } catch (e: StorageException) {
            throw e
        } catch (e: IOException) {
            throw StorageException("The definitions could not be loaded: ${e.message ?: e.javaClass.simpleName}", e)
        }

    /**
     * Saves [definitions] to the storage, without the credentials of the outbound proxy, which are held in
     * memory only. No save begins once the engine is closed.
     *
     * The save runs apart from the thread of the caller, and so does what marks its end. Whoever closes the
     * engine waits for that end, and may do so on the very thread on which the caller would go on.
     */
    private suspend fun save(definitions: Definitions) {
        try {
            withContext(Dispatchers.IO) {
                gate.withLock {
                    check(!closed) { CLOSED }
                    saving++
                }
                try {
                    storage.save(definitions.withoutProxyCredentials())
                } finally {
                    gate.withLock {
                        saving--
                        noSaveUnderWay.signalAll()
                    }
                }
            }
        } catch (e: StorageException) {
            throw e
        } catch (e: IOException) {
            throw StorageException("The definitions could not be saved: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

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
                val messages = inspectionMessages(route.service, request, installedInspectors, routing.snapshot.ruleSets)
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

    public companion object {
        private const val NO_MOCK_TRAFFIC = "the path of its URL does not begin with /service/."
        private const val CLOSED = "The engine is closed."

        /**
         * Creates an engine. [configure] sets what differs from the defaults of [EngineOptions], such as
         * `MockeyEngine.create { storage = FileDefinitionStorage(directory) }`. An engine that is given
         * nothing keeps its definitions in memory, forwards requests with the upstream client that the
         * library brings, has the inspectors that are installed on the class path, and tells the time by
         * the system clock.
         *
         * The engine starts with the definitions that its storage holds, with their default plan applied,
         * and in read-only mode unless the options say otherwise.
         *
         * @throws StorageException when the definitions cannot be loaded. No engine is created then, and
         *   an upstream client that the options name stays open.
         */
        public suspend fun create(configure: EngineOptions.() -> Unit = {}): MockeyEngine = create(EngineOptions().apply(configure))

        /** Creates an engine with [options], as [create] does with the options that it is told. */
        internal suspend fun create(options: EngineOptions): MockeyEngine {
            val engine = MockeyEngine(options)
            try {
                engine.reload()
            } catch (e: Exception) {
                // No engine comes about that could close a client of the program later, so that client stays the program's.
                engine.shutDown(closesClient = engine.ownsUpstreamClient)
                throw e
            }
            return engine
        }
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
