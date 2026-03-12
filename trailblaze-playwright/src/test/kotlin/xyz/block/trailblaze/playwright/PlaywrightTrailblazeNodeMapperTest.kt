package xyz.block.trailblaze.playwright

import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeDetail
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [PlaywrightTrailblazeNodeMapper].
 *
 * These tests validate the ARIA YAML → [TrailblazeNode] mapping without a live browser.
 * Bounds enrichment with a live page is tested in integration tests.
 */
class PlaywrightTrailblazeNodeMapperTest {

  @Test
  fun `blank yaml returns null`() {
    assertNull(PlaywrightTrailblazeNodeMapper.mapAriaSnapshotToTrailblazeNode(""))
    assertNull(PlaywrightTrailblazeNodeMapper.mapAriaSnapshotToTrailblazeNode("   "))
  }

  @Test
  fun `simple page with interactive elements`() {
    val yaml = """
      - document:
        - button "Submit"
        - link "Home"
        - textbox "Email"
    """.trimIndent()

    val root = PlaywrightTrailblazeNodeMapper.mapAriaSnapshotToTrailblazeNode(yaml)
    assertNotNull(root)

    val detail = root.driverDetail as DriverNodeDetail.Web
    assertEquals("document", detail.ariaRole)
    assertNull(detail.ariaName)

    assertEquals(3, root.children.size)

    val button = root.children[0].driverDetail as DriverNodeDetail.Web
    assertEquals("button", button.ariaRole)
    assertEquals("Submit", button.ariaName)
    assertEquals("button \"Submit\"", button.ariaDescriptor)
    assertTrue(button.isInteractive)

    val link = root.children[1].driverDetail as DriverNodeDetail.Web
    assertEquals("link", link.ariaRole)
    assertEquals("Home", link.ariaName)
    assertTrue(link.isInteractive)

    val textbox = root.children[2].driverDetail as DriverNodeDetail.Web
    assertEquals("textbox", textbox.ariaRole)
    assertEquals("Email", textbox.ariaName)
    assertTrue(textbox.isInteractive)
  }

  @Test
  fun `nested structure with landmarks`() {
    val yaml = """
      - document:
        - navigation:
          - link "Home"
          - link "About"
        - main:
          - heading "Welcome" [level=1]
          - button "Submit"
    """.trimIndent()

    val root = PlaywrightTrailblazeNodeMapper.mapAriaSnapshotToTrailblazeNode(yaml)
    assertNotNull(root)

    assertEquals(2, root.children.size)

    val nav = root.children[0]
    val navDetail = nav.driverDetail as DriverNodeDetail.Web
    assertEquals("navigation", navDetail.ariaRole)
    assertTrue(navDetail.isLandmark)
    assertEquals(2, nav.children.size)

    val main = root.children[1]
    val mainDetail = main.driverDetail as DriverNodeDetail.Web
    assertEquals("main", mainDetail.ariaRole)
    assertTrue(mainDetail.isLandmark)
    assertEquals(2, main.children.size)

    val heading = main.children[0].driverDetail as DriverNodeDetail.Web
    assertEquals("heading", heading.ariaRole)
    assertEquals("Welcome", heading.ariaName)
    assertEquals(1, heading.headingLevel)
  }

  @Test
  fun `heading level extraction`() {
    val yaml = """
      - document:
        - heading "Title" [level=1]
        - heading "Subtitle" [level=2]
        - heading "Section" [level=3]
    """.trimIndent()

    val root = PlaywrightTrailblazeNodeMapper.mapAriaSnapshotToTrailblazeNode(yaml)
    assertNotNull(root)

    val h1 = root.children[0].driverDetail as DriverNodeDetail.Web
    assertEquals(1, h1.headingLevel)

    val h2 = root.children[1].driverDetail as DriverNodeDetail.Web
    assertEquals(2, h2.headingLevel)

    val h3 = root.children[2].driverDetail as DriverNodeDetail.Web
    assertEquals(3, h3.headingLevel)
  }

  @Test
  fun `duplicate descriptors get sequential nthIndex`() {
    val yaml = """
      - document:
        - navigation:
          - link "Hardware"
        - main:
          - link "Hardware"
    """.trimIndent()

    val root = PlaywrightTrailblazeNodeMapper.mapAriaSnapshotToTrailblazeNode(yaml)
    assertNotNull(root)

    val firstLink = root.children[0].children[0].driverDetail as DriverNodeDetail.Web
    assertEquals("Hardware", firstLink.ariaName)
    assertEquals(0, firstLink.nthIndex)

    val secondLink = root.children[1].children[0].driverDetail as DriverNodeDetail.Web
    assertEquals("Hardware", secondLink.ariaName)
    assertEquals(1, secondLink.nthIndex)
  }

  @Test
  fun `text content nodes`() {
    val yaml = """
      - document:
        - text: Hello world
    """.trimIndent()

    val root = PlaywrightTrailblazeNodeMapper.mapAriaSnapshotToTrailblazeNode(yaml)
    assertNotNull(root)

    val text = root.children[0].driverDetail as DriverNodeDetail.Web
    assertEquals("text", text.ariaRole)
    assertEquals("Hello world", text.ariaName)
    assertEquals("text: Hello world", text.ariaDescriptor)
  }

  @Test
  fun `container roles without names`() {
    val yaml = """
      - document:
        - navigation:
          - link "Home"
    """.trimIndent()

    val root = PlaywrightTrailblazeNodeMapper.mapAriaSnapshotToTrailblazeNode(yaml)
    assertNotNull(root)

    val nav = root.children[0].driverDetail as DriverNodeDetail.Web
    assertEquals("navigation", nav.ariaRole)
    assertNull(nav.ariaName)
    assertEquals("navigation", nav.ariaDescriptor)
    assertTrue(nav.isLandmark)
    assertTrue(!nav.isInteractive)
  }

  @Test
  fun `node IDs are unique`() {
    val yaml = """
      - document:
        - button "One"
        - button "Two"
        - button "Three"
    """.trimIndent()

    val root = PlaywrightTrailblazeNodeMapper.mapAriaSnapshotToTrailblazeNode(yaml)
    assertNotNull(root)

    val allNodes = root.aggregate()
    val nodeIds = allNodes.map { it.nodeId }
    assertEquals(nodeIds.distinct().size, nodeIds.size, "All node IDs should be unique")
  }

  @Test
  fun `enrichTreeWithBounds populates bounds from DOM data`() {
    val yaml = """
      - document:
        - button "Submit"
        - link "Home"
    """.trimIndent()

    val tree = PlaywrightTrailblazeNodeMapper.mapAriaSnapshotToTrailblazeNode(yaml)
    assertNotNull(tree)

    // Simulate DOM bounds data
    val domBounds = listOf(
      PlaywrightTrailblazeNodeMapper.DomElementBounds(
        tag = "html",
        ariaRole = null,
        ariaLabel = null,
        textContent = null,
        id = null,
        dataTestId = null,
        x = 0, y = 0, width = 1280, height = 800,
      ),
      PlaywrightTrailblazeNodeMapper.DomElementBounds(
        tag = "button",
        ariaRole = null,
        ariaLabel = null,
        textContent = "Submit",
        id = "submit-btn",
        dataTestId = null,
        x = 100, y = 200, width = 120, height = 40,
      ),
      PlaywrightTrailblazeNodeMapper.DomElementBounds(
        tag = "a",
        ariaRole = null,
        ariaLabel = null,
        textContent = "Home",
        id = null,
        dataTestId = "home-link",
        x = 50, y = 10, width = 80, height = 24,
      ),
    )

    val enriched = PlaywrightTrailblazeNodeMapper.enrichTreeWithBounds(tree, domBounds)

    // Check button bounds
    val buttonBounds = enriched.children[0].bounds
    assertNotNull(buttonBounds)
    assertEquals(100, buttonBounds.left)
    assertEquals(200, buttonBounds.top)
    assertEquals(220, buttonBounds.right) // 100 + 120
    assertEquals(240, buttonBounds.bottom) // 200 + 40

    // Check button CSS selector from id
    val buttonDetail = enriched.children[0].driverDetail as DriverNodeDetail.Web
    assertEquals("#submit-btn", buttonDetail.cssSelector)

    // Check link bounds
    val linkBounds = enriched.children[1].bounds
    assertNotNull(linkBounds)
    assertEquals(50, linkBounds.left)
    assertEquals(10, linkBounds.top)

    // Check link data-testid
    val linkDetail = enriched.children[1].driverDetail as DriverNodeDetail.Web
    assertEquals("home-link", linkDetail.dataTestId)
    assertEquals("[data-testid=\"home-link\"]", linkDetail.cssSelector)
  }

  @Test
  fun `enrichTreeWithBounds handles duplicate descriptors with nth matching`() {
    val yaml = """
      - document:
        - link "Hardware"
        - link "Hardware"
    """.trimIndent()

    val tree = PlaywrightTrailblazeNodeMapper.mapAriaSnapshotToTrailblazeNode(yaml)
    assertNotNull(tree)

    val domBounds = listOf(
      PlaywrightTrailblazeNodeMapper.DomElementBounds(
        tag = "html", ariaRole = null, ariaLabel = null, textContent = null,
        id = null, dataTestId = null, x = 0, y = 0, width = 1280, height = 800,
      ),
      PlaywrightTrailblazeNodeMapper.DomElementBounds(
        tag = "a", ariaRole = null, ariaLabel = null, textContent = "Hardware",
        id = null, dataTestId = null, x = 10, y = 10, width = 100, height = 20,
      ),
      PlaywrightTrailblazeNodeMapper.DomElementBounds(
        tag = "a", ariaRole = null, ariaLabel = null, textContent = "Hardware",
        id = null, dataTestId = null, x = 10, y = 500, width = 100, height = 20,
      ),
    )

    val enriched = PlaywrightTrailblazeNodeMapper.enrichTreeWithBounds(tree, domBounds)

    val first = enriched.children[0]
    val second = enriched.children[1]

    assertNotNull(first.bounds)
    assertNotNull(second.bounds)
    // First link should match first DOM element (y=10)
    assertEquals(10, first.bounds!!.top)
    // Second link should match second DOM element (y=500)
    assertEquals(500, second.bounds!!.top)
  }

  @Test
  fun `enrichTreeWithBounds leaves bounds null when no DOM match`() {
    val yaml = """
      - document:
        - button "Nonexistent"
    """.trimIndent()

    val tree = PlaywrightTrailblazeNodeMapper.mapAriaSnapshotToTrailblazeNode(yaml)
    assertNotNull(tree)

    val domBounds = listOf(
      PlaywrightTrailblazeNodeMapper.DomElementBounds(
        tag = "html", ariaRole = null, ariaLabel = null, textContent = null,
        id = null, dataTestId = null, x = 0, y = 0, width = 1280, height = 800,
      ),
    )

    val enriched = PlaywrightTrailblazeNodeMapper.enrichTreeWithBounds(tree, domBounds)
    assertNull(enriched.children[0].bounds)
  }

  @Test
  fun `real-world ARIA snapshot with mixed content`() {
    val yaml = """
      - document:
        - banner:
          - link "Logo"
          - navigation:
            - link "Products"
            - link "Support"
            - link "About"
        - main:
          - heading "Welcome to Our Store" [level=1]
          - text: Find the best products here
          - textbox "Search products"
          - list:
            - listitem:
              - link "Product A"
            - listitem:
              - link "Product B"
          - button "Load More"
        - contentinfo:
          - text: © 2024 Our Store
    """.trimIndent()

    val root = PlaywrightTrailblazeNodeMapper.mapAriaSnapshotToTrailblazeNode(yaml)
    assertNotNull(root)

    // Verify structure
    assertEquals(3, root.children.size) // banner, main, contentinfo

    val banner = root.children[0]
    assertEquals("banner", (banner.driverDetail as DriverNodeDetail.Web).ariaRole)

    val main = root.children[1]
    val mainDetail = main.driverDetail as DriverNodeDetail.Web
    assertEquals("main", mainDetail.ariaRole)
    assertTrue(mainDetail.isLandmark)

    // Verify all nodes have unique IDs
    val allNodes = root.aggregate()
    val ids = allNodes.map { it.nodeId }
    assertEquals(ids.size, ids.distinct().size)

    // Verify interactive elements are marked
    val interactiveNodes = allNodes.filter {
      (it.driverDetail as DriverNodeDetail.Web).isInteractive
    }
    assertTrue(interactiveNodes.isNotEmpty())
    assertTrue(interactiveNodes.all {
      val d = it.driverDetail as DriverNodeDetail.Web
      d.ariaRole in listOf("link", "button", "textbox")
    })
  }
}
