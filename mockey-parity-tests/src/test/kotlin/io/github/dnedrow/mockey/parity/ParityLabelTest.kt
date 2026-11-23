package io.github.dnedrow.mockey.parity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ParityLabelTest {
    @Test
    fun readsTheThreeKindsOfLabel() {
        assertEquals(ParityLabel.Unreviewed, ParityLabel.parse("unreviewed"))
        assertEquals(ParityLabel.Same, ParityLabel.parse("same"))
        assertEquals(ParityLabel.Deviations(listOf("DEV-06")), ParityLabel.parse("DEV-06"))
        assertEquals(ParityLabel.Deviations(listOf("DEV-06", "DEV-08", "DEV-31")), ParityLabel.parse("DEV-06 DEV-08 DEV-31"))
    }

    @Test
    fun ordersDeviationsByNumber() {
        assertEquals(ParityLabel.Deviations(listOf("DEV-31", "DEV-100")), ParityLabel.parse("DEV-31 DEV-100"))
        assertFailsWith<IllegalArgumentException> { ParityLabel.parse("DEV-100 DEV-31") }
    }

    @Test
    fun rejectsEverythingElse() {
        listOf(
            "",
            "Same",
            "reviewed",
            "DEV-6",
            "dev-06",
            "DEV-06,DEV-08",
            "DEV-06  DEV-08",
            "DEV-08 DEV-06",
            "DEV-06 DEV-06",
            "same DEV-06",
        ).forEach { text -> assertFailsWith<IllegalArgumentException>(text) { ParityLabel.parse(text) } }
    }
}
