package io.github.dnedrow.mockey.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class IdentifiersTest {
    @Test
    fun generatedIdentifiersAreNeverRepeated() {
        val generated = List(10_000) { ServiceId.generate() }

        assertEquals(generated.size, generated.toSet().size)
    }

    @Test
    fun everyKindOfIdentifierIsGenerated() {
        assertNotEquals(ServiceId.generate(), ServiceId.generate())
        assertNotEquals(ScenarioId.generate(), ScenarioId.generate())
        assertNotEquals(PlanId.generate(), PlanId.generate())
        assertNotEquals(TwistId.generate(), TwistId.generate())
    }

    @Test
    fun aBlankIdentifierIsRejected() {
        assertFailsWith<IllegalArgumentException> { ServiceId("") }
        assertFailsWith<IllegalArgumentException> { ScenarioId(" ") }
        assertFailsWith<IllegalArgumentException> { PlanId("\t") }
        assertFailsWith<IllegalArgumentException> { TwistId("\n") }
    }

    @Test
    fun anIdentifierIsSerializedAsItsText() {
        assertEquals("\"svc-1\"", Json.encodeToString(ServiceId.serializer(), ServiceId("svc-1")))
        assertEquals(ServiceId("svc-1"), Json.decodeFromString(ServiceId.serializer(), "\"svc-1\""))
        assertEquals(ScenarioId("scn-1"), Json.decodeFromString(ScenarioId.serializer(), "\"scn-1\""))
        assertEquals(PlanId("plan-1"), Json.decodeFromString(PlanId.serializer(), "\"plan-1\""))
        assertEquals(TwistId("twist-1"), Json.decodeFromString(TwistId.serializer(), "\"twist-1\""))
    }

    @Test
    fun aBlankIdentifierIsRejectedWhenItIsRead() {
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(ServiceId.serializer(), "\" \"") }
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(ScenarioId.serializer(), "\"\"") }
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(PlanId.serializer(), "\"\"") }
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(TwistId.serializer(), "\"\"") }
    }
}
