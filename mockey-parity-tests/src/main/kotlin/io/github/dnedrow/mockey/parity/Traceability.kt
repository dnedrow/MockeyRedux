package io.github.dnedrow.mockey.parity

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/**
 * Checks the traceability table, which maps the HTTP surface of legacy to the new system. Every row of the
 * table has to have a home, and what the table names as a home has to be there.
 *
 * The table is a Markdown document. Its last part, "Legacy tests", maps the test classes of legacy to their
 * ports and is not checked here.
 */
object Traceability {
    /**
     * What is wrong with the table at [table], one line for each problem. The table is in order when all of
     * this holds:
     *
     * - No row is marked as open.
     * - Every row ends in a requirement of the specifications, in a parity case, or in a non-goal.
     * - Every requirement that the table cites is a requirement of the capability that it's cited for. The
     *   capabilities are the directories of the `spec.md` files below [specifications].
     * - Every parity case that the table names is a recording in [recordings], the start of the names of
     *   several recordings, or a group of cases with a fixture in [fixtures].
     * - Every deviation that the table names is one of [registeredDeviations].
     */
    fun problems(
        table: Path,
        specifications: Path,
        recordings: Path,
        fixtures: Path,
        registeredDeviations: Set<String>,
    ): List<String> {
        val lines = Files.readAllLines(table)
        val checked = lines.takeWhile { !it.startsWith(NOT_CHECKED) }
        val names = recordings.listDirectoryEntries().filter { it.isDirectory() }.map { it.name }
        val groups = fixtures.listDirectoryEntries().filter { it.isRegularFile() }.map { it.nameWithoutExtension }
        val cited = Cited(requirementsIn(specifications), RecordingReferences(names.toSet()), groups.toSet(), registeredDeviations)
        return checked.flatMapIndexed { index, line ->
            val row = if (isRow(line, checked.getOrNull(index + 1))) rowProblems(line, cited) else emptyList()
            (row + cited.problemsIn(line)).map { "line ${index + 1}: $it" }
        }
    }

    /**
     * Returns the names of the requirements of every capability below [root]. A capability is a directory
     * with a file `spec.md`, and a requirement is a heading of that file. A capability that several files
     * describe, as a change and the specifications that it was merged into do, has the requirements of all.
     */
    fun requirementsIn(root: Path): Map<String, Set<String>> =
        Files.walk(root).use { paths ->
            paths
                .filter { it.name == SPECIFICATION && it.isRegularFile() }
                .toList()
                .groupBy({ it.parent.name }, { file -> Files.readAllLines(file).mapNotNull(::requirementOf) })
                .mapValues { (_, names) -> names.flatten().toSet() }
        }

    private fun requirementOf(line: String): String? = line.takeIf { it.startsWith(REQUIREMENT) }?.removePrefix(REQUIREMENT)?.trim()

    /** Whether [line] is a row of a table with content: neither the row of the headings, which [next] underlines, nor that line. */
    private fun isRow(
        line: String,
        next: String?,
    ): Boolean = line.startsWith("|") && !underline.matches(line) && !(next != null && underline.matches(next))

    /** What is wrong with the home of the row [line]. Its first cell says what the row is about, and its last cell is the home. */
    private fun rowProblems(
        line: String,
        cited: Cited,
    ): List<String> {
        val inside = line.trim().removePrefix("|").removeSuffix("|")
        val cells = inside.split(cellBar).map { it.trim() }
        val subject = cells.first()
        val home = cells.last()
        return when {
            home.startsWith(OPEN) -> listOf("the row $subject has no place yet")
            home.startsWith(NON_GOAL) || cited.namesAHome(home) -> emptyList()
            else -> listOf("the row $subject ends in no requirement, no parity case, and no non-goal")
        }
    }

    /** What a table can cite, and what there is of it. */
    private class Cited(
        private val requirements: Map<String, Set<String>>,
        private val references: RecordingReferences,
        private val groups: Set<String>,
        private val deviations: Set<String>,
    ) {
        /** Whether [home] names a requirement of a capability or a parity case. */
        fun namesAHome(home: String): Boolean =
            capability.findAll(home).any { it.groupValues[1] in requirements } || references.referencesIn(home).isNotEmpty()

        /** What [text] cites that is not there. */
        fun problemsIn(text: String): List<String> {
            val ofRequirements =
                capability.findAll(text).flatMap { requirementProblems(it.groupValues[1], text.substring(it.range.last + 1)) }
            val ofCases = references.referencesIn(text).filter { references.recordingsOf(it).isEmpty() && it !in groups }
            val ofDeviations = deviation.findAll(text).map { it.value }.filter { it !in deviations }
            return ofRequirements.toList() +
                ofCases.map { "`$it` is no recording, no start of the names of recordings, and no group of cases" } +
                ofDeviations.distinct().map { "$it is not in the deviation register" }
        }

        /**
         * What is wrong with the requirements that a text cites for [capability] in what comes [after] the
         * name of the capability: one requirement, or several with a comma between them, or every
         * requirement. What follows a requirement in lower case is said about it and cites nothing.
         */
        private fun requirementProblems(
            capability: String,
            after: String,
        ): List<String> {
            val known = requirements[capability] ?: return listOf("`$capability` is no capability of the specifications")
            var rest = after
            while (!rest.startsWith(EVERY_REQUIREMENT)) {
                val name = known.filter { rest.startsWith(it) }.maxByOrNull { it.length }
                if (name == null) return listOf("`$capability` has no requirement '${shown(rest)}'")
                rest = rest.removePrefix(name)
                // After a comma, a word with a capital letter begins the name of the next requirement.
                if (!rest.startsWith(", ") || rest.getOrNull(2)?.isUpperCase() != true) break
                rest = rest.drop(2)
            }
            return emptyList()
        }

        /** Returns the start of [text] up to where a name ends, as a problem shows it. */
        private fun shown(text: String): String = text.takeWhile { it !in NAME_ENDS }.take(SHOWN).trim()
    }

    private const val SPECIFICATION = "spec.md"
    private const val REQUIREMENT = "### Requirement:"
    private const val NOT_CHECKED = "## Legacy tests"
    private const val OPEN = "**Open"
    private const val NON_GOAL = "Non-goal"
    private const val EVERY_REQUIREMENT = "every requirement"

    /** The number of characters that a problem shows of a text that names no requirement. */
    private const val SHOWN = 50

    /** The characters that come after the name of a requirement in a table. */
    private const val NAME_ENDS = ".,:|()"

    /** A bar that parts two cells of a table row. A bar inside a cell has a backslash before it. */
    private val cellBar = Regex("(?<!\\\\)\\|")

    /** The line under the headings of a table. */
    private val underline = Regex("\\|(\\s*:?-+:?\\s*\\|)+\\s*")

    /** The name of a capability in backticks with a colon after it, which begins a citation of its requirements. */
    private val capability = Regex("`([a-z]+(?:-[a-z]+)+)`: ")

    private val deviation = Regex("DEV-\\d{2,}")
}
