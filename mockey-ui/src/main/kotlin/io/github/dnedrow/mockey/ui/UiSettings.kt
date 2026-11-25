package io.github.dnedrow.mockey.ui

import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpRequest

/**
 * The settings of the UI program. [UiCommand] reads each of them from a command-line option or from an
 * environment variable. The values given here are the defaults, which apply when it finds neither.
 */
internal data class UiSettings(
    /** The address on which the UI accepts connections. */
    val host: String = "127.0.0.1",
    /** The port of the UI. Port 0 stands for a free port, which the system picks. */
    val port: Int = 8082,
    /** Where the admin listener of the server is that the UI manages. */
    val adminUrl: AdminUrl = AdminUrl.DEFAULT,
    /** The admin token of that server, which the UI presents with every request to the admin API, or `null` when it presents none. */
    val adminToken: AdminToken? = null,
    /** The level from which on messages are written to the log. */
    val logLevel: LogLevel = LogLevel.INFO,
) {
    /**
     * Returns the settings as texts for the log, each under the name of its option. No text holds a secret:
     * of the admin token it says only whether there is one.
     */
    fun summary(): Map<String, String> =
        linkedMapOf(
            "host" to host,
            "port" to port.toString(),
            "admin-url" to adminUrl.toString(),
            "admin-token" to if (adminToken == null) NONE else "set",
            "log-level" to logLevel.name.lowercase(),
        )

    internal companion object {
        /** What the help and the log say for a setting that has no value. */
        const val NONE = "none"
    }
}

/**
 * The URL of the admin listener of a server, such as `http://127.0.0.1:8081`. It says where the server is,
 * and it does not hold the path of the admin API, which the UI knows. Where a reverse proxy stands before
 * the admin listener, the URL has the path at which the proxy serves it.
 *
 * The URL has the scheme `http` or `https` and a host, and it has no user information, no query, and no
 * fragment. The pages of the UI show the URL, so it holds nothing that is a secret.
 */
internal class AdminUrl private constructor(
    private val text: String,
) {
    /**
     * Returns the URL of [path] at the admin listener. [path] starts with a slash and is written as a URL
     * has it, with its query string where it has one.
     */
    fun resolve(path: String): URI = URI.create(text + path)

    override fun equals(other: Any?): Boolean = other is AdminUrl && other.text == text

    override fun hashCode(): Int = text.hashCode()

    /** Returns the URL as it is written, without a slash at its end. */
    override fun toString(): String = text

    internal companion object {
        /** The admin listener of a server with its default settings, on this machine. */
        val DEFAULT = AdminUrl("http://127.0.0.1:8081")

        private val SCHEMES = setOf("http", "https")

        /**
         * Returns the URL that [text] is.
         *
         * @throws IllegalArgumentException when [text] is no URL of an admin listener. The message says what
         *   is wrong with it, as the rest of a sentence that begins with what the text is.
         */
        fun of(text: String): AdminUrl {
            val url =
                try {
                    URI(text.trim())
                } catch (_: URISyntaxException) {
                    throw IllegalArgumentException("is no URL")
                }
            require(url.scheme?.lowercase() in SCHEMES) { "has to begin with http:// or https://" }
            require(!url.host.isNullOrEmpty()) { "names no host" }
            require(url.rawUserInfo == null) { "must not hold a user name or a password" }
            require(url.rawQuery == null && url.rawFragment == null) { "must not have a query string or a fragment" }
            return AdminUrl("${url.scheme.lowercase()}://${url.rawAuthority}${url.rawPath.orEmpty().trimEnd('/')}")
        }
    }
}

/**
 * The admin token of the server, which the UI presents to the admin API and to nobody else. It hands its
 * value to nothing but the request that [presentTo] gets: its text form only says that there is a token,
 * and every token has the same hash code. So no message, no log line, and no page comes to hold the token or
 * something that was computed from it.
 *
 * A token is made of the characters of ASCII that are printed, and of spaces between them. The HTTP client
 * of the Java runtime writes a question mark in the place of every other character of a header, so the UI
 * could not present a token with one, although a server takes such a token.
 */
internal class AdminToken(
    value: String,
) {
    private val credentials = "Bearer $value"

    init {
        require(value.isNotBlank()) { "the token must not be blank" }
        require(value.none(Char::isISOControl)) { "the token must not hold a control character, such as a line break" }
        require(value.trim() == value) { "the token must not begin or end with a space, which a request cannot carry there" }
        require(value.all { it in PRESENTABLE }) { "the token must not hold a character beyond ASCII, which the UI cannot present" }
    }

    /** Lets [request] present the token to the admin API, as a bearer token. */
    fun presentTo(request: HttpRequest.Builder) {
        request.header("Authorization", credentials)
    }

    override fun equals(other: Any?): Boolean = other is AdminToken && other.credentials == credentials

    override fun hashCode(): Int = 0

    override fun toString(): String = "AdminToken(******)"

    private companion object {
        /** The characters that the UI can write into a header: the ones of ASCII that are printed, and the space. */
        val PRESENTABLE = ' '..'~'
    }
}
