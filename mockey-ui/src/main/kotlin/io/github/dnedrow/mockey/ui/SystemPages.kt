package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.SystemStatusDto
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.p

/**
 * The pages about the server itself: the page with its status, the switch between read-only mode and
 * writable mode, the reload of the definitions, the reset, and the button that clears the last-used times.
 * Switching to writable mode, reloading, and resetting each ask for confirmation first.
 *
 * See `docs/decisions/ui-pages.md` for the reasoning behind these pages.
 */
internal val SystemArea =
    Area { routing, ui ->
        with(routing) {
            get(SystemPages.PAGE) { ui.show(call) { view, system -> SystemPages.status(view, system) } }
            post(SystemPages.READ_ONLY) {
                ui.submit(call) {
                    ui.client.setReadOnly(true)
                    Outcome.Done(SystemPages.PAGE, SystemPages.READ_ONLY_NOTICE)
                }
            }
            getAndPost(SystemPages.WRITABLE) {
                ui.confirm(call, ask = { view, system -> SystemPages.writing(view, system) }) {
                    ui.client.setReadOnly(false)
                    Outcome.Done(SystemPages.PAGE, SystemPages.WRITABLE_NOTICE)
                }
            }
            getAndPost(SystemPages.RELOAD) {
                ui.confirm(call, ask = { view, system -> SystemPages.reloading(view, system) }) {
                    ui.client.reloadDefinitions()
                    Outcome.Done(SystemPages.PAGE, SystemPages.RELOADED_NOTICE)
                }
            }
            getAndPost(SystemPages.RESET) {
                ui.confirm(call, ask = { view, system -> SystemPages.resetting(view, system) }) {
                    ui.client.resetServer()
                    Outcome.Done(SystemPages.PAGE, SystemPages.RESET_NOTICE)
                }
            }
            post(SystemPages.CLEAR_LAST_USED) {
                ui.submit(call) {
                    ui.client.clearLastUsedTimes()
                    Outcome.Done(SystemPages.PAGE, SystemPages.CLEARED_NOTICE)
                }
            }
        }
    }

/** The pages about the server itself. */
internal object SystemPages {
    /** The path of the page with the status of the server. */
    const val PAGE = "/system"

    /** The path of the action that switches the server to read-only mode. */
    const val READ_ONLY = "/system/read-only"

    /** The path of the confirmation step that comes before the server switches to writable mode. */
    const val WRITABLE = "/system/writable"

    /** The path of the confirmation step that comes before the server reloads its definitions. */
    const val RELOAD = "/system/reload"

    /** The path of the confirmation step that comes before the server is reset. */
    const val RESET = "/system/reset"

    /** The path of the action that clears the last-used times. */
    const val CLEAR_LAST_USED = "/system/clear-last-used"

    /** The identifier of the section about the last-used times, which an address can refer to after a `#`. */
    const val LAST_USED_ANCHOR = "last-used-times"

    /** The notice to show after the server switched to read-only mode. */
    const val READ_ONLY_NOTICE = "The server is in read-only mode now. It keeps further changes in memory only."

    /** The notice to show after the server switched to writable mode. */
    const val WRITABLE_NOTICE = "The server is in writable mode now. It stores every change in its definitions location."

    /** The notice to show after the server reloaded its definitions. */
    const val RELOADED_NOTICE = "The server loaded its definitions again."

    /** The notice to show after the server was reset. */
    const val RESET_NOTICE = "The server was reset. It holds no definitions and no history now."

    /** The notice to show after the last-used times were cleared. */
    const val CLEARED_NOTICE = "The last-used times were cleared."

    /**
     * Returns the page that shows what the server reports about itself, which is [system], along with the
     * links and buttons that switch its mode, reload its definitions, reset it, and clear the last-used
     * times.
     */
    fun status(
        view: View,
        system: SystemStatusDto,
    ): Page =
        Page("Server") {
            dl {
                dt { +"Version" }
                dd { +system.version }
                dt { +"Running since" }
                dd { timeOf(system.startTime) }
                dt { +"Definitions location" }
                dd { code { +system.definitionsLocation } }
                dt { +"Mode" }
                dd { +(if (system.readOnly) IS_READ_ONLY else IS_WRITABLE) }
                dt { +"Mock endpoint" }
                dd { urlOf("${system.mockBaseUrl}/service/") }
                dt { +"Tag filter" }
                dd { if (system.tagFilter.isEmpty()) +NONE else tagsOf(system.tagFilter) }
                dt { +"Enabled twist configuration" }
                dd { +(system.enabledTwist?.name ?: NONE) }
                dt { +"Legacy shim" }
                dd { +(if (system.legacyShimEnabled) SHIM_IS_ON else SHIM_IS_OFF) }
            }
            p(classes = "actions") { a(href = ProxyPages.PAGE) { +"Outbound proxy and upstream session" } }
            h2 { +"Read-only mode" }
            modeSwitchOf(view, system)
            h2 { +"Definitions" }
            p { +ABOUT_RELOAD_AND_RESET }
            p(classes = "actions") {
                a(href = RELOAD) { +"Reload the definitions" }
                a(href = RESET) { +"Reset the server" }
            }
            h2 {
                id = LAST_USED_ANCHOR
                +"Last-used times"
            }
            p { +ABOUT_LAST_USED }
            postForm(view, CLEAR_LAST_USED) { submit("Clear the last-used times") }
        }

    /**
     * Returns the page that asks whether to switch the server to writable mode. The switch stores the
     * definitions that the server holds in memory, so the page names the definitions location of [system].
     * If the server is in writable mode already, the page says so instead of asking.
     */
    fun writing(
        view: View,
        system: SystemStatusDto,
    ): Page {
        if (!system.readOnly) {
            return Page("The server is in writable mode already") {
                p { +IS_WRITABLE }
                p(classes = "actions") { a(href = PAGE) { +"Back to the server" } }
            }
        }
        val stores =
            "The server stores the definitions that it holds now in ${system.definitionsLocation}. " +
                "They take the place of the definitions that are stored there."
        val consequences = listOf(stores, STORES_FROM_THEN_ON)
        return Confirmation.page(view, "Switch the server to writable mode?", consequences, "Switch to writable mode", WRITABLE, PAGE)
    }

    /**
     * Returns the page that asks whether to reload the definitions. In read-only mode, a reload discards
     * every change that the server has not stored, so the page says in which mode [system] is.
     */
    fun reloading(
        view: View,
        system: SystemStatusDto,
    ): Page {
        val reads =
            "The server reads the definitions in ${system.definitionsLocation} again. " +
                "They take the place of the definitions that the server holds in memory."
        val consequences = listOf(reads, if (system.readOnly) UNSTORED_ARE_LOST else ALL_ARE_STORED, RELOAD_KEEPS)
        return Confirmation.page(view, "Reload the definitions?", consequences, "Reload the definitions", RELOAD, PAGE) { copyAdvice() }
    }

    /**
     * Returns the page that asks whether to reset the server. In writable mode, a reset also removes the
     * stored definitions, so the page says in which mode [system] is.
     */
    fun resetting(
        view: View,
        system: SystemStatusDto,
    ): Page {
        val location = system.definitionsLocation
        val stored =
            if (system.readOnly) {
                "The server is in read-only mode, so the definitions that are stored in $location stay as they are."
            } else {
                "The server is in writable mode, so the definitions that are stored in $location are removed as well."
            }
        val consequences = listOf(RESET_REMOVES, RESET_EMPTIES, stored)
        return Confirmation.page(view, "Reset the server?", consequences, "Reset the server", RESET, PAGE) { copyAdvice() }
    }

    /** Shows what a switch of the mode does, along with the link or the button that switches it. */
    private fun FlowContent.modeSwitchOf(
        view: View,
        system: SystemStatusDto,
    ) {
        if (system.readOnly) {
            p { +ABOUT_WRITABLE }
            p(classes = "actions") { a(href = WRITABLE) { +"Switch to writable mode" } }
        } else {
            p { +ABOUT_READ_ONLY }
            postForm(view, READ_ONLY) { submit("Switch to read-only mode") }
        }
    }

    /** Offers the download of the definitions, for a page that asks before definitions are discarded. */
    private fun FlowContent.copyAdvice() {
        p {
            +"To keep a copy of the definitions that the server holds now, "
            a(href = DefinitionsPages.EXPORT) { +"download the definitions" }
            +" first."
        }
    }

    private const val NONE = "none"
    private const val IS_READ_ONLY = "Read-only: the server keeps changes in memory, and stores none of them."
    private const val IS_WRITABLE = "Writable: the server stores every change in its definitions location."
    private const val SHIM_IS_ON = "Enabled: the server answers the automation endpoints of Mockey 2.x."
    private const val SHIM_IS_OFF = "Disabled: the server does not answer the automation endpoints of Mockey 2.x."

    private const val ABOUT_WRITABLE =
        "In writable mode, the server stores every change in its definitions location. " +
            "The switch also stores the changes that were made in read-only mode."
    private const val ABOUT_READ_ONLY =
        "In read-only mode, the server keeps every change in memory and leaves its definitions location as it is."
    private const val STORES_FROM_THEN_ON = "From then on, the server stores every change."

    private const val ABOUT_RELOAD_AND_RESET =
        "A reload reads the definitions location again and puts what it finds in the place of the definitions in memory. " +
            "A reset removes all definitions and empties the history. Both ask for confirmation first."
    private const val UNSTORED_ARE_LOST = "The server is in read-only mode. Every change that it has not stored is lost."
    private const val ALL_ARE_STORED = "The server is in writable mode, so it has stored every change already."
    private const val RELOAD_KEEPS =
        "The server forgets the credentials of the outbound proxy, and it applies the default plan if there is one. " +
            "The history, the tag filter, and the last-used times stay as they are."

    private const val RESET_REMOVES =
        "The server removes every service with its scenarios, every plan, and every twist configuration. " +
            "It clears the settings of the outbound proxy, the universal error scenario, the default plan, and the tag filter."
    private const val RESET_EMPTIES = "It also empties the history and clears the last-used times."

    private const val ABOUT_LAST_USED =
        "The server remembers when each service and each scenario last answered a request, and when each plan was last applied. " +
            "It keeps these times in memory until it stops."
}
