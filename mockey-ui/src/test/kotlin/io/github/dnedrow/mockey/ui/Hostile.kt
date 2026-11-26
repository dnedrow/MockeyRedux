package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.HistoryEntryDto
import io.github.dnedrow.mockey.api.NameValueDto
import io.github.dnedrow.mockey.api.RecordedRequestDto
import io.github.dnedrow.mockey.api.RecordedResponseDto
import io.github.dnedrow.mockey.api.RecordedScenarioDto
import io.github.dnedrow.mockey.api.RecordedServiceDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import org.jsoup.nodes.Document

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
            .plus(setOf("textarea", "pre", "h3", "meter"))

    /**
     * The attributes with which a page marks an element for the script of the UI, see [Enhancement], each
     * with the values that the UI writes for it. The attributes that hold an address aren't among them:
     * they are held to what an address of the UI looks like.
     */
    val FOR_THE_SCRIPT =
        mapOf(
            "hx-trigger" to setOf("every 5s"),
            "hx-encoding" to setOf("multipart/form-data"),
            "hx-swap-oob" to setOf("innerHTML"),
        )

    /** The attributes with which a page tells the script of the UI which address to ask. */
    val ADDRESSES_FOR_THE_SCRIPT = setOf("hx-post", "hx-get")

    /** The attributes that the pages of the UI are written with. */
    val ATTRIBUTES =
        setOf("class", "id", "href", "action", "method", "enctype", "type", "name", "value", "for", "scope", "role", "rows")
            .plus(setOf("selected", "checked", "disabled", "datetime", "aria-label", "aria-pressed", "aria-describedby", "aria-invalid"))
            .plus(setOf("autocomplete", "step", "min", "max"))
            .plus(FOR_THE_SCRIPT.keys + ADDRESSES_FOR_THE_SCRIPT)

    /** Returns the history entry [id] that states [text] wherever a history entry states a text. */
    fun entry(
        id: Long,
        text: String,
    ): HistoryEntryDto {
        val headers = listOf(HeaderDto(text, text))
        val pairs = listOf(NameValueDto(text, text))
        return HistoryEntryDto(
            id = id,
            time = text,
            clientAddress = text,
            request = RecordedRequestDto(text, text, text, headers, pairs, pairs, Samples.body(text)),
            response = RecordedResponseDto(200, headers, Samples.body(text)),
            target = text,
            targetBeforeTwisting = text,
            service = RecordedServiceDto(text, text, listOf(text)),
            responseMode = ResponseModeDto.DYNAMIC,
            scenario = RecordedScenarioDto(text, text, listOf(text)),
            outcome = text,
            inspectionMessages = listOf(text),
            explanation = text,
            flagged = true,
        )
    }

    /**
     * Returns whether [answer] is a file that a browser saves instead of displaying it. A download can hold
     * content exactly as a server states it, because the browser never reads it as a page.
     */
    fun isDownload(answer: Browser.Response): Boolean {
        val type = answer.header("content-type").orEmpty()
        val isAttachment = answer.header("content-disposition").orEmpty().startsWith("attachment")
        return isAttachment && !type.startsWith("text/html") && answer.header("x-content-type-options") == "nosniff"
    }

    /** Returns how a client of the admin API writes [identifier] as a segment of a path, which is how an address of the UI writes it. */
    fun written(identifier: String): String = Address.of("/x", "id" to identifier).substringAfter("id=")

    /**
     * Returns what [answer] has that a page of the UI does not have, whatever a server states: an element or
     * an attribute that the UI does not write, a bracket of content that is written as it is, and an address
     * or an identifier that is not written as the UI writes one. A page that shows content as text has none
     * of it.
     *
     * The same holds for what a page tells the script of the UI: the script is the file of the UI, its
     * settings are the ones of the UI, and an element is marked for it with nothing but the fixed values
     * and the addresses that the UI writes.
     */
    fun moreThanText(answer: Browser.Response): List<String> {
        val page = answer.page
        val within = page.select("main *")
        val elements = within.map { it.tagName() }.toSet() - ELEMENTS
        val attributes = within.flatMap { element -> element.attributes().map { it.key } }.toSet() - ATTRIBUTES
        val referring = listOf("href", "action") + ADDRESSES_FOR_THE_SCRIPT
        val links = page.allElements.flatMap { element -> referring.filter(element::hasAttr).map(element::attr) }
        val ids = page.select("[id]").map { it.id() }
        val marks = page.allElements.flatMap { element -> FOR_THE_SCRIPT.keys.filter(element::hasAttr).map { it to element.attr(it) } }
        return elements.map { "the element $it" } +
            attributes.map { "the attribute $it" } +
            unwrittenIn(answer.body).map { "the text $it" } +
            links.filterNot { it.isEmpty() || SAFE_LINK.matches(it) }.map { "the address $it" } +
            ids.filterNot(SAFE_ID::matches).map { "the identifier $it" } +
            marks.filterNot { (name, value) -> value in FOR_THE_SCRIPT.getValue(name) }.map { (name, value) -> "the mark $name=$value" } +
            listOf("a script").filter { scriptsBeyondTheOneOfTheUi(page) || page.select("style, base, iframe, object, embed").isNotEmpty() }
    }

    /** The element with which every page loads the script of the UI, as the UI writes it. */
    const val SCRIPT_ELEMENT = """<script src="/assets/htmx.min.js" defer="defer"></script>"""

    /**
     * Returns the brackets of [UNWRITTEN] that [body] holds, which is what a page has when it writes content
     * as it is. The element that loads the script of the UI doesn't count. Every page has it once.
     */
    fun unwrittenIn(body: String): List<String> = UNWRITTEN.filter { it in body.replaceFirst(SCRIPT_ELEMENT, "") }

    /**
     * Returns whether [page] has a script besides the one of the UI, or states other settings for that
     * script than the ones of the UI. A part of a page has neither the script nor the settings.
     */
    private fun scriptsBeyondTheOneOfTheUi(page: Document): Boolean {
        val scripts = page.select("script")
        val settings = page.select("meta[name=htmx-config]")
        val written = scripts.singleOrNull()?.let { script -> script.attributes().map { it.key to it.value } to script.data() }
        val ofTheUi = written == (listOf("src" to "/assets/htmx.min.js", "defer" to "defer") to "") && page.select("head script").size == 1
        val asTheUiStatesThem = settings.size == 1 && settings.attr("content") == Enhancement.SETTINGS
        val isPart = page.select("title").isEmpty()
        return if (isPart) scripts.isNotEmpty() || settings.isNotEmpty() else !(ofTheUi && asTheUiStatesThem)
    }
}
