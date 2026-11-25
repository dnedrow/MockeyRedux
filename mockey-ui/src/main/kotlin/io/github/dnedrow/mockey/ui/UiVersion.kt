package io.github.dnedrow.mockey.ui

import java.util.Properties

/** The name of the UI program, which is also the name of its start script. */
internal const val PROGRAM_NAME = "mockey-ui"

/** The version of the UI program. The build writes it into a resource, which is read here. */
internal object UiVersion {
    private const val RESOURCE = "ui-version.properties"

    /** The version as a text, such as `3.0.0`. */
    val text: String by lazy {
        val resource = checkNotNull(UiVersion::class.java.getResourceAsStream(RESOURCE)) { "The program has no resource $RESOURCE." }
        val properties = resource.use { Properties().apply { load(it) } }
        checkNotNull(properties.getProperty("version")) { "The resource $RESOURCE names no version." }
    }
}
