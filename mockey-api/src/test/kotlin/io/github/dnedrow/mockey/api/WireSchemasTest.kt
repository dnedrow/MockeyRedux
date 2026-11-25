package io.github.dnedrow.mockey.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 12.9: the schemas in the description of the admin API are made from the wire types, by what the
 * serializers of the wire types say.
 */
class WireSchemasTest {
    /**
     * A schema states what the serializer of its wire type says: the members, the ones that a request has to
     * state, and the ones that can be null.
     */
    @Test
    fun aSchemaStatesTheMembersOfItsWireType() {
        val schemas = WireSchemas("#/s/")

        val reference = schemas.of(ReplayRequestDto.serializer().descriptor)

        val expected =
            """
            {"type":"object","properties":{
              "headers":{"type":["array","null"],"items":{"${'$'}ref":"#/s/Header"}},
              "parameters":{"type":["array","null"],"items":{"${'$'}ref":"#/s/NameValue"}},
              "bodyBase64":{"type":["string","null"]}}}
            """
        val header = """{"type":"object","properties":{"name":{"type":"string"},"value":{"type":"string"}},"required":["name","value"]}"""
        assertEquals(json("""{"${'$'}ref":"#/s/ReplayRequest"}"""), reference)
        assertEquals(setOf("Header", "NameValue", "ReplayRequest"), schemas.components.keys)
        assertEquals(json(expected), schemas.components.getValue("ReplayRequest"))
        assertEquals(json(header), schemas.components.getValue("Header"))
    }

    /**
     * A wire type with several kinds is one of the schemas of its kinds, each of which names its kind in
     * the member that the JSON of the API has for it, and a wire type that is one of several words lists
     * them. A value that can be null is so as JSON Schema says it of a reference.
     */
    @Test
    fun aSchemaStatesTheKindsAndTheWordsOfItsWireType() {
        val schemas = WireSchemas("#/s/")

        schemas.of(MatchCriterionDto.serializer().descriptor)
        schemas.of(HistoryEntryDto.serializer().descriptor)

        val kinds =
            """
            {"oneOf":[
               {"${'$'}ref":"#/s/MatchCriterionNone"},{"${'$'}ref":"#/s/MatchCriterionRules"},{"${'$'}ref":"#/s/MatchCriterionText"}],
             "discriminator":{"propertyName":"type","mapping":{
               "none":"#/s/MatchCriterionNone","rules":"#/s/MatchCriterionRules","text":"#/s/MatchCriterionText"}}}
            """
        val none = """{"type":"object","properties":{"type":{"type":"string","enum":["none"]}},"required":["type"]}"""
        val text =
            """
            {"type":"object","properties":{"type":{"type":"string","enum":["text"]},"text":{"type":"string"}},"required":["type","text"]}
            """
        val historyEntry = schemas.components.getValue("HistoryEntry")
        val entry = historyEntry.getValue("properties").jsonObject
        assertEquals(json(kinds), schemas.components.getValue("MatchCriterion"))
        assertEquals(json(none), schemas.components.getValue("MatchCriterionNone"))
        assertEquals(json(text), schemas.components.getValue("MatchCriterionText"))
        assertEquals(json("""{"type":"string","enum":["proxy","static","dynamic"]}"""), schemas.components.getValue("ResponseMode"))
        assertEquals(json("""{"anyOf":[{"${'$'}ref":"#/s/ResponseMode"},{"type":"null"}]}"""), entry.getValue("responseMode"))
        assertEquals(json("""{"type":"integer","format":"int64"}"""), entry.getValue("id"))
        val ofText = AdminApiJson.instance.encodeToJsonElement(MatchCriterionDto.serializer(), MatchCriterionDto.Text("Ada"))
        assertEquals(json("""{"type":"text","text":"Ada"}"""), ofText)
    }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
}
