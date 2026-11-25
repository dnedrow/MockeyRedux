package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.SystemStatusDto
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.mutableOriginConnectionPoint
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.html.p
import org.slf4j.LoggerFactory

/**
 * The UI as an application of Ktor: what it answers at each of its paths, and what holds for every answer.
 *
 * The UI shows what the server at [adminUrl] holds, which it asks for through [client] when a page is
 * loaded. It keeps nothing of it. Every page begins with what the server states of itself, so that a page
 * shows the data of a server that the UI can manage, or says why it shows none, see [FailurePages].
 *
 * The UI uses the routing of Ktor, where the server program does not, see `docs/decisions/ui-stack.md`:
 * the paths of the UI are its own, they hold no secret, and a browser is what reads its answers.
 */
internal class UiApplication(
    private val adminUrl: AdminUrl,
    private val client: AdminClient,
    hasToken: Boolean,
) {
    private val failures = FailurePages(adminUrl, hasToken)

    /** Makes [application] the UI. */
    fun install(application: Application) {
        application.install(everyRequest)
        application.routing {
            get("/") { call.respondRedirect("/system") }
            get("/system") { show { system -> SystemPages.status(system) } }
            get("${Assets.PREFIX}/{name}") { if (!Assets.respond(call, call.parameters["name"].orEmpty())) respond(call, notFound()) }
            route("{...}") { handle { respond(call, notFound()) } }
        }
    }

    /**
     * What holds for every request, around its route:
     *
     * - The answer gets the headers of every answer.
     * - A request with the method `HEAD` is one with the method `GET` from here on. The page is made as for
     *   `GET`, and the HTTP engine leaves the body out of the answer.
     * - A request that fails gets a page that says so, see [failed], where Ktor would answer with a text
     *   that repeats the address of the request.
     */
    private val everyRequest =
        createApplicationPlugin("MockeyUiEveryRequest") {
            onCall { call ->
                call.headersOfEveryAnswer()
                if (call.request.httpMethod == HttpMethod.Head) call.mutableOriginConnectionPoint.method = HttpMethod.Get
            }
            on(CallFailed) { call, cause ->
                // An answer that has begun cannot become another one. What is left of it goes to nobody.
                if (cause is CancellationException || call.response.isCommitted) throw cause
                respond(call, failed(call, cause))
            }
        }

    /**
     * Answers with the page that [page] makes of what the server states of itself, which the UI asks the
     * server for first. The frame of the page then names the server with its version and its mode.
     */
    private suspend fun RoutingContext.show(page: suspend (SystemStatusDto) -> Page) {
        val system = client.getSystem()
        call.attributes.put(SYSTEM, system)
        respond(call, page(system))
    }

    /**
     * Answers the request of [call] with [page], in the frame of every page, see [Html]. A page says what
     * the server holds now, so nothing is to store it.
     */
    private suspend fun respond(
        call: ApplicationCall,
        page: Page,
    ) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(Html.document(page, call.request.path(), adminUrl, call.attributes.getOrNull(SYSTEM)), HTML, page.status)
    }

    /** Returns the page for the request of [call], which failed with [cause]. */
    private fun failed(
        call: ApplicationCall,
        cause: Throwable,
    ): Page {
        if (cause is ApiFailure) return failures.of(cause, canBeRepeated = call.request.httpMethod == HttpMethod.Get)
        if (cause is BadRequestException) return unreadable()
        // The path is logged as the request wrote it, with its escapes, so it adds no line to the log.
        log.error("The UI failed to answer the request for {}.", call.request.path(), cause)
        return Page("The UI failed", HttpStatusCode.InternalServerError) {
            p { +"The UI failed to answer this request. Its log says why." }
        }
    }

    /** The page for a request whose address Ktor cannot read, because an escape in it is none. The page repeats nothing of the request. */
    private fun unreadable(): Page =
        Page("Not an address", HttpStatusCode.BadRequest) {
            p { +"The UI cannot read the address of this request: a percent sign in it does not begin an escape." }
        }

    private fun notFound(): Page = Page("Not there", HttpStatusCode.NotFound) { p { +"The UI has no page at this address." } }

    private companion object {
        private val log = LoggerFactory.getLogger(UiApplication::class.java)

        private val HTML = ContentType.Text.Html.withCharset(Charsets.UTF_8)

        /** What the server stated of itself for a request, from the moment at which the UI has it. */
        private val SYSTEM = AttributeKey<SystemStatusDto>("MockeyUiSystem")

        /**
         * What a browser may load for a page, and from where: stylesheets, scripts, images, and fonts from
         * the UI itself, and nothing from anywhere else. A page also runs no script that is written into it,
         * sends its forms nowhere but to the UI, and is shown in no frame of another page.
         */
        private const val CONTENT_SECURITY_POLICY =
            "default-src 'none'; style-src 'self'; script-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'; " +
                "form-action 'self'; base-uri 'none'; frame-ancestors 'none'"

        private fun ApplicationCall.headersOfEveryAnswer() {
            response.header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
            // A browser takes an answer for what the answer says it is, and not for what its bytes look like.
            response.header("X-Content-Type-Options", "nosniff")
            response.header("X-Frame-Options", "DENY")
            // The address of a page holds what the page filters by, which no other site is to get.
            response.header("Referrer-Policy", "no-referrer")
        }
    }
}
