package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import maestro.orchestra.Command
import maestro.orchestra.TapOnPointV2Command
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

/**
 * Taps a point given as a percentage of the **screen** (not of an element), so the tap lands in
 * the same place on any device size. For a point relative to an element, use [TapOnTrailblazeTool]
 * with `relativePoint` instead.
 *
 * ```yaml
 * - tapOnPercent:
 *     x: 50
 *     y: 26
 * ```
 *
 * Decimals such as `y: 26.5` work too, though a whole number is usually precise enough and
 * easier to read.
 *
 * Intended for targets that have no usable element to anchor on, such as a secure field the
 * accessibility tree hides. It records as the raw point, so prefer a selector-based tap wherever
 * an element exists. Lowers to Maestro's percent-point tap, which every driver already services.
 */
@Serializable
@TrailblazeToolClass("tapOnPercent")
@LLMDescription(
  "Tap a point given as a percentage of the screen, e.g. x=50, y=26 is horizontally centered, " +
    "26% down from the top. Decimals are allowed; whole numbers are usually precise enough. " +
    "Prefer tapping an element by ref; use this only where no element exists.",
)
data class TapOnPercentTrailblazeTool(
  @param:LLMDescription("Horizontal position, 0 (left edge) to 100 (right edge).")
  @Serializable(with = PercentSerializer::class)
  val x: Double,
  @param:LLMDescription("Vertical position, 0 (top edge) to 100 (bottom edge).")
  @Serializable(with = PercentSerializer::class)
  val y: Double,
) : MapsToMaestroCommands() {

  init {
    require(x in 0.0..100.0 && y in 0.0..100.0) {
      "tapOnPercent: x and y must each be between 0 and 100, got x=${x.toPercentString()}, " +
        "y=${y.toPercentString()}."
    }
  }

  override fun toMaestroCommands(): List<Command> = listOf(
    TapOnPointV2Command(point = "${x.toPercentString()}%,${y.toPercentString()}%"),
  )
}

/** `50.0` → `"50"`, `50.5` → `"50.5"`, so whole-number trails keep their `50%` point. */
private fun Double.toPercentString(): String =
  if (this % 1.0 == 0.0) toLong().toString() else toString()

/**
 * Writes a whole-number percent as an integer, so a trail authored as `x: 50` re-records as
 * `x: 50` rather than `x: 50.0`. Reads any number.
 */
private object PercentSerializer : KSerializer<Double> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("xyz.block.trailblaze.TapOnPercent", PrimitiveKind.DOUBLE)

  override fun serialize(encoder: Encoder, value: Double) {
    if (value % 1.0 == 0.0) encoder.encodeLong(value.toLong()) else encoder.encodeDouble(value)
  }

  override fun deserialize(decoder: Decoder): Double = decoder.decodeDouble()
}
