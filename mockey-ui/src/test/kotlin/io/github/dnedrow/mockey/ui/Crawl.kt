package io.github.dnedrow.mockey.ui

/**
 * The pages of a UI as its links lead to them: a test begins at the first page and follows every link of
 * every page that it reaches, as someone does who clicks through the UI.
 */
internal object Crawl {
    /** A link of a page: the address [of] the page, and the address that the link leads [to], as the page writes it. */
    class Link(
        val of: String,
        val to: String,
    ) {
        /** The address that a browser asks for, which is the link without the place in the page. */
        val address: String = to.substringBefore('#')

        /** The place in the page that the link leads to, or `null` when it leads to the page as a whole. */
        val place: String? = if ('#' in to) to.substringAfter('#') else null

        override fun toString(): String = "$of -> $to"
    }

    /** What a crawl found: the answer for every address that a link led to, and every link. */
    class Found(
        val pages: Map<String, Browser.Response>,
        val links: List<Link>,
    )

    /**
     * Returns what [browser] finds from the page at [start] on. A link that leads the browser on with a
     * redirect is followed to where it leads. A crawl that does not end after [most] pages fails.
     */
    fun from(
        browser: Browser,
        start: String = "/",
        most: Int = MOST,
    ): Found {
        val pages = LinkedHashMap<String, Browser.Response>()
        val links = mutableListOf<Link>()
        val waiting = ArrayDeque(listOf(start))
        while (waiting.isNotEmpty()) {
            val address = waiting.removeFirst()
            if (address in pages) continue
            check(pages.size < most) { "The crawl reached more than $most pages: ${pages.keys}" }
            val answer = browser.get(address)
            pages[address] = answer
            val leadsOn = answer.header("location")
            val written = if (leadsOn != null) listOf(leadsOn) else answer.page.select("a[href]").map { it.attr("href") }
            // A link without an address is one to the page that it is on.
            val found = written.filter { it.isNotEmpty() && !it.startsWith("#") }.map { Link(address, it) }
            links += found
            waiting += found.map { it.address }.filter { it.startsWith("/") && !it.startsWith("//") }
        }
        return Found(pages, links)
    }

    private const val MOST = 500
}
