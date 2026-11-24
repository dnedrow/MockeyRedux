package io.github.dnedrow.mockey.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals

class FileNamesTest {
    @Test
    fun aFileNameHasTheLettersAndDigitsOfTheNameInLowerCaseWithHyphensBetweenThem() {
        val names =
            mapOf(
                "Customers" to "customers",
                "Order-Service" to "order-service",
                "order service" to "order-service",
                "ORDER_SERVICE (v2)" to "order-service-v2",
                "  gold   customer!  " to "gold-customer",
                "http://api.example.com/v1/customers" to "http-api-example-com-v1-customers",
                "customers (auto generated)" to "customers-auto-generated",
                "404" to "404",
            )

        assertEquals(names.values.toList(), names.keys.map { fileNameOf(it, fallback = "service") })
    }

    @Test
    fun anAccentedLetterGivesItsBaseLetter() {
        assertEquals("cafe-fur-ninos", fileNameOf("Caf\u00e9 f\u00fcr Ni\u00f1os", fallback = "service"))
    }

    @Test
    fun aNameWithoutALetterOfAsciiOrADigitGivesTheFallback() {
        val names = listOf("", "   ", "!!!", "\u65e5\u672c\u8a9e", "\uD83D\uDE00")

        assertEquals(List(names.size) { "scenario" }, names.map { fileNameOf(it, fallback = "scenario") })
    }

    @Test
    fun aLongNameIsCutAndEndsWithNoHyphen() {
        val long = "a".repeat(63) + " " + "b".repeat(40)

        assertEquals("a".repeat(63), fileNameOf(long, fallback = "service"))
        assertEquals("c".repeat(64), fileNameOf("c".repeat(200), fallback = "service"))
    }

    @Test
    fun aNameThatIsTakenGetsTheFirstNumberThatMakesItFree() {
        val taken = mutableSetOf("orders", "orders-2", "customers")

        val names = listOf("orders", "orders", "customers", "payments", "payments").map { freeFileName(it, taken) }

        assertEquals(listOf("orders-3", "orders-4", "customers-2", "payments", "payments-2"), names)
        assertEquals(setOf("orders", "orders-2", "orders-3", "orders-4", "customers", "customers-2", "payments", "payments-2"), taken)
    }

    @Test
    fun aNameThatWindowsKeepsForADeviceIsNeverUsed() {
        val names = listOf("con", "nul", "com1", "lpt9", "aux", "prn").map { freeFileName(it, mutableSetOf()) }

        assertEquals(listOf("con-2", "nul-2", "com1-2", "lpt9-2", "aux-2", "prn-2"), names)
    }
}
