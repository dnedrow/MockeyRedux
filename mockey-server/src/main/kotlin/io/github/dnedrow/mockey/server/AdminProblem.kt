package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.ProblemDto
import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode

/**
 * Why the admin API does not do what a request asks for. The answer to the request is a problem document
 * with [status]: a JSON object that states a type, a title, the status, and [detail], which says what is
 * wrong in a sentence. A problem with the status 422 names every invalid field of the request with its
 * message in [errors].
 *
 * [headers] are headers that the answer carries besides, by their names, such as the methods that a
 * resource takes.
 *
 * An operation throws the problem, and the admin API answers with it. So neither [detail] nor [errors] hold
 * what a client is not to read, and they repeat nothing of a request that can hold a secret.
 */
internal class AdminProblem(
    val status: HttpStatusCode,
    val detail: String,
    val errors: List<FieldErrorDto> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
) : Exception(detail) {
    /**
     * Returns the problem document. The type of every problem is `about:blank`, which says that a problem
     * means what its status means, and so the title is the name of the status.
     */
    fun document(): ProblemDto = ProblemDto(NO_TYPE, status.description, status.value, detail, errors)

    internal companion object {
        /** The content type of a problem document, which is JSON in UTF-8. */
        val CONTENT_TYPE: ContentType = ContentType.parse("application/problem+json")

        private const val NO_TYPE = "about:blank"

        /**
         * Returns the problem of a request with invalid fields, which has the status 422. [errors] name each
         * of the fields with what is wrong with it, and there is at least one.
         */
        fun invalid(errors: List<FieldErrorDto>): AdminProblem {
            require(errors.isNotEmpty()) { "A request that is not valid has at least one invalid field." }
            val detail = errors.joinToString(" ", "The request is not valid. ") { "${it.field}: ${it.message}" }
            return AdminProblem(HttpStatusCode.UnprocessableEntity, detail, errors)
        }
    }
}

/**
 * Returns what a change to the definitions yields. A change that was not made is a problem: one that the
 * engine rejected has the status 422 and names every invalid field with the message of the engine, and one
 * that concerns a definition which does not exist has the status 404.
 *
 * @throws AdminProblem when the change was not made.
 */
internal fun <T> ChangeResult<T>.orProblem(): T =
    when (this) {
        is ChangeResult.Changed -> value
        is ChangeResult.Rejected -> throw AdminProblem.invalid(failure.errors.map { FieldErrorDto(it.field, it.message) })
        is ChangeResult.NotFound -> throw AdminProblem(HttpStatusCode.NotFound, message)
    }
