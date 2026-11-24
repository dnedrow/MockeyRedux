package io.github.dnedrow.mockey.core.storage

import io.github.dnedrow.mockey.core.model.Definitions

/**
 * A storage that holds definitions in memory and nowhere else, so they end with the program. It is the
 * storage of an engine that is given no other one.
 *
 * A storage that is created with [initial] definitions gives them to the engine that loads from it, which is
 * a way to start an engine with definitions that a program has put together.
 */
public class InMemoryDefinitionStorage
    @JvmOverloads
    constructor(
        initial: Definitions = Definitions.EMPTY,
    ) : DefinitionStorage {
        @Volatile
        private var stored: Definitions = initial

        override suspend fun load(): Definitions = stored

        override suspend fun save(definitions: Definitions) {
            stored = definitions
        }
    }
