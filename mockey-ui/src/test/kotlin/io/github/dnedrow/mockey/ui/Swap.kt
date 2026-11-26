package io.github.dnedrow.mockey.ui

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node

/**
 * What the script of the UI does with the answer to a request that it sent, for the tests, which have no
 * browser that runs the script. It follows the rules of htmx for the settings and the marks that the UI
 * uses (see [Enhancement]). It fails for anything else, so that a new mark isn't taken for a known one.
 *
 * The rules were read from the source of htmx and checked in a browser. `docs/decisions/ui-stack.md` says
 * how.
 */
internal object Swap {
    /**
     * Returns the page that a browser shows after the script put [answer] into [page]. [sender] is the
     * element of [page] that the script sent the request for: a form, or a part that is brought up to
     * date. [page] itself stays as it is.
     *
     * - An answer without content changes nothing.
     * - An answer says in its headers which element it goes into and how. Without them, it goes in the
     *   place of what the page shows, as the settings of the UI say.
     * - A whole page gives its title and what its body holds. Anything else is read as a part of a page.
     * - An element of the answer that is marked for another place takes its content there.
     */
    fun result(
        page: Document,
        sender: Element,
        answer: Browser.Response,
    ): Document {
        check(answer.header(REDIRECT) == null) { "The script loads the page that the answer names." }
        val shown = page.clone()
        if (answer.status == NO_CONTENT) return shown
        // The element of the copy that is where the sender is in its page.
        val steps = generateSequence(sender) { it.parent() }.toList().reversed().drop(1)
        val from = steps.fold(shown as Element) { at, step -> at.child(step.elementSiblingIndex()) }
        val target = answer.header("HX-Retarget") ?: "body"
        val into =
            when {
                target == "body" -> shown.body()
                target.startsWith("closest ") -> from.closest(target.removePrefix("closest "))
                else -> shown.select(target).first()
            }
        checkNotNull(into) { "The page has no element for $target." }
        val read = read(answer, shown)
        for (marked in read.select("[hx-swap-oob]")) {
            check(marked.attr("hx-swap-oob") == "innerHTML") { "The tests don't know the mark ${marked.attr("hx-swap-oob")}." }
            val there = checkNotNull(shown.getElementById(marked.id())) { "The page has no element with the identifier ${marked.id()}." }
            marked.remove()
            there.empty().appendChildren(marked.childNodes().map(Node::clone))
        }
        val content = read.childNodes().map(Node::clone)
        val swap = (answer.header("HX-Reswap") ?: "innerHTML").substringBefore(' ')
        check(swap == "innerHTML" || swap == "outerHTML") { "The tests don't know the swap $swap." }
        if (swap == "innerHTML") {
            into.empty().appendChildren(content)
        } else {
            content.forEach { into.before(it) }
            into.remove()
        }
        return shown
    }

    /**
     * Returns an element that holds what [answer] says, read the way the script reads it: for a whole page,
     * what the body of the page holds, and the title of the page becomes the title of [shown]. For anything
     * else, the elements of the answer, read as a browser reads the content of a template. The script drops
     * every script element of an answer.
     */
    private fun read(
        answer: Browser.Response,
        shown: Document,
    ): Element {
        val found = FIRST_TAG.find(answer.body)?.groupValues
        val first = found?.get(1)?.lowercase()
        val holder =
            if (first == "html") {
                val whole = Jsoup.parse(answer.asRead)
                shown.title(whole.title())
                whole.body()
            } else {
                Jsoup.parse("<body><template>${answer.asRead}</template></body>").select("template").single()
            }
        holder.select("script").remove()
        return holder
    }

    /**
     * Returns the answer [answer] as the page that a browser shows after the script put it into [page],
     * see [result]. The body of the answer stays what the UI sent.
     */
    fun shown(
        page: Document,
        sender: Element,
        answer: Browser.Response,
    ): Browser.Response = Browser.Response(answer.status, answer.headers, answer.body, page.location(), result(page, sender, answer))

    /** The header with which an answer tells the script to load a page, as htmx names it. */
    const val REDIRECT = "HX-Redirect"

    private const val NO_CONTENT = 204

    /** How the script finds the first element of an answer, by which it tells a whole page from a part. */
    private val FIRST_TAG = Regex("<([a-zA-Z][^/\\s>]*)")
}
