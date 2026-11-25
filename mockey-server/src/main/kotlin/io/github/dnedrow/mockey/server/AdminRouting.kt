package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.TagFilterDto
import io.github.dnedrow.mockey.api.TwistAttributesDto
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistConfiguration
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode

/**
 * The operations of the admin API on what decides, besides the services, where a request of [engine] goes:
 * the twist configurations, the tag filter, and the tags that the definitions carry.
 *
 * A request that creates or updates a twist configuration states attributes of it, see
 * [TwistAttributesDto], as a request for a service does, see [AdminServices]. At most one configuration is
 * enabled, and the status of the server states which.
 *
 * The tag filter is a resource of its own, which is always there: a filter without tags lets a request go
 * to any service. It is a setting of the running server, and no part of the definitions.
 */
internal class AdminRouting(
    private val engine: MockeyEngine,
) {
    /** The routes of the operations. */
    val routes: List<AdminRoute> =
        listOf(
            AdminRoute(HttpMethod.Get, TWISTS) { AdminResponse.json(engine.definitions.twists.map(TwistConfiguration::toAdminDto)) },
            AdminRoute(HttpMethod.Post, TWISTS) { create() },
            AdminRoute(HttpMethod.Get, TWIST) { AdminResponse.json(twist().toAdminDto()) },
            AdminRoute(HttpMethod.Patch, TWIST) { update() },
            AdminRoute(HttpMethod.Delete, TWIST) {
                engine.twists.delete(twist().id).orProblem()
                AdminResponse.noContent()
            },
            AdminRoute(HttpMethod.Post, "$TWIST/enable") {
                val enabled = engine.twists.enable(twist().id).orProblem()
                AdminResponse.json(enabled.toAdminDto())
            },
            AdminRoute(HttpMethod.Post, "$TWIST/disable") {
                val disabled = engine.twists.disable(twist().id).orProblem()
                AdminResponse.json(disabled.toAdminDto())
            },
            AdminRoute(HttpMethod.Get, TAG_FILTER) { AdminResponse.json(TagFilterDto(engine.tagFilter.toList())) },
            AdminRoute(HttpMethod.Put, TAG_FILTER) {
                val filter = Tags.of(body(TagFilterDto.serializer()).tags)
                engine.tagFilter = filter
                AdminResponse.json(TagFilterDto(filter.toList()))
            },
            AdminRoute(HttpMethod.Delete, TAG_FILTER) {
                engine.tagFilter = Tags.EMPTY
                AdminResponse.noContent()
            },
            AdminRoute(HttpMethod.Get, TAGS) { AdminResponse.json(engine.tagsInUse().toList()) },
            AdminRoute(HttpMethod.Delete, "$TAGS/{tag}") { removeTag() },
        )

    /** Creates a twist configuration with the attributes that the request states, and answers with it and where it is. */
    private suspend fun AdminRequest.create(): AdminResponse {
        val stated = attributes(TwistAttributesDto.serializer())
        val twist = engine.twists.create { blank -> blank.with(stated.on(blank.attributes())) }.orProblem()
        return AdminResponse.created(twist.toAdminDto(), TWISTS, twist.id.value)
    }

    /** Gives the twist configuration of the request the attributes that the request states, and answers with it. */
    private suspend fun AdminRequest.update(): AdminResponse {
        val id = twist().id
        val stated = attributes(TwistAttributesDto.serializer())
        val twist = engine.twists.update(id) { current -> current.with(stated.on(current.attributes())) }.orProblem()
        return AdminResponse.json(twist.toAdminDto())
    }

    /**
     * Removes the tag that the path of the request names from every service, scenario, and plan. The tag is
     * found without regard to letter case, as every tag is.
     *
     * @throws AdminProblem with the status 404 when none of them carries the tag. A text with whitespace in
     *   it is no tag, because a tag is one word.
     */
    private suspend fun AdminRequest.removeTag(): AdminResponse {
        val named = parameter("tag")
        val tag =
            Tags.of(named).singleOrNull()?.takeIf { it in engine.tagsInUse() }
                ?: throw AdminProblem(HttpStatusCode.NotFound, "No service, scenario, or plan carries the tag '$named'.")
        engine.removeTag(tag)
        return AdminResponse.noContent()
    }

    /**
     * Returns the twist configuration that the path of the request names.
     *
     * @throws AdminProblem with the status 404 when there is no such twist configuration.
     */
    private fun AdminRequest.twist(): TwistConfiguration {
        val id = parameter("twistId")
        return engine.definitions.twists.firstOrNull { it.id.value == id }
            ?: throw AdminProblem(HttpStatusCode.NotFound, "No twist configuration has the identifier $id.")
    }

    private companion object {
        const val TWISTS = "twists"
        const val TWIST = "twists/{twistId}"
        const val TAG_FILTER = "tag-filter"
        const val TAGS = "tags"
    }
}
