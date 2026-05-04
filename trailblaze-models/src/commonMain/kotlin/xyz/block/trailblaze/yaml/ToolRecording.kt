package xyz.block.trailblaze.yaml

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * The tool sequence that a step replays at execution time.
 *
 * @property tools The recorded tool calls. Empty when [autoSatisfied] is `true`.
 * @property autoSatisfied `true` when the recording author observed that the prompt's objective
 *   was already satisfied by the prior step's actions and therefore captured zero tool calls.
 *
 *   Distinct from `recording: null` (which means the step was never recorded and replay should
 *   fall through to AI). An auto-satisfied recording is an explicit assertion: "I watched, the
 *   objective was already complete, no tool needed to fire." At replay, [autoSatisfied] steps
 *   are skipped deterministically — execution advances to the next step without invoking AI.
 *   If the recording-time assumption no longer holds (e.g., a downstream product change), the
 *   next step's recorded assertions will fail loudly with the precise mismatch, which is the
 *   correct behavior.
 */
@Serializable
data class ToolRecording(
  val tools: List<@Contextual TrailblazeToolYamlWrapper> = emptyList(),
  val autoSatisfied: Boolean = false,
) {
  init {
    // A recording with no tools is only valid if the author explicitly marked it auto-satisfied.
    // This rejects malformed YAML like `recording: {}` or `recording: { tools: [] }` that would
    // otherwise silently skip a step at replay. Hand-edited or partially-deleted recordings now
    // fail fast at parse/construction time rather than producing a no-op recorded step.
    require(tools.isNotEmpty() || autoSatisfied) {
      "ToolRecording must have either non-empty tools or autoSatisfied=true. " +
        "An empty recording without auto-satisfied is invalid — mark intent explicitly " +
        "with `autoSatisfied: true` or include at least one tool."
    }
  }
}
