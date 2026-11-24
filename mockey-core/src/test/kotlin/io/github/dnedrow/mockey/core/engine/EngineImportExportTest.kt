package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.definitions.ImportItemKind
import io.github.dnedrow.mockey.core.definitions.ImportReport
import io.github.dnedrow.mockey.core.history.NOON
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.PlanId
import io.github.dnedrow.mockey.core.model.PlanItem
import io.github.dnedrow.mockey.core.model.ProxyCredentials
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Settings
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.storage.InMemoryDefinitionStorage
import io.github.dnedrow.mockey.core.storage.Sample
import io.github.dnedrow.mockey.core.storage.StorageException
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

/** How an engine gives its definitions as one document, and how it takes the definitions of a document. */
class EngineImportExportTest {
    private val ok = Scenario(id = ScenarioId("scn-ok"), name = "ok", responseBody = "fine")
    private val orders =
        Service(id = ServiceId("svc-orders"), name = "Orders", mockUrl = "orders", scenarios = listOf(ok), defaultScenarioId = ok.id)
    private val plan = Plan(id = PlanId("plan-static"), name = "All static", items = listOf(PlanItem("Orders", "ok", ResponseMode.STATIC)))
    private val proxy =
        OutboundProxy("http://proxy.example.com:3128", enabled = true, credentials = ProxyCredentials("proxy-user", "s3cret"))
    private val small = Definitions(listOf(orders), listOf(plan), settings = Settings(outboundProxy = proxy))

    private val smallDocument =
        """
        {
            "formatVersion": 1,
            "services": [
                {
                    "id": "svc-orders",
                    "name": "Orders",
                    "mockUrl": "orders",
                    "scenarios": [
                        {
                            "id": "scn-ok",
                            "name": "ok",
                            "responseBody": "fine"
                        }
                    ],
                    "defaultScenarioId": "scn-ok"
                }
            ],
            "plans": [
                {
                    "id": "plan-static",
                    "name": "All static",
                    "items": [
                        {
                            "serviceName": "Orders",
                            "scenarioName": "ok",
                            "responseMode": "static"
                        }
                    ]
                }
            ],
            "settings": {
                "outboundProxy": {
                    "url": "http://proxy.example.com:3128",
                    "enabled": true
                }
            }
        }

        """.trimIndent()

    private fun reportOf(result: ChangeResult<ImportReport>): ImportReport = assertIs<ChangeResult.Changed<ImportReport>>(result).value

    // Export

    @Test
    fun anExportIsOneDocumentWithEverythingThatAStorageHolds() {
        val engine = engineOf(small)

        val document = engine.exportDefinitions()

        assertEquals(smallDocument, document)
        assertFalse("s3cret" in document || "proxy-user" in document)
    }

    @Test
    fun anEngineWithoutDefinitionsExportsADocumentWithTheFormatVersion() {
        assertEquals("{\n    \"formatVersion\": 1\n}\n", engineOf().exportDefinitions())
    }

    // Import

    @Test
    fun anExportThatIsImportedIntoAnEmptyEngineGivesTheSameDefinitions(): Unit =
        runBlocking {
            val source = createdEngine { storage = InMemoryDefinitionStorage(Sample.definitions) }
            val target = createdEngine()

            val report = reportOf(target.importDocument(source.exportDefinitions()))

            assertEquals(source.definitions, target.definitions)
            assertEquals(emptyList(), report.conflicts)
            assertEquals(source.exportDefinitions(), target.exportDefinitions())
        }

    @Test
    fun aDocumentThatSomeoneWroteWithoutAFormatVersionIsImported(): Unit =
        runBlocking {
            val engine = engineOf()

            val report = reportOf(engine.importDocument("""{"services": [{"id": "svc-orders", "name": "Orders", "mockUrl": "orders"}]}"""))

            assertEquals(listOf("Orders"), engine.definitions.services.map { it.name })
            assertEquals(listOf("The service 'Orders' was added with 0 scenarios."), report.additions.map { it.message })
        }

    @Test
    fun aTextThatIsNoDocumentIsRejectedAndNothingIsImported(): Unit =
        runBlocking {
            val store = RecordingStorage()
            val engine =
                engineOf(small) {
                    storage = store
                    readOnly = false
                }
            val texts =
                listOf(
                    "<mockservice/>",
                    """{"formatVersion": 2, "services": []}""",
                    """{"services": [{"name": "Orders", "mockUrl": "orders"}]}""",
                    """{"services": [], "history": []}""",
                )

            val errors = texts.map { text -> assertIs<ChangeResult.Rejected>(engine.importDocument(text)).failure.errors.single() }

            val service = "io.github.dnedrow.mockey.core.model.Service"
            val problems =
                listOf(
                    "it is not valid JSON: <mockservice/> is not a JSON value.",
                    "it has the format version 2, and this version of Mockey reads the format version 1 only.",
                    "Field 'id' is required for type with serial name '$service', but it was missing",
                    "Encountered an unknown key 'history' at path: $",
                )
            assertEquals(problems.map { FieldError("document", "The document cannot be imported: $it") }, errors)
            assertSame(small, engine.definitions)
            assertEquals(emptyList(), store.saves)
        }

    @Test
    fun theTagsOfAnImportGoToEveryServiceScenarioAndPlanThatItAdds(): Unit =
        runBlocking {
            val engine = engineOf()

            engine.importDocument(smallDocument, Tags.of("sprint42"))

            val service = engine.definitions.services.single()
            val tags =
                listOf(
                    service.tags,
                    service.scenarios.single().tags,
                    engine.definitions.plans
                        .single()
                        .tags,
                )
            assertEquals(List(3) { Tags.of("sprint42") }, tags)
        }

    @Test
    fun definitionsThatAProgramPutTogetherAreImportedAsThoseOfADocumentAre(): Unit =
        runBlocking {
            val engine = engineOf(Definitions(services = listOf(orders.copy(scenarios = emptyList(), defaultScenarioId = null))))

            val report = engine.importDefinitions(small)

            assertEquals(listOf(orders), engine.definitions.services)
            assertEquals(listOf("ok", "All static", "outbound proxy"), report.additions.map { it.name })
            assertEquals(listOf(ImportItemKind.SERVICE), report.conflicts.map { it.kind })
        }

    @Test
    fun aDefaultPlanThatAnImportDesignatesIsAppliedAtTheTimeOfTheImport(): Unit =
        runBlocking {
            val time = TestClock(NOON + Duration.ofHours(2))
            val engine = engineOf { clock = time }
            val document = small.copy(settings = Settings(defaultPlanId = plan.id))

            val report = engine.importDefinitions(document)

            assertEquals(plan, report.appliedDefaultPlan?.plan)
            assertEquals(
                ResponseMode.STATIC,
                engine.definitions.services
                    .single()
                    .responseMode,
            )
            assertEquals(NOON + Duration.ofHours(2), engine.lastUsedTimes.ofPlan(plan.id))
        }

    // Import and the storage

    @Test
    fun inWritableModeAnImportIsSavedAsOneChange(): Unit =
        runBlocking {
            val store = RecordingStorage()
            val engine =
                engineOf {
                    storage = store
                    readOnly = false
                }

            engine.importDocument(smallDocument)

            assertEquals(listOf(engine.definitions), store.saves)
            assertEquals(small.copy(settings = Settings(outboundProxy = proxy.copy(credentials = null))), store.stored)
        }

    @Test
    fun anImportThatCannotBeSavedImportsNothing(): Unit =
        runBlocking {
            val store = RecordingStorage()
            val engine =
                engineOf {
                    storage = store
                    readOnly = false
                }
            store.failure = StorageException("The definitions location cannot be written to.")

            assertFailsWith<StorageException> { engine.importDocument(smallDocument) }

            assertSame(Definitions.EMPTY, engine.definitions)
        }
}
