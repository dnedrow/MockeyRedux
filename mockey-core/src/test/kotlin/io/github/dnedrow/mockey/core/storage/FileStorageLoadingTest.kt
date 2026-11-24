package io.github.dnedrow.mockey.core.storage

import io.github.dnedrow.mockey.core.model.Definitions
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** What the file storage makes of a location that it cannot read as it is, and of what in a location is no definition. */
class FileStorageLoadingTest {
    @TempDir
    lateinit var directory: Path

    private val root: Path
        get() = directory.resolve("definitions")

    private val goldFile = "services/customers/scenarios/gold-customer.json"
    private val ordersFile = "services/orders/service.json"

    /** Returns why a location with the sample definitions and with [text] in the file at [path] cannot be loaded. */
    private fun problemWith(
        path: String,
        text: String,
    ): String {
        val location = directory.resolve("location-${Files.list(directory).use { it.count() }}")
        stored(location, Sample.definitions)
        write(location, path, text)
        val failure = assertFailsWith<StorageException> { loaded(location) }
        val start = "The definition file ${location.resolve(path)} cannot be read: "
        assertEquals(start, failure.message.orEmpty().take(start.length))
        return failure.message.orEmpty().removePrefix(start)
    }

    // Files that cannot be read

    @Test
    fun aFileThatIsNoJsonIsNamedWithWhatIsWrongWithIt() {
        val cutShort = problemWith(goldFile, """{"id": "scn-gold", "name": "gold""")
        val trailingComma = problemWith(ordersFile, """{"id": "svc-orders", "name": "Orders", "mockUrl": "orders",}""")
        val noObject = problemWith("mockey.json", """["formatVersion", 1]""")
        val empty = problemWith(ordersFile, "")

        assertEquals(
            "it is not valid JSON: Unexpected JSON token at offset 32: Expected quotation mark '\"', but had 'EOF' instead.",
            cutShort,
        )
        assertEquals(
            "it is not valid JSON: Unexpected JSON token at offset 58: Trailing comma before the end of JSON object.",
            trailingComma,
        )
        assertEquals("it is not a JSON object.", noObject)
        assertEquals("it is not valid JSON: Cannot read Json element because of unexpected end of the input.", empty)
    }

    @Test
    fun aFileThatHoldsNoDefinitionIsNamedWithWhatIsWrongWithIt() {
        val service = "io.github.dnedrow.mockey.core.model.Service"
        val problems =
            listOf(
                problemWith(ordersFile, """{"name": "Orders", "mockUrl": "orders"}"""),
                problemWith(ordersFile, """{"id": "svc-orders", "name": "Orders", "mockUrl": "orders", "nmae": "Orders"}"""),
                problemWith(ordersFile, """{"id": "svc-orders", "name": "Orders", "mockUrl": "orders", "delayMillis": "soon"}"""),
                problemWith(ordersFile, """{"id": " ", "name": "Orders", "mockUrl": "orders"}"""),
                problemWith(ordersFile, """{"id": "svc-orders", "name": "Orders", "mockUrl": "orders", "position": 1.5}"""),
                problemWith(goldFile, """{"id": "scn-gold", "name": "gold customer", "matchCriterion": {"type": "text", "text": " "}}"""),
            )

        val expected =
            listOf(
                "Field 'id' is required for type with serial name '$service', but it was missing",
                "Encountered an unknown key 'nmae' at path: $",
                "Failed to parse literal '\"soon\"' as a long value at path: $.delayMillis",
                "A service identifier must not be blank.",
                "its position is no whole number.",
                "A text criterion must not be blank.",
            )
        assertEquals(expected, problems)
    }

    @Test
    fun aFileThatHoldsWhatBelongsInAnotherFileIsNamed() {
        val servicesInMain = problemWith("mockey.json", """{"formatVersion": 1, "services": []}""")
        val scenariosInService = problemWith(ordersFile, """{"id": "svc-orders", "name": "Orders", "mockUrl": "orders", "scenarios": []}""")
        val twoBodies = problemWith(goldFile, """{"id": "scn-gold", "name": "gold customer", "responseBody": "a second body"}""")

        assertEquals("it holds services, and every service belongs in a directory of its own.", servicesInMain)
        assertEquals("it holds scenarios, and every scenario belongs in a file of its own.", scenariosInService)
        assertEquals("it holds a response body, and the scenario has a body file as well.", twoBodies)
    }

    @Test
    fun aFileThatIsNoTextInUtf8IsNamed() {
        stored(root, Sample.definitions)
        val notUtf8 = byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte())
        Files.write(root.resolve("services/customers/scenarios/gold-customer.body"), notUtf8)

        val failure = assertFailsWith<StorageException> { loaded(root) }

        val bodyFile = root.resolve("services/customers/scenarios/gold-customer.body")
        assertEquals("The definition file $bodyFile cannot be read: it is not text in UTF-8.", failure.message)
    }

    @Test
    fun aDirectoryInThePlaceOfAFileIsNamed() {
        stored(root, Sample.definitions)
        Files.delete(root.resolve("mockey.json"))
        Files.createDirectory(root.resolve("mockey.json"))

        val failure = assertFailsWith<StorageException> { loaded(root) }

        assertEquals(
            "The definition file ${root.resolve("mockey.json")} cannot be read: ",
            failure.message.orEmpty().substringBefore("Is a directory"),
        )
    }

    @Test
    fun twoFilesWithTheSameIdentifierAreNamed() {
        stored(root, Sample.definitions)
        write(root, "services/orders-copy/service.json", textOf(root, ordersFile))
        val twoServices = assertFailsWith<StorageException> { loaded(root) }
        Files.delete(root.resolve("services/orders-copy/service.json"))
        write(root, "services/customers/scenarios/gold-copy.json", textOf(root, goldFile))
        val twoScenarios = assertFailsWith<StorageException> { loaded(root) }

        val services = "${root.resolve(ordersFile)} and ${root.resolve("services/orders-copy/service.json")}"
        val scenarios = "${root.resolve("services/customers/scenarios/gold-copy.json")} and ${root.resolve(goldFile)}"
        assertEquals("The definition files $services hold the same service identifier svc-orders.", twoServices.message)
        assertEquals("The definition files $scenarios hold the same scenario identifier scn-gold.", twoScenarios.message)
    }

    // The format version

    @Test
    fun definitionsOfANewerFormatAreNotRead() {
        val newer = problemWith("mockey.json", """{"formatVersion": 2}""")
        val muchNewer = problemWith("mockey.json", """{"formatVersion": 9223372036854775807, "anything": "else"}""")

        assertEquals("it has the format version 2, and this version of Mockey reads the format version 1 only.", newer)
        assertEquals(
            "it has the format version 9223372036854775807, and this version of Mockey reads the format version 1 only.",
            muchNewer,
        )
    }

    @Test
    fun aFormatVersionIsAWholeNumberAboveZero() {
        val versions = listOf("0", "-1", "1.5", "\"1\"", "true", "[1]")

        val problems = versions.map { problemWith("mockey.json", """{"formatVersion": $it}""") }

        assertEquals(List(versions.size) { "its format version is no whole number above 0." }, problems)
    }

    @Test
    fun aMainFileWithoutAFormatVersionHoldsDefinitionsOfTheFirstOne() {
        stored(root, Sample.definitions)
        write(root, "mockey.json", textOf(root, "mockey.json").replace("    \"formatVersion\": 1,\n", ""))

        assertEquals(Sample.definitions, loaded(root))
    }

    // What is no definition

    @Test
    fun whatIsNoDefinitionFileIsNotRead() {
        stored(root, Sample.definitions)
        val others =
            mapOf(
                "README.md" to "Not a definition.",
                "mockey.json.orig" to "{ not JSON",
                "services/README.md" to "Not a service.",
                "services/empty/notes.txt" to "A directory without a service file.",
                "services/without-service-file/scenarios/lost.json" to "{ not JSON",
                "services/.hidden/service.json" to "{ not JSON",
                "services/customers/service.json.orig" to "{ not JSON",
                "services/customers/scenarios/notes.txt" to "Not a scenario.",
                "services/customers/scenarios/.hidden.json" to "{ not JSON",
                "services/customers/scenarios/without-scenario-file.body" to "A body without a scenario.",
                "services/customers/scenarios/directory.json/file.txt" to "A directory with the name of a scenario file.",
            )
        others.forEach { (path, text) -> write(root, path, text) }

        assertEquals(Sample.definitions, loaded(root))
    }

    @Test
    fun aFileThatBeginsWithAByteOrderMarkIsRead() {
        stored(root, Sample.definitions)
        listOf("mockey.json", ordersFile, goldFile).forEach { path -> write(root, path, "\uFEFF" + textOf(root, path)) }

        assertEquals(Sample.definitions, loaded(root))
    }

    @Test
    fun aLocationWithoutAMainFileHoldsItsServices() {
        stored(root, Sample.definitions)
        Files.delete(root.resolve("mockey.json"))

        assertEquals(Definitions(services = Sample.definitions.services), loaded(root))
    }

    // After a failure

    @Test
    fun aStorageThatCouldNotLoadSavesNothingUntilTheLocationCanBeRead() =
        runTest {
            stored(root, Sample.definitions)
            val whole = textOf(root, goldFile)
            write(root, goldFile, "{ not JSON")
            val broken = treeOf(root)
            val storage = FileDefinitionStorage(root)

            val onLoad = assertFailsWith<StorageException> { storage.load() }
            val onSave = assertFailsWith<StorageException> { storage.save(Definitions.EMPTY) }
            val untouched = treeOf(root)
            write(root, goldFile, whole)
            storage.save(Definitions(services = listOf(Sample.orders)))

            assertEquals(onLoad.message, onSave.message)
            assertEquals(broken, untouched)
            assertEquals(Definitions(services = listOf(Sample.orders)), loaded(root))
        }
}
