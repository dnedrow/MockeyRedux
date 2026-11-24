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
import org.w3c.dom.Element

/**
 * Maps the elements of Mockey 2.x documents to definitions.
 *
 * An element is read as the loader of Mockey 2.x reads it. The text of an element counts without the
 * whitespace around it, so a response body has none at its start and none at its end. A number that cannot
 * be read counts as the default of its attribute. A flag is on for the texts `true`, `yes`, `y`, `on`, and
 * `1`, in any letter case.
 *
 * Where Mockey 2.x writes that there is no value, there is none: the texts `null` and `0` and an empty text
 * are no name of an inspector and no method.
 *
 * What is mapped gets identifiers of its own. A legacy document gives its services none, and it numbers its
 * scenarios within each service, so its numbers identify nothing beyond the document.
 */
internal object LegacyXmlDefinitions {
    /** The name of the root element of a document that holds definitions. */
    const val ROOT = "mockservice"

    /** The name of the element of a service. */
    const val SERVICE = "service"

    private const val DEFAULT_STATUS_CODE = 200

    /** The texts for which a flag is on. */
    private val ON = setOf("true", "yes", "y", "on", "1")

    /** Maps the `service` elements [services] with their scenarios to definitions, in their order. */
    fun map(services: List<Element>): Definitions = Definitions(services = services.map(::service))

    private fun service(element: Element): Service {
        val realUrls = realUrls(element)
        val requestRules = element.childElement("request_inspector_json_rules")
        val responseSchema = element.childElement("response_schema")
        return Service(
            id = ServiceId.generate(),
            name = element.attribute("name").orEmpty(),
            mockUrl = element.attribute("url").orEmpty(),
            description = element.attribute("description")?.takeIf { it.isNotBlank() },
            realUrls = realUrls,
            defaultRealUrlIndex = element.number("default_real_url_index")?.takeIf { it in realUrls.indices } ?: 0,
            responseMode = responseMode(element.number("service_response_type")),
            delayMillis = delay(element),
            tags = Tags.of(element.attribute("tag").orEmpty()),
            scenarios = element.childElements("scenario").map(::scenario),
            requestRules = requestRules?.text()?.takeIf { it.isNotEmpty() },
            requestRulesEnabled = requestRules?.flag("enable_flag") ?: false,
            inspectorName = element.attribute("request_inspector_name")?.takeUnless(::isPlaceholder),
            responseSchema = responseSchema?.text()?.takeIf { it.isNotEmpty() },
            responseSchemaEnabled = responseSchema?.flag("enable_flag") ?: false,
        )
    }

    /**
     * Returns the real URLs of a service as Mockey 2.x lists them: the URL of the attribute `proxyurl`, in which
     * old documents name the one real URL of a service, and then the URLs of the `real_url` elements, without
     * a URL that is blank and without one that is there already. The default real URL index counts in this list.
     */
    private fun realUrls(service: Element): List<String> =
        (listOfNotNull(service.attribute("proxyurl")) + service.childElements("real_url").mapNotNull { it.attribute("url") })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

    private fun scenario(element: Element): Scenario {
        val match = element.childElement("scenario_match")
        val matchText = match?.text().orEmpty()
        return Scenario(
            id = ScenarioId.generate(),
            name = element.attribute("name").orEmpty(),
            responseBody = element.childElement("scenario_response")?.text().orEmpty(),
            statusCode = element.number("http_resp_status_code") ?: DEFAULT_STATUS_CODE,
            headers = headers(element.childElement("scenario_response_header")?.text().orEmpty()),
            matchCriterion =
                when {
                    matchText.isBlank() -> MatchCriterion.None
                    match?.flag("scenario_match_evaluation_rules_flag") == true -> MatchCriterion.Rules(matchText)
                    else -> MatchCriterion.Text(matchText)
                },
            methodRestriction = element.attribute("http_method_type")?.takeUnless(::isPlaceholder),
            delayMillis = delay(element),
            tags = Tags.of(element.attribute("tag").orEmpty()),
        )
    }

    /**
     * Splits the header text of a scenario into headers: at each `|` into parts, and each part at its first `:`
     * into a name and a value. A part without a `:` is no header.
     */
    private fun headers(text: String): List<Header> =
        text.split('|').mapNotNull { part ->
            val colon = part.indexOf(':')
            if (colon < 0) null else Header(part.substring(0, colon).trim(), part.substring(colon + 1).trim())
        }

    private fun responseMode(number: Int?): ResponseMode =
        when (number) {
            1 -> ResponseMode.STATIC
            2 -> ResponseMode.DYNAMIC
            else -> ResponseMode.PROXY
        }

    /** Returns the delay of a service or of a scenario in milliseconds. A delay that is no number, or one below 0, is none. */
    private fun delay(element: Element): Long = element.number("hang_time")?.coerceAtLeast(0)?.toLong() ?: 0

    /** Tells whether [value] is what Mockey 2.x writes where there is no value. */
    private fun isPlaceholder(value: String): Boolean = value.isBlank() || value.trim().lowercase() in setOf("null", "0")

    /** The number that the attribute [name] holds, or `null` when it holds none. */
    private fun Element.number(name: String): Int? = attribute(name)?.trim()?.toIntOrNull()

    private fun Element.flag(name: String): Boolean = attribute(name)?.trim()?.lowercase() in ON
}
