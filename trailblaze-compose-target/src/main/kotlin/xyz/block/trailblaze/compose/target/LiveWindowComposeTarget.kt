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
    // Drain the EDT event queue to let Compose process pending recompositions
    SwingUtilities.invokeAndWait {}
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
