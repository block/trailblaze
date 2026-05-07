package xyz.block.trailblaze.recording

import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Factory interface for creating platform-specific tool instances from user input.
 * Playwright creates `web_click`, `web_type`, etc.
 * Maestro creates `tapOnElementBySelector`, `inputText`, etc.
 *
 * The optional [TrailblazeNode] tree passed to tap factories enables the modern
 * coordinate→selector translation (`TrailblazeNodeSelectorGenerator.resolveFromTap`).
 * Implementations may ignore it — older platforms still use the [ViewHierarchyTreeNode]
 * hit-test result.
 *
 * Lives in [commonMain] (not jvmAndAndroid) because the interface itself is pure data —
 * no JVM-only types in any signature, no `synchronized` blocks. Implementations may still
 * be JVM-only if they pull in JVM-specific drivers, but the contract is portable. This is
 * the first piece of the recording surface to move toward a wasmJs-compatible commonMain.
 */
interface InteractionToolFactory {
  /** Returns (TrailblazeTool, toolName) for a tap on the given node or coordinates. */
  fun createTapTool(
    node: ViewHierarchyTreeNode?,
    x: Int,
    y: Int,
    trailblazeNodeTree: TrailblazeNode? = null,
  ): Pair<TrailblazeTool, String>

  /** Returns (TrailblazeTool, toolName) for a long press. */
  fun createLongPressTool(
    node: ViewHierarchyTreeNode?,
    x: Int,
    y: Int,
    trailblazeNodeTree: TrailblazeNode? = null,
  ): Pair<TrailblazeTool, String>

  /**
   * Returns (TrailblazeTool, toolName) for a swipe gesture.
   *
   * @param durationMs Wall-clock duration of the user's gesture on the host. Embedded in
   *   the recorded tool so replay reproduces a fast flick as a flick and a slow drag as a
   *   drag, instead of all swipes collapsing to the driver's 400ms default. `null` falls
   *   through to the driver default — appropriate for callers that don't have a measured
   *   duration (programmatic tests, etc.).
   */
  fun createSwipeTool(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    durationMs: Long? = null,
  ): Pair<TrailblazeTool, String>

  /** Returns (TrailblazeTool, toolName) for text input. */
  fun createInputTextTool(text: String): Pair<TrailblazeTool, String>

  /** Returns (TrailblazeTool, toolName) for a special key press, or null if unsupported. */
  fun createPressKeyTool(key: String): Pair<TrailblazeTool, String>?

  /**
   * Returns the alternative selectors the recording UI can offer the author for the tap at
   * (x, y) — same primitive as `./trailblaze waypoint suggest-selector` runs against a
   * captured log. The first entry has [TrailblazeNodeSelectorGenerator.NamedSelector.isBest]
   * set and matches the selector [createTapTool] emitted by default.
   *
   * Default is empty for factories that don't have a [TrailblazeNode] tree to query
   * (e.g. Playwright). Empty disables the picker UI on those recordings.
   */
  fun findSelectorCandidates(
    trailblazeNodeTree: TrailblazeNode?,
    x: Int,
    y: Int,
  ): List<TrailblazeNodeSelectorGenerator.NamedSelector> = emptyList()
}
