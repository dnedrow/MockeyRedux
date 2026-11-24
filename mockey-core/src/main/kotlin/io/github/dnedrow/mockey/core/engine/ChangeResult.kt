package io.github.dnedrow.mockey.core.engine

import io.github.dnedrow.mockey.core.model.Definitions

/** The outcome of a change to the definitions: the definitions after the change, or why nothing changed. */
public sealed interface ChangeResult<out T> {
    /**
     * Returns what a valid change yields, and throws for every other result, with what that result says.
     * This suits a program that defines what it knows to be valid and cannot go on otherwise.
     *
     * @throws IllegalArgumentException when the change was rejected. The message names every invalid field
     *   with its error.
     * @throws NoSuchElementException when the change concerns a definition that does not exist
     */
    public fun getOrThrow(): T =
        when (this) {
            is Changed -> value
            is Rejected -> throw IllegalArgumentException(failure.toString())
            is NotFound -> throw NoSuchElementException(message)
        }

    /** The change is valid. [definitions] are the definitions after it, and [value] is what the change yields. */
    public data class Changed<out T>(
        public val definitions: Definitions,
        public val value: T,
    ) : ChangeResult<T>

    /** The change would break a rule for definitions, so the definitions stay as they are. */
    public data class Rejected(
        public val failure: ValidationFailure,
    ) : ChangeResult<Nothing>

    /** The change concerns a definition that does not exist, so the definitions stay as they are. */
    public data class NotFound(
        public val message: String,
    ) : ChangeResult<Nothing>
}

/**
 * Returns this result with what a valid change yields put through [transform]. A result that is no change
 * stays as it is.
 */
internal fun <T, R> ChangeResult<T>.map(transform: (T) -> R): ChangeResult<R> =
    when (this) {
        is ChangeResult.Changed -> ChangeResult.Changed(definitions, transform(value))
        is ChangeResult.Rejected -> this
        is ChangeResult.NotFound -> this
    }

/** Why a change was rejected: one error for each invalid field. */
public data class ValidationFailure(
    public val errors: List<FieldError>,
) {
    init {
        require(errors.isNotEmpty()) { "A validation failure must name at least one invalid field." }
    }

    /** Returns one sentence that says that the change was rejected, and then every invalid field with its message. */
    override fun toString(): String = errors.joinToString(" ", "The change was rejected. ") { "${it.field}: ${it.message}" }
}

/** An invalid [field] of a definition, and a [message] that says what is wrong with it. */
public data class FieldError(
    public val field: String,
    public val message: String,
)
