package xyz.block.trailblaze.toolcalls.commands

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import maestro.ScrollDirection
import maestro.SwipeDirection

object LenientScrollDirectionSerializer : KSerializer<ScrollDirection> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("maestro.ScrollDirection", PrimitiveKind.STRING)

  override fun deserialize(decoder: Decoder): ScrollDirection =
    ScrollDirection.valueOf(decoder.decodeString().trim().uppercase())

  override fun serialize(encoder: Encoder, value: ScrollDirection) = encoder.encodeString(value.name)
}

object LenientSwipeDirectionSerializer : KSerializer<SwipeDirection> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("maestro.SwipeDirection", PrimitiveKind.STRING)

  override fun deserialize(decoder: Decoder): SwipeDirection =
    SwipeDirection.valueOf(decoder.decodeString().trim().uppercase())

  override fun serialize(encoder: Encoder, value: SwipeDirection) = encoder.encodeString(value.name)
}
