package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.ScenarioReference
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Settings
import io.github.dnedrow.mockey.core.model.Tags
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceMaintenanceTest {
    private fun scenario(
        name: String,
        body: String = "",
    ) = Scenario(id = ScenarioId.generate(), name = name, responseBody = body)

    private fun service(
        name: String,
        mockUrl: String,
        scenarios: List<Scenario> = emptyList(),
        realUrls: List<String> = emptyList(),
        tags: Tags = Tags.EMPTY,
    ) = Service(
        id = ServiceId.generate(),
        name = name,
        mockUrl = mockUrl,
        scenarios = scenarios,
        realUrls = realUrls,
        tags = tags,
    ).withValidDesignations()

    private fun Definitions.named(name: String): Service = services.single { it.name == name }

    // Duplication

    private val silver = scenario("silver", "{\"tier\":\"silver\"}")
    private val gold = scenario("gold", "{\"tier\":\"gold\"}")
    private val customers =
        service("Customers", "customer/{id}", listOf(silver, gold), listOf("http://api.example.com/customers/{id}"), Tags.of("crm")).copy(
            description = "Customer directory",
            defaultScenarioId = gold.id,
            errorScenarioId = silver.id,
            responseMode = ResponseMode.DYNAMIC,
            delayMillis = 250,
            requestRules = """{"headers":[]}""",
            requestRulesEnabled = true,
            inspectorName = "session-check",
            responseSchema = """{"type":"object"}""",
            responseSchemaEnabled = true,
        )
    private val withCustomers = Definitions(services = listOf(customers))

    @Test
    fun aDuplicateHasItsOwnIdentifierNameAndMockUrlPattern() {
        val duplicated = withCustomers.changed(ServiceMaintenance.duplicate(customers.id))

        val copy = duplicated.value
        assertEquals(listOf(customers, copy), duplicated.definitions.services)
        assertNotEquals(customers.id, copy.id)
        assertNotEquals(customers.name, copy.name)
        assertNotEquals(customers.mockUrl, copy.mockUrl)
        assertContains(copy.name, "Customers")
        assertContains(copy.mockUrl, "customer/{id}")
    }

    @Test
    fun aDuplicateHasCopiesOfTheScenariosWithTheSameDesignations() {
        val copy = withCustomers.changed(ServiceMaintenance.duplicate(customers.id)).value

        assertEquals(listOf("silver", "gold"), copy.scenarios.map { it.name })
        assertEquals(customers.scenarios.map { it.copy(id = ScenarioId("same")) }, copy.scenarios.map { it.copy(id = ScenarioId("same")) })
        assertTrue(copy.scenarios.none { it.id == silver.id || it.id == gold.id })
        assertEquals(copy.scenarios[1].id, copy.defaultScenarioId)
        assertEquals(copy.scenarios[0].id, copy.errorScenarioId)
    }

    @Test
    fun aDuplicateKeepsTheSettingsOfTheOriginalButNotItsRealUrls() {
        val copy = withCustomers.changed(ServiceMaintenance.duplicate(customers.id)).value

        assertEquals(ResponseMode.DYNAMIC, copy.responseMode)
        assertEquals(250L, copy.delayMillis)
        assertEquals(Tags.of("crm"), copy.tags)
        assertEquals("""{"headers":[]}""", copy.requestRules)
        assertTrue(copy.requestRulesEnabled)
        assertEquals("""{"type":"object"}""", copy.responseSchema)
        assertTrue(copy.responseSchemaEnabled)
        assertEquals("Customer directory", copy.description)
        assertEquals("session-check", copy.inspectorName)
        assertEquals(emptyList(), copy.realUrls)
        assertEquals(0, copy.defaultRealUrlIndex)
    }

    @Test
    fun everyDuplicateOfAServiceHasAnotherNameAndMockUrlPattern() {
        val first = withCustomers.changed(ServiceMaintenance.duplicate(customers.id))
        val second = first.definitions.changed(ServiceMaintenance.duplicate(customers.id))
        val ofCopy = second.definitions.changed(ServiceMaintenance.duplicate(first.value.id))

        val services = ofCopy.definitions.services
        assertEquals(4, services.map { it.name.lowercase() }.toSet().size)
        assertEquals(4, services.map { it.mockUrl.lowercase() }.toSet().size)
        assertEquals(4, services.map { it.id }.toSet().size)
    }

    @Test
    fun theNameOfADuplicateIsNotLongerThanANameCanBe() {
        val long = service("n".repeat(250), "long")

        val copy = Definitions(services = listOf(long)).changed(ServiceMaintenance.duplicate(long.id)).value

        assertEquals(250, copy.name.length)
        assertNotEquals(long.name, copy.name)
    }

    @Test
    fun aDuplicateDoesNotTakeOverTheUniversalErrorScenario() {
        val universal = ScenarioReference(customers.id, silver.id)
        val definitions = withCustomers.copy(settings = Settings(universalErrorScenario = universal))

        val duplicated = definitions.changed(ServiceMaintenance.duplicate(customers.id))

        assertEquals(universal, duplicated.definitions.settings.universalErrorScenario)
    }

    @Test
    fun aServiceThatDoesNotExistIsNotDuplicated() {
        val unknown = ServiceId.generate()

        assertContains(withCustomers.notFound(ServiceMaintenance.duplicate(unknown)), unknown.value)
    }

    // Merge

    private val source =
        service(
            "Orders v1",
            "orders/v1",
            listOf(scenario("ok", "{\"status\":\"ok\"}"), scenario("timeout", "")),
            listOf("http://api.example.com/v1/orders"),
            Tags.of("legacy smoke"),
        )
    private val destination =
        service("Orders", "orders", listOf(scenario("empty", "[]")), listOf("http://api.example.com/orders"), Tags.of("smoke"))
    private val withOrders = Definitions(services = listOf(source, destination))

    @Test
    fun aMergeCopiesTheScenariosOfTheSourceAndReportsThem() {
        val merged = withOrders.changed(ServiceMaintenance.merge(destination.id, listOf(source.id)))

        val scenarios = merged.definitions.named("Orders").scenarios
        assertEquals(listOf("empty", "ok", "timeout"), scenarios.map { it.name })
        assertEquals("{\"status\":\"ok\"}", scenarios[1].responseBody)
        assertEquals(listOf(MergedScenario("Orders v1", "ok"), MergedScenario("Orders v1", "timeout")), merged.value.copied)
        assertEquals(emptyList(), merged.value.skipped)
    }

    @Test
    fun aMergeCopiesScenariosUnderNewIdentifiersAndKeepsTheDefaultScenario() {
        val merged = withOrders.changed(ServiceMaintenance.merge(destination.id, listOf(source.id)))

        val orders = merged.definitions.named("Orders")
        assertEquals(destination.defaultScenarioId, orders.defaultScenarioId)
        assertTrue(orders.scenarios.drop(1).none { copy -> source.scenarios.any { it.id == copy.id } })
        assertEquals(
            3,
            orders.scenarios
                .map { it.id }
                .toSet()
                .size,
        )
    }

    @Test
    fun aMergeAddsTheTagsOfTheSources() {
        val merged = withOrders.changed(ServiceMaintenance.merge(destination.id, listOf(source.id)))

        assertEquals(Tags.of("legacy smoke"), merged.definitions.named("Orders").tags)
    }

    @Test
    fun aMergeThatSkipsNothingMovesTheRealUrlsAndDeletesTheSource() {
        val merged = withOrders.changed(ServiceMaintenance.merge(destination.id, listOf(source.id)))

        assertTrue(merged.value.sourcesDeleted)
        assertEquals(listOf("Orders"), merged.definitions.services.map { it.name })
        assertEquals(
            listOf("http://api.example.com/orders", "http://api.example.com/v1/orders"),
            merged.definitions.named("Orders").realUrls,
        )
    }

    @Test
    fun aMergeThatSkipsAScenarioKeepsTheSourceAndItsRealUrls() {
        val duplicate = destination.copy(scenarios = destination.scenarios + scenario(" OK ", "{\"status\":\"OK\"}\n"))
        val definitions = withOrders.withService(duplicate)

        val merged = definitions.changed(ServiceMaintenance.merge(destination.id, listOf(source.id)))

        val orders = merged.definitions.named("Orders")
        assertEquals(listOf("empty", " OK ", "timeout"), orders.scenarios.map { it.name })
        assertEquals(listOf(MergedScenario("Orders v1", "ok")), merged.value.skipped)
        assertEquals(listOf(MergedScenario("Orders v1", "timeout")), merged.value.copied)
        assertFalse(merged.value.sourcesDeleted)
        assertEquals(source, merged.definitions.named("Orders v1"))
        assertEquals(listOf("http://api.example.com/orders"), orders.realUrls)
    }

    @Test
    fun scenariosWithTheSameNameButAnotherBodyAreBothKept() {
        val other = destination.copy(scenarios = destination.scenarios + scenario("ok", "{\"status\":\"different\"}"))

        val merged = withOrders.withService(other).changed(ServiceMaintenance.merge(destination.id, listOf(source.id)))

        assertEquals(
            listOf("empty", "ok", "ok", "timeout"),
            merged.definitions
                .named("Orders")
                .scenarios
                .map { it.name },
        )
        assertTrue(merged.value.sourcesDeleted)
    }

    @Test
    fun aDestinationWithoutScenariosGetsTheFirstCopyAsItsDefaultScenario() {
        val blank = service("Blank", "blank")
        val definitions = Definitions(services = listOf(source, blank))

        val merged = definitions.changed(ServiceMaintenance.merge(blank.id, listOf(source.id)))

        val service = merged.definitions.named("Blank")
        assertEquals(listOf("ok", "timeout"), service.scenarios.map { it.name })
        assertEquals(service.scenarios.first().id, service.defaultScenarioId)
    }

    @Test
    fun severalSourcesAreMergedInTheGivenOrder() {
        val second =
            service(
                "Orders v2",
                "orders/v2",
                listOf(scenario("ok", "{\"status\":\"ok\"}"), scenario("created")),
                listOf("http://api.example.com/v2/orders"),
            )
        val definitions = Definitions(services = listOf(source, second, destination))

        val merged = definitions.changed(ServiceMaintenance.merge(destination.id, listOf(second.id, source.id, second.id)))

        assertEquals(
            listOf("empty", "ok", "created", "timeout"),
            merged.definitions
                .named("Orders")
                .scenarios
                .map { it.name },
        )
        assertEquals(listOf(MergedScenario("Orders v1", "ok")), merged.value.skipped)
        assertFalse(merged.value.sourcesDeleted)
        assertEquals(3, merged.definitions.services.size)
    }

    @Test
    fun theUniversalErrorScenarioOfADeletedSourceIsNoLongerDesignated() {
        val universal = ScenarioReference(source.id, source.scenarios.first().id)
        val definitions = withOrders.copy(settings = Settings(universalErrorScenario = universal))

        val merged = definitions.changed(ServiceMaintenance.merge(destination.id, listOf(source.id)))

        assertNull(merged.definitions.settings.universalErrorScenario)
    }

    @Test
    fun aServiceIsNotMergedIntoItself() {
        val merged = withOrders.changed(ServiceMaintenance.merge(destination.id, listOf(destination.id)))

        assertEquals(withOrders, merged.definitions)
        assertEquals(MergeReport(emptyList(), emptyList(), sourcesDeleted = false), merged.value)
    }

    @Test
    fun servicesThatDoNotExistAreNotMerged() {
        val unknown = ServiceId.generate()

        assertContains(withOrders.notFound(ServiceMaintenance.merge(unknown, listOf(source.id))), unknown.value)
        assertContains(withOrders.notFound(ServiceMaintenance.merge(destination.id, listOf(source.id, unknown))), unknown.value)
    }

    // Bulk derivation of real URLs

    private val qaOrders = service("Orders", "orders", realUrls = listOf("http://qa1.example.com/orders"))
    private val qaBilling =
        service("Billing", "billing", realUrls = listOf("http://qa1.example.com/billing", "http://QA2.example.com/billing"))
    private val unlisted = service("Mock only", "mock-only")
    private val withQa = Definitions(services = listOf(qaOrders, qaBilling, unlisted))

    @Test
    fun derivationAddsAUrlForEachReplacement() {
        val derived = Definitions(services = listOf(qaOrders)).changed(ServiceMaintenance.deriveRealUrls("qa1", listOf("qa2", "qa3")))

        assertEquals(
            listOf("http://qa1.example.com/orders", "http://qa2.example.com/orders", "http://qa3.example.com/orders"),
            derived.definitions.named("Orders").realUrls,
        )
    }

    @Test
    fun derivationCoversEveryServiceAndYieldsWhatItAdded() {
        val derived = withQa.changed(ServiceMaintenance.deriveRealUrls("qa1", listOf("qa2", "qa3")))

        assertEquals(
            listOf("http://qa1.example.com/billing", "http://QA2.example.com/billing", "http://qa3.example.com/billing"),
            derived.definitions.named("Billing").realUrls,
        )
        assertEquals(emptyList(), derived.definitions.named("Mock only").realUrls)
        assertEquals(
            listOf(
                DerivedRealUrl(qaOrders.id, "Orders", "http://qa2.example.com/orders"),
                DerivedRealUrl(qaOrders.id, "Orders", "http://qa3.example.com/orders"),
                DerivedRealUrl(qaBilling.id, "Billing", "http://qa3.example.com/billing"),
            ),
            derived.value,
        )
    }

    @Test
    fun derivationSkipsAResultThatAnotherServiceLists() {
        val taken = service("Orders QA2", "orders-qa2", realUrls = listOf("http://qa2.example.com/orders"))

        val derived =
            Definitions(
                services = listOf(qaOrders, taken),
            ).changed(ServiceMaintenance.deriveRealUrls("qa1", listOf("qa2", "qa3")))

        assertEquals(listOf("http://qa1.example.com/orders", "http://qa3.example.com/orders"), derived.definitions.named("Orders").realUrls)
        assertEquals(listOf("http://qa2.example.com/orders"), derived.definitions.named("Orders QA2").realUrls)
    }

    @Test
    fun derivationAddsEachResultOnce() {
        val derived =
            Definitions(
                services = listOf(qaOrders),
            ).changed(ServiceMaintenance.deriveRealUrls("qa1", listOf("qa2", "QA2", " ", "qa1")))

        assertEquals(listOf("http://qa1.example.com/orders", "http://qa2.example.com/orders"), derived.definitions.named("Orders").realUrls)
    }

    @Test
    fun derivationChangesNothingWhenTheExpressionMatchesNoRealUrl() {
        val derived = withQa.changed(ServiceMaintenance.deriveRealUrls("uat", listOf("prod")))

        assertEquals(withQa, derived.definitions)
        assertEquals(emptyList(), derived.value)
    }

    @Test
    fun aReplacementCanReferToAGroupOfTheExpression() {
        val derived =
            Definitions(
                services = listOf(qaOrders),
            ).changed(ServiceMaintenance.deriveRealUrls("""qa(\d)\.example""", listOf("uat$1.example")))

        assertEquals(
            listOf("http://qa1.example.com/orders", "http://uat1.example.com/orders"),
            derived.definitions.named("Orders").realUrls,
        )
    }

    @Test
    fun derivationRejectsAnExpressionThatIsEmptyOrNotARegularExpression() {
        assertEquals(listOf("expression"), withQa.rejected(ServiceMaintenance.deriveRealUrls("", listOf("qa2"))).map { it.field })
        assertEquals(listOf("expression"), withQa.rejected(ServiceMaintenance.deriveRealUrls("([a-z", listOf("qa2"))).map { it.field })
    }

    @Test
    fun derivationRejectsReplacementsThatCannotBeUsed() {
        assertEquals(listOf("replacements"), withQa.rejected(ServiceMaintenance.deriveRealUrls("qa1", emptyList())).map { it.field })
        assertEquals(listOf("replacements"), withQa.rejected(ServiceMaintenance.deriveRealUrls("qa1", listOf(" ", ""))).map { it.field })
        assertEquals(listOf("replacements"), withQa.rejected(ServiceMaintenance.deriveRealUrls("qa1", listOf("qa$7"))).map { it.field })
    }
}
