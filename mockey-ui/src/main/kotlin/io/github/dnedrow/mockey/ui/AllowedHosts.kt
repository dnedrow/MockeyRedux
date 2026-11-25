package io.github.dnedrow.mockey.ui

/**
 * The names under which the UI answers. A browser says in the `Host` header of a request under which name
 * it knows the UI, and the UI answers the request only when that name is one of these:
 *
 * - an IP address,
 * - `localhost`, or a name that ends with `.localhost`,
 * - one of [names], which the UI is started with.
 *
 * That keeps a page of another site from reading and changing what the UI manages. Such a site can make its
 * own name stand for the address of the UI after the browser has loaded its page. For the browser, the page
 * and the UI are then one site, so the page gets the pages of the UI with the tokens of their forms, and can
 * send the forms. The requests of such a page name the site, and never the UI: an address is no name that a
 * site can make stand for another address, and no site decides what `localhost` stands for.
 *
 * See `docs/decisions/ui-forms.md`.
 */
internal class AllowedHosts(
    names: Collection<String>,
) {
    private val names = names.map { it.lowercase().removeSuffix(".") }.toSet()

    /**
     * Returns whether the UI answers a request with the `Host` headers [headers]. A request names its host
     * once, with or without a port.
     */
    fun allows(headers: List<String>): Boolean {
        val host = headers.singleOrNull()?.let(::hostOf) ?: return false
        return isAddress(host) || host == LOCALHOST || host.endsWith(".$LOCALHOST") || host in names
    }

    internal companion object {
        /**
         * Returns the names under which a UI with [settings] answers: the names that it is told, and the
         * one that it is told to listen on, when that is a name.
         */
        fun of(settings: UiSettings): AllowedHosts = AllowedHosts(settings.allowedHosts + settings.host)

        private const val LOCALHOST = "localhost"
        private const val LAST_OCTET = 255
        private val PORT = Regex(""":\d{1,5}$""")
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val IPV6 = Regex("""\[[0-9a-f:.]*:[0-9a-f:.]*]""")
        private val NAME = Regex("""[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*""")
        private const val LONGEST_NAME = 253

        /**
         * Returns the host that a `Host` header with the value [header] names, in lower case and without
         * its port, or `null` when the value names none.
         */
        fun hostOf(header: String): String? {
            val written = header.trim().lowercase()
            val host = written.replace(PORT, "").removeSuffix(".")
            return host.takeIf { isAddress(it) || isName(it) }
        }

        /**
         * Returns whether [host] is written as the name of a host is: labels of letters, digits, and hyphens
         * with dots between them, of which the last is no number. A browser takes a host that ends with a
         * number for an address.
         */
        fun isName(host: String): Boolean {
            val ending = host.substringAfterLast('.')
            return host.length <= LONGEST_NAME && NAME.matches(host) && ending.any { !it.isDigit() }
        }

        /** Returns whether [host] is an IP address as a URL writes it: four numbers with dots, or an address of IPv6 in brackets. */
        fun isAddress(host: String): Boolean {
            if (IPV6.matches(host)) return true
            return IPV4.matches(host) && host.split('.').all { it.toInt() <= LAST_OCTET }
        }
    }
}
