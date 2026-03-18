package xyz.block.trailblaze.compose.target

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput

/**
 * [ComposeTestTarget] backed by a [ComposeUiTest] instance.
 *
 * Used in existing test harnesses that run inside `runComposeUiTest { }`. Actions are dispatched
 * through the test framework's node interaction APIs for maximum backward compatibility.
 */
@OptIn(ExperimentalTestApi::class)
class ComposeUiTestTarget(
  val composeUiTest: ComposeUiTest,
) : ComposeTestTarget {

  override fun rootSemanticsNode(): SemanticsNode =
    composeUiTest.onRoot().fetchSemanticsNode()

  override fun allSemanticsNodes(): List<SemanticsNode> =
    composeUiTest
      .onAllNodes(SemanticsMatcher("all") { true })
      .fetchSemanticsNodes()

  override fun click(node: SemanticsNode) {
    composeUiTest.onNode(matchById(node.id)).performClick()
  }

  override fun typeText(node: SemanticsNode, text: String) {
    composeUiTest.onNode(matchById(node.id)).performTextInput(text)
  }

  override fun clearText(node: SemanticsNode) {
    composeUiTest.onNode(matchById(node.id)).performTextClearance()
  }

  override fun scrollToIndex(node: SemanticsNode, index: Int) {
    composeUiTest.onNode(matchById(node.id)).performScrollToIndex(index)
  }

  override fun captureScreenshot(): ImageBitmap? =
    try {
      composeUiTest.onRoot().captureToImage()
    } catch (e: Exception) {
      null
    }

  override fun waitForIdle() {
    composeUiTest.waitForIdle()
  }

  private fun matchById(nodeId: Int): SemanticsMatcher =
    SemanticsMatcher("nodeId($nodeId)") { it.id == nodeId }
}
