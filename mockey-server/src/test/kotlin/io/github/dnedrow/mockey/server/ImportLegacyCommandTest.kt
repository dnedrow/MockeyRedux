package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.storage.FileDefinitionStorage
import io.github.dnedrow.mockey.legacy.xml.LegacyDirectoryImporter
import io.github.dnedrow.mockey.server.TestDefinitions.orders
import io.github.dnedrow.mockey.server.TestDefinitions.staticService
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The command of the server program that imports the definitions of Mockey 2.x: what it stores in the
 * definitions location, what it prints, and with which status the program ends. The command runs in the
 * process of the test, with a function in the place of the server.
 */
class ImportLegacyCommandTest {
    private val levelBefore = ServerLogging.level()
    private val directory: Path = Files.createTempDirectory("mockey-import-legacy-test")

    /** The definitions location of a test. It does not exist until the test or an import makes it. */
    private val location: Path = directory.resolve("mockey-definitions")
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun endWhatTheTestStarted() {
        closeables.forEach(AutoCloseable::close)
        if (Files.isDirectory(location)) TestEnvironment.permit(location, "rwxr-xr-x")
        directory.toFile().deleteRecursively()
        ServerLogging.configure(levelBefore)
    }

    /** What a run of the program gave: its exit status, what it printed, and how often it ran the server. */
    private class Run(
        val status: Int,
        val output: String,
        val errors: String,
        val served: Int,
    ) {
        override fun toString(): String = "status $status\n--- standard output:\n$output--- standard error:\n$errors"
    }

    /**
     * The definitions location that the environment of a run names, unless the test gives the variable
     * another value. No test wants an import to go there: it keeps an import that takes the location from
     * the wrong place out of the default location, which is in the directory in which the tests run.
     */
    private val ofEnvironment: Path = directory.resolve("location-of-the-environment")

    /** Runs the program with [arguments] and with [variables] in its environment. */
    private fun run(
        vararg arguments: String,
        variables: Map<String, String> = emptyMap(),
    ): Run {
        val output = StringBuilder()
        val errors = StringBuilder()
        var served = 0
        val environment = mapOf("MOCKEY_DEFINITIONS" to "$ofEnvironment") + variables
        val status = ServerCommand(environment::get, output, errors) { served++ }.execute(arguments.asList())
        return Run(status, output.toString(), errors.toString(), served)
    }

    /** Runs the command for [source] and for the definitions location of the test, with [more] arguments. */
    private fun import(
        source: Any,
        vararg more: String,
    ): Run {
        val run = run("import-legacy", "$source", "--definitions", "$location", *more)
        assertFalse(Files.exists(ofEnvironment), "The import went to the location that the option of the command names")
        return run
    }

    /** Returns the definitions that [directory] holds as a definitions location, as they are stored. */
    private fun stored(directory: Path = location): Definitions = runBlocking { FileDefinitionStorage(directory).load() }

    private fun Definitions.service(name: String): Service = services.single { it.name == name }

    private fun Service.answers(): List<Pair<String, String>> = scenarios.map { it.name to it.responseBody }

    private fun Service.defaultScenarioName(): String? = scenarios.singleOrNull { it.id == defaultScenarioId }?.name

    /** Writes [text] to the file [name] and returns the path of the file. */
    private fun file(
        name: String,
        text: String,
    ): Path = Files.writeString(directory.resolve(name), text)

    /** Returns this text with [old] replaced by [new], which the text has to hold once. */
    private fun String.with(
        old: String,
        new: String,
    ): String {
        assertEquals(1, split(old).size - 1, "The text holds '$old' once")
        return replace(old, new)
    }

    /**
     * Returns the report that the command prints for an import of [source] into [into]: the [sections] in
     * between its first line and its last one, which says that the location [holds] something.
     */
    private fun report(
        source: Any,
        sections: String,
        holds: String,
        into: Path = location,
    ): String = "Imported $source into the definitions location $into.\n\n$sections\n\nThe definitions location now holds $holds.\n"

    private val nothingElse = "Conflicts: none\nIssues of the source: none\nRule sets that are not well formed: none"

    /** Returns the conflict of the report for the service `Legacy Orders` when the location has the service [existing] with its name. */
    private fun mergedInto(existing: String): String =
        "The service 'Legacy Orders' was not added: the service '$existing' has that name. " +
            "What that service did not have of it was added to it."

    private val nameTheDirectory =
        "A document is read without the files that it refers to. " +
            "A definitions directory of Mockey 2.x is read with its files when the directory is named, and not its main file."

    private val legacyOrdersAdded =
        """
        Additions: 5
          The service 'Legacy Orders' was added with 2 scenarios.
          The scenario 'silver' was added with its service 'Legacy Orders'.
          The scenario 'gold' was added with its service 'Legacy Orders'.
          The plan 'Gold path' was added.
          The plan 'Gold path' was designated as the default plan and applied.
        """.trimIndent()

    // What the command imports

    /** The scenario "Migrate a legacy directory". */
    @Test
    fun theCommandImportsADefinitionsDirectoryOfMockey2IntoTheDefinitionsLocationAndPrintsTheReport() {
        val legacy = LegacySources.directory(directory.resolve("legacy"))
        val before = TestDefinitions.contentOf(legacy)

        val run = import(legacy)

        val additions =
            """
            Additions: 2
              The service 'Directory Orders' was added with 1 scenario.
              The scenario 'list' was added with its service 'Directory Orders'.
            """.trimIndent()
        val holds = "1 service with 1 scenario, 0 plans, and 0 twist configurations"
        assertEquals(report(legacy, "$additions\n$nothingElse", holds), run.output)
        assertEquals(0, run.status, "$run")
        assertEquals("", run.errors)
        assertEquals(0, run.served, "No server ran")
        val service = stored().services.single()
        assertEquals("Directory Orders" to "directory/orders", service.name to service.mockUrl)
        assertEquals(listOf("list" to LegacySources.DIRECTORY_ORDERS), service.answers())
        assertEquals(before, TestDefinitions.contentOf(legacy), "Nothing of the source has changed")
    }

    @Test
    fun aServerThatStartsWithTheDefinitionsLocationAnswersWithWhatWasImported() {
        val imported = import(LegacySources.directory(directory.resolve("legacy")))
        val settings = ServerSettings(mockHost = "127.0.0.1", mockPort = 0, adminPort = 0, definitions = location, logLevel = LogLevel.WARN)

        val server = MockeyServer.start(settings).also { closeables += it }
        val answer = RawHttp.get(server.listeners.mock.address, "/service/directory/orders")

        assertEquals(0, imported.status, "$imported")
        assertEquals(200 to LegacySources.DIRECTORY_ORDERS, answer.status to answer.body)
    }

    @Test
    fun theCommandImportsAnExportDocumentWithItsPlansAndAppliesItsDefaultPlan() {
        val export = file("export.xml", LegacySources.export(withDefaultPlan = true))

        val run = import(export)

        val holds = "1 service with 2 scenarios, 1 plan, and 0 twist configurations"
        assertEquals(report(export, "$legacyOrdersAdded\n$nothingElse", holds), run.output)
        assertEquals(0, run.status, "$run")
        val stored = stored()
        val service = stored.services.single()
        assertEquals(listOf("silver" to LegacySources.SILVER_ORDERS, "gold" to LegacySources.GOLD_ORDERS), service.answers())
        assertEquals("gold", service.defaultScenarioName(), "The default plan was applied, and what it did is stored")
        assertEquals("Gold path", stored.plans.single { it.id == stored.settings.defaultPlanId }.name)
    }

    /** An export document is what a running Mockey 2.x gives at a URL, so that the command can fetch it from there. */
    @Test
    fun anExportDocumentCanBeImportedFromAUrl() {
        val legacy = StubUpstream(body = LegacySources.export(withDefaultPlan = true)).also { closeables += it }
        val url = "${legacy.origin}/export"

        val run = import(url)

        val holds = "1 service with 2 scenarios, 1 plan, and 0 twist configurations"
        assertEquals(report(url, "$legacyOrdersAdded\n$nothingElse", holds), run.output)
        assertEquals(0, run.status, "$run")
        assertEquals(listOf("GET /export"), legacy.requests.map { "${it.method} ${it.target}" })
        assertEquals("Legacy Orders", stored().services.single().name)
    }

    /** The user information and the query of a URL can hold a password or a key, which the output of a program is no place for. */
    @Test
    fun theReportAndTheReasonOfARefusalNameAUrlWithoutItsUserInformationAndItsQuery() {
        val legacy = StubUpstream(body = LegacySources.export()).also { closeables += it }
        val unavailable = StubUpstream(status = "503 Service Unavailable").also { closeables += it }
        val secrets = listOf("s3cret-of-the-migrator", "k-4c1e9a70")
        val withSecrets = { origin: String -> origin.replace("http://", "http://migrator:${secrets[0]}@") + "/export?key=${secrets[1]}" }
        val shown = { origin: String -> origin.replace("http://", "http://***@") + "/export?***" }

        val imported = import(withSecrets(legacy.origin))
        val refused = import(withSecrets(unavailable.origin))

        assertTrue(imported.output.startsWith("Imported ${shown(legacy.origin)} into the definitions location $location.\n"), "$imported")
        assertEquals(
            "Error: The definitions cannot be read from ${shown(unavailable.origin)}. The answer has the status 503.\n",
            refused.errors,
        )
        assertEquals(listOf(0, 1), listOf(imported.status, refused.status))
        assertEquals(listOf("/export?key=${secrets[1]}"), legacy.requests.map { it.target }, "The request has the whole URL")
        for (secret in secrets) assertFalse(secret in "$imported$refused", "$imported$refused")
    }

    // The definitions that the location holds already

    @Test
    fun theImportAddsToTheDefinitionsOfTheLocationAndLeavesWhatTheyHoldAsItIs() {
        TestDefinitions.store(location) {
            staticService("legacy orders", "orders/of/the/location", "silver" to LegacySources.SILVER_ORDERS, "bronze" to "bronze orders")
            staticService("Customers", "customers", "all" to "all customers")
        }
        val before = stored()
        val export = file("export.xml", LegacySources.export())

        val run = import(export)

        val sections =
            """
            Additions: 2
              The scenario 'gold' was added to the service 'legacy orders'.
              The plan 'Gold path' was added.
            Conflicts: 2
              The scenario 'silver' was not added: the service 'legacy orders' has a scenario with that name and response body.
              ${mergedInto("legacy orders")}
            Issues of the source: none
            Rule sets that are not well formed: none
            """.trimIndent()
        assertEquals(report(export, sections, "2 services with 4 scenarios, 1 plan, and 0 twist configurations"), run.output)
        assertEquals(0, run.status, "$run")
        val after = stored()
        val merged = after.service("legacy orders")
        assertEquals(before.service("legacy orders").scenarios, merged.scenarios.dropLast(1), "Its scenarios are as they were")
        assertEquals("gold" to LegacySources.GOLD_ORDERS, merged.answers().last())
        assertEquals(before.service("legacy orders"), merged.copy(scenarios = merged.scenarios.dropLast(1)), "Nothing else of it changed")
        assertEquals(before.service("Customers"), after.service("Customers"))
        assertEquals(listOf("legacy orders", "Customers"), after.services.map { it.name })
    }

    @Test
    fun anImportThatIsRunAgainAddsNothingAndStoresNothing() {
        val export = file("export.xml", LegacySources.export(withDefaultPlan = true))
        val first = import(export)
        val before = TestDefinitions.contentOf(location)

        val second = import(export)

        val sections =
            """
            Additions: none
            Conflicts: 5
              The scenario 'silver' was not added: the service 'Legacy Orders' has a scenario with that name and response body.
              The scenario 'gold' was not added: the service 'Legacy Orders' has a scenario with that name and response body.
              ${mergedInto("Legacy Orders")}
              The plan 'Gold path' was not added: a plan has that name.
              The default plan of the document was not designated: a default plan is designated already.
            Issues of the source: none
            Rule sets that are not well formed: none
            """.trimIndent()
        assertEquals(0, first.status, "$first")
        assertEquals(report(export, sections, "1 service with 2 scenarios, 1 plan, and 0 twist configurations"), second.output)
        assertEquals(0, second.status, "$second")
        assertEquals(before, TestDefinitions.contentOf(location), "No file of the location was written again")
    }

    // What the report says about the source

    @Test
    fun theReportNamesTheFilesOfTheSourceThatCouldNotBeReadAndTheRestIsImported() {
        val legacy = LegacySources.directory(directory.resolve("legacy"))
        val mainFile = legacy.resolve("mock_service_definitions.xml")
        val missing = """<serviceref file="mockey_def_depot/gone/gone.xml"/>"""
        Files.writeString(mainFile, Files.readString(mainFile).with("</mockservice>", "$missing\n</mockservice>"))
        val issue = LegacyDirectoryImporter(legacy).import().issues.single()

        val run = import(legacy)

        assertContains(issue.message, "mockey_def_depot/gone/gone.xml")
        assertContains(
            run.output,
            "\nConflicts: none\nIssues of the source: 1\n  ${issue.message}\nRule sets that are not well formed: none\n",
        )
        assertEquals(0, run.status, "$run")
        assertEquals("", run.errors)
        assertEquals(listOf("Directory Orders"), stored().services.map { it.name })
    }

    @Test
    fun theReportNamesTheRuleSetsThatAreNotWellFormedAndTheirTextIsImportedAsItIs() {
        val noRuleSet = "silver AND gold"
        val rules = """<scenario_match scenario_match_evaluation_rules_flag="true">$noRuleSet</scenario_match>"""
        val export = LegacySources.export().replaceFirst("""<scenario_match scenario_match_evaluation_rules_flag="false"/>""", rules)

        val run = import(file("export.xml", export))

        val entry = "The service 'Legacy Orders': The rules of the scenario 'silver' cannot be read. "
        val criterion =
            stored()
                .services
                .single()
                .scenarios
                .first()
                .matchCriterion
        assertContains(export, rules)
        assertContains(run.output, "\nRule sets that are not well formed: 1\n  $entry")
        assertContains(run.output, "\nAdditions: 4\n")
        assertEquals(0, run.status, "$run")
        assertContains("$criterion", noRuleSet)
    }

    @Test
    fun theReportNamesWhatTheDefaultPlanHoldsForAServiceAndAScenarioThatDoNotExist() {
        val forNoService = """<plan_item hang_time="0" scenario_name="any" service_name="Payments" service_response_type="1"/>"""
        val export =
            LegacySources
                .export(withDefaultPlan = true)
                .with("""scenario_name="gold" service_name="Legacy Orders"""", """scenario_name="platinum" service_name="Legacy Orders"""")
                .with("</service_plan>", "$forNoService\n</service_plan>")

        val run = import(file("export.xml", export))

        val noScenario =
            "The plan names the scenario 'platinum' of the service 'Legacy Orders', which has no scenario with that name. " +
                "The default scenario of that service stays as it is."
        val section =
            """
            Rule sets that are not well formed: none
            Parts of the default plan 'Gold path' that were not applied: 2
              The plan has settings for the service 'Payments', and no service has that name. They were not applied.
              $noScenario

            The definitions location now holds
            """.trimIndent()
        assertContains(run.output, section)
        assertContains(run.output, "  The plan 'Gold path' was designated as the default plan and applied.\n")
        assertEquals(0, run.status, "$run")
        assertEquals("silver", stored().services.single().defaultScenarioName())
    }

    @Test
    fun aDocumentThatRefersToFilesIsImportedWithoutThemAndTheReportSaysHowTheyAreRead() {
        val reference = """<serviceref file="mockey_def_depot/other/other.xml"/>"""
        val export = LegacySources.export().with("<service_plan", "$reference\n<service_plan")

        val run = import(file("export.xml", export))

        val section =
            """
            Issues of the source: 1
              The file mockey_def_depot/other/other.xml was not read: no directory is imported, so no file is read.
              Note: $nameTheDirectory
            Rule sets that are not well formed: none
            """.trimIndent()
        assertContains(run.output, "\n$section\n")
        assertEquals(0, run.status, "$run")
        assertEquals(listOf("Legacy Orders"), stored().services.map { it.name })
    }

    // Tags

    @Test
    fun theTagsOfTheCommandAreGivenToEveryServiceScenarioAndPlanThatTheImportAdds() {
        val export = file("export.xml", LegacySources.export(withDefaultPlan = true))

        val run = import(export, "--tags", "Migrated  sprint42")

        val tags = Tags.of("migrated", "sprint42")
        val stored = stored()
        assertEquals(0, run.status, "$run")
        assertEquals(tags, stored.services.single().tags)
        assertEquals(
            listOf(tags, tags),
            stored.services
                .single()
                .scenarios
                .map { it.tags },
        )
        assertEquals(tags, stored.plans.single().tags)
    }

    @Test
    fun theTagsOfTheCommandAreGivenToAServiceThatHasTheNameOfAnImportedOneAndNotToItsScenarios() {
        TestDefinitions.store(location) {
            staticService("Legacy Orders", "orders", "silver" to LegacySources.SILVER_ORDERS, tags = Tags.of("stored"))
        }

        val run = import(file("export.xml", LegacySources.export()), "--tags=migrated")

        val service = stored().services.single()
        assertEquals(0, run.status, "$run")
        assertEquals(Tags.of("migrated", "stored"), service.tags)
        assertEquals(listOf("silver" to Tags.EMPTY, "gold" to Tags.of("migrated")), service.scenarios.map { it.name to it.tags })
    }

    @Test
    fun anImportWithoutTheOptionForTagsAddsNoTag() {
        val run = import(file("export.xml", LegacySources.export()))

        val stored = stored()
        assertEquals(0, run.status, "$run")
        assertEquals(Tags.EMPTY, stored.services.single().tags)
        assertEquals(
            listOf(Tags.EMPTY, Tags.EMPTY),
            stored.services
                .single()
                .scenarios
                .map { it.tags },
        )
        assertEquals(Tags.EMPTY, stored.plans.single().tags)
    }

    // The definitions location

    @Test
    fun theDefinitionsLocationIsTheOneOfTheServerWhenTheCommandNamesNone() {
        val export = file("export.xml", LegacySources.export()).toString()
        val ofServerOption = directory.resolve("of-the-option-of-the-server")
        val ofVariable = directory.resolve("of-the-variable")

        val withServerOption = run("--definitions", "$ofServerOption", "import-legacy", export)
        val withVariable = run("import-legacy", export, variables = mapOf("MOCKEY_DEFINITIONS" to "$ofVariable"))

        for ((run, directory) in listOf(withServerOption to ofServerOption, withVariable to ofVariable)) {
            assertEquals(0, run.status, "$run")
            assertTrue(run.output.startsWith("Imported $export into the definitions location $directory.\n"), run.output)
            assertEquals(listOf("Legacy Orders"), stored(directory).services.map { it.name })
        }
    }

    @Test
    fun theReportNamesTheDefinitionsLocationByItsWholePathWithoutDetours() {
        val export = file("export.xml", LegacySources.export())
        val elsewhere = Files.createDirectory(directory.resolve("elsewhere"))

        val run = import(export, "--definitions", "$elsewhere/../mockey-definitions")

        assertEquals(0, run.status, "$run")
        assertTrue(run.output.startsWith("Imported $export into the definitions location $location.\n"), run.output)
        assertEquals(listOf("Legacy Orders"), stored().services.map { it.name })
    }

    @Test
    fun theOptionOfTheCommandComesBeforeTheOptionOfTheServerWhichComesBeforeTheVariable() {
        val export = file("export.xml", LegacySources.export()).toString()
        val ofCommand = directory.resolve("of-the-command")
        val ofServerOption = directory.resolve("of-the-option-of-the-server")
        val ofVariable = directory.resolve("of-the-variable")
        val variables = mapOf("MOCKEY_DEFINITIONS" to "$ofVariable")

        val first = run("--definitions", "$ofServerOption", "import-legacy", "--definitions", "$ofCommand", export, variables = variables)
        val onlyOfCommand = listOf(ofCommand, ofServerOption, ofVariable).filter(Files::exists)
        val second = run("--definitions", "$ofServerOption", "import-legacy", export, variables = variables)

        assertEquals(listOf(0, 0), listOf(first.status, second.status), "$first\n$second")
        assertEquals(listOf(ofCommand), onlyOfCommand)
        assertEquals(listOf(ofCommand, ofServerOption), listOf(ofCommand, ofServerOption, ofVariable).filter(Files::exists))
    }

    @Test
    fun theSettingsOfTheServerStartNoServerAndReadOnlyModeDoesNotKeepTheImportFromBeingStored() {
        val export = file("export.xml", LegacySources.export()).toString()
        val variables = mapOf("MOCKEY_READ_ONLY" to "true", "MOCKEY_INITIAL_DEFINITIONS" to "$directory/no-such-file")

        val run = run("--mock-port", "9100", "--read-only", "import-legacy", export, "--definitions", "$location", variables = variables)

        assertEquals(0, run.status, "$run")
        assertEquals(0, run.served)
        assertEquals(listOf("Legacy Orders"), stored().services.map { it.name })
    }

    @Test
    fun aSettingOfTheServerThatCannotBeUsedIsReportedAndNothingIsImported() {
        val export = file("export.xml", LegacySources.export()).toString()

        val ofOption = run("--mock-port", "http", "import-legacy", export, "--definitions", "$location")
        val ofVariable = run("import-legacy", export, "--definitions", "$location", variables = mapOf("MOCKEY_LOG_LEVEL" to "loud"))

        for ((run, named) in listOf(ofOption to "--mock-port", ofVariable to "MOCKEY_LOG_LEVEL")) {
            assertEquals(1, run.status, "$run")
            assertTrue(
                run.errors.startsWith("Usage: mockey-server [<options>] [<command> [<args>]...]\n\nError: invalid value for $named: "),
                "$run",
            )
            assertEquals("", run.output)
        }
        assertFalse(Files.exists(location))
    }

    // An import that is not carried out

    /** Returns what the command writes to the standard error when it refuses to import [source], and checks that it did nothing. */
    private fun refusalOf(
        source: Any,
        vararg more: String,
    ): String {
        val before = TestDefinitions.contentOf(location)
        val run = import(source, *more)
        assertEquals(1, run.status, "$run")
        assertEquals("", run.output)
        assertEquals(0, run.served)
        assertEquals(before, TestDefinitions.contentOf(location), "The definitions location is as it was")
        return run.errors
    }

    @Test
    fun aSourceThatCannotBeReadEndsTheProgramWithItsReasonAndTheDefinitionsLocationStaysAsItIs() {
        val nothing = directory.resolve("no-such-directory")
        val withoutMainFile = Files.createDirectory(directory.resolve("without-main-file"))
        val text = file("notes.txt", "Notes on the services")
        val unavailable = StubUpstream(status = "404 Not Found").also { closeables += it }

        assertEquals("Error: The definitions cannot be read from $nothing. There is no such file or directory.\n", refusalOf(nothing))
        assertEquals(
            "Error: The definitions cannot be read from $withoutMainFile. The directory holds no file mock_service_definitions.xml, " +
                "which a definitions directory of Mockey 2.x has.\n",
            refusalOf(withoutMainFile),
        )
        assertEquals(
            "Error: The definitions cannot be read from $text. The document is neither a definitions document of this system, " +
                "which is a JSON object, nor one of Mockey 2.x, which is XML.\n",
            refusalOf(text),
        )
        assertEquals(
            "Error: The definitions cannot be read from ${unavailable.origin}/export. The answer has the status 404.\n",
            refusalOf("${unavailable.origin}/export"),
        )
        assertFalse(Files.exists(location), "No definitions location was made")
    }

    @Test
    fun theMainFileOfADefinitionsDirectoryIsNotImportedWithoutItsFilesAndTheReasonSaysToNameTheDirectory() {
        val mainFile = LegacySources.directory(directory.resolve("legacy")).resolve("mock_service_definitions.xml")

        val refusal = refusalOf(mainFile)

        val serviceFile = "mockey_def_depot/directoryorders/directoryorders.xml"
        assertEquals(
            "Error: The definitions cannot be read from $mainFile. " +
                "The file $serviceFile was not read: no directory is imported, so no file is read. $nameTheDirectory\n",
            refusal,
        )
        assertFalse(Files.exists(location))
    }

    @Test
    fun aDefinitionsDocumentOfThisSystemIsNotImportedByTheCommand() {
        val own = file("definitions.json", TestDefinitions.document { orders() })

        val refusal = refusalOf(own)

        assertEquals(
            "Error: The definitions cannot be read from $own. The document is a definitions document of this system. " +
                "The command imports the definitions of Mockey 2.x, which are XML.\n",
            refusal,
        )
        assertFalse(Files.exists(location))
    }

    @Test
    fun aDefinitionsLocationWhoseDefinitionsCannotBeLoadedKeepsTheImportFromBeingCarriedOut() {
        TestDefinitions.store(location) { orders() }
        val scenarioFile = location.resolve("services/orders/scenarios/gold.json")
        Files.writeString(scenarioFile, """{"name": """)

        val refusal = refusalOf(file("export.xml", LegacySources.export()))

        assertTrue(refusal.startsWith("Error: The definitions location cannot be used. The definition file $scenarioFile "), refusal)
    }

    @Test
    fun anImportThatCannotBeStoredIsNotCarriedOut() {
        Files.createDirectory(location)
        val forbidden = TestEnvironment.permit(location, "r-xr-xr-x") && !Files.isWritable(location)
        TestEnvironment.needs(forbidden, "a user who cannot write to every directory")

        val refusal = refusalOf(file("export.xml", LegacySources.export()))

        assertTrue(refusal.startsWith("Error: The imported definitions cannot be stored in the definitions location. "), refusal)
        assertContains(refusal, "$location")
    }

    // The arguments of the command

    @Test
    fun argumentsThatTheCommandCannotUseEndTheProgramWithTheUsageOfTheCommand() {
        val export = file("export.xml", LegacySources.export()).toString()
        val aFile = file("a-file.txt", "no directory").toString()
        val refused =
            listOf(
                listOf("import-legacy") to "Error: missing argument <source>",
                listOf("import-legacy", "") to "Error: invalid value for <source>: the source must not be blank",
                listOf("import-legacy", " ") to "Error: invalid value for <source>: the source must not be blank",
                listOf("import-legacy", export, "more") to "Error: got unexpected extra argument (more)",
                listOf("import-legacy", export, "--mock-port=9100") to "Error: no such option --mock-port",
                listOf("import-legacy", export, "--mock-port", "9100") to "Error: no such option --mock-port",
                listOf("import-legacy", export, "--tag=migrated") to "Error: no such option --tag. Did you mean --tags?",
                listOf("import-legacy", "--nothing") to "Error: no such option --nothing\nError: missing argument <source>",
                listOf("import-legacy", export, "--definitions", aFile) to
                    "Error: invalid value for --definitions: directory \"$aFile\" is a file.",
                listOf("import-legacy", export, "--definitions") to "Error: option --definitions requires a value",
            )

        for ((arguments, error) in refused) {
            val run = run(*arguments.toTypedArray(), variables = mapOf("MOCKEY_DEFINITIONS" to "$location"))

            assertEquals("Usage: mockey-server import-legacy [<options>] <source>\n\n$error\n", run.errors, "$arguments")
            assertEquals(1, run.status, "$arguments")
            assertEquals("", run.output, "$arguments")
            assertEquals(0, run.served, "$arguments")
        }
        assertFalse(Files.exists(location), "Nothing was imported")
    }

    // The help

    @Test
    fun theHelpOfTheCommandStatesItsSourceAndItsOptionsAndImportsNothing() {
        val export = file("export.xml", LegacySources.export()).toString()

        for (option in listOf("--help", "-h")) {
            val run = run("import-legacy", export, "--definitions", "$location", option)

            assertEquals(0, run.status, "$run")
            assertEquals("", run.errors)
            assertEquals(0, run.served)
            assertTrue(
                run.output.startsWith("Usage: mockey-server import-legacy [<options>] <source>\n\nImports the definitions of Mockey 2.x"),
                run.output,
            )
            assertContains(
                run.output,
                "\n  --definitions=<directory>\n" +
                    "      Directory of the stored definitions, to which the import adds. The option\n" +
                    "      can stand before the command as well.\n" +
                    "      Environment variable: MOCKEY_DEFINITIONS\n" +
                    "      Default: mockey-definitions\n",
            )
            assertContains(run.output, "\n  --tags=<tags>\n")
            assertContains(run.output, "\n      Default: none\n")
            assertContains(
                run.output,
                "\nArguments:\n  <source>\n      Definitions directory of Mockey 2.x, or the file or the URL of an export\n",
            )
            assertEquals(emptyList(), run.output.lines().filter { it.length > 80 || it != it.trimEnd() })
        }
        assertFalse(Files.exists(location), "Nothing was imported")
    }

    @Test
    fun theHelpOfTheProgramNamesTheCommand() {
        val help = run("--help").output

        assertTrue(help.startsWith("Usage: mockey-server [<options>] [<command> [<args>]...]\n"), help)
        assertTrue(
            help.endsWith(
                "\n\nCommands:\n  import-legacy\n" +
                    "      Imports the definitions of Mockey 2.x into the definitions location and\n" +
                    "      prints the report.\n",
            ),
            help,
        )
    }
}
