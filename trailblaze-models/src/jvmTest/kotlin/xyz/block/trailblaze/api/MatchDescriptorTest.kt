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
