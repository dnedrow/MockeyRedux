package io.github.dnedrow.mockey.legacy.xml

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.ResponseMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How an export document of Mockey 2.x is imported: a text that holds the definitions inline. */
class LegacyDocumentImporterTest {
    private fun LegacyImportResult.kinds(): List<LegacyXmlIssueKind> = issues.map { it.kind }

    @Test
    fun aDocumentWithItsServicesAndScenariosInlineIsImported() {
        val result =
            LegacyDocumentImporter.import(
                """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <mockservice version="1.0" xml:lang="en-US">
                  <proxy_settings proxy_enabled="false" proxy_url=""/>
                  <service name="Customers" url="customers" service_response_type="1">
                    <scenario id="1" name="silver"><scenario_response><![CDATA[silver customer]]></scenario_response></scenario>
                    <scenario id="2" name="gold"><scenario_response><![CDATA[gold customer]]></scenario_response></scenario>
                    <scenario id="3" name="missing"><scenario_response><![CDATA[no such customer]]></scenario_response></scenario>
                  </service>
                </mockservice>
                """.trimIndent(),
            )

        assertEquals(emptyList(), result.issues)
        val service = result.definitions.services.single()
        assertEquals("Customers", service.name)
        assertEquals(listOf("silver", "gold", "missing"), service.scenarios.map { it.name })
        assertEquals(listOf("silver customer", "gold customer", "no such customer"), service.scenarios.map { it.responseBody })
    }

    @Test
    fun theSampleDocumentOfMockey2IsImported() {
        val result = LegacyDocumentImporter.import(LegacyFixtures.sample)

        assertEquals(emptyList(), result.issues)
        val service = result.definitions.services.single()
        assertEquals("Customer Directory", service.name)
        assertEquals("customer/{customerId}", service.mockUrl)
        assertEquals(ResponseMode.DYNAMIC, service.responseMode)
        assertNull(service.description)
        assertNull(service.inspectorName)
        assertNull(service.requestRules)
        assertEquals(emptyList(), service.realUrls)
        // The document lists the scenarios by their names. They are in the order of their numbers, 1 to 5.
        assertEquals(
            listOf(
                "123 Customer GET" to "GET",
                "Customer 456" to null,
                "123 Customer POST" to "POST",
                "888 Customer with POST and x-http-method-override as GET" to "POST",
                "888 Customer with a POST REST verb and x-http-method-override as PUT" to "POST",
            ),
            service.scenarios.map { it.name to it.methodRestriction },
        )
        assertEquals(service.scenarios.first().id, service.defaultScenarioId)
        assertNull(service.errorScenarioId)
        assertEquals(Definitions.EMPTY.settings, result.definitions.settings)
        assertEquals(listOf(200, 200, 200, 200, 200), service.scenarios.map { it.statusCode })
        assertEquals(emptyList(), service.scenarios.flatMap { it.headers })
        val first = service.scenarios.first()
        assertEquals(
            """
            {
                "url": [{
                    "desc": "curl http://localhost:8080/service/customer/123",
                    "value_rule_arg": "\\b123\\b",
                    "value_rule_type": "regex_required"
                }]
            }
            """.trimIndent(),
            assertIs<MatchCriterion.Rules>(first.matchCriterion).source,
        )
        assertEquals(
            """
            {
                "id": "123",
                "firstName": "Jordan",
                "lastName": "Smith",
                "type": "GET"
            }
            """.trimIndent(),
            first.responseBody,
        )
        service.scenarios.forEach { assertIs<MatchCriterion.Rules>(it.matchCriterion, it.name) }
    }

    @Test
    fun theExportOfMockey2IsImported() {
        val result = LegacyDocumentImporter.import(LegacyFixtures.export)

        assertEquals(emptyList(), result.issues)
        assertEquals(listOf("Customer Directory", "Orders", "Billing"), result.definitions.services.map { it.name })
        assertEquals(listOf(3, 2, 0), result.definitions.services.map { it.scenarios.size })
    }

    /** Returns an export document with one service, whose name is [name], and with the XML declaration [declaration]. */
    private fun documentNaming(
        name: String,
        declaration: String,
    ): String = """$declaration<mockservice><service name="$name" url="orders"><scenario id="1" name="ok"/></service></mockservice>"""

    @Test
    fun theBytesOfADocumentAreReadInTheCharsetThatTheDocumentDeclares() {
        val name = "Grüße aus Zürich"
        val latin1 = documentNaming(name, """<?xml version="1.0" encoding="ISO-8859-1"?>""").toByteArray(Charsets.ISO_8859_1)
        val utf8 = documentNaming(name, """<?xml version="1.0" encoding="UTF-8"?>""").toByteArray(Charsets.UTF_8)
        val undeclared = documentNaming(name, "").toByteArray(Charsets.UTF_8)
        val withByteOrderMark = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + undeclared
        val utf16 = documentNaming(name, """<?xml version="1.0" encoding="UTF-16"?>""").toByteArray(Charsets.UTF_16)

        val results = listOf(latin1, utf8, undeclared, withByteOrderMark, utf16).map { LegacyDocumentImporter.import(it) }

        assertEquals(List(5) { emptyList<LegacyXmlIssue>() }, results.map { it.issues })
        assertEquals(List(5) { listOf(name) }, results.map { result -> result.definitions.services.map { it.name } })
        val oneByteOfLatin1 = 0xFC.toByte()
        assertTrue(oneByteOfLatin1 in latin1 && oneByteOfLatin1 !in utf8, "The letter ü is one byte in ISO-8859-1 and two bytes in UTF-8")
    }

    @Test
    fun theBytesOfADocumentHoldWhatItsTextHolds() {
        val ofText = LegacyDocumentImporter.import(LegacyFixtures.export)
        val ofBytes = LegacyDocumentImporter.import(LegacyFixtures.export.toByteArray(Charsets.UTF_8))

        assertEquals(ofText.issues, ofBytes.issues)
        assertEquals(ofText.definitions.withPlacesAsIdentifiers(), ofBytes.definitions.withPlacesAsIdentifiers())
        assertEquals(3, ofBytes.definitions.services.size)
    }

    @Test
    fun bytesThatAreNoTextInTheCharsetOfTheDocumentAreReported() {
        val document = documentNaming("Grüße", """<?xml version="1.0" encoding="UTF-8"?>""").toByteArray(Charsets.ISO_8859_1)

        val result = LegacyDocumentImporter.import(document)

        val message = result.issues.single().message
        assertEquals(Definitions.EMPTY, result.definitions)
        assertEquals(listOf(LegacyXmlIssueKind.MALFORMED_XML), result.kinds())
        assertTrue(message.startsWith("The document is not well-formed XML in line 1: "), message)
    }

    @Test
    fun theBytesOfADocumentReadNoFileEither() {
        val document = """<mockservice><serviceref file="${LegacyFixtures.directory.resolve(
            "mockey_def_depot/orders/orders.xml",
        )}"/></mockservice>"""

        val result = LegacyDocumentImporter.import(document.toByteArray(Charsets.UTF_8))

        assertEquals(listOf(LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT), result.kinds())
        assertEquals(Definitions.EMPTY, result.definitions)
    }

    @Test
    fun aDocumentWithAnotherRootElementHoldsNoDefinitions() {
        val result = LegacyDocumentImporter.import("""<scenario id="1" name="alone"/>""")

        assertEquals(Definitions.EMPTY, result.definitions)
        assertEquals(listOf(LegacyXmlIssueKind.NOT_A_DEFINITIONS_DOCUMENT), result.kinds())
        assertEquals(
            "The document holds no definitions of Mockey 2.x: its root element is 'scenario', not 'mockservice'.",
            result.issues.single().message,
        )
    }

    @Test
    fun aTextThatIsNoXmlIsReported() {
        val result = LegacyDocumentImporter.import("""{"formatVersion": 1}""")

        assertEquals(Definitions.EMPTY, result.definitions)
        assertEquals(listOf(LegacyXmlIssueKind.MALFORMED_XML), result.kinds())
    }

    @Test
    fun aDocumentThatDeclaresAnExternalEntityIsNotImported() {
        val document =
            """
            <!DOCTYPE mockservice [<!ENTITY hosts SYSTEM "file:///etc/hosts">]>
            <mockservice><service name="&hosts;" url="orders"/></mockservice>
            """.trimIndent()

        val result = LegacyDocumentImporter.import(document)

        assertEquals(Definitions.EMPTY, result.definitions)
        assertEquals(listOf(LegacyXmlIssueKind.EXTERNAL_ENTITY), result.kinds())
        assertTrue("document type" in result.issues.single().message, result.issues.single().message)
    }

    @Test
    fun aDocumentThatIsNoFileReadsNoFile() {
        val scenarioFile = LegacyFixtures.directory.resolve("mockey_def_depot/orders/scenarios/orderlist.xml")
        val document =
            """
            <mockservice xmlns:xi="http://www.w3.org/2001/XInclude">
              <serviceref file="mockey_def_depot/orders/orders.xml"/>
              <serviceref file="${LegacyFixtures.directory.resolve("mockey_def_depot/orders/orders.xml")}"/>
              <service name="Inline" url="inline">
                <xi:include href="$scenarioFile" parse="xml"/>
                <scenario id="1" name="kept"/>
              </service>
            </mockservice>
            """.trimIndent()

        val result = LegacyDocumentImporter.import(document)

        val outside = LegacyXmlIssueKind.OUTSIDE_IMPORT_ROOT
        assertEquals(listOf(outside, outside, outside), result.kinds())
        assertTrue(result.issues.any { "'$scenarioFile'" in it.message }, result.issues.toString())
        assertTrue(result.issues.any { "mockey_def_depot/orders/orders.xml" in it.message }, result.issues.toString())
        val service = result.definitions.services.single()
        assertEquals("Inline", service.name)
        assertEquals(listOf("kept"), service.scenarios.map { it.name })
    }
}
