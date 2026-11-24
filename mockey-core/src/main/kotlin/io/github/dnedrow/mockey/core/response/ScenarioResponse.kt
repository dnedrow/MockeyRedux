package io.github.dnedrow.mockey.core.response

import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.request.charsetOf
import java.nio.charset.Charset

/**
 * Returns the response that the scenario gives: its status code, every one of its headers in their order, and
 * its body.
 *
 * The body is sent exactly as it is stored: nothing is added to it, and nothing is taken from it. It is encoded
 * in the [responseCharset] of the scenario. A character that this charset cannot encode is sent as what the
 * charset puts in its place, which is `?` for most charsets.
 *
 * The headers are those of the scenario and no others. A scenario without a `Content-Type` header gives a
 * response without one.
 */
internal fun Scenario.response(): MockResponse = MockResponse(statusCode, headers, responseBody.toByteArray(responseCharset))

/**
 * The charset in which the body of the scenario is sent: the one that the `Content-Type` header of the scenario
 * declares, and UTF-8 otherwise. That is the case when the scenario has no such header, when the header
 * declares no charset, and when it declares one that this runtime does not know or cannot encode in.
 *
 * No other header has a say in the charset. Of several `Content-Type` headers, the first one counts.
 */
internal val Scenario.responseCharset: Charset
    get() {
        val contentType = headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value
        return charsetOf(contentType)?.takeIf { it.canEncode() } ?: Charsets.UTF_8
    }
