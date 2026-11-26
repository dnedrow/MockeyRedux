package io.github.dnedrow.mockey.ui

import io.github.dnedrow.mockey.api.AdminApiJson
import io.github.dnedrow.mockey.api.FieldErrorDto
import io.github.dnedrow.mockey.api.OutboundProxyDto
import io.github.dnedrow.mockey.api.PlanApplyReportDto
import io.github.dnedrow.mockey.api.PlanDto
import io.github.dnedrow.mockey.api.PlanItemDto
import io.github.dnedrow.mockey.api.PlanReferenceDto
import io.github.dnedrow.mockey.api.PlanSaveRequestDto
import io.github.dnedrow.mockey.api.ServiceDto
import io.github.dnedrow.mockey.api.TagFilterDto
import io.github.dnedrow.mockey.api.TwistConfigurationDto
import io.github.dnedrow.mockey.api.UnresolvedPlanScenarioDto
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.done
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.json
import io.github.dnedrow.mockey.ui.FakeAdminApi.Companion.problem
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/*
 * Lets the stand-in server hold plans, twist configurations, the tag filter, and the outbound proxy. The
 * tests need this to load a page, submit one of its forms, and load the page again.
 *
 * The stand-in follows the server's rules only where the pages depend on them. Everything else is stored
 * exactly as a request sends it.
 */

private const val CREATED = 201
private const val NOT_FOUND = 404
private const val INVALID = 422
private const val APPLIED_AT = "2026-03-04T05:06:07Z"
private const val NOT_VALID = "The request is not valid."

/** Returns whether [first] and [second] are the same name for the server: it ignores letter case and surrounding whitespace. */
private fun sameName(
    first: String,
    second: String,
): Boolean = first.trim().equals(second.trim(), ignoreCase = true)

/** Returns this object with the attributes in [body] laid over it. [body] is the JSON body of a request. */
private fun JsonObject.with(body: String): JsonObject = JsonObject(this + AdminApiJson.instance.parseToJsonElement(body).jsonObject)

/**
 * Makes the stand-in hold [plans], and returns a live view of them, keyed by their identifiers. [services] is
 * the live view of the services the stand-in holds (see [hold]): saving a plan reads their current settings,
 * and applying a plan changes them. [default] is the identifier of the default plan to start with.
 *
 * Like a real server, the stand-in updates an existing plan when a save uses its name, gives a plan without
 * a name a generated one, and skips the items of a plan whose service doesn't exist. [paths] maps an
 * identifier to the path segment that a client writes for it, which is only needed for identifiers that a
 * client has to escape.
 */
internal fun FakeAdminApi.holdPlans(
    services: MutableMap<String, ServiceDto>,
    plans: List<PlanDto> = listOf(Samples.plan),
    default: String? = null,
    paths: Map<String, String> = emptyMap(),
): MutableMap<String, PlanDto> {
    val held = Collections.synchronizedMap(LinkedHashMap<String, PlanDto>())
    val defaultId = AtomicReference(default)
    val created = AtomicInteger()
    val gone = { id: String -> problem(NOT_FOUND, "No plan has the identifier $id.") }
    val json = AdminApiJson.instance

    fun itemOf(service: ServiceDto): PlanItemDto {
        val scenario = service.scenarios.firstOrNull { it.id == service.defaultScenarioId }
        return PlanItemDto(service.name, scenario?.name, service.responseMode, service.delayMillis)
    }

    /** Returns the plan with the settings of the request, or the problem for a service that doesn't exist. */
    fun saved(
        into: PlanDto,
        request: PlanSaveRequestDto,
    ): Pair<PlanDto?, FakeAdminApi.Answer?> {
        val unknown = request.serviceIds.indices.filter { request.serviceIds[it] !in services }
        if (unknown.isNotEmpty()) {
            val errors = unknown.map { FieldErrorDto("serviceIds[$it]", "No service has this identifier.") }
            return null to problem(INVALID, NOT_VALID, errors)
        }
        val items = request.serviceIds.distinct().map { itemOf(services.getValue(it)) }
        val name = request.name?.trim()?.takeIf { it.isNotEmpty() } ?: into.name.ifEmpty { "Plan (auto-generated-name)" }
        val description = request.description ?: into.description
        return into.copy(name = name, description = description, tags = request.tags ?: into.tags, items = items) to null
    }

    fun applied(plan: PlanDto): PlanApplyReportDto {
        val skipped = mutableListOf<PlanItemDto>()
        val unresolved = mutableListOf<UnresolvedPlanScenarioDto>()
        for (item in plan.items) {
            val service = services.values.firstOrNull { sameName(it.name, item.serviceName) }
            if (service == null) {
                skipped += item
                continue
            }
            val named = item.scenarioName
            val scenario = service.scenarios.firstOrNull { named != null && sameName(it.name, named) }
            if (named != null && scenario == null) unresolved += UnresolvedPlanScenarioDto(service.name, named)
            val default = scenario?.id ?: service.defaultScenarioId
            val effective = if (service.scenarios.isEmpty()) service.effectiveResponseMode else item.responseMode
            val now = service.copy(responseMode = item.responseMode, effectiveResponseMode = effective, delayMillis = item.delayMillis)
            services[service.id] = now.copy(defaultScenarioId = default)
        }
        val after = plan.copy(lastUsedTime = APPLIED_AT)
        held[plan.id] = after
        return PlanApplyReportDto(after, skipped, unresolved)
    }

    fun take(plan: PlanDto) {
        val id = plan.id
        val path = "plans/${paths[id] ?: id}"
        held[id] = plan
        on("GET", path) { held[id]?.let { json(it) } ?: gone(id) }
        on("PATCH", path) { request ->
            val before = held[id]
            if (before == null) {
                gone(id)
            } else {
                val now = json.encodeToJsonElement(PlanDto.serializer(), before).jsonObject.with(request.body)
                json(json.decodeFromJsonElement(PlanDto.serializer(), now).also { held[id] = it })
            }
        }
        on("DELETE", path) {
            defaultId.compareAndSet(id, null)
            if (held.remove(id) == null) gone(id) else done()
        }
        on("POST", "$path/save") { request ->
            val before = held[id]
            val (after, refusal) = saved(before ?: plan, json.decodeFromString(PlanSaveRequestDto.serializer(), request.body))
            when {
                before == null -> gone(id)
                after == null -> refusal!!
                else -> json(after.also { held[id] = it })
            }
        }
        on("POST", "$path/apply") { held[id]?.let { json(applied(it)) } ?: gone(id) }
    }
    plans.forEach(::take)
    on("GET", "plans") { json(held.values.toList()) }
    on("POST", "plans") { request ->
        val asked = json.decodeFromString(PlanSaveRequestDto.serializer(), request.body)
        val existing = held.values.firstOrNull { plan -> asked.name?.let { sameName(plan.name, it) } == true }
        val (after, refusal) = saved(existing ?: PlanDto("plan-new-${created.incrementAndGet()}", ""), asked)
        when {
            after == null -> refusal!!
            existing == null -> json(after.also(::take), CREATED)
            else -> json(after.also { held[it.id] = it })
        }
    }
    on("GET", "default-plan") {
        val id = defaultId.get()?.takeIf { it in held }
        if (id == null) problem(NOT_FOUND, "No plan is designated as the default plan.") else json(PlanReferenceDto(id))
    }
    on("PUT", "default-plan") { request ->
        val named = json.decodeFromString(PlanReferenceDto.serializer(), request.body)
        if (named.planId in held) {
            json(named.also { defaultId.set(it.planId) })
        } else {
            problem(INVALID, NOT_VALID, listOf(FieldErrorDto("planId", "No plan has this identifier.")))
        }
    }
    on("DELETE", "default-plan") {
        defaultId.set(null)
        done()
    }
    return held
}

/**
 * Makes the stand-in hold [twists], and returns a live view of them, keyed by their identifiers. [enabled] is
 * the identifier of the twist configuration that's enabled to start with. The stand-in reports the enabled
 * configuration in the server status, as a real server does.
 *
 * Like a real server, the stand-in gives a configuration without a name a generated one, and it drops a pair
 * without a text to replace. [paths] maps an identifier to the path segment that a client writes for it.
 */
internal fun FakeAdminApi.holdTwists(
    twists: List<TwistConfigurationDto> = listOf(Samples.twist),
    enabled: String? = null,
    paths: Map<String, String> = emptyMap(),
): MutableMap<String, TwistConfigurationDto> {
    val held = Collections.synchronizedMap(LinkedHashMap<String, TwistConfigurationDto>())
    val enabledId = AtomicReference(enabled)
    val created = AtomicInteger()
    val gone = { id: String -> problem(NOT_FOUND, "No twist configuration has the identifier $id.") }
    val json = AdminApiJson.instance

    fun report() {
        system = system.copy(enabledTwist = enabledId.get()?.let(held::get))
    }

    fun changed(
        before: TwistConfigurationDto,
        body: String,
    ): TwistConfigurationDto {
        val now = json.encodeToJsonElement(TwistConfigurationDto.serializer(), before).jsonObject.with(body)
        val stated = json.decodeFromJsonElement(TwistConfigurationDto.serializer(), now)
        val name = stated.name.trim().ifEmpty { "TwistInfo (auto-generated)" }
        return stated.copy(name = name, pairs = stated.pairs.filter { it.origination.isNotEmpty() })
    }

    fun take(twist: TwistConfigurationDto) {
        val id = twist.id
        val path = "twists/${paths[id] ?: id}"
        held[id] = twist
        on("GET", path) { held[id]?.let { json(it) } ?: gone(id) }
        on("PATCH", path) { request ->
            val before = held[id]
            if (before == null) gone(id) else json(changed(before, request.body).also { held[id] = it }).also { report() }
        }
        on("DELETE", path) {
            enabledId.compareAndSet(id, null)
            val removed = held.remove(id)
            report()
            if (removed == null) gone(id) else done()
        }
        on("POST", "$path/enable") {
            val current = held[id]
            if (current == null) gone(id) else json(current).also { enabledId.set(id) }.also { report() }
        }
        on("POST", "$path/disable") {
            val current = held[id]
            if (current == null) gone(id) else json(current).also { enabledId.compareAndSet(id, null) }.also { report() }
        }
    }
    twists.forEach(::take)
    report()
    on("GET", "twists") { json(held.values.toList()) }
    on("POST", "twists") { request ->
        val twist = changed(TwistConfigurationDto("twist-new-${created.incrementAndGet()}", ""), request.body)
        json(twist.also(::take), CREATED)
    }
    return held
}

/**
 * Makes the stand-in hold the tag filter and list the tags in use. [services] and [plans] are the live views
 * of what the stand-in holds (see [hold] and [holdPlans]). The tags in use are the tags of the services, of
 * their scenarios, and of the plans, and removing a tag removes it from all of them. The stand-in reports
 * the tag filter in the server status, as a real server does.
 *
 * [paths] maps a tag to the path segment that a client writes for it, which is only needed for tags that a
 * client has to escape.
 */
internal fun FakeAdminApi.holdTags(
    services: MutableMap<String, ServiceDto>,
    plans: MutableMap<String, PlanDto>,
    paths: Map<String, String> = emptyMap(),
) {
    fun inUse(): List<String> {
        val ofServices = services.values.flatMap { service -> service.tags + service.scenarios.flatMap { it.tags } }
        return (ofServices + plans.values.flatMap { it.tags }).distinct()
    }

    fun remove(tag: String): FakeAdminApi.Answer {
        if (tag !in inUse()) return problem(NOT_FOUND, "No service, scenario, or plan carries the tag '$tag'.")
        for ((id, service) in services.toMap()) {
            val scenarios = service.scenarios.map { it.copy(tags = it.tags - tag) }
            services[id] = service.copy(tags = service.tags - tag, scenarios = scenarios)
        }
        for ((id, plan) in plans.toMap()) plans[id] = plan.copy(tags = plan.tags - tag)
        return done()
    }

    // A route is an exact path, so every tag in use gets a route of its own, each time the tags are listed.
    fun takeTags() = inUse().forEach { tag -> on("DELETE", "tags/${paths[tag] ?: tag}") { remove(tag) } }
    takeTags()
    on("GET", "tags") { json(inUse()).also { takeTags() } }
    on("GET", "tag-filter") { json(TagFilterDto(system.tagFilter)) }
    on("PUT", "tag-filter") { request ->
        val stated = AdminApiJson.instance.decodeFromString(TagFilterDto.serializer(), request.body)
        val filter = stated.tags.map { it.lowercase() }.distinct()
        system = system.copy(tagFilter = filter)
        json(TagFilterDto(filter))
    }
    on("DELETE", "tag-filter") {
        system = system.copy(tagFilter = emptyList())
        done()
    }
}

/**
 * Makes the stand-in hold the settings of the outbound proxy, starting with [proxy], and returns a live view
 * of them. The stand-in also accepts the request that clears the upstream session.
 *
 * Like a real server, the stand-in never states the password. It only says whether one is set. It also
 * refuses to enable a proxy that has no URL.
 */
internal fun FakeAdminApi.holdProxy(proxy: OutboundProxyDto = Samples.proxy): AtomicReference<OutboundProxyDto> {
    val held = AtomicReference(proxy)
    on("GET", "upstream-proxy") { json(held.get()) }
    on("PATCH", "upstream-proxy") { request ->
        val stated = AdminApiJson.instance.parseToJsonElement(request.body).jsonObject
        val before = held.get()
        val url = stated["url"]?.jsonPrimitive?.contentOrNull?.trim() ?: before.url
        val enabled = stated["enabled"]?.jsonPrimitive?.booleanOrNull ?: before.enabled
        val statedUsername = stated["username"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull
        val username = if ("username" in stated) statedUsername else before.username
        val passwordSet = if ("password" in stated) stated["password"] != JsonNull else before.passwordSet
        if (enabled && url.isEmpty()) {
            val needsUrl = FieldErrorDto("url", "An outbound proxy needs a URL before it can be enabled.")
            problem(INVALID, NOT_VALID, listOf(needsUrl))
        } else {
            json(OutboundProxyDto(url, enabled, username, passwordSet).also(held::set))
        }
    }
    on("DELETE", "system/upstream-session") { done() }
    return held
}
