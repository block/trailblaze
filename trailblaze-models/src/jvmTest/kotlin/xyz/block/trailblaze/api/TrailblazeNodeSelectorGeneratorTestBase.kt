package xyz.block.trailblaze.api

import kotlin.test.assertEquals
import kotlin.test.assertTrue

abstract class TrailblazeNodeSelectorGeneratorTestBase {

  protected var nextId = 1L

  /** Creates a node with any [DriverNodeDetail] variant. */
  protected fun nodeOf(
    detail: DriverNodeDetail,
    bounds: TrailblazeNode.Bounds? = TrailblazeNode.Bounds(0, 0, 100, 50),
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode {
    val id = nextId++
    return TrailblazeNode(nodeId = id, children = children, bounds = bounds, driverDetail = detail)
  }

  protected fun assertUniqueMatch(
    root: TrailblazeNode,
    target: TrailblazeNode,
  ): TrailblazeNodeSelector {
    val selector = TrailblazeNodeSelectorGenerator.findBestSelector(root, target)
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertTrue(
      result is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch,
      "Expected SingleMatch but got $result for selector: ${selector.description()}",
    )
    assertEquals(target.nodeId, result.node.nodeId)
    return selector
  }
}
