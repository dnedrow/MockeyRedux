package com.example.inspectors

import io.github.dnedrow.mockey.core.inspection.Inspector
import io.github.dnedrow.mockey.core.request.MockRequest

/**
 * The sample inspector of Mockey 2.x as an inspector of this system. It reports every request that carries the
 * parameter `foobarKey`.
 */
class SampleRequestInspector : Inspector {
    /** The class name of the legacy inspector, so that services which were imported with that name still name it. */
    override val name: String = "com.mockey.plugin.SampleRequestInspector"

    /** The legacy inspector inspected the requests of every service. */
    override val isGlobal: Boolean = true

    override fun inspect(request: MockRequest): String? =
        if (request.parameters.any { it.name == PARAMETER }) {
            "Howdy! This isn't a real error. This is here to show you that you can use request inspectors to " +
                "validate incoming requests. You are seeing this message because the request parameter " +
                "'$PARAMETER' was in the request."
        } else {
            null
        }

    private companion object {
        const val PARAMETER = "foobarKey"
    }
}
