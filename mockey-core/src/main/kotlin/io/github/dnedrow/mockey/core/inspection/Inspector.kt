package io.github.dnedrow.mockey.core.inspection

import io.github.dnedrow.mockey.core.request.MockRequest

/**
 * Inspects the requests that services get, and says what it finds about a request in the history entry of that
 * request. An inspector has no say in how a request is answered.
 *
 * An inspector that [isGlobal] inspects the requests of every service. Any other inspector inspects the
 * requests of the services that name it by its [name].
 *
 * An inspector is installed through the service loader of the Java runtime. A jar on the class path lists the
 * classes of its inspectors in the file `META-INF/services/io.github.dnedrow.mockey.core.inspection.Inspector`,
 * one class name on each line. Such a class is public and has a public constructor without parameters.
 *
 * One instance inspects all requests, and several threads can ask it at the same time. An inspector therefore
 * keeps nothing about one request for the next one.
 */
public interface Inspector {
    /**
     * The name by which a service names this inspector, and under which the list of inspectors shows it. It is
     * not blank, and no other installed inspector has it. It is read once, when the inspector is installed.
     */
    public val name: String

    /**
     * Whether this inspector inspects the requests of every service. An inspector that does not say so
     * inspects only the requests of the services that name it. It is read once, when the inspector is installed.
     */
    public val isGlobal: Boolean
        get() = false

    /**
     * Inspects [request] and returns the message for its history entry, or `null` or a blank text when there
     * is nothing to say about the request. A failure of this function does no harm to the request: its history
     * entry then says that this inspector failed.
     */
    public fun inspect(request: MockRequest): String?
}

/** An installed inspector as the list of inspectors shows it: its [name], and whether it [isGlobal]. */
public data class InspectorInfo(
    public val name: String,
    public val isGlobal: Boolean,
)
