package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.DiagnosticDto
import io.github.dnedrow.mockey.api.HeaderDto
import io.github.dnedrow.mockey.api.InspectorDto
import io.github.dnedrow.mockey.api.MatchCriterionDto
import io.github.dnedrow.mockey.api.OutboundProxyDto
import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.PlanItemDto
import io.github.dnedrow.mockey.api.ResponseModeDto
import io.github.dnedrow.mockey.api.ScenarioConformanceDto
import io.github.dnedrow.mockey.api.ScenarioDto
import io.github.dnedrow.mockey.api.ScenarioReferenceDto
import io.github.dnedrow.mockey.api.SchemaReportDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.github.dnedrow.mockey.api.TwistConfigurationDto
import io.github.dnedrow.mockey.api.TwistPairDto

private const val OK = 200

/**
 * Makes the stand-in state [text] wherever a server states a text: as every identifier, every name, every
 * URL, every tag, every body, and every time. It holds one service with two scenarios, one diagnostic, one
 * plan, one twist configuration, proxy settings, and one history entry. Returns the live view of the
 * services.
 *
 * The tests use it with the texts of [Hostile], which are written to be read as markup or as an address.
 * What the stand-in held before is replaced.
 */
internal fun FakeAdminApi.holdHostile(text: String): MutableMap<String, ServiceDto> {
    val headers = listOf(HeaderDto(text, text))
    val asText = ScenarioDto(text, text, text, OK, headers, MatchCriterionDto.Text(text), text, 5, listOf(text), text)
    val asRules = Samples.missing.copy(name = text, matchCriterion = MatchCriterionDto.Rules(text))
    val service =
        ServiceDto(
            id = text,
            name = text,
            mockUrl = text,
            description = text,
            realUrls = listOf(text, "$text-2"),
            tags = listOf(text),
            scenarios = listOf(asText, asRules),
            defaultScenarioId = text,
            errorScenarioId = text,
            requestRules = text,
            inspectorName = text,
            responseSchema = text,
            lastUsedTime = text,
        )
    val diagnostic = DiagnosticDto(text, text, text, text, text, listOf(text))
    val paths = mapOf(text to Hostile.written(text))
    val inspectors = listOf(InspectorDto(text, global = true))
    val conformance = ScenarioConformanceDto(text, text, conforms = false, listOf(text))
    val schemaReports = mapOf(text to SchemaReportDto.Checked(listOf(conformance)))
    val held = hold(listOf(service), listOf(diagnostic), paths, inspectors, ScenarioReferenceDto(text, text), schemaReports)
    val twist = TwistConfigurationDto(text, text, listOf(TwistPairDto(text, text)))
    system = SystemStatusDto(text, text, text, readOnly = false, listOf(text), twist, text, legacyShimEnabled = true)
    val plan = PlanDto(text, text, text, listOf(text), listOf(PlanItemDto(text, text, ResponseModeDto.STATIC, 5)), text)
    val plans = holdPlans(held, listOf(plan), default = text, paths = paths)
    holdTwists(listOf(twist), enabled = text, paths = paths)
    holdTags(held, plans, paths)
    holdProxy(OutboundProxyDto(text, enabled = true, username = text, passwordSet = true))
    holdHistory(listOf(Hostile.entry(1, text)), held)
    holdDefinitions(held, plans)
    holdSystem(held, plans)
    return held
}
