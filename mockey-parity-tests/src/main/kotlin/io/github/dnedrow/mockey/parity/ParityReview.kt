package io.github.dnedrow.mockey.parity

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** A row of the deviation register: the [id] of a deviation, and what the register names as the [evidence] for it. */
data class RegisteredDeviation(
    val id: String,
    val evidence: String,
)

/**
 * Checks that every recording has been reviewed against the specifications and the deviation register, and
 * that the register and the labels of the recordings say the same.
 */
object ParityReview {
    private val registerRow = Regex("^\\|\\s*(DEV-\\d{2,})\\s*\\|")

    /** A bar that parts two cells of a table row. A bar inside a cell has a backslash before it. */
    private val cellBar = Regex("(?<!\\\\)\\|")

    /** The number of recordings that a row states for its deviation, with a few of them named as examples. */
    private val statedTotal = Regex("(\\d+) recordings?, such as")

    /** The number of recordings that a row states in addition to the ones that it names. */
    private val statedRest = Regex("and (\\d+) more recordings?")

    /** The deviation IDs that the register at [register] lists, one in the first column of each table row. */
    fun registeredDeviations(register: Path): Set<String> = registerRows(register).map { it.id }.toSet()

    /** The rows of the register at [register], in their order. The evidence is the last cell of a row. */
    fun registerRows(register: Path): List<RegisteredDeviation> =
        Files.readAllLines(register).mapNotNull { line ->
            val id = registerRow.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            val cells = line.trim().removeSuffix("|").split(cellBar)
            RegisteredDeviation(id, cells.last().trim())
        }

    /**
     * What is wrong between the register at [register] and the labels of the recordings in [recordings], one
     * line for each problem. The two say the same when all of this holds:
     *
     * - The IDs of the register count up from `DEV-01` without a gap.
     * - Every recording that a row names in its evidence exists. A name that ends in a star stands for the
     *   recordings whose names start that way, and there is at least one.
     * - A deviation that is the label of recordings has evidence of the kind `run`, and that evidence
     *   accounts for every one of those recordings. It names each of them, or it names some and says how
     *   many more there are, or it states their number and names examples.
     *
     * A row may name a recording that does not carry its label. Such a recording shows what legacy does,
     * and the new server answers it the same way.
     */
    fun registerProblems(
        register: Path,
        recordings: Path,
        store: ParityCaseStore = ParityCaseStore(),
    ): List<String> {
        val rows = registerRows(register)
        val labels =
            recordings
                .listDirectoryEntries()
                .filter { it.isDirectory() }
                .sortedBy { it.name }
                .associate { it.name to deviationsOf(store.readOrNull(it)?.label) }
        val references = RecordingReferences(labels.keys)
        return numberingProblems(rows) + rows.flatMap { evidenceProblems(it, labels, references) }
    }

    /**
     * The deviation IDs that [label] names. A label that names none gives none, and so does one that cannot
     * be read: [problems] says what is wrong with such a recording.
     */
    private fun deviationsOf(label: String?): List<String> =
        (runCatching { ParityLabel.parse(label.orEmpty()) }.getOrNull() as? ParityLabel.Deviations)?.ids.orEmpty()

    private fun numberingProblems(rows: List<RegisteredDeviation>): List<String> =
        rows.mapIndexedNotNull { index, row ->
            val expected = "DEV-%02d".format(index + 1)
            "the register has ${row.id} where $expected is next".takeIf { row.id != expected }
        }

    private fun evidenceProblems(
        row: RegisteredDeviation,
        labels: Map<String, List<String>>,
        references: RecordingReferences,
    ): List<String> {
        val referred = references.referencesIn(row.evidence).associateWith(references::recordingsOf)
        val missing = referred.filterValues { it.isEmpty() }.keys.map { "${row.id} names `$it`, and no recording has that name" }
        val labeled = labels.filterValues { row.id in it }.keys
        return missing + accountProblems(row, labeled, referred.values.flatten().toSet())
    }

    /** What is wrong with how [row] accounts for the recordings [labeled] with its ID, of which it refers to [referred]. */
    private fun accountProblems(
        row: RegisteredDeviation,
        labeled: Set<String>,
        referred: Set<String>,
    ): List<String> {
        if (labeled.isEmpty()) return emptyList()
        val carries = "${row.id} is the label of ${if (labeled.size == 1) "1 recording" else "${labeled.size} recordings"}"
        if (!row.evidence.startsWith("run")) return listOf("$carries, and its evidence is not of the kind run")
        val named = labeled.intersect(referred)
        val total = numberIn(statedTotal, row.evidence)
        val rest = numberIn(statedRest, row.evidence)
        return when {
            total != null -> {
                listOfNotNull(
                    "$carries, and its evidence states $total".takeIf { total != labeled.size },
                    "$carries, and its evidence names none of them".takeIf { named.isEmpty() },
                )
            }

            rest != null -> {
                val states = "$carries, and its evidence names ${named.size} of them and states $rest more"
                listOfNotNull(states.takeIf { named.size + rest != labeled.size })
            }

            else -> {
                (labeled - named).sorted().map { "${row.id} is the label of `$it`, which its evidence does not name" }
            }
        }
    }

    /** The number that [pattern] finds in [evidence], or `null` when the evidence states none. */
    private fun numberIn(
        pattern: Regex,
        evidence: String,
    ): Int? {
        val found = pattern.find(evidence) ?: return null
        return found.groupValues[1].toInt()
    }

    /**
     * What is wrong with the reviews of the recordings in [recordings], one line for each problem. A recording
     * is in order when its label is `same` and it states no new response, or when its label names deviations
     * that are in [registeredDeviations] and it states the response of the new server.
     */
    fun problems(
        recordings: Path,
        registeredDeviations: Set<String>,
        store: ParityCaseStore = ParityCaseStore(),
    ): List<String> =
        recordings
            .listDirectoryEntries()
            .filter { it.isDirectory() }
            .sortedBy { it.name }
            .flatMap { directory ->
                val case = runCatching { store.read(directory) }.getOrElse { return@flatMap listOf("${directory.name}: ${it.message}") }
                problemsOf(case, registeredDeviations).map { "${directory.name}: $it" }
            }

    private fun problemsOf(
        case: ParityCase,
        registeredDeviations: Set<String>,
    ): List<String> {
        val label = runCatching { ParityLabel.parse(case.label) }.getOrElse { return listOf(it.message.orEmpty()) }
        return when (label) {
            ParityLabel.Unreviewed -> listOf("is not reviewed")
            ParityLabel.Same -> listOfNotNull("is labeled same but states a new response".takeIf { case.newResponse != null })
            is ParityLabel.Deviations -> unregistered(label, registeredDeviations) + statementProblems(case)
        }
    }

    private fun unregistered(
        label: ParityLabel.Deviations,
        registeredDeviations: Set<String>,
    ): List<String> = label.ids.filter { it !in registeredDeviations }.map { "$it is not in the deviation register" }

    /** A stated response lists headers exactly when headers are compared, which is for the mock endpoint. */
    private fun statementProblems(case: ParityCase): List<String> {
        val stated = case.newResponse ?: return listOf("deviates but states no new response")
        val mockTraffic = ResponseComparison.isMockTraffic(case.request)
        return listOfNotNull(
            "states no headers for a response of the mock endpoint".takeIf { mockTraffic && stated.headers.isEmpty() },
            "states headers that are not compared outside the mock endpoint".takeIf { !mockTraffic && stated.headers.isNotEmpty() },
            (stated.body as? ExpectedBody.Json)?.let { body ->
                runCatching { JsonDocuments.parse(body.template) }.exceptionOrNull()?.let { "states a body that is no JSON: ${it.message}" }
            },
        )
    }
}
