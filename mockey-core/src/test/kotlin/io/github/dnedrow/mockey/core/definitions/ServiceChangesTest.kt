package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.engine.Change
import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.engine.EngineState
import io.github.dnedrow.mockey.core.engine.FieldError
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.ScenarioReference
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Settings
import io.github.dnedrow.mockey.core.model.Tags
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class ServiceChangesTest {
    private fun service(
        name: String,
        mockUrl: String,
        vararg realUrls: String,
    ): Change<Service> = ServiceChanges.create { it.copy(name = name, mockUrl = mockUrl, realUrls = realUrls.toList()) }

    private val keyedRules = """{"headers": [{"key": "X-Tier", "value_rule_arg": "gold", "value_rule_type": "string_required"}]}"""
    private val keylessRules = """{"headers": [{"value_rule_arg": "gold", "value_rule_type": "string_required"}]}"""

    private val customers = Definitions.EMPTY.changed(service("Customers", "customer/{id}"))
    private val orders =
        customers.definitions.changed(service("Orders", "orders", "http://api.example.com/orders", "http://qa.example.com/orders"))
    private val definitions = orders.definitions

    @Test
    fun aServiceCreatedWithOnlyANameAndAMockUrlHasTheDefaults() {
        val created = Definitions.EMPTY.changed(ServiceChanges.create { it.copy(name = "Customers", mockUrl = "customer/{id}") })

        val service = created.value
        assertEquals(listOf(service), created.definitions.services)
        assertEquals("Customers", service.name)
        assertEquals("customer/{id}", service.mockUrl)
        assertEquals(ResponseMode.PROXY, service.responseMode)
        assertEquals(0L, service.delayMillis)
        assertEquals(Tags.EMPTY, service.tags)
        assertEquals(emptyList(), service.realUrls)
        assertEquals(emptyList(), service.scenarios)
        assertFalse(service.requestRulesEnabled)
        assertFalse(service.responseSchemaEnabled)
    }

    @Test
    fun servicesAreKeptInTheOrderInWhichTheyWereCreated() {
        val billing = definitions.changed(service("Billing", "billing"))

        assertEquals(listOf("Customers", "Orders", "Billing"), billing.definitions.services.map { it.name })
    }

    @Test
    fun aServiceKeepsItsIdentifierWhenItIsRenamed() {
        val renamed = definitions.changed(ServiceChanges.update(customers.value.id) { it.copy(name = "Clients") })

        assertEquals(customers.value.id, renamed.value.id)
        assertEquals("Clients", renamed.value.name)
        assertEquals(listOf("Clients", "Orders"), renamed.definitions.services.map { it.name })
        assertEquals(definitions.services.map { it.id }, renamed.definitions.services.map { it.id })
    }

    @Test
    fun aNewServiceGetsAnIdentifierThatWasNeverUsedBefore() {
        val afterDeletion = definitions.changed(ServiceChanges.delete(customers.value.id)).definitions

        val created = afterDeletion.changed(service("Customers", "customer/{id}"))

        assertNotEquals(customers.value.id, created.value.id)
        assertNotEquals(orders.value.id, created.value.id)
    }

    @Test
    fun aChangeThatCreatesAServiceYieldsAnotherIdentifierEachTimeItIsApplied() {
        val create = service("Customers", "customer/{id}")

        assertNotEquals(
            Definitions.EMPTY
                .changed(create)
                .value.id,
            Definitions.EMPTY
                .changed(create)
                .value.id,
        )
    }

    @Test
    fun aServiceWithoutRealUrlsAndWithABlankNameIsRejected() {
        val errors = definitions.rejected(service("   ", "billing"))

        assertEquals(listOf("name"), errors.map { it.field })
    }

    @Test
    fun aServiceThatIsGivenABlankNameIsRejectedAndStaysAsItWas(): Unit =
        runBlocking {
            val state = EngineState(definitions)

            val result = state.change(ServiceChanges.update(customers.value.id) { it.copy(name = " \t ", delayMillis = 250) })

            assertEquals(listOf("name"), assertIs<ChangeResult.Rejected>(result).failure.errors.map { it.field })
            assertSame(definitions, state.current)
        }

    @Test
    fun aRejectedServiceIsNotCreated(): Unit =
        runBlocking {
            val state = EngineState(definitions)

            val result = state.change(service("", "billing"))

            assertIs<ChangeResult.Rejected>(result)
            assertSame(definitions, state.current)
        }

    @Test
    fun aNameOfUpTo250CharactersIsAccepted() {
        val longest = "n".repeat(250)

        assertEquals(longest, definitions.changed(service(longest, "billing")).value.name)
        assertEquals(longest, definitions.changed(service("  $longest  ", "billing")).value.name)
    }

    @Test
    fun aNameOfMoreThan250CharactersIsRejected() {
        val errors = definitions.rejected(service("n".repeat(251), "billing"))

        assertEquals(listOf("name"), errors.map { it.field })
        assertContains(errors.single().message, "250")
    }

    @Test
    fun aNameIsStoredWithoutSurroundingWhitespace() {
        assertEquals("Billing", definitions.changed(service("  Billing\t", "billing")).value.name)
        assertEquals("Clients", definitions.changed(ServiceChanges.update(customers.value.id) { it.copy(name = " Clients ") }).value.name)
    }

    @Test
    fun aServiceCreatedWithoutANameIsNamedAfterItsFirstRealUrl() {
        val created =
            Definitions.EMPTY.changed(
                ServiceChanges.create {
                    it.copy(realUrls = listOf("http://api.example.com/v1/customers?active=true", "http://qa.example.com/v1/clients"))
                },
            )

        assertEquals("customers (auto generated)", created.value.name)
    }

    @Test
    fun aNameOfOnlySpacesCountsAsNoName() {
        val created = Definitions.EMPTY.changed(service("   ", "customers", "http://api.example.com/v1/customers"))

        assertEquals("customers (auto generated)", created.value.name)
    }

    @Test
    fun aNameThatIsGivenIsNotReplacedByADerivedOne() {
        val created = Definitions.EMPTY.changed(service("Customers", "customers", "http://api.example.com/v1/customers"))

        assertEquals("Customers", created.value.name)
    }

    @Test
    fun theDerivedNameIsTheLastPartOfTheUrlWithoutItsQueryString() {
        assertEquals("customers (auto generated)", ServiceChanges.derivedName("http://api.example.com/v1/customers?active=true"))
        assertEquals("customers (auto generated)", ServiceChanges.derivedName("http://api.example.com/v1/customers/"))
        assertEquals("{id} (auto generated)", ServiceChanges.derivedName("http://api.example.com/customers/{id}"))
        assertEquals("customers (auto generated)", ServiceChanges.derivedName("customers"))
    }

    @Test
    fun theDerivedNameOfAUrlWithoutAPathIsItsHost() {
        assertEquals("api.example.com (auto generated)", ServiceChanges.derivedName("http://api.example.com"))
        assertEquals("api.example.com (auto generated)", ServiceChanges.derivedName("http://api.example.com/?page=2"))
    }

    @Test
    fun aNameIsDerivedOnlyWhenAServiceIsCreated() {
        val errors = definitions.rejected(ServiceChanges.update(orders.value.id) { it.copy(name = "") })

        assertEquals(listOf("name"), errors.map { it.field })
    }

    @Test
    fun aMockUrlPatternThatAnotherServiceUsesIsRejected() {
        val errors = definitions.rejected(service("Clients", "Customer/{ID}"))

        assertEquals(listOf("mockUrl"), errors.map { it.field })
        assertContains(errors.single().message, "'Customers'")
    }

    @Test
    fun mockUrlPatternsAreComparedAfterTrimming() {
        val errors = definitions.rejected(service("Clients", "  customer/{id} "))

        assertEquals(listOf("mockUrl"), errors.map { it.field })
    }

    @Test
    fun aServiceCannotTakeTheMockUrlPatternOfAnotherService() {
        val errors = definitions.rejected(ServiceChanges.update(orders.value.id) { it.copy(mockUrl = "CUSTOMER/{id}") })

        assertEquals(listOf("mockUrl"), errors.map { it.field })
        assertContains(errors.single().message, "'Customers'")
    }

    @Test
    fun aServiceCanKeepItsOwnMockUrlPattern() {
        val updated =
            definitions.changed(
                ServiceChanges.update(customers.value.id) { it.copy(mockUrl = "Customer/{ID}", delayMillis = 250) },
            )

        assertEquals("Customer/{ID}", updated.value.mockUrl)
        assertEquals(250L, updated.value.delayMillis)
    }

    @Test
    fun onlyOneServiceCanBeWithoutAMockUrlPattern() {
        val first = definitions.changed(service("Billing", "", "http://api.example.com/billing"))

        val errors = first.definitions.rejected(service("Payments", "  ", "http://api.example.com/payments"))

        assertEquals(listOf("mockUrl"), errors.map { it.field })
        assertContains(errors.single().message, "'Billing'")
    }

    @Test
    fun aMockUrlPatternIsStoredWithoutSurroundingWhitespace() {
        assertEquals("billing/{id}", definitions.changed(service("Billing", " billing/{id}\n")).value.mockUrl)
    }

    @Test
    fun aRealUrlThatAnotherServiceListsIsRejected() {
        val errors = definitions.rejected(service("Billing", "billing", "http://api.example.com/billing", "http://api.example.com/orders"))

        assertEquals(listOf("realUrls"), errors.map { it.field })
        assertContains(errors.single().message, "'Orders'")
        assertContains(errors.single().message, "http://api.example.com/orders")
    }

    @Test
    fun realUrlsAreComparedAfterTrimmingAndWithoutRegardToLetterCase() {
        val errors = definitions.rejected(service("Billing", "billing", "  HTTP://API.example.com/Orders "))

        assertEquals(listOf("realUrls"), errors.map { it.field })
        assertContains(errors.single().message, "'Orders'")
    }

    @Test
    fun aServiceCannotTakeARealUrlOfAnotherService() {
        val errors =
            definitions.rejected(ServiceChanges.update(customers.value.id) { it.copy(realUrls = listOf("http://qa.example.com/orders")) })

        assertEquals(listOf("realUrls"), errors.map { it.field })
        assertContains(errors.single().message, "http://qa.example.com/orders")
    }

    @Test
    fun aServiceCanKeepItsOwnRealUrls() {
        val updated =
            definitions.changed(
                ServiceChanges.update(orders.value.id) { it.copy(realUrls = it.realUrls + "http://uat.example.com/orders") },
            )

        assertEquals(
            listOf("http://api.example.com/orders", "http://qa.example.com/orders", "http://uat.example.com/orders"),
            updated.value.realUrls,
        )
    }

    @Test
    fun realUrlsAreStoredTrimmedAndWithoutBlankOrRepeatedOnes() {
        val created =
            definitions.changed(
                service(
                    "Billing",
                    "billing",
                    " http://api.example.com/billing ",
                    "",
                    "HTTP://API.EXAMPLE.COM/billing",
                    "  ",
                    "http://qa.example.com/billing",
                ),
            )

        assertEquals(listOf("http://api.example.com/billing", "http://qa.example.com/billing"), created.value.realUrls)
    }

    @Test
    fun theDefaultRealUrlStaysTheOneThatWasDesignated() {
        val created =
            definitions.changed(
                ServiceChanges.create {
                    it.copy(
                        name = "Billing",
                        mockUrl = "billing",
                        realUrls = listOf("", "http://api.example.com/billing", " http://qa.example.com/billing "),
                        defaultRealUrlIndex = 2,
                    )
                },
            )

        assertEquals(1, created.value.defaultRealUrlIndex)
        assertEquals("http://qa.example.com/billing", created.value.realUrls[created.value.defaultRealUrlIndex])
    }

    @Test
    fun theFirstRealUrlIsTheDefaultWhenNoneOfTheRealUrlsIsDesignated() {
        val outside = definitions.changed(ServiceChanges.update(orders.value.id) { it.copy(defaultRealUrlIndex = 7) })
        val negative = definitions.changed(ServiceChanges.update(orders.value.id) { it.copy(defaultRealUrlIndex = -1) })
        val removed =
            definitions
                .changed(ServiceChanges.update(orders.value.id) { it.copy(defaultRealUrlIndex = 1) })
                .definitions
                .changed(ServiceChanges.update(orders.value.id) { it.copy(realUrls = listOf("http://api.example.com/orders")) })
        val none = definitions.changed(ServiceChanges.update(orders.value.id) { it.copy(realUrls = emptyList(), defaultRealUrlIndex = 1) })

        assertEquals(listOf(0, 0, 0, 0), listOf(outside, negative, removed, none).map { it.value.defaultRealUrlIndex })
    }

    @Test
    fun everyInvalidFieldIsReported() {
        val errors = definitions.rejected(service("n".repeat(251), "ORDERS", "http://api.example.com/orders"))

        assertEquals(listOf("name", "mockUrl", "realUrls"), errors.map { it.field })
    }

    @Test
    fun blankOptionalAttributesAreAbsent() {
        val created =
            definitions.changed(
                ServiceChanges.create {
                    it.copy(
                        name = "Billing",
                        mockUrl = "billing",
                        description = " ",
                        requestRules = "",
                        inspectorName = "\t",
                        responseSchema = "\n",
                    )
                },
            )

        assertNull(created.value.description)
        assertNull(created.value.requestRules)
        assertNull(created.value.inspectorName)
        assertNull(created.value.responseSchema)
    }

    @Test
    fun optionalAttributesThatAreGivenAreKept() {
        val rules = """ {"headers": []} """
        val created =
            definitions.changed(
                ServiceChanges.create {
                    it.copy(
                        name = "Billing",
                        mockUrl = "billing",
                        description = "Invoices and payments",
                        responseMode = ResponseMode.DYNAMIC,
                        delayMillis = 100,
                        tags = Tags.of("Finance smoke"),
                        requestRules = rules,
                        requestRulesEnabled = true,
                        inspectorName = " session-check ",
                        responseSchema = """{"type": "object"}""",
                        responseSchemaEnabled = true,
                    )
                },
            )

        val service = created.value
        assertEquals("Invoices and payments", service.description)
        assertEquals(ResponseMode.DYNAMIC, service.responseMode)
        assertEquals(100L, service.delayMillis)
        assertEquals(Tags.of("finance smoke"), service.tags)
        assertEquals(rules, service.requestRules)
        assertTrue(service.requestRulesEnabled)
        assertEquals("session-check", service.inspectorName)
        assertEquals("""{"type": "object"}""", service.responseSchema)
        assertTrue(service.responseSchemaEnabled)
    }

    @Test
    fun aServiceWithRequestRulesThatAreNotWellFormedIsNotCreated(): Unit =
        runBlocking {
            val state = EngineState(definitions)

            val result = state.change(ServiceChanges.create { it.copy(name = "Billing", mockUrl = "billing", requestRules = keylessRules) })

            val error = assertIs<ChangeResult.Rejected>(result).failure.errors.single()
            assertEquals("requestRules", error.field)
            assertContains(error.message, "Rule 1 of the group 'headers' has no 'key'.")
            assertSame(definitions, state.current)
        }

    @Test
    fun aServiceThatIsGivenRequestRulesThatAreNotWellFormedIsRejectedAndStaysAsItWas(): Unit =
        runBlocking {
            val state = EngineState(definitions)
            val update = ServiceChanges.update(orders.value.id) { it.copy(requestRules = "not a rule set", delayMillis = 250) }

            val result = state.change(update)

            val error = assertIs<ChangeResult.Rejected>(result).failure.errors.single()
            assertEquals("requestRules", error.field)
            assertContains(error.message, "not valid JSON")
            assertSame(definitions, state.current)
        }

    @Test
    fun requestRulesAreCheckedWhetherOrNotTheyAreEnabled() {
        val errors =
            listOf(true, false).map { enabled ->
                val update = ServiceChanges.update(orders.value.id) { it.copy(requestRules = keylessRules, requestRulesEnabled = enabled) }
                definitions.rejected(update)
            }

        assertEquals(listOf(listOf("requestRules"), listOf("requestRules")), errors.map { fields -> fields.map { it.field } })
    }

    @Test
    fun requestRulesThatAreNotWellFormedAreReportedWithTheOtherInvalidFields() {
        val errors = definitions.rejected(ServiceChanges.create { it.copy(name = "  ", mockUrl = "ORDERS", requestRules = keylessRules) })

        assertEquals(listOf("name", "mockUrl", "requestRules"), errors.map { it.field })
    }

    /** A service can have such request rules when it was not saved with them, as after an import. */
    @Test
    fun aServiceWhoseStoredRequestRulesAreNotWellFormedIsRejectedUntilTheyAreRepaired() {
        val stored = definitions.copy(services = listOf(customers.value, orders.value.copy(requestRules = keylessRules)))

        val delayed = stored.rejected(ServiceChanges.update(orders.value.id) { it.copy(delayMillis = 250) })
        val repaired = stored.changed(ServiceChanges.update(orders.value.id) { it.copy(requestRules = keyedRules, delayMillis = 250) })
        val removed = stored.changed(ServiceChanges.update(orders.value.id) { it.copy(requestRules = null, delayMillis = 250) })

        assertEquals(listOf("requestRules"), delayed.map { it.field })
        assertEquals(keyedRules, repaired.value.requestRules)
        assertNull(removed.value.requestRules)
        assertEquals(listOf(250L, 250L), listOf(repaired, removed).map { it.value.delayMillis })
    }

    @Test
    fun anUpdateChangesOnlyWhatItSets() {
        val updated = definitions.changed(ServiceChanges.update(orders.value.id) { it.copy(delayMillis = 250) })

        assertEquals(orders.value.copy(delayMillis = 250), updated.value)
        assertEquals(listOf(customers.value, updated.value), updated.definitions.services)
    }

    @Test
    fun deletingAServiceDeletesItsScenarios() {
        val scenarios = listOf(Scenario(id = ScenarioId.generate(), name = "gold"), Scenario(id = ScenarioId.generate(), name = "silver"))
        val withScenarios =
            definitions.copy(
                services = definitions.services.map { if (it.id == customers.value.id) it.copy(scenarios = scenarios) else it },
            )

        val deleted = withScenarios.changed(ServiceChanges.delete(customers.value.id))

        assertEquals(scenarios, deleted.value.scenarios)
        assertEquals(listOf(orders.value), deleted.definitions.services)
        assertTrue(
            deleted.definitions.services
                .flatMap { it.scenarios }
                .isEmpty(),
        )
    }

    @Test
    fun deletingTheServiceOfTheUniversalErrorScenarioClearsTheDesignation() {
        val failure = Scenario(id = ScenarioId.generate(), name = "failure")
        val errors = Service(id = ServiceId.generate(), name = "Errors", mockUrl = "errors", scenarios = listOf(failure))
        val withUniversal =
            definitions.copy(
                services = definitions.services + errors,
                settings = Settings(universalErrorScenario = ScenarioReference(errors.id, failure.id)),
            )

        val deleted = withUniversal.changed(ServiceChanges.delete(errors.id))

        assertNull(deleted.definitions.settings.universalErrorScenario)
        assertEquals(definitions.services, deleted.definitions.services)
    }

    @Test
    fun deletingAnotherServiceKeepsTheUniversalErrorScenario() {
        val failure = Scenario(id = ScenarioId.generate(), name = "failure")
        val errors = Service(id = ServiceId.generate(), name = "Errors", mockUrl = "errors", scenarios = listOf(failure))
        val universal = ScenarioReference(errors.id, failure.id)
        val withUniversal =
            definitions.copy(
                services = definitions.services + errors,
                settings = Settings(universalErrorScenario = universal),
            )

        val deleted = withUniversal.changed(ServiceChanges.delete(customers.value.id))

        assertEquals(universal, deleted.definitions.settings.universalErrorScenario)
        assertEquals(listOf("Orders", "Errors"), deleted.definitions.services.map { it.name })
    }

    @Test
    fun aServiceThatDoesNotExistIsNeitherUpdatedNorDeleted() {
        val unknown = ServiceId.generate()

        assertContains(definitions.notFound(ServiceChanges.update(unknown) { it.copy(name = "Clients") }), unknown.value)
        assertContains(definitions.notFound(ServiceChanges.delete(unknown)), unknown.value)
    }

    @Test
    fun theIdentifierOfAServiceCannotBeChanged() {
        assertFailsWith<IllegalArgumentException> {
            ServiceChanges.update(customers.value.id) { it.copy(id = ServiceId.generate()) }(definitions)
        }
        assertFailsWith<IllegalArgumentException> {
            ServiceChanges.create { it.copy(id = ServiceId.generate(), name = "Billing", mockUrl = "billing") }(definitions)
        }
    }

    @Test
    fun theScenariosOfAServiceAreNotChangedTogetherWithItsAttributes() {
        val scenario = Scenario(id = ScenarioId.generate(), name = "gold")

        assertFailsWith<IllegalArgumentException> {
            ServiceChanges.update(customers.value.id) { it.copy(scenarios = listOf(scenario)) }(definitions)
        }
        assertFailsWith<IllegalArgumentException> {
            ServiceChanges.create { it.copy(name = "Billing", mockUrl = "billing", scenarios = listOf(scenario)) }(definitions)
        }
    }

    @Test
    fun aNewServiceHasNoScenarioToDesignate() {
        val scenario = ScenarioId.generate()

        val errors =
            definitions.rejected(
                ServiceChanges.create {
                    it.copy(
                        name = "Billing",
                        mockUrl = "billing",
                        defaultScenarioId = scenario,
                        errorScenarioId = scenario,
                    )
                },
            )

        assertEquals(listOf("defaultScenarioId", "errorScenarioId"), errors.map { it.field })
    }
}
