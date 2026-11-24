package io.github.dnedrow.mockey.core.inspection

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.request.MockRequest
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Checks `docs/inspectors.md` and `docs/rules.md` against the code. The sample inspector of the first document
 * is the one that is compiled with these tests, and it is found through the provider-configuration file that
 * the document shows. The rule sets, the faults, and the messages of the second document are those that the
 * code reads and gives.
 */
class InspectionDocumentsTest {
    private val inspectorsDocument = Path.of("../docs/inspectors.md")
    private val rulesDocument = Path.of("../docs/rules.md")
    private val compiledSample = Path.of("src/test/kotlin/com/example/inspectors/SampleRequestInspector.kt")
    private val providerFile = "META-INF/services/io.github.dnedrow.mockey.core.inspection.Inspector"

    /** A fenced block of a Markdown document: the language that its fence names, and its text. */
    private class Block(
        val language: String,
        val text: String,
    )

    /** Returns the fenced blocks of [document]. A block that is indented, as in a list, loses that indentation. */
    private fun blocks(document: Path): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = mutableListOf<String>()
        var language: String? = null
        var indentation = ""
        for (line in Files.readAllLines(document)) {
            val opened = language
            if (!line.trimStart().startsWith("```")) {
                if (opened != null) lines += line.removePrefix(indentation)
            } else if (opened == null) {
                language = line.trim().removePrefix("```")
                indentation = line.takeWhile { it == ' ' }
                lines.clear()
            } else {
                blocks += Block(opened, lines.joinToString("\n"))
                language = null
            }
        }
        return blocks
    }

    private fun wellFormed(source: String): RuleSet = assertIs<RuleSetReading.WellFormed>(readRuleSet(source), source).ruleSet

    @Test
    fun theSampleInspectorOfTheDocumentIsTheOneThatIsCompiledWithTheTests() {
        val documented = blocks(inspectorsDocument).single { it.language == "kotlin" }

        assertEquals(Files.readString(compiledSample).trimEnd(), documented.text)
    }

    @Test
    fun theSampleInspectorIsFoundThroughTheProviderFileOfTheDocument(
        @TempDir directory: Path,
    ) {
        val documented = blocks(inspectorsDocument).single { it.language == "text" }
        val file = directory.resolve(providerFile)
        Files.createDirectories(file.parent)
        Files.writeString(file, documented.text)
        val service = Service(id = ServiceId.generate(), name = "Customers", mockUrl = "customers")
        val url = "http://mockey.test:8080/service/customers"

        val inspectors = URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use { Inspectors.installed(it) }

        assertContains(Files.readString(inspectorsDocument), "`$providerFile`")
        assertEquals(listOf(InspectorInfo("com.mockey.plugin.SampleRequestInspector", isGlobal = true)), inspectors.list())
        assertEquals(emptyList(), inspectors.problems)
        assertContains(inspectors.messages(service, MockRequest("GET", url, "foobarKey=1")).single(), "'foobarKey' was in the request")
        assertEquals(emptyList(), inspectors.messages(service, MockRequest("GET", url, "other=1")))
    }

    @Test
    fun theRuleSetsOfTheRulesDocumentAreWellFormed() {
        val ruleSets = blocks(rulesDocument).filter { it.language == "json" }.map { wellFormed(it.text) }

        assertEquals(listOf(5, 2), ruleSets.map { it.rules.size })
    }

    @Test
    fun theFaultsOfTheRulesDocumentAreNamedAsTheDocumentShows() {
        val documented = blocks(rulesDocument).single { it.language == "text" && it.text.startsWith("Rule 1 of the group") }
        val faulty =
            """
            {
                "headers": [{"value_rule_arg": "gold", "value_rule_type": "string_required"}],
                "parameters": [
                    {"key": "ticker", "value_rule_arg": "goog", "value_rule_type": "string_required"},
                    {"key": "count", "value_rule_arg": "5", "value_rule_type": "number_required"}
                ],
                "body": [{"value_rule_arg": "([a-z", "value_rule_type": "regex_required"}]
            }
            """

        val reading = assertIs<RuleSetReading.NotWellFormed>(readRuleSet(faulty))

        assertEquals(documented.text.lines(), reading.problems)
    }

    @Test
    fun theMessagesOfTheRulesDocumentAreThoseThatItsRuleSetGives() {
        val documented = blocks(rulesDocument).single { it.language == "text" && it.text.startsWith("The request fails") }
        val rules = blocks(rulesDocument).first { it.language == "json" }.text
        val service = Service(id = ServiceId.generate(), name = "Customers", mockUrl = "customer/{id}", requestRules = rules)
        val request = MockRequest("GET", "http://mockey.test:8080/service/customer/123", "ticker=msft")
        val ruleSets = RuleSets(Definitions(services = listOf(service)))

        val messages = ruleSets.ruleMessages(service.copy(requestRulesEnabled = true), request)

        assertEquals(documented.text.lines(), messages)
        assertEquals(true, wellFormed(rules).evaluate(request).satisfied)
    }
}
