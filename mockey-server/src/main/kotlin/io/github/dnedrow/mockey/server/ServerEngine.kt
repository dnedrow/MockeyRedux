package io.github.dnedrow.mockey.server

import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.engine.MockeyEngine
import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.storage.FileDefinitionStorage
import io.github.dnedrow.mockey.core.storage.StorageException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * The engine of the server, and how it comes by the definitions that the server starts with.
 *
 * 1. The engine loads the definitions of the definitions location and applies their default plan.
 * 2. When the settings name a source of initial definitions, these take the place of the loaded ones,
 *    and their default plan is applied. In read-only mode that happens in memory, and the definitions
 *    location stays as it is. In writable mode the initial definitions are stored in the definitions
 *    location, and so the server takes them only while that location holds no definitions: Mockey 2.x
 *    deleted the stored definitions for the initial ones.
 * 3. The tag filter of the settings is set.
 *
 * The server starts its listeners when all of this is done.
 */
internal object ServerEngine {
    private val log = LoggerFactory.getLogger(ServerEngine::class.java)

    /**
     * Creates the engine that [settings] ask for, with the definitions that the server starts with.
     *
     * @throws StartupFailure when the definitions of the definitions location cannot be loaded, when the
     *   initial definitions cannot be read, and when the initial definitions would take the place of stored
     *   definitions in the definitions location. Nothing in the definitions location has changed then.
     */
    fun start(settings: ServerSettings): MockeyEngine =
        runBlocking {
            val source = settings.initialDefinitions?.let(::DefinitionsSource)
            // With initial definitions the engine is in read-only mode until it has them, so that nothing is stored before.
            val engine = created(settings, readOnly = settings.readOnly || source != null)
            try {
                log.info("The definitions location {} holds {}.", locationOf(settings), contentOf(engine.definitions))
                if (source != null) engine.replaceDefinitions(source, settings)
                engine.tagFilter = settings.tagFilter
                defaultPlanOf(engine.definitions)?.let { log.info("The default plan '{}' is applied.", it) }
            } catch (failure: Throwable) {
                engine.close()
                throw failure
            }
            engine
        }

    private suspend fun created(
        settings: ServerSettings,
        readOnly: Boolean,
    ): MockeyEngine =
        try {
            MockeyEngine.create {
                storage = FileDefinitionStorage(settings.definitions)
                this.readOnly = readOnly
                forwardUnknownTargets = settings.forwardUnknownTargets
                historyLimit = settings.historyLimit
                bodyCaptureLimit = settings.bodyCaptureLimit
                connectTimeout = settings.upstreamConnectTimeout
                responseTimeout = settings.upstreamResponseTimeout
            }
        } catch (failure: StorageException) {
            throw StartupFailure("The definitions cannot be loaded. ${failure.message}", failure)
        }

    /**
     * Puts the definitions of [source] in the place of the definitions of this engine, which is in read-only
     * mode, and switches the engine to writable mode when [settings] ask for that mode.
     */
    private suspend fun MockeyEngine.replaceDefinitions(
        source: DefinitionsSource,
        settings: ServerSettings,
    ) {
        if (!settings.readOnly && definitions != Definitions.EMPTY) throw StartupFailure(storedDefinitionsStay(settings))
        val remarks =
            try {
                val read = source.read()
                reset()
                imported(read, source)
            } catch (failure: UnreadableSource) {
                throw StartupFailure("${failure.message} $SOURCE_SETTING", failure)
            }
        if (settings.readOnly) {
            log.info(
                "The server starts with the initial definitions of {}, which it keeps in memory: {}.",
                source.shown,
                contentOf(definitions),
            )
        } else {
            try {
                setReadOnly(false)
            } catch (failure: StorageException) {
                throw StartupFailure("The initial definitions cannot be stored in the definitions location. ${failure.message}", failure)
            }
            log.info("The server starts with the initial definitions of {}, which it has stored: {}.", source.shown, contentOf(definitions))
        }
        remarks.forEach { log.warn("Of the initial definitions: {}", it) }
    }

    /**
     * Imports [read], which was read from [source], into this engine, and returns what there is to say about
     * what was not taken as the source has it.
     */
    private suspend fun MockeyEngine.imported(
        read: SourceDefinitions,
        source: DefinitionsSource,
    ): List<String> =
        when (read) {
            is SourceDefinitions.Document -> {
                when (val result = importDocument(read.text)) {
                    is ChangeResult.Changed -> result.value.conflicts.map { it.message }
                    is ChangeResult.Rejected -> throw source.unreadable(result.failure.errors.joinToString(" ") { it.message })
                    is ChangeResult.NotFound -> throw source.unreadable(result.message)
                }
            }

            is SourceDefinitions.Legacy -> {
                val report = read.result.importInto(this)
                report.issues.map { it.message } +
                    report.imported.conflicts.map { it.message } +
                    report.unreadableRuleSets.map { "The service '${it.serviceName}': ${it.message}" } +
                    listOfNotNull(read.advice)
            }
        }

    private fun storedDefinitionsStay(settings: ServerSettings): String =
        "The definitions location ${locationOf(settings)} holds definitions, and the server is in writable mode, " +
            "in which the initial definitions would be stored in their place. " +
            "The server takes initial definitions in writable mode only while the definitions location holds no definitions. " +
            "To start with the initial definitions and leave the stored definitions as they are, start the server in read-only mode, " +
            "which is the default: leave out --writable, or set MOCKEY_READ_ONLY=true. " +
            "To go on with the stored definitions, leave out --initial-definitions and MOCKEY_INITIAL_DEFINITIONS."

    private fun locationOf(settings: ServerSettings): Path = settings.definitions.toAbsolutePath().normalize()

    /** Returns the name of the default plan of [definitions], or `null` when they have none. */
    private fun defaultPlanOf(definitions: Definitions): String? =
        definitions.settings.defaultPlanId
            ?.let { id -> definitions.plans.firstOrNull { it.id == id } }
            ?.name

    private const val SOURCE_SETTING = "The source is the setting --initial-definitions or MOCKEY_INITIAL_DEFINITIONS."
}
