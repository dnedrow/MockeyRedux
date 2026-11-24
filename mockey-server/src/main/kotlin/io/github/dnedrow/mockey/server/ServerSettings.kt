package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.model.Tags
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The settings of the server program. [ServerCommand] reads each of them from a command-line option or from
 * an environment variable. The values given here are the defaults, which apply when it finds neither.
 */
internal data class ServerSettings(
    /** The address on which the mock listener accepts connections. */
    val mockHost: String = "0.0.0.0",
    /** The port of the mock listener. Port 0 stands for a free port, which the system picks. */
    val mockPort: Int = 8080,
    /** The address on which the admin listener accepts connections. */
    val adminHost: String = "127.0.0.1",
    /** The port of the admin listener. Port 0 stands for a free port, which the system picks. */
    val adminPort: Int = 8081,
    /** The token that a request to the admin API has to present, or `null` when it has to present none. */
    val adminToken: AdminToken? = null,
    /** Whether the admin listener may accept connections from other hosts while there is no [adminToken]. */
    val allowUnprotectedAdmin: Boolean = false,
    /** The directory of the stored definitions. */
    val definitions: Path = Path("mockey-definitions"),
    /** Whether changes to the definitions are kept in memory only. */
    val readOnly: Boolean = true,
    /** The file, the directory, or the URL of the definitions that take the place of the loaded ones at startup. */
    val initialDefinitions: String? = null,
    /** The tags that a service or a scenario needs one of to take part in routing. No tags admit all of them. */
    val tagFilter: Tags = Tags.EMPTY,
    /** The number of history entries that are kept. */
    val historyLimit: Int = 1_000,
    /** The number of bytes that a history entry holds of a request body and of a response body. */
    val bodyCaptureLimit: Int = MEBIBYTE,
    /** The time within which the connection to an upstream has to be made. */
    val upstreamConnectTimeout: Duration = 10.seconds,
    /** The time within which the complete response of an upstream has to arrive. */
    val upstreamResponseTimeout: Duration = 60.seconds,
    /** The number of bytes that the body of a request may have. */
    val maxRequestBody: Long = 50L * MEBIBYTE,
    /** Whether a request for an absolute target that no service knows is forwarded to that target. */
    val forwardUnknownTargets: Boolean = true,
    /** The level from which on messages are written to the log. */
    val logLevel: LogLevel = LogLevel.INFO,
    /** The time that requests in progress get to finish in when the server is told to stop. */
    val shutdownGracePeriod: Duration = 10.seconds,
) {
    /**
     * Returns the settings as texts for the log, each under the name of its option. No text holds a secret:
     * of the admin token it says only whether there is one, and a URL comes without its user information
     * and its query.
     */
    fun summary(): Map<String, String> =
        linkedMapOf(
            "mock-host" to mockHost,
            "mock-port" to mockPort.toString(),
            "admin-host" to adminHost,
            "admin-port" to adminPort.toString(),
            "admin-token" to if (adminToken == null) NONE else "set",
            "allow-unprotected-admin" to allowUnprotectedAdmin.toString(),
            "definitions" to definitions.toAbsolutePath().normalize().toString(),
            "read-only" to readOnly.toString(),
            "initial-definitions" to (initialDefinitions?.let(::withoutSecrets) ?: NONE),
            "tag-filter" to tagFilter.toString().ifEmpty { NONE },
            "history-limit" to historyLimit.toString(),
            "body-capture-limit" to bodyCaptureLimit.toString(),
            "upstream-connect-timeout" to upstreamConnectTimeout.toString(),
            "upstream-response-timeout" to upstreamResponseTimeout.toString(),
            "max-request-body" to maxRequestBody.toString(),
            "forward-unknown-targets" to forwardUnknownTargets.toString(),
            "log-level" to logLevel.name.lowercase(),
            "shutdown-grace-period" to shutdownGracePeriod.toString(),
        )

    internal companion object {
        /** What the help and the log say for a setting that has no value. */
        const val NONE = "none"

        private const val MEBIBYTE = 1_048_576
    }
}

/**
 * The token that protects the admin API. It hands its value to nobody: [matches] compares a text with it,
 * its text form only says that there is a token, and every token has the same hash code. So no message and
 * no log line comes to hold the token or something that was computed from it.
 */
internal class AdminToken(
    value: String,
) {
    private val bytes = value.toByteArray()

    init {
        require(value.isNotBlank()) { "An admin token is a text that is not blank." }
    }

    /** Returns whether [candidate] is this token. How long the comparison takes does not tell where the texts differ. */
    fun matches(candidate: String): Boolean = MessageDigest.isEqual(bytes, candidate.toByteArray())

    override fun equals(other: Any?): Boolean = other is AdminToken && MessageDigest.isEqual(bytes, other.bytes)

    override fun hashCode(): Int = 0

    override fun toString(): String = "AdminToken(******)"
}

/** The levels of the log, from the one that writes the least to the one that writes the most. */
internal enum class LogLevel {
    ERROR,
    WARN,
    INFO,
    DEBUG,
    TRACE,
}

private val URL = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*://)([^/?#]*@)?([^?#]*)(\?[^#]*)?(#.*)?$""")

/**
 * Returns the name of the source of definitions [source] as a log or a message may hold it. A URL loses
 * its user information and its query, which can carry a password or a key. A path stays as it is.
 */
internal fun withoutSecrets(source: String): String {
    val parts = URL.matchEntire(source)?.groupValues ?: return source
    val (scheme, userInformation, location, query, fragment) = parts.drop(1)
    return scheme + (if (userInformation.isEmpty()) "" else "$HIDDEN@") + location + (if (query.isEmpty()) "" else "?$HIDDEN") + fragment
}

private const val HIDDEN = "***"
