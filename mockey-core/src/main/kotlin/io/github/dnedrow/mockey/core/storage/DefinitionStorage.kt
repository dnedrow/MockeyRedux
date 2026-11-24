package io.github.dnedrow.mockey.core.storage

import io.github.dnedrow.mockey.core.model.Definitions
import java.io.IOException

/**
 * Where an engine keeps its definitions between one run and the next. The library brings a storage that holds
 * them in memory, see [InMemoryDefinitionStorage], and one that holds them in files. A program can put its own
 * in that place.
 *
 * A storage holds definitions and nothing else of an engine: no history, no last-used times, no cookies of
 * real services, no tag filter, and not whether the engine is in read-only mode. The definitions that it is
 * given hold no credentials of the outbound proxy either.
 *
 * An engine uses its storage for one thing at a time: it never asks for a save while another save or a load
 * is under way. A storage therefore serves one engine. Two engines that share a storage overwrite each other.
 */
public interface DefinitionStorage {
    /**
     * Returns the definitions that the storage holds. A storage that holds nothing yet returns
     * [Definitions.EMPTY].
     *
     * @throws StorageException when what the storage holds cannot be read. The message says what cannot be
     *   read and why, in words for whoever looks after the storage.
     */
    public suspend fun load(): Definitions

    /**
     * Stores [definitions] in the place of everything that the storage holds. The definitions are stored
     * when this function returns: a [load] after that returns them.
     *
     * An engine in writable mode saves every change before the change takes effect. When the save fails, the
     * change does not take effect, and the operation that made it fails with the message of the failure.
     *
     * @throws StorageException when the definitions cannot be stored
     */
    public suspend fun save(definitions: Definitions)
}

/**
 * Tells that definitions could not be loaded from a storage, or could not be saved to it. The message says
 * what happened, in words for whoever looks after the storage.
 */
public class StorageException
    @JvmOverloads
    constructor(
        message: String,
        cause: Throwable? = null,
    ) : IOException(message, cause)
