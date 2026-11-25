package io.github.dnedrow.mockey.server

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.output.HelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import io.github.dnedrow.mockey.core.model.Tags
import java.nio.file.Path

/**
 * The command of the server program that imports the definitions of Mockey 2.x into the definitions
 * location, prints the report of the import, and ends the program. It starts no server and opens no
 * listener.
 *
 * The definitions location is the directory that the option `--definitions` of the command names. Without
 * that option it is the one of the server: the directory that the option of the server names, which stands
 * before the command, or else the environment variable of that setting, or else its default.
 *
 * A source from which nothing can be read and a definitions location that cannot be used end the program
 * with a status other than 0 and with the reason. An import of which the report names conflicts or issues
 * was carried out, and ends the program with the status 0.
 *
 * @param locationOfTheServer gives the definitions location that the settings of the server name.
 */
internal class ImportLegacyCommand(
    private val locationOfTheServer: () -> Path,
) : CoreCliktCommand(name = "import-legacy") {
    private val source by argument(
        "source",
        help = "Definitions directory of Mockey 2.x, or the file or the URL of an export document of Mockey 2.x.",
    ).convert { if (it.isBlank()) fail("the source must not be blank") else it }

    private val definitions by option(
        "--definitions",
        metavar = "directory",
        help = "Directory of the stored definitions, to which the import adds. The option can stand before the command as well.",
        helpTags =
            mapOf(
                SettingsHelpFormatter.VARIABLE to ServerCommand.variableOf("definitions"),
                HelpFormatter.Tags.DEFAULT to ServerSettings().definitions.toString(),
            ),
    ).path(canBeFile = false)

    private val tags by option(
        "--tags",
        metavar = "tags",
        help =
            "Tags, separated by spaces, that the import gives every service, scenario, and plan that it adds, " +
                "and every service that has the name of an imported one.",
    ).convert { Tags.of(it) }
        .default(Tags.EMPTY, defaultForHelp = ServerSettings.NONE)

    override fun help(context: Context): String =
        """
        Imports the definitions of Mockey 2.x into the definitions location and prints the report.

        The source is a definitions directory of Mockey 2.x, which holds the file
        mock_service_definitions.xml, or an export document of Mockey 2.x, which a running Mockey 2.x gives
        at its path /export. Nothing of the source changes.

        The import adds to the definitions that the definitions location holds. It adds each service, plan,
        and twist configuration whose name is new, and it gives a service that has the name of an imported
        one the scenarios that it lacks. It removes and replaces nothing, and the report names what it did
        not add. Where the location has no default plan, the default plan of the source becomes the default
        plan and is applied.

        The command starts no server. Run it while no server in writable mode uses the definitions location.
        """.trimIndent()

    override fun run() {
        val report =
            try {
                LegacyDefinitionsImport.run(source, definitions ?: locationOfTheServer(), tags)
            } catch (failure: ImportFailure) {
                throw CliktError("Error: ${failure.message}", failure)
            }
        echo(report)
    }
}
