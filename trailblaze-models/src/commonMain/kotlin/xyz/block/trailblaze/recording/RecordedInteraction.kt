package xyz.block.trailblaze.recording

import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * A single user interaction captured during interactive recording.
 * Contains the resolved semantic tool and optional screenshot/hierarchy context.
 */
data class RecordedInteraction(
  /** The resolved semantic tool (e.g., TapOnByElementSelector). */
  val tool: TrailblazeTool,
  /** Tool name for YAML serialization (e.g., "tapOnElementBySelector"). */
  val toolName: String,
  /** Screenshot bytes captured at the moment of interaction. */
  val screenshotBytes: ByteArray?,
  /** View hierarchy text at the moment of interaction. */
  val viewHierarchyText: String?,
  /** Epoch millis when the interaction occurred. */
  val timestamp: Long,
  /**
   * Alternative selectors the recorder can offer the author for this tap, ranked best-first.
   * Empty for non-tap interactions, taps on empty space, or platforms without a
   * [xyz.block.trailblaze.api.TrailblazeNode] tree.
   *
   * Computed once at record time and held in memory for the recording session — the trail YAML
   * carries only the chosen [tool], not the candidate set. Excluded from [equals]/[hashCode]
   * because two interactions are logically the same when the chosen tool, target, and timing
   * agree; the rest of the candidates are advisory metadata for the UI.
   */
  val selectorCandidates: List<TrailblazeNodeSelectorGenerator.NamedSelector> = emptyList(),
  /**
   * In-memory only. The [TrailblazeNode] tree captured at the moment this interaction fired,
   * or null when the device couldn't produce one. Excluded from [equals]/[hashCode] (UI-advisory
   * metadata, not identity) and never serialized to trail YAML.
   *
   * Held so the picker UI can re-run [TrailblazeNodeSelectorGenerator.findAllValidSelectors] /
   * [TrailblazeNodeSelectorGenerator.resolveFromTap] on demand with different parameters than
   * the cascade that ran at record time. The pre-computed [selectorCandidates] are still useful
   * as a default; this is the escape hatch for "show me MORE / different / structural-only
   * candidates" — and for `tapOnPoint` results where the original cascade bailed because round-
   * trip validation failed, picker access requires re-running resolution against this tree.
   */
  val capturedTree: TrailblazeNode? = null,
  /**
   * For tap-shaped tools (TapOnByElementSelector, TapOnPointTrailblazeTool, longPress), the
   * original (x, y) the user clicked. Lets the picker re-run
   * [TrailblazeNodeSelectorGenerator.resolveFromTap] against [capturedTree] to find the target
   * node — even for `tapOnPoint` results where the original cascade bailed because round-trip
   * validation failed. Null for non-tap tools (inputText, navigate, swipe, etc.). Excluded
   * from [equals]/[hashCode].
   */
  val tapPoint: Pair<Int, Int>? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is RecordedInteraction) return false
    return tool == other.tool &&
      toolName == other.toolName &&
      timestamp == other.timestamp &&
      (screenshotBytes contentEquals other.screenshotBytes) &&
      viewHierarchyText == other.viewHierarchyText
  }

  override fun hashCode(): Int {
    var result = tool.hashCode()
    result = 31 * result + toolName.hashCode()
    result = 31 * result + timestamp.hashCode()
    result = 31 * result + (screenshotBytes?.contentHashCode() ?: 0)
    result = 31 * result + (viewHierarchyText?.hashCode() ?: 0)
    return result
  }
}
