package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.definitions.DerivedRealUrl
import io.github.dnedrow.mockey.core.definitions.DiagnosticKind
import io.github.dnedrow.mockey.core.definitions.MergeReport
import io.github.dnedrow.mockey.core.definitions.MergedScenario
import io.github.dnedrow.mockey.core.definitions.SchemaReport
import io.github.dnedrow.mockey.core.definitions.SearchResultType
import io.github.dnedrow.mockey.core.history.HistoryEntryId
import io.github.dnedrow.mockey.core.history.HistoryQuery
import io.github.dnedrow.mockey.core.history.NOON
import io.github.dnedrow.mockey.core.history.ServiceStatistics
import io.github.dnedrow.mockey.core.history.headersOf
import io.github.dnedrow.mockey.core.history.requestFor
import io.github.dnedrow.mockey.core.inspection.StubInspector
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.PlanItem
import io.github.dnedrow.mockey.core.model.ProxyCredentials
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioReference
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistPair
import io.github.dnedrow.mockey.core.response.MockResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The operations of an engine on its definitions and on its history. Each makes one change of the definitions,
 * and the tests of those changes say what the changes do. These tests say that an operation makes its change.
 */
class EngineOperationsTest {
    /** Creates a static-mode service with a scenario for each of [scenarioNames], and returns it as it is after that. */
    private suspend fun MockeyEngine.staticService(
        name: String,
        mockUrl: String,
        vararg scenarioNames: String,
    ): Service {
        val created = services.create { it.copy(name = name, mockUrl = mockUrl, responseMode = ResponseMode.STATIC) }.getOrThrow()
        for (scenarioName in scenarioNames) {
            scenarios.create(created.id) { it.copy(name = scenarioName, responseBody = scenarioName) }.getOrThrow()
        }
        return service(created.id)
    }

    /** Creates the proxy-mode service `Orders` with the mock URL pattern `orders`, which forwards to [realUrl]. */
    private suspend fun MockeyEngine.orders(realUrl: String): Service =
        services.create { it.copy(name = "Orders", mockUrl = "orders", realUrls = listOf(realUrl)) }.getOrThrow()

    private fun MockeyEngine.service(id: ServiceId): Service = definitions.services.single { it.id == id }

    private fun Service.scenario(name: String): Scenario = scenarios.single { it.name == name }

    @Test
    fun servicesAreCreatedUpdatedDuplicatedAndDeleted() =
        runTest {
            val engine = engineOf()

            val created = engine.services.create { it.copy(name = "Customers", mockUrl = "customer/{id}") }.getOrThrow()
            val updated = engine.services.update(created.id) { it.copy(description = "Who buys") }.getOrThrow()
            val copy = engine.services.duplicate(created.id).getOrThrow()
            val deleted = engine.services.delete(created.id).getOrThrow()

            assertEquals("Customers" to "customer/{id}", created.name to created.mockUrl)
            assertEquals(created.copy(description = "Who buys"), updated)
            assertNotEquals(listOf(created.id, created.name, created.mockUrl), listOf(copy.id, copy.name, copy.mockUrl))
            assertEquals("Who buys", copy.description)
            assertEquals(updated, deleted)
            assertEquals(listOf(copy), engine.definitions.services)
        }

    @Test
    fun servicesAreMergedAndGetDerivedRealUrls() =
        runTest {
            val engine = engineOf()
            val orders = engine.orders("http://qa1.example.com/orders")
            val old = engine.staticService("Old orders", "old-orders", "ok")

            val merged = engine.services.merge(orders.id, listOf(old.id)).getOrThrow()
            val derived = engine.services.deriveRealUrls("qa1", listOf("qa2")).getOrThrow()

            val copied = listOf(MergedScenario("Old orders", "ok"))
            assertEquals(MergeReport(copied, skipped = emptyList(), sourcesDeleted = true), merged)
            assertEquals(listOf(DerivedRealUrl(orders.id, "Orders", "http://qa2.example.com/orders")), derived)
            assertEquals(listOf("Orders"), engine.definitions.services.map { it.name })
            assertEquals(listOf("http://qa1.example.com/orders", "http://qa2.example.com/orders"), engine.service(orders.id).realUrls)
        }

    @Test
    fun theSchemaReportOfAServiceSaysWhichScenariosConform() =
        runTest {
            val engine = engineOf()
            val service = engine.staticService("Customers", "customer/{id}", "{}", "plain text")
            engine.services.update(service.id) { it.copy(responseSchema = """{"type": "object"}""") }.getOrThrow()
            val unchecked = engine.staticService("Orders", "orders", "ok")

            val report = engine.services.schemaReport(service.id)

            val conformance = assertIs<SchemaReport.Checked>(report).scenarios.map { it.scenarioName to it.conforms }
            assertEquals(listOf("{}" to true, "plain text" to false), conformance)
            assertNull(engine.services.schemaReport(unchecked.id))
            assertNull(engine.services.schemaReport(ServiceId.generate()))
        }

    @Test
    fun scenariosAreCreatedUpdatedDesignatedAndDeleted() =
        runTest {
            val engine = engineOf()
            val service = engine.services.create { it.copy(name = "Customers", mockUrl = "customer/{id}") }.getOrThrow()

            val ok = engine.scenarios.create(service.id) { it.copy(name = "ok") }.getOrThrow()
            val failed = engine.scenarios.create(service.id) { it.copy(name = "failed", statusCode = 500) }.getOrThrow()
            val renamed = engine.scenarios.update(service.id, failed.id) { it.copy(name = "broken") }.getOrThrow()
            engine.scenarios.designateUniversalError(ScenarioReference(service.id, failed.id)).getOrThrow()
            val designated = engine.definitions.settings.universalErrorScenario
            val deleted = engine.scenarios.delete(service.id, failed.id).getOrThrow()

            assertEquals(failed.copy(name = "broken"), renamed)
            assertEquals(ScenarioReference(service.id, failed.id), designated)
            assertEquals(renamed, deleted)
            assertNull(engine.definitions.settings.universalErrorScenario)
            assertEquals(listOf(ok), engine.service(service.id).scenarios)
            assertEquals(ok.id, engine.service(service.id).defaultScenarioId)
        }

    @Test
    fun plansAreSavedCreatedUpdatedAppliedDesignatedAndDeleted() =
        runTest {
            val clock = TestClock()
            val engine = engineOf { this.clock = clock }
            val service = engine.staticService("Customers", "customer/{id}", "ok", "busy")
            val busy = PlanItem("Customers", "busy", ResponseMode.STATIC)

            val saved = engine.plans.save(listOf(service.id), name = "Normal") { it.copy(tags = Tags.of("nightly")) }.getOrThrow()
            val created = engine.plans.create { it.copy(name = "Outage", items = listOf(busy)) }.getOrThrow()
            val updated = engine.plans.update(created.id) { it.copy(description = "Everything is busy") }.getOrThrow()
            clock.now = NOON.plusSeconds(60)
            val report = engine.plans.apply(created.id).getOrThrow()
            val default = engine.plans.designateDefault(saved.id).getOrThrow()
            val deleted = engine.plans.delete(created.id).getOrThrow()

            assertEquals(listOf(PlanItem("Customers", "ok", ResponseMode.STATIC)) to Tags.of("nightly"), saved.items to saved.tags)
            assertEquals(created.copy(description = "Everything is busy"), updated)
            assertEquals(updated, report.plan)
            assertEquals(service.scenario("busy").id, engine.service(service.id).defaultScenarioId)
            assertEquals(mapOf(created.id to NOON.plusSeconds(60)), engine.lastUsedTimes.plans)
            assertEquals(saved to saved.id, default to engine.definitions.settings.defaultPlanId)
            assertEquals(updated, deleted)
            assertEquals(listOf(saved), engine.definitions.plans)
        }

    @Test
    fun aPlanThatDoesNotExistIsNotNotedAsApplied() =
        runTest {
            val engine = engineOf()
            val plan = engine.plans.create { it.copy(name = "Outage") }.getOrThrow()
            engine.plans.delete(plan.id).getOrThrow()

            val result = engine.plans.apply(plan.id)

            assertIs<ChangeResult.NotFound>(result)
            assertEquals(emptyMap(), engine.lastUsedTimes.plans)
        }

    @Test
    fun twistConfigurationsAreCreatedUpdatedEnabledDisabledAndDeleted() =
        runTest {
            val engine = engineOf()

            val created = engine.twists.create { it.copy(name = "to-qa", pairs = listOf(TwistPair("prod", "qa"))) }.getOrThrow()
            val updated = engine.twists.update(created.id) { it.copy(name = "to-qa2") }.getOrThrow()
            engine.twists.enable(created.id).getOrThrow()
            val enabled = engine.definitions.settings.enabledTwistId
            engine.twists.disable(created.id).getOrThrow()
            val disabled = engine.definitions.settings.enabledTwistId
            val deleted = engine.twists.delete(created.id).getOrThrow()

            assertEquals(created.copy(name = "to-qa2"), updated)
            assertEquals(created.id to null, enabled to disabled)
            assertEquals(updated, deleted)
            assertEquals(emptyList(), engine.definitions.twists)
        }

    @Test
    fun theSettingsOfTheOutboundProxyAreSetRejectedAndRemoved() =
        runTest {
            val engine = engineOf()
            val proxy = OutboundProxy("http://proxy.example.com:3128", enabled = true, credentials = ProxyCredentials("jane", "s3cret"))

            val set = engine.updateOutboundProxy { proxy }.getOrThrow()
            val rejected = engine.updateOutboundProxy { it?.copy(url = "") }
            val kept = engine.definitions.settings.outboundProxy
            val removed = engine.updateOutboundProxy { null }.getOrThrow()

            assertEquals(proxy, set)
            assertEquals(listOf("url"), assertIs<ChangeResult.Rejected>(rejected).failure.errors.map { it.field })
            assertEquals(proxy, kept)
            assertNull(removed)
            assertNull(engine.definitions.settings.outboundProxy)
        }

    @Test
    fun theTagsInUseAreListedAndATagIsRemovedEverywhere() =
        runTest {
            val engine = engineOf()
            val service = engine.staticService("Customers", "customer/{id}", "ok")
            engine.services.update(service.id) { it.copy(tags = Tags.of("checkout", "beta")) }.getOrThrow()
            engine.scenarios.update(service.id, service.scenario("ok").id) { it.copy(tags = Tags.of("beta", "smoke")) }.getOrThrow()
            engine.plans.create { it.copy(name = "Outage", tags = Tags.of("nightly", "beta")) }.getOrThrow()

            val before = engine.tagsInUse()
            engine.removeTag("beta")

            assertEquals(Tags.of("beta", "checkout", "nightly", "smoke"), before)
            assertEquals(Tags.of("checkout", "nightly", "smoke"), engine.tagsInUse())
            assertEquals(Tags.of("checkout"), engine.service(service.id).tags)
        }

    @Test
    fun theDefinitionsAreSearched() =
        runTest {
            val engine = engineOf()
            val service = engine.staticService("Customers", "customer/{id}", "gold customer")
            engine.staticService("Orders", "orders", "ok")
            engine.plans.create { it.copy(name = "Gold week") }.getOrThrow()

            val found = engine.search("GOLD")

            val scenario = found.last()
            val expected = listOf(SearchResultType.PLAN to "Gold week", SearchResultType.SCENARIO to "gold customer")
            assertEquals(expected, found.map { it.type to it.name })
            assertEquals(service.scenario("gold customer").id.value to service.id, scenario.id to scenario.serviceId)
            assertEquals(emptyList(), engine.search("silver"))
        }

    @Test
    fun theDiagnosticsNameAnInspectorThatTheEngineDoesNotHave() =
        runTest {
            val given = listOf(StubInspector("session-check"), StubInspector("session-check"), StubInspector(" "))
            val engine = engineOf { inspectors = given }
            engine.services.create { it.copy(name = "Customers", mockUrl = "customer/{id}", inspectorName = "session-check") }.getOrThrow()
            val unchecked = engine.services.create { it.copy(name = "Orders", mockUrl = "orders", inspectorName = "audit") }.getOrThrow()

            val diagnostics = engine.diagnostics()

            assertEquals(listOf(DiagnosticKind.UNKNOWN_INSPECTOR to unchecked.id), diagnostics.map { it.kind to it.serviceId })
            assertContains(diagnostics.single().message, "audit")
            assertEquals(listOf("session-check"), engine.inspectors.map { it.name })
            assertEquals(2, engine.inspectorProblems.size)
        }

    @Test
    fun theUpstreamSessionIsEmptiedOfItsCookies() =
        runTest {
            val upstream = FakeUpstream { MockResponse(200, headersOf("Set-Cookie" to "session=abc")) }
            val engine = engineOf { upstreamClient = upstream }
            engine.orders("http://orders.example.com/api")
            engine.handle(requestFor("orders"))
            engine.handle(requestFor("orders"))

            engine.clearUpstreamSession()
            engine.handle(requestFor("orders"))

            val cookies = upstream.requests.map { sent -> sent.headers.firstOrNull { it.name == "Cookie" }?.value }
            assertEquals(listOf(null, "session=abc", null), cookies)
        }

    @Test
    fun theEntriesOfTheHistoryAreReadFlaggedAndDeleted() =
        runTest {
            val engine = engineOf()
            val customers = engine.staticService("Customers", "customer/{id}", "ok")
            engine.staticService("Orders", "orders", "ok")
            val first = engine.handle(requestFor("customer/1")).entry
            val second = engine.handle(requestFor("orders")).entry
            val third = engine.handle(requestFor("customer/2")).entry

            val all = engine.history.entries()
            val ofCustomers = engine.history.entries(HistoryQuery(serviceId = customers.id))
            val read = engine.history.entry(second.id)
            val flagged = engine.history.flag(second.id, true)
            val deletions = listOf(engine.history.delete(first.id), engine.history.delete(first.id))
            val deletedOfCustomers = engine.history.deleteOfService(customers.id)
            val left = engine.history.entries()
            val cleared = engine.history.clear()

            assertEquals(listOf(third, second, first) to listOf(third, first), all to ofCustomers)
            assertEquals(second to second.copy(flagged = true), read to flagged)
            assertEquals(listOf(true, false), deletions)
            assertEquals(1 to listOf(flagged), deletedOfCustomers to left)
            assertEquals(1 to emptyList(), cleared to engine.history.entries())
            assertNull(engine.history.entry(second.id))
            assertNull(engine.history.flag(second.id, false))
        }

    @Test
    fun anEntryOfTheHistoryIsSavedAsAScenarioOfItsService() =
        runTest {
            val answer = MockResponse(202, headersOf("Content-Type" to "text/plain"), "from the real service".toByteArray())
            val upstream = FakeUpstream { answer }
            val engine = engineOf { upstreamClient = upstream }
            val service = engine.orders("http://orders.example.com/api")
            val entry = engine.handle(requestFor("orders")).entry

            val scenario = engine.history.saveAsScenario(entry.id) { it.copy(name = "recorded") }.getOrThrow()
            val missing = engine.history.saveAsScenario(HistoryEntryId(99))

            assertEquals("recorded" to 202, scenario.name to scenario.statusCode)
            assertEquals("from the real service", scenario.responseBody)
            assertEquals(listOf(scenario), engine.service(service.id).scenarios)
            assertEquals(ChangeResult.NotFound("The history keeps no entry with the identifier 99."), missing)
        }

    @Test
    fun theStatisticsOfTheHistoryCountTheEntriesOfEveryService() =
        runTest {
            val clock = TestClock()
            val engine = engineOf { this.clock = clock }
            val customers = engine.staticService("Customers", "customer/{id}", "ok")
            val orders = engine.staticService("Orders", "orders", "ok")
            engine.handle(requestFor("customer/1"))
            clock.now = NOON.plusSeconds(60)
            engine.handle(requestFor("customer/2"))
            engine.handle(requestFor("orders"))

            val all = engine.history.statistics()
            val late = engine.history.statistics(from = NOON.plusSeconds(1))

            val ofCustomers = ServiceStatistics(customers.id, "Customers", 2, NOON)
            val ofOrders = ServiceStatistics(orders.id, "Orders", 1, NOON.plusSeconds(60))
            assertEquals(listOf(ofCustomers, ofOrders), all.services)
            assertEquals(listOf(1, 1), late.services.map { it.count })
            assertEquals(NOON.plusSeconds(1), late.from)
        }

    @Test
    fun aResultGivesWhatItsChangeYieldsOrThrowsWhatItSays() =
        runTest {
            val engine = engineOf()
            val absent = ServiceId.generate()

            val created = engine.services.create { it.copy(name = "Customers", mockUrl = "customer/{id}") }
            val rejected = engine.services.create { it.copy(name = "", mockUrl = "customer/{id}") }
            val missing = engine.services.delete(absent)

            val nameError = "name: The name of a service must not be empty."
            val mockUrlError = "mockUrl: The service 'Customers' already uses the mock URL pattern 'customer/{id}'."
            assertEquals("Customers", created.getOrThrow().name)
            val invalid = assertFailsWith<IllegalArgumentException> { rejected.getOrThrow() }
            assertEquals("The change was rejected. $nameError $mockUrlError", invalid.message)
            val notFound = assertFailsWith<NoSuchElementException> { missing.getOrThrow() }
            assertEquals("No service has the identifier ${absent.value}.", notFound.message)
        }
}
