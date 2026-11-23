package io.github.dnedrow.mockey.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The tags of a service, a scenario, or a plan: a set of lower-case words, held in alphabetical order.
 * [Tags.of] is the only way to create one, so every instance is normalized. It is serialized as a list of
 * its words.
 */
@Serializable(with = TagsSerializer::class)
public class Tags private constructor(
    private val values: Set<String>,
) : Set<String> by values {
    /** Returns these tags together with the [other] ones. */
    public operator fun plus(other: Tags): Tags = of(values + other.values)

    /** Returns these tags without the [other] ones. */
    public operator fun minus(other: Tags): Tags = of(values - other.values)

    override fun equals(other: Any?): Boolean = values == other

    override fun hashCode(): Int = values.hashCode()

    /** Returns the tags separated by single spaces, which is a text that [Tags.of] reads back. */
    override fun toString(): String = values.joinToString(" ")

    public companion object {
        private val SEPARATORS = Regex("""[\s\p{Z}]+""")

        /** No tags. */
        public val EMPTY: Tags = Tags(emptySet())

        /** Returns the tags in [input]. Each text is split on whitespace, so one text can hold several tags. */
        public fun of(vararg input: String): Tags = of(input.asList())

        /** Returns the tags in [input]. Each text is split on whitespace, so one text can hold several tags. */
        public fun of(input: Iterable<String>): Tags {
            val words = input.flatMap { it.lowercase().split(SEPARATORS) }.filter { it.isNotEmpty() }
            return if (words.isEmpty()) EMPTY else Tags(words.toSortedSet())
        }
    }
}

internal object TagsSerializer : KSerializer<Tags> {
    private val words = ListSerializer(String.serializer())

    override val descriptor: SerialDescriptor = words.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Tags,
    ) {
        encoder.encodeSerializableValue(words, value.toList())
    }

    override fun deserialize(decoder: Decoder): Tags = Tags.of(decoder.decodeSerializableValue(words))
}
