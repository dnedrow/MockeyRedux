package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.engine.Change
import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.engine.FieldError
import io.github.dnedrow.mockey.core.engine.ValidationFailure
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.OutboundProxy
import java.net.URI
import java.net.URISyntaxException

/**
 * The change that sets the outbound proxy.
 *
 * The settings are stored in a normal form. The URL has no surrounding whitespace. A text that is empty or
 * blank is no value: credentials without a user name and without a password are no credentials, and settings
 * without a URL and without credentials are no settings at all.
 *
 * A URL that is set names where the proxy listens and nothing else: it starts with `http://` and holds a
 * host and, where the proxy does not listen on port 80, a port. The user name and the password are no part
 * of it, because the URL is stored with the definitions and the credentials are not.
 */
internal object OutboundProxyChanges {
    /**
     * Changes the outbound proxy. [change] gets the settings as they are, with their credentials, or `null`
     * when there are none. It returns what the settings are to be, or `null` for none. The change yields the
     * settings as they are stored.
     *
     * Credentials are kept by returning those that [change] got. They are held with the definitions in memory
     * and are never written with them.
     */
    fun update(change: (OutboundProxy?) -> OutboundProxy?): Change<OutboundProxy?> =
        { definitions ->
            val proxy = change(definitions.settings.outboundProxy)?.let(::normalized)?.takeIf { it.hasSettings || it.enabled }
            val errors = proxy?.let(::errorsOf).orEmpty()
            if (errors.isEmpty()) {
                ChangeResult.Changed(definitions.copy(settings = definitions.settings.copy(outboundProxy = proxy)), proxy)
            } else {
                ChangeResult.Rejected(ValidationFailure(errors))
            }
        }

    private fun normalized(proxy: OutboundProxy): OutboundProxy =
        proxy.copy(
            url = proxy.url.trim(),
            credentials = proxy.credentials?.takeUnless { it.username.isBlank() && it.password.isBlank() },
        )

    private fun errorsOf(proxy: OutboundProxy): List<FieldError> {
        val url =
            when {
                proxy.url.isNotEmpty() -> proxyUrlProblem(proxy.url)
                proxy.enabled -> "An outbound proxy needs a URL before it can be enabled."
                else -> null
            }
        val username =
            "The user name for an outbound proxy must not contain a colon.".takeIf { proxy.credentials?.username?.contains(':') == true }
        return listOfNotNull(url?.let { FieldError("url", it) }, username?.let { FieldError("credentials.username", it) })
    }
}

/**
 * Whether these are settings for an outbound proxy at all: they hold a URL, a user name, or a password. A text
 * that is empty or blank is no value, because definitions are also written and merged by hand.
 */
internal val OutboundProxy?.hasSettings: Boolean
    get() = this != null && (url.isNotBlank() || credentials?.let { it.username.isNotBlank() || it.password.isNotBlank() } == true)

/**
 * The outbound proxy that forwarded requests go through: the proxy of the settings while it is enabled, and
 * `null` while requests go to real services directly.
 */
internal val Definitions.outboundProxyInUse: OutboundProxy?
    get() = settings.outboundProxy?.takeIf { it.enabled }

/**
 * What a reader is shown of the outbound proxy: its [url], whether it is [enabled], the [username] of its
 * credentials, and whether they hold a password. The password itself is no part of it.
 */
internal data class OutboundProxyView(
    val url: String,
    val enabled: Boolean,
    val username: String?,
    val passwordSet: Boolean,
)

/** What a reader is shown of this outbound proxy. */
internal val OutboundProxy.view: OutboundProxyView
    get() = OutboundProxyView(url, enabled, credentials?.username, credentials?.password?.isNotEmpty() == true)

/** Where an outbound proxy listens. */
internal data class ProxyAddress(
    val host: String,
    val port: Int,
) {
    override fun toString(): String = "$host:$port"
}

private const val HTTP_PORT = 80
private const val LAST_PORT = 65_535

/**
 * Returns where the outbound proxy with [url] listens. A URL without a port stands for port 80.
 *
 * @throws IllegalArgumentException when [url] is no URL of an outbound proxy. Its message says why, as a sentence.
 */
internal fun proxyAddressOf(url: String): ProxyAddress {
    val uri =
        try {
            URI(url.trim())
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("The URL of the outbound proxy is not a valid URL.")
        }
    val (host, port) = hostAndPortOf(uri)
    val namesMore = uri.rawPath !in listOf("", "/") || uri.rawQuery != null || uri.rawFragment != null
    val problem =
        when {
            url.isBlank() -> "The outbound proxy has no URL."
            !uri.scheme.equals("http", ignoreCase = true) -> "The URL of the outbound proxy must start with http://."
            '@' in uri.rawAuthority.orEmpty() -> "The URL of the outbound proxy must not hold a user name or a password."
            host.isEmpty() -> "The URL of the outbound proxy names no host."
            port !in 1..LAST_PORT -> "The URL of the outbound proxy names a port that does not exist."
            namesMore -> "The URL of the outbound proxy must name a host and a port and nothing else."
            else -> null
        }
    require(problem == null) { problem.orEmpty() }
    return ProxyAddress(host, port)
}

/**
 * Returns the host and the port that [uri] names, with port 80 for a URL without a port, an empty host for a
 * URL without one, and the port 0 for a port that is no number. `java.net.URI` reads no host out of a name
 * that it does not hold to be one, such as a name with an underscore. Such a name is read here.
 */
private fun hostAndPortOf(uri: URI): Pair<String, Int> {
    if (uri.host != null) return uri.host to (if (uri.port < 0) HTTP_PORT else uri.port)
    val authority = uri.rawAuthority.orEmpty()
    val host = authority.substringBefore(':')
    val port = if (':' in authority) authority.substringAfter(':').toIntOrNull() ?: 0 else HTTP_PORT
    return host to port
}

/** Returns why [url] is no URL of an outbound proxy, as a sentence, or `null` when it is one. */
internal fun proxyUrlProblem(url: String): String? =
    try {
        proxyAddressOf(url)
        null
    } catch (e: IllegalArgumentException) {
        e.message
    }
