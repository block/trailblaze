package xyz.block.trailblaze.compose.target

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.text.AnnotatedString
import javax.swing.SwingUtilities
import org.jetbrains.skia.Image
import org.jetbrains.skiko.SkiaLayer

/**
 * [ComposeTestTarget] backed by a live [ComposeWindow].
 *
 * Used to expose a running Compose Desktop application as a test target. Semantics are accessed via
 * the public `ComposeWindow.semanticsOwners` API. Actions are dispatched by invoking
 * [SemanticsActions] directly on the resolved node, with EDT dispatch for thread safety.
 *
 * Screenshots are captured via [SkiaLayer.screenshot] which reads directly from the Skia rendering
 * surface — no OS-level screen capture permissions required.
 */
@OptIn(ExperimentalComposeUiApi::class)
class LiveWindowComposeTarget(
  private val window: ComposeWindow,
) : ComposeTestTarget {

  override fun rootSemanticsNode(): SemanticsNode = onEdt {
    // Use the first semantics owner (the main window composition).
    window.semanticsOwners.first().rootSemanticsNode
  }

  override fun allRootSemanticsNodes(): List<SemanticsNode> = onEdt {
    // One root per semantics owner — includes popups, dialogs, dropdowns.
    window.semanticsOwners.map { it.rootSemanticsNode }
  }

  override fun allSemanticsNodes(): List<SemanticsNode> = onEdt {
    // Collect nodes from ALL semantics owners so popups/dialogs/dropdowns are included.
    window.semanticsOwners.flatMap { collectAllNodes(it.rootSemanticsNode) }
  }

  override fun click(node: SemanticsNode) = onEdt {
    val clickNode =
      findNodeOrAncestorWithAction(node, SemanticsActions.OnClick)
        ?: error("No clickable node or ancestor found for: $node")
    clickNode.config[SemanticsActions.OnClick].action?.invoke()
    Unit
  }

  override fun typeText(node: SemanticsNode, text: String) = onEdt {
    if (SemanticsActions.InsertTextAtCursor in node.config) {
      node.config[SemanticsActions.InsertTextAtCursor].action?.invoke(AnnotatedString(text))
    } else {
      val textNode =
        findNodeOrAncestorWithAction(node, SemanticsActions.SetText)
          ?: error("No text-input node or ancestor found for: $node")
      textNode.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(text))
    }
    Unit
  }

  override fun clearText(node: SemanticsNode) = onEdt {
    val textNode =
      findNodeOrAncestorWithAction(node, SemanticsActions.SetText)
        ?: error("No text-clearing node or ancestor found for: $node")
    textNode.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(""))
    Unit
  }

  override fun scrollToIndex(node: SemanticsNode, index: Int) = onEdt {
    val scrollNode =
      findNodeOrAncestorWithAction(node, SemanticsActions.ScrollToIndex)
        ?: error("No scrollable node or ancestor found for: $node")
    scrollNode.config[SemanticsActions.ScrollToIndex].action?.invoke(index)
    Unit
  }

  override fun captureScreenshot(): ImageBitmap? {
    return try {
      onEdt {
        val skiaLayer = findSkiaLayer(window) ?: return@onEdt null
        val bitmap = skiaLayer.screenshot() ?: return@onEdt null
        Image.makeFromBitmap(bitmap).toComposeImageBitmap()
      }
    } catch (e: Exception) {
      null
    }
  }

  override fun waitForIdle() {
    // Drain the EDT event queue to let Compose process pending recompositions.
    SwingUtilities.invokeAndWait {}

    // Poll until Compose rendering stabilizes (no more animation frames being drawn).
    // Actions like clicks can trigger animated state changes (e.g., a dialog appearing with
    // an enter animation). We sample a few pixels from the Skia layer; once they stop
    // changing across STABLE_FRAME_COUNT consecutive frames, rendering has settled.
    var prevHash = onEdt { frameHash() }
    var stableFrames = 0
    repeat(SETTLE_MAX_FRAMES) {
      Thread.sleep(FRAME_INTERVAL_MS)
      val currentHash = onEdt { frameHash() }
      if (currentHash == prevHash) {
        if (++stableFrames >= STABLE_FRAME_COUNT) return
      } else {
        stableFrames = 0
        prevHash = currentHash
      }
    }
  }

  /**
   * Samples pixel colors at 3 points in the Skia layer to detect frame changes.
   * Returns 0 if the layer or bitmap is unavailable (treated as stable).
   * Must be called on the EDT.
   */
  private fun frameHash(): Int {
    val layer = findSkiaLayer(window) ?: return 0
    val bitmap = try { layer.screenshot() } catch (_: Exception) { return 0 } ?: return 0
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return 0
    return (bitmap.getColor(w / 2, h / 2) * 31 +
      bitmap.getColor(w / 4, h / 4)) * 31 +
      bitmap.getColor(3 * w / 4, 3 * h / 4)
  }

  companion object {
    private const val FRAME_INTERVAL_MS = 16L // ~1 frame at 60fps
    private const val STABLE_FRAME_COUNT = 3  // consecutive identical frames = settled
    private const val SETTLE_MAX_FRAMES = 50  // max ~800ms total wait
  }

  /**
   * If [node] has the given action in its config, return it. Otherwise walk up the parent chain to
   * find the nearest ancestor that does. This handles unmerged semantics trees where text and
   * actions may be on different nodes.
   */
  private fun findNodeOrAncestorWithAction(
    node: SemanticsNode,
    action: SemanticsPropertyKey<*>,
  ): SemanticsNode? {
    if (action in node.config) return node
    var current: SemanticsNode? = node.parent
    while (current != null) {
      if (action in current.config) return current
      current = current.parent
    }
    return null
  }

  /** Recursively finds the [SkiaLayer] component within the window's component hierarchy. */
  private fun findSkiaLayer(container: java.awt.Container): SkiaLayer? {
    for (component in container.components) {
      if (component is SkiaLayer) return component
      if (component is java.awt.Container) {
        val found = findSkiaLayer(component)
        if (found != null) return found
      }
    }
    return null
  }

  private fun collectAllNodes(node: SemanticsNode): List<SemanticsNode> =
    listOf(node) + node.children.flatMap { collectAllNodes(it) }

  private fun <T> onEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) {
      return block()
    }
    val result = java.util.concurrent.atomic.AtomicReference<Any?>()
    val error = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    SwingUtilities.invokeAndWait {
      try {
        result.set(block())
      } catch (e: Throwable) {
        error.set(e)
      }
    }
    error.get()?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result.get() as T
  }
}
