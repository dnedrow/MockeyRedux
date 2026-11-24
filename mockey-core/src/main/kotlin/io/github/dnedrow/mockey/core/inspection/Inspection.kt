package io.github.dnedrow.mockey.core.inspection

import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.request.MockRequest

/**
 * Inspects [request], which goes to [service], and returns the messages for the history entry of the request,
 * each of them once: those of the [inspectors], and after them those of the request rules of the service, see
 * [ruleMessages].
 *
 * Inspection gives messages and nothing else. It has no say in how the request is answered, and it does not
 * fail, whatever the request, the rules, and the inspectors do.
 */
internal fun inspectionMessages(
    service: Service,
    request: MockRequest,
    inspectors: Inspectors,
    ruleSets: RuleSets,
): List<String> = (inspectors.messages(service, request) + ruleSets.ruleMessages(service, request)).distinct()

/**
 * Inspects [request], which goes to [service], with the request rules of the service, and returns the messages
 * for the history entry of the request.
 *
 * Request rules inspect only while they are enabled. Every rule that the request does not satisfy gives one
 * message, in the order of the rule set, whether or not the rule set as a whole is satisfied. Request rules
 * that cannot be read give one message, which says so.
 */
internal fun RuleSets.ruleMessages(
    service: Service,
    request: MockRequest,
): List<String> =
    when (val rules = requestRules(service).takeIf { service.requestRulesEnabled }) {
        null -> {
            emptyList()
        }

        is RuleSetReading.NotWellFormed -> {
            listOf("The request rules of the service '${service.name}' could not be read.")
        }

        is RuleSetReading.WellFormed -> {
            rules.ruleSet
                .evaluate(request)
                .failedRules
                .map(::failureMessage)
        }
    }

/**
 * Returns the message for a [rule] that a request does not satisfy. It states the group of the rule, its key
 * where it has one, what the rule requires, and the description of the rule where it has one.
 */
internal fun failureMessage(rule: Rule): String {
    val key = rule.key?.let { " for the key '$it'" }.orEmpty()
    val description =
        rule.description
            ?.takeIf { it.isNotBlank() }
            ?.let { " Description: $it" }
            .orEmpty()
    return "The request fails a rule of the group '${rule.group.key}'$key, which requires ${requirement(rule)}.$description"
}

private fun requirement(rule: Rule): String =
    when (rule.type) {
        RuleType.STRING_REQUIRED -> if (rule.wantedText.isEmpty()) "a value" else "a value that contains '${rule.wantedText}'"
        RuleType.REGEX_REQUIRED -> "a value in which the regular expression '${rule.argument}' is found"
        RuleType.REGEX_OPTIONAL -> "a value in which the regular expression '${rule.argument}' is found, when there is a value"
    }
