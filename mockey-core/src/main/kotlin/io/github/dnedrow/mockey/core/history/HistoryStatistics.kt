package io.github.dnedrow.mockey.core.history

import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * What the history says about the use of the services: for every service that is defined, how many entries
 * the history keeps of it, and when the earliest of them arrived.
 *
 * [from] and [to] are the time range that was asked for. An entry counts when its time is in that range, the
 * two bounds included, and a bound that is `null` leaves the range open at that end. [oldestEntryTime] is the
 * time of the oldest entry that the history keeps at all, whatever the range and whichever service handled it,
 * and `null` for a history without entries.
 */
public data class HistoryStatistics(
    public val services: List<ServiceStatistics>,
    public val oldestEntryTime: Instant? = null,
    public val from: Instant? = null,
    public val to: Instant? = null,
) {
    /**
     * Returns the statistics as a JSON object with the members `oldestEntryTime`, `from`, `to`, and `services`,
     * which is an array with one object for each service: `serviceId`, `serviceName`, `count`, and
     * `earliestEntryTime`. A time is a text in ISO 8601 format, or `null` where there is none.
     */
    public fun toJson(): String {
        val json =
            buildJsonObject {
                put("oldestEntryTime", oldestEntryTime.json())
                put("from", from.json())
                put("to", to.json())
                put("services", JsonArray(services.map(::json)))
            }
        return PRETTY.encodeToString(JsonObject.serializer(), json)
    }

    /**
     * Returns the statistics as CSV text, as RFC 4180 describes it: a line with the names of the columns, and
     * after it one line for each service, with its identifier, its name, the number of its entries, the time
     * of its earliest entry, and the time of the oldest entry that the history keeps, which is the same in
     * every line. A time is in ISO 8601 format, and it is empty where there is none.
     *
     * A name that a spreadsheet program would read as a formula gets an apostrophe in front, so that such a
     * program shows it as the text that it is.
     */
    public fun toCsv(): String {
        val rows = services.map { listOf(it.serviceId.value, it.serviceName, it.count.toString(), it.earliestEntryTime, oldestEntryTime) }
        return (
            listOf(
                CSV_COLUMNS,
            ) + rows
        ).joinToString("") { row -> row.joinToString(",") { csvField(it?.toString().orEmpty()) } + "\r\n" }
    }

    private companion object {
        val PRETTY = Json { prettyPrint = true }
        val CSV_COLUMNS = listOf("Service identifier", "Service name", "Entries", "Earliest entry", "Oldest retained entry")

        /** What begins a text that a spreadsheet program reads as a formula. */
        const val FORMULA_STARTS = "=+-@\t\r"

        /** What makes a field one that stands in quotes. */
        val NEEDS_QUOTES = Regex("""[",\r\n]|^\s|\s$""")

        fun Instant?.json() = if (this == null) JsonNull else JsonPrimitive(toString())

        fun json(service: ServiceStatistics): JsonObject =
            buildJsonObject {
                put("serviceId", service.serviceId.value)
                put("serviceName", service.serviceName)
                put("count", service.count)
                put("earliestEntryTime", service.earliestEntryTime.json())
            }

        fun csvField(text: String): String {
            val safe = if (text.isNotEmpty() && text.first() in FORMULA_STARTS) "'$text" else text
            return if (NEEDS_QUOTES.containsMatchIn(safe)) "\"${safe.replace("\"", "\"\"")}\"" else safe
        }
    }
}

/**
 * The statistics of one service: [count] is the number of entries that the history keeps of the requests that
 * the service handled, and [earliestEntryTime] is when the earliest of them arrived. A service without such
 * entries has the count 0 and no time.
 */
public data class ServiceStatistics(
    public val serviceId: ServiceId,
    public val serviceName: String,
    public val count: Int = 0,
    public val earliestEntryTime: Instant? = null,
)

/**
 * Returns the statistics of the history for [services], which are the services that are defined, in their
 * order. Only the entries whose time is between [from] and [to] count, the two bounds included. A bound that
 * is `null` leaves the range open at that end.
 *
 * An entry belongs to the service that handled its request, by the identifier of that service. The entries of
 * a service that is no longer defined, and those of requests that no service handled, belong to none of the
 * services. They count only for the time of the oldest entry.
 */
internal fun HistoryStore.statistics(
    services: List<Service>,
    from: Instant? = null,
    to: Instant? = null,
): HistoryStatistics {
    val kept = entries()
    val counted =
        kept
            .filter {
                (
                    from == null ||
                        !it.time.isBefore(
                            from,
                        )
                ) && (to == null || !it.time.isAfter(to))
            }.groupBy { it.service?.id }
    val ofServices =
        services.map { service ->
            val own = counted[service.id].orEmpty()
            ServiceStatistics(service.id, service.name, own.size, own.minOfOrNull { it.time })
        }
    return HistoryStatistics(ofServices, kept.minOfOrNull { it.time }, from, to)
}
