package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.OutboundProxyAttributesDto
import io.github.dnedrow.mockey.api.OutboundProxyDto
import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Settings
import io.ktor.http.HttpMethod

/**
 * The operations of the admin API on how [engine] reaches real services: the outbound proxy, through which
 * forwarded requests go while it is enabled, and the upstream session, which holds the cookies that real
 * services have set.
 *
 * The settings of the outbound proxy are a resource that is always there: without a URL there is no proxy.
 * A request states the attributes that change, see [OutboundProxyAttributesDto], as an update of a service
 * does, see [AdminServices].
 *
 * The password of the proxy goes one way. A request states it, and the engine holds it in memory. No answer
 * holds it, also not the problem of a request that is not valid: an answer says whether there is one. The
 * log gets nothing of a request, see [AdminRequest.jsonObject].
 *
 * The upstream session can be emptied and not read: its cookies are secrets of the real services.
 */
internal class AdminUpstream(
    private val engine: MockeyEngine,
) {
    /** The routes of the operations. */
    val routes: List<AdminRoute> =
        listOf(
            AdminRoute(HttpMethod.Get, PROXY) { AdminResponse.json(engine.definitions.settings.toAdminDto()) },
            AdminRoute(HttpMethod.Patch, PROXY) { update() },
            AdminRoute(HttpMethod.Delete, "system/upstream-session") {
                engine.clearUpstreamSession()
                AdminResponse.noContent()
            },
        )

    /**
     * Gives the outbound proxy the attributes that the request states, and answers with the settings as an
     * answer states them. The attributes are laid over the settings as they are, with their password, so
     * that a request which states no password leaves the password as it is.
     *
     * @throws AdminProblem with the status 422 when the engine takes the settings for none of an outbound
     *   proxy. The problem names the members of the request, and not the fields of the engine.
     */
    private suspend fun AdminRequest.update(): AdminResponse {
        val stated = attributes(OutboundProxyAttributesDto.serializer())
        val result = engine.updateOutboundProxy { current -> stated.on(current.attributes()).toModel() }
        if (result is ChangeResult.Rejected) {
            throw AdminProblem.invalid(result.failure.errors.map { FieldErrorDto(it.field.removePrefix("credentials."), it.message) })
        }
        return AdminResponse.json(result.orProblem().toAdminDto())
    }

    /** Returns what the admin API states of the outbound proxy of these settings. */
    private fun Settings.toAdminDto(): OutboundProxyDto = outboundProxy.toAdminDto()

    private companion object {
        const val PROXY = "upstream-proxy"
    }
}
