package io.github.dnedrow.mockey.legacy.xml

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.Tags
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a definitions directory of Mockey 2.x is imported: its main file, the service files that the main file
 * refers to, the scenario files that a service file includes, and the text files of the response bodies.
 */
class LegacyDirectoryImporterTest {
    @TempDir
    lateinit var directory: Path

    /** Writes [content] to the file [path] of the directory that a test imports. */
    private fun file(
        path: String,
        content: String,
    ): Path {
        val file = directory.resolve(path)
        Files.createDirectories(file.parent)
        return Files.writeString(file, content)
    }

    /**
     * Writes the files of the service [name] as Mockey 2.x writes them: its service file, and for each of its
     * [scenarios] a scenario file and the text file of its response body.
     */
    private fun serviceFiles(
        name: String,
        vararg scenarios: Pair<String, String>,
    ) {
        val depot = "mockey_def_depot/$name"
        val inclusions = scenarios.joinToString("\n") { (scenario, _) -> include("$depot/scenarios/$scenario.xml", "xml") }
        file("$depot/$name.xml", """<mockservice version="1.0"><service name="$name" url="$name">$inclusions</service></mockservice>""")
        scenarios.forEachIndexed { index, (scenario, body) ->
            val response = "<scenario_response>\n${include("$scenario.txt", "text")}\n</scenario_response>"
            file("$depot/scenarios/$scenario.xml", """<scenario id="${index + 1}" name="$scenario" version="1.0">$response</scenario>""")
            file("$depot/scenarios/$scenario.txt", body)
        }
    }

    private fun include(
        href: String,
        parse: String,
    ) = """<xi:include href="$href" parse="$parse" $XINCLUDE/>"""

    private fun mainFile(vararg content: String): Path {
        val document = "<mockservice>${content.joinToString("")}</mockservice>"
        return file(LegacyDirectoryImporter.MAIN_FILE, document)
    }

    private fun reference(service: String) = """<serviceref file="mockey_def_depot/$service/$service.xml"/>"""

    private fun LegacyImportResult.kinds(): List<LegacyXmlIssueKind> = issues.map { it.kind }

    private fun Service.bodies(): Map<String, String> = scenarios.associate { it.name to it.responseBody }

    // The directory that Mockey 2.x wrote

    @Test
    fun aDirectoryThatMockey2WroteIsImported() {
        val result = LegacyDirectoryImporter(LegacyFixtures.directory).import()

        assertEquals(emptyList(), result.issues)
        val (customers, orders, billing) = result.definitions.services
        assertEquals("Customer Directory", customers.name)
        assertEquals("Customers by their identifier.", customers.description)
        assertEquals("customer/{customerId}", customers.mockUrl)
        assertEquals(Tags.of("directory", "team-a"), customers.tags)
        assertEquals(500, customers.delayMillis)
        assertEquals(ResponseMode.DYNAMIC, customers.responseMode)
        assertEquals(listOf("qa1", "qa2", "qa3").map { "http://$it.example.com/customers/{customerId}" }, customers.realUrls)
        assertEquals(1, customers.defaultRealUrlIndex)
        assertEquals("com.example.inspectors.SessionInspector", customers.inspectorName)
        assertTrue(customers.requestRulesEnabled)
        assertTrue(customers.requestRules.orEmpty().startsWith("{\n    \"headers\": [{"), customers.requestRules)
        assertEquals("""{"type": "object", "required": ["id"]}""", customers.responseSchema)
        assertEquals(false, customers.responseSchemaEnabled)
        assertEquals(setOf("Directory down", "gold", "silver"), customers.scenarios.map { it.name }.toSet())

        val gold = customers.scenarios.single { it.name == "gold" }
        assertEquals("""{"id": "123", "tier": "gold"}""", gold.responseBody)
        assertEquals(201, gold.statusCode)
        assertEquals("POST", gold.methodRestriction)
        assertEquals(250, gold.delayMillis)
        assertEquals(Tags.of("premium"), gold.tags)
        assertEquals(listOf(Header("Content-Type", "application/json; charset=utf-8"), Header("X-Rate", "10")), gold.headers)
        assertTrue(gold.matchCriterion is MatchCriterion.Rules, gold.matchCriterion.toString())
        assertEquals(MatchCriterion.Text("silver"), customers.scenarios.single { it.name == "silver" }.matchCriterion)
        assertEquals(MatchCriterion.None, customers.scenarios.single { it.name == "Directory down" }.matchCriterion)

        assertEquals("Orders", orders.name)
        assertEquals(ResponseMode.STATIC, orders.responseMode)
        assertEquals(50, orders.delayMillis)
        assertEquals(setOf("order list", "no orders"), orders.scenarios.map { it.name }.toSet())

        assertEquals("Billing", billing.name)
        assertEquals("http://billing.example.com/api", billing.mockUrl)
        assertEquals(listOf("http://billing.example.com/api"), billing.realUrls)
        assertEquals(emptyList(), billing.scenarios)
    }

    @Test
    fun aResponseBodyIsTheTextOfItsFile() {
        val (customers, orders) = LegacyDirectoryImporter(LegacyFixtures.directory).import().definitions.services

        assertEquals(
            mapOf(
                "Directory down" to "The customer directory is down.",
                "gold" to """{"id": "123", "tier": "gold"}""",
                "silver" to """{"id": "1", "tier": "silver"}""",
            ),
            customers.bodies(),
        )
        assertEquals(mapOf("order list" to ORDER_LIST, "no orders" to ""), orders.bodies())
    }

    @Test
    fun aDirectoryAndItsExportHoldTheSameDefinitions() {
        val ofDirectory = LegacyDirectoryImporter(LegacyFixtures.directory).import()
        val ofExport = LegacyDocumentImporter.import(LegacyFixtures.export)

        assertEquals(emptyList(), ofDirectory.issues + ofExport.issues)
        assertEquals(ofExport.definitions.withPlacesAsIdentifiers(), ofDirectory.definitions.withPlacesAsIdentifiers())
    }

    // The layout of a directory

    @Test
    fun importsReferencedServiceAndResponseFile() {
        mainFile(reference("orders"))
        serviceFiles("orders", "created" to """{"created":true}""", "rejected" to "<rejected/>\n")

        val result = LegacyDirectoryImporter(directory).import()

        assertEquals(emptyList(), result.issues)
        val service = result.definitions.services.single()
        assertEquals("orders", service.name)
        assertEquals(mapOf("created" to """{"created":true}""", "rejected" to "<rejected/>"), service.bodies())
    }

    @Test
    fun theServicesOfServiceFilesComeBeforeTheServicesOfTheMainFile() {
        mainFile("""<service name="inline" url="inline"/>""", reference("first"), reference("second"))
        serviceFiles("first")
        serviceFiles("second")

        val result = LegacyDirectoryImporter(directory).import()

        assertEquals(emptyList(), result.issues)
        assertEquals(listOf("first", "second", "inline"), result.definitions.services.map { it.name })
    }

    @Test
    fun anotherMainFileOfTheDirectoryCanBeNamed() {
        file("exports/suite-a.xml", "<mockservice>${reference("orders")}</mockservice>")
        serviceFiles("orders", "created" to "created")

        val byPath = LegacyDirectoryImporter(directory).import(directory.resolve("exports/suite-a.xml"))
        val byRelativePath = LegacyDirectoryImporter(directory).import(Path.of("exports", "suite-a.xml"))

        assertEquals(emptyList(), byPath.issues + byRelativePath.issues)
        assertEquals(listOf("orders"), byPath.definitions.services.map { it.name })
        assertEquals(listOf(mapOf("created" to "created")), byRelativePath.definitions.services.map { it.bodies() })
    }

    @Test
    fun aDirectoryWithoutAMainFileHoldsNoDefinitions() {
        serviceFiles("orders", "created" to "created")

        val result = LegacyDirectoryImporter(directory).import()

        assertEquals(Definitions.EMPTY, result.definitions)
        assertEquals(listOf(LegacyXmlIssueKind.UNREADABLE_FILE), result.kinds())
        assertEquals("The file mock_service_definitions.xml does not exist.", result.issues.single().message)
    }

    @Test
    fun aMainFileOutsideTheDirectoryIsNotRead() {
        val imported = Files.createDirectory(directory.resolve("definitions"))
        val outside = file("outside.xml", """<mockservice><service name="outside" url="outside"/></mockservice>""")

        val result = LegacyDirectoryImporter(imported).import(outside)

        assertEquals(Definitions.EMPTY, result.definitions)
        assertEquals(listOf(LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT), result.kinds())
    }

    @Test
    fun aFileOutsideTheDirectoryIsNotRead() {
        val imported = Files.createDirectory(directory.resolve("definitions"))
        file("outside/outside.xml", """<mockservice><service name="outside" url="outside"/></mockservice>""")
        file("outside/scenario.xml", """<scenario id="1" name="outside"/>""")
        file(
            "definitions/mock_service_definitions.xml",
            """
            <mockservice $XINCLUDE>
              <serviceref file="../outside/outside.xml"/>
              <serviceref file="${directory.resolve("outside/outside.xml")}"/>
              <service name="inside" url="inside"><xi:include href="../outside/scenario.xml"/></service>
            </mockservice>
            """.trimIndent(),
        )

        val result = LegacyDirectoryImporter(imported).import()

        val outside = LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT
        assertEquals(listOf(outside, outside, outside), result.kinds())
        assertTrue(result.issues.any { "'../outside/scenario.xml'" in it.message }, result.issues.toString())
        assertEquals(listOf("inside"), result.definitions.services.map { it.name })
        assertEquals(emptyList(), result.definitions.services.flatMap { it.scenarios })
    }

    private companion object {
        const val XINCLUDE = """xmlns:xi="http://www.w3.org/2001/XInclude""""

        /** The response body of the scenario `order list` of the directory that Mockey 2.x wrote. */
        const val ORDER_LIST =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<orders>\n" +
                "\t<order id=\"1\" note=\"caf&#233; &amp; cr&#232;me\">Gr\u00fc\u00dfe \u2713</order>\n" +
                "\t<order id=\"2\"><![CDATA[1 < 2]]></order>\n</orders>"
    }
}
