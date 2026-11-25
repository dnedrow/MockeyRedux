package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.ImportReportDto
import io.github.dnedrow.mockey.core.definitions.Diagnostic
import io.github.dnedrow.mockey.core.definitions.SearchResult
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.legacy.xml.LegacyDocumentImporter
import io.github.dnedrow.mockey.legacy.xml.LegacyXmlIssueKind
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode

/**
 * The operations of the admin API on the definitions of [engine] as a whole: the export and the import of
 * a definitions document, the diagnostics, and the search.
 *
 * A definitions document is the JSON text that the engine exports, with everything that the definitions
 * location holds, and without the credentials of the outbound proxy. It is the document that the server
 * takes as its initial definitions. What such a document looks like is for the engine to say, and not for
 * the wire types of the API.
 *
 * The import takes such a document, and an export document of Mockey 2.x, which is XML. The engine imports
 * both by the same rules, see [MockeyEngine.importDefinitions]: it adds what is new, merges a service into
 * the service that has its name, and takes a setting where it has none. The answer is the report, which
 * names every part of the document as an addition or as a conflict.
 */
internal class AdminDefinitions(
    private val engine: MockeyEngine,
) {
    /** The routes of the operations. */
    val routes: List<AdminRoute> =
        listOf(
            AdminRoute(HttpMethod.Get, "definitions/export") { AdminResponse(HttpStatusCode.OK, engine.exportDefinitions()) },
            AdminRoute(HttpMethod.Post, "definitions/import", query = setOf(TAG)) { import() },
            AdminRoute(HttpMethod.Get, "diagnostics") { AdminResponse.json(engine.diagnostics().map(Diagnostic::toAdminDto)) },
            AdminRoute(HttpMethod.Get, "search", query = setOf(TERM)) { search() },
        )

    /**
     * Imports the document that is the body of the request, and answers with the report of the import. The
     * request says with its `Content-Type` header which document it is: JSON for a definitions document of
     * this system, and XML for an export document of Mockey 2.x. The tags of the query string are added to
     * every service, scenario, and plan that the import adds, and to every service that it merges into.
     *
     * @throws AdminProblem with the status 422 when the body is no document that can be imported. The
     *   problem names the document and says why, and nothing is imported. Besides, it is thrown as
     *   [AdminRequest.document] says.
     */
    private suspend fun AdminRequest.import(): AdminResponse {
        val tags = Tags.of(queryValues(TAG))
        val document = document()
        return AdminResponse.json(if (document.isXml) importedOfMockey2(document, tags) else importedOfThisSystem(document, tags))
    }

    /** Imports [document] as a definitions document of this system, and returns the report. */
    private suspend fun importedOfThisSystem(
        document: Document,
        tags: Tags,
    ): ImportReportDto = engine.importDocument(document.text(), tags).orProblem().toAdminDto()

    /**
     * Imports [document] as an export document of Mockey 2.x, and returns the report. A document of which
     * some part cannot be read is imported without that part, which the report names among its issues. A
     * document counts as one that cannot be imported only when nothing was read of it at all. A reference
     * that names nothing is dropped and keeps nothing from being read.
     *
     * The document says in which charset it is written. It is read without what it refers to: no document
     * type, no entity, and no other file.
     */
    private suspend fun importedOfMockey2(
        document: Document,
        tags: Tags,
    ): ImportReportDto {
        val read = LegacyDocumentImporter.import(document.bytes)
        val unread = read.issues.filter { it.kind != LegacyXmlIssueKind.DANGLING_REFERENCE }
        if (read.definitions == Definitions.EMPTY && unread.isNotEmpty()) {
            val why = unread.joinToString(" ") { it.message }
            throw AdminProblem.invalid(listOf(FieldErrorDto(DOCUMENT, "The document cannot be imported: $why")))
        }
        return read.importInto(engine, tags).toAdminDto()
    }

    /**
     * Answers with the services, the scenarios, and the plans that hold the term of the query string,
     * without regard to letter case. A term that is blank finds nothing.
     *
     * @throws AdminProblem with the status 400 when the query string has no term.
     */
    private fun AdminRequest.search(): AdminResponse {
        val term = queryValue(TERM) ?: throw AdminProblem(HttpStatusCode.BadRequest, NO_TERM)
        return AdminResponse.json(engine.search(term).map(SearchResult::toAdminDto))
    }

    private companion object {
        const val TAG = "tag"
        const val TERM = "term"

        /** The name under which a problem names the document of an import, as the engine names it. */
        const val DOCUMENT = "document"
        const val NO_TERM = "The query string of the request has to state what to search for, in the parameter term."
    }
}
