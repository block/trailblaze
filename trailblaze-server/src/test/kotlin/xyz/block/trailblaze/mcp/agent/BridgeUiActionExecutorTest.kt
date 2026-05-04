package xyz.block.trailblaze.mcp.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.mcp.agent.BridgeUiActionExecutor.Companion.ELEMENT_TYPE_BUTTON
import xyz.block.trailblaze.mcp.agent.BridgeUiActionExecutor.Companion.ELEMENT_TYPE_CHECKBOX
import xyz.block.trailblaze.mcp.agent.BridgeUiActionExecutor.Companion.ELEMENT_TYPE_ICON
import xyz.block.trailblaze.mcp.agent.BridgeUiActionExecutor.Companion.ELEMENT_TYPE_INPUT
import xyz.block.trailblaze.mcp.agent.BridgeUiActionExecutor.Companion.ELEMENT_TYPE_RADIO
import xyz.block.trailblaze.mcp.agent.BridgeUiActionExecutor.Companion.ELEMENT_TYPE_SCROLL
import xyz.block.trailblaze.mcp.agent.BridgeUiActionExecutor.Companion.ELEMENT_TYPE_TAB
import xyz.block.trailblaze.mcp.agent.BridgeUiActionExecutor.Companion.ELEMENT_TYPE_TOGGLE
import xyz.block.trailblaze.mcp.executor.ConfigurableMockBridge
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [BridgeUiActionExecutor.inferElementTypeFromVh] and
 * [BridgeUiActionExecutor.stripSystemUiSubtrees].
 */
class BridgeUiActionExecutorTest {

  private val executor = BridgeUiActionExecutor(mcpBridge = ConfigurableMockBridge())

  // ---------------------------------------------------------------------------
  // inferElementTypeFromVh
  // ---------------------------------------------------------------------------

  @Test
  fun `inferElementTypeFromVh returns input when hintText is present`() {
    val node = ViewHierarchyTreeNode(hintText = "Enter name")
    assertEquals(ELEMENT_TYPE_INPUT, executor.inferElementTypeFromVh(node))
  }

  @Test
  fun `inferElementTypeFromVh returns scroll when scrollable`() {
    val node = ViewHierarchyTreeNode(scrollable = true)
    assertEquals(ELEMENT_TYPE_SCROLL, executor.inferElementTypeFromVh(node))
  }

  @Test
  fun `inferElementTypeFromVh returns checkbox when checked`() {
    val node = ViewHierarchyTreeNode(checked = true)
    assertEquals(ELEMENT_TYPE_CHECKBOX, executor.inferElementTypeFromVh(node))
  }

  @Test
  fun `inferElementTypeFromVh returns button for Button className`() {
    val node = ViewHierarchyTreeNode(className = "android.widget.Button")
    assertEquals(ELEMENT_TYPE_BUTTON, executor.inferElementTypeFromVh(node))
  }

  @Test
  fun `inferElementTypeFromVh returns toggle for Switch className`() {
    val node = ViewHierarchyTreeNode(className = "android.widget.Switch")
    assertEquals(ELEMENT_TYPE_TOGGLE, executor.inferElementTypeFromVh(node))
  }

  @Test
  fun `inferElementTypeFromVh returns toggle for ToggleButton className`() {
    val node = ViewHierarchyTreeNode(className = "android.widget.ToggleButton")
    assertEquals(ELEMENT_TYPE_TOGGLE, executor.inferElementTypeFromVh(node))
  }

  @Test
  fun `inferElementTypeFromVh returns tab for TabView className`() {
    val node = ViewHierarchyTreeNode(className = "android.widget.TabView")
    assertEquals(ELEMENT_TYPE_TAB, executor.inferElementTypeFromVh(node))
  }

  @Test
  fun `inferElementTypeFromVh returns checkbox for CheckBox className`() {
    val node = ViewHierarchyTreeNode(className = "android.widget.CheckBox")
    assertEquals(ELEMENT_TYPE_CHECKBOX, executor.inferElementTypeFromVh(node))
  }

  @Test
  fun `inferElementTypeFromVh returns checkbox for CompoundButton with check in name`() {
    val node = ViewHierarchyTreeNode(className = "com.example.CheckableView")
    assertEquals(ELEMENT_TYPE_CHECKBOX, executor.inferElementTypeFromVh(node))
  }

  @Test
  fun `inferElementTypeFromVh returns radio for RadioButton className`() {
    val node = ViewHierarchyTreeNode(className = "android.widget.RadioButton")
    assertEquals(ELEMENT_TYPE_RADIO, executor.inferElementTypeFromVh(node))
  }

  @Test
  fun `inferElementTypeFromVh returns input for EditText className`() {
    val node = ViewHierarchyTreeNode(className = "android.widget.EditText")
    assertEquals(ELEMENT_TYPE_INPUT, executor.inferElementTypeFromVh(node))
  }

  @Test
  fun `inferElementTypeFromVh returns input for TextField className`() {
    val node = ViewHierarchyTreeNode(className = "com.example.TextField")
    assertEquals(ELEMENT_TYPE_INPUT, executor.inferElementTypeFromVh(node))
  }

  @Test
  fun `inferElementTypeFromVh returns input for TextInput className`() {
    val node = ViewHierarchyTreeNode(className = "com.example.TextInput")
    assertEquals(ELEMENT_TYPE_INPUT, executor.inferElementTypeFromVh(node))
  }

  @Test
  fun `inferElementTypeFromVh returns icon for clickable ImageView`() {
    val node = ViewHierarchyTreeNode(className = "android.widget.ImageView", clickable = true)
    assertEquals(ELEMENT_TYPE_ICON, executor.inferElementTypeFromVh(node))
  }

  @Test
  fun `inferElementTypeFromVh returns null for non-clickable ImageView`() {
    val node = ViewHierarchyTreeNode(className = "android.widget.ImageView", clickable = false)
    assertNull(executor.inferElementTypeFromVh(node))
  }

  @Test
  fun `inferElementTypeFromVh returns null for unknown className`() {
    val node = ViewHierarchyTreeNode(className = "android.widget.FrameLayout")
    assertNull(executor.inferElementTypeFromVh(node))
  }

  @Test
  fun `inferElementTypeFromVh returns null when no properties match`() {
    val node = ViewHierarchyTreeNode()
    assertNull(executor.inferElementTypeFromVh(node))
  }

  @Test
  fun `inferElementTypeFromVh prioritizes hintText over className`() {
    val node = ViewHierarchyTreeNode(
      hintText = "Search",
      className = "android.widget.Button",
    )
    assertEquals(ELEMENT_TYPE_INPUT, executor.inferElementTypeFromVh(node))
  }

  @Test
  fun `inferElementTypeFromVh prioritizes scrollable over className`() {
    val node = ViewHierarchyTreeNode(
      scrollable = true,
      className = "android.widget.Button",
    )
    assertEquals(ELEMENT_TYPE_SCROLL, executor.inferElementTypeFromVh(node))
  }

  // ---------------------------------------------------------------------------
  // stripSystemUiSubtrees
  // ---------------------------------------------------------------------------

  @Test
  fun `stripSystemUiSubtrees removes children with systemui resource id prefix`() {
    val root = ViewHierarchyTreeNode(
      children = listOf(
        ViewHierarchyTreeNode(resourceId = "com.android.systemui:id/status_bar", text = "status"),
        ViewHierarchyTreeNode(resourceId = "com.example:id/content", text = "content"),
      ),
    )
    val result = executor.stripSystemUiSubtrees(root)
    assertEquals(1, result.children.size)
    assertEquals("content", result.children[0].text)
  }

  @Test
  fun `stripSystemUiSubtrees removes children with statusBarBackground resource id`() {
    val root = ViewHierarchyTreeNode(
      children = listOf(
        ViewHierarchyTreeNode(resourceId = "statusBarBackground"),
        ViewHierarchyTreeNode(resourceId = "app_content", text = "hello"),
      ),
    )
    val result = executor.stripSystemUiSubtrees(root)
    assertEquals(1, result.children.size)
    assertEquals("hello", result.children[0].text)
  }

  @Test
  fun `stripSystemUiSubtrees removes children with navigationBarBackground resource id`() {
    val root = ViewHierarchyTreeNode(
      children = listOf(
        ViewHierarchyTreeNode(resourceId = "navigationBarBackground"),
        ViewHierarchyTreeNode(text = "keep"),
      ),
    )
    val result = executor.stripSystemUiSubtrees(root)
    assertEquals(1, result.children.size)
    assertEquals("keep", result.children[0].text)
  }

  @Test
  fun `stripSystemUiSubtrees clears children of system ui node`() {
    val systemChild = ViewHierarchyTreeNode(text = "nested")
    val root = ViewHierarchyTreeNode(
      resourceId = "com.android.systemui:id/root",
      children = listOf(systemChild),
    )
    val result = executor.stripSystemUiSubtrees(root)
    assertEquals(0, result.children.size)
  }

  @Test
  fun `stripSystemUiSubtrees preserves non-system nodes recursively`() {
    val root = ViewHierarchyTreeNode(
      children = listOf(
        ViewHierarchyTreeNode(
          resourceId = "app:id/container",
          children = listOf(
            ViewHierarchyTreeNode(text = "deep child"),
            ViewHierarchyTreeNode(resourceId = "com.android.systemui:id/icon"),
          ),
        ),
      ),
    )
    val result = executor.stripSystemUiSubtrees(root)
    assertEquals(1, result.children.size)
    val container = result.children[0]
    assertEquals(1, container.children.size)
    assertEquals("deep child", container.children[0].text)
  }

  @Test
  fun `stripSystemUiSubtrees preserves tree when no system ui present`() {
    val root = ViewHierarchyTreeNode(
      children = listOf(
        ViewHierarchyTreeNode(text = "a"),
        ViewHierarchyTreeNode(
          text = "b",
          children = listOf(ViewHierarchyTreeNode(text = "c")),
        ),
      ),
    )
    val result = executor.stripSystemUiSubtrees(root)
    assertEquals(2, result.children.size)
    assertEquals(1, result.children[1].children.size)
  }

  @Test
  fun `stripSystemUiSubtrees keeps nodes with null resource id`() {
    val root = ViewHierarchyTreeNode(
      children = listOf(
        ViewHierarchyTreeNode(resourceId = null, text = "no id"),
      ),
    )
    val result = executor.stripSystemUiSubtrees(root)
    assertEquals(1, result.children.size)
  }

  // ---------------------------------------------------------------------------
  // mapToTrailblazeTool — no-repo fallback (regression for #2634 lead-dev review)
  // ---------------------------------------------------------------------------

  @Test
  fun `mapToTrailblazeTool wraps as OtherTrailblazeTool when no repo configured`() {
    // Pins the no-repo fallback path. With neither `dynamicToolRepoProvider` nor
    // `trailblazeToolRepo` wired, the executor must wrap the args verbatim so the bridge
    // can still forward to the device — without this, a callsite that legitimately doesn't
    // need a repo would hard-fail on every tool dispatch.
    val executorWithoutRepo = BridgeUiActionExecutor(mcpBridge = ConfigurableMockBridge())
    val args = buildJsonObject { put("ref", "z639") }

    val tool = runBlocking {
      executorWithoutRepo.mapToTrailblazeTool(toolName = "tap", args = args)
    }

    assertTrue(tool is OtherTrailblazeTool, "expected OtherTrailblazeTool wrap, got ${tool::class.simpleName}")
    val wrapped = tool as OtherTrailblazeTool
    assertEquals("tap", wrapped.toolName)
    assertEquals(args, wrapped.raw)
  }
}
