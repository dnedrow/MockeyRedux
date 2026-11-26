package io.github.dnedrow.mockey.ui

import io.ktor.server.routing.get
import kotlinx.html.FlowContent
import kotlinx.html.FlowOrPhrasingContent
import kotlinx.html.ThScope
import kotlinx.html.a
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.h2
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.pre
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul

/**
 * The area of the built-in help: a page that lists the topics, and a page for each topic. The help is part
 * of the UI itself, so it needs nothing from the server and refers to nothing outside the UI. The UI shows
 * it whatever the server says.
 *
 * See `docs/decisions/ui-pages.md` for the reasoning behind these pages.
 */
internal val HelpArea =
    Area { routing, ui ->
        with(routing) {
            get(HelpPages.INDEX) { ui.showAnyway(call, HelpPages.index()) }
            for (topic in HelpTopics.all) get(HelpPages.addressOf(topic)) { ui.showAnyway(call, HelpPages.page(topic)) }
        }
    }

/**
 * A topic of the help: its [name] in the address of its page, its [title], and a [summary] of one sentence
 * for the list of the topics. [places] are the pages of the UI that the topic is about, each as the text of
 * its link and its address. [related] are the names of the topics that continue from this one, and [content]
 * writes the page.
 */
internal class HelpTopic(
    val name: String,
    val title: String,
    val summary: String,
    val places: List<Pair<String, String>> = emptyList(),
    val related: List<String> = emptyList(),
    val content: FlowContent.() -> Unit,
)

/** The pages of the help. */
internal object HelpPages {
    /** The address of the page that lists the topics. */
    const val INDEX = "/help"

    /** Returns the address of the page of [topic]. */
    fun addressOf(topic: HelpTopic): String = "$INDEX/${topic.name}"

    /** Returns the page that says what Mockey is for and lists the topics, each with its summary. */
    fun index(): Page =
        Page("Help") {
            div(classes = "help") {
                prose(HelpTopics.INTRODUCTION)
                h2 { +"Topics" }
                dl(classes = "topics") {
                    for (topic in HelpTopics.all) {
                        dt { a(href = addressOf(topic)) { +topic.title } }
                        dd { +topic.summary }
                    }
                }
                h2 { +"More to read" }
                prose(HelpTopics.MORE_TO_READ)
            }
        }

    /** Returns the page of [topic]: its content, the pages of the UI that it's about, and the topics that continue from it. */
    fun page(topic: HelpTopic): Page =
        Page("Help: ${topic.title}") {
            div(classes = "help") {
                topic.content(this)
                if (topic.places.isNotEmpty()) {
                    h2 { +"Where to find it" }
                    ul { topic.places.forEach { (text, address) -> li { a(href = address) { +text } } } }
                }
                h2 { +"More help" }
                ul {
                    for (other in topic.related.map(HelpTopics::named)) li { a(href = addressOf(other)) { +other.title } }
                    li { a(href = INDEX) { +"All topics" } }
                }
            }
        }
}

/**
 * Writes [text] as paragraphs and lists. Blocks of the text have one or more empty lines between them, and a
 * line of nothing but spaces counts as empty. A block whose first line begins with `- ` is a list, in which
 * every line that begins that way starts an item. Any other block is a paragraph. Line breaks within a block
 * count as spaces, and what stands between two backticks is written as code.
 */
internal fun FlowContent.prose(text: String) {
    for (block in text.trim().split(BLANK_LINES)) {
        val lines = block.lines().map { it.trim() }
        if (!lines.first().startsWith(ITEM)) {
            p { marked(lines.joinToString(" ")) }
            continue
        }
        val items = mutableListOf<String>()
        for (line in lines) if (line.startsWith(ITEM)) items += line.removePrefix(ITEM) else items[items.lastIndex] += " $line"
        ul { items.forEach { li { marked(it) } } }
    }
}

/** Writes [text] as it is, on its own lines, as an example of what someone writes or reads. */
internal fun FlowContent.example(text: String) {
    pre(classes = "example") { code { +text.trimIndent().trim() } }
}

/**
 * Writes a table with the column headings [headings] and the rows [rows]. A cell is text in which backticks
 * mark code, as in [prose].
 */
internal fun FlowContent.rows(
    headings: List<String>,
    rows: List<List<String>>,
) {
    table {
        thead { tr { headings.forEach { th(scope = ThScope.col) { +it } } } }
        tbody { rows.forEach { row -> tr { row.forEach { td { marked(it) } } } } }
    }
}

/** Writes [text], with what stands between two backticks as code. */
private fun FlowOrPhrasingContent.marked(text: String) {
    text.split('`').forEachIndexed { index, part -> if (index % 2 == 1) code { +part } else +part }
}

private val BLANK_LINES = Regex("""\n(?:[ \t]*\n)+""")
private const val ITEM = "- "
