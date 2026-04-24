package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for the shared strategy factories in `TrailblazeNodeSelectorGeneratorHelpers`.
 *
 * Each factory wraps a helper call as a `Pair<String, () -> TrailblazeNodeSelector?>`
 * — written at call sites as `"name" to { /* returns TrailblazeNodeSelector? */ }`.
 * Per-driver behavior (which fields get matched, priority order) is covered by the
 * existing per-generator test files; these tests only assert the **shared factory contract**:
 *
 *   1. The default `name` is what we expect (catches accidental renames in review).
 *   2. A custom `name` override is respected.
 *   3. When invoked against a tree where the underlying helper succeeds, the lambda
 *      produces a selector with the expected structural shape (childOf / containsChild /
 *      spatial / index, etc.).
 *
 * Uses the Compose driver for all fixtures — it's the simplest sealed variant with a stable
 * `testTag` field that makes building "uniquely identifiable" nodes ergonomic.
 */
class TrailblazeNodeSelectorGeneratorHelpersTest : TrailblazeNodeSelectorGeneratorTestBase() {

  // ---------------------------------------------------------------------------
  // Content strategy factories
  // ---------------------------------------------------------------------------

  @Test
  fun `childOfUniqueParentStrategy - default name and childOf produced`() {
    val (root, target, parentMap) = parentAnchoredTree()

    val (name, lambda) = childOfUniqueParentStrategy(root, target, target.driverDetail, parentMap)

    assertEquals("Child of parent", name)
    val selector = lambda()
    assertNotNull(selector, "Expected a selector when the target has a unique ancestor")
    assertNotNull(selector.childOf, "Strategy should populate childOf")
  }

  @Test
  fun `childOfUniqueParentStrategy - custom name override`() {
    val (root, target, parentMap) = parentAnchoredTree()

    val (name, _) = childOfUniqueParentStrategy(
      root, target, target.driverDetail, parentMap, name = "Custom child-of",
    )

    assertEquals("Custom child-of", name)
  }

  @Test
  fun `containsUniqueChildStrategy - default name and containsChild produced`() {
    val (root, target) = targetWithUniqueChildTree()

    val (name, lambda) = containsUniqueChildStrategy(root, target, target.driverDetail)

    assertEquals("Contains child", name)
    val selector = lambda()
    assertNotNull(selector, "Expected a selector when the target has a unique child")
    assertNotNull(selector.containsChild, "Strategy should populate containsChild")
  }

  @Test
  fun `spatialStrategy - default name and spatial predicate produced`() {
    val (root, target, parentMap) = siblingSpatialTree()

    val (name, lambda) = spatialStrategy(root, target, parentMap)

    assertEquals("Spatial relationship", name)
    val selector = lambda()
    assertNotNull(selector, "Expected a selector when a uniquely-identifiable sibling exists")
    val hasSpatial = selector.above != null || selector.below != null ||
      selector.leftOf != null || selector.rightOf != null
    assertEquals(true, hasSpatial, "Strategy should populate one of above/below/leftOf/rightOf")
  }

  @Test
  fun `indexFallbackStrategy - default name and index populated`() {
    val (root, target) = ambiguousSiblingsTree()

    val (name, lambda) = indexFallbackStrategy(root, target, target.driverDetail)

    assertEquals("Index fallback", name)
    val selector = lambda()
    assertNotNull(selector, "Expected a selector — index fallback should always succeed")
    assertNotNull(selector.index, "Strategy should populate index")
  }

  // ---------------------------------------------------------------------------
  // Structural strategy factories
  // ---------------------------------------------------------------------------

  @Test
  fun `structuralChildOfParentStrategy - default name and childOf produced`() {
    val (root, target, parentMap) = structuralParentAnchoredTree()

    val (name, lambda) = structuralChildOfParentStrategy(root, target, target.driverDetail, parentMap)

    assertEquals("Structural: child of parent", name)
    val selector = lambda()
    assertNotNull(selector, "Expected a selector when the target has a structural parent anchor")
    assertNotNull(selector.childOf)
  }

  @Test
  fun `structuralChildOfLabeledParentStrategy - default name and childOf produced`() {
    val (root, target, parentMap) = parentAnchoredTree()

    val (name, lambda) = structuralChildOfLabeledParentStrategy(
      root, target, target.driverDetail, parentMap,
    )

    assertEquals("Structural: child of labeled parent", name)
    val selector = lambda()
    assertNotNull(selector, "Expected a selector when the target has a content-labeled ancestor")
    assertNotNull(selector.childOf)
  }

  @Test
  fun `structuralContainsChildStrategy - default name and containsChild produced`() {
    val (root, target) = targetWithUniqueChildTree()

    val (name, lambda) = structuralContainsChildStrategy(root, target)

    assertEquals("Structural: contains child", name)
    val selector = lambda()
    assertNotNull(selector, "Expected a selector when the target has a structurally-unique child")
    assertNotNull(selector.containsChild, "Strategy should populate containsChild")
  }

  @Test
  fun `structuralSpatialStrategy - default name`() {
    val (root, target, parentMap) = structuralSiblingTree()

    val (name, lambda) = structuralSpatialStrategy(root, target, parentMap)

    assertEquals("Structural: spatial", name)
    val selector = lambda()
    assertNotNull(selector, "Expected a selector when a structurally-identifiable sibling exists")
  }

  @Test
  fun `structuralContentAnchoredSpatialStrategy - default name and spatial predicate produced`() {
    val (root, target, parentMap) = siblingSpatialTree()

    val (name, lambda) = structuralContentAnchoredSpatialStrategy(root, target, parentMap)

    assertEquals("Structural: spatial (labeled anchor)", name)
    val selector = lambda()
    assertNotNull(selector, "Expected a selector when a content-labeled sibling anchor exists")
    val hasSpatial = selector.above != null || selector.below != null ||
      selector.leftOf != null || selector.rightOf != null
    assertEquals(true, hasSpatial, "Strategy should populate one of above/below/leftOf/rightOf")
  }

  @Test
  fun `structuralScopedIndexStrategy - default name`() {
    val (root, target, parentMap) = structuralParentAnchoredTree()

    val (name, lambda) = structuralScopedIndexStrategy(root, target, target.driverDetail, parentMap)

    assertEquals("Structural: scoped index in parent", name)
    assertNotNull(lambda(), "Expected a scoped index selector when parent is identifiable")
  }

  @Test
  fun `structuralIndexFallbackStrategy - caller supplies name, always produces a selector`() {
    val (root, target) = ambiguousSiblingsTree()

    val (name, lambda) = structuralIndexFallbackStrategy(
      root, target, target.driverDetail, name = "Structural: role + index",
    )

    // No default — callers must name it after their primary type.
    assertEquals("Structural: role + index", name)
    val selector = lambda()
    assertNotNull(selector, "Global index fallback should always succeed")
    assertNotNull(selector.index)
  }

  @Test
  fun `strategies return null lambda result when prerequisites are not met`() {
    nextId = 1L
    // Target has no identifiable content and no unique ancestor — content strategies
    // that need buildTargetMatch(detail) should gracefully return null rather than
    // throw.
    val target = nodeOf(detail = DriverNodeDetail.Compose())
    val root = nodeOf(detail = DriverNodeDetail.Compose(), children = listOf(target))
    val parentMap = buildParentMap(root)

    assertNull(
      childOfUniqueParentStrategy(root, target, target.driverDetail, parentMap).second(),
      "Expected null when no unique parent exists",
    )
    assertNull(
      containsUniqueChildStrategy(root, target, target.driverDetail).second(),
      "Expected null when the target has no unique children",
    )
  }

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  /**
   * Tree: root → parent(testTag=UniqueParent) → [target(text=Pay), other(text=Cancel)].
   * The target has a uniquely-identifiable ancestor, so `findUniqueParentSelector` succeeds.
   */
  private fun parentAnchoredTree(): Triple<TrailblazeNode, TrailblazeNode, Map<Long, TrailblazeNode>> {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.Compose(role = "Button", text = "Pay"),
      bounds = TrailblazeNode.Bounds(10, 110, 100, 150),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.Compose(role = "Button", text = "Cancel"),
      bounds = TrailblazeNode.Bounds(110, 110, 200, 150),
    )
    val parent = nodeOf(
      detail = DriverNodeDetail.Compose(testTag = "UniqueParent"),
      bounds = TrailblazeNode.Bounds(0, 100, 300, 200),
      children = listOf(target, other),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.Compose(),
      bounds = TrailblazeNode.Bounds(0, 0, 300, 300),
      children = listOf(parent),
    )
    return Triple(root, target, buildParentMap(root))
  }

  /**
   * Tree: root → [target, sibling], target contains a uniquely-identifiable child
   * (testTag=Badge). `findUniqueChildSelector` should find the badge and construct a
   * containsChild selector.
   */
  private fun targetWithUniqueChildTree(): Pair<TrailblazeNode, TrailblazeNode> {
    nextId = 1L
    val uniqueChild = nodeOf(
      detail = DriverNodeDetail.Compose(testTag = "Badge"),
      bounds = TrailblazeNode.Bounds(20, 20, 40, 40),
    )
    val target = nodeOf(
      detail = DriverNodeDetail.Compose(role = "Button"),
      bounds = TrailblazeNode.Bounds(10, 10, 100, 50),
      children = listOf(uniqueChild),
    )
    val sibling = nodeOf(
      detail = DriverNodeDetail.Compose(role = "Button"),
      bounds = TrailblazeNode.Bounds(110, 10, 200, 50),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.Compose(),
      bounds = TrailblazeNode.Bounds(0, 0, 300, 100),
      children = listOf(target, sibling),
    )
    return root to target
  }

  /**
   * Tree with two content-bearing siblings spatially separated — `findSpatialSelector`
   * finds a uniquely-identifiable anchor and returns an above/below/leftOf/rightOf match.
   */
  private fun siblingSpatialTree(): Triple<TrailblazeNode, TrailblazeNode, Map<Long, TrailblazeNode>> {
    nextId = 1L
    val anchor = nodeOf(
      detail = DriverNodeDetail.Compose(testTag = "Anchor", text = "Anchor"),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
    )
    val target = nodeOf(
      detail = DriverNodeDetail.Compose(role = "Button", text = "Pay"),
      bounds = TrailblazeNode.Bounds(0, 100, 100, 150),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.Compose(),
      bounds = TrailblazeNode.Bounds(0, 0, 200, 300),
      children = listOf(anchor, target),
    )
    return Triple(root, target, buildParentMap(root))
  }

  /**
   * Tree with a structurally-identifiable parent (role=Toolbar). The target's structural
   * parent selector can anchor to that role.
   */
  private fun structuralParentAnchoredTree(): Triple<TrailblazeNode, TrailblazeNode, Map<Long, TrailblazeNode>> {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.Compose(role = "Button"),
      bounds = TrailblazeNode.Bounds(10, 110, 100, 150),
    )
    val parent = nodeOf(
      detail = DriverNodeDetail.Compose(role = "Toolbar"),
      bounds = TrailblazeNode.Bounds(0, 100, 300, 200),
      children = listOf(target),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.Compose(),
      bounds = TrailblazeNode.Bounds(0, 0, 300, 300),
      children = listOf(parent),
    )
    return Triple(root, target, buildParentMap(root))
  }

  /**
   * Two siblings with structural identity — one a Toolbar, one a Button — so the
   * structural spatial finder has a uniquely-identifiable anchor sibling.
   */
  private fun structuralSiblingTree(): Triple<TrailblazeNode, TrailblazeNode, Map<Long, TrailblazeNode>> {
    nextId = 1L
    val anchor = nodeOf(
      detail = DriverNodeDetail.Compose(role = "Toolbar"),
      bounds = TrailblazeNode.Bounds(0, 0, 300, 50),
    )
    val target = nodeOf(
      detail = DriverNodeDetail.Compose(role = "Button"),
      bounds = TrailblazeNode.Bounds(10, 100, 100, 150),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.Compose(),
      bounds = TrailblazeNode.Bounds(0, 0, 300, 300),
      children = listOf(anchor, target),
    )
    return Triple(root, target, buildParentMap(root))
  }

  /**
   * Two identical-looking Button siblings so no content strategy can distinguish
   * them — forces the index fallback to fire.
   */
  private fun ambiguousSiblingsTree(): Pair<TrailblazeNode, TrailblazeNode> {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.Compose(role = "Button", text = "Save"),
      bounds = TrailblazeNode.Bounds(10, 10, 100, 50),
    )
    val dupe = nodeOf(
      detail = DriverNodeDetail.Compose(role = "Button", text = "Save"),
      bounds = TrailblazeNode.Bounds(110, 10, 200, 50),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.Compose(),
      bounds = TrailblazeNode.Bounds(0, 0, 300, 100),
      children = listOf(target, dupe),
    )
    return root to target
  }

}
