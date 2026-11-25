package io.github.dnedrow.mockey.ui

import io.ktor.http.HttpStatusCode
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.code
import kotlinx.html.p

/**
 * The pages for a request that the server gave nothing for that the UI can show. Each says what is the
 * matter and names the server at [adminUrl], which is where the UI was told that the server is. A page shows
 * nothing that the server holds: a page that cannot show all that it is for shows none of it.
 *
 * The UI keeps nothing of a failure. It asks the server again for the next page, so the page of the server
 * is shown as soon as the server answers as the UI needs it.
 *
 * @param hasToken whether the UI was started with an admin token. The pages say that, and never the token.
 */
internal class FailurePages(
    private val adminUrl: AdminUrl,
    private val hasToken: Boolean,
) {
    /**
     * Returns the page for [failure]. A page for a request that only reads has a link that asks again, when
     * the request [canBeRepeated].
     */
    fun of(
        failure: ApiFailure,
        canBeRepeated: Boolean,
    ): Page =
        when (failure) {
            is ApiFailure.Unreachable -> unreachable(failure, canBeRepeated)
            is ApiFailure.TokenRejected -> tokenRejected(canBeRepeated)
            is ApiFailure.Incompatible -> incompatible(failure, canBeRepeated)
            is ApiFailure.Refused -> refused(failure)
            is ApiFailure.Failed -> failed(failure, canBeRepeated)
        }

    /** The scenario "Server is down": the page says that the server at the configured address cannot be reached. */
    private fun unreachable(
        failure: ApiFailure.Unreachable,
        canBeRepeated: Boolean,
    ): Page =
        Page("The server cannot be reached", HttpStatusCode.BadGateway) {
            p(classes = "problem") { +"The UI cannot reach the server at $adminUrl. ${failure.message}" }
            p {
                +"Start the server. Where the server runs at another address, start the UI with the URL of its admin listener, in "
                setting("admin-url")
                +"."
            }
            again(canBeRepeated)
        }

    private fun tokenRejected(canBeRepeated: Boolean): Page {
        val title = if (hasToken) "The server does not take the admin token of the UI" else "The server asks for an admin token"
        val matter =
            if (hasToken) {
                "The server at $adminUrl does not take the admin token that the UI was started with."
            } else {
                "The server at $adminUrl asks for an admin token, and the UI was started without one."
            }
        return Page(title, HttpStatusCode.BadGateway) {
            p(classes = "problem") { +matter }
            p {
                +"Start the UI with the admin token that the server was started with, in "
                setting("admin-token")
                +". The variable is the one that the server reads its token from."
            }
            again(canBeRepeated)
        }
    }

    /** The scenario "Wrong API version": the page says that the server is not compatible, and which version of the API the UI needs. */
    private fun incompatible(
        failure: ApiFailure.Incompatible,
        canBeRepeated: Boolean,
    ): Page =
        Page("The server is not compatible with this UI", HttpStatusCode.BadGateway) {
            p(classes = "problem") { +"${failure.reason} This UI needs the admin API of the version ${AdminClient.VERSION}." }
            p {
                +"Use a server and a UI of the same version of Mockey. The URL that the UI is started with, in "
                setting("admin-url")
                +", is the one of the admin listener of the server, which has the port 8081 unless the server is told otherwise. "
                +"The listener for the mock traffic of a server, with the port 8080, has no admin API."
            }
            again(canBeRepeated)
        }

    /** The page for a request that the admin API refuses: what the request names is not there, or the request is not valid. */
    private fun refused(failure: ApiFailure.Refused): Page {
        val missing = failure.problem.status == HttpStatusCode.NotFound.value
        val title = if (missing) "Not there" else "The server refused the request"
        return Page(title, if (missing) HttpStatusCode.NotFound else HttpStatusCode.BadGateway) {
            p { +"The UI asked the server at $adminUrl, which answered:" }
            p(classes = "problem") { +failure.problem.detail }
            for (error in failure.problem.errors) p(classes = "problem") { +"${error.field}: ${error.message}" }
        }
    }

    private fun failed(
        failure: ApiFailure.Failed,
        canBeRepeated: Boolean,
    ): Page =
        Page("The server failed", HttpStatusCode.BadGateway) {
            p { +"The UI asked the server at $adminUrl, which failed to handle the request and answered:" }
            p(classes = "problem") { +failure.problem.detail }
            p { +"The log of the server says more." }
            again(canBeRepeated)
        }

    /** Names the setting [name] by its option and its environment variable. */
    private fun FlowContent.setting(name: String) {
        code { +"--$name" }
        +" or "
        code { +UiCommand.variableOf(name) }
    }

    /**
     * Says that the UI asks the server again, with a link that does so where the request can be repeated.
     * The link refers to the page that it is on, so it says nothing of the address of the request.
     */
    private fun FlowContent.again(canBeRepeated: Boolean) {
        if (!canBeRepeated) return
        p {
            +"The UI asks the server again whenever a page is loaded. "
            a(href = "") { +"Load this page again" }
            +"."
        }
    }
}
