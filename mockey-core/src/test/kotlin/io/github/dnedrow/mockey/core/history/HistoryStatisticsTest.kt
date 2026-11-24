package io.github.dnedrow.mockey.core.history

import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryStatisticsTest {
    private val gold = scenarioOf("gold")
    private val a = serviceOf("Service A", "a", gold).copy(id = ServiceId("svc-a"))
    private val b = serviceOf("Service B", "b").copy(id = ServiceId("svc-b"))
    private val history = HistoryStore()

    /** Records a request that [service] handled and that arrived [seconds] after noon. */
    private fun record(
        service: Service?,
        seconds: Long,
    ): HistoryEntry = history.record(exchangeOf(proxied(service), time = NOON.plusSeconds(seconds)))

    private fun at(seconds: Long): Instant = NOON.plusSeconds(seconds)

    // Per-service statistics

    @Test
    fun everyServiceHasTheNumberOfItsEntriesAndTheTimeOfTheEarliestOne() {
        listOf(30L, 10L, 20L).forEach { record(a, it) }
        record(b, 5)

        val statistics = history.statistics(listOf(a, b))

        assertEquals(
            listOf(ServiceStatistics(a.id, "Service A", 3, at(10)), ServiceStatistics(b.id, "Service B", 1, at(5))),
            statistics.services,
        )
        assertEquals(at(5), statistics.oldestEntryTime)
        assertNull(statistics.from)
        assertNull(statistics.to)
    }

    @Test
    fun onlyTheEntriesInsideTheTimeRangeCount() {
        listOf(10L, 20L, 30L).forEach { record(a, it) }

        val statistics = history.statistics(listOf(a, b), from = at(15), to = at(40))

        assertEquals(
            listOf(ServiceStatistics(a.id, "Service A", 2, at(20)), ServiceStatistics(b.id, "Service B", 0, null)),
            statistics.services,
        )
        assertEquals(at(15) to at(40), statistics.from to statistics.to)
    }

    @Test
    fun aServiceWithoutEntriesHasTheCountZero() {
        val statistics = history.statistics(listOf(a, b))

        assertEquals(listOf(ServiceStatistics(a.id, "Service A"), ServiceStatistics(b.id, "Service B")), statistics.services)
        assertEquals(listOf(0, 0), statistics.services.map { it.count })
        assertNull(statistics.oldestEntryTime)
    }

    @Test
    fun theBoundsOfTheRangeAreInsideIt() {
        listOf(10L, 20L, 30L).forEach { record(a, it) }

        fun count(
            from: Long?,
            to: Long?,
        ) = history
            .statistics(listOf(a), from?.let(::at), to?.let(::at))
            .services
            .single()
            .count

        assertEquals(3, count(10, 30))
        assertEquals(1, count(20, 20))
        assertEquals(1, count(11, 29))
        assertEquals(0, count(21, 29))
        assertEquals(0, count(30, 10))
    }

    @Test
    fun aRangeIsOpenAtAnEndThatIsNotGiven() {
        listOf(10L, 20L, 30L).forEach { record(a, it) }

        val sinceTwenty = history.statistics(listOf(a), from = at(20)).services.single()
        val untilTwenty = history.statistics(listOf(a), to = at(20)).services.single()

        assertEquals(2 to at(20), sinceTwenty.count to sinceTwenty.earliestEntryTime)
        assertEquals(2 to at(10), untilTwenty.count to untilTwenty.earliestEntryTime)
    }

    @Test
    fun theTimeOfTheOldestEntryIsThatOfTheWholeHistoryWhateverTheRange() {
        record(null, 3)
        record(serviceOf("Deleted", "deleted"), 7)
        listOf(10L, 20L).forEach { record(a, it) }

        val statistics = history.statistics(listOf(a, b), from = at(15))

        assertEquals(at(3), statistics.oldestEntryTime)
        assertEquals(listOf(1, 0), statistics.services.map { it.count })
    }

    @Test
    fun theOldestEntryIsTheOneThatArrivedFirstAlsoWhenItWasRecordedLater() {
        record(a, 50)
        record(a, 40)

        assertEquals(at(40), history.statistics(listOf(a)).oldestEntryTime)
    }

    @Test
    fun onlyTheEntriesThatTheHistoryKeepsCount() {
        val limited = HistoryStore(maxEntries = 2)
        listOf(10L, 20L, 30L).forEach { limited.record(exchangeOf(proxied(a), time = at(it))) }

        val statistics = limited.statistics(listOf(a))

        assertEquals(ServiceStatistics(a.id, "Service A", 2, at(20)), statistics.services.single())
        assertEquals(at(20), statistics.oldestEntryTime)
    }

    @Test
    fun anEntryBelongsToItsServiceByTheIdentifierAndTheServiceHasTheNameThatItHasNow() {
        record(a, 10)
        val renamed = a.copy(name = "Renamed")
        val twin = serviceOf("Service A", "twin")

        val statistics = history.statistics(listOf(renamed, twin))

        assertEquals(listOf("Renamed" to 1, "Service A" to 0), statistics.services.map { it.serviceName to it.count })
    }

    // As JSON and as CSV

    @Test
    fun theStatisticsAreWrittenAsJson() {
        listOf(10L, 20L).forEach { record(a, it) }

        val json = Json.parseToJsonElement(history.statistics(listOf(a, b), from = at(15)).toJson()).jsonObject

        assertEquals(setOf("oldestEntryTime", "from", "to", "services"), json.keys)
        assertEquals("2026-11-24T12:00:10Z", json.getValue("oldestEntryTime").jsonPrimitive.content)
        assertEquals("2026-11-24T12:00:15Z", json.getValue("from").jsonPrimitive.content)
        assertEquals(JsonNull, json.getValue("to"))
        val (first, second) = json.getValue("services").jsonArray.map { it.jsonObject }
        assertEquals(setOf("serviceId", "serviceName", "count", "earliestEntryTime"), first.keys)
        assertEquals(listOf("svc-a", "Service A", "1", "2026-11-24T12:00:20Z"), first.values.map { it.jsonPrimitive.content })
        assertEquals(listOf("svc-b", "Service B", "0"), second.values.take(3).map { it.jsonPrimitive.content })
        assertEquals(JsonNull, second.getValue("earliestEntryTime"))
    }

    @Test
    fun theStatisticsAreWrittenAsCsvWithOneLineForEachService() {
        listOf(10L, 20L).forEach { record(a, it) }

        val csv = history.statistics(listOf(a, b)).toCsv()

        val expected =
            "Service identifier,Service name,Entries,Earliest entry,Oldest retained entry\r\n" +
                "svc-a,Service A,2,2026-11-24T12:00:10Z,2026-11-24T12:00:10Z\r\n" +
                "svc-b,Service B,0,,2026-11-24T12:00:10Z\r\n"
        assertEquals(expected, csv)
    }

    @Test
    fun statisticsWithoutServicesAreTheLineOfTheColumnNames() {
        assertEquals(
            "Service identifier,Service name,Entries,Earliest entry,Oldest retained entry\r\n",
            history.statistics(emptyList()).toCsv(),
        )
    }

    @Test
    fun aNameWithACommaAQuoteOrALineBreakStandsInQuotes() {
        val names = listOf("Orders, new", "The \"gold\" one", "two\nlines", " padded ", "plain")
        val statistics = HistoryStatistics(names.mapIndexed { index, name -> ServiceStatistics(ServiceId("svc-$index"), name) })

        val fields =
            statistics
                .toCsv()
                .split("\r\n")
                .drop(1)
                .dropLast(1)
                .map { it.removePrefix("svc-").drop(2).removeSuffix(",0,,") }

        assertEquals(listOf("\"Orders, new\"", "\"The \"\"gold\"\" one\"", "\"two\nlines\"", "\" padded \"", "plain"), fields)
    }

    @Test
    fun aNameThatASpreadsheetProgramWouldReadAsAFormulaGetsAnApostropheInFront() {
        val names = listOf("=HYPERLINK(\"http://evil.example\")", "+1", "-1", "@SUM(A1)", "\tcmd", "a=b", "1+1")
        val statistics = HistoryStatistics(names.mapIndexed { index, name -> ServiceStatistics(ServiceId("svc-$index"), name) })

        val fields =
            statistics
                .toCsv()
                .split("\r\n")
                .drop(1)
                .dropLast(1)
                .map { it.removePrefix("svc-").drop(2).removeSuffix(",0,,") }

        assertEquals(listOf("\"'=HYPERLINK(\"\"http://evil.example\"\")\"", "'+1", "'-1", "'@SUM(A1)", "'\tcmd", "a=b", "1+1"), fields)
    }
}
