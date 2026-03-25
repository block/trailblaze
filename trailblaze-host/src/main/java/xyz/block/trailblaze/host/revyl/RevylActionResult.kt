package xyz.block.trailblaze.host.revyl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.util.Console

/**
 * Structured result from a Revyl CLI device action (tap, type, swipe, etc.).
 *
 * Maps 1:1 to the `ActionResult` struct returned by the Go CLI's `--json`
 * output. Contains the resolved coordinates after AI grounding, which
 * downstream consumers like Trailblaze use for click overlay rendering.
 *
 * @property action The action type (e.g. "tap", "swipe", "type").
 * @property x Resolved horizontal pixel coordinate.
 * @property y Resolved vertical pixel coordinate.
 * @property target The natural language target that was grounded, if any.
 * @property success Whether the worker reported success.
 * @property latencyMs Round-trip latency in milliseconds.
 * @property durationMs Hold duration for long-press actions.
 * @property text Typed text for type actions.
 * @property direction Swipe direction for swipe actions.
 */
@Serializable
data class RevylActionResult(
  val action: String = "",
  val x: Int = 0,
  val y: Int = 0,
  val target: String? = null,
  val success: Boolean = true,
  @SerialName("latency_ms")
  val latencyMs: JsonElement? = null,
  @SerialName("duration_ms")
  val durationMs: Int = 0,
  val text: String? = null,
  val direction: String? = null,
) {
  companion object {
    /**
     * Parses a CLI JSON stdout line into a [RevylActionResult].
     *
     * Falls back to a default (success=true, coords=0,0) if parsing fails,
     * since the CLI already succeeded if we got stdout.
     *
     * @param jsonString Raw JSON from `revyl device <action> --json`.
     * @return Parsed result with coordinates and metadata.
     */
    fun fromJson(jsonString: String): RevylActionResult {
      return try {
        TrailblazeJsonInstance.decodeFromString<RevylActionResult>(jsonString.trim())
      } catch (e: Exception) {
        Console.log("RevylActionResult: JSON parse failed, using default: ${e.message}")
        RevylActionResult(success = true)
      }
    }
  }
}
