package io.github.dnedrow.mockey.parity

/** Credentials for the outbound proxy of a group. The harness supplies them, because a fixture cannot. */
data class OutboundProxyCredentials(
    val proxyUrl: String,
    val username: String,
    val password: String,
)

/**
 * The twist configuration that is enabled for a group. Legacy Mockey enables it by [legacyId], the number that
 * it gives the configuration when it loads the fixture. The new server knows it by [name].
 */
data class EnabledTwist(
    val name: String,
    val legacyId: Int,
)

/**
 * The cases that are recorded against one fixture. [name] is the fixture's file name without `.xml`.
 *
 * A fixture cannot carry every setting. Whoever runs the cases of a group applies the rest after loading the
 * fixture: the [outboundProxyCredentials], the [tagFilter], and the [enabledTwist].
 */
class ParityCaseGroup(
    val name: String,
    val cases: Map<String, ParityCase>,
    val outboundProxyCredentials: OutboundProxyCredentials? = null,
    val tagFilter: String? = null,
    val enabledTwist: EnabledTwist? = null,
) {
    val fixturePath: String
        get() = "parity-fixtures/$name.xml"
}

/** Defines the group of cases that share the fixture [name]. Every case starts as [ParityCase.UNREVIEWED]. */
fun parityCaseGroup(
    name: String,
    outboundProxyCredentials: OutboundProxyCredentials? = null,
    tagFilter: String? = null,
    enabledTwist: EnabledTwist? = null,
    define: ParityCaseGroupBuilder.() -> Unit,
): ParityCaseGroup {
    val builder = ParityCaseGroupBuilder("parity-fixtures/$name.xml").apply(define)
    return ParityCaseGroup(name, builder.cases, outboundProxyCredentials, tagFilter, enabledTwist)
}

/** All cases of [groups] by name. Fails when two groups define a case of the same name. */
fun casesOf(groups: Collection<ParityCaseGroup>): Map<String, ParityCase> =
    groups.fold(emptyMap()) { cases, group ->
        val duplicates = group.cases.keys.intersect(cases.keys)
        require(duplicates.isEmpty()) { "Duplicate parity cases: $duplicates" }
        cases + group.cases
    }

/** The [groups] named [selection], or whose names start with [selection] followed by a hyphen. */
fun groupsMatching(
    groups: Collection<ParityCaseGroup>,
    selection: String,
): List<ParityCaseGroup> = groups.filter { it.name == selection || it.name.startsWith("$selection-") }

class ParityCaseGroupBuilder internal constructor(
    private val fixture: String,
) {
    internal val cases = linkedMapOf<String, ParityCase>()

    fun case(
        name: String,
        method: String,
        target: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
        timing: ParityTiming? = null,
        before: List<ParityRequest> = emptyList(),
        snapshot: Boolean = false,
    ) {
        val case =
            ParityCase(
                label = ParityCase.UNREVIEWED,
                fixtureXml = fixture,
                request = request(method, target, headers, body),
                timing = timing,
                before = before,
                snapshot = snapshot,
            )
        require(cases.put(name, case) == null) { "Duplicate parity case: $name" }
    }

    /** With [snapshot], the response holds times or session IDs, and only its status is compared later. */
    fun get(
        name: String,
        target: String,
        headers: Map<String, String> = emptyMap(),
        timing: ParityTiming? = null,
        before: List<ParityRequest> = emptyList(),
        snapshot: Boolean = false,
    ) = case(name, "GET", target, headers, timing = timing, before = before, snapshot = snapshot)

    fun post(
        name: String,
        target: String,
        body: String,
        contentType: String = "text/plain",
        before: List<ParityRequest> = emptyList(),
    ) = case(name, "POST", target, mapOf("Content-Type" to contentType), body.toByteArray(), before = before)

    /** A `before` list of one GET request to [target]. */
    fun step(target: String): List<ParityRequest> = listOf(request("GET", target))

    /** A request for the `before` list of a case. */
    fun request(
        method: String,
        target: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ) = ParityRequest(method, target, headers.mapValues { listOf(it.value) }, body)
}
