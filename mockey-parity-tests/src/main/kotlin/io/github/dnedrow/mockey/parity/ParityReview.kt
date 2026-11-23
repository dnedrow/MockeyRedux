package io.github.dnedrow.mockey.parity

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Checks that every recording has been reviewed against the specifications and the deviation register. */
object ParityReview {
    private val registerRow = Regex("^\\|\\s*(DEV-\\d{2,})\\s*\\|")

    /** The deviation IDs that the register at [register] lists, one in the first column of each table row. */
    fun registeredDeviations(register: Path): Set<String> =
        Files
            .readAllLines(register)
            .mapNotNull { line -> registerRow.find(line)?.groupValues?.get(1) }
            .toSet()

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
