package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.HistoryStatisticsDto
import io.github.dnedrow.mockey.api.ServiceStatisticsDto
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.routing.get
import kotlinx.html.FlowContent
import kotlinx.html.ThScope
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.meter
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The statistics page, which says how many history entries the server keeps for each service, and the
 * downloads of the same numbers as CSV and as JSON.
 *
 * See `docs/decisions/ui-pages.md` for the reasoning behind this page.
 */
internal val StatisticsArea =
    Area { routing, ui ->
        with(routing) {
            get(StatisticsPages.PAGE) {
                val range = TimeRange.of(call.request.queryParameters)
                ui.show(call) { _, _ ->
                    StatisticsPages.statistics(range, if (range.isValid) ui.client.getStatistics(range.from, range.to) else null)
                }
            }
            get(StatisticsPages.DOWNLOAD) {
                val range = TimeRange.of(call.request.queryParameters)
                val format = call.request.queryParameters[StatisticsPages.FORMAT]
                if (format != StatisticsPages.CSV && format != StatisticsPages.JSON) throw NoSuchPage()
                // An address with a time that can't be read gets the page that says so, and no file.
                if (!range.isValid) return@get ui.show(call) { _, _ -> StatisticsPages.statistics(range, null) }
                ui.download(call) {
                    if (format == StatisticsPages.CSV) {
                        Download("mockey-statistics.csv", ContentType.Text.CSV, ui.client.getStatisticsAsCsv(range.from, range.to))
                    } else {
                        val json = ui.client.getStatisticsAsJson(range.from, range.to)
                        Download("mockey-statistics.json", ContentType.Application.Json, json)
                    }
                }
            }
        }
    }

/**
 * The time range of the statistics, as the address of the page holds it. [fromText] and [toText] are the two
 * bounds as they were entered, and an empty text leaves that end of the range open.
 *
 * The pages show every time in UTC, so a time without an offset is read as UTC. A bound can be written the
 * way a `datetime-local` field submits it (`2026-01-02T03:04:05`), the way the pages display a time
 * (`2026-01-02 03:04:05 UTC`), or as an ISO 8601 time with an offset (`2026-01-02T03:04:05+01:00`).
 */
internal class TimeRange(
    val fromText: String,
    val toText: String,
) {
    private val fromTime: Instant? = timeOf(fromText)
    private val toTime: Instant? = timeOf(toText)

    /** The start of the range as the admin API reads it, or `null` if that end is open or unreadable. */
    val from: String? = fromTime?.toString()

    /** The end of the range as the admin API reads it, or `null` if that end is open or unreadable. */
    val to: String? = toTime?.toString()

    /** The messages for the bounds that can't be read, each for the field of its bound. */
    val errors: List<FieldErrorDto> =
        listOfNotNull(
            FieldErrorDto(FROM, unreadable(fromText)).takeIf { fromText.isNotBlank() && fromTime == null },
            FieldErrorDto(TO, unreadable(toText)).takeIf { toText.isNotBlank() && toTime == null },
        )

    /** Whether both bounds can be read. */
    val isValid: Boolean = errors.isEmpty()

    /** Whether the range limits the statistics at all. */
    val isSet: Boolean = fromText.isNotBlank() || toText.isNotBlank()

    /**
     * Returns the state of the form that edits the range. A bound that can be read is shown the way a
     * `datetime-local` field expects it. A bound that can't be read leaves its field empty, and the message
     * next to the field repeats what was entered.
     */
    fun formState(): FormState {
        val readable = listOfNotNull(fromTime?.let { FROM to it }, toTime?.let { TO to it })
        return FormState(readable.associate { (name, time) -> name to listOf(FIELD_FORMAT.format(time)) }, errors)
    }

    internal companion object {
        /** The address parameter that holds the start of the range. */
        const val FROM = "from"

        /** The address parameter that holds the end of the range. */
        const val TO = "to"

        /** Returns the message for the bound [text], which can't be read. A browser can't show such a text in the field. */
        private fun unreadable(text: String): String =
            "${briefly(text.trim())} is not a time that the UI can read. Write it like 2026-01-02T03:04:05, in UTC."

        /** How a `datetime-local` field writes a time: to the second, without an offset. */
        private val FIELD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)

        /** Returns the range that [parameters] describe. */
        fun of(parameters: Parameters): TimeRange = TimeRange(parameters[FROM].orEmpty(), parameters[TO].orEmpty())

        /** Returns the time that [text] holds, or `null` if it's blank or isn't a time. */
        private fun timeOf(text: String): Instant? {
            val entered = text.trim()
            if (entered.isEmpty()) return null
            // The pages display a time with a space after the date and "UTC" at the end.
            val local = entered.removeSuffix("UTC").trim().replaceFirst(' ', 'T')
            return parsed { OffsetDateTime.parse(entered).toInstant() } ?: parsed { LocalDateTime.parse(local).toInstant(ZoneOffset.UTC) }
        }

        private fun parsed(parse: () -> Instant): Instant? =
            try {
                parse()
            } catch (_: DateTimeException) {
                null
            }
    }
}

/** The statistics page. */
internal object StatisticsPages {
    /** The path of the page. */
    const val PAGE = "/statistics"

    /** The path of the downloads. */
    const val DOWNLOAD = "/statistics/download"

    /** The address parameter that holds the format of a download: [CSV] or [JSON]. */
    const val FORMAT = "format"
    const val CSV = "csv"
    const val JSON = "json"

    private val COLUMNS = listOf("Service", "Entries", "Share", "Earliest entry")

    private val BY_NAME = compareBy<ServiceStatisticsDto, String>(String.CASE_INSENSITIVE_ORDER) { it.serviceName }.thenBy { it.serviceId }

    /**
     * Returns the statistics page for [range]. [statistics] is what the server counted for that range, or
     * `null` if the range can't be read. In that case, the page shows the form with a message and no numbers.
     */
    fun statistics(
        range: TimeRange,
        statistics: HistoryStatisticsDto?,
    ): Page =
        Page("History statistics", if (range.isValid) HttpStatusCode.OK else HttpStatusCode.BadRequest) {
            p { +INTRO }
            val state = range.formState()
            getForm(PAGE, classes = "filter") {
                timeField(state, TimeRange.FROM, "From (UTC)", hint = FROM_HINT)
                timeField(state, TimeRange.TO, "To (UTC)", hint = TO_HINT)
                submit("Show")
            }
            if (range.isSet) {
                p(classes = "count") {
                    if (range.isValid) +"Only the requests within the time range are counted. "
                    a(href = PAGE) { +"Count the whole history" }
                }
            }
            if (statistics != null) numbersOf(range, statistics)
            p(classes = "actions") { a(href = HistoryPages.LIST) { +"History" } }
        }

    /** Shows the table of [statistics], the time of the oldest entry, and the download links for [range]. */
    private fun FlowContent.numbersOf(
        range: TimeRange,
        statistics: HistoryStatisticsDto,
    ) {
        if (statistics.services.isEmpty()) {
            p { +"There are no services, so there is nothing to count." }
        } else {
            val most = statistics.services.maxOf { it.count }
            div(classes = "scroll") {
                table(classes = "statistics") {
                    thead {
                        tr { COLUMNS.forEach { th(scope = ThScope.col) { +it } } }
                    }
                    tbody {
                        for (service in statistics.services.sortedWith(BY_NAME)) {
                            tr {
                                th(scope = ThScope.row) { a(href = ServicePages.addressOf(service.serviceId)) { +service.serviceName } }
                                td {
                                    val history = HistoryPages.addressOfService(service.serviceId)
                                    if (service.count > 0) a(href = history) { +"${service.count}" } else +"0"
                                }
                                td { if (most > 0) shareOf(service.count, most) }
                                td {
                                    val earliest = service.earliestEntryTime
                                    if (earliest == null) span(classes = "none") { +"none" } else timeOf(earliest)
                                }
                            }
                        }
                    }
                }
            }
        }
        p {
            val oldest = statistics.oldestEntryTime
            if (oldest == null) {
                +"The history is empty."
            } else {
                +"The oldest entry that the history keeps is from "
                timeOf(oldest)
                +"."
            }
        }
        p(classes = "hint") { +NOT_COUNTED }
        p(classes = "actions") {
            a(href = downloadOf(CSV, range)) { +"Download as CSV" }
            a(href = downloadOf(JSON, range)) { +"Download as JSON" }
        }
    }

    /** Shows [count] as a bar. The longest bar stands for [most], which is the highest count on the page. */
    private fun FlowContent.shareOf(
        count: Int,
        most: Int,
    ) {
        meter {
            attributes["min"] = "0"
            attributes["max"] = "$most"
            attributes["value"] = "$count"
            +"$count of $most"
        }
    }

    /** Returns the address of the download in [format] for [range]. */
    private fun downloadOf(
        format: String,
        range: TimeRange,
    ): String {
        val from = range.fromText.ifBlank { null }
        return Address.of(DOWNLOAD, FORMAT to format, TimeRange.FROM to from, TimeRange.TO to range.toText.ifBlank { null })
    }

    private const val INTRO =
        "The statistics say how many history entries the server keeps for each service. " +
            "A time range limits the count to the requests that arrived within it."
    private const val FROM_HINT = "Only requests from this time on are counted. Leave it empty to start with the oldest entry."
    private const val TO_HINT = "Only requests up to this time are counted. Leave it empty to end with the newest entry."
    private const val NOT_COUNTED = "A request that no service handled has a history entry, but it is not counted here."
}
