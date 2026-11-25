package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.storage.StorageException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLDecodeException
import io.ktor.http.content.TextContent
import io.ktor.http.decodeURLPart
import io.ktor.http.decodeURLQueryComponent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.time.Instant

/**
 * The admin API: the one handler of the admin listener, which answers every request whose path is `/api/v1`
 * or lies below it. A request for any other path is none of the admin API.
 *
 * The API is no routing of Ktor. That routing takes `//api//v1` and `/%61pi/v1` for `/api/v1`, it knows no
 * request target that is a URL, it answers a path with a malformed escape with the request target, and it
 * writes the path of every request to the log at the most verbose level, see `docs/decisions/http-stack.md`.
 * Like the mock endpoint, the API reads the request target as the client sent it, and it writes nothing of
 * a request to the log.
 *
 * A request takes these steps, and the first one that fails gives the answer:
 *
 * 1. While the server has an admin token, the request has to present it, see [presentsToken]. A request
 *    that does not gets the status 401, whatever it is for, and nothing is read or changed for it.
 * 2. A request that can change something must not come from a web page, see [comesFromAPage]. One that
 *    does gets the status 403, whatever it is for.
 * 3. The path below the prefix names a resource. It is taken apart at its slashes, and a path that no route
 *    is for gets the status 404.
 * 4. The method names the operation on the resource. `HEAD` stands for `GET`, and the HTTP engine leaves
 *    the body out of the answer. A resource that has no operation with the method gets the status 405, with
 *    the methods that it does have.
 * 5. What the path has at the parameters of the route is decoded. That comes after the path was taken
 *    apart, so that an escaped slash stays in its segment. A malformed escape gets the status 400.
 * 6. The query string is taken apart, see [queryOf]. A malformed escape in it gets the status 400, and so
 *    does a parameter that the operation does not take.
 * 7. The operation answers, see [AdminRoute.handle]. An operation that fails gets the status 500. The answer
 *    says why when the definitions location is why: when the definitions could not be read from it or
 *    written to it. Any other reason is in the log and not in the answer.
 *
 * The body of an answer is JSON in UTF-8. An answer that reports a failure is a problem document, see
 * [AdminProblem]. Every answer says that it is not to be stored: what the API says of the server now says
 * nothing about later. No answer carries a header that lets a page of another origin read it.
 *
 * A request that the server forwarded as mock traffic takes none of these steps: the admin listener refuses
 * it before the API sees it, see [ForwardMark].
 *
 * [token] is the admin token of the server, or `null` when it has none. [maxRequestBody] is the number of
 * bytes that the body of a request may have. [routes] are the operations of the API, of which no two have
 * the same method for the same resources.
 */
internal class AdminApi(
    private val token: AdminToken?,
    private val maxRequestBody: Long,
    routes: List<AdminRoute>,
) {
    private val routes = routes.toList()

    init {
        val twice = routes.groupBy { it.method to it.form }.filterValues { it.size > 1 }
        require(twice.isEmpty()) { "The admin API has more than one operation for the same requests: ${twice.values.flatten()}." }
    }

    /** Answers the request of [call] when it is for the admin API, and returns whether it is. */
    suspend fun handle(call: ApplicationCall): Boolean {
        val path = RequestTarget.parse(call.request.local.uri).path
        if (path != PREFIX && !path.startsWith("$PREFIX/")) return false
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val response =
            try {
                answer(call, path.removePrefix(PREFIX).removePrefix("/"))
            } catch (problem: AdminProblem) {
                val document = AdminApiJson.instance.encodeToString(problem.document())
                AdminResponse(problem.status, document, AdminProblem.CONTENT_TYPE, problem.headers)
            }
        response.headers.forEach { (name, value) -> call.response.headers.append(name, value) }
        val body = response.body
        if (body == null) call.respond(response.status) else call.respond(TextContent(body, response.contentType, response.status))
        return true
    }

    /**
     * Returns the answer to the request of [call] for [resource], which is the path below the prefix as the
     * client wrote it.
     *
     * @throws AdminProblem when the request does not get the answer of an operation.
     */
    private suspend fun answer(
        call: ApplicationCall,
        resource: String,
    ): AdminResponse {
        if (!presentsToken(call)) {
            throw AdminProblem(HttpStatusCode.Unauthorized, NO_TOKEN, headers = mapOf(HttpHeaders.WWWAuthenticate to BEARER))
        }
        if (comesFromAPage(call)) throw AdminProblem(HttpStatusCode.Forbidden, OF_A_PAGE)
        val (route, parameters) = routeOf(call.request.local.method, resource)
        val request = AdminRequest(call, parameters, queryOf(call, route), maxRequestBody)
        return try {
            route.handle(request)
        } catch (problem: AdminProblem) {
            throw problem
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: StorageException) {
            // What the storage says is about the definitions location, and it is for whoever looks after it.
            log.error("The operation {} of the admin API failed at the definitions location.", route, failure)
            throw AdminProblem(HttpStatusCode.InternalServerError, "$NOT_STORED ${failure.message}")
        } catch (failure: Exception) {
            // The route names its path as it is defined, and so the message holds nothing of the request.
            log.error("The operation {} of the admin API failed.", route, failure)
            throw AdminProblem(HttpStatusCode.InternalServerError, FAILED)
        }
    }

    /**
     * Returns whether the request of [call] presents the admin token as a bearer token: in its one
     * `Authorization` header, after the scheme `Bearer` in any letter case. A request has nothing to present
     * while the server has no token.
     *
     * The token is compared as the bytes that the client sent, and Netty hands each byte of a header on as
     * one character. So a token with a character beyond ASCII is found as well.
     */
    private fun presentsToken(call: ApplicationCall): Boolean {
        val token = token ?: return true
        val credentials =
            call.request.headers
                .getAll(HttpHeaders.Authorization)
                ?.singleOrNull() ?: return false
        val scheme = credentials.substringBefore(' ')
        val presented = credentials.substring(scheme.length).trimStart(' ')
        return scheme.equals(BEARER, ignoreCase = true) && token.matches(asTheClientSent(presented))
    }

    /**
     * Returns whether the request of [call] can change something and comes from a web page: its method is
     * neither `GET` nor `HEAD`, and it carries an `Origin` header.
     *
     * A browser sends that header with every request of a page whose method is neither `GET` nor `HEAD`,
     * also where it has nothing to say in it and sends `null`. A page of any origin can make the browser of
     * someone on the machine of the server send a request to the admin listener, which the loopback address
     * lets in. No page is a client of the admin API, and a program sends no such header by itself.
     * `docs/decisions/web-pages-and-the-admin-api.md` says more.
     */
    private fun comesFromAPage(call: ApplicationCall): Boolean {
        val method = call.request.local.method
        return method != HttpMethod.Get && method != HttpMethod.Head && call.request.headers.contains(HttpHeaders.Origin)
    }

    /**
     * Returns the route of the operation that a request with [method] for [resource] asks for, and what the
     * path has at the parameters of that route, decoded.
     *
     * Of the routes that are for the path, the ones with the greatest precedence count, see
     * [AdminRoute.precedence]. They have one form, and so they are the operations of one resource.
     *
     * What a path has at a parameter is read as UTF-8 before its escapes are decoded, because a client that
     * does not escape a character beyond ASCII sends its bytes, and Netty hands each byte on as one character.
     *
     * @throws AdminProblem when no route is for the path, when the resource has no operation with the
     *   method, and when a parameter has a malformed escape.
     */
    private fun routeOf(
        method: HttpMethod,
        resource: String,
    ): Pair<AdminRoute, Map<String, String>> {
        val segments = resource.split('/')
        val reached = routes.mapNotNull { route -> route.parametersOf(segments)?.let { route to it } }
        val closest = reached.maxOfOrNull { (route, _) -> route.precedence } ?: throw AdminProblem(HttpStatusCode.NotFound, NO_RESOURCE)
        val ofResource = reached.filter { (route, _) -> route.precedence == closest }
        val asked = if (method == HttpMethod.Head) HttpMethod.Get else method
        val operation = ofResource.firstOrNull { (route, _) -> route.method == asked }
        val (route, written) = operation ?: throw noOperation(ofResource.map { it.first })
        return try {
            route to written.mapValues { (_, text) -> asTheClientSent(text).decodeURLPart() }
        } catch (_: URLDecodeException) {
            throw AdminProblem(HttpStatusCode.BadRequest, MALFORMED_PATH)
        }
    }

    /**
     * Returns the parameters of the query string of the request of [call], by their names, each with its
     * values in their order. The query string is what follows the first `?` of the request target. Its
     * parameters stand between `&` signs, and the value of a parameter follows its first `=`. Names and
     * values are decoded as a form writes them: a `+` stands for a space, and an escape for a byte of a
     * character in UTF-8.
     *
     * The problem of a parameter that [route] does not take names the parameters that it does take, and
     * not the one of the request, which can hold what the client did not mean to send.
     *
     * @throws AdminProblem with the status 400 for a malformed escape, and for a parameter that the
     *   operation does not take.
     */
    private fun queryOf(
        call: ApplicationCall,
        route: AdminRoute,
    ): Map<String, List<String>> {
        val written = RequestTarget.parse(call.request.local.uri).query.orEmpty()
        val parameters =
            try {
                written.split('&').filter { it.isNotEmpty() }.map { parameter ->
                    val name = parameter.substringBefore('=')
                    val value = parameter.substring(name.length).removePrefix("=")
                    asTheClientSent(name).decodeURLQueryComponent(plusIsSpace = true) to
                        asTheClientSent(value).decodeURLQueryComponent(plusIsSpace = true)
                }
            } catch (_: URLDecodeException) {
                throw AdminProblem(HttpStatusCode.BadRequest, MALFORMED_QUERY)
            }
        if (parameters.any { (name, _) -> name !in route.query }) throw AdminProblem(HttpStatusCode.BadRequest, otherParameter(route))
        return parameters.groupBy({ it.first }, { it.second })
    }

    /** Returns what the problem of a request says that has a parameter in its query string which [route] does not take. */
    private fun otherParameter(route: AdminRoute): String {
        val taken = if (route.query.isEmpty()) "It takes no parameter." else "It takes these: ${route.query.joinToString()}."
        return "The query string of the request has a parameter that the operation does not take. $taken"
    }

    /**
     * Returns the problem of a request with a method that none of [operations] has, which are the operations
     * of the resource of the request. The answer lists their methods in its `Allow` header, and with `GET`
     * the method `HEAD`.
     */
    private fun noOperation(operations: List<AdminRoute>): AdminProblem {
        val methods = operations.flatMap { if (it.method == HttpMethod.Get) listOf("GET", "HEAD") else listOf(it.method.value) }
        val allow = mapOf(HttpHeaders.Allow to methods.joinToString())
        return AdminProblem(HttpStatusCode.MethodNotAllowed, NO_OPERATION, headers = allow)
    }

    /** Returns the text whose bytes in UTF-8 are the characters of [text], each of which is one byte that a client sent. */
    private fun asTheClientSent(text: String): String = String(text.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)

    internal companion object {
        /** The path prefix of the admin API. It states the version of the API. */
        const val PREFIX = "/api/v1"

        private val log = LoggerFactory.getLogger(AdminApi::class.java)

        private const val BEARER = "Bearer"

        private const val NO_TOKEN =
            "The admin API takes a request only with the admin token, which a request presents as a bearer token " +
                "in its Authorization header."
        private const val OF_A_PAGE =
            "The request carries an Origin header, as the request of a web page does, and its method is neither GET nor HEAD. " +
                "The admin API takes a request that can change something only from a program."
        private const val MALFORMED_PATH = "The path of the request holds a percent sign that begins no escape of a character."
        private const val MALFORMED_QUERY =
            "The query string of the request holds a percent sign that begins no escape of a character."
        private const val NO_RESOURCE = "The admin API has no resource at the path of the request."
        private const val NO_OPERATION = "The resource has no operation with the method of the request."
        private const val FAILED = "The server failed to handle the request. Its log says why."
        private const val NOT_STORED = "The server could not read or write its definitions location, and nothing was changed."

        /**
         * Returns the admin API of a server with [settings]: the operations on the definitions and on the
         * history of [engine], and on the server itself, which runs since [since] and whose mock listener
         * accepts connections on [mock].
         */
        fun of(
            engine: MockeyEngine,
            settings: ServerSettings,
            since: Instant,
            mock: InetSocketAddress,
        ): AdminApi {
            val routes =
                AdminServices(engine).routes +
                    AdminScenarios(engine).routes +
                    AdminPlans(engine).routes +
                    AdminRouting(engine).routes +
                    AdminUpstream(engine).routes +
                    AdminHistory(engine).routes +
                    AdminDefinitions(engine).routes +
                    AdminSystem(engine, since, settings.definitions, mock).routes
            return AdminApi(settings.adminToken, settings.maxRequestBody, routes)
        }
    }
}
