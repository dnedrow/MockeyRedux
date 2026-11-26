package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.SystemStatusDto
import io.ktor.http.HttpStatusCode
import kotlinx.html.FlowContent
import kotlinx.html.MetaHttpEquiv
import kotlinx.html.TagConsumer
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.footer
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.html
import kotlinx.html.id
import kotlinx.html.lang
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.noScript
import kotlinx.html.p
import kotlinx.html.role
import kotlinx.html.script
import kotlinx.html.stream.appendHTML
import kotlinx.html.title
import kotlinx.html.ul

/**
 * A page of the UI: its [title], the [status] that it is answered with, and what it shows below its
 * heading, in [content].
 *
 * A page is written with the builders of kotlinx.html, which write every text and every value of an
 * attribute so that a browser reads it as text. So what a page shows of the server, such as a name or a
 * body, is never read as markup.
 */
internal class Page(
    val title: String,
    val status: HttpStatusCode = HttpStatusCode.OK,
    /** The part of the page that is brought up to date by itself, or `null` for a page that stays as it is. */
    val live: Live? = null,
    val content: FlowContent.() -> Unit,
)

/**
 * A section of the UI that the navigation of every page leads to: the [path] of its first page, and what the
 * link says. The pages of the section are the ones whose paths begin as [path] does, and the ones whose
 * paths begin with one of [others].
 */
internal class Section(
    val path: String,
    val label: String,
    private val others: Set<String> = emptySet(),
) {
    /** Returns whether the page at [path] is one of the section. */
    fun holds(path: String): Boolean = "/${path.removePrefix("/").substringBefore('/')}".let { it == this.path || it in others }
}

/**
 * The frame of every page: the head, with the stylesheet, the icon, and the script of the UI, the
 * navigation, and the foot, which names the server that the UI manages.
 *
 * Everything that a page refers to is a path of the UI itself, so a browser that shows a page asks no other
 * place for anything.
 */
internal object Html {
    /** The paths outside `/services` whose pages belong to the section of the services. */
    private val ABOUT_SERVICES = setOf(ServicePages.PAGE, ScenarioPages.PAGE, DiagnosticsPages.PATH, SearchPages.PAGE)

    /** The sections of the UI, in the order of the navigation. */
    val sections: List<Section> =
        listOf(
            Section(ServicesPages.OVERVIEW, "Services", ABOUT_SERVICES),
            Section(HistoryPages.LIST, "History", setOf(StatisticsPages.PAGE)),
            Section(PlanPages.LIST, "Plans", setOf(PlanPages.PAGE)),
            Section(TwistPages.LIST, "Twisting", setOf(TwistPages.PAGE)),
            Section(TagPages.PAGE, "Tags", setOf("/tag")),
            Section(DefinitionsPages.PAGE, "Import/Export"),
            Section(SystemPages.PAGE, "Server", setOf(ProxyPages.PAGE)),
            Section(HelpPages.INDEX, "Help"),
        )

    /**
     * Returns [page] as an HTML document. [path] is the path at which the page is shown, by which the
     * navigation marks the section of the page. [adminUrl] is where the server is that the UI manages, and
     * [system] is what that server states of itself, or `null` when it stated nothing.
     *
     * The page says [notice] above its heading, which is what the UI says of a form that it has done, and
     * [trouble], which is why a page that needs nothing of the server shows nothing of it.
     */
    fun document(
        page: Page,
        path: String,
        adminUrl: AdminUrl,
        system: SystemStatusDto?,
        notice: String? = null,
        trouble: String? = null,
    ): String =
        buildString {
            append("<!DOCTYPE html>\n")
            appendHTML(prettyPrint = false).html {
                lang = "en"
                head {
                    meta(charset = "utf-8")
                    meta(name = "viewport", content = "width=device-width, initial-scale=1")
                    meta(name = "htmx-config", content = Enhancement.SETTINGS)
                    page.live?.let { live ->
                        // A browser that runs scripts skips this element. The script brings the page up to date there.
                        noScript {
                            meta {
                                httpEquiv = MetaHttpEquiv.refresh
                                content = "${live.seconds}"
                            }
                        }
                    }
                    title("${page.title} - Mockey")
                    link(rel = "stylesheet", href = Assets.path("mockey.css"))
                    link(rel = "icon", href = Assets.path("favicon.svg"), type = "image/svg+xml")
                    script(src = Assets.path(Enhancement.SCRIPT)) { defer = true }
                }
                body {
                    header {
                        a(href = "/", classes = "brand") { +"Mockey" }
                        navigation(path)
                    }
                    consumer.mainOf(page, notice, trouble)
                    footer {
                        p { +serverLine(adminUrl, system) }
                        p { +"Mockey UI ${UiVersion.text}" }
                    }
                }
            }
        }

    /**
     * Returns the part of [page] between the navigation and the foot, which is what a page shows of its
     * own. The script of a page puts it in the place of that part where a form is shown again.
     */
    fun mainPartOf(page: Page): Part = Part(Place.MAIN) { mainOf(page) }

    /**
     * Returns what [part] writes as HTML that stands for itself, without the frame of a page. With
     * [notice], a notice element follows the part. It's marked so that what it says goes into the notice
     * element of the page that the part goes into.
     */
    fun partial(
        part: Part,
        notice: String? = null,
    ): String =
        buildString {
            val consumer = appendHTML(prettyPrint = false)
            part.content(consumer)
            if (notice != null) consumer.noticeOf(notice, outOfBand = true)
        }

    /**
     * Writes what [page] shows between the navigation and the foot: what the UI says of a form that it has
     * done, which is [notice], then [trouble], the heading, and the content of the page.
     */
    private fun TagConsumer<*>.mainOf(
        page: Page,
        notice: String? = null,
        trouble: String? = null,
    ) {
        main {
            consumer.noticeOf(notice)
            if (trouble != null) {
                p(classes = "problem") {
                    +"$trouble "
                    a(href = SystemPages.PAGE) { +"More about it" }
                    +"."
                }
            }
            h1 { +page.title }
            page.content(this)
        }
    }

    /**
     * Writes the element that says [notice], which is what the UI says of a form that it has done. The
     * element is there without a notice as well, and empty then, so that a script can put a notice into
     * the page later and a screen reader announces it. [outOfBand] marks the element as one whose content
     * goes into the notice element of a page, see [Enhancement.markOutOfBand].
     */
    private fun TagConsumer<*>.noticeOf(
        notice: String?,
        outOfBand: Boolean = false,
    ) {
        div(classes = "notice") {
            id = Enhancement.NOTICE_ID
            role = "status"
            if (outOfBand) Enhancement.markOutOfBand(this)
            if (notice != null) +notice
        }
    }

    /**
     * Returns a document that says [text] under [title] and nothing else: it has no navigation, names no
     * server, and refers to nothing. It is for a request that the UI does not take for one of a browser that
     * shows its pages.
     */
    fun bare(
        title: String,
        text: String,
    ): String =
        buildString {
            append("<!DOCTYPE html>\n")
            appendHTML(prettyPrint = false).html {
                lang = "en"
                head {
                    meta(charset = "utf-8")
                    title("$title - Mockey")
                }
                body {
                    h1 { +title }
                    p { +text }
                }
            }
        }

    /** The links to the sections of the UI. The link to the section of the page at [path] says that it is the current one. */
    private fun FlowContent.navigation(path: String) {
        nav {
            attributes["aria-label"] = "Sections"
            ul {
                for (section in sections) {
                    li {
                        a(href = section.path) {
                            if (section.holds(path)) attributes["aria-current"] = "page"
                            +section.label
                        }
                    }
                }
            }
        }
    }

    /** Returns what the foot of a page says of the server at [adminUrl], which stated [system] of itself. */
    private fun serverLine(
        adminUrl: AdminUrl,
        system: SystemStatusDto?,
    ): String {
        if (system == null) return "Server at $adminUrl"
        val mode = if (system.readOnly) "read-only mode" else "writable mode"
        return "Server at $adminUrl, version ${system.version}, in $mode"
    }
}
