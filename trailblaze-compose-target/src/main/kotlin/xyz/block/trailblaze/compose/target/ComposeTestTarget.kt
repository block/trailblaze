package xyz.block.trailblaze.compose.target

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsNode

/**
 * Abstraction over a Compose UI test target.
 *
 * Provides raw semantics tree access and action dispatch for Compose UI testing. The interface
 * operates on [SemanticsNode] rather than matchers, keeping it free of `compose-ui-test`
 * dependencies so production apps can import it without pulling in test framework code.
 *
 * Implementations:
 * - [ComposeUiTestTarget]: Wraps `ComposeUiTest` for existing test harnesses.
 * - [LiveWindowComposeTarget]: Wraps a live `ComposeWindow` for self-testing.
 */
interface ComposeTestTarget {

  /** Returns the root semantics node of the main composition. */
  fun rootSemanticsNode(): SemanticsNode

  /**
   * Returns root semantics nodes for all active compositions.
   *
   * In Compose Desktop, popups, dialogs, and dropdown menus each create a separate composition
   * with its own semantics tree. This method returns one root per composition so callers can
   * build a complete view of all visible UI, including overlays.
   *
   * Default implementation returns only [rootSemanticsNode] (sufficient for test harnesses).
   */
  fun allRootSemanticsNodes(): List<SemanticsNode> = listOf(rootSemanticsNode())

  /** Returns all semantics nodes in the composition tree (flattened). */
  fun allSemanticsNodes(): List<SemanticsNode>

  /** Performs a click action on the given node. */
  fun click(node: SemanticsNode)

  /** Types text into the given node. */
  fun typeText(node: SemanticsNode, text: String)

  /** Clears text from the given node. */
  fun clearText(node: SemanticsNode)

  /** Scrolls the given node to the specified index. */
  fun scrollToIndex(node: SemanticsNode, index: Int)

  /** Captures a screenshot of the current UI state. */
  fun captureScreenshot(): ImageBitmap?

  /** Waits for the UI to reach an idle state. */
  fun waitForIdle()
}
