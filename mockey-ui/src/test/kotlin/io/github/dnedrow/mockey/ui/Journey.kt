package io.github.dnedrow.mockey.ui

import org.jsoup.nodes.Element
import org.jsoup.nodes.FormElement

/**
 * A walk through the UI by someone whose browser runs no scripts. The walk only opens an address that one
 * of its pages refers to, and it only submits a form that one of its pages shows. So everything that the
 * walk does can be done with links, forms, and full page loads.
 *
 * The walk is made of steps (see [step]). For each step, it remembers which operations of the admin API
 * the stand-in server [api] was asked for. It also remembers every form that its pages showed and every
 * form that it submitted, so a test can tell whether a form was left out.
 *
 * With [withScript], the walk is the one of a browser that runs the script of the UI: the script sends
 * every form that is marked for it, and the walk goes on from the page that the script makes of the answer.
 */
internal class Journey(
    private val browser: Browser,
    private val api: FakeAdminApi,
    private val withScript: Boolean = false,
) {
    /** The addresses that the walk may open: the first page, and every address that a page of the walk refers to. */
    private val linked = mutableSetOf("/")

    /** The paths that the walk asked for with the method GET, whether it opened them or a redirect or a form led to them. */
    val opened: MutableSet<String> = sortedSetOf()

    /** The forms that the pages of the walk showed, each as its method and the path of its action (see [nameOf]). */
    val shown: MutableSet<String> = sortedSetOf()

    /** The forms that the walk submitted, named the same way. */
    val submitted: MutableSet<String> = sortedSetOf()

    /** The forms that the script of the UI sent on the walk, named the same way. */
    val sentByScript: MutableSet<String> = sortedSetOf()

    /** Every page that the browser of the walk showed, in the order of the walk. */
    val pages: MutableList<Browser.Response> = mutableListOf()

    /** The operations of the admin API that each step reached, by the name of the step, in the order of the steps. */
    val reached: MutableMap<String, Set<String>> = LinkedHashMap()

    /** What went wrong on the walk, one sentence for each failure. A walk without failures leaves this empty. */
    val failures: MutableList<String> = mutableListOf()

    /**
     * Does [action] as the step [name] of the walk and returns its result. Everything that the stand-in
     * server is asked for in the meantime counts as reached by this step.
     */
    fun <T> step(
        name: String,
        action: Journey.() -> T,
    ): T {
        val before = api.operations.size
        val result = action()
        reached[name] = reached[name].orEmpty() + api.operations.drop(before)
        return result
    }

    /**
     * Opens [address], which has to be one that a page of the walk refers to, and returns the answer. A
     * redirect is followed, as a browser does.
     */
    fun open(address: String): Browser.Response {
        val asked = address.substringBefore('#')
        if (asked !in linked) failures += "No page of the walk refers to $asked."
        opened += asked.substringBefore('?')
        return arrived(browser.get(asked), "The address $asked")
    }

    /**
     * Opens the address that the element which [selector] finds on the page of [answer] refers to, and
     * returns the answer. The element is a link, or it names the stylesheet or the script of the page. If
     * [selector] finds several elements, the first one is taken.
     */
    fun follow(
        answer: Browser.Response,
        selector: String,
    ): Browser.Response {
        val found = answer.page.select(selector).firstOrNull() ?: error("The page has nothing for $selector: ${answer.page.title()}")
        return open(found.attr("href").ifEmpty { found.attr("src") })
    }

    /**
     * Submits [form] the way a browser does (see [Browser.submit]) and returns the page that the browser
     * ends up on. The form has to be one of a page of the walk. [pressed] is the button that is pressed,
     * which is the first button of the form unless a test names another one.
     */
    fun submit(
        form: FormElement,
        edits: Map<String, String?> = emptyMap(),
        files: Map<String, Browser.Chosen> = emptyMap(),
        pressed: Element? = null,
        rows: Map<String, List<String>> = emptyMap(),
    ): Browser.Response {
        val name = nameOf(form)
        if (name !in shown) failures += "No page of the walk showed the form $name."
        submitted += name
        // A browser sends a form with the method GET as a request for a page.
        if (name.startsWith("GET ")) opened += name.substringAfter(' ')
        val button = pressed ?: form.select("button").firstOrNull()
        if (withScript && form.hasAttr("hx-post")) {
            sentByScript += name
            return arrivedByScript(form, browser.submitWithScript(form, edits, files, button, rows), "The form $name")
        }
        return arrived(browser.submit(form, edits, files, button, rows), "The form $name")
    }

    /** Follows the redirects of [answer], takes note of the page that the browser ends up on, and returns it. */
    private fun arrived(
        answer: Browser.Response,
        what: String,
    ): Browser.Response {
        var current = answer
        var hops = 0
        while (current.status in REDIRECTS && hops < MOST_HOPS) {
            val target = current.header("location")!!.substringBefore('#')
            opened += target.substringBefore('?')
            current = browser.get(target)
            hops++
        }
        return noted(current, what)
    }

    /**
     * Does what the script of the UI does with [answer], which is the answer to [form], and returns the
     * page that the browser shows then. The script loads the page that the answer names, as a browser
     * does. Otherwise, it puts the answer into the page that showed the form (see [Swap]).
     */
    private fun arrivedByScript(
        form: FormElement,
        answer: Browser.Response,
        what: String,
    ): Browser.Response {
        val target = answer.header(Swap.REDIRECT)?.substringBefore('#')
        if (target == null) return noted(Swap.shown(form.ownerDocument()!!, form, answer), what)
        opened += target.substringBefore('?')
        return arrived(browser.get(target), what)
    }

    /** Takes note of [current], which is what the browser shows after [what], and returns it. */
    private fun noted(
        current: Browser.Response,
        what: String,
    ): Browser.Response {
        if (current.status != OK) failures += "$what led to the status ${current.status}: ${current.page.select("main").text().take(BRIEF)}"
        // Only a page has links and forms. A browser saves a file, or uses it for a page, whatever the file holds.
        if (!current.header("content-type").orEmpty().startsWith("text/html")) return current
        pages += current
        val referred = current.page.select("[href]").map { it.attr("href") } + current.page.select("[src]").map { it.attr("src") }
        linked += referred.map { it.substringBefore('#') }
        shown += current.page.select("form").map { nameOf(it as FormElement) }
        return current
    }

    companion object {
        private const val OK = 200
        private val REDIRECTS = setOf(302, 303)
        private const val MOST_HOPS = 5
        private const val BRIEF = 200

        /** Returns the name of [form] for the walk: its method in upper case and the path of its action, such as `POST /service/edit`. */
        fun nameOf(form: FormElement): String = "${form.attr("method").uppercase()} ${form.attr("action").substringBefore('?')}"
    }
}

/**
 * Returns the form on the page of this answer that is sent to [path], whatever the query string of its
 * action is. If the page has several such forms, the first one is taken.
 */
internal fun Browser.Response.formTo(path: String): FormElement {
    val forms = page.select("form").filter { it.attr("action").substringBefore('?') == path }
    return (forms.firstOrNull() ?: error("The page has no form for $path: ${page.title()}")) as FormElement
}
