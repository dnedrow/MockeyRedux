package io.github.dnedrow.mockey.server

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.output.AbstractHelpFormatter
import com.github.ajalt.clikt.output.HelpFormatter
import com.github.ajalt.clikt.output.HelpFormatter.ParameterHelp

/**
 * The layout of the help of the server program. An option stands on a line of its own, and the lines below
 * it hold its description, its environment variable, and its default. Texts are broken at [WIDTH]
 * characters, so that the help fits a terminal, which a table of the options would not.
 */
internal class SettingsHelpFormatter(
    context: Context,
) : AbstractHelpFormatter<String>(context, showDefaultValues = true) {
    override fun formatHelp(
        error: UsageError?,
        prolog: String,
        epilog: String,
        parameters: List<ParameterHelp>,
        programName: String,
    ): String = collectHelpParts(error, prolog, epilog, parameters, programName).joinToString("\n\n")

    override fun renderError(
        parameters: List<ParameterHelp>,
        error: UsageError,
    ): String = renderErrorString(parameters, error)

    override fun renderUsage(
        parameters: List<ParameterHelp>,
        programName: String,
    ): String =
        listOf(localization.usageTitle(), programName, renderUsageParametersString(parameters))
            .filter {
                it.isNotEmpty()
            }.joinToString(" ")

    override fun renderProlog(prolog: String): String = paragraphs(prolog)

    override fun renderEpilog(epilog: String): String = paragraphs(epilog)

    override fun renderParameters(parameters: List<ParameterHelp>): String =
        collectParameterSections(parameters).joinToString("\n\n") { (title, content) -> "$title\n$content" }

    override fun renderOptionGroup(
        help: String?,
        parameters: List<ParameterHelp.Option>,
    ): String = buildParameterList(parameters.map(::renderOptionDefinition))

    override fun renderDefinitionTerm(row: DefinitionRow): String = TERM_INDENT + row.term

    override fun renderDefinitionDescription(row: DefinitionRow): String =
        row.description
            .lines()
            .flatMap { broken(it, WIDTH - DESCRIPTION_INDENT.length) }
            .joinToString("\n") { DESCRIPTION_INDENT + it }

    override fun buildParameterList(rows: List<DefinitionRow>): String =
        rows.joinToString("\n\n") { row ->
            listOf(renderDefinitionTerm(row), renderDefinitionDescription(row)).filter { it.isNotEmpty() }.joinToString("\n")
        }

    /** Returns the description of a parameter: its [help], and below it a line for each of its [tags]. */
    override fun renderParameterHelpText(
        help: String,
        tags: Map<String, String>,
    ): String {
        val notes = tags.filter { (tag, value) -> shouldShowTag(tag, value) }.map { (tag, value) -> renderTag(tag, value) }
        return (listOf(help) + notes).filter { it.isNotEmpty() }.joinToString("\n")
    }

    override fun renderTag(
        tag: String,
        value: String,
    ): String =
        when (tag) {
            VARIABLE -> "Environment variable: $value"
            HelpFormatter.Tags.DEFAULT -> "Default: $value"
            else -> super.renderTag(tag, value)
        }

    /** Returns [text] with each of its paragraphs, which empty lines separate, broken into lines anew. */
    private fun paragraphs(text: String): String =
        text
            .trim()
            .split(EMPTY_LINES)
            .joinToString("\n\n") { paragraph -> broken(paragraph.lines().joinToString(" ") { it.trim() }, WIDTH).joinToString("\n") }

    /** Returns [text] in lines of at most [width] characters, broken at its spaces. A longer word keeps a line to itself. */
    private fun broken(
        text: String,
        width: Int,
    ): List<String> {
        val lines = mutableListOf<String>()
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val last = lines.lastOrNull()
            if (last == null || last.length + 1 + word.length > width) lines += word else lines[lines.lastIndex] = "$last $word"
        }
        return lines
    }

    internal companion object {
        /** The help tag of an option that holds the name of its environment variable. */
        const val VARIABLE = "variable"

        /** The number of characters that a line of the help has at most, but for a line with one long word. */
        const val WIDTH = 80

        private const val TERM_INDENT = "  "
        private const val DESCRIPTION_INDENT = "      "
        private val EMPTY_LINES = Regex("""\n\s*\n""")
    }
}
