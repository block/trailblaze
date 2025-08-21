package xyz.block.trailblaze.tracing

import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Duration.Companion.microseconds

object InstantMicrosSerializer : KSerializer<Instant> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("InstantMicros", PrimitiveKind.LONG)

  private fun toEpochMicrosCompat(instant: Instant): Long {
    // Avoid floating math; works across targets
    return instant.epochSeconds * 1_000_000 + (instant.nanosecondsOfSecond / 1_000)
  }

  override fun serialize(encoder: Encoder, value: Instant) {
    encoder.encodeLong(toEpochMicrosCompat(value))
  }

  override fun deserialize(decoder: Decoder): Instant {
    val us = decoder.decodeLong()
    val ms = us / 1_000
    val remUs = (us % 1_000).toInt()
    return Instant.fromEpochMilliseconds(ms).plus(remUs.microseconds)
  }
}
