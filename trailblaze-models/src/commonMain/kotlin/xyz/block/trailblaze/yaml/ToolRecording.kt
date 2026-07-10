package xyz.block.trailblaze.yaml

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * The tool sequence that a step replays at execution time.
 *
 * There are three distinct states a step can be in, and they mean different things at replay:
 * - **`recording == null`** — nothing was ever declared for this step/device. Replay falls
 *   through to AI (blaze).
 * - **`recording.tools` is empty** — the author (or a merge that carries forward a
 *   hand-authored declaration) explicitly said "this step needs zero tools on this device."
 *   Replay runs zero tools and succeeds deterministically — it does NOT fall through to AI.
 *   This is the only way to express "explicitly do nothing here" without invoking the LLM.
 * - **`recording.tools` is non-empty** — replay the recorded tools in order.
 *
 * Automated recorders (e.g. [TrailblazeRecordingGenerator]) must never manufacture the empty
 * state on their own — a live session that happens to capture zero tools for an objective means
 * "not recorded," not "author declared a no-op," so those call sites emit `recording = null`
 * rather than `ToolRecording(tools = emptyList())`. The empty state is reserved for deliberate,
 * hand-authored (or hand-authored-then-merged) declarations.
 *
 * @property tools The recorded tool calls, possibly empty (see above). Use `recording: null`
 *   (omit the block entirely) to signal that a step has no recording and replay should fall
 *   through to AI.
 *
 * [tools] has no default: `recording: { tools: [] }` is a valid, deliberate no-op, but
 * `recording: {}` (the `tools:` key missing entirely) is malformed input and must fail to decode
 * rather than silently defaulting to the same empty list — a default here would turn an authoring
 * mistake (or truncated YAML) into an indistinguishable, silently-accepted no-op.
 */
@Serializable
data class ToolRecording(
  val tools: List<@Contextual TrailblazeToolYamlWrapper>,
)
