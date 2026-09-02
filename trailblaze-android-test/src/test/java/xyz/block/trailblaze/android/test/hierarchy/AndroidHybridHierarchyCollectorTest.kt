package xyz.block.trailblaze.android.test.hierarchy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode

class AndroidHybridHierarchyCollectorTest {
  @Test
  fun `graft substitutes compose descendants beneath matching host`() {
    val host = node(
      id = 2,
      bounds = bounds(0, 0, 100, 100),
      detail = DriverNodeDetail.AndroidView(className = COMPOSE_HOST),
    )
    val views = node(
      id = 1,
      bounds = bounds(0, 0, 200, 200),
      detail = DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(host),
    )
    val compose = node(
      id = 3,
      bounds = bounds(10, 10, 90, 90),
      detail = DriverNodeDetail.Compose(testTag = "confirm", hasClickAction = true),
    )

    val result = AndroidHybridHierarchyCollector.graftForTest(views, listOf(compose))

    val graftedHost = result.children.single()
    assertEquals("confirm", assertIs<DriverNodeDetail.Compose>(graftedHost.children.single().driverDetail).testTag)
  }

  @Test
  fun `graft retains unmatched compose roots as window siblings`() {
    val views = node(
      id = 1,
      bounds = bounds(0, 0, 100, 100),
      detail = DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
    )
    val popup = node(
      id = 2,
      bounds = bounds(120, 0, 180, 50),
      detail = DriverNodeDetail.Compose(text = "Popup"),
    )

    val result = AndroidHybridHierarchyCollector.graftForTest(views, listOf(popup))

    assertEquals("Popup", assertIs<DriverNodeDetail.Compose>(result.children.single().driverDetail).text)
  }

  /**
   * Only the in-process View shape hosts Compose. An accessibility-projected tree names the same
   * class, but its nodes are not the live views this driver acts on, and grafting native semantics
   * under them would produce a tree half of which nothing can act on.
   */
  @Test
  fun `graft ignores a compose host that did not come from the live view tree`() {
    val views = node(
      id = 1,
      bounds = bounds(0, 0, 200, 200),
      detail = DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(
        node(
          id = 2,
          bounds = bounds(0, 0, 100, 100),
          detail = DriverNodeDetail.AndroidAccessibility(className = COMPOSE_HOST),
        ),
      ),
    )
    val compose = node(
      id = 3,
      bounds = bounds(10, 10, 90, 90),
      detail = DriverNodeDetail.Compose(testTag = "confirm"),
    )

    val result = AndroidHybridHierarchyCollector.graftForTest(views, listOf(compose))

    val accessibilityHost = result.children.first { it.nodeId == 2L }
    assertTrue(accessibilityHost.children.isEmpty(), "Compose was grafted under a foreign host")
    assertEquals(
      "confirm",
      assertIs<DriverNodeDetail.Compose>(result.children.last().driverDetail).testTag,
      "Ungrafted Compose must survive as a sibling rather than vanish",
    )
  }

  /**
   * The View children of a Compose host are the content of an `AndroidView` composable, and Compose
   * semantics stop at that boundary — this tree is their only record. Replacing the host's children
   * with the grafted Compose tree deletes them.
   */
  @Test
  fun `graft keeps the views a compose host embeds`() {
    val embedded = node(
      id = 3,
      bounds = bounds(10, 10, 90, 40),
      detail = DriverNodeDetail.AndroidView(className = "android.widget.TextView", text = "Embedded"),
    )
    val host = node(
      id = 2,
      bounds = bounds(0, 0, 100, 100),
      detail = DriverNodeDetail.AndroidView(className = COMPOSE_HOST),
      children = listOf(embedded),
    )
    val compose = node(
      id = 4,
      bounds = bounds(0, 0, 100, 100),
      detail = DriverNodeDetail.Compose(testTag = "confirm"),
    )

    val result = AndroidHybridHierarchyCollector.graftForTest(host, listOf(compose))

    assertEquals(
      listOf<Long>(4, 3),
      result.children.map { it.nodeId },
      "The embedded View was dropped when Compose was grafted",
    )
  }

  /**
   * Compose hosts nest, so every ancestor's rectangle also contains a descendant's semantics root
   * and bounds alone cannot say which host owns what. Identity can.
   */
  @Test
  fun `graft matches nested hosts by semantics identity rather than bounds`() {
    val innerHost = node(
      id = 3,
      bounds = bounds(0, 0, 100, 50),
      detail = DriverNodeDetail.AndroidView(className = COMPOSE_HOST),
    )
    val outerHost = node(
      id = 2,
      bounds = bounds(0, 0, 100, 100),
      detail = DriverNodeDetail.AndroidView(className = COMPOSE_HOST),
      children = listOf(innerHost),
    )
    val outerCompose = node(
      id = 4,
      bounds = bounds(0, 0, 100, 100),
      detail = DriverNodeDetail.Compose(testTag = "outer"),
    )
    val innerCompose = node(
      id = 5,
      bounds = bounds(0, 0, 100, 50),
      detail = DriverNodeDetail.Compose(testTag = "inner"),
    )

    val result = AndroidHybridHierarchyCollector.graftForTest(
      viewTree = outerHost,
      composeTrees = listOf(outerCompose, innerCompose),
      hostSemanticsIdByNodeId = mapOf(2L to 100, 3L to 200),
      semanticsIdByNodeId = mapOf(4L to 100, 5L to 200),
    )

    val grafted = result.children.first { it.nodeId == 4L }
    assertEquals("outer", assertIs<DriverNodeDetail.Compose>(grafted.driverDetail).testTag)
    val inner = result.children.first { it.nodeId == 3L }.children.single()
    assertEquals(
      "inner",
      assertIs<DriverNodeDetail.Compose>(inner.driverDetail).testTag,
      "The inner host's own Compose tree did not land under it",
    )
  }

  private fun node(
    id: Long,
    bounds: TrailblazeNode.Bounds,
    detail: DriverNodeDetail,
    children: List<TrailblazeNode> = emptyList(),
  ) = TrailblazeNode(id, children = children, bounds = bounds, driverDetail = detail)

  private fun bounds(left: Int, top: Int, right: Int, bottom: Int) =
    TrailblazeNode.Bounds(left, top, right, bottom)

  private companion object {
    const val COMPOSE_HOST = "androidx.compose.ui.platform.AndroidComposeView"
  }
}
