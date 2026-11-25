package io.github.dnedrow.mockey.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AdminApiModelsTest {
    private val json = AdminApiJson.instance

    @Test
    fun serializesEveryWireTypeWithItsContractFields() {
        assertPrimitive(ResponseModeDto.PROXY, "proxy")
        assertPrimitive(ResponseModeDto.STATIC, "static")
        assertPrimitive(ResponseModeDto.DYNAMIC, "dynamic")
        assertShape<MatchCriterionDto>(MatchCriterionDto.None, setOf("type"))
        assertShape<MatchCriterionDto>(MatchCriterionDto.Text("needle"), setOf("type", "text"))
        assertShape<MatchCriterionDto>(MatchCriterionDto.Rules("{}"), setOf("type", "source"))
        assertDiscriminator(MatchCriterionDto.None, "none")
        assertDiscriminator(MatchCriterionDto.Text("needle"), "text")
        assertDiscriminator(MatchCriterionDto.Rules("{}"), "rules")
        assertShape(HeaderDto("X-Test", "yes"), setOf("name", "value"))
        assertShape(
            ScenarioDto("s1", "scenario", "body", 201, listOf(HeaderDto("X-Test", "yes")), MatchCriterionDto.None),
            setOf("id", "name", "responseBody", "statusCode", "headers", "matchCriterion", "methodRestriction", "delayMillis", "tags"),
        )
        assertShape(
            ServiceDto("svc1", "service", "/service/example"),
            setOf(
                "id",
                "name",
                "mockUrl",
                "description",
                "realUrls",
                "defaultRealUrlIndex",
                "responseMode",
                "effectiveResponseMode",
                "delayMillis",
                "tags",
                "scenarios",
                "defaultScenarioId",
                "errorScenarioId",
                "requestRules",
                "requestRulesEnabled",
                "inspectorName",
                "responseSchema",
                "responseSchemaEnabled",
            ),
        )
        assertShape(
            ServiceDefinitionDto("svc1", "service", "/service/example"),
            setOf(
                "id",
                "name",
                "mockUrl",
                "description",
                "realUrls",
                "defaultRealUrlIndex",
                "responseMode",
                "delayMillis",
                "tags",
                "scenarios",
                "defaultScenarioId",
                "errorScenarioId",
                "requestRules",
                "requestRulesEnabled",
                "inspectorName",
                "responseSchema",
                "responseSchemaEnabled",
            ),
        )
        assertShape(
            ScenarioAttributesDto(),
            setOf("name", "responseBody", "statusCode", "headers", "matchCriterion", "methodRestriction", "delayMillis", "tags"),
        )
        assertShape(
            ServiceAttributesDto(),
            setOf(
                "name",
                "mockUrl",
                "description",
                "realUrls",
                "defaultRealUrlIndex",
                "responseMode",
                "delayMillis",
                "tags",
                "defaultScenarioId",
                "errorScenarioId",
                "requestRules",
                "requestRulesEnabled",
                "inspectorName",
                "responseSchema",
                "responseSchemaEnabled",
            ),
        )
        assertShape(ScenarioReferenceDto("svc1", "s1"), setOf("serviceId", "scenarioId"))
        assertShape(ServiceMergeRequestDto(listOf("svc2")), setOf("sourceIds"))
        assertShape(RealUrlDerivationRequestDto("qa1", listOf("qa2")), setOf("expression", "replacements"))
        assertShape(DerivedRealUrlDto("svc1", "service", "http://qa2.example.com"), setOf("serviceId", "serviceName", "realUrl"))
        assertShape(PlanItemDto("service"), setOf("serviceName", "scenarioName", "responseMode", "delayMillis"))
        assertShape(PlanDto("p1", "plan"), setOf("id", "name", "description", "tags", "items"))
        assertShape(PlanAttributesDto(), setOf("name", "description", "tags", "items"))
        assertShape(PlanSaveRequestDto(listOf("svc1")), setOf("serviceIds", "name", "description", "tags"))
        assertShape(PlanReferenceDto("p1"), setOf("planId"))
        assertShape(TwistPairDto("from", "to"), setOf("origination", "destination"))
        assertShape(TwistConfigurationDto("t1", "twist"), setOf("id", "name", "pairs"))
        assertShape(TwistAttributesDto(), setOf("name", "pairs"))
        assertShape(OutboundProxyDefinitionDto("http://proxy", true), setOf("url", "enabled"))
        assertShape(
            DefinitionSettingsDto(),
            setOf("enabledTwistId", "outboundProxy", "universalErrorServiceId", "universalErrorScenarioId", "defaultPlanId"),
        )
        assertShape(
            DefinitionDocumentDto(),
            setOf("formatVersion", "services", "plans", "twists", "settings"),
        )
        assertShape(OutboundProxyDto("http://proxy", true, "user", true), setOf("url", "enabled", "username", "passwordSet"))
        assertShape(OutboundProxyAttributesDto("http://proxy", true, "user", "secret"), setOf("url", "enabled", "username", "password"))
        assertShape(TagFilterDto(listOf("orders")), setOf("tags"))
        assertShape(NameValueDto("name", "value"), setOf("name", "value"))
        assertShape(RecordedBodyDto("AQI=", 2, false), setOf("base64", "size", "isCut"))
        assertShape(
            RecordedRequestDto("GET", "/", body = RecordedBodyDto("", 0, false)),
            setOf("method", "url", "query", "headers", "parameters", "cookies", "body"),
        )
        assertShape(RecordedResponseDto(200, body = RecordedBodyDto("", 0, false)), setOf("status", "headers", "body"))
        assertShape(RecordedServiceDto("svc1", "service"), setOf("id", "name", "tags"))
        assertShape(RecordedScenarioDto("s1", "scenario"), setOf("id", "name", "tags"))
        assertShape(
            HistoryEntryDto(
                1,
                "2025-01-01T00:00:00Z",
                request = RecordedRequestDto("GET", "/", body = RecordedBodyDto("", 0, false)),
                response = RecordedResponseDto(200, body = RecordedBodyDto("", 0, false)),
                target = "/",
                outcome = "PROXIED",
            ),
            setOf(
                "id",
                "time",
                "clientAddress",
                "request",
                "response",
                "target",
                "targetBeforeTwisting",
                "service",
                "responseMode",
                "scenario",
                "outcome",
                "inspectionMessages",
                "explanation",
                "flagged",
            ),
        )
        assertShape(ReplayRequestDto(bodyBase64 = "AQI="), setOf("headers", "parameters", "bodyBase64"))
        assertShape(ServiceStatisticsDto("svc1", "service", 1), setOf("serviceId", "serviceName", "count", "earliestEntryTime"))
        assertShape(HistoryStatisticsDto(emptyList()), setOf("services", "oldestEntryTime", "from", "to"))
        assertShape(InspectorDto("inspector", false), setOf("name", "global"))
        assertShape(
            DiagnosticDto("kind", "svc1", "service", "message"),
            setOf("kind", "serviceId", "serviceName", "message", "otherServiceId", "scenarioIds"),
        )
        assertShape(SearchResultDto("service", "svc1", "service"), setOf("type", "id", "name", "serviceId", "serviceName"))
        assertShape(FieldErrorDto("name", "required"), setOf("field", "message"))
        assertShape(ProblemDto("about:blank", "Bad Request", 400, "Invalid JSON"), setOf("type", "title", "status", "detail", "errors"))
        assertShape(MergedScenarioDto("service", "scenario"), setOf("serviceName", "scenarioName"))
        assertShape(MergeReportDto(emptyList(), emptyList(), false), setOf("copied", "skipped", "sourcesDeleted"))
        assertShape(UnresolvedPlanScenarioDto("service", "scenario"), setOf("serviceName", "scenarioName"))
        assertShape(
            PlanApplyReportDto(PlanDto("p1", "plan"), emptyList(), emptyList()),
            setOf("plan", "skippedItems", "unresolvedScenarios"),
        )
        assertShape(ImportItemDto("service", "service", "added"), setOf("kind", "name", "message", "serviceName"))
        assertShape(ImportReportDto(emptyList(), emptyList()), setOf("additions", "conflicts", "appliedDefaultPlan"))
        assertShape<SchemaReportDto>(SchemaReportDto.Checked(emptyList()), setOf("type", "scenarios"))
        assertShape<SchemaReportDto>(SchemaReportDto.UnsupportedDialect("draft"), setOf("type", "dialect"))
        assertShape<SchemaReportDto>(SchemaReportDto.UnusableSchema(listOf("invalid")), setOf("type", "problems"))
        assertDiscriminator(SchemaReportDto.Checked(emptyList()), "checked")
        assertDiscriminator(SchemaReportDto.UnsupportedDialect("draft"), "unsupportedDialect")
        assertDiscriminator(SchemaReportDto.UnusableSchema(listOf("invalid")), "unusableSchema")
        assertShape(
            ScenarioConformanceDto("s1", "scenario", true, emptyList()),
            setOf("scenarioId", "scenarioName", "conforms", "violations"),
        )
        assertShape(
            SystemStatusDto(
                "3.0",
                "2025-01-01T00:00:00Z",
                "/definitions",
                false,
                emptyList(),
                mockBaseUrl = "http://localhost",
                legacyShimEnabled = false,
            ),
            setOf(
                "version",
                "startTime",
                "definitionsLocation",
                "readOnly",
                "tagFilter",
                "enabledTwist",
                "mockBaseUrl",
                "legacyShimEnabled",
            ),
        )
    }

    private inline fun <reified T> assertShape(
        value: T,
        expectedKeys: Set<String>,
    ) {
        val element = json.encodeToJsonElement(serializer<T>(), value)
        assertEquals(expectedKeys, assertIs<JsonObject>(element).keys)
        assertEquals(value, json.decodeFromJsonElement(serializer<T>(), element))
    }

    private fun assertPrimitive(
        value: ResponseModeDto,
        expected: String,
    ) {
        val element = json.encodeToJsonElement(serializer<ResponseModeDto>(), value)
        assertEquals(JsonPrimitive(expected), element)
        assertEquals(value, json.decodeFromJsonElement(serializer<ResponseModeDto>(), element))
    }

    private fun assertDiscriminator(
        value: MatchCriterionDto,
        expected: String,
    ) {
        assertEquals(
            JsonPrimitive(expected),
            json.encodeToJsonElement(serializer<MatchCriterionDto>(), value).let {
                assertIs<JsonObject>(it)["type"]
            },
        )
    }

    private fun assertDiscriminator(
        value: SchemaReportDto,
        expected: String,
    ) {
        assertEquals(
            JsonPrimitive(expected),
            json.encodeToJsonElement(serializer<SchemaReportDto>(), value).let {
                assertIs<JsonObject>(it)["type"]
            },
        )
    }
}
