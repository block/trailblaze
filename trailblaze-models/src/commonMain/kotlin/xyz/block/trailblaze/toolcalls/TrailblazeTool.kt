package xyz.block.trailblaze.toolcalls

import kotlin.jvm.JvmInline

/**
 * A marker interface for all Trailblaze commands.
 *
 * All Trailblaze commands should implement this interface and be @[kotlinx.serialization.Serializable].
 *
 * Implementations may override [toolMetadata] to expose per-instance metadata that overrides
 * the class-level [TrailblazeToolClass] annotation. The default is `null`, meaning the
 * annotation is the source of truth — almost every implementation takes this path.
 * `YamlDefinedTrailblazeTool` is the one current exception: it shares a class with all other
 * YAML-defined tools, so it overrides this hook to surface its per-config flags.
 */
interface TrailblazeTool {
  /**
   * Per-instance metadata override. Defaults to `null` so class-annotated tools are
   * unaffected. See [TrailblazeToolMetadata] for the semantics of each field.
   */
  val toolMetadata: TrailblazeToolMetadata? get() = null
}

@JvmInline
value class ToolName(val toolName: String)

/**
 * Marker interface for tap-shaped tools that carry **raw (x, y) coordinates only** — no
 * pre-resolved selector. The shared `ActionYamlCard` (in `trailblaze-ui/commonMain`) uses this
 * to decide when to re-run `TrailblazeNodeSelectorGenerator.findAllValidSelectors` on demand
 * against the recorded tree, so the user can upgrade a `tapOnPoint` recording into a stable
 * `tapOnElementBySelector` without round-tripping through the YAML editor.
 *
 * Lives in commonMain (alongside [TrailblazeTool]) so the wasmJs recording panel can perform
 * the same upgrade flow as the desktop tab without importing the JVM-only concrete tool class
 * — `TapOnPointTrailblazeTool` is in `trailblaze-common/jvmAndAndroid` (its Maestro and
 * accessibility-service hooks need JVM types), but the picker UI only needs the (x, y).
 *
 * Tools that already carry their own selector (e.g. `TapOnByElementSelector`) should NOT
 * implement this interface — for them the precomputed `RecordedInteraction.selectorCandidates`
 * is the picker's source, and re-resolving from coords would re-pick the same element through
 * a different cascade entry.
 */
interface RawCoordinateTapTool : TrailblazeTool {
  /** Center X of the tap in device pixels. */
  val x: Int
  /** Center Y of the tap in device pixels. */
  val y: Int
}
