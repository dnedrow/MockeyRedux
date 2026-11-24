package io.github.dnedrow.mockey.core.storage

import io.github.dnedrow.mockey.core.definitions.withoutProxyCredentials
import io.github.dnedrow.mockey.core.json.JsonReading
import io.github.dnedrow.mockey.core.json.readJson
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.Service
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** The format version of the definition files that this version of the library writes. */
internal const val FORMAT_VERSION: Int = 1

/**
 * What a definition file holds: the definition as [value], and its [position] among its like, if the file
 * states one. [holdsBody] tells of a scenario file whether it states the response body of its scenario.
 */
internal data class FileContent<T>(
    val value: T,
    val position: Long?,
    val holdsBody: Boolean = false,
)

/** Tells what is wrong with the content of a definition file, as the end of a sentence about that file. */
internal class FileFault(
    val problem: String,
) : Exception(problem)

/**
 * The texts of the definition files, and how they are read.
 *
 * Every file is a JSON object in the form in which the definitions are serialized: the members are in the
 * order of the model, a member that has its default value is left out, and the text is indented by four
 * spaces and ends with a line feed. The same definitions therefore always give the same text.
 *
 * - The main file holds the format version, the plans, the twist configurations, and the settings.
 * - A service file holds one service without its scenarios.
 * - A scenario file holds one scenario. Its response body is in a file of its own, unless it is no text that
 *   UTF-8 can hold: such a body stays in the scenario file.
 *
 * A service file and a scenario file end with the member `position`, by which the services, and the scenarios
 * of one service, are put in their order when they are read. Of two with the same position, the one whose
 * identifier sorts first comes first, and one without a position comes after all that have one.
 */
internal object DefinitionFiles {
    private const val VERSION = "formatVersion"
    private const val POSITION = "position"
    private const val SERVICES = "services"
    private const val SCENARIOS = "scenarios"
    private const val BODY = "responseBody"

    private val format = Json { prettyPrint = true }

    /** Returns the text of the main file for [definitions]. Their services are no part of it. */
    fun mainText(definitions: Definitions): String {
        val members = format.encodeToJsonElement(Definitions.serializer(), mainOf(definitions)).jsonObject
        return text(
            buildJsonObject {
                put(VERSION, JsonPrimitive(FORMAT_VERSION))
                members.forEach { (name, value) -> put(name, value) }
            },
        )
    }

    /** Returns what the main file holds of [definitions]: everything but the services, and no credentials. */
    fun mainOf(definitions: Definitions): Definitions = definitions.copy(services = emptyList()).withoutProxyCredentials()

    /** Returns the definitions that the main file with the text [text] holds, which are definitions without services. */
    fun main(text: String): Definitions {
        val members = membersOf(text)
        if (SERVICES in members) throw FileFault("it holds services, and every service belongs in a directory of its own.")
        return decoded(Definitions.serializer(), members - VERSION)
    }

    /** Returns the text of the service file for [service] at [position]. The scenarios of the service are no part of it. */
    fun serviceText(
        service: Service,
        position: Long?,
    ): String =
        text(positioned(format.encodeToJsonElement(Service.serializer(), service.copy(scenarios = emptyList())).jsonObject, position))

    /** Returns the service that the service file with the text [text] holds, without scenarios, and its position. */
    fun service(text: String): FileContent<Service> {
        val members = membersOf(text)
        if (SCENARIOS in members) throw FileFault("it holds scenarios, and every scenario belongs in a file of its own.")
        return FileContent(decoded(Service.serializer(), members - POSITION), positionOf(members))
    }

    /**
     * Returns the text of the scenario file for [scenario] at [position]. The response body is part of it only
     * when [withBody] is on.
     */
    fun scenarioText(
        scenario: Scenario,
        position: Long?,
        withBody: Boolean,
    ): String {
        val members = format.encodeToJsonElement(Scenario.serializer(), scenario).jsonObject
        return text(positioned(if (withBody) members else JsonObject(members - BODY), position))
    }

    /**
     * Returns the scenario that the scenario file with the text [text] holds, and its position. [body] is the
     * text of the body file of the scenario, or `null` when the scenario has no body file. A scenario without
     * one has the body that its scenario file states, which is none unless it states one.
     */
    fun scenario(
        text: String,
        body: String?,
    ): FileContent<Scenario> {
        val members = membersOf(text)
        if (body != null && BODY in members) throw FileFault("it holds a response body, and the scenario has a body file as well.")
        val scenario = decoded(Scenario.serializer(), members - POSITION)
        return FileContent(if (body == null) scenario else scenario.copy(responseBody = body), positionOf(members), BODY in members)
    }

    private fun text(members: JsonObject): String = withEscapedSurrogates(format.encodeToString(JsonObject.serializer(), members)) + "\n"

    /**
     * Returns the JSON text [json] with an escape for every surrogate that has no other half. UTF-8 cannot
     * hold such a half character, and JSON can as an escape, so the text is read back as it was written.
     */
    private fun withEscapedSurrogates(json: String): String {
        if (json.none { it.isSurrogate() }) return json
        val escaped = StringBuilder(json.length)
        var index = 0
        while (index < json.length) {
            val char = json[index]
            val isPair = char.isHighSurrogate() && index + 1 < json.length && json[index + 1].isLowSurrogate()
            when {
                isPair -> escaped.append(char).append(json[++index])
                char.isSurrogate() -> escaped.append("\\u").append(char.code.toString(HEX).padStart(ESCAPE_DIGITS, '0'))
                else -> escaped.append(char)
            }
            index++
        }
        return escaped.toString()
    }

    private fun positioned(
        members: JsonObject,
        position: Long?,
    ): JsonObject = if (position == null) members else JsonObject(members + (POSITION to JsonPrimitive(position)))

    private fun membersOf(text: String): Map<String, JsonElement> =
        when (val reading = readJson(text.removePrefix(BYTE_ORDER_MARK))) {
            is JsonReading.Invalid -> throw FileFault("it is not valid JSON: ${reading.reason}")
            is JsonReading.Valid -> reading.element as? JsonObject ?: throw FileFault("it is not a JSON object.")
        }

    private fun positionOf(members: Map<String, JsonElement>): Long? {
        val position = members[POSITION]?.takeUnless { it is JsonNull } ?: return null
        return (position as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: throw FileFault("its position is no whole number.")
    }

    private fun <T> decoded(
        strategy: DeserializationStrategy<T>,
        members: Map<String, JsonElement>,
    ): T =
        try {
            format.decodeFromJsonElement(strategy, JsonObject(members))
        } catch (e: SerializationException) {
            throw FileFault(problemOf(e))
        } catch (e: IllegalArgumentException) {
            throw FileFault(e.message ?: "it holds a value that no definition can have.")
        }

    /** Returns the first line of the message of [failure]. The lines after it repeat the text of the file. */
    private fun problemOf(failure: SerializationException): String =
        failure.message
            .orEmpty()
            .substringBefore('\n')
            .trim()
            .ifEmpty { "it is no definition." }

    private const val BYTE_ORDER_MARK = "\uFEFF"
    private const val HEX = 16
    private const val ESCAPE_DIGITS = 4
}
