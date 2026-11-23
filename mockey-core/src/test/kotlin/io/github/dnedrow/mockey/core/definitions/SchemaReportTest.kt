package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaReportTest {
    private val requiresId = """{"type": "object", "required": ["id"], "properties": {"id": {"type": "integer"}}}"""

    private fun scenario(
        name: String,
        body: String,
    ) = Scenario(id = ScenarioId.generate(), name = name, responseBody = body)

    private fun service(
        schema: String?,
        vararg scenarios: Scenario,
    ) = Service(
        id = ServiceId.generate(),
        name = "Customers",
        mockUrl = "customer/{id}",
        responseSchema = schema,
        scenarios = scenarios.toList(),
    )

    private fun report(schema: String): SchemaReport? = service(schema, scenario("gold", "{}")).responseSchemaReport()

    private fun checked(
        schema: String,
        vararg bodies: String,
    ): List<ScenarioConformance> {
        val scenarios = bodies.mapIndexed { index, body -> scenario("scenario $index", body) }
        return assertIs<SchemaReport.Checked>(service(schema, *scenarios.toTypedArray()).responseSchemaReport()).scenarios
    }

    private fun problems(schema: String): List<String> = assertIs<SchemaReport.UnusableSchema>(report(schema)).problems

    @Test
    fun aServiceWithoutAResponseSchemaHasNoReport() {
        assertNull(service(null, scenario("gold", "{}")).responseSchemaReport())
    }

    @Test
    fun aBodyThatConformsToTheSchemaIsReportedAsConforming() {
        val gold = scenario("gold", """{"id": 7, "name": "Jane"}""")

        val report = assertIs<SchemaReport.Checked>(service(requiresId, gold).responseSchemaReport())

        assertEquals(listOf(ScenarioConformance(gold.id, "gold", emptyList())), report.scenarios)
        assertTrue(report.scenarios.single().conforms)
    }

    @Test
    fun aBodyThatDoesNotConformIsReportedWithWhatIsMissing() {
        val nameless = scenario("no id", """{"name": "Jane"}""")

        val report = assertIs<SchemaReport.Checked>(service(requiresId, nameless).responseSchemaReport())

        val conformance = report.scenarios.single()
        assertEquals(nameless.id, conformance.scenarioId)
        assertEquals("no id", conformance.scenarioName)
        assertFalse(conformance.conforms)
        assertContains(conformance.violations.single(), "[id]")
    }

    @Test
    fun everyScenarioOfTheServiceGetsAVerdict() {
        val scenarios = checked(requiresId, """{"id": 1}""", """{"id": "one"}""", """{}""", """{"id": 2}""")

        assertEquals(listOf(true, false, false, true), scenarios.map { it.conforms })
        assertEquals(listOf("scenario 0", "scenario 1", "scenario 2", "scenario 3"), scenarios.map { it.scenarioName })
    }

    @Test
    fun aViolationSaysWhereInTheBodyItIs() {
        val schema = """{"properties": {"customer": {"properties": {"age": {"type": "integer", "minimum": 0}}}}}"""

        val violations = checked(schema, """{"customer": {"age": -1}}""").single().violations

        assertEquals(1, violations.size)
        assertContains(violations.single(), "At /customer/age:")
    }

    @Test
    fun everyViolationOfABodyIsListed() {
        val schema = """{"required": ["id"], "properties": {"age": {"minimum": 0}, "name": {"type": "string"}}}"""

        val violations = checked(schema, """{"age": -1, "name": 5}""").single().violations

        assertEquals(3, violations.size)
    }

    @Test
    fun aBodyThatIsNotJsonDoesNotConform() {
        val scenarios =
            checked(requiresId, "<customer><id>7</id></customer>", "", "plain text", """{"id": 7} and more""", """{"id": seven}""")

        assertEquals(List(5) { listOf("The response body cannot be read as JSON.") }, scenarios.map { it.violations })
    }

    @Test
    fun aBodyThatIsNestedMoreDeeplyThanTheParserAcceptsDoesNotConform() {
        val deep = "[".repeat(100_000) + "]".repeat(100_000)

        assertEquals(listOf("The response body cannot be read as JSON."), checked("{}", deep).single().violations)
    }

    @Test
    fun aBodyWithANumberOutsideTheRangeOfADoubleIsNotChecked() {
        val scenarios =
            checked("""{"enum": [1, 1e308]}""", "1e308", "1e309", "1e2147483647", "1e99999999999", "1e-400", """[{"n": 1e999}]""")

        assertEquals(emptyList(), scenarios.first().violations)
        scenarios.drop(1).forEach { assertContains(it.violations.single(), "outside the range of a double") }
    }

    @Test
    fun theFormatKeywordIsChecked() {
        val draft4 = """{"properties": {"mail": {"format": "email"}, "at": {"format": "date-time"}}}"""
        val draft2020 = """{"${'$'}schema": "https://json-schema.org/draft/2020-12/schema", "format": "ipv4"}"""

        val scenarios =
            checked(draft4, """{"mail": "jane@example.com", "at": "2026-11-23T10:00:00Z"}""", """{"mail": "jane", "at": "yesterday"}""")

        assertEquals(listOf(0, 2), scenarios.map { it.violations.size })
        assertEquals(listOf(true, false), checked(draft2020, "\"10.0.0.1\"", "\"999.1.1.1\"").map { it.conforms })
    }

    @Test
    fun aFormatThatIsNotKnownIsNotChecked() {
        assertTrue(checked("""{"format": "made-up"}""", "\"anything\"").single().conforms)
    }

    @Test
    fun aSchemaInADialectThatIsNotSupportedGetsNoVerdict() {
        val draft3 = """{"${'$'}schema": "http://json-schema.org/draft-03/schema", "properties": {"id": {"required": true}}}"""
        val draft3WithFragment = """{"${'$'}schema": "http://json-schema.org/draft-03/schema#", "type": "object"}"""
        val unknown = """{"${'$'}schema": "https://example.com/my-dialect", "type": "object"}"""

        val reports = listOf(draft3, draft3WithFragment, unknown).map { report(it) }

        assertEquals(
            listOf(
                SchemaReport.UnsupportedDialect("http://json-schema.org/draft-03/schema"),
                SchemaReport.UnsupportedDialect("http://json-schema.org/draft-03/schema"),
                SchemaReport.UnsupportedDialect("https://example.com/my-dialect"),
            ),
            reports,
        )
    }

    @Test
    fun aDeclarationThatDoesNotNameASupportedDialectGetsNoVerdict() {
        val partOfAMetaSchema = """{"${'$'}schema": "http://json-schema.org/draft-04/schema#/properties/not", "type": "object"}"""
        val noUri = """{"${'$'}schema": "%% bad", "type": "object"}"""
        val noText = """{"${'$'}schema": 4, "type": "object"}"""

        val reports = listOf(partOfAMetaSchema, noUri, noText).map { report(it) }

        assertEquals(
            listOf(
                SchemaReport.UnsupportedDialect("http://json-schema.org/draft-04/schema#/properties/not"),
                SchemaReport.UnsupportedDialect("%% bad"),
                SchemaReport.UnsupportedDialect("4"),
            ),
            reports,
        )
    }

    @Test
    fun aSchemaInsideTheSchemaInADialectThatIsNotSupportedGetsNoVerdict() {
        val old = """{"${'$'}id": "https://example.com/old", "${'$'}schema": "http://json-schema.org/draft-03/schema#"}"""
        val schema = """{"${'$'}schema": "https://json-schema.org/draft/2020-12/schema", "${'$'}defs": {"old": $old}}"""

        assertEquals(SchemaReport.UnsupportedDialect("http://json-schema.org/draft-03/schema"), report(schema))
    }

    @Test
    fun aSchemaThatDeclaresNoDialectIsReadAsDraft04() {
        val scenarios = checked("""{"minimum": 5, "exclusiveMinimum": true, "const": 6}""", "5", "7")

        assertEquals(listOf(false, true), scenarios.map { it.conforms })
    }

    @Test
    fun aSchemaIsReadInTheDialectThatItDeclares() {
        val draft4 = """{"${'$'}schema": "http://json-schema.org/draft-04/schema#", "minimum": 5, "exclusiveMinimum": true}"""
        val draft6 = """{"${'$'}schema": "http://json-schema.org/draft-06/schema#", "exclusiveMinimum": 5}"""
        val draft7 = """{"${'$'}schema": "http://json-schema.org/draft-07/schema#", "if": {"minimum": 5}, "then": {"multipleOf": 5}}"""
        val draft2019 = """{"${'$'}schema": "https://json-schema.org/draft/2019-09/schema", "dependentRequired": {"a": ["b"]}}"""
        val draft2020 = """{"${'$'}schema": "https://json-schema.org/draft/2020-12/schema", "prefixItems": [{"type": "string"}]}"""

        assertEquals(listOf(false, true), checked(draft4, "5", "6").map { it.conforms })
        assertEquals(listOf(false, true), checked(draft6, "5", "6").map { it.conforms })
        assertEquals(listOf(false, true), checked(draft7, "6", "10").map { it.conforms })
        assertEquals(listOf(false, true), checked(draft2019, """{"a": 1}""", """{"a": 1, "b": 2}""").map { it.conforms })
        assertEquals(listOf(false, true), checked(draft2020, "[1]", """["one"]""").map { it.conforms })
    }

    @Test
    fun aDialectIsRecognizedWithAndWithoutTheNumberSignAtItsEnd() {
        val draft4 = """{"${'$'}schema": "http://json-schema.org/draft-04/schema", "minimum": 5, "exclusiveMinimum": true}"""
        val draft2020 = """{"${'$'}schema": "https://json-schema.org/draft/2020-12/schema#", "prefixItems": [{"type": "string"}]}"""

        assertEquals(listOf(false, true), checked(draft4, "5", "6").map { it.conforms })
        assertEquals(listOf(false, true), checked(draft2020, "[1]", """["one"]""").map { it.conforms })
    }

    @Test
    fun aSchemaThatIsNotJsonGetsNoVerdict() {
        val reports = listOf("""{"type": """, "not a schema at all").map { report(it) }

        assertEquals(List(2) { SchemaReport.UnusableSchema(listOf("The schema cannot be read as JSON.")) }, reports)
    }

    @Test
    fun aSchemaThatIsNotValidInItsDialectSaysWhereItIsWrong() {
        val inTheStyleOfDraft3 = """{"properties": {"id": {"type": "integer", "required": true}}}"""

        assertContains(problems(inTheStyleOfDraft3).single(), "At /properties/id/required:")
        assertTrue(problems("""{"type": 5}""").all { it.startsWith("At /type:") })
    }

    @Test
    fun aPatternThatIsNoRegularExpressionMakesTheSchemaUnusable() {
        val problem = problems("""{"type": "string", "pattern": "("}""").single()

        assertContains(problem, "At /pattern:")
        assertFalse("\n" in problem)
    }

    @Test
    fun aSchemaWhoseIdentifierIsNoUriGetsNoVerdict() {
        assertContains(problems("""{"id": "not a uri %%", "type": "object"}""").single(), "not a uri %%")
    }

    @Test
    fun aSchemaWithANumberOutsideTheRangeOfADoubleGetsNoVerdict() {
        assertContains(problems("""{"properties": {"n": {"maximum": 1e99999999999}}}""").single(), "outside the range of a double")
        assertContains(problems("""{"enum": [1e309]}""").single(), "outside the range of a double")
    }

    @Test
    fun aSchemaThatRefersToItselfInALoopGetsNoVerdict() {
        val itself = """{"${'$'}ref": "#"}"""
        val toA = """{"${'$'}ref": "#/definitions/a"}"""
        val toB = """{"${'$'}ref": "#/definitions/b"}"""
        val oneAnother = """{"definitions": {"a": $toB, "b": $toA}, "allOf": [$toA]}"""

        assertContains(problems(itself).single(), "refers to itself")
        assertContains(problems(oneAnother).single(), "refers to itself")
    }

    @Test
    fun aSchemaThatRefersToItselfForThePartsOfABodyIsApplied() {
        val tree = """{"required": ["name"], "properties": {"children": {"items": {"${'$'}ref": "#"}}}}"""

        val scenarios = checked(tree, """{"name": "root", "children": [{"name": "leaf"}]}""", """{"name": "root", "children": [{}]}""")

        assertEquals(listOf(true, false), scenarios.map { it.conforms })
        assertContains(scenarios.last().violations.single(), "At /children/0:")
    }

    @Test
    fun aReferenceToASchemaElsewhereIsNotFollowed() {
        val scenarios = checked("""{"${'$'}ref": "http://127.0.0.1:1/customer.json"}""", "{}")

        assertFalse(scenarios.single().conforms)
        assertContains(scenarios.single().violations.single(), "http://127.0.0.1:1/customer.json")
    }

    @Test
    fun aReportIsTheSameEachTimeItIsMade() {
        val customers = service("""{"properties": {"n": {"${'$'}ref": "#/definitions/missing"}}}""", scenario("gold", """{"n": 1}"""))

        assertEquals(customers.responseSchemaReport(), customers.responseSchemaReport())
        assertFalse(assertIs<SchemaReport.Checked>(customers.responseSchemaReport()).scenarios.single().conforms)
    }

    @Test
    fun aScenarioThatDoesNotConformIsStillSaved() {
        val customers = service(requiresId).copy(responseSchemaEnabled = true)
        val definitions = Definitions(services = listOf(customers))

        val created =
            definitions.changed(
                ScenarioChanges.create(customers.id) { it.copy(name = "no id", responseBody = """{"name": "Jane"}""") },
            )

        assertEquals("""{"name": "Jane"}""", created.value.responseBody)
        assertEquals(
            listOf(created.value),
            created.definitions.services
                .single()
                .scenarios,
        )
    }
}
