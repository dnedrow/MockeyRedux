package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.model.Definitions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Holds the definitions of one engine. The definitions are one value that never changes: a change puts a new
 * value in its place. A reader therefore never sees a change that is half done, and it needs no lock.
 *
 * Changes are applied one at a time, each to the definitions that the change before it left. A rule that a
 * change checks therefore still holds when the change takes effect.
 */
internal class EngineState(
    initial: Definitions = Definitions.EMPTY,
    observers: List<ChangeObserver> = emptyList(),
) {
    private val observers = observers.toList()
    private val writer = Mutex()

    /**
     * The definitions as they are now. A reader that needs several things from them reads this property once
     * and takes all of them from that value, because the next read can return other definitions.
     */
    @Volatile
    var current: Definitions = initial
        private set

    /**
     * Applies [change] to the current definitions and returns its result. The function gets the current
     * definitions and returns either the changed definitions or a rejection. It must depend on nothing else
     * and must not use this state, because it runs while other changes wait.
     *
     * When the function returns definitions that differ from the current ones, the observers are told, in
     * their order, and then the changed definitions become the current ones. All of this is done when this
     * function returns. Once the observers have been told of a change, the change is completed even when the
     * caller is canceled.
     *
     * Nothing changes when the function rejects the change, when it fails, or when an observer fails. A failure
     * is thrown to the caller, and the observers after a failed one are not told.
     */
    suspend fun <T> change(change: (Definitions) -> ChangeResult<T>): ChangeResult<T> =
        writer.withLock {
            val before = current
            val result = change(before)
            if (result is ChangeResult.Changed && result.definitions != before) {
                withContext(NonCancellable) {
                    observers.forEach { it.changed(before, result.definitions) }
                    current = result.definitions
                }
            }
            result
        }
}

/**
 * Is told of every change to the definitions of an [EngineState], such as the storage that saves them. It is
 * told before the change takes effect, so that a change whose observer fails does not take effect at all. An
 * observer that can fail therefore belongs last: the observers before it have been told of the change by then.
 * An observer must not change the state itself.
 */
internal fun interface ChangeObserver {
    suspend fun changed(
        before: Definitions,
        after: Definitions,
    )
}
