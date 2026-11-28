package io.github.dnedrow.mockey.core.storage

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.ProxyCredentials
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the file storage writes for definitions, and that it reads back what it wrote. */
class FileDefinitionStorageTest {
    @TempDir
    lateinit var directory: Path

    /** The definitions location, which does not exist before the first save. */
    private val root: Path
        get() = directory.resolve("definitions")

    private fun scenario(
        name: String,
        body: String = "",
    ) = Scenario(id = ScenarioId.generate(), name = name, responseBody = body)

    private fun service(
        name: String,
        vararg scenarios: Scenario,
    ) = Service(id = ServiceId.generate(), name = name, mockUrl = name, scenarios = scenarios.toList())

    // The layout

    @Test
    fun everyDefinitionHasFilesOfItsOwn() {
        stored(root, Sample.definitions)

        assertEquals(Sample.files, filesOf(root))
    }

    @Test
    fun theMainFileHoldsTheFormatVersionThePlansTheTwistConfigurationsAndTheSettings() {
        stored(root, Sample.definitions)

        val expected =
            """
            {
              "formatVersion": 1,
              "plans": [
                {
                  "id": "plan-gold",
                  "name": "Gold path",
                  "description": "Everything gold",
                  "tags": [
                    "demo"
                  ],
                  "items": [
                    {
                      "serviceName": "Customers",
                      "scenarioName": "gold customer",
                      "responseMode": "static",
                      "delayMillis": 250
                    },
                    {
                      "serviceName": "Orders"
                    }
                  ]
                }
              ],
              "twists": [
                {
                  "id": "twist-qa2",
                  "name": "to-qa2",
                  "pairs": [
                    {
                      "origination": "qa1",
                      "destination": "qa2"
                    }
                  ]
                }
              ],
              "settings": {
                "enabledTwistId": "twist-qa2",
                "outboundProxy": {
                  "url": "http://proxy.example.com:3128",
                  "enabled": true
                },
                "universalErrorScenario": {
                  "serviceId": "svc-customers",
                  "scenarioId": "scn-error"
                },
                "defaultPlanId": "plan-gold"
              }
            }

            """.trimIndent()
        assertEquals(expected, textOf(root, "mockey.json"))
    }

    @Test
    fun aServiceFileHoldsTheServiceWithoutItsScenarios() {
        stored(root, Sample.definitions)

        val customers =
            """
            {
              "id": "svc-customers",
              "name": "Customers",
              "mockUrl": "customer/{id}",
              "description": "Customer directory",
              "realUrls": [
                "http://qa1.example.com/customers/{id}",
                "http://qa2.example.com/customers/{id}"
              ],
              "defaultRealUrlIndex": 1,
              "responseMode": "dynamic",
              "delayMillis": 100,
              "tags": [
                "crm"
              ],
              "defaultScenarioId": "scn-gold",
              "errorScenarioId": "scn-error",
              "requestRules": "{\"headers\":[]}",
              "requestRulesEnabled": true,
              "inspectorName": "session-check",
              "responseSchema": "{\"type\":\"object\"}",
              "responseSchemaEnabled": true,
              "position": 0
            }

            """.trimIndent()
        val orders =
            """
            {
              "id": "svc-orders",
              "name": "Orders",
              "mockUrl": "http://api.example.com/orders",
              "position": 1
            }

            """.trimIndent()
        assertEquals(customers, textOf(root, "services/customers/service.json"))
        assertEquals(orders, textOf(root, "services/orders/service.json"))
    }

    @Test
    fun aScenarioFileHoldsTheScenarioAndABodyFileHoldsItsResponseBody() {
        stored(root, Sample.definitions)

        val gold =
            """
            {
              "id": "scn-gold",
              "name": "gold customer",
              "statusCode": 201,
              "headers": [
                {
                  "name": "Set-Cookie",
                  "value": "a=1"
                },
                {
                  "name": "Content-Type",
                  "value": "application/json"
                },
                {
                  "name": "Set-Cookie",
                  "value": "b=2"
                }
              ],
              "matchCriterion": {
                "type": "text",
                "text": "ABC123"
              },
              "methodRestriction": "POST",
              "delayMillis": 500,
              "tags": [
                "regression",
                "smoke"
              ],
              "position": 0
            }

            """.trimIndent()
        assertEquals(gold, textOf(root, "services/customers/scenarios/gold-customer.json"))
        assertEquals("""{"tier":"gold"}""", textOf(root, "services/customers/scenarios/gold-customer.body"))
        assertEquals("Something went wrong.\n", textOf(root, "services/customers/scenarios/error.body"))
    }

    @Test
    fun aScenarioWithoutAResponseBodyHasNoBodyFile() {
        stored(root, Sample.definitions)

        val byRules =
            """
            {
              "id": "scn-rules",
              "name": "By rules",
              "matchCriterion": {
                "type": "rules",
                "source": "{\"url\":[]}"
              },
              "position": 1
            }

            """.trimIndent()
        assertEquals(byRules, textOf(root, "services/customers/scenarios/by-rules.json"))
        assertFalse(isFile(root, "services/customers/scenarios/by-rules.body"))
    }

    // Reading back

    @Test
    fun definitionsAreReadBackAsTheyWereStored() {
        stored(root, Sample.definitions)

        assertEquals(Sample.definitions, loaded(root))
    }

    @Test
    fun aLocationThatDoesNotExistHoldsNoDefinitionsAndIsNotCreatedByReadingIt() {
        assertEquals(Definitions.EMPTY, loaded(root))
        assertFalse(root.exists())
    }

    @Test
    fun aLocationWithoutDefinitionFilesHoldsNoDefinitions() {
        write(root, "README.md", "Definitions of the team.")
        Files.createDirectories(root.resolve("services"))

        assertEquals(Definitions.EMPTY, loaded(root))
        assertEquals(mapOf("README.md" to "Definitions of the team.", "services/" to ""), treeOf(root))
    }

    @Test
    fun servicesAndScenariosAreReadBackInTheirOrder() {
        val zebra = service("Zebra", scenario("zulu"), scenario("alpha"), scenario("mike"))
        val apple = service("Apple", scenario("yankee"), scenario("bravo"))
        val mango = service("Mango")
        val definitions = Definitions(services = listOf(zebra, apple, mango))

        stored(root, definitions)

        assertEquals(definitions, loaded(root))
        assertEquals(listOf("Zebra", "Apple", "Mango"), loaded(root).services.map { it.name })
    }

    @Test
    fun aResponseBodyIsStoredByteForByteAsItWasEntered() {
        val bodies =
            listOf(
                "line one\r\nline two\r\n",
                "\n\n  indented, with blank lines around it  \n\n",
                "\uFEFFa byte order mark in front",
                "caf\u00e9 \u00fc\u00f1\u00ee \u65e5\u672c\u8a9e \uD83D\uDE00",
                "tab\tand a null character \u0000 and a trailing space ",
                " ",
            )
        val scenarios = bodies.mapIndexed { number, body -> scenario("body $number", body) }
        val definitions = Definitions(services = listOf(service("Bodies", *scenarios.toTypedArray())))

        stored(root, definitions)

        bodies.forEachIndexed { number, body ->
            assertContentEquals(
                body.toByteArray(Charsets.UTF_8),
                Files.readAllBytes(root.resolve("services/bodies/scenarios/body-$number.body")),
            )
        }
        assertEquals(definitions, loaded(root))
    }

    @Test
    fun aBodyThatUtf8CannotHoldStaysInItsScenarioFile() {
        val halfACharacter = scenario("half", "a surrogate without its other half: \uD83D")
        val definitions = Definitions(services = listOf(service("Bodies", halfACharacter)))

        stored(root, definitions)

        assertEquals(listOf("mockey.json", "services/bodies/scenarios/half.json", "services/bodies/service.json"), filesOf(root))
        assertEquals(definitions, loaded(root))
    }

    @Test
    fun theCredentialsOfTheOutboundProxyAreNotStored() {
        val proxy = OutboundProxy("http://proxy.example.com:3128", enabled = true, credentials = ProxyCredentials("proxy-user", "s3cret"))
        val definitions = Sample.definitions.copy(settings = Sample.settings.copy(outboundProxy = proxy))

        stored(root, definitions)

        assertTrue(treeOf(root).values.none { "s3cret" in it || "proxy-user" in it })
        assertEquals(Sample.definitions, loaded(root))
    }

    // The same definitions give the same files

    @Test
    fun theSameDefinitionsGiveTheSameBytesWhereverTheyAreStored() {
        val elsewhere = directory.resolve("elsewhere")

        stored(root, Sample.definitions)
        stored(elsewhere, loaded(root))

        assertEquals(treeOf(root), treeOf(elsewhere))
    }

    @Test
    fun savingWhatWasLoadedLeavesEveryFileAsItIs() =
        runTest {
            stored(root, Sample.definitions)
            val before = treeOf(root)
            val steps = mutableListOf<String>()
            val storage = watchedStorage(root, steps)

            storage.save(storage.load())

            assertEquals(emptyList(), steps)
            assertEquals(before, treeOf(root))
        }

    @Test
    fun aFileThatSomeoneWroteInAnotherFormIsLeftAsItIsWhileItsDefinitionDoesNotChange() =
        runTest {
            stored(root, Sample.definitions)
            val byHand = """{"mockUrl": "http://api.example.com/orders", "position": 1, "name": "Orders", "id": "svc-orders"}"""
            write(root, "services/orders/service.json", byHand)
            val steps = mutableListOf<String>()
            val storage = watchedStorage(root, steps)

            val loaded = storage.load()
            storage.save(loaded.copy(plans = emptyList(), settings = loaded.settings.copy(defaultPlanId = null)))

            assertEquals(Sample.definitions, loaded)
            assertEquals(listOf("STAGE mockey.json", "MOVE mockey.json"), steps)
            assertEquals(byHand, textOf(root, "services/orders/service.json"))
        }

    @Test
    fun aLocationThatHoldsNoDefinitionsIsNotCreatedBySavingNone() =
        runTest {
            val storage = FileDefinitionStorage(root)

            storage.save(storage.load())

            assertFalse(root.exists())
        }

    @Test
    fun aStorageThatHasNotLoadedYetReadsTheLocationBeforeItSaves() =
        runTest {
            stored(root, Sample.definitions)
            val before = treeOf(root)
            val steps = mutableListOf<String>()

            watchedStorage(root, steps).save(Sample.definitions)

            assertEquals(emptyList(), steps)
            assertEquals(before, treeOf(root))
        }

    @Test
    fun theLocationIsADirectoryWithTheMainFileAndTheServices() {
        stored(root, Sample.definitions)

        assertTrue(root.isDirectory())
        assertEquals("FileDefinitionStorage($root)", FileDefinitionStorage(root).toString())
        assertEquals(root, FileDefinitionStorage(root).root)
    }
}
