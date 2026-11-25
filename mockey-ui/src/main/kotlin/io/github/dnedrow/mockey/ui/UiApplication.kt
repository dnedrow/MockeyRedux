package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.SystemStatusDto
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.hooks.ReceiveRequestBytes
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.mutableOriginConnectionPoint
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.html.p
import org.slf4j.LoggerFactory

/**
 * What an area of the UI makes its pages and takes its forms with. An area is the pages about one kind of
 * thing, see [Area].
 */
internal interface Ui {
    /** The client through which the UI asks the server. */
    val client: AdminClient

    /**
     * Answers the request of [call] with the page that [page] makes. The UI first asks the server what it
     * states of itself, and gives that to [page] with what the page needs of the request. Where the server
     * gives nothing that the UI can show, the answer is a page that says so, see [FailurePages].
     */
    suspend fun show(
        call: ApplicationCall,
        page: suspend (View, SystemStatusDto) -> Page,
    )

    /**
     * Does what a form asks for: [act] gets what the form holds, asks the server to do it, and says what
     * came of it, see [Outcome]. The form is one that the UI took, see [FormTokens].
     *
     * When the server or [act] does not take what the form holds, the answer is the page that [again] makes,
     * which shows the form with what was sent and with what is the matter. Without [again], the answer is a
     * page that reports the refusal.
     */
    suspend fun submit(
        call: ApplicationCall,
        again: (suspend (View, SystemStatusDto, FormState) -> Page)? = null,
        act: suspend (FormInput) -> Outcome,
    )

    /**
     * Does [act] for a form that was sent from the page that [ask] makes, and answers every other request
     * with that page, see [Confirmation]. So what [act] does is done only after the UI has asked.
     */
    suspend fun confirm(
        call: ApplicationCall,
        ask: suspend (View, SystemStatusDto) -> Page,
        act: suspend (FormInput) -> Outcome,
    )
}

/**
 * Adds a route at [path] that answers a request for a page and a request with a form alike. A route that
 * asks before it acts is one, see [Ui.confirm]: the link to it asks for a page, and the form of that page is
 * sent to the same path.
 */
internal fun Route.getAndPost(
    path: String,
    body: suspend RoutingContext.() -> Unit,
) {
    get(path) { body() }
    post(path) { body() }
}

/** A part of the UI: the pages about one kind of thing, at the paths that it serves them at. */
internal fun interface Area {
    /** Adds the routes of the area to [routing]. The area makes its pages and takes its forms with [ui]. */
    fun install(
        routing: Route,
        ui: Ui,
    )
}

/**
 * The UI as an application of Ktor: what it answers at each of its paths, and what holds for every request.
 *
 * The UI shows what the server at [adminUrl] holds, which it asks for through [client] when a page is
 * loaded. It keeps nothing of it. Every page begins with what the server states of itself, so that a page
 * shows the data of a server that the UI can manage, or says why it shows none, see [FailurePages].
 *
 * The UI answers under the names that [hosts] has, and it changes something only for a form of one of its
 * own pages, see [AllowedHosts] and [FormTokens]. Its pages are those of [areas], and a form has at most
 * [formLimit] bytes.
 *
 * The UI uses the routing of Ktor, where the server program does not, see `docs/decisions/ui-stack.md`:
 * the paths of the UI are its own, they hold no secret, and a browser is what reads its answers.
 */
internal class UiApplication(
    private val adminUrl: AdminUrl,
    override val client: AdminClient,
    hasToken: Boolean,
    private val hosts: AllowedHosts,
    private val areas: List<Area> = AREAS,
    private val formLimit: Long = FormReader.LIMIT,
) : Ui {
    private val failures = FailurePages(adminUrl, hasToken)
    private val key = ProcessKey()
    private val tokens = FormTokens(key)
    private val notices = Notices(key)
    private val reports = Reports()

    /** Makes [application] the UI. */
    fun install(application: Application) {
        application.install(everyRequest)
        application.intercept(ApplicationCallPipeline.Plugins) {
            if (!admitted(call)) finish()
        }
        application.routing {
            get("/") { call.respondRedirect(ServicesPages.OVERVIEW) }
            get("${Assets.PREFIX}/{name}") { if (!Assets.respond(call, call.parameters["name"].orEmpty())) respond(call, notFound()) }
            get("$REPORTS/{id}") { showReport(call, call.parameters["id"].orEmpty()) }
            for (area in areas) area.install(this, this@UiApplication)
            route("{...}") { handle { respond(call, notFound()) } }
        }
    }

    /**
     * What holds for every request, around everything else:
     *
     * - The answer gets the headers of every answer.
     * - A request with the method `HEAD` is one with the method `GET` from here on. The page is made as for
     *   `GET`, and the HTTP engine leaves the body out of the answer.
     * - A request that fails gets a page that says so, see [failed], where Ktor would answer with a text
     *   that repeats the address of the request.
     * - Ktor takes a form of parts apart from the bytes that the UI has read of it, see [FormReader].
     */
    private val everyRequest =
        createApplicationPlugin("MockeyUiEveryRequest") {
            onCall { call ->
                call.headersOfEveryAnswer()
                if (call.request.httpMethod == HttpMethod.Head) call.mutableOriginConnectionPoint.method = HttpMethod.Get
            }
            on(ReceiveRequestBytes) { call, body -> FormReader.bodyFor(call, body) }
            on(CallFailed) { call, cause ->
                // An answer that has begun cannot become another one. What is left of it goes to nobody.
                if (cause is CancellationException || call.response.isCommitted) throw cause
                respond(call, failed(call, cause))
            }
        }

    /**
     * Returns whether the request of [call] is one that the UI goes on with, and answers it when it is not.
     * The UI goes on with a request that names the UI as it is known, see [AllowedHosts], and that asks for
     * a page or holds a form that the UI takes, see [FormTokens]. The form is kept for the route then. No
     * other request reaches a route, so none makes the UI ask the server for anything.
     */
    private suspend fun admitted(call: ApplicationCall): Boolean {
        val headers = call.request.headers
        val named = headers.getAll(HttpHeaders.Host).orEmpty()
        if (!hosts.allows(named)) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(Html.bare("The UI does not answer under this name", otherName(named)), HTML, HttpStatusCode.Forbidden)
            return false
        }
        val refusal =
            when (call.request.httpMethod) {
                HttpMethod.Get -> null
                HttpMethod.Post -> refusalOfForm(call)
                else -> NO_SUCH_METHOD.also { call.response.header(HttpHeaders.Allow, "GET, HEAD, POST") }
            }
        if (refusal != null) respond(call, refusal)
        return refusal == null
    }

    /**
     * Returns what the UI says to a request with the `Host` headers [named], none of which is a name of the
     * UI. It repeats the name when the request has one that is written as a name is, so that the one who
     * runs the UI behind another name reads which name to tell the UI.
     */
    private fun otherName(named: List<String>): String {
        val name = named.singleOrNull()?.let(AllowedHosts::hostOf) ?: return "The request names no host that the UI goes by. $ITS_NAMES"
        val how = "A UI that is to answer under this name is started with --allowed-host $name."
        return "The request names the host $name, which the UI does not go by. $ITS_NAMES $how"
    }

    /**
     * Returns the page that refuses the form of the request of [call], or `null` when the UI takes the
     * form. A request that comes without the value of a browser is refused before its body is read.
     */
    private suspend fun refusalOfForm(call: ApplicationCall): Page? {
        if (!tokens.hasValue(call)) return NOT_OF_THIS_UI
        val form =
            try {
                FormReader.read(call, formLimit)
            } catch (unreadable: FormReader.Unreadable) {
                return Page("The form cannot be read", unreadable.status) { p(classes = "problem") { +"${unreadable.message} $UNCHANGED" } }
            }
        if (!tokens.accepts(call, form.text(FormTokens.FIELD))) return NOT_OF_THIS_UI
        call.attributes.put(FORM, form)
        return null
    }

    override suspend fun show(
        call: ApplicationCall,
        page: suspend (View, SystemStatusDto) -> Page,
    ) {
        val system = client.getSystem()
        call.attributes.put(SYSTEM, system)
        respond(call, page(View(tokens.tokenFor(call)), system))
    }

    override suspend fun submit(
        call: ApplicationCall,
        again: (suspend (View, SystemStatusDto, FormState) -> Page)?,
        act: suspend (FormInput) -> Outcome,
    ) {
        val form = call.attributes[FORM]
        val outcome =
            try {
                act(form)
            } catch (refused: ApiFailure.Refused) {
                if (again == null || refused.problem.status !in ABOUT_THE_FORM) throw refused
                return showAgain(call, again, FormState(form.values, refused.problem.errors, refused.problem.detail))
            } catch (invalid: InvalidInput) {
                // A form without a page to show it again has nothing that a person entered, such as a switch of a page.
                if (again == null) return respond(call, notTaken(invalid))
                return showAgain(call, again, FormState(form.values, invalid.errors))
            }
        when (outcome) {
            is Outcome.Done -> seeOther(call, outcome.to, outcome.notice)
            is Outcome.Report -> seeOther(call, "$REPORTS/${reports.keep(outcome.page, outcome.entries)}")
            is Outcome.Shown -> respond(call, outcome.page)
        }
    }

    override suspend fun confirm(
        call: ApplicationCall,
        ask: suspend (View, SystemStatusDto) -> Page,
        act: suspend (FormInput) -> Outcome,
    ) {
        val form = call.attributes.getOrNull(FORM)
        if (form != null && Confirmation.isGiven(form)) submit(call, act = act) else show(call, ask)
    }

    /** Answers with the form that was not taken, as the page that [again] makes of [state]. */
    private suspend fun showAgain(
        call: ApplicationCall,
        again: suspend (View, SystemStatusDto, FormState) -> Page,
        state: FormState,
    ) {
        val system = client.getSystem()
        call.attributes.put(SYSTEM, system)
        val page = again(View(tokens.tokenFor(call)), system, state)
        respond(call, Page(page.title, HttpStatusCode.UnprocessableEntity, page.content))
    }

    /**
     * Sends the browser to [path], which is a path of the UI, after a form that the UI has done. The
     * browser asks for that page with the method `GET`, so loading the page again sends no form again. The
     * page says [notice], when there is one.
     */
    private suspend fun seeOther(
        call: ApplicationCall,
        path: String,
        notice: String? = null,
    ) {
        require(path.startsWith("/") && !path.startsWith("//") && '\\' !in path) { "A form leads to a path of the UI, and not to $path." }
        if (notice != null) notices.leave(call, notice)
        call.response.header(HttpHeaders.Location, path)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(HttpStatusCode.SeeOther)
    }

    /**
     * Answers with the page of the report [id]. The UI keeps the report itself, so it shows it whatever the
     * server says, and says what is the matter with the server where something is.
     */
    private suspend fun showReport(
        call: ApplicationCall,
        id: String,
    ) {
        val trouble =
            try {
                call.attributes.put(SYSTEM, client.getSystem())
                null
            } catch (failure: ApiFailure) {
                failures.inASentence(failure)
            }
        respond(call, reports.find(id) ?: REPORT_GONE, trouble)
    }

    /**
     * Answers the request of [call] with [page], in the frame of every page, see [Html]. A page says what
     * the server holds now, so nothing is to store it.
     */
    private suspend fun respond(
        call: ApplicationCall,
        page: Page,
        trouble: String? = null,
    ) {
        val notice = if (call.request.httpMethod == HttpMethod.Get) notices.take(call) else null
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val document = Html.document(page, call.request.path(), adminUrl, call.attributes.getOrNull(SYSTEM), notice, trouble)
        call.respondText(document, HTML, page.status)
    }

    /** Returns the page for the request of [call], which failed with [cause]. */
    private fun failed(
        call: ApplicationCall,
        cause: Throwable,
    ): Page {
        if (cause is ApiFailure) return failures.of(cause, canBeRepeated = call.request.httpMethod == HttpMethod.Get)
        if (cause is BadRequestException) return unreadable()
        if (cause is NoSuchPage) return notFound()
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

    /** The page for a form that holds what the UI itself does not take, which [invalid] says, and that no page shows again. */
    private fun notTaken(invalid: InvalidInput): Page =
        Page("The form was not taken", HttpStatusCode.UnprocessableEntity) {
            invalid.errors.forEach { p(classes = "problem") { +it.message } }
            p { +UNCHANGED }
        }

    internal companion object {
        /** The areas of the UI. */
        val AREAS: List<Area> = listOf(ServicesArea, DiagnosticsArea, SystemArea)

        private val log = LoggerFactory.getLogger(UiApplication::class.java)

        private val HTML = ContentType.Text.Html.withCharset(Charsets.UTF_8)

        /** The path under which the pages of the reports are. */
        private const val REPORTS = "/reports"

        /** What the server stated of itself for a request, from the moment at which the UI has it. */
        private val SYSTEM = AttributeKey<SystemStatusDto>("MockeyUiSystem")

        /** The form of a request, from the moment at which the UI has taken it. */
        private val FORM = AttributeKey<FormInput>("MockeyUiForm")

        /** The statuses with which the admin API says that it does not take what a request holds: too much, or not what it reads. */
        private val ABOUT_THE_FORM = setOf(400, 413, 422)

        private const val UNCHANGED = "Nothing was changed."

        private const val ITS_NAMES =
            "The UI answers under an IP address, under localhost, and under the names that it is started with, " +
                "in --allowed-host or MOCKEY_UI_ALLOWED_HOST. So no page of another site reads or changes what the UI manages."

        private val NO_SUCH_METHOD =
            Page("Not a request for a page", HttpStatusCode.MethodNotAllowed) {
                p { +"The UI takes requests with the methods GET, HEAD, and POST, as a browser sends them for a page and for a form." }
            }

        private val NOT_OF_THIS_UI =
            Page("The form was not taken", HttpStatusCode.Forbidden) {
                p(classes = "problem") {
                    +"The UI takes a form only from one of its own pages, in the browser that the page was loaded in. $UNCHANGED"
                }
                p {
                    +"A form is not taken when it comes from a page of another site, when the browser does not keep cookies, "
                    +"and when the UI was started anew after the page of the form was loaded. "
                    +"Go back to the page of the form, load it again, and send the form again."
                }
            }

        private val REPORT_GONE =
            Page("The report is gone", HttpStatusCode.NotFound) {
                p {
                    +"The UI keeps the report of a form for a while, and it does not keep this one any longer. "
                    +"What the report was about stays done."
                }
            }

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
