package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for [PlaywrightScreenState] bounding box enrichment and
 * viewport visibility annotation.
 *
 * Verifies that:
 * - [ViewHierarchyDetail.BOUNDS] correctly resolves bounding boxes for elements
 * - Elements outside the viewport are annotated with `(offscreen)` by default
 * - The batch JS visibility check works in a single round-trip
 *
 * Uses a real Chromium browser with inline HTML pages.
 */
class PlaywrightScreenStateBoundsTest {

  private lateinit var playwright: Playwright
  private lateinit var browser: Browser
  private lateinit var page: Page

  @Before
  fun setUp() {
    playwright = Playwright.create()
    browser = playwright.chromium().launch(
      BrowserType.LaunchOptions().setHeadless(true),
    )
    val context = browser.newContext(
      Browser.NewContextOptions().setViewportSize(1280, 800),
    )
    page = context.newPage()
  }

  @After
  fun tearDown() {
    browser.close()
    playwright.close()
  }

  private val testHtml = """
    <!DOCTYPE html>
    <html>
    <body>
      <nav aria-label="Main">
        <a href="#home">Home</a>
        <a href="#about">About</a>
      </nav>
      <main>
        <h1>Welcome</h1>
        <form>
          <label for="email">Email</label>
          <input id="email" type="text" aria-label="Email" />
          <button type="submit">Submit</button>
        </form>
      </main>
    </body>
    </html>
  """.trimIndent()

  @Test
  fun `compact element list without bounds has no coordinates`() {
    page.setContent(testHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    // Default compact list should NOT contain bounding box coordinates
    assertFalse(text.contains("{x:"), "Default compact list should not contain bounds, but got:\n$text")
    assertFalse(text.contains(",w:"), "Default compact list should not contain bounds, but got:\n$text")
    // Should still have element IDs
    assertContains(text, "[e1]")
  }

  @Test
  fun `compact element list with BOUNDS includes bounding boxes`() {
    page.setContent(testHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
      requestedDetails = setOf(ViewHierarchyDetail.BOUNDS),
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    // Should contain element IDs
    assertContains(text, "[e1]")
    // Should contain bounding box coordinates for at least some elements
    assertTrue(text.contains("{x:"), "Enriched list should contain bounds, but got:\n$text")
    assertTrue(text.contains(",w:"), "Enriched list should contain width, but got:\n$text")
    assertTrue(text.contains(",h:"), "Enriched list should contain height, but got:\n$text")
  }

  @Test
  fun `bounding boxes have reasonable values`() {
    page.setContent(testHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
      requestedDetails = setOf(ViewHierarchyDetail.BOUNDS),
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    // Parse out bounding boxes and verify they have positive dimensions
    val boundsPattern = Regex("""\{x:(\d+),y:(\d+),w:(\d+),h:(\d+)}""")
    val matches = boundsPattern.findAll(text).toList()
    assertTrue(matches.isNotEmpty(), "Should find at least one bounding box in:\n$text")

    for (match in matches) {
      val w = match.groupValues[3].toInt()
      val h = match.groupValues[4].toInt()
      assertTrue(w > 0, "Width should be positive: $match")
      assertTrue(h > 0, "Height should be positive: $match")
    }
  }

  @Test
  fun `element ID mapping is consistent between default and enriched modes`() {
    page.setContent(testHtml)

    val defaultState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )
    val enrichedState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
      requestedDetails = setOf(ViewHierarchyDetail.BOUNDS),
    )

    // Element ID mapping should be identical in both modes
    assertNotNull(defaultState.elementIdMapping)
    assertNotNull(enrichedState.elementIdMapping)
    assertTrue(
      defaultState.elementIdMapping.keys == enrichedState.elementIdMapping.keys,
      "Element IDs should be identical: default=${defaultState.elementIdMapping.keys}, " +
        "enriched=${enrichedState.elementIdMapping.keys}",
    )
  }

  @Test
  fun `browser manager auto-clears pending detail requests after consumption`() {
    page.setContent(testHtml)

    val browserManager = PlaywrightBrowserManager(headless = true)
    try {
      browserManager.currentPage.setContent(testHtml)

      // Request bounds
      browserManager.requestDetails(setOf(ViewHierarchyDetail.BOUNDS))

      // First getScreenState should have bounds
      val enrichedState = browserManager.getScreenState() as PlaywrightScreenState
      val enrichedText = enrichedState.viewHierarchyTextRepresentation!!
      assertTrue(enrichedText.contains("{x:"), "First state should have bounds:\n$enrichedText")

      // Second getScreenState should NOT have bounds (auto-cleared)
      val defaultState = browserManager.getScreenState() as PlaywrightScreenState
      val defaultText = defaultState.viewHierarchyTextRepresentation!!
      assertFalse(defaultText.contains("{x:"), "Second state should not have bounds:\n$defaultText")
    } finally {
      browserManager.close()
    }
  }

  // -- Viewport visibility annotation tests --

  /**
   * HTML page with a tall layout where some elements are guaranteed to be below
   * the 800px viewport fold. The spacer div pushes the "offscreen" section down.
   */
  private val tallPageHtml = """
    <!DOCTYPE html>
    <html>
    <body style="margin:0; padding:0;">
      <nav aria-label="Top nav">
        <a href="#a">Visible Link</a>
      </nav>
      <main>
        <h1>Top Heading</h1>
        <button>Visible Button</button>
        <!-- Push everything below here past the 800px viewport -->
        <div style="height: 2000px;"></div>
        <h2>Offscreen Heading</h2>
        <button>Offscreen Button</button>
        <a href="#bottom">Offscreen Link</a>
      </main>
    </body>
    </html>
  """.trimIndent()

  @Test
  fun `elements in viewport are not annotated as offscreen`() {
    page.setContent(tallPageHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    // "Visible Link", "Top Heading", "Visible Button" should NOT be offscreen
    val visibleLinkLine = text.lines().find { it.contains("\"Visible Link\"") }
    assertNotNull(visibleLinkLine, "Should find 'Visible Link' in:\n$text")
    assertFalse(
      visibleLinkLine.contains("(offscreen)"),
      "'Visible Link' should not be offscreen: $visibleLinkLine",
    )

    val visibleButtonLine = text.lines().find { it.contains("\"Visible Button\"") }
    assertNotNull(visibleButtonLine, "Should find 'Visible Button' in:\n$text")
    assertFalse(
      visibleButtonLine.contains("(offscreen)"),
      "'Visible Button' should not be offscreen: $visibleButtonLine",
    )
  }

  @Test
  fun `elements below viewport fold are filtered out by default`() {
    page.setContent(tallPageHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    // Elements below the 2000px spacer should be absent from the output
    assertFalse(
      text.contains("\"Offscreen Heading\""),
      "'Offscreen Heading' should be filtered out, but got:\n$text",
    )
    assertFalse(
      text.contains("\"Offscreen Button\""),
      "'Offscreen Button' should be filtered out, but got:\n$text",
    )
    assertFalse(
      text.contains("\"Offscreen Link\""),
      "'Offscreen Link' should be filtered out, but got:\n$text",
    )
    // Should have a summary line indicating hidden elements
    assertTrue(
      text.contains("offscreen elements hidden"),
      "Should have summary line about hidden offscreen elements, but got:\n$text",
    )
    assertTrue(
      text.contains("request OFFSCREEN_ELEMENTS"),
      "Summary line should mention OFFSCREEN_ELEMENTS detail type, but got:\n$text",
    )
  }

  @Test
  fun `all elements in viewport page have no offscreen annotations or summary`() {
    // The basic testHtml fits entirely within 1280x800 viewport
    page.setContent(testHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    assertFalse(
      text.contains("(offscreen)"),
      "Small page should have no offscreen annotations, but got:\n$text",
    )
    assertFalse(
      text.contains("offscreen elements hidden"),
      "Small page should have no offscreen summary line, but got:\n$text",
    )
  }

  @Test
  fun `offscreen elements included when OFFSCREEN_ELEMENTS detail requested`() {
    page.setContent(tallPageHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
      requestedDetails = setOf(ViewHierarchyDetail.OFFSCREEN_ELEMENTS),
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    // Offscreen elements should be present and annotated
    val offscreenHeadingLine = text.lines().find { it.contains("\"Offscreen Heading\"") }
    assertNotNull(offscreenHeadingLine, "Should find 'Offscreen Heading' in:\n$text")
    assertTrue(
      offscreenHeadingLine.contains("(offscreen)"),
      "'Offscreen Heading' should be annotated as offscreen: $offscreenHeadingLine",
    )

    val offscreenButtonLine = text.lines().find { it.contains("\"Offscreen Button\"") }
    assertNotNull(offscreenButtonLine, "Should find 'Offscreen Button' in:\n$text")
    assertTrue(
      offscreenButtonLine.contains("(offscreen)"),
      "'Offscreen Button' should be annotated as offscreen: $offscreenButtonLine",
    )

    // Visible elements should NOT have offscreen annotation
    val visibleButtonLine = text.lines().find { it.contains("\"Visible Button\"") }
    assertNotNull(visibleButtonLine, "Should find 'Visible Button' in:\n$text")
    assertFalse(
      visibleButtonLine.contains("(offscreen)"),
      "'Visible Button' should not be offscreen: $visibleButtonLine",
    )

    // Should NOT have the summary line since all elements are included
    assertFalse(
      text.contains("offscreen elements hidden"),
      "Should not have hidden summary when OFFSCREEN_ELEMENTS requested, but got:\n$text",
    )
  }

  @Test
  fun `offscreen annotation works alongside BOUNDS enrichment`() {
    page.setContent(tallPageHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
      requestedDetails = setOf(ViewHierarchyDetail.BOUNDS, ViewHierarchyDetail.OFFSCREEN_ELEMENTS),
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    // Should have both bounds AND offscreen annotations
    assertTrue(text.contains("{x:"), "Should have bounds in:\n$text")
    assertTrue(text.contains("(offscreen)"), "Should have offscreen annotations in:\n$text")

    // Offscreen elements should have both annotations
    val offscreenButtonLine = text.lines().find { it.contains("\"Offscreen Button\"") }
    assertNotNull(offscreenButtonLine, "Should find 'Offscreen Button' in:\n$text")
    assertTrue(
      offscreenButtonLine.contains("{x:") && offscreenButtonLine.contains("(offscreen)"),
      "Offscreen button should have both bounds and offscreen annotation: $offscreenButtonLine",
    )
  }
}
