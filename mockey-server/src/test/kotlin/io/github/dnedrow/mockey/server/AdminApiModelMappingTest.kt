package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.DefinitionSettingsDto
import io.github.dnedrow.mockey.api.OutboundProxyUpdateDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.PlanId
import io.github.dnedrow.mockey.core.model.PlanItem
import io.github.dnedrow.mockey.core.model.ProxyCredentials
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.ScenarioReference
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Settings
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistConfiguration
import io.github.dnedrow.mockey.core.model.TwistId
import io.github.dnedrow.mockey.core.model.TwistPair
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AdminApiModelMappingTest {
    @Test
    fun definitionsMapToAndFromWireTypesWithoutProxyCredentials() {
        val definitions = definitions()

        val document = definitions.toDefinitionDocumentDto()
        val restored = document.toModel()

        assertEquals(
            definitions.copy(settings = definitions.settings.copy(outboundProxy = OutboundProxy("http://proxy.example", true))),
            restored,
        )
        val json = AdminApiJson.instance.encodeToString(document)
        assertFalse(json.contains("secret-password"))
        assertFalse(json.contains("passwordSet"))
        assertEquals("http://proxy.example", document.settings.outboundProxy?.url)
    }

    @Test
    fun servicesMapBothWaysAndDeriveTheirEffectiveResponseMode() {
        val service = definitions().services.single()

        val dto = service.toAdminDto()

        assertEquals(ResponseModeDto.DYNAMIC, dto.effectiveResponseMode)
        assertEquals(service, dto.toModel())
    }

    @Test
    fun proxyResponsesExposeUsernameAndPasswordPresenceButNeverThePassword() {
        val proxy = OutboundProxy("http://proxy.example", true, ProxyCredentials("proxy-user", "secret-password"))

        val dto = proxy.toAdminDto()
        val json = AdminApiJson.instance.encodeToString(dto)

        assertEquals("proxy-user", dto.username)
        assertEquals(true, dto.passwordSet)
        assertContains(json, "proxy-user")
        assertFalse(json.contains("secret-password"))
        assertEquals(proxy, OutboundProxyUpdateDto("http://proxy.example", true, "proxy-user", "secret-password").toModel())
    }

    @Test
    fun inconsistentUniversalErrorReferencesAndUnsupportedDefinitionVersionsAreRejected() {
        assertFailsWith<IllegalArgumentException> { DefinitionSettingsDto(universalErrorServiceId = "service-1").toModel() }
        assertFailsWith<IllegalArgumentException> { Definitions().toDefinitionDocumentDto().copy(formatVersion = 2).toModel() }
    }

    private fun definitions(): Definitions {
        val scenario =
            Scenario(
                id = ScenarioId("scenario-1"),
                name = "created",
                responseBody = "created body",
                statusCode = 201,
                matchCriterion = MatchCriterion.Text("create"),
                tags = Tags.of("Created", "orders"),
            )
        val service =
            Service(
                id = ServiceId("service-1"),
                name = "orders",
                mockUrl = "/service/orders",
                realUrls = listOf("https://orders.example"),
                responseMode = ResponseMode.DYNAMIC,
                tags = Tags.of("ORDERS"),
                scenarios = listOf(scenario),
                defaultScenarioId = scenario.id,
                requestRules = "{}",
                requestRulesEnabled = true,
                responseSchema = "{}",
                responseSchemaEnabled = true,
            )
        val plan = Plan(PlanId("plan-1"), "all orders", items = listOf(PlanItem(service.name, scenario.name, ResponseMode.DYNAMIC)))
        val twist = TwistConfiguration(TwistId("twist-1"), "to test", listOf(TwistPair("orders.example", "test.example")))
        return Definitions(
            services = listOf(service),
            plans = listOf(plan),
            twists = listOf(twist),
            settings =
                Settings(
                    enabledTwistId = twist.id,
                    outboundProxy = OutboundProxy("http://proxy.example", true, ProxyCredentials("proxy-user", "secret-password")),
                    universalErrorScenario = ScenarioReference(service.id, scenario.id),
                    defaultPlanId = plan.id,
                ),
        )
    }
}
