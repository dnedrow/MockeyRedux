package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.SystemStatusDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.a
import kotlinx.html.h2
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.ul

/**
 * The pages for tags: one page that shows the server's tag filter along with every tag in use, and the
 * confirmation step before a tag is removed from all definitions. The area also handles the forms that set
 * the tag filter and turn it off.
 *
 * See `docs/decisions/ui-pages.md` for the reasoning behind these pages.
 */
internal val TagsArea =
    Area { routing, ui ->
        with(routing) {
            get(TagPages.PAGE) {
                ui.show(call) { view, system ->
                    TagPages.tags(view, system.tagFilter, ui.client.listTags(), TagPages.filterState(system.tagFilter))
                }
            }
            post(TagPages.FILTER) {
                val again: suspend (View, SystemStatusDto, FormState) -> Page = { view, system, state ->
                    TagPages.tags(view, system.tagFilter, ui.client.listTags(), state)
                }
                ui.submit(call, again) { input ->
                    val tags = tagsIn(input.text(TagPages.TAGS))
                    if (tags.isEmpty()) {
                        ui.client.clearTagFilter()
                        Outcome.Done(TagPages.PAGE, TagPages.FILTER_OFF_NOTICE)
                    } else {
                        val filter = ui.client.setTagFilter(tags)
                        Outcome.Done(TagPages.PAGE, "The tag filter is now ${briefly(filter.tags.joinToString(" "))}.")
                    }
                }
            }
            post(TagPages.FILTER_OFF) {
                ui.submit(call) {
                    ui.client.clearTagFilter()
                    Outcome.Done(TagPages.PAGE, TagPages.FILTER_OFF_NOTICE)
                }
            }
            getAndPost(TagPages.REMOVE) {
                val tag = call.partOfAddress(TagPages.TAG).ifEmpty { throw NoSuchPage() }
                val ask: suspend (View, SystemStatusDto) -> Page = { view, system ->
                    TagPages.removal(view, tag, ui.client.listServices(), ui.client.listPlans(), system.tagFilter)
                }
                ui.confirm(call, ask) {
                    ui.client.removeTag(tag)
                    Outcome.Done(TagPages.PAGE, "The tag ${briefly(tag)} was removed from all definitions.")
                }
            }
        }
    }

/** The pages for tags. */
internal object TagPages {
    /** The path of the page with the tag filter and the tags in use. */
    const val PAGE = "/tags"

    /** The path of the action that sets the tag filter. */
    const val FILTER = "/tags/filter"

    /** The path of the action that turns the tag filter off. */
    const val FILTER_OFF = "/tags/filter-off"

    /** The path of the confirmation step that comes before a tag is removed from all definitions. */
    const val REMOVE = "/tag/remove"

    /** The address parameter that holds the tag to remove. */
    const val TAG = "tag"

    /** The field for the tags of the filter. */
    const val TAGS = "tags"

    /** The notice to show after the tag filter was turned off. */
    const val FILTER_OFF_NOTICE = "The tag filter is now off. Every service answers requests."

    /** Returns the initial state of the filter form: the tags of [filter], separated by spaces. */
    fun filterState(filter: List<String>): FormState = FormState.of(TAGS to filter.joinToString(" "))

    /**
     * Returns the page with the tag filter and the tags in use. [filter] holds the tags of the server's tag
     * filter, which is off while the list is empty. [inUse] holds every tag that a service, a scenario, or a
     * plan carries. The filter form is displayed with [state].
     */
    fun tags(
        view: View,
        filter: List<String>,
        inUse: List<String>,
        state: FormState,
    ): Page =
        Page("Tags") {
            h2 { +"Tag filter" }
            p { +FILTER_INTRO }
            p {
                if (filter.isEmpty()) {
                    +FILTER_IS_OFF
                } else {
                    +"The tag filter is on: "
                    tagsOf(filter)
                }
            }
            postForm(view, FILTER, classes = "filter", inPlace = true) {
                rejectionOf(state)
                textField(state, TAGS, "Tags of the filter", hint = FILTER_HINT)
                otherProblemsOf(state)
                submit("Set the tag filter")
            }
            if (filter.isNotEmpty()) postForm(view, FILTER_OFF) { submit("Turn the tag filter off") }
            h2 { +"Tags in use" }
            if (inUse.isEmpty()) {
                p { +"No service, scenario, or plan carries a tag." }
                return@Page
            }
            p { +IN_USE_INTRO }
            ul(classes = "plain tags") {
                for (tag in inUse.sorted()) {
                    li {
                        tagLinksOf(listOf(tag))
                        a(href = Address.of(REMOVE, TAG to tag)) {
                            attributes["aria-label"] = "Remove the tag $tag from all definitions"
                            +"Remove"
                        }
                    }
                }
            }
        }

    /**
     * Returns the page that asks whether to remove [tag] from all definitions. It says how many of [services],
     * their scenarios, and [plans] carry the tag, and it warns if the server's tag [filter] uses the tag. If
     * nothing carries the tag, the page says so and offers nothing to confirm.
     */
    fun removal(
        view: View,
        tag: String,
        services: List<ServiceDto>,
        plans: List<PlanDto>,
        filter: List<String>,
    ): Page {
        // The server finds a tag without regard to letter case, so the counts do the same.
        val carries = { tags: List<String> -> tags.any { it.equals(tag, ignoreCase = true) } }
        val onServices = services.count { carries(it.tags) }
        val onScenarios = services.sumOf { service -> service.scenarios.count { carries(it.tags) } }
        val onPlans = plans.count { carries(it.tags) }
        if (onServices + onScenarios + onPlans == 0) {
            return Page("The tag $tag is not in use", HttpStatusCode.NotFound) {
                p { +"No service, scenario, or plan carries this tag." }
                p(classes = "actions") { a(href = PAGE) { +"Back to the tags" } }
            }
        }
        val counts = listOf(counted(onServices, "service"), counted(onScenarios, "scenario"), counted(onPlans, "plan"))
        val carried = "The tag is on ${counts[0]}, ${counts[1]}, and ${counts[2]}. Each of them loses the tag."
        val consequences = listOfNotNull(carried, IN_FILTER.takeIf { carries(filter) })
        val title = "Remove the tag $tag from all definitions?"
        return Confirmation.page(view, title, consequences, "Remove the tag", Address.of(REMOVE, TAG to tag), PAGE)
    }

    /** Returns [count] with [noun] in the singular or the plural, for example "1 service" or "3 services". */
    private fun counted(
        count: Int,
        noun: String,
    ): String = if (count == 1) "1 $noun" else "$count ${noun}s"

    private const val FILTER_INTRO =
        "The tag filter decides which services answer requests. While it is on, a service answers a request only if " +
            "the service or one of its scenarios carries one of the filter's tags."
    private const val FILTER_IS_OFF = "The tag filter is off. Every service answers requests."
    private const val FILTER_HINT = "Separate the tags with spaces. An empty field turns the filter off."
    private const val IN_USE_INTRO =
        "At least one service, scenario, or plan carries each of these tags. A tag leads to the services that carry it."
    private const val IN_FILTER =
        "The tag filter of the server uses this tag. Removing the tag does not change the filter, " +
            "so no service will match this tag afterward."
}
