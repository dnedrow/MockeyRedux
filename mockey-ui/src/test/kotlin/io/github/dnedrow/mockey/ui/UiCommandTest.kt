package io.github.dnedrow.mockey.ui

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 13.1: the command line of the UI program: where each setting comes from, what the help and the
 * version print, and with which status the program ends. The UI that the command would run is replaced by a
 * function that keeps the settings.
 */
class UiCommandTest {
    /** What a run of the command gave: its exit status, what it printed, and the settings that it ran the UI with. */
    private class Run(
        val status: Int,
        val output: String,
        val errors: String,
        val served: List<UiSettings>,
    ) {
        val settings: UiSettings
            get() = served.single()
    }

    private fun run(
        vararg arguments: String,
        variables: Map<String, String> = emptyMap(),
        serve: (UiSettings) -> Unit = {},
    ): Run {
        val output = StringBuilder()
        val errors = StringBuilder()
        val served = mutableListOf<UiSettings>()
        val command =
            UiCommand(variables::get, output, errors) {
                served += it
                serve(it)
            }
        return Run(command.execute(arguments.asList()), output.toString(), errors.toString(), served)
    }

    /** The scenario "Default bind", for the settings: a UI that is told nothing accepts connections on the loopback address. */
    @Test
    fun aUiThatIsToldNothingListensOnTheLoopbackAddressAndManagesTheServerOfThisMachine() {
        val told = run()

        assertEquals(0, told.status)
        assertEquals(UiSettings(), told.settings)
        assertEquals("127.0.0.1" to 8082, told.settings.host to told.settings.port)
        assertEquals("http://127.0.0.1:8081" to null, "${told.settings.adminUrl}" to told.settings.adminToken)
        assertEquals("" to "", told.output to told.errors)
    }

    @Test
    fun everySettingComesFromItsOptionOrFromItsEnvironmentVariableAndTheOptionTakesPrecedence() {
        val variables =
            mapOf(
                "MOCKEY_UI_HOST" to "192.0.2.2",
                "MOCKEY_UI_PORT" to "9000",
                "MOCKEY_UI_ADMIN_URL" to "https://mockey.test/admin/",
                "MOCKEY_ADMIN_TOKEN" to "of the variable",
                "MOCKEY_UI_ALLOWED_HOST" to "Mockey.Corp, ui.team.example",
                "MOCKEY_UI_LOG_LEVEL" to "DEBUG",
            )
        val ofTheServer = arrayOf("--admin-url", "http://[::1]:9081", "--admin-token", "of the option")
        val names = arrayOf("--allowed-host", "a.example", "--allowed-host=b.example,c.example", "--allowed-host", "a.example")
        val options = arrayOf("--host", "192.0.2.1", "--port=9100", *ofTheServer, *names, "--log-level", "warn")

        val fromVariables = run(variables = variables).settings
        val fromOptions = run(*options, variables = variables).settings

        val behindAProxy = AdminUrl.of("https://mockey.test/admin")
        val told = listOf("mockey.corp", "ui.team.example")
        val ofVariables = UiSettings("192.0.2.2", 9000, behindAProxy, AdminToken("of the variable"), told, LogLevel.DEBUG)
        val named = listOf("a.example", "b.example", "c.example")
        val ofOptions = UiSettings("192.0.2.1", 9100, AdminUrl.of("http://[::1]:9081"), AdminToken("of the option"), named, LogLevel.WARN)
        assertEquals(ofVariables, fromVariables)
        assertEquals(ofOptions, fromOptions)
        assertEquals("https://mockey.test/admin", "${fromVariables.adminUrl}")
    }

    /** The variable of every setting is the one that [UiCommand.variableOf] names, and the help names it. */
    @Test
    fun theHelpNamesEverySettingWithItsVariableAndItsDefault() {
        val help = run("--help")

        assertEquals(0 to emptyList(), help.status to help.served)
        assertEquals("", help.errors)
        for ((option, variable, default) in listOf(
            Triple("--host=<address>", "MOCKEY_UI_HOST", "127.0.0.1"),
            Triple("--port=<port>", "MOCKEY_UI_PORT", "8082"),
            Triple("--admin-url=<url>", "MOCKEY_UI_ADMIN_URL", "http://127.0.0.1:8081"),
            Triple("--admin-token=<token>", "MOCKEY_ADMIN_TOKEN", "none"),
            Triple("--allowed-host=<name>", "MOCKEY_UI_ALLOWED_HOST", "none"),
            Triple("--log-level=(error|warn|info|debug|trace)", "MOCKEY_UI_LOG_LEVEL", "info"),
        )) {
            val described = help.output.substringAfter("  $option\n", "").substringBefore("\n\n")
            assertContains(described, "Environment variable: $variable\n", message = option)
            assertTrue(described.endsWith("Default: $default"), "$option: $described")
        }
        assertEquals(help.output, run("-h").output)
    }

    @Test
    fun theVersionOptionPrintsTheVersionOfTheBuild() {
        val version = run("--version")

        assertEquals(0 to emptyList(), version.status to version.served)
        assertEquals("mockey-ui version ${System.getProperty("mockey.version")}\n", version.output)
    }

    /** A value that cannot be used ends the program with the status 1, before a UI runs. The message names the option or the variable. */
    @Test
    fun aValueThatCannotBeUsedIsReportedWithWhereItCameFrom() {
        val cases =
            listOf(
                run("--port", "70000") to "--port",
                run("--port", "http") to "--port",
                run("--host", " ") to "the address must not be blank",
                run("--log-level", "loud") to "--log-level",
                run("--admin-url", "mockey.test:8081") to "the URL has to begin with http:// or https://",
                run("--admin-url", "ftp://mockey.test") to "the URL has to begin with http:// or https://",
                run("--admin-url", "http://") to "the URL is no URL",
                run("--admin-url", "http:///admin") to "the URL names no host",
                run("--admin-url", "http://user:secret@mockey.test:8081") to "the URL must not hold a user name or a password",
                run("--admin-url", "http://mockey.test:8081/?a=b") to "the URL must not have a query string or a fragment",
                run("--admin-url", "http://mockey test") to "the URL is no URL",
                run("--admin-token", " ") to "the token must not be blank",
                run("--admin-token", "two\nlines") to "the token must not hold a control character",
                run("--admin-token", " padded") to "the token must not begin or end with a space",
                run("--admin-token", "gro\u00dfes-geheimnis") to "the token must not hold a character beyond ASCII",
                run("--allowed-host", "http://mockey.corp") to "the name of a host has letters, digits, hyphens, and dots",
                run("--allowed-host", "mockey.corp:8082") to "and no scheme and no port",
                run("--allowed-host", "mockey.corp,two words") to "--allowed-host",
                run(variables = mapOf("MOCKEY_UI_ALLOWED_HOST" to "mockey.corp;other.corp")) to "MOCKEY_UI_ALLOWED_HOST",
                run(variables = mapOf("MOCKEY_UI_PORT" to "-1")) to "MOCKEY_UI_PORT",
                run(variables = mapOf("MOCKEY_ADMIN_TOKEN" to "\t")) to "MOCKEY_ADMIN_TOKEN",
                run("--no-such-option") to "--no-such-option",
                run("serve") to "serve",
            )

        for ((told, expected) in cases) {
            assertEquals(1 to emptyList(), told.status to told.served, expected)
            assertContains(told.errors, expected)
            assertEquals("", told.output, expected)
        }
    }

    /** No message of the program holds the admin token, also not the one about a URL that holds a password. */
    @Test
    fun noMessageHoldsASecret() {
        val token = "the-token-of-the-server"
        val told =
            listOf(
                run("--admin-token", token, "--port", "70000"),
                run("--admin-token", token, "--help"),
                run("--admin-url", "http://user:$token@mockey.test:8081"),
                run("--admin-token", token) { throw StartupFailure("The UI cannot accept connections.") },
            )

        for (run in told) assertFalse(token in run.output + run.errors, run.errors)
        assertEquals("AdminToken(******)", "${AdminToken(token)}")
        val withToken = UiSettings(adminToken = AdminToken(token))
        assertFalse(token in "$withToken")
        assertEquals("set" to "none", withToken.summary()["admin-token"] to UiSettings().summary()["admin-token"])
    }

    @Test
    fun aUiThatDoesNotStartEndsTheProgramWithItsReason() {
        val told = run { throw StartupFailure("The UI cannot accept connections on 127.0.0.1:8082.") }

        assertEquals(1, told.status)
        assertEquals("Error: The UI cannot accept connections on 127.0.0.1:8082.\n", told.errors)
    }
}
