package io.github.dnedrow.mockey.ui

import org.jsoup.nodes.FormElement

/**
 * Keeps track of the stand-in servers and the UIs that a test starts, so the test can stop all of them with
 * one call to [close].
 */
internal class Started : AutoCloseable {
    private val fakes = mutableListOf<FakeAdminApi>()
    private val uis = mutableListOf<MockeyUi>()

    /** Starts a stand-in server that doesn't hold anything yet. */
    fun api(): FakeAdminApi = FakeAdminApi().also { fakes += it }

    /** Starts a UI for [api] and returns a browser that talks to it. */
    fun browserFor(api: FakeAdminApi): Browser {
        val settings = UiSettings(port = 0, adminUrl = AdminUrl.of(api.url), logLevel = LogLevel.WARN)
        return Browser(MockeyUi.start(settings).also { uis += it })
    }

    override fun close() {
        uis.forEach(MockeyUi::close)
        fakes.forEach(FakeAdminApi::close)
    }
}

/**
 * Returns the field values of [form] by name, the way a browser would submit them if nobody typed anything.
 * The token and the marks of the initial values are left out, because no test types into those.
 */
internal fun fieldsOf(form: FormElement): Map<String, List<String>> {
    val areas = form.select("textarea").associate { it.attr("name") to it.wholeText() }
    val entered = form.formData().filter { it.key() != "_token" && !it.key().startsWith(Shown.PREFIX) }
    val fields = entered.map { it.key() to (areas[it.key()] ?: it.value()) }
    return fields.groupBy({ it.first }, { it.second })
}

/** Returns the text of the first element that [selector] finds on the page of this answer, exactly as the page holds it. */
internal fun Browser.Response.wholeTextOf(selector: String): String {
    val found = page.select(selector)
    return found.first()!!.wholeText()
}

/** Returns the links that [selector] finds on the page of this answer, each as its text and its address. */
internal fun Browser.Response.linksOf(selector: String): List<Pair<String, String>> =
    page.select(selector).map { it.text() to it.attr("href") }

/** Returns the token that the forms of this browser carry. It reads the token from the single form of the page at [path]. */
internal fun Browser.token(path: String): String {
    val form = get(path).form()
    return form.select("[name=_token]").attr("value")
}

/** Returns the requests to this stand-in that could change something, each as a pair of its request line and its body. */
internal fun FakeAdminApi.changes(): List<Pair<String, String>> = requests.filter { it.method != "GET" }.map { it.line to it.body }
