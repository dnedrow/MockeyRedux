package io.github.dnedrow.mockey.core.routing

import io.github.dnedrow.mockey.core.definitions.ServiceChanges
import io.github.dnedrow.mockey.core.engine.Change
import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.Tags

/** The change that registers a service for an unknown target. */
internal object TargetRegistration {
    /**
     * Registers a service for [target] when the route of a request for it is a [Route.Forward] that registers,
     * and yields the route that the request takes after that.
     *
     * The new service forwards requests: it is in proxy mode, and [target] is its mock URL pattern and its
     * only real URL. It is named after its real URL, and it carries the tags of [tagFilter], so that the filter
     * does not hide it. The request goes to that service.
     *
     * The change routes the request again in the definitions that it gets, because they can have changed
     * since the request was routed. When a service knows the target by then, such as the one that another
     * request for the same target has registered, no service is registered and the request takes its new
     * route. A target for which no valid service can be defined, such as one whose name would be too long, is
     * forwarded without a service.
     *
     * [tableOf] gives the routing table of the definitions that the change gets. Whoever keeps such tables
     * hands them out here, so that the change compiles none of its own.
     */
    fun register(
        target: Target,
        tagFilter: Tags,
        tableOf: (Definitions) -> RoutingTable = ::RoutingTable,
    ): Change<Route> =
        { definitions ->
            val route = tableOf(definitions).route(target, tagFilter, forwardUnknownTargets = true)
            val created = if (route is Route.Forward && route.registers) proxyService(target, tagFilter)(definitions) else null
            when {
                created is ChangeResult.Changed -> ChangeResult.Changed(created.definitions, routeTo(created.value, target))
                route is Route.Forward -> ChangeResult.Changed(definitions, route.copy(registers = false))
                else -> ChangeResult.Changed(definitions, route)
            }
        }

    private fun proxyService(
        target: Target,
        tagFilter: Tags,
    ): Change<Service> = ServiceChanges.create { it.copy(mockUrl = target.text, realUrls = listOf(target.text), tags = tagFilter) }

    private fun routeTo(
        service: Service,
        target: Target,
    ): Route = Route.ToService(target, service, UrlPattern.compile(service.mockUrl).match(target).orEmpty())
}
