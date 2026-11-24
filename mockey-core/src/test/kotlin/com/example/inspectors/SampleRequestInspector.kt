package com.example.inspectors

import io.github.dnedrow.mockey.core.inspection.Inspector
import io.github.dnedrow.mockey.core.request.MockRequest

/**
 * The sample inspector of Mockey 2.x, ported. It reports every request that carries the parameter `foobarKey`.
 */
class SampleRequestInspector : Inspector {
    /** The class name of the inspector in Mockey 2.x, so that the services that named it there still name it. */
    override val name: String = "com.mockey.plugin.SampleRequestInspector"

    /** The inspector inspects the requests of every service, as it did in Mockey 2.x. */
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
