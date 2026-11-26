package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.DerivedRealUrlDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ThScope
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.p
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/**
 * The pages that derive real URLs for all services at once: the form with a regular expression and its
 * replacements, and the page that lists the real URLs that the server added.
 *
 * See `docs/decisions/ui-pages.md` for the reasoning behind these pages.
 */
internal val RealUrlArea =
    Area { routing, ui ->
        with(routing) {
            get(RealUrlPages.PAGE) { ui.show(call) { view, _ -> RealUrlPages.derivation(view, FormState.EMPTY) } }
            post(RealUrlPages.PAGE) {
                val again: suspend (View, SystemStatusDto, FormState) -> Page = { view, _, state ->
                    RealUrlPages.derivation(view, state)
                }
                ui.submit(call, again) { input ->
                    val added = ui.client.deriveRealUrls(input.text(RealUrlForm.EXPRESSION), RealUrlForm.replacementsOf(input))
                    Outcome.Report(RealUrlPages.derived(added), added.size)
                }
            }
        }
    }

/** The pages that derive real URLs for all services. */
internal object RealUrlPages {
    /** The path of the form, which is also where the form is submitted to. */
    const val PAGE = "/services/real-urls"

    private const val REPLACEMENT_ROWS = 4
    private val COLUMNS = listOf("Service", "Added real URL")

    /** Returns the page with the form that derives real URLs. The form is displayed with [state]. */
    fun derivation(
        view: View,
        state: FormState,
    ): Page =
        Page("Derive real URLs") {
            p { +ABOUT }
            p { +EXAMPLE }
            postForm(view, PAGE) {
                rejectionOf(state)
                textField(state, RealUrlForm.EXPRESSION, "Regular expression", hint = EXPRESSION_HINT)
                textAreaField(state, RealUrlForm.REPLACEMENTS, "Replacements", hint = REPLACEMENTS_HINT, rows = REPLACEMENT_ROWS)
                otherProblemsOf(state)
                p(classes = "actions") {
                    submit("Derive the real URLs")
                    +" "
                    a(href = ServicesPages.OVERVIEW) { +"Cancel" }
                }
            }
        }

    /** Returns the page that lists [added], which are the real URLs that the server added, in the order of the server. */
    fun derived(added: List<DerivedRealUrlDto>): Page =
        Page("Derived real URLs") {
            p {
                +when (added.size) {
                    0 -> NOTHING_ADDED
                    1 -> "The server added 1 real URL."
                    else -> "The server added ${added.size} real URLs."
                }
            }
            if (added.isNotEmpty()) {
                div(classes = "scroll") {
                    table(classes = "derived") {
                        thead {
                            tr { COLUMNS.forEach { th(scope = ThScope.col) { +it } } }
                        }
                        tbody {
                            for (url in added) {
                                tr {
                                    th(scope = ThScope.row) { a(href = ServicePages.addressOf(url.serviceId)) { +url.serviceName } }
                                    td { urlOf(url.realUrl) }
                                }
                            }
                        }
                    }
                }
            }
            p(classes = "actions") {
                a(href = ServicesPages.OVERVIEW) { +"Services" }
                a(href = PAGE) { +"Derive more real URLs" }
            }
        }

    private const val ABOUT =
        "Environments that resemble each other often differ in one part of their addresses, such as qa1 and qa2. " +
            "This form adds the real URLs of such an environment to every service at once. For each real URL of a service, " +
            "it replaces what the regular expression finds, and it adds the result to the service as another real URL."
    private const val EXAMPLE =
        "For example, the expression qa1 with the replacement qa2 gives a service with the real URL " +
            "http://qa1.example.com/orders the real URL http://qa2.example.com/orders as well. " +
            "The existing real URLs stay as they are, and a URL that a service already lists is not added."
    private const val EXPRESSION_HINT = "The part of a real URL to replace, as a regular expression."
    private const val REPLACEMENTS_HINT =
        "One replacement per line. Every replacement adds a real URL of its own. " +
            "A replacement can refer to a group of the expression, such as \$1."
    private const val NOTHING_ADDED =
        "The server added no real URL. Either the expression found nothing in the real URLs, " +
            "or the services already list every derived URL."
}

/** The form that derives real URLs: the names of its fields, and what it reads from a submitted form. */
internal object RealUrlForm {
    /** The field for the regular expression. It has the name of the member of the request to the admin API. */
    const val EXPRESSION = "expression"

    /** The field for the replacements, one per line. It has the name of the member of the request to the admin API. */
    const val REPLACEMENTS = "replacements"

    /** Returns the replacements that the form holds: the lines of the field that aren't blank, exactly as typed. */
    fun replacementsOf(input: FormInput): List<String> = input.lines(REPLACEMENTS).split('\n').filter { it.isNotBlank() }
}
