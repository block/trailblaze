package xyz.block.trailblaze.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatchDescriptorTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `serializes and round-trips through Json`() {
    val original = MatchDescriptor(
      indexPath = listOf(0, 2, 1, 4),
      bounds = TrailblazeNode.Bounds(left = 10, top = 20, right = 110, bottom = 80),
      matchedText = "Submit",
      accessibilityId = "submit_button",
      resourceId = "com.example:id/submit",
    )

    val encoded = json.encodeToString(MatchDescriptor.serializer(), original)
    val decoded = json.decodeFromString(MatchDescriptor.serializer(), encoded)

    assertEquals(original, decoded)
  }

  @Test
  fun `null optional fields survive round-trip`() {
    val original = MatchDescriptor(
      indexPath = emptyList(),
      bounds = TrailblazeNode.Bounds(0, 0, 0, 0),
    )

    val encoded = json.encodeToString(MatchDescriptor.serializer(), original)
    val decoded = json.decodeFromString(MatchDescriptor.serializer(), encoded)

    assertEquals(original, decoded)
    assertNull(decoded.matchedText)
    assertNull(decoded.accessibilityId)
    assertNull(decoded.resourceId)
  }

  @Test
  fun `bounds = null round-trips through Json`() {
    // Pinned after the Codex P1 fix made bounds nullable — guards against a future
    // explicitNulls / encodeDefaults flip silently omitting null fields and breaking
    // the TS-side optional-vs-nullable contract.
    val original = MatchDescriptor(
      indexPath = listOf(0),
      bounds = null,
    )

    val encoded = json.encodeToString(MatchDescriptor.serializer(), original)
    val decoded = json.decodeFromString(MatchDescriptor.serializer(), encoded)

    assertEquals(original, decoded)
    assertNull(decoded.bounds)
  }

  // -- MatchDescriptorBuilder.indexPathOf --

  @Test
  fun `indexPathOf returns empty for root`() {
    val root = TrailblazeNode(nodeId = 1, driverDetail = DriverNodeDetail.AndroidAccessibility())
    assertEquals(emptyList(), MatchDescriptorBuilder.indexPathOf(root, root))
  }

  @Test
  fun `indexPathOf walks child indices`() {
    val target = TrailblazeNode(nodeId = 99, driverDetail = DriverNodeDetail.AndroidAccessibility())
    val midContainer = TrailblazeNode(
      nodeId = 10,
      children = listOf(
        TrailblazeNode(nodeId = 11, driverDetail = DriverNodeDetail.AndroidAccessibility()),
        TrailblazeNode(nodeId = 12, driverDetail = DriverNodeDetail.AndroidAccessibility()),
        target,
      ),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
    )
    val root = TrailblazeNode(
      nodeId = 1,
      children = listOf(
        TrailblazeNode(nodeId = 2, driverDetail = DriverNodeDetail.AndroidAccessibility()),
        midContainer,
      ),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
    )

    assertEquals(listOf(1, 2), MatchDescriptorBuilder.indexPathOf(root, target))
  }

  @Test
  fun `indexPathOf returns null when target is not in tree`() {
    val target = TrailblazeNode(nodeId = 99, driverDetail = DriverNodeDetail.AndroidAccessibility())
    val root = TrailblazeNode(
      nodeId = 1,
      children = listOf(
        TrailblazeNode(nodeId = 2, driverDetail = DriverNodeDetail.AndroidAccessibility()),
      ),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
    )
    assertNull(MatchDescriptorBuilder.indexPathOf(root, target))
  }

  // -- MatchDescriptorBuilder.indexPaths --
  //
  // `indexPaths` exists only to give the same answer as `indexPathOf` in one walk instead of one
  // per node, so agreement with it IS the specification. A faster function that disagreed would
  // shift every match's `indexPath`, which is how a scripted caller re-identifies an element.

  @Test
  fun `indexPaths agrees with indexPathOf for every node`() {
    var next = 1L
    fun node(vararg children: TrailblazeNode) = TrailblazeNode(
      nodeId = next++,
      children = children.toList(),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
    )
    // Ragged on purpose: differing child counts and depths, so a walk that mismanaged its path
    // stack (failing to pop, or popping twice) cannot coincidentally agree.
    val root = node(
      node(node(), node(node(), node())),
      node(),
      node(node(node(node()))),
    )

    val paths = MatchDescriptorBuilder.indexPaths(root)
    val allNodes = root.aggregate()

    assertEquals(allNodes.size, paths.size, "every node must get a path")
    allNodes.forEach { n ->
      assertEquals(
        MatchDescriptorBuilder.indexPathOf(root, n),
        paths[n.nodeId],
        "path for nodeId=${n.nodeId} must match indexPathOf",
      )
    }
    assertEquals(emptyList(), paths[root.nodeId], "root's path is empty")
  }

  @Test
  fun `indexPaths keeps the first DFS occurrence when nodeIds collide`() {
    // Both functions compare by nodeId, so on a colliding tree both must name the
    // shallowest-leftmost node. Divergence here would make the batch form and the single form
    // answer differently for the same tree, which is the one thing it may not do.
    val dupe = { TrailblazeNode(nodeId = 7, driverDetail = DriverNodeDetail.AndroidAccessibility()) }
    val root = TrailblazeNode(
      nodeId = 1,
      children = listOf(
        TrailblazeNode(
          nodeId = 2,
          children = listOf(dupe()),
          driverDetail = DriverNodeDetail.AndroidAccessibility(),
        ),
        dupe(),
      ),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
    )

    val paths = MatchDescriptorBuilder.indexPaths(root)
    assertEquals(listOf(0, 0), paths[7L])
    assertEquals(MatchDescriptorBuilder.indexPathOf(root, dupe()), paths[7L])
  }

  @Test
  fun `toMatchDescriptor returns null for a node absent from the path map`() {
    // The null contract the query tools' assertion rests on: a node the map cannot place is
    // undescribable. Nothing may paper over it with a fabricated path — an indexPath pointing at
    // a different element is worse than no descriptor at all.
    val orphan = TrailblazeNode(nodeId = 99, driverDetail = DriverNodeDetail.AndroidAccessibility())
    assertNull(orphan.toMatchDescriptor(emptyMap()))
    assertEquals(
      emptyList(),
      orphan.toMatchDescriptor(mapOf(99L to emptyList<Int>()))?.indexPath,
    )
  }

  // -- toMatchDescriptor identity extraction (one test per driver variant) --
  //
  // MatchDescriptorBuilder.extractIdentity has a 6-arm `when` on DriverNodeDetail;
  // each arm has its own field-mapping rules (e.g. Web uses `ariaName` rather than
  // a `resolveText()` chain, IosAxe collapses uniqueId into BOTH accessibilityId
  // AND resourceId). Cover every arm so a future field rename doesn't silently
  // drift across an untested driver.

  @Test
  fun `build extracts AndroidAccessibility identity`() {
    val matched = TrailblazeNode(
      nodeId = 7,
      bounds = TrailblazeNode.Bounds(5, 10, 105, 60),
      driverDetail = DriverNodeDetail.AndroidAccessibility(
        text = "Submit",
        contentDescription = "submit_button",
        resourceId = "com.example:id/submit",
      ),
    )
    val root = TrailblazeNode(
      nodeId = 1,
      children = listOf(matched),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
    )

    val descriptor = matched.toMatchDescriptor(root)

    assertEquals(
      MatchDescriptor(
        indexPath = listOf(0),
        bounds = TrailblazeNode.Bounds(5, 10, 105, 60),
        matchedText = "Submit",
        accessibilityId = "submit_button",
        resourceId = "com.example:id/submit",
      ),
      descriptor,
    )
  }

  @Test
  fun `build extracts AndroidMaestro identity`() {
    val matched = TrailblazeNode(
      nodeId = 7,
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
      driverDetail = DriverNodeDetail.AndroidMaestro(
        text = "Continue",
        accessibilityText = "continue-content-desc",
        resourceId = "com.example:id/continue",
      ),
    )
    val root = TrailblazeNode(
      nodeId = 1,
      children = listOf(matched),
      driverDetail = DriverNodeDetail.AndroidMaestro(),
    )

    val descriptor = matched.toMatchDescriptor(root)!!

    assertEquals("Continue", descriptor.matchedText)
    assertEquals("continue-content-desc", descriptor.accessibilityId)
    assertEquals("com.example:id/continue", descriptor.resourceId)
  }

  @Test
  fun `build extracts IosMaestro identity`() {
    val matched = TrailblazeNode(
      nodeId = 7,
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
      driverDetail = DriverNodeDetail.IosMaestro(
        text = "Done",
        accessibilityText = "done-axlabel",
        resourceId = "doneButton",
      ),
    )
    val root = TrailblazeNode(
      nodeId = 1,
      children = listOf(matched),
      driverDetail = DriverNodeDetail.IosMaestro(),
    )

    val descriptor = matched.toMatchDescriptor(root)!!

    assertEquals("Done", descriptor.matchedText)
    assertEquals("done-axlabel", descriptor.accessibilityId)
    assertEquals("doneButton", descriptor.resourceId)
  }

  @Test
  fun `build extracts IosAxe identity with uniqueId mapped to both accessibilityId and resourceId`() {
    // IosAxe collapses uniqueId into BOTH accessibilityId AND resourceId — the
    // single accessibilityIdentifier is the canonical identity on iOS AX, so it
    // shows up under both fields.
    val matched = TrailblazeNode(
      nodeId = 7,
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
      driverDetail = DriverNodeDetail.IosAxe(
        role = "AXButton",
        label = "Cancel",
        uniqueId = "cancelButton",
      ),
    )
    val root = TrailblazeNode(
      nodeId = 1,
      children = listOf(matched),
      driverDetail = DriverNodeDetail.IosAxe(),
    )

    val descriptor = matched.toMatchDescriptor(root)!!

    assertEquals("Cancel", descriptor.matchedText)
    assertEquals("cancelButton", descriptor.accessibilityId)
    assertEquals("cancelButton", descriptor.resourceId)
  }

  @Test
  fun `build extracts Compose identity with testTag mapped to resourceId`() {
    val matched = TrailblazeNode(
      nodeId = 7,
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
      driverDetail = DriverNodeDetail.Compose(
        testTag = "submit_compose",
        text = "Submit",
        contentDescription = "submit-content-desc",
      ),
    )
    val root = TrailblazeNode(
      nodeId = 1,
      children = listOf(matched),
      driverDetail = DriverNodeDetail.Compose(),
    )

    val descriptor = matched.toMatchDescriptor(root)!!

    assertEquals("Submit", descriptor.matchedText)
    assertEquals("submit-content-desc", descriptor.accessibilityId)
    assertEquals("submit_compose", descriptor.resourceId)
  }

  @Test
  fun `build extracts Web identity via aria fields`() {
    val matched = TrailblazeNode(
      nodeId = 7,
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
      driverDetail = DriverNodeDetail.Web(
        ariaRole = "button",
        ariaName = "Search",
        ariaDescriptor = "button \"Search\"",
        dataTestId = "search-btn",
      ),
    )
    val root = TrailblazeNode(
      nodeId = 1,
      children = listOf(matched),
      driverDetail = DriverNodeDetail.Web(),
    )

    val descriptor = matched.toMatchDescriptor(root)!!

    assertEquals("Search", descriptor.matchedText)
    assertEquals("button \"Search\"", descriptor.accessibilityId)
    assertEquals("search-btn", descriptor.resourceId)
  }
}
