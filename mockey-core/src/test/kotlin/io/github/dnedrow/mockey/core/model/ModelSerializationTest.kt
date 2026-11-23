package io.github.dnedrow.mockey.core.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ModelSerializationTest {
    private val pretty = Json { prettyPrint = true }

    private val gold =
        Scenario(
            id = ScenarioId("scn-gold"),
            name = "gold customer",
            responseBody = """{"tier":"gold"}""",
            statusCode = 201,
            headers =
                listOf(
                    Header("Set-Cookie", "a=1"),
                    Header("Content-Type", "application/json"),
                    Header("Set-Cookie", "b=2"),
                ),
            matchCriterion = MatchCriterion.Text("ABC123"),
            methodRestriction = "POST",
            delayMillis = 500,
            tags = Tags.of("smoke regression"),
        )
    private val ruled =
        Scenario(id = ScenarioId("scn-rules"), name = "by rules", matchCriterion = MatchCriterion.Rules("""{"url":[]}"""))
    private val failure = Scenario(id = ScenarioId("scn-error"), name = "error")
    private val customers =
        Service(
            id = ServiceId("svc-customers"),
            name = "Customers",
            mockUrl = "customer/{id}",
            description = "Customer directory",
            realUrls = listOf("http://qa1.example.com/customers/{id}", "http://qa2.example.com/customers/{id}"),
            defaultRealUrlIndex = 1,
            responseMode = ResponseMode.DYNAMIC,
            delayMillis = 100,
            tags = Tags.of("crm"),
            scenarios = listOf(gold, ruled, failure),
            defaultScenarioId = gold.id,
            errorScenarioId = failure.id,
            requestRules = """{"headers":[]}""",
            requestRulesEnabled = true,
            inspectorName = "session-check",
            responseSchema = """{"type":"object"}""",
            responseSchemaEnabled = true,
        )
    private val orders = Service(id = ServiceId("svc-orders"), name = "Orders", mockUrl = "http://api.example.com/orders")
    private val plan =
        Plan(
            id = PlanId("plan-gold"),
            name = "Gold path",
            description = "Everything gold",
            tags = Tags.of("demo"),
            items = listOf(PlanItem("Customers", "gold customer", ResponseMode.STATIC, 250), PlanItem("Orders")),
        )
    private val twist =
        TwistConfiguration(
            id = TwistId("twist-qa2"),
            name = "to-qa2",
            pairs = listOf(TwistPair("qa1", "qa2"), TwistPair("example.com", "example.org")),
        )
    private val definitions =
        Definitions(
            services = listOf(customers, orders),
            plans = listOf(plan),
            twists = listOf(twist),
            settings =
                Settings(
                    enabledTwistId = twist.id,
                    outboundProxy = OutboundProxy("http://proxy.example.com:3128", enabled = true),
                    universalErrorScenario = ScenarioReference(customers.id, failure.id),
                    defaultPlanId = plan.id,
                ),
        )

    @Test
    fun identifiersSurviveARenameAndBeingStored() {
        val service =
            Service(
                id = ServiceId.generate(),
                name = "Customers",
                mockUrl = "customer/{id}",
                scenarios = listOf(Scenario(id = ScenarioId.generate(), name = "gold")),
            )
        val plan = Plan(id = PlanId.generate(), name = "Gold path")
        val twist = TwistConfiguration(id = TwistId.generate(), name = "to-qa2")
        val renamed =
            Definitions(
                services = listOf(service.copy(name = "Clients", scenarios = service.scenarios.map { it.copy(name = "premium") })),
                plans = listOf(plan.copy(name = "Premium path")),
                twists = listOf(twist.copy(name = "to-qa3")),
            )

        val loaded = Json.decodeFromString(Definitions.serializer(), Json.encodeToString(Definitions.serializer(), renamed))

        assertEquals(service.id, loaded.services.single().id)
        assertEquals("Clients", loaded.services.single().name)
        assertEquals(
            service.scenarios.single().id,
            loaded.services
                .single()
                .scenarios
                .single()
                .id,
        )
        assertEquals(
            "premium",
            loaded.services
                .single()
                .scenarios
                .single()
                .name,
        )
        assertEquals(plan.id, loaded.plans.single().id)
        assertEquals(twist.id, loaded.twists.single().id)
    }

    @Test
    fun definitionsAreReadBackAsTheyWereWritten() {
        val text = Json.encodeToString(Definitions.serializer(), definitions)

        assertEquals(definitions, Json.decodeFromString(Definitions.serializer(), text))
    }

    @Test
    fun writingWhatWasReadGivesTheSameText() {
        val text = pretty.encodeToString(Definitions.serializer(), definitions)

        assertEquals(text, pretty.encodeToString(Definitions.serializer(), pretty.decodeFromString(Definitions.serializer(), text)))
    }

    @Test
    fun definitionsAreWrittenInThisForm() {
        val expected =
            """
            {
                "services": [
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
                        "scenarios": [
                            {
                                "id": "scn-gold",
                                "name": "gold customer",
                                "responseBody": "{\"tier\":\"gold\"}",
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
                                ]
                            },
                            {
                                "id": "scn-rules",
                                "name": "by rules",
                                "matchCriterion": {
                                    "type": "rules",
                                    "source": "{\"url\":[]}"
                                }
                            },
                            {
                                "id": "scn-error",
                                "name": "error"
                            }
                        ],
                        "defaultScenarioId": "scn-gold",
                        "errorScenarioId": "scn-error",
                        "requestRules": "{\"headers\":[]}",
                        "requestRulesEnabled": true,
                        "inspectorName": "session-check",
                        "responseSchema": "{\"type\":\"object\"}",
                        "responseSchemaEnabled": true
                    },
                    {
                        "id": "svc-orders",
                        "name": "Orders",
                        "mockUrl": "http://api.example.com/orders"
                    }
                ],
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
                            },
                            {
                                "origination": "example.com",
                                "destination": "example.org"
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

        assertEquals(expected, pretty.encodeToString(Definitions.serializer(), definitions))
    }

    @Test
    fun attributesThatHaveTheirDefaultValueAreLeftOut() {
        assertEquals(
            """{"id":"svc-orders","name":"Orders","mockUrl":"http://api.example.com/orders"}""",
            Json.encodeToString(Service.serializer(), orders),
        )
        assertEquals("""{"id":"scn-error","name":"error"}""", Json.encodeToString(Scenario.serializer(), failure))
        assertEquals("{}", Json.encodeToString(Definitions.serializer(), Definitions.EMPTY))
        assertEquals(Definitions.EMPTY, Json.decodeFromString(Definitions.serializer(), "{}"))
    }

    @Test
    fun tagsOfServicesScenariosAndPlansAreNormalizedWhenTheyAreRead() {
        val text =
            """
            {
                "services": [
                    {
                        "id": "svc-1",
                        "name": "Customers",
                        "mockUrl": "customer/{id}",
                        "tags": ["Regression  smoke", "REGRESSION"],
                        "scenarios": [{"id": "scn-1", "name": "gold", "tags": ["Smoke REGRESSION"]}]
                    }
                ],
                "plans": [{"id": "plan-1", "name": "Gold path", "tags": ["smoke", "Regression", "smoke"]}]
            }
            """.trimIndent()

        val loaded = Json.decodeFromString(Definitions.serializer(), text)

        val service = loaded.services.single()
        listOf(service.tags, service.scenarios.single().tags, loaded.plans.single().tags).forEach { tags ->
            assertEquals(listOf("regression", "smoke"), tags.toList())
        }
    }

    @Test
    fun aDefinitionWithoutAnIdentifierIsNotRead() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString(Service.serializer(), """{"name":"Orders","mockUrl":"http://api.example.com/orders"}""")
        }
        assertFailsWith<SerializationException> { Json.decodeFromString(Scenario.serializer(), """{"name":"error"}""") }
        assertFailsWith<SerializationException> { Json.decodeFromString(Plan.serializer(), """{"name":"Gold path"}""") }
        assertFailsWith<SerializationException> {
            Json.decodeFromString(TwistConfiguration.serializer(), """{"name":"to-qa2"}""")
        }
    }

    @Test
    fun proxyCredentialsAreNeverWritten() {
        val proxy =
            OutboundProxy("http://proxy.example.com:3128", enabled = true, credentials = ProxyCredentials("jane", "s3cret"))
        val withCredentials = definitions.copy(settings = definitions.settings.copy(outboundProxy = proxy))

        val text = Json.encodeToString(Definitions.serializer(), withCredentials)

        assertFalse("s3cret" in text)
        assertFalse("jane" in text)
        assertEquals(Json.encodeToString(Definitions.serializer(), definitions), text)
        assertNull(
            Json
                .decodeFromString(Definitions.serializer(), text)
                .settings.outboundProxy
                ?.credentials,
        )
    }

    @Test
    fun theTextFormOfCredentialsHidesThePassword() {
        val credentials = ProxyCredentials("jane", "s3cret")
        val proxy = OutboundProxy("http://proxy.example.com:3128", enabled = true, credentials = credentials)

        assertEquals("ProxyCredentials(username=jane, password=***)", credentials.toString())
        assertFalse("s3cret" in proxy.toString())
        assertFalse("s3cret" in Definitions(settings = Settings(outboundProxy = proxy)).toString())
    }

    @Test
    fun eachKindOfMatchCriterionIsWrittenWithItsType() {
        val forms =
            mapOf(
                MatchCriterion.None to """{"type":"none"}""",
                MatchCriterion.Text("ABC123") to """{"type":"text","text":"ABC123"}""",
                MatchCriterion.Rules("not a rule set") to """{"type":"rules","source":"not a rule set"}""",
            )

        forms.forEach { (criterion, text) ->
            assertEquals(text, Json.encodeToString(MatchCriterion.serializer(), criterion))
            assertEquals(criterion, Json.decodeFromString(MatchCriterion.serializer(), text))
        }
    }

    @Test
    fun aBlankTextCriterionIsNotRead() {
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString(MatchCriterion.serializer(), """{"type":"text","text":" "}""")
        }
    }

    @Test
    fun responseModesAreWrittenAsWords() {
        val words = ResponseMode.entries.map { Json.encodeToString(ResponseMode.serializer(), it) }

        assertEquals(listOf("\"proxy\"", "\"static\"", "\"dynamic\""), words)
        assertEquals(ResponseMode.DYNAMIC, Json.decodeFromString(ResponseMode.serializer(), "\"dynamic\""))
    }
}
