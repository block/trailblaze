package xyz.block.trailblaze.recording

import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * A single user interaction captured during interactive recording.
 * Contains the resolved semantic tool and optional screenshot/hierarchy context.
 */
data class RecordedInteraction(
  /** The resolved semantic tool (e.g., TapOnElementWithTextTrailblazeTool). */
  val tool: TrailblazeTool,
  /** Tool name for YAML serialization (e.g., "tapOnElementWithText"). */
  val toolName: String,
  /** Screenshot bytes captured at the moment of interaction. */
  val screenshotBytes: ByteArray?,
  /** View hierarchy text at the moment of interaction. */
  val viewHierarchyText: String?,
  /** Epoch millis when the interaction occurred. */
  val timestamp: Long,
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
