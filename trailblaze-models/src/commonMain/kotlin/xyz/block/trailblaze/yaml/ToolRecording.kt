package xyz.block.trailblaze.yaml

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * The tool sequence that a step replays at execution time.
 *
 * @property tools The recorded tool calls. Must be non-empty — a recording with no tools is
 *   rejected at construction. Use `recording: null` (omit the block entirely) to signal that
 *   a step has no recording and replay should fall through to AI.
 */
@Serializable
data class ToolRecording(
  val tools: List<@Contextual TrailblazeToolYamlWrapper> = emptyList(),
) {
  init {
    // A recording with no tools is malformed: either the block was hand-edited and the tool list
    // accidentally emptied, or an authoring agent fabricated the block without real tool calls.
    // Either way, an empty recording at replay would silently skip the step (ghost-pass), so we
    // reject it here at parse/construction time. To express "no recording", omit the `recording:`
    // block on the step — the replay path will then fall through to AI as designed.
    require(tools.isNotEmpty()) {
      "ToolRecording must have non-empty tools. An empty `recording:` block is invalid — " +
        "omit `recording:` entirely to fall through to AI, or include at least one recorded tool."
    }
  }
}
