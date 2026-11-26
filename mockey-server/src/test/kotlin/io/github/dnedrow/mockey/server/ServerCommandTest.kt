package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.model.Tags
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The command line of the server program: where each setting comes from, what the help and the version
 * print, and with which status the program ends. The server that the command would run is replaced by a
 * function that keeps the settings.
 */
class ServerCommandTest {
    /** What a run of the command gave: its exit status, what it printed, and the settings it ran the server with. */
    private class Run(
        val status: Int,
        val output: String,
        val errors: String,
        val served: List<ServerSettings>,
    ) {
        val settings: ServerSettings
            get() = served.single()
    }

    private fun run(
        vararg arguments: String,
        variables: Map<String, String> = emptyMap(),
    ): Run {
        val output = StringBuilder()
        val errors = StringBuilder()
        val served = mutableListOf<ServerSettings>()
        val status = ServerCommand(variables::get, output, errors) { served += it }.execute(arguments.asList())
        return Run(status, output.toString(), errors.toString(), served)
    }

    /**
     * A setting, with [arguments] that set it to [fromArguments] and a value of its environment variable,
     * [variable], that sets it to [fromVariable]. [read] takes the setting from the settings.
     */
    private class Case(
        val name: String,
        val arguments: List<String>,
        val fromArguments: Any?,
        val variable: String,
        val fromVariable: Any?,
        val read: (ServerSettings) -> Any?,
    ) {
        val environment: Map<String, String> = mapOf(ServerCommand.variableOf(name) to variable)

        override fun toString(): String = "$arguments with $environment"
    }

    private val cases =
        listOf(
            Case("mock-host", listOf("--mock-host", "192.0.2.1"), "192.0.2.1", "192.0.2.2", "192.0.2.2") { it.mockHost },
            Case("mock-port", listOf("--mock-port", "9100"), 9100, "9000", 9000) { it.mockPort },
            Case("admin-host", listOf("--admin-host", "192.0.2.3"), "192.0.2.3", "192.0.2.4", "192.0.2.4") { it.adminHost },
            Case("admin-port", listOf("--admin-port=9300"), 9300, "9200", 9200) { it.adminPort },
            Case(
                "admin-token",
                listOf("--admin-token", "of the option"),
                AdminToken("of the option"),
                "of the variable",
                AdminToken("of the variable"),
            ) {
                it.adminToken
            },
            Case("allow-unprotected-admin", listOf("--allow-unprotected-admin"), true, "false", false) { it.allowUnprotectedAdmin },
            Case("allow-unprotected-admin", listOf("--allow-unprotected-admin"), true, "true", true) { it.allowUnprotectedAdmin },
            Case(
                "definitions",
                listOf("--definitions", "of/the/option"),
                Path("of/the/option"),
                "of/the/variable",
                Path("of/the/variable"),
            ) {
                it.definitions
            },
            Case("read-only", listOf("--writable"), false, "true", true) { it.readOnly },
            Case("read-only", listOf("--read-only"), true, "false", false) { it.readOnly },
            Case(
                "initial-definitions",
                listOf("--initial-definitions", "option.json"),
                "option.json",
                "https://example.org/variable.json",
                "https://example.org/variable.json",
            ) {
                it.initialDefinitions
            },
            Case("tag-filter", listOf("--tag-filter", "regression"), Tags.of("regression"), "smoke Nightly", Tags.of("nightly", "smoke")) {
                it.tagFilter
            },
            Case("history-limit", listOf("--history-limit", "20"), 20, "10", 10) { it.historyLimit },
            Case("body-capture-limit", listOf("--body-capture-limit", "2048"), 2048, "1024", 1024) { it.bodyCaptureLimit },
            Case("upstream-connect-timeout", listOf("--upstream-connect-timeout", "2s"), 2.seconds, "500ms", 500.milliseconds) {
                it.upstreamConnectTimeout
            },
            Case("upstream-response-timeout", listOf("--upstream-response-timeout", "2m"), 2.minutes, "90s", 90.seconds) {
                it.upstreamResponseTimeout
            },
            Case("max-request-body", listOf("--max-request-body", "2097152"), 2_097_152L, "1048576", 1_048_576L) { it.maxRequestBody },
            Case("forward-unknown-targets", listOf("--no-forward-unknown-targets"), false, "true", true) { it.forwardUnknownTargets },
            Case("forward-unknown-targets", listOf("--forward-unknown-targets"), true, "false", false) { it.forwardUnknownTargets },
            Case("legacy-shim", listOf("--legacy-shim"), true, "false", false) { it.legacyShim },
            Case("legacy-shim", listOf("--legacy-shim"), true, "true", true) { it.legacyShim },
            Case("log-level", listOf("--log-level", "debug"), LogLevel.DEBUG, "warn", LogLevel.WARN) { it.logLevel },
            Case(
                "shutdown-grace-period",
                listOf("--shutdown-grace-period", "3s"),
                3.seconds,
                "0s",
                Duration.ZERO,
            ) { it.shutdownGracePeriod },
        )

    /** The names of the settings of the command, which are the names of its options but for the help and the version. */
    private fun settingNames(): List<String> =
        ServerCommand()
            .registeredOptions()
            .filterNot { it.eager }
            .map { it.names.single().removePrefix("--") }

    /** Checks that [settings] have [expected] for the setting of [case] and the default for every other setting. */
    private fun assertOnlySettingChanged(
        case: Case,
        expected: Any?,
        settings: ServerSettings,
    ) {
        assertEquals(expected, case.read(settings), "The setting ${case.name} for $case")
        for (other in cases.filter { it.name != case.name }) {
            assertEquals(other.read(ServerSettings()), other.read(settings), "The setting ${other.name} for $case")
        }
    }

    @Test
    fun aSettingHasItsDefaultWhenNeitherItsOptionNorItsVariableIsGiven() {
        val run = run()

        val settings = run.settings
        assertEquals(0, run.status)
        assertEquals(ServerSettings(), settings)
        assertEquals("0.0.0.0" to 8080, settings.mockHost to settings.mockPort)
        assertEquals("127.0.0.1" to 8081, settings.adminHost to settings.adminPort)
        assertNull(settings.adminToken)
        assertFalse(settings.allowUnprotectedAdmin)
        assertEquals(Path("mockey-definitions"), settings.definitions)
        assertTrue(settings.readOnly)
        assertNull(settings.initialDefinitions)
        assertEquals(Tags.EMPTY, settings.tagFilter)
        assertEquals(1_000, settings.historyLimit)
        assertEquals(1_048_576, settings.bodyCaptureLimit)
        assertEquals(10.seconds, settings.upstreamConnectTimeout)
        assertEquals(60.seconds, settings.upstreamResponseTimeout)
        assertEquals(52_428_800L, settings.maxRequestBody)
        assertTrue(settings.forwardUnknownTargets)
        assertFalse(settings.legacyShim)
        assertEquals(LogLevel.INFO, settings.logLevel)
        assertEquals(10.seconds, settings.shutdownGracePeriod)
    }

    @Test
    fun anOptionSetsItsSetting() {
        for (case in cases) {
            val run = run(*case.arguments.toTypedArray())

            assertEquals(0, run.status, "$case")
            assertOnlySettingChanged(case, case.fromArguments, run.settings)
        }
    }

    @Test
    fun anEnvironmentVariableSetsItsSetting() {
        for (case in cases) {
            val run = run(variables = case.environment)

            assertEquals(0, run.status, "$case")
            assertOnlySettingChanged(case, case.fromVariable, run.settings)
        }
    }

    @Test
    fun anOptionTakesPrecedenceOverItsEnvironmentVariable() {
        for (case in cases) {
            val run = run(*case.arguments.toTypedArray(), variables = case.environment)

            assertEquals(0, run.status, "$case")
            assertOnlySettingChanged(case, case.fromArguments, run.settings)
        }
    }

    /** The three tests above say something about a setting only when their cases tell its sources apart. */
    @Test
    fun theCasesOfThisTestTellTheSourcesOfEverySettingApart() {
        val defaults = ServerSettings()

        assertEquals(settingNames(), cases.map { it.name }.distinct())
        for (name in settingNames()) {
            val ofSetting = cases.filter { it.name == name }
            assertTrue(ofSetting.any { it.fromArguments != it.read(defaults) }, "An option for $name that changes the default")
            assertTrue(ofSetting.any { it.fromVariable != it.read(defaults) }, "A variable for $name that changes the default")
            assertTrue(ofSetting.any { it.fromArguments != it.fromVariable }, "An option and a variable for $name that differ")
        }
    }

    @Test
    fun theOptionForThePortOverridesTheEnvironment() {
        val run = run("--mock-port", "9100", variables = mapOf("MOCKEY_MOCK_PORT" to "9000"))

        assertEquals(9100, run.settings.mockPort)
    }

    @Test
    fun theTagFilterTakesTheTagsOfItsText() {
        assertEquals(Tags.of("regression"), run("--tag-filter", "regression").settings.tagFilter)
        assertEquals(Tags.of("regression", "smoke"), run("--tag-filter", "Smoke  regression").settings.tagFilter)
        assertEquals(Tags.EMPTY, run("--tag-filter", "").settings.tagFilter)
        assertEquals(Tags.EMPTY, run(variables = mapOf("MOCKEY_TAG_FILTER" to " ")).settings.tagFilter)
    }

    @Test
    fun aLengthOfTimeIsWrittenWithItsUnit() {
        fun responseTimeout(text: String) = run("--upstream-response-timeout", text).settings.upstreamResponseTimeout

        assertEquals(30.seconds, responseTimeout("30s"))
        assertEquals(250.milliseconds, responseTimeout("250ms"))
        assertEquals(90.seconds, responseTimeout("1m 30s"))
        assertEquals(1.5.seconds, responseTimeout("1.5s"))
        assertEquals(5.minutes, responseTimeout("PT5M"))
    }

    @Test
    fun aGracePeriodOfNoTimeIsASetting() {
        assertEquals(Duration.ZERO, run("--shutdown-grace-period", "0s").settings.shutdownGracePeriod)
    }

    @Test
    fun theLogLevelIsReadInAnyCase() {
        assertEquals(LogLevel.TRACE, run("--log-level", "TRACE").settings.logLevel)
        assertEquals(LogLevel.ERROR, run(variables = mapOf("MOCKEY_LOG_LEVEL" to "Error")).settings.logLevel)
        assertEquals(LogLevel.entries, listOf("error", "warn", "info", "debug", "trace").map { run("--log-level", it).settings.logLevel })
    }

    @Test
    fun theVariableOfAnOptionWithoutAValueTakesTrueOrFalse() {
        fun readOnly(value: String) = run(variables = mapOf("MOCKEY_READ_ONLY" to value)).settings.readOnly

        assertEquals(listOf(true, true, true, true), listOf("true", "TRUE", "1", "yes").map(::readOnly))
        assertEquals(listOf(false, false, false, false), listOf("false", "False", "0", "no").map(::readOnly))
    }

    @Test
    fun theLastOfSeveralOptionsForOneSettingCounts() {
        assertEquals(9102, run("--mock-port", "9101", "--mock-port", "9102").settings.mockPort)
        assertEquals(false, run("--read-only", "--writable").settings.readOnly)
        assertEquals(true, run("--writable", "--read-only").settings.readOnly)
    }

    @Test
    fun theLargestBodyThatCanBeAllowedIsTheLargestThatTheRuntimeHolds() {
        assertEquals(2_147_483_639L, run("--max-request-body", "2147483639").settings.maxRequestBody)
        assertEquals(0L, run("--max-request-body", "0").settings.maxRequestBody)
    }

    @Test
    fun aPortCanBeAnyPortOrTheFreeOne() {
        assertEquals(0, run("--mock-port", "0").settings.mockPort)
        assertEquals(65_535, run("--admin-port", "65535").settings.adminPort)
    }

    /** What the help has to show as the default of each setting. */
    private val defaultsInTheHelp =
        mapOf(
            "mock-host" to "0.0.0.0",
            "mock-port" to "8080",
            "admin-host" to "127.0.0.1",
            "admin-port" to "8081",
            "admin-token" to "none",
            "allow-unprotected-admin" to "off",
            "definitions" to "mockey-definitions",
            "read-only" to "--read-only",
            "initial-definitions" to "none",
            "tag-filter" to "none",
            "history-limit" to "1000",
            "body-capture-limit" to "1048576 (1 MiB)",
            "upstream-connect-timeout" to "10s",
            "upstream-response-timeout" to "1m",
            "max-request-body" to "52428800 (50 MiB)",
            "forward-unknown-targets" to "--forward-unknown-targets",
            "legacy-shim" to "off",
            "log-level" to "info",
            "shutdown-grace-period" to "10s",
        )

    /**
     * Returns the entries of the options in the help text [help]: the line of each option, without its
     * indent, with the lines that describe it.
     */
    private fun optionEntries(help: String): Map<String, List<String>> {
        val entries = linkedMapOf<String, MutableList<String>>()
        val options = help.substringAfter("\nOptions:\n").substringBefore("\nCommands:\n")
        for (line in options.lines().filter { it.isNotBlank() }) {
            if (line.startsWith("      ")) entries.values.last() += line.trim() else entries[line.trim()] = mutableListOf()
        }
        return entries
    }

    @Test
    fun theHelpStatesEverySettingWithItsOptionItsVariableAndItsDefault() {
        val run = run("--help")

        val entries = optionEntries(run.output)
        assertEquals(settingNames(), defaultsInTheHelp.keys.toList())
        assertEquals(settingNames().size + 2, entries.size, "An entry for each setting, for the version, and for the help")
        for (name in settingNames()) {
            val (option, description) = entries.entries.single { (option, _) -> option.split('=', ' ').first() == "--$name" }
            val notes = listOf("Environment variable: ${ServerCommand.variableOf(name)}", "Default: ${defaultsInTheHelp.getValue(name)}")
            assertEquals(notes, description.takeLast(2), "The entry of $option")
            assertTrue(
                description.size > 2 && description.dropLast(2).last().endsWith("."),
                "The entry of $option begins with a description",
            )
        }
        assertContains(entries.keys, "--read-only / --writable")
        assertContains(entries.keys, "--forward-unknown-targets / --no-forward-unknown-targets")
        assertContains(entries.keys, "--version")
        assertContains(entries.keys, "-h, --help")
    }

    @Test
    fun theHelpEndsTheProgramWithStatusZeroAndStartsNoServer() {
        for (option in listOf("--help", "-h")) {
            val run = run(option, "--mock-port", "9100")

            assertEquals(0, run.status)
            assertEquals(emptyList(), run.served)
            assertEquals("", run.errors)
            assertTrue(run.output.startsWith("$USAGE\n\nServes mock responses"), run.output)
        }
    }

    @Test
    fun theHelpFitsATerminal() {
        val lines = run("--help").output.lines()

        assertEquals(emptyList(), lines.filter { it.length > 80 })
        assertEquals(emptyList(), lines.filter { it != it.trimEnd() })
    }

    @Test
    fun theVersionOptionPrintsTheVersionAndStartsNoServer() {
        val run = run("--version")

        assertEquals(0, run.status)
        assertEquals("mockey-server version ${System.getProperty("mockey.version")}\n", run.output)
        assertEquals(emptyList(), run.served)
        assertEquals("", run.errors)
    }

    /**
     * The version is three numbers, as in `3.0.0`. A version that is no release goes on after a hyphen, as
     * `3.0.0-rc.1` and `3.1.0-SNAPSHOT` do. That is also what `scripts/set-version.sh` takes for a version.
     */
    @Test
    fun theVersionIsTheOneOfTheBuild() {
        assertEquals(System.getProperty("mockey.version"), ServerVersion.text)
        assertTrue(Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z]+(\.[0-9A-Za-z]+)*)?""").matches(ServerVersion.text), ServerVersion.text)
    }

    /** Arguments or variables that the command refuses, and the option or the variable that its message has to name. */
    private class Refused(
        val named: String,
        val arguments: List<String> = emptyList(),
        val variables: Map<String, String> = emptyMap(),
    ) {
        override fun toString(): String = "$arguments with $variables"
    }

    private fun refusedOption(
        option: String,
        vararg values: String,
    ) = Refused(option, listOf(option) + values)

    private fun refusedVariable(
        variable: String,
        value: String,
    ) = Refused(variable, variables = mapOf(variable to value))

    @Test
    fun aValueThatCannotBeUsedEndsTheProgramWithAnErrorThatNamesItsSource() {
        val file = Files.createTempFile("mockey-definitions", ".txt")
        val refused =
            listOf(
                refusedOption("--mock-port", "http"),
                refusedOption("--mock-port", "65536"),
                refusedOption("--mock-port", "-1"),
                refusedOption("--mock-port"),
                refusedOption("--admin-port", "8081.5"),
                refusedVariable("MOCKEY_ADMIN_PORT", "70000"),
                refusedVariable("MOCKEY_MOCK_PORT", ""),
                refusedOption("--mock-host", ""),
                refusedOption("--admin-host", " "),
                refusedVariable("MOCKEY_MOCK_HOST", ""),
                refusedOption("--admin-token", ""),
                refusedOption("--admin-token", "  "),
                refusedVariable("MOCKEY_ADMIN_TOKEN", ""),
                Refused("--allow-unprotected-admin", listOf("--allow-unprotected-admin=yes")),
                refusedVariable("MOCKEY_ALLOW_UNPROTECTED_ADMIN", "maybe"),
                refusedOption("--definitions", file.toString()),
                refusedVariable("MOCKEY_READ_ONLY", "sometimes"),
                refusedOption("--initial-definitions", ""),
                refusedVariable("MOCKEY_INITIAL_DEFINITIONS", " "),
                refusedOption("--history-limit", "-1"),
                refusedOption("--history-limit", "many"),
                refusedOption("--body-capture-limit", "-1"),
                refusedVariable("MOCKEY_BODY_CAPTURE_LIMIT", "1MiB"),
                refusedOption("--upstream-connect-timeout", "0s"),
                refusedOption("--upstream-connect-timeout", "10"),
                refusedOption("--upstream-connect-timeout", "-10s"),
                refusedOption("--upstream-response-timeout", "soon"),
                refusedOption("--upstream-response-timeout", "Infinity"),
                refusedVariable("MOCKEY_UPSTREAM_RESPONSE_TIMEOUT", "0ms"),
                refusedOption("--max-request-body", "-1"),
                refusedOption("--max-request-body", "2147483640"),
                refusedOption("--max-request-body", "9223372036854775808"),
                refusedVariable("MOCKEY_FORWARD_UNKNOWN_TARGETS", "2"),
                Refused("--legacy-shim", listOf("--legacy-shim=on")),
                refusedVariable("MOCKEY_LEGACY_SHIM", "perhaps"),
                refusedOption("--log-level", "loud"),
                refusedVariable("MOCKEY_LOG_LEVEL", ""),
                refusedOption("--shutdown-grace-period", "-1s"),
                refusedOption("--shutdown-grace-period", "Infinity"),
                refusedOption("--port", "8080"),
                Refused("serve", listOf("serve")),
            )
        try {
            for (case in refused) {
                val run = run(*case.arguments.toTypedArray(), variables = case.variables)

                assertEquals(1, run.status, "$case")
                assertEquals(emptyList(), run.served, "$case")
                assertEquals("", run.output, "$case")
                assertTrue(run.errors.startsWith("$USAGE\n\nError: "), "$case gave ${run.errors}")
                assertContains(run.errors.substringAfter("Error: "), case.named, message = "$case")
            }
        } finally {
            Files.delete(file)
        }
    }

    @Test
    fun aServerThatDoesNotStartEndsTheProgramWithItsReasonAndAnotherStatus() {
        val output = StringBuilder()
        val errors = StringBuilder()
        val command = ServerCommand({ null }, output, errors) { throw StartupFailure("The server has a reason not to start.") }

        val status = command.execute(listOf("--mock-port", "9100"))

        assertEquals(1, status)
        assertEquals("Error: The server has a reason not to start.\n", errors.toString())
        assertEquals("", output.toString())
    }

    @Test
    fun noMessageOfTheCommandHoldsTheAdminToken() {
        val token = "tk-4c1e9a70b2d853f6"
        val ofVariable = mapOf("MOCKEY_ADMIN_TOKEN" to token)
        val runs =
            listOf(
                run("--admin-token", token),
                run("--admin-token=$token", "--help"),
                run("--admin-token", token, "--version"),
                run("--admin-token", token, "--mock-port", "http"),
                run("--admin-token", token, "--port", "8080"),
                run("--admin-token", token, "serve"),
                run("--admin-tokn=$token"),
                run(variables = ofVariable),
                run("--help", variables = ofVariable),
                run("--log-level", "loud", variables = ofVariable),
                run("--admin-token", token, "import-legacy", "no-such-source"),
                run("import-legacy", "--help", variables = ofVariable),
            )

        assertEquals(listOf(0, 0, 0, 1, 1, 1, 1, 0, 0, 1, 1, 0), runs.map { it.status })
        for (run in runs) {
            assertFalse(token in run.output, run.output)
            assertFalse(token in run.errors, run.errors)
            for (settings in run.served) {
                assertTrue(settings.adminToken!!.matches(token))
                assertFalse(token in settings.toString(), settings.toString())
                assertFalse(token in settings.summary().toString())
            }
        }
    }
}

/** The first line of the help of the program and of its messages about arguments that it cannot use. */
private const val USAGE = "Usage: mockey-server [<options>] [<command> [<args>]...]"
