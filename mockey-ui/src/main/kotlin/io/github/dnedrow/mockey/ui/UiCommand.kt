package io.github.dnedrow.mockey.ui

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.MultiUsageError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.output.HelpFormatter
import com.github.ajalt.clikt.output.Localization
import com.github.ajalt.clikt.parameters.options.RawOption
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo

/**
 * The command line of the UI program. It reads every setting from its option or, when the option is not
 * given, from its environment variable, and a setting that has neither keeps the default of [UiSettings].
 * With the settings it calls [serve].
 *
 * The help option prints every setting with its option, its environment variable, and its default, and the
 * version option prints the version. Both end the program without a call of [serve], as does a value that
 * cannot be used, which is reported with the option or the variable that it came from. A UI that does not
 * start is reported with its reason, see [StartupFailure].
 *
 * @param environment gives the value of an environment variable, or `null` when it is not set.
 * @param output receives the help and the version.
 * @param errors receives the messages about arguments that cannot be used.
 * @param serve runs the UI with the settings and returns when the UI has stopped.
 */
internal class UiCommand(
    environment: (String) -> String? = System::getenv,
    private val output: Appendable = System.out,
    private val errors: Appendable = System.err,
    private val serve: (UiSettings) -> Unit = ::serve,
) : CoreCliktCommand(name = PROGRAM_NAME) {
    private val defaults = UiSettings()

    private val host by setting("host", "Address on which the UI accepts connections.")
        .convert("address") { if (it.isBlank()) fail("the address must not be blank") else it }
        .default(defaults.host)

    private val port by setting("port", "Port of the UI. 0 stands for a free port.", metavar = "port")
        .int()
        .restrictTo(0..LAST_PORT)
        .default(defaults.port)

    private val adminUrl by setting(
        "admin-url",
        "URL of the admin listener of the server that the UI manages, without the path of the admin API.",
    ).convert("URL") {
        try {
            AdminUrl.of(it)
        } catch (unusable: IllegalArgumentException) {
            fail("the URL ${unusable.message}")
        }
    }.default(defaults.adminUrl)

    private val adminToken by setting(
        "admin-token",
        "Admin token of the server, which the UI presents with every request to the admin API. " +
            "The variable keeps it from the other users of the machine, who can read the options of a running program.",
        default = UiSettings.NONE,
    ).convert("token") {
        try {
            AdminToken(it)
        } catch (unusable: IllegalArgumentException) {
            fail(unusable.message.orEmpty())
        }
    }

    private val logLevel by setting("log-level", "Level from which on messages are written to the log.")
        .choice(LogLevel.entries.associateBy { it.name.lowercase() }, ignoreCase = true)
        .default(defaults.logLevel, defaultForHelp = defaults.logLevel.name.lowercase())

    init {
        context {
            readEnvvar = environment
            helpFormatter = { SettingsHelpFormatter(it) }
            localization =
                object : Localization {
                    override fun helpOptionMessage(): String = "Prints this help and exits."
                }
            echoMessage = { _, message, trailingNewline, toErrors ->
                val target = if (toErrors) errors else output
                target.append(message.toString())
                if (trailingNewline) target.append('\n')
            }
        }
        versionOption(UiVersion.text, help = "Prints the version and exits.")
    }

    override fun help(context: Context): String =
        """
        Serves the web pages with which a person manages a Mockey server, through the admin API of that server.

        Every setting has an option and an environment variable. An option takes precedence over the
        variable, and the default applies when neither is given.
        """.trimIndent()

    override fun run() {
        val settings = UiSettings(host = host, port = port, adminUrl = adminUrl, adminToken = adminToken, logLevel = logLevel)
        try {
            serve(settings)
        } catch (failure: StartupFailure) {
            throw CliktError("Error: ${failure.message}", failure)
        }
    }

    /**
     * Starts the option of the setting [name], which reads the environment variable that [variableOf] names
     * when the option is not given. The help calls the value of the option [metavar], and it shows [default]
     * for a setting that has no value by default.
     */
    private fun setting(
        name: String,
        help: String,
        metavar: String? = null,
        default: String? = null,
    ): RawOption {
        val variable = variableOf(name)
        val tags = listOfNotNull(SettingsHelpFormatter.VARIABLE to variable, default?.let { HelpFormatter.Tags.DEFAULT to it })
        return option("--$name", help = help, metavar = metavar, envvar = variable, helpTags = tags.toMap())
    }

    internal companion object {
        private const val LAST_PORT = 65_535

        /** The setting that has the environment variable of the server program: the token is the one of the server. */
        private const val ADMIN_TOKEN = "admin-token"

        /**
         * Returns the name of the environment variable of the setting with the option [name]. The variables
         * of the UI begin with `MOCKEY_UI_`. The admin token is the one that the server has in
         * `MOCKEY_ADMIN_TOKEN`, so the UI reads it from there as well.
         */
        fun variableOf(name: String): String {
            val prefix = if (name == ADMIN_TOKEN) "MOCKEY_" else "MOCKEY_UI_"
            return prefix + name.uppercase().replace('-', '_')
        }
    }
}

/**
 * Runs the program with the arguments [argv] and returns its exit status: 0 after the UI has run, after the
 * help, and after the version, and another number when the arguments cannot be used or the UI does not
 * start.
 */
internal fun UiCommand.execute(argv: List<String>): Int =
    try {
        parse(argv)
        0
    } catch (error: CliktError) {
        // Several errors at once come without the command that they are about, and the usage would be that of the program.
        if (error is MultiUsageError) error.context = error.context ?: error.errors.firstNotNullOfOrNull { it.context }
        echoFormattedHelp(error)
        error.statusCode
    }
