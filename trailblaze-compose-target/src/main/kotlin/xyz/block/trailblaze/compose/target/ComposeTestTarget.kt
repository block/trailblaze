package xyz.block.trailblaze.compose.target

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsNode
import xyz.block.trailblaze.api.DriverDispatch
import xyz.block.trailblaze.api.dispatchThenSettle

/**
 * Abstraction over a Compose UI test target.
 *
 * Provides raw semantics tree access and action dispatch for Compose UI testing. The interface
 * operates on [SemanticsNode] rather than matchers, keeping it free of `compose-ui-test`
 * dependencies so production apps can import it without pulling in test framework code.
 *
 * Note on transitive deps: this module api-depends on `trailblaze-models` for the [DriverDispatch]
 * contract, which transitively brings kotlinx-serialization, kaml, and the koog agent SDK.
 * Production consumers will see these on their classpath. If the weight becomes a concern, the
 * `DriverDispatch` interface could be carved out into a lighter `trailblaze-driver-api` module.
 *
 * Implementation: [ComposeUiTestTarget], which wraps `ComposeUiTest` for existing test harnesses.
 */
interface ComposeTestTarget : DriverDispatch {

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

  /**
   * Implements the [DriverDispatch] contract for Compose Desktop: runs [action], then waits
   * for the recomposition queue to drain via [waitForIdle].
   *
   * Every content-changing tool (click / typeText / scrollToIndex) MUST route through here
   * so the post-dispatch settle is impossible to forget when a new gesture is added. Verify
   * tools (reads, no content change) bypass this and use [allSemanticsNodes] directly.
   *
   * The body never actually suspends — it invokes [action] (which may suspend), then calls
   * the blocking [waitForIdle] settle primitive inline. The suspend signature exists so
   * tools (whose `executeWithCompose` is already suspend) can call it without ceremony.
   *
   * The settle wait runs whether [action] returns normally or throws. A `ComposeTimeoutException`
   * from it after the action landed is logged rather than failing the dispatch, so a click that
   * already changed the UI is never reported as failed. Any other exception [waitForIdle] throws —
   * it rethrows exceptions the app raised on the UI thread — still fails it. See [DriverDispatch]
   * kdoc. (Compose Desktop's own `ComposeUiTest.waitForIdle()` has no timeout; it waits until idle.)
   */
  override suspend fun <R> dispatchAndAwaitSettle(action: suspend () -> R): R =
    dispatchThenSettle(action, settle = ::waitForIdle, isSettleTimeout = ::isComposeIdleTimeout)
}

/**
 * Whether [error] is Compose test reporting that the UI never went idle. Matched by class name
 * because this module only compile-depends on `compose-ui-test`, and a production target may not
 * ship it at all.
 */
internal fun isComposeIdleTimeout(error: Throwable): Boolean =
  generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }
    .any { it.javaClass.name == "androidx.compose.ui.test.ComposeTimeoutException" }
