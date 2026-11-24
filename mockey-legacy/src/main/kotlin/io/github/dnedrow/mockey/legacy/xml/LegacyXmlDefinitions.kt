package io.github.dnedrow.mockey.legacy.xml

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.MatchCriterion
import io.github.dnedrow.mockey.core.model.OutboundProxy
import io.github.dnedrow.mockey.core.model.Plan
import io.github.dnedrow.mockey.core.model.PlanId
import io.github.dnedrow.mockey.core.model.PlanItem
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
 * are no name of an inspector, no method, and no reference.
 *
 * What is mapped gets identifiers of its own, and a reference of a document is resolved to them. A document
 * refers by number: a service to its default scenario and to its error scenario, and the document to the
 * twist configuration that is enabled, to the default plan, and to the universal error scenario. Mockey 2.x
 * numbers scenarios within their service, and it gives a service no number in a document: it numbers the
 * services from 1 as it loads them. So the service of the universal error scenario is the one at that place
 * among the services of the document. A reference that names nothing is dropped and reported.
 *
 * Scenarios, plans, and twist configurations are in the order of their numbers, which is the order in which
 * Mockey 2.x created them, whatever their order in the document is. Mockey 2.x writes the scenarios of a
 * service in the order of their names.
 */
internal object LegacyXmlDefinitions {
    /** The name of the root element of a document that holds definitions. */
    const val ROOT = "mockservice"

    /** The name of the element of a service. */
    const val SERVICE = "service"

    private const val DEFAULT_STATUS_CODE = 200

    /** The texts for which a flag is on. */
    private val ON = setOf("true", "yes", "y", "on", "1")

    /** The texts that Mockey 2.x writes where there is no value. A blank text is one as well. */
    private val PLACEHOLDERS = setOf("null", "0")

    /**
     * Maps the definitions of a document. [root] is its root element, which holds the plans, the twist
     * configurations, and the settings. [services] are the `service` elements of the document in the order
     * in which Mockey 2.x loads them, with `null` in the place of a service that could not be read.
     */
    fun map(
        root: Element,
        services: List<Element?>,
    ): LegacyImportResult = Mapping().of(root, services)

    /** A service as it was mapped, and the scenarios of it that the numbers of the document name. */
    private class MappedService(
        val service: Service,
        val scenarios: Map<Long, Scenario>,
    )

    /** One mapping of a document. It collects the [issues] of the document. */
    private class Mapping {
        private val issues = ArrayList<LegacyXmlIssue>()

        fun of(
            root: Element,
            services: List<Element?>,
        ): LegacyImportResult {
            val mapped = services.map { it?.let(::service) }
            val plans = numbered(root.childElements("service_plan"), ::plan)
            val twists = numbered(root.childElements("twist_config"), ::twist)
            val settings =
                Settings(
                    enabledTwistId = enabledTwist(root, twists.toMap())?.id,
                    outboundProxy = root.childElement("proxy_settings")?.let(::outboundProxy),
                    universalErrorScenario = universalErrorScenario(root, mapped),
                    defaultPlanId = defaultPlan(root, plans.toMap())?.id,
                )
            val definitions = Definitions(mapped.mapNotNull { it?.service }, plans.map { it.second }, twists.map { it.second }, settings)
            return LegacyImportResult(definitions, issues.toList())
        }

        // Services and scenarios

        private fun service(element: Element): MappedService {
            val name = element.attribute("name").orEmpty()
            val realUrls = realUrls(element)
            val requestRules = element.childElement("request_inspector_json_rules")
            val responseSchema = element.childElement("response_schema")
            val scenarios = numbered(element.childElements("scenario"), ::scenario)
            val byNumber = scenarios.toMap()
            val service =
                Service(
                    id = ServiceId.generate(),
                    name = name,
                    mockUrl = element.attribute("url").orEmpty(),
                    description = element.attribute("description")?.takeIf { it.isNotBlank() },
                    realUrls = realUrls,
                    defaultRealUrlIndex = element.number("default_real_url_index")?.takeIf { it in realUrls.indices } ?: 0,
                    responseMode = responseMode(element.number("service_response_type")),
                    delayMillis = delay(element),
                    tags = Tags.of(element.attribute("tag").orEmpty()),
                    scenarios = scenarios.map { it.second },
                    defaultScenarioId = designated(element, "default_scenario_id", "default scenario", byNumber, DEFAULT_IS_EARLIEST)?.id,
                    errorScenarioId = designated(element, "error_scenario_id", "error scenario", byNumber, NO_ERROR_SCENARIO)?.id,
                    requestRules = requestRules?.text()?.takeIf { it.isNotEmpty() },
                    requestRulesEnabled = requestRules?.flag("enable_flag") ?: false,
                    inspectorName = element.attribute("request_inspector_name")?.takeUnless(::isPlaceholder),
                    responseSchema = responseSchema?.text()?.takeIf { it.isNotEmpty() },
                    responseSchemaEnabled = responseSchema?.flag("enable_flag") ?: false,
                )
            return MappedService(service, byNumber)
        }

        /**
         * Returns the scenario of a service that the attribute [attribute] of the service designates as its
         * [role], or `null` when it designates none. A number that no scenario of the service has is reported,
         * with what becomes of the role: [otherwise].
         */
        private fun designated(
            service: Element,
            attribute: String,
            role: String,
            scenarios: Map<Long, Scenario>,
            otherwise: String,
        ): Scenario? {
            val reference = service.reference(attribute) ?: return null
            val scenario = reference.toLongOrNull()?.let(scenarios::get)
            if (scenario == null) {
                val name = service.attribute("name").orEmpty()
                dangling("The service '$name' names the scenario $reference as its $role ($attribute), $NO_SUCH_SCENARIO $otherwise")
            }
            return scenario
        }

        /**
         * Returns the real URLs of a service as Mockey 2.x lists them: the URL of the attribute `proxyurl`, in
         * which old documents name the one real URL of a service, and then the URLs of the `real_url` elements,
         * without a URL that is blank and without one that is there already. The default real URL index counts
         * in this list.
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
         * Splits the header text of a scenario into headers: at each `|` into parts, and each part at its first
         * `:` into a name and a value. A part without a `:` is no header.
         */
        private fun headers(text: String): List<Header> =
            text.split('|').mapNotNull { part ->
                val colon = part.indexOf(':')
                if (colon < 0) null else Header(part.substring(0, colon).trim(), part.substring(colon + 1).trim())
            }

        // Plans, twist configurations, and settings

        private fun plan(element: Element): Plan =
            Plan(
                id = PlanId.generate(),
                name = element.attribute("name").orEmpty(),
                description = element.attribute("description")?.takeIf { it.isNotBlank() },
                tags = Tags.of(element.attribute("tag").orEmpty()),
                items =
                    element.childElements("plan_item").map { item ->
                        PlanItem(
                            serviceName = item.attribute("service_name").orEmpty(),
                            scenarioName = item.attribute("scenario_name")?.takeIf { it.isNotBlank() },
                            responseMode = responseMode(item.number("service_response_type")),
                            delayMillis = delay(item),
                        )
                    },
            )

        private fun twist(element: Element): TwistConfiguration =
            TwistConfiguration(
                id = TwistId.generate(),
                name = element.attribute("name").orEmpty(),
                pairs =
                    element.childElements("twist_pattern").map { pair ->
                        TwistPair(pair.attribute("origination").orEmpty(), pair.attribute("destination").orEmpty())
                    },
            )

        /** Returns the settings of the outbound proxy, or `null` for settings without a URL, which Mockey 2.x writes for none. */
        private fun outboundProxy(element: Element): OutboundProxy? {
            val url = element.attribute("proxy_url").orEmpty().trim()
            return if (url.isEmpty()) null else OutboundProxy(url, enabled = element.flag("proxy_enabled"))
        }

        private fun enabledTwist(
            root: Element,
            twists: Map<Long, TwistConfiguration>,
        ): TwistConfiguration? {
            val reference = root.reference(ENABLED_TWIST) ?: return null
            return reference.toLongOrNull()?.let(twists::get)
                ?: dangling("The document enables the twist configuration $reference ($ENABLED_TWIST), $NO_SUCH_TWIST")
        }

        private fun defaultPlan(
            root: Element,
            plans: Map<Long, Plan>,
        ): Plan? {
            val reference = root.reference(DEFAULT_PLAN) ?: return null
            return reference.toLongOrNull()?.let(plans::get)
                ?: dangling("The document names the plan $reference as the default plan ($DEFAULT_PLAN), $NO_SUCH_PLAN")
        }

        /**
         * Returns the universal error scenario that the document designates: the scenario with the number of
         * the one attribute, of the service at the place that the other attribute names. [services] are the
         * services of the document at their places.
         */
        private fun universalErrorScenario(
            root: Element,
            services: List<MappedService?>,
        ): ScenarioReference? {
            val place = root.reference(UNIVERSAL_ERROR_SERVICE)
            val number = root.reference(UNIVERSAL_ERROR_SCENARIO)
            if (place == null && number == null) return null
            val index = place?.toLongOrNull()?.let(::placeOf)?.takeIf { it in services.indices }
            val service = index?.let(services::get)
            val scenario = number?.toLongOrNull()?.let { service?.scenarios?.get(it) }
            if (service != null && scenario != null) return ScenarioReference(service.service.id, scenario.id)
            val why =
                when {
                    place == null -> "but it names no service ($UNIVERSAL_ERROR_SERVICE)."
                    number == null -> "but it names no scenario ($UNIVERSAL_ERROR_SCENARIO)."
                    index == null -> "but it holds no service at the place $place. It holds ${services.size} of them."
                    service == null -> "but the service at the place $place could not be read."
                    else -> "but the service '${service.service.name}' at the place $place has no scenario with the number $number."
                }
            val named = "the scenario ${number ?: "without a number"} of the service ${place ?: "without a number"}"
            return dangling("The document names $named as the universal error scenario, $why $NO_UNIVERSAL_ERROR")
        }

        /**
         * Returns the place in a list of the service that a document numbers [number]. Mockey 2.x counts the
         * services from 1. A number that is no place of any list gives -1.
         */
        private fun placeOf(number: Long): Int = if (number in 1..Int.MAX_VALUE) (number - 1).toInt() else -1

        /** Reports a reference that names nothing, and returns `null` for what it names. */
        private fun dangling(message: String): Nothing? {
            issues += LegacyXmlIssue(LegacyXmlIssueKind.DANGLING_REFERENCE, message)
            return null
        }
    }

    /**
     * Returns what [map] makes of [elements], each with the number that Mockey 2.x gives its element when it
     * loads the document, in the order of the numbers. An element has the number of its `id` attribute, and
     * an element without one gets the number after the highest number so far. Where two elements have one
     * number, Mockey 2.x keeps the later one, so that is the one that the number names.
     */
    private fun <T> numbered(
        elements: List<Element>,
        map: (Element) -> T,
    ): List<Pair<Long, T>> {
        var highest = 0L
        return elements
            .map { element ->
                val number = element.attribute("id")?.trim()?.toLongOrNull() ?: (highest + 1)
                highest = maxOf(highest, number)
                number to map(element)
            }.sortedBy { it.first }
    }

    private fun responseMode(number: Int?): ResponseMode =
        when (number) {
            1 -> ResponseMode.STATIC
            2 -> ResponseMode.DYNAMIC
            else -> ResponseMode.PROXY
        }

    /** Returns the delay of a service, of a scenario, or of a plan item in milliseconds. A delay that is no number, or one below 0, is none. */
    private fun delay(element: Element): Long = element.number("hang_time")?.coerceAtLeast(0)?.toLong() ?: 0

    /** Tells whether [value] is what Mockey 2.x writes where there is no value. */
    private fun isPlaceholder(value: String): Boolean = value.isBlank() || value.trim().lowercase() in PLACEHOLDERS

    /** The number that the attribute [name] holds, or `null` when it holds none. */
    private fun Element.number(name: String): Int? = attribute(name)?.trim()?.toIntOrNull()

    private fun Element.flag(name: String): Boolean = attribute(name)?.trim()?.lowercase() in ON

    /** The text with which the attribute [name] refers to something, or `null` when it refers to nothing. */
    private fun Element.reference(name: String): String? = attribute(name)?.trim()?.takeUnless(::isPlaceholder)

    private const val ENABLED_TWIST = "universal_twist_info_id"
    private const val DEFAULT_PLAN = "default_service_plan_id"
    private const val UNIVERSAL_ERROR_SERVICE = "universal_error_service_id"
    private const val UNIVERSAL_ERROR_SCENARIO = "universal_error_scenario_id"

    private const val NO_SUCH_SCENARIO = "but it has no scenario with that number."
    private const val DEFAULT_IS_EARLIEST = "The earliest scenario of the service is its default scenario."
    private const val NO_ERROR_SCENARIO = "The service has no error scenario."
    private const val NO_SUCH_TWIST = "but it holds no twist configuration with that number. No twist configuration is enabled."
    private const val NO_SUCH_PLAN = "but it holds no plan with that number. No plan is the default plan."
    private const val NO_UNIVERSAL_ERROR = "No universal error scenario is designated."
}
