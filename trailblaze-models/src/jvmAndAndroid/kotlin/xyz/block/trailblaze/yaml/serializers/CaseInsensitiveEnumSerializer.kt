package xyz.block.trailblaze.yaml.serializers

import kotlin.reflect.KClass
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Base class for enum serializers that accept any casing from the LLM (e.g. "back", "BACK", "Back").
 *
 * Usage — create a nested `object` inside the enum and annotate the enum with `@Serializable`:
 * ```kotlin
 * @Serializable(with = MyEnum.Serializer::class)
 * enum class MyEnum {
 *   FOO, BAR;
 *   object Serializer : CaseInsensitiveEnumSerializer<MyEnum>(MyEnum::class)
 * }
 * ```
 *
 * Serializes back to the canonical uppercase name; accepts any casing on deserialization.
 * Throws [SerializationException] with the list of valid values if the input doesn't match.
 */
@Suppress("UNCHECKED_CAST")
abstract class CaseInsensitiveEnumSerializer<T : Enum<T>>(
  kClass: KClass<T>,
) : KSerializer<T> {

  private val values: Array<T> = kClass.java.enumConstants as Array<T>

  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor(kClass.qualifiedName ?: kClass.simpleName!!, PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(value.name)

  override fun deserialize(decoder: Decoder): T {
    val raw = decoder.decodeString().uppercase()
    return values.firstOrNull { it.name == raw }
      ?: throw SerializationException(
        "Unknown ${descriptor.serialName} value: '$raw'. " +
          "Expected one of: ${values.joinToString { it.name }}",
      )
  }
}
