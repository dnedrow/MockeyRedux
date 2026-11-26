package io.github.dnedrow.mockey.ui

import io.ktor.server.application.ApplicationCall
import kotlinx.html.CommonAttributeGroupFacade
import kotlinx.html.FORM
import kotlinx.html.TagConsumer
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What the UI does for a browser that runs scripts. Every page loads one script, which is htmx. Where a page
 * marks an element for it, the script sends the request of that element itself and puts the answer into the
 * page, so the browser doesn't load a whole page.
 *
 * Nothing depends on the script. An element that is marked for it is a form or a part of a page that works
 * without it: the script sends the same request to the same address, and the server ends up in the same
 * state. The only difference is the header [REQUEST_HEADER], by which the UI knows that it can answer with a
 * part of a page.
 *
 * The UI answers a request of the script in one of three ways:
 *
 * - With a [Part], which names the element of the page that it takes the place of.
 * - With the address of a page that the script is to load, see [REDIRECT].
 * - With a whole page, as for any other request. The script shows it in the place of the page that the
 *   browser shows, see [SETTINGS].
 *
 * See `docs/decisions/ui-stack.md` for the reasoning, and `docs/third-party.md` for where the script comes
 * from.
 */
internal object Enhancement {
    /** The name of the script among the files of the UI, see [Assets]. */
    const val SCRIPT = "htmx.min.js"

    /** The header that the script adds to every request that it sends, with the value `true`. */
    const val REQUEST_HEADER = "HX-Request"

    /** The header of an answer that tells the script to load the page at the address that it holds. */
    const val REDIRECT = "HX-Redirect"

    /** The header of an answer that names the element of the page that the answer goes into. */
    const val RETARGET = "HX-Retarget"

    /** The header of an answer that says how the answer goes into that element. */
    const val RESWAP = "HX-Reswap"

    /**
     * The settings of the script, which every page states in its head:
     *
     * - The script adds no style to the page, and it evaluates no text as a script. The content security
     *   policy of the UI forbids both.
     * - The script runs no script that an answer holds.
     * - The script keeps no copy of a page in the storage of the browser.
     * - The script puts an answer in the place of the whole page, whatever its status is. That's what a
     *   browser does with the answer to a form, also when something between the browser and the UI sent
     *   it. An answer that is a part of a page says where it goes instead, see [Part]. An answer without
     *   content changes nothing.
     */
    val SETTINGS: String =
        buildJsonObject {
            put("includeIndicatorStyles", false)
            put("allowEval", false)
            put("allowScriptTags", false)
            put("historyCacheSize", 0)
            putJsonArray("responseHandling") {
                addJsonObject {
                    put("code", "204")
                    put("swap", false)
                }
                addJsonObject {
                    put("code", "...")
                    put("swap", true)
                    put("target", "body")
                    put("swapOverride", "innerHTML")
                }
            }
        }.toString()

    /** The identifier of the element that says what a form did, which is there on every page. */
    const val NOTICE_ID = "notice"

    /** Returns whether the script of a page sent the request of [call]. */
    fun isRequested(call: ApplicationCall): Boolean = call.request.headers[REQUEST_HEADER] == "true"

    /**
     * Marks [form] for the script: it sends the form to [action] itself, the way a browser would, and puts
     * the answer into the page. [withFiles] says whether the form holds files.
     */
    fun mark(
        form: FORM,
        action: String,
        withFiles: Boolean,
    ) {
        form.attributes["hx-post"] = action
        if (withFiles) form.attributes["hx-encoding"] = "multipart/form-data"
    }

    /**
     * Marks [element] as a part that the script brings up to date: every [seconds] seconds, the script asks
     * for [address], which is the address of the page, and puts the answer into the page.
     */
    fun refresh(
        element: CommonAttributeGroupFacade,
        address: String,
        seconds: Int,
    ) {
        element.attributes["hx-get"] = address
        element.attributes["hx-trigger"] = "every ${seconds}s"
    }

    /**
     * Marks [element] as one whose content goes into the element of the page that has the same identifier,
     * wherever that one is, in the place of the content that it has. The element of the page itself stays,
     * so a screen reader that watches it announces the new content.
     */
    fun markOutOfBand(element: CommonAttributeGroupFacade) {
        element.attributes["hx-swap-oob"] = "innerHTML"
    }
}

/**
 * The place of a [Part] in the page that a browser shows: the element that the part takes the place of.
 * [target] is how the script finds that element, starting from the element that sent the request. [swap]
 * says how the script puts the part there, which is in the place of the element unless it says more.
 */
internal class Place private constructor(
    val target: String,
    val swap: String = "outerHTML",
) {
    companion object {
        /**
         * Everything that the page shows between its navigation and its foot. The browser then shows the
         * start of the page, as it does after it has loaded one. This is for a form that is shown again
         * with its messages.
         */
        val MAIN = Place("main", "outerHTML show:window:top")

        /** The table row that holds the form that was sent. This is for a switch in a list. */
        val ROW = Place("closest tr")

        /** Returns the place of the element with the identifier [id]. */
        fun of(id: String): Place = Place("#$id")
    }
}

/**
 * A part of a page: what [content] writes, which is one element. The script of a page asks for a part and
 * puts it in the [place] of the element that the page shows there.
 */
internal class Part(
    val place: Place,
    val content: TagConsumer<*>.() -> Unit,
)

/**
 * A part of a page that shows what the server holds from moment to moment, such as the list of the history.
 * Every [seconds] seconds, the page is brought up to date. A browser without scripting loads the whole page
 * again. A browser with scripting asks for the address of the page and gets [part] alone, which takes the
 * place of the part that it shows.
 */
internal class Live(
    val seconds: Int,
    val part: Part,
)
