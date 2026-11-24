package io.github.dnedrow.mockey.core.history

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryEntryTest {
    @Test
    fun anEntryWithOnlyWhatItMustHaveHasNoServiceNoScenarioAndNoMark() {
        val entry =
            HistoryEntry(
                id = HistoryEntryId(1),
                time = NOON,
                request = RecordedRequest("GET", "http://mockey.test:8080/service/foo"),
                response = RecordedResponse(404),
                target = "foo",
                outcome = Outcome.NO_SERVICE_MATCHED,
            )

        assertNull(entry.clientAddress)
        assertNull(entry.targetBeforeTwisting)
        assertNull(entry.service)
        assertNull(entry.responseMode)
        assertNull(entry.scenario)
        assertEquals(emptyList(), entry.inspectionMessages)
        assertNull(entry.explanation)
        assertFalse(entry.flagged)
        assertEquals(RecordedBody.EMPTY, entry.request.body)
        assertEquals("", entry.request.bodyText + entry.response.bodyText)
    }

    @Test
    fun aLaterEntryHasTheGreaterIdentifier() {
        assertTrue(HistoryEntryId(2) > HistoryEntryId(1))
        assertEquals(listOf(HistoryEntryId(1), HistoryEntryId(2), HistoryEntryId(10)), listOf(10L, 1L, 2L).map(::HistoryEntryId).sorted())
        assertEquals("10", HistoryEntryId(10).toString())
    }

    @Test
    fun aBodyIsCutWhenTheEntryHoldsLessThanAllOfIt() {
        val whole = RecordedBody("answer".toByteArray())
        val cut = RecordedBody("ans".toByteArray(), size = 6)

        assertEquals(6L to false, whole.size to whole.isCut)
        assertEquals(6L to true, cut.size to cut.isCut)
        assertContentEquals("ans".toByteArray(), cut.bytes)
        assertFailsWith<IllegalArgumentException> { RecordedBody("answer".toByteArray(), size = 5) }
    }

    @Test
    fun bodiesAreEqualWhenTheirBytesAndTheirSizesAre() {
        val body = RecordedBody("answer".toByteArray())

        assertEquals(body, RecordedBody("answer".toByteArray()))
        assertEquals(body.hashCode(), RecordedBody("answer".toByteArray()).hashCode())
        assertNotEquals(body, RecordedBody("answer".toByteArray(), size = 9))
        assertNotEquals(body, RecordedBody("Answer".toByteArray()))
        assertEquals(RecordedBody.EMPTY, RecordedBody(ByteArray(0)))
        assertEquals(
            RecordedRequest("GET", "http://a/b", body = body),
            RecordedRequest("GET", "http://a/b", body = RecordedBody("answer".toByteArray())),
        )
    }

    @Test
    fun theBytesOfABodyCannotBeChangedThroughWhatIsReadOfIt() {
        val body = RecordedBody("answer".toByteArray())

        body.bytes[0] = 'A'.code.toByte()

        assertEquals("answer", body.text(null))
    }

    @Test
    fun theTextFormOfABodyShowsItsSizesAndNotItsBytes() {
        assertEquals("RecordedBody(3 of 6 bytes)", RecordedBody("s3c".toByteArray(), size = 6).toString())
    }

    @Test
    fun aBodyIsReadAsTextInTheCharsetThatItsContentTypeDeclares() {
        val latin1 = RecordedBody(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte()))
        val utf8 = RecordedBody("caf\u00e9".toByteArray())

        assertEquals("caf\u00e9", latin1.text("text/plain; charset=ISO-8859-1"))
        assertEquals("caf\u00e9", utf8.text("text/plain"))
        assertEquals("caf\u00e9", utf8.text(null))
        assertEquals("caf\u00e9", utf8.text("text/plain; charset=no-such-charset"))
        assertEquals("caf\ufffd", latin1.text(null))
    }

    @Test
    fun theTextOfARecordedRequestAndOfARecordedResponseFollowsTheirContentType() {
        val bytes = RecordedBody(byteArrayOf(0xE9.toByte()))
        val request =
            RecordedRequest("POST", "http://a/b", headers = headersOf("content-type" to "text/plain; charset=ISO-8859-1"), body = bytes)
        val response = RecordedResponse(200, headersOf("X-A" to "1", "Content-Type" to "text/plain; charset=ISO-8859-1"), bytes)

        assertEquals("\u00e9" to "text/plain; charset=ISO-8859-1", request.bodyText to request.contentType)
        assertEquals("\u00e9" to "text/plain; charset=ISO-8859-1", response.bodyText to response.contentType)
        assertNull(RecordedResponse(204).contentType)
    }

    @Test
    fun aCutThatDividesACharacterLeavesAReplacementCharacterAtTheEndOfTheText() {
        val cut = RecordedBody("caf\u00e9".toByteArray().copyOf(4), size = 5)

        assertEquals("caf\ufffd", cut.text("text/plain; charset=utf-8"))
    }
}
