package xyz.block.trailblaze.tracing

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

object DurationMicrosSerializer : KSerializer<Duration> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("DurationMicros", PrimitiveKind.LONG)

  override fun serialize(encoder: Encoder, value: Duration) {
    encoder.encodeLong(value.inWholeMicroseconds)
  }

  override fun deserialize(decoder: Decoder): Duration = decoder.decodeLong().microseconds
}
