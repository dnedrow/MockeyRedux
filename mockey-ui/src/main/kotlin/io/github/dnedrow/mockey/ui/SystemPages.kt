package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.SystemStatusDto
import io.ktor.server.routing.get
import kotlinx.html.a
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.p

/** The area of the pages about the server itself. */
internal val SystemArea =
    Area { routing, ui ->
        routing.get("/system") { ui.show(call) { _, system -> SystemPages.status(system) } }
    }

/** The pages about the server itself. */
internal object SystemPages {
    /** The page that shows what the server states of itself, which is [system]. */
    fun status(system: SystemStatusDto): Page =
        Page("Server") {
            dl {
                dt { +"Version" }
                dd { +system.version }
                dt { +"Running since" }
                dd { timeOf(system.startTime) }
                dt { +"Definitions location" }
                dd { code { +system.definitionsLocation } }
                dt { +"Mode" }
                dd { +(if (system.readOnly) READ_ONLY else WRITABLE) }
                dt { +"Mock endpoint" }
                dd { urlOf("${system.mockBaseUrl}/service/") }
                dt { +"Tag filter" }
                dd { if (system.tagFilter.isEmpty()) +NONE else tagsOf(system.tagFilter) }
                dt { +"Enabled twist configuration" }
                dd { +(system.enabledTwist?.name ?: NONE) }
            }
            p(classes = "actions") { a(href = ProxyPages.PAGE) { +"Outbound proxy and upstream session" } }
        }

    private const val NONE = "none"
    private const val READ_ONLY = "Read-only: the server keeps changes in memory, and stores none of them."
    private const val WRITABLE = "Writable: the server stores every change in its definitions location."
}
