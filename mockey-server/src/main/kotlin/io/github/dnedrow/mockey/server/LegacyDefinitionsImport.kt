package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.definitions.PlanApplyReport
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.storage.FileDefinitionStorage
import io.github.dnedrow.mockey.core.storage.StorageException
import io.github.dnedrow.mockey.legacy.xml.LegacyImportReport
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/** Tells that an import was not carried out. The message says why, for the one who ran the import. */
internal class ImportFailure(
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The import of the definitions of Mockey 2.x into a definitions location, which the server program offers
 * as a command.
 *
 * The import adds to the definitions that the location holds, by the rules of every import: it adds what is
 * new, merges a service into the service that has its name, and takes a setting where the location has
 * none. A default plan that it takes is applied. It removes and replaces nothing that the location holds,
 * and it changes nothing of its source.
 */
internal object LegacyDefinitionsImport {
    /**
     * Imports the definitions of [source] into the definitions location [location], and returns the report
     * of the import as the program prints it.
     *
     * [source] names a definitions directory of Mockey 2.x, or the file or the URL of an export document of
     * Mockey 2.x, as [DefinitionsSource] reads them. A source of which some part cannot be read is imported
     * without that part, which the report names. [tags] are added to every service, scenario, and plan that
     * the import adds, and to every service that it merges into.
     *
     * The report says what was imported from where to where, and then it has every addition, every
     * conflict, every issue of the source, and every rule set that is not well formed on a line of its own,
     * under the title and the number of its kind. Its last line says what the definitions location holds
     * after the import.
     *
     * @throws ImportFailure when nothing can be read from the source, when the source is no source of
     *   Mockey 2.x, when the definitions location cannot be used, and when the imported definitions cannot
     *   be stored.
     */
    fun run(
        source: String,
        location: Path,
        tags: Tags = Tags.EMPTY,
    ): String {
        val definitions = DefinitionsSource(source)
        val read =
            try {
                definitions.read()
            } catch (failure: UnreadableSource) {
                throw ImportFailure(failure.message, failure)
            }
        if (read !is SourceDefinitions.Legacy) throw ImportFailure(definitions.unreadable(OWN_DOCUMENT).message)
        return runBlocking {
            stored(location).use { engine ->
                val report =
                    try {
                        read.result.importInto(engine, tags)
                    } catch (failure: StorageException) {
                        throw ImportFailure("$NOT_STORED ${failure.message}", failure)
                    }
                textOf(definitions.shown, location.toAbsolutePath().normalize(), report, read.advice, engine.definitions)
            }
        }
    }

    /** Returns an engine that holds the definitions of [location] and stores every change to them there. */
    private suspend fun stored(location: Path): MockeyEngine =
        try {
            MockeyEngine.create {
                storage = FileDefinitionStorage(location)
                readOnly = false
            }
        } catch (failure: StorageException) {
            throw ImportFailure("$NOT_USABLE ${failure.message}", failure)
        }

    /**
     * Returns the report of an import as text. [source] and [location] are the names of the two as the text
     * shows them, [advice] says how more of the source can be read, where there is a way, and [holds] are
     * the definitions of the location after the import.
     */
    private fun textOf(
        source: String,
        location: Path,
        report: LegacyImportReport,
        advice: String?,
        holds: Definitions,
    ): String {
        val ruleSets = report.unreadableRuleSets.map { "The service '${it.serviceName}': ${it.message}" }
        val unapplied = report.imported.appliedDefaultPlan?.let(::unappliedPartsOf)
        val lines =
            listOf("Imported $source into the definitions location $location.", "") +
                section("Additions", report.imported.additions.map { it.message }) +
                section("Conflicts", report.imported.conflicts.map { it.message }) +
                section("Issues of the source", report.issues.map { it.message }) +
                listOfNotNull(advice?.let { "$ENTRY_INDENT$NOTE $it" }) +
                section("Rule sets that are not well formed", ruleSets) +
                unapplied.orEmpty() +
                listOf("", "The definitions location now holds ${contentOf(holds)}.")
        return lines.joinToString("\n")
    }

    /** Returns the lines of a section of the report: its [title] with the number of its [entries], and the entries. */
    private fun section(
        title: String,
        entries: List<String>,
    ): List<String> = listOf("$title: ${if (entries.isEmpty()) "none" else entries.size}") + entries.map { ENTRY_INDENT + it }

    /**
     * Returns the section that names what the default plan of [applied] holds for a service or a scenario
     * that does not exist, and nothing when the plan holds no such thing.
     */
    private fun unappliedPartsOf(applied: PlanApplyReport): List<String> {
        val services =
            applied.skippedItems.map {
                "The plan has settings for the service '${it.serviceName}', and no service has that name. They were not applied."
            }
        val scenarios =
            applied.unresolvedScenarios.map {
                "The plan names the scenario '${it.scenarioName}' of the service '${it.serviceName}', which has no scenario " +
                    "with that name. The default scenario of that service stays as it is."
            }
        val parts = services + scenarios
        val title = "Parts of the default plan '${applied.plan.name}' that were not applied"
        return if (parts.isEmpty()) emptyList() else section(title, parts)
    }

    private const val ENTRY_INDENT = "  "
    private const val NOTE = "Note:"
    private const val NOT_USABLE = "The definitions location cannot be used."
    private const val NOT_STORED = "The imported definitions cannot be stored in the definitions location."
    private const val OWN_DOCUMENT =
        "The document is a definitions document of this system. The command imports the definitions of Mockey 2.x, which are XML."
}
