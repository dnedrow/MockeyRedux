package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.ProblemDto
import io.github.dnedrow.mockey.api.TagFilterDto
import io.github.dnedrow.mockey.api.TwistConfigurationDto
import io.github.dnedrow.mockey.api.TwistPairDto
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Tags
import io.github.dnedrow.mockey.core.model.TwistConfiguration
import io.github.dnedrow.mockey.core.model.TwistId
import io.github.dnedrow.mockey.core.model.TwistPair
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Task 12.4: the operations of the admin API on the twist configurations, on the tag filter, and on the
 * tags of the definitions, over real HTTP: on a listener that runs the admin API with these operations, for
 * an engine of the test.
 *
 * A test sends its request to the listener, and looks at the answer and at what the engine holds afterwards.
 * What these settings do to a request at the mock endpoint is the matter of [ServerAdminApiTest].
 */
class AdminRoutingTest {
    private val engine: MockeyEngine = runBlocking { MockeyEngine.create() }
    private val api = AdminApi(token = null, maxRequestBody = 65_536, routes = AdminRouting(engine).routes)
    private val listener = Listener.start(ListenerKind.ADMIN, RawHttp.loopback(0), MockeyServer.adminListenerModule(ForwardMark(null), api))

    @AfterTest
    fun stopEverything() {
        listener.stop()
        engine.close()
    }

    private fun send(
        method: String,
        target: String,
        body: String = "",
    ): RawHttp.Answer {
        val headers = if (body.isEmpty()) emptyList() else listOf("Content-Type: application/json")
        return RawHttp.exchange(listener.address, RawHttp.request(method, "/api/v1/$target", headers, RawHttp.utf8(body)))
    }

    private inline fun <reified T> RawHttp.Answer.decoded(): T = AdminApiJson.instance.decodeFromString<T>(bodyInUtf8)

    private val twists: List<TwistConfiguration>
        get() = engine.definitions.twists

    /** The identifier of the twist configuration that is enabled, or `null` when none is. */
    private val enabled: TwistId?
        get() = engine.definitions.settings.enabledTwistId

    /** Defines a twist configuration in the engine, with [pairs] of an origination and its destination. */
    private fun twist(
        name: String,
        vararg pairs: Pair<String, String>,
    ): TwistConfiguration =
        runBlocking { engine.twists.create { it.copy(name = name, pairs = pairs.map { (from, to) -> TwistPair(from, to) }) }.getOrThrow() }

    private fun enable(twist: TwistConfiguration) {
        runBlocking { engine.twists.enable(twist.id).getOrThrow() }
    }

    /**
     * Defines what carries tags in the engine: the service `Customers` with the tags `nightly` and `team-a`,
     * its scenario `found` with `nightly` and `of-a-scenario`, the service `Orders` without a tag, and the
     * plan `Night` with `nightly` and `of-a-plan`.
     */
    private fun defineWhatCarriesTags() {
        runBlocking {
            val ofCustomers = Tags.of("nightly", "team-a")
            val customers = engine.services.create { it.copy(name = "Customers", mockUrl = "customer/{id}", tags = ofCustomers) }
            val ofFound = Tags.of("of-a-scenario", "nightly")
            engine.scenarios.create(customers.getOrThrow().id) { it.copy(name = "found", tags = ofFound) }.getOrThrow()
            engine.services.create { it.copy(name = "Orders", mockUrl = "orders") }.getOrThrow()
            engine.plans.create { it.copy(name = "Night", tags = Tags.of("nightly", "of-a-plan")) }.getOrThrow()
        }
    }

    /** The tags of everything that [defineWhatCarriesTags] defines: of the two services, of the scenario, and of the plan. */
    private fun tagsOfTheDefinitions(): List<List<String>> {
        val current = engine.definitions
        val ofServices = current.services.map { it.tags.toList() }
        val ofScenarios = current.services.flatMap { service -> service.scenarios.map { it.tags.toList() } }
        return ofServices + ofScenarios + current.plans.map { it.tags.toList() }
    }

    private fun assertProblem(
        answer: RawHttp.Answer,
        status: Int,
        title: String,
        detail: String,
        errors: List<FieldErrorDto> = emptyList(),
    ) {
        assertEquals("HTTP/1.1 $status $title", answer.statusLine, "$answer")
        assertEquals(listOf("application/problem+json"), answer.headerValues("Content-Type"))
        assertEquals(ProblemDto("about:blank", title, status, detail, errors), answer.decoded<ProblemDto>())
    }

    /** [answer] is the problem of a request that is not valid, and it names exactly [errors], which are fields with their messages. */
    private fun assertInvalid(
        answer: RawHttp.Answer,
        vararg errors: Pair<String, String>,
    ) {
        val fields = errors.map { (field, message) -> FieldErrorDto(field, message) }
        val detail = fields.joinToString(" ", "The request is not valid. ") { "${it.field}: ${it.message}" }
        assertProblem(answer, 422, "Unprocessable Entity", detail, fields)
    }

    // Twist configurations

    /**
     * The answer holds the twist configuration with its identifier and says where it is. The texts of a
     * pair stay exactly as the request has them. A new configuration is not enabled.
     */
    @Test
    fun aTwistConfigurationIsCreatedWithTheAttributesOfTheRequestAndReturnedWithItsIdentifier() {
        val attributes =
            """
            {"name": " To the other stage ",
             "pairs": [{"origination": "qa1", "destination": "qa2"}, {"origination": " Example.com/ü", "destination": ""}]}
            """.trimIndent()

        val answer = send("POST", "twists", attributes)

        assertEquals("HTTP/1.1 201 Created", answer.statusLine, "$answer")
        val created = answer.decoded<TwistConfigurationDto>()
        assertEquals(listOf("/api/v1/twists/${created.id}"), answer.headerValues("Location"))
        val pairs = listOf(TwistPairDto("qa1", "qa2"), TwistPairDto(" Example.com/ü", ""))
        assertEquals(TwistConfigurationDto(created.id, "To the other stage", pairs), created)
        assertEquals(listOf(created), twists.map(TwistConfiguration::toAdminDto))
        assertNull(enabled)
    }

    /**
     * A twist configuration is stored in the form that the engine gives it: without a name it gets a
     * generated one, and a pair without an origination is left out.
     */
    @Test
    fun aTwistConfigurationIsCreatedWithTheDefaultsForWhatTheRequestLeavesOut() {
        val withoutAnOrigination =
            """{"name": "  ", "pairs": [{"origination": "", "destination": "qa2"}, {"origination": "qa1", "destination": "qa2"}]}"""

        val ofNothing = send("POST", "twists", "{}")
        val ofPairs = send("POST", "twists", withoutAnOrigination)

        assertEquals(201 to 201, ofNothing.status to ofPairs.status)
        val created = listOf(ofNothing.decoded<TwistConfigurationDto>(), ofPairs.decoded<TwistConfigurationDto>())
        assertEquals(List(2) { "TwistInfo (auto-generated)" }, created.map { it.name })
        assertEquals(listOf(emptyList(), listOf(TwistPairDto("qa1", "qa2"))), created.map { it.pairs })
        assertEquals(created, twists.map(TwistConfiguration::toAdminDto))
    }

    @Test
    fun aTwistConfigurationWithAPairThatIsNotAsAPairIsIsNotCreated() {
        val attributes =
            """
            {"name": "To the other stage", "enabled": true,
             "pairs": [{"origination": "qa1"}, {"origination": 1, "destination": "qa2", "id": 7}, null, ["qa1", "qa2"]]}
            """.trimIndent()

        val answer = send("POST", "twists", attributes)

        val noMember = "The request can have no member of this name here."
        assertInvalid(
            answer,
            "enabled" to noMember,
            "pairs[0].destination" to "The member is missing.",
            "pairs[1].id" to noMember,
            "pairs[1].origination" to "The value has to be a text.",
            "pairs[2]" to "The member must have a value, and null is none.",
            "pairs[3]" to "The value has to be an object.",
        )
        assertEquals(emptyList(), twists)
    }

    @Test
    fun theTwistConfigurationsAreListedInTheOrderInWhichTheyWereCreatedAndOneIsReadByItsIdentifier() {
        val none = send("GET", "twists")
        val toQa2 = twist("to-qa2", "qa1" to "qa2", "example.com" to "example.org")
        val toSandbox = twist("to-sandbox")
        enable(toSandbox)

        val listed = send("GET", "twists")
        val read = send("GET", "twists/${toQa2.id.value}")

        assertEquals(200 to emptyList(), none.status to none.decoded<List<TwistConfigurationDto>>())
        assertEquals(200 to 200, listed.status to read.status)
        val first =
            TwistConfigurationDto(toQa2.id.value, "to-qa2", listOf(TwistPairDto("qa1", "qa2"), TwistPairDto("example.com", "example.org")))
        assertEquals(listOf(first, TwistConfigurationDto(toSandbox.id.value, "to-sandbox")), listed.decoded<List<TwistConfigurationDto>>())
        assertEquals(first, read.decoded<TwistConfigurationDto>())
    }

    /**
     * The requirement "Partial updates", for a twist configuration. A stated list of pairs takes the place
     * of all pairs. A configuration that is enabled stays so.
     */
    @Test
    fun anUpdateChangesOnlyTheAttributesThatTheRequestStates() {
        val twist = twist("to-qa2", "qa1" to "qa2", "example.com" to "example.org")
        enable(twist)
        val target = "twists/${twist.id.value}"

        val renamed = send("PATCH", target, """{"name": "To QA 2"}""")
        val afterTheName = twists.single()
        val withOnePair = send("PATCH", target, """{"pairs": [{"origination": "qa1", "destination": "qa3"}]}""")
        val unchanged = send("PATCH", target, "{}")

        assertEquals(listOf<Int?>(200, 200, 200), listOf(renamed.status, withOnePair.status, unchanged.status))
        assertEquals(twist.copy(name = "To QA 2"), afterTheName)
        assertEquals(afterTheName.toAdminDto(), renamed.decoded<TwistConfigurationDto>())
        assertEquals(twist.copy(name = "To QA 2", pairs = listOf(TwistPair("qa1", "qa3"))), twists.single())
        assertEquals(twists.single().toAdminDto(), unchanged.decoded<TwistConfigurationDto>())
        assertEquals(twist.id, enabled)
    }

    @Test
    fun anUpdateWithAPairThatIsNotAsAPairIsChangesNothing() {
        val twist = twist("to-qa2", "qa1" to "qa2")

        val answer = send("PATCH", "twists/${twist.id.value}", """{"name": "To QA 2", "pairs": [{"destination": "qa3"}]}""")

        assertInvalid(answer, "pairs[0].origination" to "The member is missing.")
        assertEquals(listOf(twist), twists)
    }

    /** The scenario "Enabling replaces the enabled configuration" of the routing, through the API. The answer is the configuration. */
    @Test
    fun aTwistConfigurationIsEnabledInThePlaceOfTheOneBefore() {
        val toQa2 = twist("to-qa2", "qa1" to "qa2")
        val toSandbox = twist("to-sandbox", "qa1" to "sandbox")

        val first = send("POST", "twists/${toQa2.id.value}/enable")
        val afterTheFirst = enabled
        val second = send("POST", "twists/${toSandbox.id.value}/enable")
        val again = send("POST", "twists/${toSandbox.id.value}/enable")

        assertEquals("HTTP/1.1 200 OK", first.statusLine, "$first")
        assertEquals(toQa2.toAdminDto(), first.decoded<TwistConfigurationDto>())
        assertEquals(toQa2.id, afterTheFirst)
        assertEquals(200 to toSandbox.toAdminDto(), second.status to second.decoded<TwistConfigurationDto>())
        assertEquals(200 to toSandbox.toAdminDto(), again.status to again.decoded<TwistConfigurationDto>())
        assertEquals(toSandbox.id, enabled)
        assertEquals(listOf(toQa2, toSandbox), twists)
    }

    /** Disabling a twist configuration that is not the enabled one changes nothing, and it is no failure. */
    @Test
    fun aTwistConfigurationIsDisabledAndDisablingAnotherChangesNothing() {
        val toQa2 = twist("to-qa2", "qa1" to "qa2")
        val toSandbox = twist("to-sandbox", "qa1" to "sandbox")
        enable(toQa2)

        val ofAnother = send("POST", "twists/${toSandbox.id.value}/disable")
        val afterAnother = enabled
        val disabled = send("POST", "twists/${toQa2.id.value}/disable")
        val again = send("POST", "twists/${toQa2.id.value}/disable")

        assertEquals(200 to toSandbox.toAdminDto(), ofAnother.status to ofAnother.decoded<TwistConfigurationDto>())
        assertEquals(toQa2.id, afterAnother)
        assertEquals("HTTP/1.1 200 OK", disabled.statusLine, "$disabled")
        assertEquals(toQa2.toAdminDto(), disabled.decoded<TwistConfigurationDto>())
        assertEquals(200, again.status)
        assertNull(enabled)
        assertEquals(listOf(toQa2, toSandbox), twists)
    }

    @Test
    fun aTwistConfigurationIsDeletedAndNoneIsEnabledWhenItWas() {
        val toQa2 = twist("to-qa2", "qa1" to "qa2")
        val toSandbox = twist("to-sandbox", "qa1" to "sandbox")
        enable(toQa2)

        val answer = send("DELETE", "twists/${toQa2.id.value}")
        val again = send("DELETE", "twists/${toQa2.id.value}")

        assertEquals("HTTP/1.1 204 No Content" to "", answer.statusLine to answer.body)
        assertEquals(emptyList(), answer.headerValues("Content-Type") + answer.headerValues("Content-Length"))
        assertEquals(listOf(toSandbox), twists)
        assertNull(enabled)
        assertProblem(again, 404, "Not Found", "No twist configuration has the identifier ${toQa2.id.value}.")
    }

    /** A twist configuration that is deleted while another one is enabled leaves that one enabled. */
    @Test
    fun deletingATwistConfigurationLeavesAnotherOneEnabled() {
        val toQa2 = twist("to-qa2", "qa1" to "qa2")
        val toSandbox = twist("to-sandbox", "qa1" to "sandbox")
        enable(toSandbox)

        val answer = send("DELETE", "twists/${toQa2.id.value}")

        assertEquals(204, answer.status)
        assertEquals(listOf(toSandbox) to toSandbox.id, twists to enabled)
    }

    /** The scenario "Unknown resource", for every operation on one twist configuration, which is found by its identifier. */
    @Test
    fun anOperationOnATwistConfigurationThatIsNotThereGetsTheStatus404() {
        enable(twist("to-qa2", "qa1" to "qa2"))
        val before = engine.definitions

        val answers =
            listOf(
                send("GET", "twists/$MISSING"),
                send("PATCH", "twists/$MISSING", """{"name": "To QA 2"}"""),
                send("PATCH", "twists/$MISSING", """{"pairs": "not looked at"}"""),
                send("DELETE", "twists/$MISSING"),
                send("POST", "twists/$MISSING/enable"),
                send("POST", "twists/$MISSING/disable"),
            )
        val ofTheName = send("POST", "twists/to-qa2/disable")

        answers.forEach { assertProblem(it, 404, "Not Found", "No twist configuration has the identifier $MISSING.") }
        assertProblem(ofTheName, 404, "Not Found", "No twist configuration has the identifier to-qa2.")
        assertEquals(before, engine.definitions)
    }

    // The tag filter

    /**
     * The tag filter is always there, and without tags it is no filter. Its tags are stored as tags are: in
     * lower case, each once, and in the order of the alphabet. Clearing a filter without tags changes nothing.
     */
    @Test
    fun theTagFilterIsSetReadAndCleared() {
        val before = send("GET", "tag-filter")
        val set = send("PUT", "tag-filter", """{"tags": ["Nightly team-a", "TEAM-A"]}""")
        val ofTheEngine = engine.tagFilter
        val read = send("GET", "tag-filter")
        val cleared = send("DELETE", "tag-filter")
        val after = send("GET", "tag-filter")
        val clearedAgain = send("DELETE", "tag-filter")

        assertEquals(200 to TagFilterDto(emptyList()), before.status to before.decoded<TagFilterDto>())
        assertEquals("HTTP/1.1 200 OK", set.statusLine, "$set")
        assertEquals(TagFilterDto(listOf("nightly", "team-a")), set.decoded<TagFilterDto>())
        assertEquals(Tags.of("nightly", "team-a"), ofTheEngine)
        assertEquals(200 to TagFilterDto(listOf("nightly", "team-a")), read.status to read.decoded<TagFilterDto>())
        assertEquals("HTTP/1.1 204 No Content" to "", cleared.statusLine to cleared.body)
        assertEquals(200 to TagFilterDto(emptyList()), after.status to after.decoded<TagFilterDto>())
        assertEquals(204, clearedAgain.status)
        assertEquals(Tags.EMPTY, engine.tagFilter)
    }

    /** A request that sets the filter states its tags. A filter takes the place of the one before, and it can be without tags. */
    @Test
    fun aTagFilterIsSetWithItsTags() {
        engine.tagFilter = Tags.of("nightly")

        val withoutTags = send("PUT", "tag-filter", "{}")
        val ofAText = send("PUT", "tag-filter", """{"tags": "team-a"}""")
        val ofANumber = send("PUT", "tag-filter", """{"tags": ["team-a", 7], "enabled": true}""")
        val afterThem = engine.tagFilter
        val ofAnother = send("PUT", "tag-filter", """{"tags": ["team-b"]}""")
        val ofAnotherInTheEngine = engine.tagFilter
        val ofNone = send("PUT", "tag-filter", """{"tags": [" "]}""")

        assertInvalid(withoutTags, "tags" to "The member is missing.")
        assertInvalid(ofAText, "tags" to "The value has to be a list.")
        assertInvalid(
            ofANumber,
            "enabled" to "The request can have no member of this name here.",
            "tags[1]" to "The value has to be a text.",
        )
        assertEquals(Tags.of("nightly"), afterThem)
        assertEquals(200 to TagFilterDto(listOf("team-b")), ofAnother.status to ofAnother.decoded<TagFilterDto>())
        assertEquals(Tags.of("team-b"), ofAnotherInTheEngine)
        assertEquals(200 to TagFilterDto(emptyList()), ofNone.status to ofNone.decoded<TagFilterDto>())
        assertEquals(Tags.EMPTY, engine.tagFilter)
    }

    // Tags

    /**
     * The tags in use are those of the services, of their scenarios, and of the plans, each once and in the
     * order of the alphabet. A tag of the tag filter that nothing carries is not in use.
     */
    @Test
    fun theTagsInUseAreThoseOfServicesScenariosAndPlans() {
        val none = send("GET", "tags")
        defineWhatCarriesTags()
        engine.tagFilter = Tags.of("of-the-filter")

        val answer = send("GET", "tags")

        assertEquals(200 to emptyList(), none.status to none.decoded<List<String>>())
        assertEquals("HTTP/1.1 200 OK", answer.statusLine, "$answer")
        assertEquals(listOf("application/json"), answer.headerValues("Content-Type"))
        assertEquals("""["nightly","of-a-plan","of-a-scenario","team-a"]""", answer.body)
    }

    /**
     * A tag is removed wherever it is, and every other tag stays. The path names the tag without regard to
     * letter case. The tag filter is no definition, so it keeps the tag.
     */
    @Test
    fun aTagIsRemovedFromEveryServiceScenarioAndPlan() {
        defineWhatCarriesTags()
        engine.tagFilter = Tags.of("nightly")
        val before = tagsOfTheDefinitions()

        val answer = send("DELETE", "tags/NIGHTLY")
        val again = send("DELETE", "tags/nightly")

        assertEquals(
            listOf(listOf("nightly", "team-a"), emptyList(), listOf("nightly", "of-a-scenario"), listOf("nightly", "of-a-plan")),
            before,
        )
        assertEquals("HTTP/1.1 204 No Content" to "", answer.statusLine to answer.body)
        assertEquals(listOf(listOf("team-a"), emptyList(), listOf("of-a-scenario"), listOf("of-a-plan")), tagsOfTheDefinitions())
        assertEquals(listOf("of-a-plan", "of-a-scenario", "team-a"), send("GET", "tags").decoded<List<String>>())
        assertEquals(Tags.of("nightly"), engine.tagFilter)
        assertProblem(again, 404, "Not Found", "No service, scenario, or plan carries the tag 'nightly'.")
    }

    /** A tag is one word, so a path with two of them names no tag, and it removes none. A part of a tag is no tag either. */
    @Test
    fun aTagThatNothingCarriesIsNoResource() {
        defineWhatCarriesTags()
        val before = engine.definitions
        val named =
            mapOf(
                "weekly" to "weekly",
                "nightly%20team-a" to "nightly team-a",
                "nightly+team-a" to "nightly+team-a",
                "night" to "night",
                "%20" to " ",
            )

        val answers = named.mapValues { (path, _) -> send("DELETE", "tags/$path") }
        val ofAllTags = send("DELETE", "tags")
        val read = send("GET", "tags/nightly")

        for ((path, tag) in named) {
            assertProblem(answers.getValue(path), 404, "Not Found", "No service, scenario, or plan carries the tag '$tag'.")
        }
        assertEquals(405 to listOf("GET, HEAD"), ofAllTags.status to ofAllTags.headerValues("Allow"))
        assertEquals(405 to listOf("DELETE"), read.status to read.headerValues("Allow"))
        assertEquals(before, engine.definitions)
    }

    private companion object {
        const val MISSING = "no-such-identifier"
    }
}
