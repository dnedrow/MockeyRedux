package io.github.dnedrow.mockey.legacy.xml

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.ResponseMode
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Tags
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Maps an already-read inline Mockey 2.x export document to core definitions. */
public object LegacyXmlDefinitions {
    /** Maps the services and scenarios in [document]. */
    public fun map(document: Document): Definitions =
        Definitions(
            services = document.services().mapIndexed { index, element -> service(element, index) },
        )

    internal fun mapServiceFiles(
        document: Document,
        responseFiles: Map<String, String>,
    ): Definitions {
        responseFiles.forEach { (scenarioId, body) ->
            document.findScenario(scenarioId)?.childElement("scenario_response")?.textContent = body
        }
        return map(document)
    }

    private fun service(
        element: Element,
        index: Int,
    ): Service {
        val scenarios =
            element.childElements("scenario").map { scenario ->
                scenario(scenario)
            }
        val realUrls = element.childElements("real_url").mapNotNull { it.attribute("url")?.takeUnless(String::isBlank) }
        val defaultRealUrlIndex = element.intAttribute("default_real_url_index", 0).coerceIn(0, realUrls.lastIndex.coerceAtLeast(0))
        val requestRules = element.childElement("request_inspector_json_rules")?.textContent?.takeUnless(String::isBlank)
        val responseSchema = element.childElement("response_schema")?.textContent?.takeUnless(String::isBlank)
        return Service(
            id = ServiceId("legacy-service-$index"),
            name = element.attribute("name").orEmpty(),
            mockUrl = element.attribute("url").orEmpty(),
            description = element.attribute("description").legacyValue(),
            realUrls = realUrls,
            defaultRealUrlIndex = defaultRealUrlIndex,
            responseMode = responseMode(element.intAttribute("service_response_type", 0)),
            delayMillis = element.longAttribute("hang_time", 0),
            tags = Tags.of(element.attribute("tag").orEmpty()),
            scenarios = scenarios,
            requestRules = requestRules,
            requestRulesEnabled = element.childElement("request_inspector_json_rules")?.booleanAttribute("enable_flag") ?: false,
            inspectorName = element.attribute("request_inspector_name").legacyValue(),
            responseSchema = responseSchema,
            responseSchemaEnabled = element.childElement("response_schema")?.booleanAttribute("enable_flag") ?: false,
        )
    }

    private fun scenario(element: Element): Scenario {
        val match = element.childElement("scenario_match")
        val matchText = match?.textContent.orEmpty()
        val rules = match?.booleanAttribute("scenario_match_evaluation_rules_flag") ?: false
        val criterion =
            when {
                rules && matchText.isNotBlank() -> MatchCriterion.Rules(matchText)
                !rules && matchText.isNotBlank() -> MatchCriterion.Text(matchText)
                else -> MatchCriterion.None
            }
        return Scenario(
            id = ScenarioId("legacy-scenario-${element.attribute("id").orEmpty().ifBlank { "unknown" }}"),
            name = element.attribute("name").orEmpty(),
            responseBody = element.childElement("scenario_response")?.textContent.orEmpty(),
            statusCode = element.intAttribute("http_resp_status_code", 200),
            headers = parseHeaders(element.childElement("scenario_response_header")?.textContent.orEmpty()),
            matchCriterion = criterion,
            methodRestriction = element.attribute("http_method_type").orEmpty().takeUnless(String::isBlank),
            delayMillis = element.longAttribute("hang_time", 0),
            tags = Tags.of(element.attribute("tag").orEmpty()),
        )
    }

    private fun parseHeaders(text: String): List<Header> =
        text.split('|').mapNotNull { part ->
            val separator = part.indexOf(':')
            if (separator < 0) {
                null
            } else {
                Header(part.substring(0, separator).trim(), part.substring(separator + 1).trim())
            }
        }

    private fun responseMode(value: Int): ResponseMode =
        when (value) {
            1 -> ResponseMode.STATIC
            2 -> ResponseMode.DYNAMIC
            else -> ResponseMode.PROXY
        }

    private fun String?.legacyValue(): String? = this?.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }

    private fun Element.attribute(name: String): String? = getAttribute(name).takeUnless { !hasAttribute(name) }

    private fun Element.intAttribute(
        name: String,
        default: Int,
    ): Int = attribute(name)?.toIntOrNull() ?: default

    private fun Element.longAttribute(
        name: String,
        default: Long,
    ): Long = attribute(name)?.toLongOrNull() ?: default

    private fun Element.booleanAttribute(name: String): Boolean = attribute(name)?.equals("true", ignoreCase = true) == true

    private fun Document.childElements(name: String): List<Element> = documentElement.childElements(name)

    private fun Document.services(): List<Element> =
        if (documentElement.localName == "service" || documentElement.nodeName == "service") {
            listOf(documentElement)
        } else {
            childElements("service")
        }

    private fun Document.findScenario(id: String): Element? =
        services().asSequence().flatMap { it.childElements("scenario").asSequence() }.firstOrNull { it.attribute("id") == id }
}

internal fun Element.childElement(name: String): Element? = childElements(name).firstOrNull()

internal fun Element.childElements(name: String): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.localName == name || it.nodeName == name }
