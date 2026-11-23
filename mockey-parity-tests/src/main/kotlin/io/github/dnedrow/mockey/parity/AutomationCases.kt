package io.github.dnedrow.mockey.parity

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Cases for the legacy automation endpoints `/definitions`, `/config/service`, `/plan/setup`, `/home`, and
 * `/status`, recorded from legacy Mockey. Fixtures live in src/test/resources/parity-fixtures.
 *
 * The fixture defines the services `Customers` (1), `Orders` (2), and `Billing` (3) and the plans `Gold path` (1)
 * and `Silver path` (2). The numbers are the identifiers that legacy assigns when it loads the fixture. The new
 * server gives other identifiers, so a case names a service or a plan wherever legacy lets it, and only the
 * cases that are about identifiers use a number.
 */
object AutomationCases {
    /** The fixture that the `/home` initialization cases load. */
    const val INIT_FIXTURE = "parity-fixtures/automation-init.xml"

    /**
     * The name under which [INIT_FIXTURE] must exist in the working directory of legacy Mockey, where legacy
     * resolves a relative `file` parameter. The recording script copies it there.
     */
    const val INIT_DOCUMENT = "suite-a.xml"

    /** The port of the stub that serves [INIT_FIXTURE] to the case that initializes from a URL. */
    const val DOCUMENT_PORT = RoutingCases.UPSTREAM_PORT

    private const val CONFIG = "/config/service"
    private const val PLAN = "/plan/setup"
    private const val INIT = "/home?action=init&type=json&transientState=true"
    private const val CUSTOMERS = "/service/customers"

    private const val GOLD = "$CONFIG?serviceName=Customers&serviceResponseType=static&scenarioName=gold&hangTime=250"
    private const val GOLD_PATH = "service_plan_name=Gold%20path"
    private const val SET_GOLD_PATH = "$PLAN?action=set_plan&$GOLD_PATH"
    private const val SAVE = "$PLAN?action=save_plan&type=json"
    private const val SAVE_EVENING = "$SAVE&service_plan_name=Evening&service_plan_tag=night"
    private const val INIT_FROM_HOSTS = "$INIT&file=%2Fetc%2Fhosts"
    private val initFromUrl = "$INIT&url=${encoded("http://127.0.0.1:$DOCUMENT_PORT/$INIT_DOCUMENT")}"

    private val rules = """{"parameters": [{"key": "ticker", "value_rule_arg": "goog", "value_rule_type": "string_required"}]}"""
    private const val SCHEMA = """{"type": "object", "required": ["id"]}"""

    val groups: List<ParityCaseGroup> =
        listOf(
            parityCaseGroup("automation") {
                get("automation-definitions-all", "/definitions")
                get("automation-definitions-by-name", "/definitions?serviceName=customers")
                get("automation-definitions-by-id", "/definitions?serviceId=2")
                get("automation-definitions-unknown-name", "/definitions?serviceName=Nobody")
                get("automation-definitions-empty", "/definitions", before = step("/home?action=deleteAllServices&type=json"))

                get("automation-config-scenario-by-name", GOLD)
                get("automation-config-effect", CUSTOMERS, timing = ParityTiming(atLeastMillis = 250), before = step(GOLD))
                get("automation-config-numeric-type", "$CONFIG?serviceName=Customers&serviceResponseType=2&scenarioName=missing")
                get("automation-config-by-id", "$CONFIG?serviceId=1&scenarioId=3")
                get("automation-config-proxy-type", "$CONFIG?serviceName=Orders&serviceResponseType=proxy")
                get("automation-config-default-url-index", "$CONFIG?serviceName=Customers&defaultUrlIndex=2")
                get(
                    "automation-config-inspector",
                    "$CONFIG?serviceName=Customers&requestInspectorName=com.mockey.plugin.SampleRequestInspector" +
                        "&requestInspectorJsonRules=${encoded(rules)}&requestInspectorJsonRulesEnableFlag=true",
                )
                get(
                    "automation-config-schema",
                    "$CONFIG?serviceName=Customers&serviceSchema=${encoded(SCHEMA)}&serviceSchemaEnableFlag=true",
                )
                get("automation-config-transient-state", "$CONFIG?serviceName=Customers&transientState=true&hangTime=10")
                get("automation-config-unknown-scenario", "$CONFIG?serviceName=Customers&scenarioName=platinum")
                get("automation-config-unknown-service", "$CONFIG?serviceName=Nobody&hangTime=5")
                post("automation-config-post-form", CONFIG, "serviceName=Customers&hangTime=75", "application/x-www-form-urlencoded")
                get("automation-config-second-path", "/service_scenario?serviceName=Customers&hangTime=5")

                get("automation-plan-set-by-name", SET_GOLD_PATH)
                get("automation-plan-set-by-id", "$PLAN?action=set_plan&plan_id=2")
                get("automation-plan-set-unknown", "$PLAN?action=set_plan&plan_id=99", snapshot = true)
                get("automation-plan-set-effect", CUSTOMERS, before = step(SET_GOLD_PATH))
                get("automation-plan-set-transient-state", "$SET_GOLD_PATH&transient_state=true")
                get("automation-plan-save-new", "$SAVE_EVENING&service_ids%5B%5D=1&service_ids%5B%5D=2")
                get("automation-plan-save-existing-name", "$SAVE&service_plan_name=gold%20path&service_ids%5B%5D=3")
                get("automation-plan-save-without-name", "$SAVE&service_ids%5B%5D=1")
                get(
                    "automation-plan-saved-plan-applies",
                    CUSTOMERS,
                    before =
                        listOf(
                            request("GET", "$CONFIG?serviceName=Customers&scenarioName=gold"),
                            request("GET", "$SAVE_EVENING&service_ids%5B%5D=1"),
                            request("GET", "$CONFIG?serviceName=Customers&scenarioName=silver"),
                            request("GET", "$PLAN?action=set_plan&service_plan_name=Evening"),
                        ),
                )
                get("automation-plan-delete", "$PLAN?action=delete_plan&service_plan_name=Silver%20path")
                get("automation-plan-delete-unknown", "$PLAN?action=delete_plan&plan_id=99")
                get("automation-plan-default", "$PLAN?action=set_as_default_plan&$GOLD_PATH")
                get("automation-plan-default-none", "$PLAN?action=set_as_default_plan&plan_id=none")
                get("automation-plan-default-unknown", "$PLAN?action=set_as_default_plan&plan_id=99")

                get("automation-home-init", "$INIT&file=$INIT_DOCUMENT")
                get("automation-home-init-effect", "/definitions", before = step("$INIT&file=$INIT_DOCUMENT"))
                get("automation-home-init-missing-file", "$INIT&file=missing.xml")
                get("automation-home-init-not-a-document", INIT_FROM_HOSTS)
                get("automation-home-init-not-a-document-effect", "/definitions", before = step(INIT_FROM_HOSTS))
                get("automation-home-init-url", initFromUrl)
                get("automation-home-init-url-effect", "/definitions", before = step(initFromUrl))
                get("automation-home-init-filter-tag", "/service/suite/untagged", before = step("$INIT&file=$INIT_DOCUMENT&filterTag=keep"))
                get("automation-home-init-without-type", "/home?action=init&transientState=true&file=$INIT_DOCUMENT")
                get("automation-home-delete-all", "/home?action=deleteAllServices&type=json")

                get("automation-status", "/status", snapshot = true)
            },
        )

    val all: Map<String, ParityCase> = casesOf(groups)

    private fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
