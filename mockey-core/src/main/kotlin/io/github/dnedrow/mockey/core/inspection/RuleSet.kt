package io.github.dnedrow.mockey.core.inspection

import io.github.dnedrow.mockey.core.json.JsonReading
import io.github.dnedrow.mockey.core.json.readJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.regex.PatternSyntaxException

/**
 * A group of rules of a rule set, which says what of a request its rules evaluate. [key] is the name of the
 * member that holds the rules of the group in the JSON text of a rule set. Each rule of a [keyed] group names
 * the parameter or the header that it evaluates.
 */
internal enum class RuleGroup(
    val key: String,
    val keyed: Boolean,
) {
    /** The query parameters and the form fields of the request. */
    PARAMETERS("parameters", keyed = true),

    /** The headers of the request. */
    HEADERS("headers", keyed = true),

    /** The body of the request. */
    BODY("body", keyed = false),

    /** The URL that the client addressed, without its query string. */
    URL("url", keyed = false),
}

/** How a rule evaluates its values. [key] is the text that names the type in a rule. */
internal enum class RuleType(
    val key: String,
) {
    /** A value must contain the argument of the rule. */
    STRING_REQUIRED("string_required"),

    /** The argument of the rule, which is a regular expression, must be found in a value. */
    REGEX_REQUIRED("regex_required"),

    /** As [REGEX_REQUIRED], unless there is no value at all. */
    REGEX_OPTIONAL("regex_optional"),
    ;

    /** Whether the argument of a rule of this type is a regular expression. */
    val usesExpression: Boolean
        get() = this != STRING_REQUIRED

    companion object {
        /** Returns the type that [text] names, in any letter case, or `null` when it names none. */
        fun of(text: String): RuleType? = entries.firstOrNull { it.key.equals(text, ignoreCase = true) }
    }
}

/**
 * One rule of a rule set. [key] names the parameter or the header whose values the rule evaluates, and is
 * `null` for a rule of a group that is not keyed. [argument] is what the [type] of the rule looks for, and
 * [description] is what the author of the rule says about it.
 *
 * A rule of a type that takes a regular expression cannot be created with an argument that is none.
 */
internal data class Rule(
    val group: RuleGroup,
    val key: String?,
    val type: RuleType,
    val argument: String,
    val description: String? = null,
) {
    init {
        require((key != null) == group.keyed) { "A rule has a key exactly when its group is keyed." }
    }

    /** The regular expression that [argument] is, for a rule of a type that takes one. */
    val expression: Regex? = if (type.usesExpression) Regex(argument) else null
}

/**
 * A well-formed rule set. [source] is the JSON text that it was read from, exactly as it was written, and
 * [rules] are its rules in the order of that text.
 */
internal data class RuleSet(
    val source: String,
    val rules: List<Rule>,
)

/** What reading the text of a rule set gave. */
internal sealed interface RuleSetReading {
    /** The text is a well-formed rule set. */
    data class WellFormed(
        val ruleSet: RuleSet,
    ) : RuleSetReading

    /** The text is not a well-formed rule set. Each of the [problems] is a sentence that names one fault. */
    data class NotWellFormed(
        val problems: List<String>,
    ) : RuleSetReading {
        init {
            require(problems.isNotEmpty()) { "A rule set that is not well formed has at least one fault." }
        }
    }
}

/**
 * Reads [source] as a rule set.
 *
 * A well-formed rule set is a JSON object. Each of its members is one of the groups `parameters`, `headers`,
 * `body`, and `url`, and holds an array of rules. A rule is a JSON object with these members:
 *
 * - `value_rule_type`: one of `string_required`, `regex_required`, and `regex_optional`, in any letter case.
 * - `value_rule_arg`: what the type looks for. For the two regex types it must be a regular expression.
 * - `key`: the name of a parameter or a header. A rule of `parameters` or `headers` must have it. A rule of
 *   `body` or `url` can have it, where it means nothing.
 * - `desc`: a description, which a rule need not have.
 *
 * The values of `key`, `value_rule_arg`, and `desc` are texts. A number or a boolean stands for the text that
 * it is written as, and a member that is `null` counts as absent. Any other member of a rule means nothing.
 *
 * Every fault of the text is reported, not only the first one. A text that is not JSON has one fault.
 */
internal fun readRuleSet(source: String): RuleSetReading {
    val json =
        when (val reading = readJson(source)) {
            is JsonReading.Valid -> reading.element
            is JsonReading.Invalid -> return notWellFormed("The rule set is not valid JSON: ${reading.reason}")
        }
    val members = json as? JsonObject ?: return notWellFormed("The rule set is not a JSON object.")
    val problems = mutableListOf<String>()
    val rules = mutableListOf<Rule>()
    for ((name, value) in members) {
        val group = RuleGroup.entries.firstOrNull { it.key == name }
        if (group == null) {
            problems += "The rule set has the member '$name', which is no group of rules. The groups are $GROUPS."
        } else if (value !is JsonArray) {
            problems += "The group '$name' is not an array of rules."
        } else {
            value.forEachIndexed { index, entry -> RuleReader(group, index + 1, problems).read(entry)?.let { rules += it } }
        }
    }
    return if (problems.isEmpty()) RuleSetReading.WellFormed(RuleSet(source, rules)) else RuleSetReading.NotWellFormed(problems)
}

/** Returns what is wrong with [source] as a rule set, in one text, or `null` when it is a well-formed rule set. */
internal fun ruleSetProblems(source: String): String? = (readRuleSet(source) as? RuleSetReading.NotWellFormed)?.problems?.joinToString(" ")

private const val KEY = "key"
private const val DESCRIPTION = "desc"
private const val ARGUMENT = "value_rule_arg"
private const val TYPE = "value_rule_type"

private val GROUPS = quoted(RuleGroup.entries.map { it.key })
private val TYPES = quoted(RuleType.entries.map { it.key })

private fun notWellFormed(problem: String): RuleSetReading = RuleSetReading.NotWellFormed(listOf(problem))

/** Returns [names] as they are listed in a sentence, each in quotes. */
private fun quoted(names: List<String>): String = names.dropLast(1).joinToString(", ") { "'$it'" } + ", and '${names.last()}'"

/** Reads the rule that is entry [number] of [group], counted from 1, and adds its faults to [problems]. */
private class RuleReader(
    private val group: RuleGroup,
    number: Int,
    private val problems: MutableList<String>,
) {
    private val place = "Rule $number of the group '${group.key}'"

    /** Returns the rule that [entry] is, or `null` when it has a fault. */
    fun read(entry: JsonElement): Rule? {
        val members = entry as? JsonObject ?: return fault("$place is not a JSON object.")
        val faultsBefore = problems.size
        val key = if (group.keyed) members.text(KEY, required = true) else null
        val argument = members.text(ARGUMENT, required = true)
        val typeName = members.text(TYPE, required = true)
        val type = typeName?.let { RuleType.of(it) ?: fault("$place has the '$TYPE' '$it'. The types are $TYPES.") }
        val description = members.text(DESCRIPTION, required = false)
        return if (problems.size > faultsBefore || argument == null || type == null) null else rule(key, type, argument, description)
    }

    private fun rule(
        key: String?,
        type: RuleType,
        argument: String,
        description: String?,
    ): Rule? =
        try {
            Rule(group, key, type, argument, description)
        } catch (e: PatternSyntaxException) {
            val where = if (e.index >= 0) " near index ${e.index}" else ""
            fault("$place has the '$ARGUMENT' '$argument', which is not a regular expression: ${e.description}$where.")
        }

    /**
     * Returns the text of the member [name], or `null` when the member is absent or is no text. A member that
     * is an object or an array is a fault, and so is one that is absent although it is [required].
     */
    private fun JsonObject.text(
        name: String,
        required: Boolean,
    ): String? =
        when (val value = this[name]) {
            null, JsonNull -> if (required) fault("$place has no '$name'.") else null
            is JsonPrimitive -> value.content
            else -> fault("$place has a '$name' that is not a text.")
        }

    private fun fault(problem: String): Nothing? {
        problems += problem
        return null
    }
}
