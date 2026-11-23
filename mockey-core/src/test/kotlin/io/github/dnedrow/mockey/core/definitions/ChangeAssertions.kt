package io.github.dnedrow.mockey.core.definitions

import io.github.dnedrow.mockey.core.engine.Change
import io.github.dnedrow.mockey.core.engine.ChangeResult
import io.github.dnedrow.mockey.core.engine.FieldError
import io.github.dnedrow.mockey.core.model.Definitions
import kotlin.test.fail

/** Applies [change] to these definitions and returns its result, which must be a change. */
internal fun <T> Definitions.changed(change: Change<T>): ChangeResult.Changed<T> =
    when (val result = change(this)) {
        is ChangeResult.Changed -> result
        else -> fail("The change was not made: $result")
    }

/** Applies [change] to these definitions and returns the errors of its result, which must be a rejection. */
internal fun <T> Definitions.rejected(change: Change<T>): List<FieldError> =
    when (val result = change(this)) {
        is ChangeResult.Rejected -> result.failure.errors
        else -> fail("The change was not rejected: $result")
    }

/** Applies [change] to these definitions and returns the message of its result, which must be "not found". */
internal fun <T> Definitions.notFound(change: Change<T>): String =
    when (val result = change(this)) {
        is ChangeResult.NotFound -> result.message
        else -> fail("The change did not report a missing definition: $result")
    }
