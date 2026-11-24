package io.github.dnedrow.mockey.core.upstream

import io.github.dnedrow.mockey.core.model.Header
import java.net.URI
import java.net.URISyntaxException
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The upstream session: the cookies that real services have set, in one store for all requests that are
 * forwarded. A cookie that the answer to one request set goes with every later request that it applies to,
 * whichever client that request comes from. The cookies that a client sends are not part of the session.
 *
 * The session follows RFC 6265. A cookie applies to a request
 *
 * - for its host: the host whose answer set the cookie, or, for a cookie that names a domain, that domain and
 *   every host in it. The port makes no difference.
 * - for its path: the path that the cookie names, and everything below it. A cookie that names no path has
 *   the directory of the request whose answer set it.
 * - until it expires, which a cookie with `Max-Age` or `Expires` does.
 * - over `https` only, when it is marked `Secure`.
 *
 * A cookie takes the place of the cookie with its name, its domain, and its path.
 *
 * The session is held in memory only. [now] tells the time, by which cookies expire.
 */
internal class UpstreamSession(
    private val now: () -> Instant = Instant::now,
) {
    private class Cookie(
        val name: String,
        val value: String,
        val domain: String,
        val hostOnly: Boolean,
        val path: String,
        val secure: Boolean,
        val expires: Instant?,
        val order: Long,
    )

    /** What tells two cookies apart. */
    private data class Key(
        val name: String,
        val domain: String,
        val path: String,
    )

    private val cookies = HashMap<Key, Cookie>()
    private var stored = 0L

    /** Empties the session: no request carries a cookie until a real service sets one again. */
    @Synchronized
    fun clear() {
        cookies.clear()
    }

    /** Takes the cookies that the answer to a request for [url] sets with the `Set-Cookie` headers among its [headers]. */
    @Synchronized
    fun take(
        url: String,
        headers: List<Header>,
    ) {
        val uri = parsed(url) ?: return
        headers.filter { it.name.equals("Set-Cookie", ignoreCase = true) }.forEach { store(uri, it.value) }
    }

    /**
     * Returns the value of the `Cookie` header for a request for [url]: the cookies that apply to it as
     * `name=value`, with `; ` between them, or `null` when none applies. Cookies with a longer path come first,
     * and cookies with paths of one length come in the order in which they were first set.
     */
    @Synchronized
    fun cookieHeader(url: String): String? {
        val uri = parsed(url) ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.rawPath?.takeIf { it.startsWith("/") } ?: "/"
        val secure = uri.scheme.equals("https", ignoreCase = true)
        val time = now()
        cookies.values.removeIf { it.expires != null && !it.expires.isAfter(time) }
        return cookies.values
            .filter { it.appliesToHost(host) && pathMatches(path, it.path) && (secure || !it.secure) }
            .sortedWith(compareByDescending<Cookie> { it.path.length }.thenBy { it.order })
            .takeIf { it.isNotEmpty() }
            ?.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /**
     * Returns [request] with the cookies of the session: its only `Cookie` header holds the cookies that apply
     * to its URL, and it has no such header when none applies.
     */
    fun withCookies(request: UpstreamRequest): UpstreamRequest {
        val others = request.headers.filterNot { it.name.equals(COOKIE, ignoreCase = true) }
        val cookie = cookieHeader(request.url)
        return request.copy(headers = if (cookie == null) others else others + Header(COOKIE, cookie))
    }

    /** Stores the cookie that the `Set-Cookie` value [header] of an answer to a request for [uri] sets, if it sets one. */
    private fun store(
        uri: URI,
        header: String,
    ) {
        val host = uri.host?.lowercase() ?: return
        val parts = header.split(';')
        val pair = parts.first()
        val name = pair.substringBefore('=').trim()
        if ('=' !in pair || name.isEmpty()) return
        val attributes = parts.drop(1).associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=', "").trim() }
        val named = attributes["domain"]?.removePrefix(".")?.lowercase().orEmpty()
        // A domain of one label, such as "com", holds hosts of many owners. Only the host of that very name sets a cookie for it.
        val hostOnly = named.isEmpty() || (named == host && '.' !in named)
        if (!hostOnly && ('.' !in named || !domainMatches(host, named))) return
        val key = Key(name, if (hostOnly) host else named, attributes["path"]?.takeIf { it.startsWith("/") } ?: defaultPath(uri))
        val expires = expiryOf(attributes)
        if (expires != null && !expires.isAfter(now())) {
            cookies.remove(key)
            return
        }
        val order = cookies[key]?.order ?: stored++
        cookies[key] = Cookie(name, pair.substringAfter('=').trim(), key.domain, hostOnly, key.path, "secure" in attributes, expires, order)
    }

    /**
     * Returns when a cookie with [attributes] expires, or `null` for one that lasts as long as the session.
     * `Max-Age` counts before `Expires`, and an attribute that is not well-formed does not count.
     */
    private fun expiryOf(attributes: Map<String, String>): Instant? {
        val maxAge = attributes["max-age"]?.takeIf(MAX_AGE::matches)
        return when {
            maxAge == null -> attributes["expires"]?.let(::cookieDate)
            maxAge.startsWith("-") || maxAge.all { it == '0' } -> Instant.EPOCH
            else -> now().plusSeconds((maxAge.toLongOrNull() ?: LONGEST_AGE_SECONDS).coerceAtMost(LONGEST_AGE_SECONDS))
        }
    }

    private fun Cookie.appliesToHost(host: String): Boolean = if (hostOnly) host == domain else domainMatches(host, domain)

    private companion object {
        const val COOKIE = "Cookie"

        /** A cookie is kept for at most this long, so that no age leads past the last time that can be told. */
        const val LONGEST_AGE_SECONDS = 400L * 366 * 24 * 60 * 60

        val MAX_AGE = Regex("-?\\d+")

        /** Returns [url] as a URI, or `null` for a URL that cannot be read. */
        fun parsed(url: String): URI? =
            try {
                uriOf(url)
            } catch (_: URISyntaxException) {
                null
            }

        /** Returns whether [host] is [domain] or lies in it. An IP address lies in no domain. */
        fun domainMatches(
            host: String,
            domain: String,
        ): Boolean = host == domain || (host.endsWith(".$domain") && host.any(Char::isLetter) && ':' !in host)

        /** Returns whether a cookie for [cookiePath] applies to a request for [requestPath]: for that path, and for what lies below it. */
        fun pathMatches(
            requestPath: String,
            cookiePath: String,
        ): Boolean =
            requestPath == cookiePath ||
                (requestPath.startsWith(cookiePath) && (cookiePath.endsWith("/") || requestPath[cookiePath.length] == '/'))

        /** Returns the path of a cookie that names none: the directory of the path of the request. */
        fun defaultPath(uri: URI): String {
            val path = uri.rawPath.orEmpty()
            val lastSlash = path.lastIndexOf('/')
            return if (lastSlash <= 0) "/" else path.substring(0, lastSlash)
        }
    }
}

/** What separates the parts of a date of a cookie. A colon is none, so that a time stays in one piece. */
private val DATE_DELIMITERS = Regex("[\\x09\\x20-\\x2F\\x3B-\\x40\\x5B-\\x60\\x7B-\\x7E]+")
private val TIME_OF_DAY = Regex("(\\d{1,2}):(\\d{1,2}):(\\d{1,2})(\\D.*)?")
private val DAY_OF_MONTH = Regex("(\\d{1,2})(\\D.*)?")
private val YEAR = Regex("(\\d{2,4})(\\D.*)?")
private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

private const val MONTH_LETTERS = 3
private const val FIRST_YEAR = 1601
private const val CENTURY = 100
private const val FIRST_SHORT_YEAR_OF_1900S = 70
private const val YEAR_1900 = 1900
private const val YEAR_2000 = 2000

/**
 * Returns the time that the `Expires` attribute [text] of a cookie names, in UTC, or `null` when it names
 * none. The date is read as RFC 6265 says, which takes the forms that servers write: a time as `hh:mm:ss`, a
 * day of the month, the name of a month, and a year, in any order and with anything between them. A year of
 * two digits from 70 on lies in the 1900s, and a lower one in the 2000s.
 */
internal fun cookieDate(text: String): Instant? {
    var time: List<Int>? = null
    var day: Int? = null
    var month: Int? = null
    var year: Int? = null
    for (token in text.split(DATE_DELIMITERS).filter { it.isNotEmpty() }) {
        val monthNumber = MONTHS.indexOf(token.take(MONTH_LETTERS).lowercase()) + 1
        when {
            time == null && TIME_OF_DAY.matches(token) -> {
                time = TIME_OF_DAY.matchEntire(token)?.numbers()
            }

            day == null && DAY_OF_MONTH.matches(token) -> {
                day = DAY_OF_MONTH.matchEntire(token)?.numbers()?.first()
            }

            month == null && monthNumber > 0 -> {
                month = monthNumber
            }

            year == null && YEAR.matches(token) -> {
                year =
                    YEAR
                        .matchEntire(token)
                        ?.numbers()
                        ?.first()
                        ?.let(::fullYear)
            }
        }
    }
    if (time == null || day == null || month == null || year == null || year < FIRST_YEAR) return null
    return try {
        LocalDateTime.of(year, month, day, time[0], time[1], time[2]).toInstant(ZoneOffset.UTC)
    } catch (_: DateTimeException) {
        null
    }
}

/** Returns the numbers that the groups of the match hold, without the group for what follows them. */
private fun MatchResult.numbers(): List<Int> = groupValues.drop(1).dropLast(1).map(String::toInt)

/** Returns the year that [year] stands for: a year of two digits lies in the 1900s from 70 on, and in the 2000s below that. */
private fun fullYear(year: Int): Int =
    when {
        year >= CENTURY -> year
        year >= FIRST_SHORT_YEAR_OF_1900S -> YEAR_1900 + year
        else -> YEAR_2000 + year
    }
