package io.github.dnedrow.mockey.core.inspection

import io.github.dnedrow.mockey.core.request.MockRequest

/** A global inspector that misses the header `X-Trace-Id`. */
class TraceHeaderInspector : Inspector {
    override val name: String = "trace-header"

    override val isGlobal: Boolean = true

    override fun inspect(request: MockRequest): String? = if (request.header("X-Trace-Id") == null) "missing trace header" else null
}

/** An inspector for the services that name it, which misses a session cookie. */
class SessionCheckInspector : Inspector {
    override val name: String = "session-check"

    override fun inspect(request: MockRequest): String? =
        if (request.headerValues("Cookie").none { "session=" in it }) "session-check: the request has no session cookie" else null
}

/** A global inspector that fails for every request. */
class FailingInspector : Inspector {
    override val name: String = "always-failing"

    override val isGlobal: Boolean = true

    override fun inspect(request: MockRequest): String? = error("no luck with ${request.method}")
}

/** An inspector that cannot be created. */
class UncreatableInspector : Inspector {
    init {
        error("not today")
    }

    override val name: String = "uncreatable"

    override fun inspect(request: MockRequest): String? = null
}

/** An inspector with the given [name] that says what [inspection] says. */
class StubInspector(
    override val name: String,
    override val isGlobal: Boolean = false,
    private val inspection: (MockRequest) -> String? = { null },
) : Inspector {
    override fun inspect(request: MockRequest): String? = inspection(request)
}
