package io.github.dnedrow.mockey.core.definitions

import dev.harrel.jsonschema.Dialects
import dev.harrel.jsonschema.FormatEvaluatorFactory
import dev.harrel.jsonschema.InvalidSchemaException
import dev.harrel.jsonschema.JsonSchemaException
import dev.harrel.jsonschema.MetaSchemaResolvingException
import dev.harrel.jsonschema.SpecificationVersion
import dev.harrel.jsonschema.Validator
import dev.harrel.jsonschema.ValidatorFactory
import dev.harrel.jsonschema.providers.KotlinxJsonNode
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import dev.harrel.jsonschema.Error as SchemaError

/** What checking the scenarios of a service against its response schema gave. */
public sealed interface SchemaReport {
    /** The schema was applied: [scenarios] has an entry for each scenario of the service, in their order. */
    public data class Checked(
        public val scenarios: List<ScenarioConformance>,
    ) : SchemaReport

    /** The schema, or a schema inside it, declares [dialect], which is not supported, so no scenario was checked. */
    public data class UnsupportedDialect(
        public val dialect: String,
    ) : SchemaReport

    /** The schema cannot be applied, for the reasons that [problems] gives, so no scenario was checked. */
    public data class UnusableSchema(
        public val problems: List<String>,
    ) : SchemaReport
}

/** Whether the response body of a scenario conforms to the response schema of its service. */
public data class ScenarioConformance(
    public val scenarioId: ScenarioId,
    public val scenarioName: String,
    public val violations: List<String>,
) {
    /** A body conforms when the schema has nothing against it. */
    public val conforms: Boolean
        get() = violations.isEmpty()
}

/**
 * Checks the response body of every scenario against the response schema of the service, or returns `null`
 * for a service without a response schema. The report changes nothing: a scenario that does not conform is
 * stored and served as before.
 *
 * A schema is read in the dialect that its `$schema` declares, which can be JSON Schema draft-04, draft-06,
 * draft-07, 2019-09, or 2020-12. A schema that declares none is read as draft-04. The `format` keyword is
 * checked in every dialect. References to schemas elsewhere are not followed.
 *
 * The schema and the response bodies must be JSON that is nested no more deeply than the JSON parser accepts,
 * and their numbers must be within the range of a double.
 */
internal fun Service.responseSchemaReport(): SchemaReport? {
    val text = responseSchema ?: return null
    val schema = json(text) ?: return unusable("The schema cannot be read as JSON.")
    if (schema.hasNumberOutOfRange()) return unusable("The schema holds a number outside the range of a double.")
    val declared = (schema as? JsonObject)?.get("\$schema")
    if (declared != null && declared.dialect() !in SUPPORTED_DIALECTS) {
        return SchemaReport.UnsupportedDialect(declared.dialect() ?: declared.toString())
    }
    return try {
        val validator =
            ValidatorFactory()
                .withDefaultDialect(Dialects.Draft4Dialect())
                .withJsonNodeFactory(KotlinxJsonNode.Factory())
                .withEvaluatorFactory(FormatEvaluatorFactory())
                .createValidator()
        val registered = validator.registerSchema(SCHEMA_URI, schema)
        SchemaReport.Checked(
            scenarios.map { ScenarioConformance(it.id, it.name, violations(validator, registered, it.responseBody)) },
        )
    } catch (e: MetaSchemaResolvingException) {
        SchemaReport.UnsupportedDialect(e.uri)
    } catch (e: InvalidSchemaException) {
        SchemaReport.UnusableSchema(e.errors.map { it.text() }.ifEmpty { listOf("The schema is not valid in its dialect.") })
    } catch (e: JsonSchemaException) {
        unusable("The schema cannot be applied: ${e.message}")
    } catch (e: IllegalArgumentException) {
        unusable("The schema cannot be applied: ${e.message}")
    } catch (e: StackOverflowError) {
        // The validator has no guard against a schema that refers to itself without consuming any of the body.
        unusable("Applying the schema did not come to an end, which happens when a schema refers to itself in a loop.")
    }
}

/** The dialects that the validator supports, by the text of their `$schema` without a `#` at the end. */
private val SUPPORTED_DIALECTS: Set<String> = SpecificationVersion.entries.map { it.id.removeSuffix("#") }.toSet()

/** Names the schema in the texts of the validator, which would otherwise give it a random name on each run. */
private val SCHEMA_URI: URI = URI.create("mockey:/response-schema")

/**
 * The validator computes with numbers at their full size, so that a number such as `1e100000000` can take
 * minutes or all of the memory. RFC 8259 names the range of a double as the one that software can rely on.
 */
private val LARGEST_NUMBER = Double.MAX_VALUE.toBigDecimal()
private val SMALLEST_NUMBER = Double.MIN_VALUE.toBigDecimal()

private val JSON_NUMBER = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")

private fun unusable(problem: String): SchemaReport = SchemaReport.UnusableSchema(listOf(problem))

private fun JsonElement.dialect(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.removeSuffix("#")

private fun violations(
    validator: Validator,
    schema: URI,
    responseBody: String,
): List<String> {
    val body = json(responseBody) ?: return listOf("The response body cannot be read as JSON.")
    if (body.hasNumberOutOfRange()) {
        return listOf("The response body holds a number outside the range of a double, which the report does not check.")
    }
    return validator.validate(schema, body).errors.map { it.text() }
}

/** The text of the validator on one line, with the place in the JSON that it is about. */
private fun SchemaError.text(): String {
    val message = error.lines().joinToString(" ") { it.trim() }
    return if (instanceLocation.isEmpty()) message else "At $instanceLocation: $message"
}

/**
 * Returns [text] as JSON, or `null` when it cannot be read as JSON, which is also the case for JSON that is
 * nested more deeply than the parser accepts. The parser alone would let a bare word pass as a value.
 */
private fun json(text: String): JsonElement? =
    try {
        Json.parseToJsonElement(text).takeIf { it.isJson() }
    } catch (e: SerializationException) {
        null
    }

private fun JsonElement.isJson(): Boolean =
    when (this) {
        is JsonObject -> values.all { it.isJson() }
        is JsonArray -> all { it.isJson() }
        is JsonPrimitive -> isString || content == "true" || content == "false" || content == "null" || JSON_NUMBER.matches(content)
    }

private fun JsonElement.hasNumberOutOfRange(): Boolean =
    when (this) {
        is JsonObject -> values.any { it.hasNumberOutOfRange() }
        is JsonArray -> any { it.hasNumberOutOfRange() }
        is JsonPrimitive -> !isString && JSON_NUMBER.matches(content) && !content.isInTheRangeOfADouble()
    }

private fun String.isInTheRangeOfADouble(): Boolean {
    val magnitude = toBigDecimalOrNull()?.abs() ?: return false
    return magnitude.signum() == 0 || magnitude in SMALLEST_NUMBER..LARGEST_NUMBER
}
