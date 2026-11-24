package io.github.dnedrow.mockey.core.upstream

import io.github.dnedrow.mockey.core.definitions.defaultRealUrl
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.routing.Target
import io.github.dnedrow.mockey.core.routing.UrlPattern
import io.github.dnedrow.mockey.core.routing.percentEncoded

/** Where a request in proxy mode goes. */
internal sealed interface ForwardTarget {
    /** The request goes to [url]: the URL of a real service, with the query string of the request. */
    data class To(
        val url: String,
    ) : ForwardTarget

    /**
     * The request goes nowhere, because no URL can be made for it. It gets the status 502 and the
     * [explanation], and no host is contacted.
     */
    data class Nowhere(
        val explanation: String,
    ) : ForwardTarget
}

/**
 * Returns where a request for [target] with the query string [query] goes when it is forwarded without a
 * service: to the target itself, as it is written, with the query string. Only an absolute target is forwarded
 * that way.
 */
internal fun forwardTarget(
    target: Target,
    query: String?,
): ForwardTarget.To {
    require(target.isAbsolute) { "Only an absolute target is a URL to forward to." }
    return ForwardTarget.To(withQuery(target.text, query))
}

/**
 * Returns where a request for [target] with the query string [query] goes when [service] forwards it.
 * [target] is the target after twisting, and [variables] are the values that the request has for the
 * variables of the URL pattern through which it reaches the service.
 *
 * - An absolute target is the URL of a real service, and the request goes to it as it is written. The real
 *   URLs of the service have no say.
 * - A request for a relative target goes to the default real URL of the service. Each variable of that URL is
 *   replaced by the value of the variable of the same name, see [substituted].
 *
 * In both cases the query string of the request follows the URL. A request for a relative target goes nowhere
 * when the service has no real URL, or when its default real URL has a variable that has no value.
 */
internal fun forwardTarget(
    service: Service,
    target: Target,
    variables: Map<String, String>,
    query: String?,
): ForwardTarget {
    if (target.isAbsolute) return forwardTarget(target, query)
    val cannotForward = "The service '${service.name}' cannot forward the request for '${target.text}'"
    val realUrl = service.defaultRealUrl ?: return ForwardTarget.Nowhere("$cannotForward: the service has no real URL.")
    val missing =
        UrlPattern.VARIABLE
            .findAll(realUrl)
            .map { it.groupValues[1] }
            .filterNot { it in variables }
            .distinct()
            .toList()
    if (missing.isNotEmpty()) return ForwardTarget.Nowhere("$cannotForward: its real URL $realUrl has ${withoutValue(missing)}.")
    return ForwardTarget.To(withQuery(substituted(realUrl, variables), query))
}

/** Returns what an explanation calls the variables with the names [names], for which a request has no value. */
private fun withoutValue(names: List<String>): String {
    val quoted = names.joinToString(", ") { "'$it'" }
    val one = "the variable $quoted, for which the request has no value"
    val several = "the variables $quoted, for which the request has no values"
    return if (names.size == 1) one else several
}

/**
 * Returns [url] with each of its variables replaced by its value of [values]. A value is written as data of
 * a URL: every character other than a letter, a digit, `-`, `.`, `_`, and `~` is escaped, so that a slash or a
 * question mark in a value does not change the meaning of the URL. A variable without a value stays as it is.
 */
internal fun substituted(
    url: String,
    values: Map<String, String>,
): String = UrlPattern.VARIABLE.replace(url) { variable -> values[variable.groupValues[1]]?.let(::percentEncoded) ?: variable.value }

/** Returns [url] followed by [query]. A URL that has a query string of its own gets [query] as further parameters. */
private fun withQuery(
    url: String,
    query: String?,
): String =
    when {
        query == null -> url
        '?' in url -> "$url&$query"
        else -> "$url?$query"
    }
