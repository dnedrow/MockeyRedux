package io.github.dnedrow.mockey.core.storage

import io.github.dnedrow.mockey.core.model.Definitions
import io.github.dnedrow.mockey.core.model.Scenario
import io.github.dnedrow.mockey.core.model.ScenarioId
import io.github.dnedrow.mockey.core.model.Service
import io.github.dnedrow.mockey.core.model.ServiceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.random.Random

/**
 * A storage that holds definitions as files below one directory, the definitions location:
 *
 * ```text
 * <root>/
 *   mockey.json            the format version, the plans, the twist configurations, and the settings
 *   services/
 *     <name>/
 *       service.json       one service, without its scenarios
 *       scenarios/
 *         <name>.json      one scenario, without its response body
 *         <name>.body      the response body of that scenario in UTF-8, exactly as it was entered
 * ```
 *
 * The files are made to be kept under version control:
 *
 * - The same definitions always give the same bytes, so definitions that were loaded and not changed leave
 *   every file as it is.
 * - A save writes the files of the definitions that changed, and no others.
 * - A file is written under another name and then moved into its place, and so is the directory of a new
 *   service with all its files. A reader therefore sees a file as it was before a save or as it is after
 *   it, and never a part of it, also when the program is ended in the middle of a save.
 *
 * A definition is known by the identifier inside its file. The names of files and directories are made from
 * the names of the definitions for whoever reads the location, and they mean nothing else: two definitions
 * with similar names get different file names, a definition that is renamed keeps its file, and a file or a
 * directory that someone has renamed is read as before.
 *
 * A location that does not exist holds no definitions. It is created with the first save. What else is in
 * the location, such as a file of a version control system, is left alone.
 */
public class FileDefinitionStorage internal constructor(
    /** The definitions location. */
    public val root: Path,
    private val beforeEach: (FileOperation) -> Unit,
) : DefinitionStorage {
    /** Creates a storage for the definitions location [root]. Nothing is read or written until the storage is used. */
    public constructor(root: Path) : this(root, {})

    private val lock = Mutex()

    /** What the location holds, as far as this storage has read or written it, or `null` when that is not known. */
    private var index: Index? = null
    private var leftoversCleared = false

    /**
     * Returns the definitions that the location holds, which are none for a location that does not exist or
     * holds no definition files. Nothing in the location is created, changed, or deleted.
     *
     * @throws StorageException when a definition file cannot be read. The message names the file and says
     *   what is wrong with it.
     */
    override suspend fun load(): Definitions =
        lock.withLock {
            withContext(Dispatchers.IO) {
                index = null
                val location = read()
                index = location.index
                location.definitions
            }
        }

    /**
     * Stores [definitions] in the location: writes the files of the definitions that differ from what the
     * location holds, and deletes the files of the definitions that are gone.
     *
     * Every new content is written under another name first. When that fails, the location is left as it
     * was.
     *
     * @throws StorageException when the definitions cannot be stored
     */
    override suspend fun save(definitions: Definitions) {
        lock.withLock { withContext(Dispatchers.IO) { write(definitions) } }
    }

    override fun toString(): String = "FileDefinitionStorage($root)"

    // Reading

    private fun read(): Location {
        if (Files.notExists(root)) return Location(Definitions.EMPTY, Index(null, emptyMap()))
        if (!Files.isDirectory(root)) throw StorageException("The definitions location $root is no directory.")
        val main = readMain()
        val services = readServices()
        val definitions =
            Definitions(
                services = services.values.sortedWith(SERVICES_IN_ORDER).map { it.withScenarios() },
                plans = main?.plans.orEmpty(),
                twists = main?.twists.orEmpty(),
                settings = main?.settings ?: Definitions.EMPTY.settings,
            )
        return Location(definitions, Index(main, services))
    }

    private fun readMain(): Definitions? {
        val file = root.resolve(MAIN_FILE)
        return if (Files.exists(file)) reading(file) { DefinitionFiles.main(textOf(file)) } else null
    }

    private fun readServices(): Map<ServiceId, StoredService> {
        val services = LinkedHashMap<ServiceId, StoredService>()
        for (directory in entriesOf(root.resolve(SERVICES_DIRECTORY))) {
            val file = directory.resolve(SERVICE_FILE)
            if (!Files.isRegularFile(file)) continue
            val content = reading(file) { DefinitionFiles.service(textOf(file)) }
            val id = content.value.id
            services[id]?.let {
                throw sameIdentifier(
                    "service",
                    id.value,
                    root.resolve(SERVICES_DIRECTORY).resolve(it.directory).resolve(SERVICE_FILE),
                    file,
                )
            }
            services[id] =
                StoredService(
                    directory.fileName.toString(),
                    content.value,
                    content.position,
                    readScenarios(directory.resolve(SCENARIOS_DIRECTORY)),
                )
        }
        return services
    }

    private fun readScenarios(directory: Path): Map<ScenarioId, StoredScenario> {
        val scenarios = LinkedHashMap<ScenarioId, StoredScenario>()
        for (file in entriesOf(directory)) {
            val name = file.fileName.toString()
            if (!name.endsWith(JSON_SUFFIX) || !Files.isRegularFile(file)) continue
            val base = name.removeSuffix(JSON_SUFFIX)
            val bodyFile = directory.resolve(base + BODY_SUFFIX)
            val body = if (Files.isRegularFile(bodyFile)) reading(bodyFile) { textOf(bodyFile) } else null
            val content = reading(file) { DefinitionFiles.scenario(textOf(file), body) }
            val id = content.value.id
            scenarios[id]?.let { throw sameIdentifier("scenario", id.value, directory.resolve(it.base + JSON_SUFFIX), file) }
            val bodyPlace =
                when {
                    body != null -> BodyPlace.FILE
                    content.holdsBody -> BodyPlace.INLINE
                    else -> BodyPlace.NONE
                }
            scenarios[id] = StoredScenario(base, content.value, content.position, bodyPlace)
        }
        return scenarios
    }

    private fun sameIdentifier(
        kind: String,
        id: String,
        first: Path,
        second: Path,
    ) = StorageException("The definition files $first and $second hold the same $kind identifier $id.")

    /** Returns the entries of [directory] in the order of their names, without those whose name begins with a dot. */
    private fun entriesOf(directory: Path): List<Path> {
        if (!Files.isDirectory(directory)) return emptyList()
        return try {
            Files.newDirectoryStream(directory).use { entries ->
                entries.filterNot { it.fileName.toString().startsWith(".") }.sortedBy { it.fileName.toString() }
            }
        } catch (e: DirectoryIteratorException) {
            throw StorageException("The directory $directory cannot be read: ${reasonOf(e.cause)}", e)
        } catch (e: IOException) {
            throw StorageException("The directory $directory cannot be read: ${reasonOf(e)}", e)
        }
    }

    /** Returns what [read] reads of [file], or tells what is wrong with the file. */
    private inline fun <T> reading(
        file: Path,
        read: () -> T,
    ): T =
        try {
            read()
        } catch (e: FileFault) {
            throw StorageException("The definition file $file cannot be read: ${e.problem}", e)
        } catch (e: CharacterCodingException) {
            throw StorageException("The definition file $file cannot be read: it is not text in UTF-8.", e)
        } catch (e: IOException) {
            throw StorageException("The definition file $file cannot be read: ${reasonOf(e)}", e)
        }

    /** Returns the text of [file], which must be UTF-8. Bytes that are no text are a failure and not a replacement character. */
    private fun textOf(file: Path): String {
        val decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString()
    }

    // Writing

    private fun write(definitions: Definitions) {
        val stored = index ?: read().index
        val plan =
            try {
                planned(definitions, stored)
            } catch (e: IOException) {
                throw notSaved(e)
            }
        if (plan.changesNothing) {
            index = plan.next
            return
        }
        clearLeftovers()
        val staged = staged(plan.writes)
        // From here on the location is between what it held and what it is to hold, until all of it is done.
        index = null
        try {
            staged.forEach(::moveIntoPlace)
            plan.deletions.forEach(::delete)
        } catch (e: IOException) {
            staged.forEach { discard(it.temporary) }
            throw notSaved(e)
        }
        plan.emptied.forEach(::removeIfEmpty)
        index = plan.next
    }

    private fun notSaved(failure: IOException): StorageException =
        failure as? StorageException ?: StorageException("The definitions could not be saved to $root: ${reasonOf(failure)}", failure)

    /** Returns what a save of [definitions] has to do in a location that holds what [stored] says. */
    private fun planned(
        definitions: Definitions,
        stored: Index,
    ): SavePlan {
        requireDistinct("service", definitions.services.map { it.id.value })
        val plan = SavePlan()
        val directory = root.resolve(SERVICES_DIRECTORY)
        val taken = lazy { namesIn(directory) }
        val positions = positionsOf(definitions.services.map { it.id.value }, stored.servicePositions)
        val services = LinkedHashMap<ServiceId, StoredService>()
        definitions.services.forEachIndexed { place, service ->
            val known = stored.services[service.id]
            val name = known?.directory ?: freeFileName(fileNameOf(service.name, fallback = "service"), taken.value)
            val firstOwnWrite = plan.writes.size
            services[service.id] = plan.store(directory.resolve(name), service, positions[place], known)
            if (known == null) plan.asOneDirectory(directory.resolve(name), firstOwnWrite)
        }
        for ((id, gone) in stored.services) if (id !in services) plan.remove(directory.resolve(gone.directory), gone)
        if (services.isEmpty()) plan.emptied.add(directory)

        val main = DefinitionFiles.mainOf(definitions)
        val mainFile = root.resolve(MAIN_FILE)
        val holdsNothing = services.isEmpty() && main == Definitions.EMPTY
        // The main file states the format version, so it is there whenever a definition is stored.
        val nextMain =
            when {
                stored.main == main -> main
                !holdsNothing -> main.also { plan.writes += FileWrite(mainFile, DefinitionFiles.mainText(main)) }
                else -> null.also { if (stored.main != null) plan.deletions.add(mainFile) }
            }
        plan.next = Index(nextMain, services)
        return plan
    }

    /** Plans the files of [service] in [directory], and returns what the location holds of the service after that. */
    private fun SavePlan.store(
        directory: Path,
        service: Service,
        position: Long?,
        known: StoredService?,
    ): StoredService {
        requireDistinct("scenario", service.scenarios.map { it.id.value })
        val scenariosDirectory = directory.resolve(SCENARIOS_DIRECTORY)
        val taken = lazy { if (known == null) HashSet() else namesIn(scenariosDirectory).mapTo(HashSet()) { it.substringBeforeLast('.') } }
        val positions = positionsOf(service.scenarios.map { it.id.value }, known?.scenarioPositions.orEmpty())
        val scenarios = LinkedHashMap<ScenarioId, StoredScenario>()
        service.scenarios.forEachIndexed { place, scenario ->
            val knownScenario = known?.scenarios?.get(scenario.id)
            val base = knownScenario?.base ?: freeFileName(fileNameOf(scenario.name, fallback = "scenario"), taken.value)
            scenarios[scenario.id] = store(scenariosDirectory, base, scenario, positions[place], knownScenario)
        }
        for ((id, gone) in known?.scenarios.orEmpty()) {
            if (id in scenarios) continue
            deletions.add(scenariosDirectory.resolve(gone.base + JSON_SUFFIX))
            if (gone.bodyPlace == BodyPlace.FILE) deletions.add(scenariosDirectory.resolve(gone.base + BODY_SUFFIX))
            if (scenarios.isEmpty()) emptied.add(scenariosDirectory)
        }
        // The scenario files come before the service file, so that a service never names a scenario that is not there yet.
        val withoutScenarios = service.copy(scenarios = emptyList())
        if (known == null || known.service != withoutScenarios || known.position != position) {
            writes += FileWrite(directory.resolve(SERVICE_FILE), DefinitionFiles.serviceText(withoutScenarios, position))
        }
        return StoredService(directory.fileName.toString(), withoutScenarios, position, scenarios)
    }

    /** Plans the files of [scenario] with the name [base] in [directory], and returns what the location holds of it after that. */
    private fun SavePlan.store(
        directory: Path,
        base: String,
        scenario: Scenario,
        position: Long?,
        known: StoredScenario?,
    ): StoredScenario {
        if (known != null && known.scenario == scenario && known.position == position) return known
        val bodyPlace = bodyPlaceOf(scenario.responseBody)
        val bodyFile = directory.resolve(base + BODY_SUFFIX)
        val bodyIsStored = known?.bodyPlace == BodyPlace.FILE && known.scenario.responseBody == scenario.responseBody
        // The body file comes before the scenario file, so that a new scenario is never there without its body.
        if (bodyPlace == BodyPlace.FILE && !bodyIsStored) writes += FileWrite(bodyFile, scenario.responseBody)
        val next = StoredScenario(base, scenario, position, bodyPlace)
        if (known == null || known.position != position || known.inScenarioFile != next.inScenarioFile) {
            val text = DefinitionFiles.scenarioText(scenario, position, withBody = bodyPlace == BodyPlace.INLINE)
            writes += FileWrite(directory.resolve(base + JSON_SUFFIX), text)
        }
        if (known?.bodyPlace == BodyPlace.FILE && bodyPlace != BodyPlace.FILE) deletions.add(bodyFile)
        return next
    }

    /**
     * Puts the writes of the plan from the one at [firstWrite] on together as the directory [directory] with
     * these files: the directory of a new service, which comes into being with everything in it.
     */
    private fun SavePlan.asOneDirectory(
        directory: Path,
        firstWrite: Int,
    ) {
        val files = writes.subList(firstWrite, writes.size).filterIsInstance<FileWrite>()
        writes.subList(firstWrite, writes.size).clear()
        writes += DirectoryWrite(directory, files)
    }

    /** Plans the removal of the files of the service [gone] in [directory]. */
    private fun SavePlan.remove(
        directory: Path,
        gone: StoredService,
    ) {
        val scenariosDirectory = directory.resolve(SCENARIOS_DIRECTORY)
        // The service file goes first: without it the directory holds no service, whatever else is left in it.
        deletions.add(directory.resolve(SERVICE_FILE))
        gone.scenarios.values.forEach { scenario ->
            deletions.add(scenariosDirectory.resolve(scenario.base + JSON_SUFFIX))
            if (scenario.bodyPlace == BodyPlace.FILE) deletions.add(scenariosDirectory.resolve(scenario.base + BODY_SUFFIX))
        }
        emptied.add(scenariosDirectory)
        emptied.add(directory)
    }

    /**
     * Makes sure that no identifier of [ids] is there twice. The files tell definitions apart by their
     * identifiers, so of two definitions of one [kind] with the same identifier only one would be stored.
     */
    private fun requireDistinct(
        kind: String,
        ids: List<String>,
    ) {
        val twice =
            ids
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .firstOrNull() ?: return
        throw StorageException("The definitions cannot be saved to $root: they hold the $kind identifier $twice more than once.")
    }

    /** Returns the names of everything in [directory], in lower case. */
    private fun namesIn(directory: Path): MutableSet<String> {
        if (!Files.isDirectory(directory)) return HashSet()
        return Files.newDirectoryStream(directory).use { entries -> entries.mapTo(HashSet()) { it.fileName.toString().lowercase() } }
    }

    /**
     * Writes the content of each of [writes] under another name next to where it belongs, and returns what
     * was written. When something cannot be written, all of it is removed, and with it the directories that
     * were made for it, so that the location is as it was.
     */
    private fun staged(writes: List<Write>): List<Staged> {
        val staged = ArrayList<Staged>(writes.size)
        val created = ArrayList<Path>()
        try {
            for (write in writes) {
                val temporary = write.target.resolveSibling(".${write.target.fileName}.${randomHex()}$TEMPORARY_SUFFIX")
                when (write) {
                    is FileWrite -> {
                        beforeEach(FileOperation(FileOperation.Kind.STAGE, write.target))
                        created.addAll(createdDirectories(write.target.parent))
                        staged += Staged(temporary, write.target)
                        writeNew(temporary, write.bytes)
                    }

                    is DirectoryWrite -> {
                        created.addAll(createdDirectories(write.target.parent))
                        staged += Staged(temporary, write.target)
                        for (file in write.files) {
                            beforeEach(FileOperation(FileOperation.Kind.STAGE, file.target))
                            val path = temporary.resolve(write.target.relativize(file.target))
                            Files.createDirectories(path.parent)
                            writeNew(path, file.bytes)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            staged.forEach { discard(it.temporary) }
            created.asReversed().forEach(::removeIfEmpty)
            throw notSaved(e)
        }
        return staged
    }

    /** Writes [bytes] to the new file [file], and returns when the system has them on the disk. */
    private fun writeNew(
        file: Path,
        bytes: ByteArray,
    ) {
        FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    /** Creates [directory] with the directories above it that do not exist, and returns those that it created, the highest first. */
    private fun createdDirectories(directory: Path): List<Path> {
        val missing = generateSequence(directory) { it.parent }.takeWhile { Files.notExists(it) }.toList().asReversed()
        Files.createDirectories(directory)
        return missing
    }

    private fun moveIntoPlace(staged: Staged) {
        beforeEach(FileOperation(FileOperation.Kind.MOVE, staged.target))
        try {
            Files.move(staged.temporary, staged.target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staged.temporary, staged.target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun delete(file: Path) {
        beforeEach(FileOperation(FileOperation.Kind.DELETE, file))
        Files.deleteIfExists(file)
    }

    /**
     * Removes [temporary], a file or a directory with everything in it, if it can be removed. What cannot be
     * removed stays, and nothing is said about it.
     */
    private fun discard(temporary: Path) {
        try {
            if (Files.isDirectory(temporary)) {
                Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            } else {
                Files.deleteIfExists(temporary)
            }
        } catch (_: IOException) {
            // What is left is in nobody's way: its name makes it no definition.
        } catch (_: UncheckedIOException) {
            // As above.
        }
    }

    /** Removes [directory] when nothing is left in it. A directory that holds something stays, such as one with a file of someone else. */
    private fun removeIfEmpty(directory: Path) {
        try {
            Files.deleteIfExists(directory)
        } catch (_: IOException) {
            // A directory without a service file holds no service, so it does no harm when it stays.
        }
    }

    /**
     * Removes what a save has left behind that was ended before it was done: the files and the directories
     * under other names that it had not moved into their places yet. It is done once, before the first save
     * of this storage that writes.
     */
    private fun clearLeftovers() {
        if (leftoversCleared) return
        leftoversCleared = true
        val services = root.resolve(SERVICES_DIRECTORY)
        val serviceDirectories = entriesOf(services).filter { Files.isDirectory(it) }
        val directories = listOf(root, services) + serviceDirectories.flatMap { listOf(it, it.resolve(SCENARIOS_DIRECTORY)) }
        for (directory in directories.filter { Files.isDirectory(it) }) {
            try {
                Files.newDirectoryStream(directory).use { entries ->
                    entries.filter { LEFTOVER.matches(it.fileName.toString()) }.forEach(::discard)
                }
            } catch (_: IOException) {
                // What cannot be listed cannot be cleared, and the save says what it cannot write.
            } catch (_: DirectoryIteratorException) {
                // As above.
            }
        }
    }

    /** What a file holds of the body of a scenario. */
    private enum class BodyPlace {
        /** The scenario has no body, and no body file. */
        NONE,

        /** The body is in the body file of the scenario. */
        FILE,

        /** The body is in the scenario file. */
        INLINE,
    }

    /** A scenario as the location holds it: in the files with the name [base]. */
    private class StoredScenario(
        val base: String,
        val scenario: Scenario,
        val position: Long?,
        val bodyPlace: BodyPlace,
    ) {
        /** What the scenario file holds of the scenario: all of it but the body, unless the body is in that file. */
        val inScenarioFile: Scenario
            get() = if (bodyPlace == BodyPlace.INLINE) scenario else scenario.copy(responseBody = "")
    }

    /** A service as the location holds it: in the directory with the name [directory]. [service] is without its scenarios. */
    private class StoredService(
        val directory: String,
        val service: Service,
        val position: Long?,
        val scenarios: Map<ScenarioId, StoredScenario>,
    ) {
        /** The position that the file of each scenario states, by the identifier of the scenario. */
        val scenarioPositions: Map<String, Long?>
            get() = scenarios.entries.associate { (id, scenario) -> id.value to scenario.position }

        fun withScenarios(): Service = service.copy(scenarios = scenarios.values.sortedWith(SCENARIOS_IN_ORDER).map { it.scenario })
    }

    /** What the location holds. [main] is what the main file holds, or `null` when there is no main file. */
    private class Index(
        val main: Definitions?,
        val services: Map<ServiceId, StoredService>,
    ) {
        /** The position that the file of each service states, by the identifier of the service. */
        val servicePositions: Map<String, Long?>
            get() = services.entries.associate { (id, service) -> id.value to service.position }
    }

    private class Location(
        val definitions: Definitions,
        val index: Index,
    )

    /** Something that a save writes to [target]. */
    private sealed interface Write {
        val target: Path
    }

    /** The new content of the file [target]. */
    private class FileWrite(
        override val target: Path,
        val bytes: ByteArray,
    ) : Write {
        constructor(target: Path, text: String) : this(target, text.toByteArray(Charsets.UTF_8))
    }

    /** The new directory [target] with the [files] in it, which are named as they are when the directory is in its place. */
    private class DirectoryWrite(
        override val target: Path,
        val files: List<FileWrite>,
    ) : Write

    /** A new content that waits under the name [temporary] to be moved to [target]. */
    private class Staged(
        val temporary: Path,
        val target: Path,
    )

    /**
     * What a save does: it writes [writes] in their order, then it deletes [deletions] in their order, and
     * then it removes those of the directories [emptied] in which nothing is left. [next] is what the location
     * holds after that.
     */
    private class SavePlan {
        val writes = ArrayList<Write>()
        val deletions = ArrayList<Path>()
        val emptied = ArrayList<Path>()
        lateinit var next: Index

        val changesNothing: Boolean
            get() = writes.isEmpty() && deletions.isEmpty()
    }

    private companion object {
        const val MAIN_FILE = "mockey.json"
        const val SERVICES_DIRECTORY = "services"
        const val SERVICE_FILE = "service.json"
        const val SCENARIOS_DIRECTORY = "scenarios"
        const val JSON_SUFFIX = ".json"
        const val BODY_SUFFIX = ".body"
        const val TEMPORARY_SUFFIX = ".tmp"

        /** The name under which a save writes a new content before it moves it into its place. */
        val LEFTOVER = Regex("""\..+\.[0-9a-f]{16}\.tmp""")

        val SERVICES_IN_ORDER: Comparator<StoredService> = compareBy({ it.position ?: Long.MAX_VALUE }, { it.service.id.value })
        val SCENARIOS_IN_ORDER: Comparator<StoredScenario> = compareBy({ it.position ?: Long.MAX_VALUE }, { it.scenario.id.value })

        fun randomHex(): String = "%016x".format(Random.nextLong())

        /** Where the body [body] of a scenario is stored: in a file of its own, unless it is empty or no text that UTF-8 can hold. */
        fun bodyPlaceOf(body: String): BodyPlace =
            when {
                body.isEmpty() -> BodyPlace.NONE
                String(body.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == body -> BodyPlace.FILE
                else -> BodyPlace.INLINE
            }

        /** Returns what an explanation says of [failure], with the file that it is about where the failure names one. */
        fun reasonOf(failure: IOException?): String =
            when (failure) {
                null -> "the directory cannot be listed."
                is NoSuchFileException -> "${failure.file} does not exist."
                is AccessDeniedException -> "access to ${failure.file} is denied."
                is FileAlreadyExistsException -> "${failure.file} is in the way."
                is DirectoryNotEmptyException -> "the directory ${failure.file} is in the way."
                else -> failure.message ?: failure.javaClass.simpleName
            }
    }
}

/** A step of a save, which a test can watch and can make fail: [kind] says what is about to happen to the file [path]. */
internal data class FileOperation(
    val kind: Kind,
    val path: Path,
) {
    enum class Kind {
        /** The new content of the file is written under another name. */
        STAGE,

        /** The new content is moved into the place of the file. */
        MOVE,

        /** The file is deleted. */
        DELETE,
    }
}
