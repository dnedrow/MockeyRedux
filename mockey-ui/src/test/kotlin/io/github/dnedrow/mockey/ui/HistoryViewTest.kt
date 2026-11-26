package io.github.dnedrow.mockey.ui

import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlin.test.Test
import kotlin.test.assertEquals

/** How the history list is displayed, as its address says it: the filter tokens, the service, the page, and the automatic refresh. */
class HistoryViewTest {
    private fun viewOf(vararg parameters: Pair<String, String>): HistoryView =
        HistoryView.of(parametersOf(*parameters.map { (name, value) -> name to listOf(value) }.toTypedArray()))

    /** The address of a view leads back to the same view, and it only names what differs from the plain list. */
    @Test
    fun theAddressOfAViewLeadsBackToTheView() {
        val views =
            mapOf(
                HistoryView() to "/history",
                HistoryView(listOf("orders", "!gold")) to "/history?tokens=orders%20%21gold",
                HistoryView(service = "a/b c") to "/history?service=a%2Fb%20c",
                HistoryView(page = 3) to "/history?page=3",
                HistoryView(refresh = true) to "/history?refresh=on",
                HistoryView(listOf("x"), "svc", 2, true) to "/history?tokens=x&service=svc&page=2&refresh=on",
            )

        for ((view, address) in views) {
            assertEquals(address, view.address())
            val query = address.substringAfter('?', "")
            assertEquals(view, HistoryView.of(io.ktor.http.parseQueryString(query)), address)
        }
    }

    /** A parameter that's missing, empty, or unreadable gets its default, and the tokens are split at whitespace of any kind. */
    @Test
    fun anUnreadableParameterGetsItsDefault() {
        assertEquals(HistoryView(), HistoryView.of(Parameters.Empty))
        assertEquals(HistoryView(), viewOf("tokens" to "  ", "service" to "", "page" to "x", "refresh" to "yes"))
        assertEquals(HistoryView(), viewOf("page" to "0"))
        assertEquals(HistoryView(), viewOf("page" to "-5"))
        assertEquals(HistoryView(), viewOf("page" to "99999999999"))
        assertEquals(HistoryView(page = 1_000_000), viewOf("page" to "2000000"))
        assertEquals(HistoryView(listOf("a", "!b", "c")), viewOf("tokens" to " a\t!b\u00A0\n c "))
        assertEquals(HistoryView(refresh = false), viewOf("refresh" to "ON"))
    }

    /** An action of the list carries the view in its address, after its own parameters. */
    @Test
    fun anActionCarriesTheView() {
        val view = HistoryView(listOf("x"), "svc", 2, refresh = true)

        val ofFlag = view.address("/history/flag", "id" to "7", "from" to "list")
        assertEquals("/history/flag?id=7&from=list&tokens=x&service=svc&page=2&refresh=on", ofFlag)
        assertEquals("/history/remove?id=7", HistoryView().address("/history/remove", "id" to "7"))
    }

    /** The address of a row names the row, unless the page reloads itself. */
    @Test
    fun theAddressOfARowNamesTheRowUnlessThePageReloadsItself() {
        assertEquals("/history?page=2#entry-7", HistoryView(page = 2).addressOfRow(7))
        assertEquals("/history?refresh=on", HistoryView(refresh = true).addressOfRow(7))
        assertEquals(true to false, HistoryView(service = "s").isFiltered to HistoryView(page = 2, refresh = true).isFiltered)
    }
}
