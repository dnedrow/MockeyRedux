package io.github.dnedrow.mockey.legacy.xml

import io.github.dnedrow.mockey.core.definitions.DiagnosticKind
import io.github.dnedrow.mockey.core.definitions.ImportItem
import io.github.dnedrow.mockey.core.definitions.ImportItemKind
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.PlanId
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Settings
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistConfiguration
import io.github.dnedrow.mockey.core.model.TwistId
import io.github.dnedrow.mockey.core.storage.FileDefinitionStorage
import io.github.dnedrow.mockey.core.storage.InMemoryDefinitionStorage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a legacy source is imported into an engine, and what the report of the import says: what was added and
 * what was not, which files could not be read, and which rule sets are not well formed. An import leaves its
 * source as it is.
 */
class LegacyEngineImportTest {
    @TempDir
    lateinit var directory: Path

    /** Runs [test] with an engine that starts with the definitions [initial], and closes the engine after it. */
    private fun withEngine(
        initial: Definitions = Definitions.EMPTY,
        test: suspend (MockeyEngine) -> Unit,
    ): Unit = runBlocking { MockeyEngine.create { storage = InMemoryDefinitionStorage(initial) }.use { test(it) } }

    /** Writes [content] to the file [path] of the directory of the test. */
    private fun write(
        path: Path,
        content: String,
    ): Path {
        val file = directory.resolve(path)
        Files.createDirectories(file.parent)
        return Files.writeString(file, content)
    }

    /**
     * Copies the definitions directory that Mockey 2.x wrote into the directory of the test and returns the
     * copy, so that a test can take a file out of it. Every file and every directory of the copy was last
     * modified long ago, so a later change of it shows.
     */
    private fun copyOfTheLegacyDirectory(): Path {
        val copy = directory.resolve("legacy")
        Files.walk(LegacyFixtures.directory).use { paths ->
            paths.forEach { source ->
                val target = copy.resolve(LegacyFixtures.directory.relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
            }
        }
        Files.walk(copy).use { paths -> paths.forEach { Files.setLastModifiedTime(it, LONG_AGO) } }
        return copy
    }

    /** What a file or a directory is: its content, which a directory has none of, and when it was last modified. */
    private data class FileState(
        val content: List<Byte>?,
        val lastModified: FileTime,
    )

    /** Returns the state of every file and every directory in [root], by its path from there. */
    private fun stateOf(root: Path): Map<Path, FileState> =
        Files.walk(root).use { paths ->
            paths.toList().associate { path ->
                val content = if (Files.isRegularFile(path)) Files.readAllBytes(path).toList() else null
                root.relativize(path) to FileState(content, Files.getLastModifiedTime(path))
            }
        }

    private fun List<ImportItem>.named(): List<Pair<ImportItemKind, String>> = map { it.kind to it.name }

    private fun List<ImportItem>.ofDefinitionsOtherThanServices(): List<Pair<ImportItemKind, String>> =
        named().filter { (kind, _) -> kind != ImportItemKind.SERVICE && kind != ImportItemKind.SCENARIO }

    private fun MockeyEngine.service(name: String): Service = definitions.services.single { it.name == name }

    // The report, and the general rules of an import

    @Test
    fun everyPartOfADirectoryIsAnAdditionWhenTheEngineHoldsNothing() =
        withEngine { engine ->
            val report = LegacyDirectoryImporter(LegacyFixtures.directory).import().importInto(engine)

            val additions =
                listOf(
                    ImportItemKind.SERVICE to "Customer Directory",
                    ImportItemKind.SCENARIO to "silver",
                    ImportItemKind.SCENARIO to "gold",
                    ImportItemKind.SCENARIO to "Directory down",
                    ImportItemKind.SERVICE to "Orders",
                    ImportItemKind.SCENARIO to "order list",
                    ImportItemKind.SCENARIO to "no orders",
                    ImportItemKind.SERVICE to "Billing",
                    ImportItemKind.PLAN to "Gold path",
                    ImportItemKind.PLAN to "Slow day",
                    ImportItemKind.TWIST_CONFIGURATION to "to qa2",
                    ImportItemKind.SETTING to ImportItem.OUTBOUND_PROXY,
                    ImportItemKind.SETTING to ImportItem.UNIVERSAL_ERROR_SCENARIO,
                    ImportItemKind.SETTING to ImportItem.ENABLED_TWIST_CONFIGURATION,
                    ImportItemKind.SETTING to ImportItem.DEFAULT_PLAN,
                )
            assertEquals(additions, report.imported.additions.named())
            assertEquals(emptyList(), report.imported.conflicts)
            assertEquals(emptyList(), report.issues)
            assertEquals(emptyList(), report.unreadableRuleSets)
            assertEquals(listOf("Customer Directory", "Orders", "Billing"), engine.definitions.services.map { it.name })
        }

    @Test
    fun theDefaultPlanOfASourceIsApplied() =
        withEngine { engine ->
            val document =
                """
                <mockservice default_service_plan_id="1">
                  <service name="Orders" url="orders" service_response_type="0" hang_time="0" default_scenario_id="1">
                    <scenario id="1" name="order list"><scenario_response>LIST</scenario_response></scenario>
                    <scenario id="2" name="no orders"><scenario_response>NONE</scenario_response></scenario>
                  </service>
                  <service_plan id="1" name="Slow day">
                    <plan_item service_name="Orders" scenario_name="no orders" service_response_type="1" hang_time="50"/>
                  </service_plan>
                </mockservice>
                """.trimIndent()

            val report = LegacyDocumentImporter.import(document).importInto(engine)

            val orders = engine.service("Orders")
            assertEquals(ResponseMode.STATIC to 50L, orders.responseMode to orders.delayMillis)
            assertEquals("no orders", orders.scenarios.single { it.id == orders.defaultScenarioId }.name)
            assertEquals(
                "Slow day",
                report.imported.appliedDefaultPlan
                    ?.plan
                    ?.name,
            )
            assertEquals(
                engine.definitions.plans
                    .single()
                    .id,
                engine.definitions.settings.defaultPlanId,
            )
        }

    @Test
    fun aServiceWithTheNameOfAServiceIsMergedIntoThatService() {
        val gold = Scenario(id = ScenarioId.generate(), name = "gold", responseBody = "GOLD")
        val existing =
            Service(
                id = ServiceId.generate(),
                name = "Customer Directory",
                mockUrl = "customers",
                scenarios = listOf(gold),
                defaultScenarioId = gold.id,
            )
        val document =
            """
            <mockservice>
              <service name="customer directory" url="directory" hang_time="700">
                <scenario id="1" name="gold"><scenario_response>GOLD</scenario_response></scenario>
                <scenario id="2" name="silver"><scenario_response>SILVER</scenario_response></scenario>
              </service>
            </mockservice>
            """.trimIndent()

        withEngine(Definitions(services = listOf(existing))) { engine ->
            val report = LegacyDocumentImporter.import(document).importInto(engine)

            val service = engine.definitions.services.single()
            assertEquals(existing.copy(scenarios = service.scenarios), service)
            assertEquals(listOf(gold), service.scenarios.dropLast(1))
            assertEquals("silver" to "SILVER", service.scenarios.last().let { it.name to it.responseBody })
            assertEquals(listOf(ImportItemKind.SCENARIO to "silver"), report.imported.additions.named())
            val conflicts = listOf(ImportItemKind.SCENARIO to "gold", ImportItemKind.SERVICE to "customer directory")
            assertEquals(conflicts, report.imported.conflicts.named())
        }
    }

    @Test
    fun aPlanAndATwistConfigurationAreAddedWhenTheirNamesAreNotInUse() {
        val plan = Plan(id = PlanId.generate(), name = "Gold path")
        val twist = TwistConfiguration(id = TwistId.generate(), name = "to qa2")

        withEngine(Definitions(plans = listOf(plan), twists = listOf(twist))) { engine ->
            val report = LegacyDocumentImporter.import(LegacyFixtures.export).importInto(engine)

            assertEquals(listOf(plan), engine.definitions.plans.dropLast(1))
            assertEquals(
                "Slow day",
                engine.definitions.plans
                    .last()
                    .name,
            )
            assertEquals(listOf(twist), engine.definitions.twists)
            assertNull(engine.definitions.settings.enabledTwistId)
            assertContains(report.imported.additions.named(), ImportItemKind.PLAN to "Slow day")
            val conflicts =
                listOf(
                    ImportItemKind.PLAN to "Gold path",
                    ImportItemKind.TWIST_CONFIGURATION to "to qa2",
                    ImportItemKind.SETTING to ImportItem.ENABLED_TWIST_CONFIGURATION,
                )
            assertEquals(conflicts, report.imported.conflicts.named())
        }
    }

    @Test
    fun aSettingIsTakenFromASourceWhereTheEngineHasNone() {
        val proxy = OutboundProxy("http://other-proxy.example.com:8080", enabled = true)

        withEngine(Definitions(settings = Settings(outboundProxy = proxy))) { engine ->
            val report = LegacyDocumentImporter.import(LegacyFixtures.export).importInto(engine)

            val settings = engine.definitions.settings
            assertEquals(proxy, settings.outboundProxy)
            assertEquals(
                "to qa2",
                engine.definitions.twists
                    .single { it.id == settings.enabledTwistId }
                    .name,
            )
            assertEquals(
                "Slow day",
                engine.definitions.plans
                    .single { it.id == settings.defaultPlanId }
                    .name,
            )
            val directory = engine.service("Customer Directory")
            assertEquals(directory.id, settings.universalErrorScenario?.serviceId)
            assertEquals("Directory down", directory.scenarios.single { it.id == settings.universalErrorScenario?.scenarioId }.name)
            assertEquals(listOf(ImportItemKind.SETTING to ImportItem.OUTBOUND_PROXY), report.imported.conflicts.named())
        }
    }

    @Test
    fun theTagsOfAnImportAreAddedToWhatItAddsAndToTheServicesThatItMergesInto() {
        val read = LegacyDirectoryImporter(LegacyFixtures.directory).import()
        val orderList =
            read.definitions.services
                .single { it.name == "Orders" }
                .scenarios
                .single { it.name == "order list" }
        val known = Scenario(id = ScenarioId.generate(), name = "order list", responseBody = orderList.responseBody)
        val orders = Service(id = ServiceId.generate(), name = "Orders", mockUrl = "known-orders", scenarios = listOf(known))

        withEngine(Definitions(services = listOf(orders))) { engine ->
            read.importInto(engine, Tags.of("migrated"))

            val services = engine.definitions.services
            assertEquals(listOf("Orders", "Customer Directory", "Billing"), services.map { it.name })
            assertTrue(services.all { "migrated" in it.tags })
            assertEquals(Tags.of("directory", "migrated", "team-a"), engine.service("Customer Directory").tags)
            assertTrue(engine.service("Customer Directory").scenarios.all { "migrated" in it.tags })
            val ofOrders = engine.service("Orders").scenarios.associate { it.name to ("migrated" in it.tags) }
            assertEquals(mapOf("order list" to false, "no orders" to true), ofOrders)
            assertEquals(listOf(Tags.of("migrated", "premium"), Tags.of("migrated")), engine.definitions.plans.map { it.tags })
        }
    }

    @Test
    fun aSecondImportOfASourceAddsNothing() =
        withEngine { engine ->
            LegacyDirectoryImporter(LegacyFixtures.directory).import().importInto(engine)
            val afterTheFirst = engine.definitions

            val report = LegacyDirectoryImporter(LegacyFixtures.directory).import().importInto(engine)

            assertEquals(emptyList(), report.imported.additions)
            assertEquals(15, report.imported.conflicts.size)
            assertNull(report.imported.appliedDefaultPlan)
            assertEquals(afterTheFirst, engine.definitions)
        }

    @Test
    fun theReportHoldsTheIssuesOfTheSource() =
        withEngine { engine ->
            val document = """<mockservice><service name="Orders" url="orders" error_scenario_id="9"/></mockservice>"""

            val report = LegacyDocumentImporter.import(document).importInto(engine)

            assertEquals(listOf(LegacyXmlIssueKind.DANGLING_REFERENCE), report.issues.map { it.kind })
            assertContains(report.issues.single().message, "error_scenario_id")
            assertNull(engine.service("Orders").errorScenarioId)
        }

    // Files that cannot be read

    @Test
    fun aServiceFileThatDoesNotExistDoesNotStopTheImport() {
        val missing = Path.of("mockey_def_depot", "orders", "orders.xml")
        val present = Path.of("mockey_def_depot", "billing", "billing.xml")
        val main =
            """
            <mockservice>
              <serviceref file="mockey_def_depot/orders/orders.xml"/>
              <serviceref file="mockey_def_depot/billing/billing.xml"/>
            </mockservice>
            """.trimIndent()
        write(Path.of(LegacyDirectoryImporter.MAIN_FILE), main)
        write(present, """<mockservice><service name="Billing" url="billing"/></mockservice>""")

        withEngine { engine ->
            val report = LegacyDirectoryImporter(directory).import().importInto(engine)

            assertEquals(listOf("Billing"), engine.definitions.services.map { it.name })
            assertEquals(listOf(ImportItemKind.SERVICE to "Billing"), report.imported.additions.named())
            val issue = report.issues.single()
            assertEquals(LegacyXmlIssueKind.UNREADABLE_FILE, issue.kind)
            assertEquals(directory.resolve(missing), issue.file)
            assertEquals("The file $missing does not exist.", issue.message)
        }
    }

    @Test
    fun aServiceFileThatIsNoXmlDoesNotStopTheImport() {
        val legacy = copyOfTheLegacyDirectory()
        val broken = Path.of("mockey_def_depot", "billing", "billing.xml")
        Files.writeString(legacy.resolve(broken), """<mockservice><service name="Billing" """)

        withEngine { engine ->
            val report = LegacyDirectoryImporter(legacy).import().importInto(engine)

            assertEquals(listOf("Customer Directory", "Orders"), engine.definitions.services.map { it.name })
            val issue = report.issues.single()
            assertEquals(LegacyXmlIssueKind.MALFORMED_XML, issue.kind)
            assertEquals(legacy.resolve(broken), issue.file)
            assertContains(issue.message, "The file $broken is not well-formed XML")
        }
    }

    @Test
    fun aScenarioFileThatDoesNotExistLeavesTheOtherScenariosOfItsService() {
        val legacy = copyOfTheLegacyDirectory()
        val missing = Path.of("mockey_def_depot", "customerdirectory", "scenarios", "silver.xml")
        Files.delete(legacy.resolve(missing))

        withEngine { engine ->
            val report = LegacyDirectoryImporter(legacy).import().importInto(engine)

            assertEquals(listOf("Customer Directory", "Orders", "Billing"), engine.definitions.services.map { it.name })
            assertEquals(listOf("gold", "Directory down"), engine.service("Customer Directory").scenarios.map { it.name })
            val issue = report.issues.single()
            assertEquals(LegacyXmlIssueKind.UNREADABLE_FILE to legacy.resolve(missing), issue.kind to issue.file)
            assertEquals("The file $missing does not exist.", issue.message)
        }
    }

    @Test
    fun aScenarioWhoseResponseFileDoesNotExistIsImportedWithoutAResponseBody() {
        val legacy = copyOfTheLegacyDirectory()
        val missing = Path.of("mockey_def_depot", "customerdirectory", "scenarios", "gold.txt")
        Files.delete(legacy.resolve(missing))

        withEngine { engine ->
            val report = LegacyDirectoryImporter(legacy).import().importInto(engine)

            val bodies = engine.service("Customer Directory").scenarios.associate { it.name to it.responseBody }
            assertEquals(mapOf("silver" to SILVER, "gold" to "", "Directory down" to DIRECTORY_DOWN), bodies)
            val issue = report.issues.single()
            assertEquals(LegacyXmlIssueKind.UNREADABLE_FILE to legacy.resolve(missing), issue.kind to issue.file)
            assertEquals("The file $missing does not exist.", issue.message)
        }
    }

    @Test
    fun everyFileThatCannotBeReadIsReported() {
        val legacy = copyOfTheLegacyDirectory()
        val depot = Path.of("mockey_def_depot")
        val files =
            listOf(
                depot.resolve(Path.of("customerdirectory", "scenarios", "gold.txt")),
                depot.resolve(Path.of("customerdirectory", "scenarios", "silver.xml")),
                depot.resolve(Path.of("orders", "orders.xml")),
                depot.resolve(Path.of("billing", "billing.xml")),
            )
        files.dropLast(1).forEach { Files.delete(legacy.resolve(it)) }
        Files.writeString(legacy.resolve(files.last()), "no XML")

        withEngine { engine ->
            val report = LegacyDirectoryImporter(legacy).import().importInto(engine)

            assertEquals(files.map(legacy::resolve), report.issues.map { it.file })
            val bodies =
                engine.definitions.services
                    .single()
                    .scenarios
                    .associate { it.name to it.responseBody }
            assertEquals(
                "Customer Directory",
                engine.definitions.services
                    .single()
                    .name,
            )
            assertEquals(mapOf("gold" to "", "Directory down" to DIRECTORY_DOWN), bodies)
            assertEquals(listOf("Gold path", "Slow day"), engine.definitions.plans.map { it.name })
        }
    }

    @Test
    fun aMainFileThatCannotBeReadLeavesTheEngineAsItIs() {
        val legacy = copyOfTheLegacyDirectory()
        Files.writeString(legacy.resolve(LegacyDirectoryImporter.MAIN_FILE), "no XML")
        val orders = Service(id = ServiceId.generate(), name = "Orders", mockUrl = "orders")

        withEngine(Definitions(services = listOf(orders))) { engine ->
            val before = engine.definitions

            val report = LegacyDirectoryImporter(legacy).import().importInto(engine)

            assertEquals(before, engine.definitions)
            assertEquals(emptyList(), report.imported.additions + report.imported.conflicts)
            assertEquals(listOf(LegacyXmlIssueKind.MALFORMED_XML), report.issues.map { it.kind })
            assertEquals(legacy.resolve(LegacyDirectoryImporter.MAIN_FILE), report.issues.single().file)
        }
    }

    // Rule sets that are not well formed

    @Test
    fun aScenarioWithRulesThatAreNotWellFormedIsImportedWithThem() =
        withEngine { engine ->
            val document =
                """
                <mockservice>
                  <service name="Orders" url="orders" request_inspector_name="com.example.inspectors.SessionInspector">
                    <scenario id="1" name="fine">
                      <scenario_match scenario_match_evaluation_rules_flag="true">$WELL_FORMED</scenario_match>
                    </scenario>
                    <scenario id="2" name="broken">
                      <scenario_match scenario_match_evaluation_rules_flag="true">{"parameters": [</scenario_match>
                    </scenario>
                  </service>
                </mockservice>
                """.trimIndent()

            val report = LegacyDocumentImporter.import(document).importInto(engine)

            val orders = engine.service("Orders")
            val broken = orders.scenarios.single { it.name == "broken" }
            assertEquals(MatchCriterion.Rules("""{"parameters": ["""), broken.matchCriterion)
            assertEquals(MatchCriterion.Rules(WELL_FORMED), orders.scenarios.single { it.name == "fine" }.matchCriterion)
            val named = report.unreadableRuleSets.single()
            assertEquals(DiagnosticKind.UNREADABLE_RULE_SET, named.kind)
            assertEquals(orders.id to "Orders", named.serviceId to named.serviceName)
            assertEquals(listOf(broken.id), named.scenarioIds)
            assertContains(named.message, "'broken'")
            assertEquals(listOf(DiagnosticKind.UNKNOWN_INSPECTOR, DiagnosticKind.UNREADABLE_RULE_SET), engine.diagnostics().map { it.kind })
            assertEquals(emptyList(), report.issues)
        }

    @Test
    fun aServiceWithRequestRulesThatAreNotWellFormedIsImportedWithThem() =
        withEngine { engine ->
            val document =
                """
                <mockservice>
                  <service name="Orders" url="orders">
                    <request_inspector_json_rules enable_flag="true">{'parameters': []}</request_inspector_json_rules>
                  </service>
                  <service name="Billing" url="billing">
                    <request_inspector_json_rules enable_flag="true">$WELL_FORMED</request_inspector_json_rules>
                  </service>
                </mockservice>
                """.trimIndent()

            val report = LegacyDocumentImporter.import(document).importInto(engine)

            val orders = engine.service("Orders")
            assertEquals("{'parameters': []}" to true, orders.requestRules to orders.requestRulesEnabled)
            assertEquals(WELL_FORMED, engine.service("Billing").requestRules)
            val named = report.unreadableRuleSets.single()
            assertEquals(DiagnosticKind.UNREADABLE_RULE_SET, named.kind)
            assertEquals(orders.id to "Orders", named.serviceId to named.serviceName)
            assertEquals(emptyList(), named.scenarioIds)
        }

    @Test
    fun anImportReportsTheRuleSetsThatItAdded() =
        withEngine { engine ->
            fun document(service: String) =
                """
                <mockservice>
                  <service name="$service" url="$service">
                    <scenario id="1" name="broken">
                      <scenario_match scenario_match_evaluation_rules_flag="true">[</scenario_match>
                    </scenario>
                  </service>
                </mockservice>
                """.trimIndent()
            LegacyDocumentImporter.import(document("Orders")).importInto(engine)

            val report = LegacyDocumentImporter.import(document("Billing")).importInto(engine)

            assertEquals(listOf("Billing"), report.unreadableRuleSets.map { it.serviceName })
            val known = engine.diagnostics().filter { it.kind == DiagnosticKind.UNREADABLE_RULE_SET }
            assertEquals(listOf("Orders", "Billing"), known.map { it.serviceName })
        }

    @Test
    fun aRuleSetOfWhatWasNotAddedIsNotReported() {
        val known = Scenario(id = ScenarioId.generate(), name = "known", responseBody = "KNOWN")
        val orders = Service(id = ServiceId.generate(), name = "Orders", mockUrl = "orders", scenarios = listOf(known))
        val document =
            """
            <mockservice>
              <service name="Orders" url="orders">
                <request_inspector_json_rules enable_flag="true">[</request_inspector_json_rules>
                <scenario id="1" name="known">
                  <scenario_match scenario_match_evaluation_rules_flag="true">[</scenario_match>
                  <scenario_response>KNOWN</scenario_response>
                </scenario>
                <scenario id="2" name="new">
                  <scenario_match scenario_match_evaluation_rules_flag="true">[</scenario_match>
                  <scenario_response>NEW</scenario_response>
                </scenario>
              </service>
            </mockservice>
            """.trimIndent()

        withEngine(Definitions(services = listOf(orders))) { engine ->
            val report = LegacyDocumentImporter.import(document).importInto(engine)

            val service = engine.definitions.services.single()
            assertNull(service.requestRules)
            assertEquals(listOf(known), service.scenarios.dropLast(1))
            assertEquals(listOf(listOf(service.scenarios.last().id)), report.unreadableRuleSets.map { it.scenarioIds })
            assertContains(report.unreadableRuleSets.single().message, "'new'")
        }
    }

    // The source

    @Test
    fun aDirectoryIsTheSameAfterItsImport(): Unit =
        runBlocking {
            val legacy = copyOfTheLegacyDirectory()
            val location = directory.resolve("definitions")
            val before = stateOf(legacy)

            MockeyEngine
                .create {
                    storage = FileDefinitionStorage(location)
                    readOnly = false
                }.use { engine ->
                    val report = LegacyDirectoryImporter(legacy).import().importInto(engine)

                    assertEquals(15, report.imported.additions.size)
                }

            assertEquals(before, stateOf(legacy))
            assertTrue(Files.walk(location).use { paths -> paths.anyMatch(Files::isRegularFile) })
        }

    @Test
    fun aDirectoryWithFilesThatCannotBeReadIsTheSameAfterItsImport() {
        val legacy = copyOfTheLegacyDirectory()
        Files.delete(legacy.resolve(Path.of("mockey_def_depot", "orders", "orders.xml")))
        Files.writeString(legacy.resolve(Path.of("mockey_def_depot", "billing", "billing.xml")), "no XML")
        Files.walk(legacy).use { paths -> paths.forEach { Files.setLastModifiedTime(it, LONG_AGO) } }
        val before = stateOf(legacy)

        withEngine { engine ->
            val report = LegacyDirectoryImporter(legacy).import().importInto(engine)

            assertEquals(2, report.issues.size)
        }

        assertEquals(before, stateOf(legacy))
    }

    private companion object {
        /** A rule set that is well formed. */
        const val WELL_FORMED = """{"parameters": [{"key": "id", "value_rule_type": "string_required", "value_rule_arg": "1"}]}"""

        /** The response bodies of two scenarios of the directory that Mockey 2.x wrote. */
        const val SILVER = """{"id": "1", "tier": "silver"}"""
        const val DIRECTORY_DOWN = "The customer directory is down."

        /** A time that is long before any test runs. */
        val LONG_AGO: FileTime = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"))
    }
}
