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
 */
internal class Journey(
    private val browser: Browser,
    private val api: FakeAdminApi,
) {
    /** The addresses that the walk may open: the first page, and every address that a page of the walk refers to. */
    private val linked = mutableSetOf("/")

    /** The paths that the walk asked for with the method GET, whether it opened them or a redirect or a form led to them. */
    val opened: MutableSet<String> = sortedSetOf()

    /** The forms that the pages of the walk showed, each as its method and the path of its action (see [nameOf]). */
    val shown: MutableSet<String> = sortedSetOf()

    /** The forms that the walk submitted, named the same way. */
    val submitted: MutableSet<String> = sortedSetOf()

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
     * returns the answer. The element is a link, or it names the stylesheet of the page. If [selector]
     * finds several elements, the first one is taken.
     */
    fun follow(
        answer: Browser.Response,
        selector: String,
    ): Browser.Response {
        val found = answer.page.select(selector).firstOrNull() ?: error("The page has nothing for $selector: ${answer.page.title()}")
        return open(found.attr("href"))
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
        if (current.status != OK) failures += "$what led to the status ${current.status}: ${current.page.select("main").text().take(BRIEF)}"
        // Only a page has links and forms. A browser saves a file, or uses it for a page, whatever the file holds.
        if (!current.header("content-type").orEmpty().startsWith("text/html")) return current
        linked += current.page.select("[href]").map { it.attr("href").substringBefore('#') }
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
