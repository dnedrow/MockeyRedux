package io.github.dnedrow.mockey.core.upstream

import io.github.dnedrow.mockey.core.definitions.errorScenarioFor
import io.github.dnedrow.mockey.core.definitions.outboundProxyInUse
import io.github.dnedrow.mockey.core.definitions.proxyAddressOf
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.response.MockResponse
import io.github.dnedrow.mockey.core.response.awaitResponseDelay
import io.github.dnedrow.mockey.core.response.builtInResponse
import io.github.dnedrow.mockey.core.response.response
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.time.Duration

/** How a request in proxy mode is answered. */
internal sealed interface ProxyAnswer {
    /** Returns the response that the client gets. */
    fun response(): MockResponse

    /** A real service answered the request. [relayed] is its answer as the client gets it. */
    data class Proxied(
        val relayed: MockResponse,
    ) : ProxyAnswer {
        override fun response(): MockResponse = relayed
    }

    /**
     * The real service gave no complete answer, and [failure] says what happened. [scenario] answers in the
     * place of the real service: the error scenario of the service, or else the universal error scenario.
     * Where neither is designated, the request gets the status 502 and a JSON object with two members: `fail`
     * with [failure], and `proxyInfo` with [proxyInfo], which says whether an outbound proxy is enabled.
     */
    data class UpstreamFailed(
        val failure: String,
        val scenario: Scenario?,
        val proxyInfo: String,
    ) : ProxyAnswer {
        override fun response(): MockResponse = scenario?.response() ?: failureResponse(failure, proxyInfo)
    }

    /**
     * The request was sent nowhere, because there is no URL of a real service for it. It gets the status 502
     * and the [explanation] as plain text.
     */
    data class NotForwarded(
        val explanation: String,
    ) : ProxyAnswer {
        override fun response(): MockResponse = builtInResponse(BAD_GATEWAY, explanation)
    }
}

private const val BAD_GATEWAY = 502

/**
 * Answers [request] of [service] in proxy mode, and returns the answer when it is time to send it. [target]
 * is where the request goes, see [forwardTarget].
 *
 * The request is forwarded through [client] within [session], through the outbound proxy while one is
 * enabled, and the answer of the real service is relayed, see [forward] and [relayed].
 *
 * An upstream failure is answered in the place of the real service. It is a failure when the real service
 * cannot be reached, when its certificate is not accepted, when it takes longer than the connect timeout of
 * [client] or than [responseTimeout], when it redirects without end, and when its answer is not complete.
 * These answer, in this order:
 *
 * 1. The error scenario of the service, with its status code, its headers, and its body.
 * 2. The universal error scenario, in the same way.
 * 3. The system itself, with the status 502 and a JSON object that describes the failure.
 *
 * A request whose [target] is nowhere is not forwarded, and no error scenario answers it.
 *
 * Every answer waits for the delay of the service before it is returned, also the answer of an error
 * scenario, whose own delay does not count here. The wait holds no thread, so it does not postpone the
 * handling of any other request.
 */
internal suspend fun Definitions.proxyAnswer(
    service: Service,
    request: MockRequest,
    target: ForwardTarget,
    client: UpstreamClient,
    session: UpstreamSession,
    responseTimeout: Duration = DEFAULT_RESPONSE_TIMEOUT,
): ProxyAnswer {
    val answer =
        when (target) {
            is ForwardTarget.Nowhere -> ProxyAnswer.NotForwarded(target.explanation)
            is ForwardTarget.To -> forwarded(service, request, target.url, client, session, responseTimeout)
        }
    awaitResponseDelay(service, scenario = null)
    return answer
}

/**
 * Forwards [request] of [service] to [url], and returns the answer of the real service, or what answers in its
 * place after an upstream failure.
 */
private suspend fun Definitions.forwarded(
    service: Service,
    request: MockRequest,
    url: String,
    client: UpstreamClient,
    session: UpstreamSession,
    responseTimeout: Duration,
): ProxyAnswer {
    val forwarding = upstreamRequest(request, url, outboundProxyInUse)
    return try {
        ProxyAnswer.Proxied(relayed(client.forward(forwarding, session, responseTimeout), request.method))
    } catch (e: IOException) {
        val failure = e.message ?: "${whereOf(forwarding)} gave no complete answer: ${e.javaClass.simpleName}."
        ProxyAnswer.UpstreamFailed(failure, errorScenarioFor(service), proxyInfo(forwarding.outboundProxy))
    }
}

/**
 * Returns what the built-in response to an upstream failure says about the outbound proxy: whether one is
 * enabled, and its host. [proxy] is the outbound proxy that the request went through, or `null` for none.
 */
internal fun proxyInfo(proxy: OutboundProxy?): String {
    if (proxy == null) return "No outbound proxy is enabled."
    val host =
        try {
            proxyAddressOf(proxy.url).host
        } catch (_: IllegalArgumentException) {
            return "An outbound proxy is enabled, but its URL names no host."
        }
    return "An outbound proxy is enabled. Its host is $host."
}

/**
 * Returns the response that the system gives on its own after an upstream failure: the status 502, and a
 * JSON object with the members `fail` and `proxyInfo`.
 */
private fun failureResponse(
    failure: String,
    proxyInfo: String,
): MockResponse {
    val body =
        buildJsonObject {
            put("fail", failure)
            put("proxyInfo", proxyInfo)
        }
    return MockResponse(BAD_GATEWAY, listOf(Header("Content-Type", "application/json")), body.toString().toByteArray(Charsets.UTF_8))
}
