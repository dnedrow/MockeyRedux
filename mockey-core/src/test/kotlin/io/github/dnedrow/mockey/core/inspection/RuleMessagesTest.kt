package io.github.dnedrow.mockey.core.inspection

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Header
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.request.MockRequest
import io.github.dnedrow.mockey.core.routing.RoutingTable
import io.github.dnedrow.mockey.core.routing.Target
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RuleMessagesTest {
    private val pageIdRule =
        """{"key": "page_id", "desc": "page_id is mandatory", "value_rule_arg": "", "value_rule_type": "string_required"}"""

    /** The rule set of the parity fixture `inspection`. */
    private val inspectionRules =
        """
        {
            "headers": [$pageIdRule],
            "parameters": [
                {"key": "ticker", "desc": "The ticker must contain goog.", "value_rule_arg": "goog", "value_rule_type": "string_required"},
                {"key": "date", "desc": "A date must look like 11/23/2026.", "value_rule_arg": "^\\d{2}/\\d{2}/\\d{4}$",
                 "value_rule_type": "regex_optional"}
            ],
            "body": [
                {"desc": "The body must have an amount.", "value_rule_arg": "\"amount\":\\s*\\d+", "value_rule_type": "regex_required"}
            ],
            "url": [{"desc": "The URL must end in checked.", "value_rule_arg": "checked$", "value_rule_type": "regex_required"}]
        }
        """

    private fun service(
        name: String,
        requestRules: String?,
        enabled: Boolean = true,
    ) = Service(id = ServiceId.generate(), name = name, mockUrl = name, requestRules = requestRules, requestRulesEnabled = enabled)

    private fun request(
        target: String,
        headers: List<Header> = emptyList(),
        body: String? = null,
    ): MockRequest {
        val query = if ('?' in target) target.substringAfter('?') else null
        val url = "http://127.0.0.1:8080/service/${target.substringBefore('?')}"
        return MockRequest(if (body == null) "GET" else "POST", url, query, headers, body?.toByteArray() ?: ByteArray(0))
    }

    private fun messages(
        service: Service,
        request: MockRequest,
    ): List<String> = RuleSets(Definitions(services = listOf(service))).ruleMessages(service, request)

    private fun messages(
        rules: String,
        request: MockRequest,
    ): List<String> = messages(service("checked", rules), request)

    @Test
    fun aFailedRuleGivesAMessageThatNamesItsGroupItsKeyAndItsDescription() {
        val message = messages("""{"headers": [$pageIdRule]}""", request("checked")).single()

        assertEquals(
            "The request fails a rule of the group 'headers' for the key 'page_id', which requires a value. " +
                "Description: page_id is mandatory",
            message,
        )
    }

    @Test
    fun aMessageSaysWhatARuleOfEachTypeRequires() {
        val rules =
            """
            {"parameters": [
                {"key": "page", "value_rule_arg": " ", "value_rule_type": "string_required"},
                {"key": "ticker", "value_rule_arg": " GOOG ", "value_rule_type": "string_required"},
                {"key": "id", "value_rule_arg": "^\\d{3}$", "value_rule_type": "REGEX_REQUIRED"},
                {"key": "date", "value_rule_arg": "^\\d{2}/\\d{2}/\\d{4}$", "value_rule_type": "regex_optional"}
            ]}
            """
        val prefix = "The request fails a rule of the group 'parameters' for the key"

        val messages = messages(rules, request("checked?ticker=msft&id=12a&date=tomorrow"))

        assertEquals(
            listOf(
                "$prefix 'page', which requires a value.",
                "$prefix 'ticker', which requires a value that contains 'GOOG'.",
                "$prefix 'id', which requires a value in which the regular expression '^\\d{3}$' is found.",
                "$prefix 'date', which requires a value in which the regular expression '^\\d{2}/\\d{2}/\\d{4}$' is found, " +
                    "when there is a value.",
            ),
            messages,
        )
    }

    @Test
    fun aRuleOfTheBodyOrTheUrlGivesAMessageWithoutAKey() {
        val rules =
            """
            {
                "body": [{"key": "ignored", "desc": "Needs an amount.", "value_rule_arg": "amount", "value_rule_type": "string_required"}],
                "url": [{"desc": "Must end in checked.", "value_rule_arg": "checked$", "value_rule_type": "regex_required"}]
            }
            """

        val messages = messages(rules, request("other", body = "nothing"))

        assertEquals(
            listOf(
                "The request fails a rule of the group 'body', which requires a value that contains 'amount'. " +
                    "Description: Needs an amount.",
                "The request fails a rule of the group 'url', which requires a value in which the regular expression " +
                    "'checked$' is found. Description: Must end in checked.",
            ),
            messages,
        )
    }

    @Test
    fun aRuleWithoutADescriptionGivesAMessageWithoutOne() {
        val blank = """{"headers": [{"key": "page_id", "desc": "  ", "value_rule_arg": "", "value_rule_type": "string_required"}]}"""
        val absent = """{"headers": [{"key": "page_id", "value_rule_arg": "", "value_rule_type": "string_required"}]}"""
        val expected = listOf("The request fails a rule of the group 'headers' for the key 'page_id', which requires a value.")

        assertEquals(expected, messages(blank, request("checked")))
        assertEquals(expected, messages(absent, request("checked")))
    }

    @Test
    fun everyFailedRuleGivesOneMessageAlsoWhenTheRuleSetIsSatisfied() {
        val request = request("checked?ticker=msft&date=tomorrow", listOf(Header("Content-Type", "text/plain")), "no amount here")

        val messages = messages(inspectionRules, request)

        assertEquals(4, messages.size)
        assertContains(messages[0], "'headers' for the key 'page_id'")
        assertContains(messages[1], "'parameters' for the key 'ticker'")
        assertContains(messages[2], "'parameters' for the key 'date'")
        assertContains(messages[3], "'body'")
    }

    @Test
    fun aRequestThatSatisfiesEveryRuleGivesNoMessage() {
        val headers = listOf(Header("Content-Type", "text/plain"), Header("page_id", "home"))

        assertEquals(emptyList(), messages(inspectionRules, request("checked?ticker=goog", headers, """{"amount": 25}""")))
    }

    @Test
    fun requestRulesThatAreNotEnabledAreNotEvaluated() {
        val disabled = service("disabled", inspectionRules, enabled = false)

        assertEquals(emptyList(), messages(disabled, request("disabled?ticker=msft")))
    }

    @Test
    fun aServiceWithoutRequestRulesGivesNoMessage() {
        assertEquals(emptyList(), messages(service("plain", requestRules = null, enabled = true), request("plain")))
        assertEquals(emptyList(), messages(service("empty", requestRules = "{}", enabled = true), request("empty")))
    }

    @Test
    fun requestRulesThatCannotBeReadGiveAMessageThatSaysSo() {
        val truncated = """{"parameters": [{"key": "ticker""""
        val enabled = service("inspection unreadable", truncated, enabled = true)
        val disabled = service("inspection unreadable", truncated, enabled = false)

        assertEquals(
            listOf("The request rules of the service 'inspection unreadable' could not be read."),
            messages(enabled, request("inspection/unreadable")),
        )
        assertEquals(emptyList(), messages(disabled, request("inspection/unreadable")))
    }

    /**
     * The services of the parity fixture `inspection` and the requests that go before the recording
     * `inspection-history-messages`. Legacy gave the first request no message for the header `page_id`, which
     * it does not send, because legacy skipped a rule whose key is absent (DEV-15).
     */
    @Test
    fun theRequestsOfTheInspectionRecordingGetAMessageForEachRuleThatTheyFail() {
        val text = listOf(Header("Content-Type", "text/plain"))
        val checked = service("inspection checked", inspectionRules, enabled = true)
        val disabled = service("inspection disabled", inspectionRules, enabled = false)
        val unreadable = service("inspection unreadable", """{"parameters": [{"key": "ticker"""", enabled = true)
        val ruleSets = RuleSets(Definitions(services = listOf(checked, disabled, unreadable)))
        val group = "The request fails a rule of the group"

        val messages =
            listOf(
                checked to request("inspection/checked?ticker=msft&date=tomorrow", text, "no amount here"),
                checked to request("inspection/checked?ticker=goog", text + Header("page_id", "home"), """{"amount": 25}"""),
                disabled to request("inspection/disabled?ticker=msft"),
                unreadable to request("inspection/unreadable"),
                disabled to request("inspection/disabled?foobarKey=1"),
            ).map { (service, request) -> ruleSets.ruleMessages(service, request) }

        assertEquals(
            listOf(
                listOf(
                    "$group 'headers' for the key 'page_id', which requires a value. Description: page_id is mandatory",
                    "$group 'parameters' for the key 'ticker', which requires a value that contains 'goog'. " +
                        "Description: The ticker must contain goog.",
                    "$group 'parameters' for the key 'date', which requires a value in which the regular expression " +
                        "'^\\d{2}/\\d{2}/\\d{4}$' is found, when there is a value. Description: A date must look like 11/23/2026.",
                    "$group 'body', which requires a value in which the regular expression '\"amount\":\\s*\\d+' is found. " +
                        "Description: The body must have an amount.",
                ),
                emptyList(),
                emptyList(),
                listOf("The request rules of the service 'inspection unreadable' could not be read."),
                emptyList(),
            ),
            messages,
        )
    }

    /**
     * Inspection gets the service and the request, and it gives messages. It does not fail for a request that
     * breaks every rule or for rules that cannot be read, and the request goes to the same service, with the
     * same scenarios, as without it.
     */
    @Test
    fun inspectionLeavesTheAnswerToARequestAsItIs() {
        val checked = service("inspection/checked", inspectionRules, enabled = true)
        val unreadable = service("inspection/unreadable", "not a rule set", enabled = true)
        val definitions = Definitions(services = listOf(checked, unreadable))
        val table = RoutingTable(definitions)
        val routes = { listOf("inspection/checked", "inspection/unreadable").map { table.route(Target(it), Tags.EMPTY, true) } }
        val before = routes()
        val odd = MockRequest("TRACE", "http://127.0.0.1:8080/service/inspection/checked", "%zz=%&&=", body = byteArrayOf(-1, 0, 7))

        val messages =
            listOf(checked to odd, unreadable to request("inspection/unreadable"))
                .map { (service, request) -> RuleSets(definitions).ruleMessages(service, request) }

        assertEquals(listOf(3, 1), messages.map { it.size })
        assertEquals(before, routes())
        assertSame(checked, definitions.services.first())
    }
}
