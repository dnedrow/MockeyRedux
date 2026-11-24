package io.github.dnedrow.mockey.core.inspection

import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.request.MockRequest
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/**
 * The inspectors of an engine: those that are installed on the class path, see [installed], or those that the
 * engine was given, see [of].
 *
 * An inspector that cannot be installed is left out, and [problems] says which one and why. The other
 * inspectors work without it.
 */
internal class Inspectors private constructor(
    private val inspectors: List<Installed>,
    val problems: List<String>,
) {
    /** The names of the inspectors, which are the names that a service can name. */
    val names: Set<String> = inspectors.mapTo(linkedSetOf()) { it.name }

    /** Returns the inspectors in the order of their names, each with its name and whether it is global. */
    fun list(): List<InspectorInfo> = inspectors.map { InspectorInfo(it.name, it.global) }

    /**
     * Lets the inspectors inspect [request], which goes to [service], and returns their messages, each of them
     * once. A global inspector inspects the request of every service, and any other inspector only the request
     * of a service that names it. The inspectors are asked in the order of their names.
     *
     * An inspector that fails does not fail the inspection: its message says that it failed, and the
     * inspectors after it are asked as usual.
     */
    fun messages(
        service: Service,
        request: MockRequest,
    ): List<String> =
        inspectors
            .filter { it.global || it.name == service.inspectorName }
            .mapNotNull { it.message(request) }
            .distinct()

    companion object {
        /** No inspectors. */
        val NONE: Inspectors = Inspectors(emptyList(), emptyList())

        /** Returns the given [inspectors]. Of several inspectors with the same name, the first one is installed. */
        fun of(inspectors: Iterable<Inspector>): Inspectors = install(inspectors, emptyList())

        /**
         * Returns the inspectors that are installed: those that the service loader of the Java runtime finds
         * through [classLoader], which is the class loader of the calling thread unless another one is given.
         * A class that the service loader cannot load or create is left out.
         */
        fun installed(classLoader: ClassLoader? = Thread.currentThread().contextClassLoader): Inspectors {
            val found = mutableListOf<Inspector>()
            val problems = mutableListOf<String>()
            val providers = ServiceLoader.load(Inspector::class.java, classLoader).iterator()
            // The service loader fails for one provider at a time and goes on with the next one after that. The
            // limit ends a search that fails without getting anywhere, as with a class loader that is broken.
            var searching = true
            while (searching && problems.size < MAX_LOAD_FAILURES) {
                try {
                    if (providers.hasNext()) found += providers.next() else searching = false
                } catch (e: ServiceConfigurationError) {
                    problems += "An inspector cannot be installed: ${e.message}"
                } catch (e: LinkageError) {
                    problems += "An inspector cannot be installed: $e"
                }
            }
            return install(found, problems)
        }

        private const val MAX_LOAD_FAILURES = 100

        private fun install(
            inspectors: Iterable<Inspector>,
            problemsSoFar: List<String>,
        ): Inspectors {
            val problems = problemsSoFar.toMutableList()
            val installed = LinkedHashMap<String, Installed>()
            for (inspector in inspectors) {
                val cannot = "The inspector of the class ${inspector.javaClass.name} cannot be installed"
                val declared = declared(inspector) { failure -> problems += "$cannot: $failure" }
                when {
                    declared == null -> Unit
                    declared.name.isEmpty() -> problems += "$cannot: it has no name."
                    declared.name in installed -> problems += "$cannot: another inspector has its name '${declared.name}'."
                    else -> installed[declared.name] = declared
                }
            }
            return Inspectors(installed.values.sortedBy { it.name }, problems)
        }

        /** Reads the name of [inspector] and whether it is global, or tells [failed] why they cannot be read. */
        private fun declared(
            inspector: Inspector,
            failed: (String) -> Unit,
        ): Installed? =
            try {
                Installed(inspector.name.trim(), inspector.isGlobal, inspector)
            } catch (e: Throwable) {
                failed(reported(e))
                null
            }
    }
}

/** An inspector with what it said about itself when it was installed. */
private class Installed(
    val name: String,
    val global: Boolean,
    private val inspector: Inspector,
) {
    /** Returns the message of the inspector about [request], or `null` when it has nothing to say. */
    fun message(request: MockRequest): String? =
        try {
            inspector.inspect(request)?.takeIf { it.isNotBlank() }
        } catch (e: Throwable) {
            "The inspector '$name' failed: ${reported(e)}"
        }
}

/**
 * Returns the text that reports the [failure] of an inspector. A failure that is none of the inspector is
 * thrown on instead: the runtime has run out of memory or is broken. A thread that was interrupted stays
 * interrupted.
 */
private fun reported(failure: Throwable): String {
    if (failure is VirtualMachineError && failure !is StackOverflowError) throw failure
    if (failure is InterruptedException) Thread.currentThread().interrupt()
    return failure.toString()
}
