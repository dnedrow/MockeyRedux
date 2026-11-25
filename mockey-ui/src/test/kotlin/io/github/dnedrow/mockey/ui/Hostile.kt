package io.github.dnedrow.mockey.ui

/**
 * Content that is written to be read as more than the text that it is, for the tests that show that the pages
 * of the UI read it as text, wherever a server states it.
 */
internal object Hostile {
    /** Content that a browser would read as more than text if a page wrote it as it is. */
    val MARKUP =
        listOf(
            "<script>alert(1)</script>",
            "<img src=x onerror=alert(1)>",
            "\"><svg onload=alert(1)>",
            "' onmouseover='alert(1)",
            "\" onfocus=\"alert(1)\" autofocus=\"",
            "</pre><script>alert(1)</script>",
            "</textarea><script>alert(1)</script>",
            "</title><script>alert(1)</script>",
            "</option></select><script>alert(1)</script>",
            "--><script>alert(1)</script><!--",
            "&lt;b&gt; &amp;amp; &#x3C;i&#x3E; &quot;",
            "<b>bold</b> & <i>italic</i>",
            "]]><b>",
            "{{7*7}} \${7*7} <%= 7*7 %>",
        )

    /** Content that would lead a link elsewhere if an address held it as it is. */
    val ADDRESSES =
        listOf(
            "javascript:alert(1)",
            "//evil.example/x",
            "http://evil.example/x",
            "../../system/reset",
            "..",
            "a/b?c=d#e",
            "\\\\evil.example\\x",
            "data:text/html,<script>alert(1)</script>",
            " leading space",
            "line\nbreak",
        )

    /** What a document does not hold when every `<` and every `>` of the content is written as text. */
    val UNWRITTEN = listOf("<script", "<img", "<svg", "<b>", "<i>", "<!--", "-->", "]]>")

    /** How an address of a page of the UI is written, whatever it holds: a path, and parameters with their escapes. */
    val SAFE_ADDRESS = Regex("/[A-Za-z0-9._~%/-]*(\\?[A-Za-z0-9._~%=&-]*)?")

    /** How a link of a page is written: an address of the UI, and after it a place of the page that it leads to. */
    val SAFE_LINK = Regex("/[A-Za-z0-9._~%/-]*(\\?[A-Za-z0-9._~%=&-]*)?(#[A-Za-z0-9._~-]*)?")

    /** How the identifier of an element of a page is written. */
    val SAFE_ID = Regex("[A-Za-z0-9._~-]+")

    /** The elements that the pages of the UI are made of, below the frame of every page. */
    val ELEMENTS =
        setOf("h1", "h2", "p", "a", "form", "div", "label", "input", "button", "table", "thead", "tbody", "tr", "th", "td", "code")
            .plus(setOf("select", "option", "span", "time", "section", "ul", "li", "dl", "dt", "dd", "fieldset", "legend"))
            .plus(setOf("textarea", "pre"))

    /** The attributes that the pages of the UI are written with. */
    val ATTRIBUTES =
        setOf("class", "id", "href", "action", "method", "enctype", "type", "name", "value", "for", "scope", "role", "rows")
            .plus(setOf("selected", "checked", "disabled", "datetime", "aria-label", "aria-pressed", "aria-describedby", "aria-invalid"))

    /** Returns how a client of the admin API writes [identifier] as a segment of a path, which is how an address of the UI writes it. */
    fun written(identifier: String): String = Address.of("/x", "id" to identifier).substringAfter("id=")

    /**
     * Returns what [answer] has that a page of the UI does not have, whatever a server states: an element or
     * an attribute that the UI does not write, a bracket of content that is written as it is, and an address
     * or an identifier that is not written as the UI writes one. A page that shows content as text has none
     * of it.
     */
    fun moreThanText(answer: Browser.Response): List<String> {
        val page = answer.page
        val within = page.select("main *")
        val elements = within.map { it.tagName() }.toSet() - ELEMENTS
        val attributes = within.flatMap { element -> element.attributes().map { it.key } }.toSet() - ATTRIBUTES
        val links = page.select("[href], [action]").map { it.attr("href") + it.attr("action") }
        val ids = page.select("[id]").map { it.id() }
        return elements.map { "the element $it" } +
            attributes.map { "the attribute $it" } +
            UNWRITTEN.filter { it in answer.body }.map { "the text $it" } +
            links.filterNot { it.isEmpty() || SAFE_LINK.matches(it) }.map { "the address $it" } +
            ids.filterNot(SAFE_ID::matches).map { "the identifier $it" } +
            listOf("a script").filter { page.select("script, style, base, iframe, object, embed").isNotEmpty() }
    }
}
