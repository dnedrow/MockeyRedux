package io.github.dnedrow.mockey.core.routing

import io.github.dnedrow.mockey.core.model.Service

/** What becomes of a request for [target]: the outcome of routing. */
internal sealed interface Route {
    val target: Target

    /**
     * [service] handles the request. [variables] are the values of the variables of the URL pattern through
     * which the target reaches the service.
     */
    data class ToService(
        override val target: Target,
        val service: Service,
        val variables: Map<String, String>,
    ) : Route

    /**
     * No service handles the request, and its target is the URL of a real service: the request is forwarded
     * to the target as a request in proxy mode. With [registers], no service knows the target at all, and a
     * service is registered for it before the request is forwarded, see [TargetRegistration].
     */
    data class Forward(
        override val target: Target,
        val registers: Boolean,
    ) : Route

    /**
     * No service handles the request, and it is not forwarded either. The request is answered with the
     * status 404 and the [explanation], which names the target.
     */
    data class NotFound(
        override val target: Target,
        val explanation: String,
    ) : Route
}
