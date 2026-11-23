package io.github.dnedrow.mockey.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * The identifier of a [Service]. An identifier is an opaque text. It is generated once, it is stored with its
 * definition, and no other definition ever receives it: renaming a definition does not change it, and deleting
 * a definition does not free it.
 */
@Serializable
@JvmInline
public value class ServiceId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "A service identifier must not be blank." }
    }

    public companion object {
        /** Returns an identifier that no service has had before. */
        public fun generate(): ServiceId = ServiceId(newIdentifier())
    }
}

/** The identifier of a [Scenario]. It has the properties that [ServiceId] describes. */
@Serializable
@JvmInline
public value class ScenarioId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "A scenario identifier must not be blank." }
    }

    public companion object {
        /** Returns an identifier that no scenario has had before. */
        public fun generate(): ScenarioId = ScenarioId(newIdentifier())
    }
}

/** The identifier of a [Plan]. It has the properties that [ServiceId] describes. */
@Serializable
@JvmInline
public value class PlanId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "A plan identifier must not be blank." }
    }

    public companion object {
        /** Returns an identifier that no plan has had before. */
        public fun generate(): PlanId = PlanId(newIdentifier())
    }
}

/** The identifier of a [TwistConfiguration]. It has the properties that [ServiceId] describes. */
@Serializable
@JvmInline
public value class TwistId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "A twist configuration identifier must not be blank." }
    }

    public companion object {
        /** Returns an identifier that no twist configuration has had before. */
        public fun generate(): TwistId = TwistId(newIdentifier())
    }
}

private fun newIdentifier(): String = UUID.randomUUID().toString()
