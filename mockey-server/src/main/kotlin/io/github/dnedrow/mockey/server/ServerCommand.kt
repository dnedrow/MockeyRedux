package io.github.dnedrow.mockey.server

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.output.HelpFormatter
import com.github.ajalt.clikt.output.Localization
import com.github.ajalt.clikt.parameters.options.NullableOption
import com.github.ajalt.clikt.parameters.options.RawOption
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.github.dnedrow.mockey.core.model.Tags
import kotlin.time.Duration

/**
 * The command line of the server program. It reads every setting from its option or, when the option is not
 * given, from its environment variable, and a setting that has neither keeps the default of
 * [ServerSettings]. With the settings it calls [serve].
 *
 * The help option prints every setting with its option, its environment variable, and its default, and the
 * version option prints the version. Both end the program without a call of [serve], as does a value that
 * cannot be used, which is reported with the option or the variable that it came from.
 *
 * @param environment gives the value of an environment variable, or `null` when it is not set.
 * @param output receives the help and the version.
 * @param errors receives the messages about arguments that cannot be used.
 * @param serve runs the server with the settings and returns when the server has stopped.
 */
internal class ServerCommand(
    environment: (String) -> String? = System::getenv,
    private val output: Appendable = System.out,
    private val errors: Appendable = System.err,
    private val serve: (ServerSettings) -> Unit = ::serve,
) : CoreCliktCommand(name = PROGRAM_NAME) {
    private val defaults = ServerSettings()

    private val mockHost by setting("mock-host", "Address on which the mock listener accepts connections.")
        .notBlank("address")
        .default(defaults.mockHost)

    private val mockPort by setting("mock-port", "Port of the mock listener. 0 stands for a free port.", metavar = "port")
        .port()
        .default(defaults.mockPort)

    private val adminHost by setting("admin-host", "Address on which the admin listener accepts connections.")
        .notBlank("address")
        .default(defaults.adminHost)

    private val adminPort by setting("admin-port", "Port of the admin listener. 0 stands for a free port.", metavar = "port")
        .port()
        .default(defaults.adminPort)

    private val adminToken by setting(
        "admin-token",
        "Token that every request to the admin API has to present as a bearer token. " +
            "The variable keeps it from the other users of the machine, who can read the options of a running program.",
        default = ServerSettings.NONE,
    ).convert("token") { if (it.isBlank()) fail("the token must not be blank") else AdminToken(it) }

    private val allowUnprotectedAdmin by setting(
        "allow-unprotected-admin",
        "Lets the admin listener accept connections from other hosts without an admin token.",
    ).flag(default = defaults.allowUnprotectedAdmin, defaultForHelp = "off")

    private val definitions by setting("definitions", "Directory of the stored definitions.", metavar = "directory")
        .path(canBeFile = false)
        .default(defaults.definitions)

    private val readOnly by setting(
        "read-only",
        "Keeps changes to the definitions in memory, or writes them to the definitions directory.",
    ).flag("--writable", default = defaults.readOnly, defaultForHelp = "--read-only")

    private val initialDefinitions by setting(
        "initial-definitions",
        "File, directory, or URL of the definitions that take the place of the stored ones at startup.",
        default = ServerSettings.NONE,
    ).notBlank("source")

    private val tagFilter by setting(
        "tag-filter",
        "Tags, separated by spaces, of which a service or a scenario needs one to take part in routing.",
    ).convert("tags") { Tags.of(it) }
        .default(defaults.tagFilter, defaultForHelp = ServerSettings.NONE)

    private val historyLimit by setting("history-limit", "Number of history entries that are kept.", metavar = "entries")
        .int()
        .restrictTo(min = 0)
        .default(defaults.historyLimit)

    private val bodyCaptureLimit by setting(
        "body-capture-limit",
        "Number of bytes that a history entry holds of a request body and of a response body.",
        metavar = "bytes",
    ).int()
        .restrictTo(min = 0)
        .default(defaults.bodyCaptureLimit, defaultForHelp = bytes(defaults.bodyCaptureLimit.toLong()))

    private val upstreamConnectTimeout by setting(
        "upstream-connect-timeout",
        "Time within which the connection to an upstream has to be made, such as 10s or 500ms.",
    ).duration()
        .default(defaults.upstreamConnectTimeout)

    private val upstreamResponseTimeout by setting(
        "upstream-response-timeout",
        "Time within which the complete response of an upstream has to arrive, such as 60s or 2m.",
    ).duration()
        .default(defaults.upstreamResponseTimeout)

    private val maxRequestBody by setting("max-request-body", "Number of bytes that the body of a request may have.", metavar = "bytes")
        .long()
        .restrictTo(min = 0)
        .default(defaults.maxRequestBody, defaultForHelp = bytes(defaults.maxRequestBody))

    private val forwardUnknownTargets by setting(
        "forward-unknown-targets",
        "Forwards a request for an absolute target that no service knows to that target, or answers 404.",
    ).flag("--no-forward-unknown-targets", default = defaults.forwardUnknownTargets, defaultForHelp = "--forward-unknown-targets")

    private val logLevel by setting("log-level", "Level from which on messages are written to the log.")
        .choice(LogLevel.entries.associateBy { it.name.lowercase() }, ignoreCase = true)
        .default(defaults.logLevel, defaultForHelp = defaults.logLevel.name.lowercase())

    private val shutdownGracePeriod by setting(
        "shutdown-grace-period",
        "Time that requests in progress get to finish in when the server is told to stop, such as 10s.",
    ).duration(orNoTime = true)
        .default(defaults.shutdownGracePeriod)

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
        versionOption(ServerVersion.text, help = "Prints the version and exits.")
    }

    override fun help(context: Context): String =
        """
        Serves mock responses and forwards requests to real services.

        Every setting has an option and an environment variable. An option takes precedence over the
        variable, and the default applies when neither is given. For an option without a value, the variable
        takes true or false.
        """.trimIndent()

    override fun run() {
        serve(
            ServerSettings(
                mockHost = mockHost,
                mockPort = mockPort,
                adminHost = adminHost,
                adminPort = adminPort,
                adminToken = adminToken,
                allowUnprotectedAdmin = allowUnprotectedAdmin,
                definitions = definitions,
                readOnly = readOnly,
                initialDefinitions = initialDefinitions,
                tagFilter = tagFilter,
                historyLimit = historyLimit,
                bodyCaptureLimit = bodyCaptureLimit,
                upstreamConnectTimeout = upstreamConnectTimeout,
                upstreamResponseTimeout = upstreamResponseTimeout,
                maxRequestBody = maxRequestBody,
                forwardUnknownTargets = forwardUnknownTargets,
                logLevel = logLevel,
                shutdownGracePeriod = shutdownGracePeriod,
            ),
        )
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
        /** Returns the name of the environment variable of the setting with the option [name]. */
        fun variableOf(name: String): String = "MOCKEY_" + name.uppercase().replace('-', '_')
    }
}

/**
 * Runs the program with the arguments [argv] and returns its exit status: 0 after the server has run, after
 * the help, and after the version, and another number when the arguments cannot be used.
 */
internal fun ServerCommand.execute(argv: List<String>): Int =
    try {
        parse(argv)
        0
    } catch (error: CliktError) {
        echoFormattedHelp(error)
        error.statusCode
    }

private const val LAST_PORT = 65_535
private const val MEBIBYTE = 1_048_576L

/** Returns the number of bytes [count] as the help shows it: with the same in mebibytes where that is a whole number. */
private fun bytes(count: Long): String = if (count > 0 && count % MEBIBYTE == 0L) "$count (${count / MEBIBYTE} MiB)" else "$count"

/** A text that is not blank, which the help calls [metavar]. */
private fun RawOption.notBlank(metavar: String): NullableOption<String, String> =
    convert(metavar) { if (it.isBlank()) fail("the $metavar must not be blank") else it }

/** A port number, where 0 stands for a free port. */
private fun RawOption.port(): NullableOption<Int, Int> = int().restrictTo(0..LAST_PORT)

/**
 * A length of time in the notation of [Duration.parse], such as `10s`, `500ms`, or `1m 30s`. The time
 * passes and has an end, and it can be no time at all where [orNoTime] says so.
 */
private fun RawOption.duration(orNoTime: Boolean = false): NullableOption<Duration, Duration> =
    convert("duration") {
        val duration = Duration.parseOrNull(it) ?: fail("$it is not a length of time such as 10s, 500ms, or 2m")
        when {
            duration.isInfinite() -> fail("the time must have an end")
            duration.isNegative() -> fail("the time must not be negative")
            duration == Duration.ZERO && !orNoTime -> fail("the time must be longer than 0s")
            else -> duration
        }
    }
