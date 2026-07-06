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

  // -- Batch JS viewport check correctness tests --

  /**
   * Complex page with diverse element types that exercises the batch JS viewport check.
   * Tests role matching (implicit + explicit) and accessible name computation for:
   * links, buttons, headings, inputs, selects, images, checkboxes, radio buttons,
   * aria-label, aria-labelledby, and nested text content.
   *
   * Elements above the spacer are in viewport; below are offscreen.
   */
  private val complexPageHtml = """
    <!DOCTYPE html>
    <html>
    <body style="margin:0; padding:0;">
      <nav aria-label="Primary">
        <a href="#home">Home</a>
        <a href="#about">About Us</a>
        <a href="#contact">Contact</a>
      </nav>
      <main>
        <h1>Dashboard</h1>
        <h2>User Settings</h2>
        <form>
          <label for="search-input">Search</label>
          <input id="search-input" type="search" aria-label="Search users" />
          <label for="name-input">Name</label>
          <input id="name-input" type="text" placeholder="Enter your name" />
          <select aria-label="Country">
            <option>United States</option>
            <option>Canada</option>
          </select>
          <label for="agree"><input id="agree" type="checkbox" /> I agree</label>
          <button type="submit">Save Changes</button>
        </form>
        <img src="logo.png" alt="Company Logo" width="100" height="50" />
        <p id="desc">Welcome back!</p>
        <span id="status-label">Status:</span>
        <div role="status" aria-labelledby="status-label">Active</div>
        <button aria-label="Close dialog">X</button>
        <a href="#more"><span>Learn</span> <span>More</span></a>

        <!-- Push below elements offscreen (past 800px viewport) -->
        <div style="height: 2000px;"></div>

        <h2>Offscreen Section</h2>
        <a href="#hidden">Hidden Link</a>
        <button>Hidden Button</button>
        <input type="text" aria-label="Offscreen Input" />
        <img src="offscreen.png" alt="Offscreen Image" width="100" height="50" />
        <input type="checkbox" aria-label="Offscreen Checkbox" />
      </main>
    </body>
    </html>
  """.trimIndent()

  @Test
  fun `batch JS correctly identifies diverse element types as in-viewport`() {
    page.setContent(complexPageHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    // All these elements should be present (visible, not filtered out)
    val expectedVisible = listOf(
      "\"Home\"", "\"About Us\"", "\"Contact\"",
      "\"Dashboard\"", "\"User Settings\"",
      "\"Search users\"", "\"Save Changes\"",
      "\"Company Logo\"", "\"Close dialog\"",
      "\"Learn More\"",
    )
    for (expected in expectedVisible) {
      assertTrue(
        text.contains(expected),
        "Expected visible element with $expected in output, but got:\n$text",
      )
    }
  }

  @Test
  fun `batch JS correctly identifies diverse element types as offscreen`() {
    page.setContent(complexPageHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    // All these elements should be filtered out (offscreen)
    val expectedOffscreen = listOf(
      "\"Offscreen Section\"",
      "\"Hidden Link\"",
      "\"Hidden Button\"",
      "\"Offscreen Input\"",
      "\"Offscreen Image\"",
      "\"Offscreen Checkbox\"",
    )
    for (expected in expectedOffscreen) {
      assertFalse(
        text.contains(expected),
        "Expected offscreen element with $expected to be filtered out, but found in:\n$text",
      )
    }
    assertTrue(
      text.contains("offscreen elements hidden"),
      "Should have offscreen summary line, but got:\n$text",
    )
  }

  @Test
  fun `batch JS matches Playwright ARIA snapshot for every element`() {
    page.setContent(complexPageHtml)

    // Capture ARIA snapshot and build compact list (this is what Playwright sees)
    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
      requestedDetails = setOf(ViewHierarchyDetail.OFFSCREEN_ELEMENTS),
    )

    val text = screenState.viewHierarchyTextRepresentation!!

    // Cross-reference: for every element in the compact list, verify the batch JS
    // classification matches Playwright's own locator-based bounding box check.
    for ((id, elementRef) in screenState.elementIdMapping) {
      val locator = PlaywrightAriaSnapshot.resolveElementRef(page, elementRef)
      if (locator.count() == 0) continue

      val box = locator.boundingBox() ?: continue
      val playwrightSaysInViewport = box.y + box.height > 0 && box.y < 800 &&
        box.x + box.width > 0 && box.x < 1280 &&
        box.width > 0 && box.height > 0

      // Find this element's line in the output
      val elementLine = text.lines().find { it.contains("[$id]") } ?: continue
      val batchSaysOffscreen = elementLine.contains("(offscreen)")

      if (playwrightSaysInViewport) {
        assertFalse(
          batchSaysOffscreen,
          "Element $id (${elementRef.descriptor}) is in viewport per Playwright " +
            "but batch JS marked it offscreen: $elementLine",
        )
      } else {
        assertTrue(
          batchSaysOffscreen,
          "Element $id (${elementRef.descriptor}) is offscreen per Playwright " +
            "but batch JS did NOT mark it offscreen: $elementLine",
        )
      }
    }
  }

  @Test
  fun `batch JS handles page with many duplicate elements`() {
    // Page with 50 links that share the same text — exercises nth-index disambiguation
    val manyLinksHtml = buildString {
      append("<!DOCTYPE html><html><body style='margin:0;padding:0;'><main>")
      append("<h1>Link List</h1>")
      for (i in 1..50) {
        append("""<a href="#item$i">Item</a> """)
      }
      // Push more links offscreen
      append("<div style='height:2000px;'></div>")
      for (i in 51..100) {
        append("""<a href="#item$i">Item</a> """)
      }
      append("</main></body></html>")
    }

    page.setContent(manyLinksHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    // Should have some visible elements
    assertTrue(text.contains("[e"), "Should have element IDs in output:\n$text")
    // Should have offscreen summary (the 50 links below the spacer)
    assertTrue(
      text.contains("offscreen elements hidden"),
      "Should have offscreen summary for duplicate-heavy page, but got:\n$text",
    )
    // Heading should be visible
    assertTrue(text.contains("\"Link List\""), "Heading should be visible:\n$text")
  }

  @Test
  fun `batch JS skips aria-hidden elements to keep nth-indices aligned`() {
    // This page has 3 DOM links named "Edit", but the middle one is aria-hidden.
    // Playwright's ARIA snapshot only sees 2: nth=0 (visible-top) and nth=1 (offscreen).
    // If the JS DOM walk doesn't skip the aria-hidden link, nth=1 would resolve to
    // the aria-hidden element instead of the offscreen one — a correctness bug.
    val ariaHiddenHtml = """
      <!DOCTYPE html>
      <html>
      <body style="margin:0; padding:0;">
        <main>
          <a href="#top">Edit</a>
          <div aria-hidden="true"><a href="#hidden">Edit</a></div>
          <div style="height: 2000px;"></div>
          <a href="#bottom">Edit</a>
        </main>
      </body>
      </html>
    """.trimIndent()

    page.setContent(ariaHiddenHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
      requestedDetails = setOf(ViewHierarchyDetail.OFFSCREEN_ELEMENTS),
    )

    val text = screenState.viewHierarchyTextRepresentation!!

    // Should have exactly 2 "Edit" links (the aria-hidden one is excluded by Playwright)
    val editLines = text.lines().filter { it.contains("link \"Edit\"") }
    assertTrue(
      editLines.size == 2,
      "Expected 2 'Edit' links (ARIA-hidden excluded), but found ${editLines.size} in:\n$text",
    )

    // Cross-reference each element against Playwright's own locator resolution
    for ((id, elementRef) in screenState.elementIdMapping) {
      val locator = PlaywrightAriaSnapshot.resolveElementRef(page, elementRef)
      if (locator.count() == 0) continue
      val box = locator.boundingBox() ?: continue
      val playwrightInViewport = box.y + box.height > 0 && box.y < 800 &&
        box.x + box.width > 0 && box.x < 1280
      val elementLine = text.lines().find { it.contains("[$id]") } ?: continue
      val batchSaysOffscreen = elementLine.contains("(offscreen)")
      if (playwrightInViewport) {
        assertFalse(
          batchSaysOffscreen,
          "Element $id (${elementRef.descriptor} nth=${elementRef.nthIndex}) " +
            "is in viewport per Playwright but batch JS says offscreen: $elementLine",
        )
      } else {
        assertTrue(
          batchSaysOffscreen,
          "Element $id (${elementRef.descriptor} nth=${elementRef.nthIndex}) " +
            "is offscreen per Playwright but batch JS missed it: $elementLine",
        )
      }
    }
  }

  @Test
  fun `batch JS skips display-none elements to keep nth-indices aligned`() {
    val displayNoneHtml = """
      <!DOCTYPE html>
      <html>
      <body style="margin:0; padding:0;">
        <main>
          <button>Submit</button>
          <div style="display:none;"><button>Submit</button></div>
          <div style="height: 2000px;"></div>
          <button>Submit</button>
        </main>
      </body>
      </html>
    """.trimIndent()

    page.setContent(displayNoneHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
      requestedDetails = setOf(ViewHierarchyDetail.OFFSCREEN_ELEMENTS),
    )

    val text = screenState.viewHierarchyTextRepresentation!!

    // Cross-reference every element against Playwright's locator resolution
    for ((id, elementRef) in screenState.elementIdMapping) {
      val locator = PlaywrightAriaSnapshot.resolveElementRef(page, elementRef)
      if (locator.count() == 0) continue
      val box = locator.boundingBox() ?: continue
      val playwrightInViewport = box.y + box.height > 0 && box.y < 800 &&
        box.x + box.width > 0 && box.x < 1280
      val elementLine = text.lines().find { it.contains("[$id]") } ?: continue
      val batchSaysOffscreen = elementLine.contains("(offscreen)")
      if (playwrightInViewport) {
        assertFalse(
          batchSaysOffscreen,
          "Element $id (${elementRef.descriptor} nth=${elementRef.nthIndex}) " +
            "in viewport per Playwright but batch JS says offscreen: $elementLine",
        )
      } else {
        assertTrue(
          batchSaysOffscreen,
          "Element $id (${elementRef.descriptor} nth=${elementRef.nthIndex}) " +
            "offscreen per Playwright but batch JS missed it: $elementLine",
        )
      }
    }
  }

  // ---- Occlusion tests ----

  /**
   * HTML page with two interactive buttons and a modal overlay covering one of them.
   * The overlay is a fixed `<div role="dialog">` positioned to cover the area where
   * "Covered Button" renders. The button is still in the DOM, still rendered, but
   * not the topmost element at its center point — so `elementFromPoint` returns the
   * overlay and our occlusion check should flag the button.
   */
  private val occludedPageHtml = """
    <!DOCTYPE html>
    <html>
    <body style="margin:0; padding:0;">
      <main>
        <button id="visible-button" style="position: absolute; left: 50px; top: 50px; width: 160px; height: 40px;">Visible Button</button>
        <button id="covered-button" style="position: absolute; left: 50px; top: 200px; width: 160px; height: 40px;">Covered Button</button>
        <div role="dialog" aria-label="Modal Overlay"
             style="position: fixed; left: 30px; top: 180px; width: 400px; height: 200px; background: rgba(0,0,0,0.8); z-index: 999;">
          <button id="modal-button">Modal Button</button>
        </div>
      </main>
    </body>
    </html>
  """.trimIndent()

  @Test
  fun `occluded elements are filtered from text view by default`() {
    page.setContent(occludedPageHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    // The covered button is hidden behind the modal overlay — drop it from text.
    assertFalse(
      text.contains("\"Covered Button\""),
      "'Covered Button' should be filtered out as occluded, but got:\n$text",
    )
    // The visible button and the modal's own button are not occluded — they stay.
    assertTrue(text.contains("\"Visible Button\""), "'Visible Button' should be present in:\n$text")
    assertTrue(text.contains("\"Modal Button\""), "'Modal Button' (in the dialog) should be present in:\n$text")
    // Summary line tells the LLM how to surface them if it wants to.
    assertTrue(
      text.contains("occluded elements hidden"),
      "Summary line should mention occluded elements hidden, but got:\n$text",
    )
    assertTrue(
      text.contains("request OCCLUDED_ELEMENTS"),
      "Summary should mention OCCLUDED_ELEMENTS detail flag, but got:\n$text",
    )
  }

  @Test
  fun `occluded elements appear annotated when OCCLUDED_ELEMENTS detail requested`() {
    page.setContent(occludedPageHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
      requestedDetails = setOf(ViewHierarchyDetail.OCCLUDED_ELEMENTS),
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    val coveredButtonLine = text.lines().find { it.contains("\"Covered Button\"") }
    assertNotNull(coveredButtonLine, "Should find 'Covered Button' when OCCLUDED_ELEMENTS requested:\n$text")
    assertTrue(
      coveredButtonLine.contains("(occluded)"),
      "'Covered Button' should be annotated (occluded): $coveredButtonLine",
    )

    // Visible elements should NOT be annotated.
    val visibleButtonLine = text.lines().find { it.contains("\"Visible Button\"") }
    assertNotNull(visibleButtonLine, "Should find 'Visible Button' in:\n$text")
    assertFalse(
      visibleButtonLine.contains("(occluded)"),
      "'Visible Button' should not be occluded: $visibleButtonLine",
    )

    // No summary line for occluded since we're including them.
    assertFalse(
      text.contains("occluded elements hidden"),
      "Should not have occluded summary when OCCLUDED_ELEMENTS requested, but got:\n$text",
    )
  }

  /**
   * SoM-side mirror of the text-side filter: image annotations for occluded
   * elements must also be dropped so the screenshot overlay matches what the
   * text view shows. Otherwise the LLM sees `[e60]` painted on the popup that's
   * covering `e60`, which is exactly the kind of stale annotation the PR is
   * trying to eliminate.
   */
  @Test
  fun `annotationElements skips occluded refs to keep image and text views consistent`() {
    page.setContent(occludedPageHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val annotations = screenState.annotationElements
    assertNotNull(annotations, "Should produce annotations for the page")
    // No annotation should carry the refLabel for the covered button — the
    // covered button's `[eN]` ID won't even appear in elementIdMapping after
    // filtering, but the strict check is: among emitted annotations none should
    // resolve back to the covered button's bounds.
    val coveredButtonEntry = screenState.elementIdMapping.entries
      .find { it.value.descriptor.contains("Covered Button") }
    if (coveredButtonEntry != null) {
      assertFalse(
        annotations.any { it.refLabel == coveredButtonEntry.key },
        "Covered button (${coveredButtonEntry.key}) should NOT be in painted annotations",
      )
    }
    // Visible button SHOULD have an annotation.
    val visibleButtonEntry = screenState.elementIdMapping.entries
      .find { it.value.descriptor.contains("Visible Button") }
    assertNotNull(visibleButtonEntry, "Visible Button should be in elementIdMapping")
    assertTrue(
      annotations.any { it.refLabel == visibleButtonEntry.key },
      "Visible button (${visibleButtonEntry.key}) SHOULD be in painted annotations",
    )
  }

  /**
   * Real-world reproduction of the Square Dashboard Managerbot popup case:
   * a popup container with `pointer-events: none` sits visually on top of
   * page elements, while the page remains interactive underneath because
   * clicks pass through. Set-of-mark uses VISUAL occlusion (paint stack
   * via `elementsFromPoint`), not click occlusion (`elementFromPoint`),
   * so this case correctly flags the underlying button — the LLM looking
   * at the screenshot cannot see the button regardless of whether
   * Playwright could technically click it through the popup.
   *
   * Why we accept this trade-off: drawing a labeled box on the screenshot
   * at a position the LLM can't see is misleading. Listing the underlying
   * button in the text view while the screenshot shows it covered is also
   * misleading. The LLM can always request OCCLUDED_ELEMENTS detail to
   * surface what's behind the overlay if needed.
   */
  @Test
  fun `pointer-events none overlay does not occlude clickable underlying element`() {
    // A transparent or click-through wrapper that has `pointer-events: none`
    // does NOT intercept clicks — `elementsFromPoint` skips it, so the
    // underlying button is the actual hit-target. We match Playwright's
    // actionability decision verbatim: if the click would land on the button,
    // the button is visible to the LLM.
    //
    // (Earlier versions of this snapshot supplier injected
    // `* { pointer-events: auto !important }` before the hit-test to treat
    // visually-painted-but-click-through wrappers as occluders. That
    // divergence caused systematic false positives for every transparent
    // full-viewport wrapper — focus traps, drawer roots, route transitions,
    // ambient layers — and was removed in favor of fully matching Playwright.
    // See PR #2917 review thread.)
    page.setContent(
      """
      <!DOCTYPE html>
      <html>
      <body style="margin:0; padding:0;">
        <button id="real-button" style="position: absolute; left: 50px; top: 50px; width: 160px; height: 40px;">Real Button</button>
        <div aria-hidden="true"
             style="position: absolute; left: 30px; top: 30px; width: 200px; height: 80px;
                    background: #222; pointer-events: none;"></div>
      </body>
      </html>
      """.trimIndent(),
    )

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    assertTrue(
      text.contains("\"Real Button\""),
      "Button under a pointer-events:none wrapper must stay visible — the click would reach it. Got:\n$text",
    )
    assertFalse(
      text.lines().any { it.contains("\"Real Button\"") && it.contains("(occluded)") },
      "Button must not be annotated occluded. Got:\n$text",
    )
  }

  /**
   * Negative case: `elementFromPoint` returning a DESCENDANT of our element
   * is the visible case, not the occluded case. Buttons routinely contain
   * inner `<span>` / icon nodes, and the hit-test will return whichever inner
   * node sits at the click point.
   *
   * `el.contains(top)` returns true when `top === el` OR `top` is any descendant,
   * which is exactly the "visible" semantics we want.
   */
  @Test
  fun `descendant of target returned by elementFromPoint counts as visible`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html>
      <body style="margin:0; padding:0;">
        <button aria-label="Iconic Button"
                style="position: absolute; left: 50px; top: 50px; width: 100px; height: 100px;
                       display: flex; align-items: center; justify-content: center;">
          <span class="icon" style="display: inline-block; width: 40px; height: 40px; background: #333;"></span>
        </button>
      </body>
      </html>
      """.trimIndent(),
    )

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    assertTrue(
      text.contains("\"Iconic Button\""),
      "Button containing inner span/icon should remain visible (descendant returned by elementFromPoint):\n$text",
    )
    assertFalse(
      text.contains("(occluded)"),
      "Should NOT flag a button as occluded by its own child:\n$text",
    )
  }

  /**
   * Negative case: partial coverage where the element's CENTER is still in
   * the clear. Element's bottom edge sits under an overlay, but its center
   * point hits the element itself. Should remain visible.
   *
   * This is the most common partial-coverage scenario — e.g., a sticky footer
   * bar sitting at the bottom of the page covers the lower portion of every
   * page element below the fold, but the page element's center is usually
   * still in the open area.
   */
  @Test
  fun `element with partially-covered bottom-edge but visible center is not flagged`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html>
      <body style="margin:0; padding:0;">
        <!-- Tall button, center at (130, 200) -->
        <button id="tall-button" style="position: absolute; left: 50px; top: 100px; width: 160px; height: 200px;">Tall Button</button>
        <!-- Sticky footer-style overlay across the bottom of the button, covering only
             the lower portion — the button's center is in the clear. -->
        <div role="status" aria-label="Sticky Notice"
             style="position: fixed; left: 0; top: 280px; width: 100%; height: 40px;
                    background: rgba(0,0,0,0.7); z-index: 100;"></div>
      </body>
      </html>
      """.trimIndent(),
    )

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    val tallButtonLine = text.lines().find { it.contains("\"Tall Button\"") }
    assertNotNull(tallButtonLine, "'Tall Button' should be present in:\n$text")
    assertFalse(
      tallButtonLine.contains("(occluded)"),
      "Partial bottom-edge cover should NOT flag the button as occluded: $tallButtonLine",
    )
  }

  /**
   * Element fully covered by a stacked-z overlay even though the overlay is
   * an EARLIER sibling in document order. Document order alone isn't enough
   * to determine paint order; z-index decides. The browser's elementFromPoint
   * respects z-index, so we get the right answer for free.
   */
  @Test
  fun `element covered by earlier-sibling overlay with higher z-index is flagged occluded`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html>
      <body style="margin:0; padding:0;">
        <!-- Overlay appears FIRST in document order, but z-index puts it on top. -->
        <div role="dialog" aria-label="Z-Stacked Overlay"
             style="position: absolute; left: 30px; top: 30px; width: 300px; height: 200px;
                    background: rgba(0,0,255,0.9); z-index: 50;"></div>
        <button id="under-overlay" style="position: absolute; left: 80px; top: 80px; width: 120px; height: 40px;">Stacked-Covered Button</button>
      </body>
      </html>
      """.trimIndent(),
    )

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    assertFalse(
      text.contains("\"Stacked-Covered Button\""),
      "Button covered by earlier-sibling overlay with higher z-index should be filtered: $text",
    )
  }

  /**
   * Joint case: an element is BOTH offscreen AND occluded. With both detail
   * flags requested, only one annotation kind appears — the implementation
   * marks offscreen first and bails on the occlusion check (occlusion is
   * meaningless for elements outside the viewport).
   */
  @Test
  fun `offscreen elements bypass the occlusion check entirely`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html>
      <body style="margin:0; padding:0;">
        <button id="visible-btn" style="position: absolute; left: 50px; top: 50px; width: 120px; height: 40px;">Visible Above</button>
        <div role="dialog" aria-label="Big Overlay"
             style="position: fixed; left: 0; top: 0; width: 100%; height: 1500px;
                    background: rgba(0,0,0,0.5); z-index: 999;"></div>
        <!-- Way below the 800px viewport, behind a tall overlay -->
        <button id="offscreen-btn" style="position: absolute; left: 50px; top: 1200px; width: 120px; height: 40px;">Far Below</button>
      </body>
      </html>
      """.trimIndent(),
    )

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
      requestedDetails = setOf(
        ViewHierarchyDetail.OFFSCREEN_ELEMENTS,
        ViewHierarchyDetail.OCCLUDED_ELEMENTS,
      ),
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    val farBelowLine = text.lines().find { it.contains("\"Far Below\"") }
    assertNotNull(farBelowLine, "Should find 'Far Below' element with both flags set:\n$text")
    // Should be annotated offscreen, NOT occluded — the offscreen path is checked
    // first and we skip the occlusion check for offscreen elements (it's meaningless
    // outside the viewport).
    assertTrue(
      farBelowLine.contains("(offscreen)"),
      "Far Below should be annotated offscreen: $farBelowLine",
    )
    assertFalse(
      farBelowLine.contains("(occluded)"),
      "Far Below should NOT be annotated occluded (it's offscreen — the occlusion check is skipped): $farBelowLine",
    )
  }

  // ---- CSS-pattern coverage for the supplier's opacity predicate ----
  //
  // Real-world UI library popup cards rarely use a plain `background-color: <color>`;
  // CSS-in-JS frameworks (Emotion / styled-components / Material UI / etc.) commonly
  // build the visible card via `::before` pseudo-elements, `box-shadow`, or
  // embedded `<iframe>`s. These three patterns defeated an earlier version of the
  // predicate and produced the false-visible regression on real production
  // dashboard popups. Pin each pattern so a future predicate tweak can't silently
  // reintroduce the leak.

  @Test
  fun `popup using only box-shadow inset fill occludes underlying button`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html>
      <body style="margin:0; padding:0;">
        <button id="nav" style="position:absolute;left:50px;top:200px;width:160px;height:40px;background:white;">Nav</button>
        <div aria-label="ShadowCard" style="position:absolute;left:30px;top:180px;width:400px;height:200px;box-shadow:inset 0 0 0 200px #222;"></div>
      </body>
      </html>
      """.trimIndent(),
    )

    val text = PlaywrightScreenState(page, 1280, 800).viewHierarchyTextRepresentation!!
    assertFalse(
      text.contains("\"Nav\""),
      "Box-shadow-only card should occlude the underlying nav button, but Nav is still listed:\n$text",
    )
    assertTrue(text.contains("occluded elements hidden"), "Expected occluded summary:\n$text")
  }

  @Test
  fun `popup using only pseudo-element background occludes underlying button`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html>
      <head>
        <style>
          .pseudo-card { position:relative; }
          .pseudo-card::before {
            content:''; position:absolute; inset:0;
            background:#222; border-radius:12px;
          }
        </style>
      </head>
      <body style="margin:0; padding:0;">
        <button id="nav" style="position:absolute;left:50px;top:200px;width:160px;height:40px;background:white;">Nav</button>
        <div class="pseudo-card" aria-label="PseudoCard" style="position:absolute;left:30px;top:180px;width:400px;height:200px;"></div>
      </body>
      </html>
      """.trimIndent(),
    )

    val text = PlaywrightScreenState(page, 1280, 800).viewHierarchyTextRepresentation!!
    assertFalse(
      text.contains("\"Nav\""),
      "Pseudo-element-bg card should occlude the underlying nav button, but Nav is still listed:\n$text",
    )
    assertTrue(text.contains("occluded elements hidden"), "Expected occluded summary:\n$text")
  }

  @Test
  fun `shadow-DOM dialog does not falsely occlude its own shadow-DOM children`() {
    // Real-world failure mode: when a Web Component dialog (e.g. `<market-dialog>`)
    // hosts an interactive button (e.g. Save) inside its shadow root, the
    // button's `parentElement` chain stops at the shadow root boundary. If the
    // ancestor walk doesn't cross via the shadow host, the button doesn't
    // "know" the dialog host is its ancestor — and when the dialog host's
    // bounds are the topmost opaque element at the button's center, the
    // algorithm misclassifies it as occluded by an unrelated container.
    // Reproduces the Square Dashboard Create-Item-dialog regression where
    // Save was filtered out and the LLM concluded it couldn't be clicked.
    page.setContent(
      """
      <!DOCTYPE html>
      <html>
      <body style="margin:0; padding:0;">
        <my-dialog id="dialog-host" style="display:block;position:absolute;left:100px;top:100px;width:400px;height:300px;border:1px solid #ccc;background:white;"></my-dialog>
        <script>
          customElements.define('my-dialog', class extends HTMLElement {
            connectedCallback() {
              const sr = this.attachShadow({ mode: 'open' });
              sr.innerHTML =
                '<div style="position:absolute;inset:0;background:white;">' +
                  '<h2>Dialog title</h2>' +
                  '<button aria-label="Save" style="position:absolute;left:280px;top:240px;width:100px;height:40px;background:#0066ff;color:white;">Save</button>' +
                '</div>';
            }
          });
        </script>
      </body>
      </html>
      """.trimIndent(),
    )

    val text = PlaywrightScreenState(page, 1280, 800).viewHierarchyTextRepresentation!!
    assertTrue(
      text.contains("\"Save\""),
      "Save button inside the dialog's shadow root must stay VISIBLE — without the shadow-host ancestor crossover, the algorithm falsely flags it as occluded by the dialog host. Got:\n$text",
    )
    assertFalse(
      text.lines().any { it.contains("\"Save\"") && it.contains("(occluded)") },
      "Save button must not be annotated occluded. Got:\n$text",
    )
  }

  @Test
  fun `shadow-DOM web-component popup occludes underlying shadow-DOM web-component button`() {
    // Reproduces the real-world failure mode found on production dashboards
    // built with Web Components (Market design system, Shoelace, etc.):
    // both the candidate AND the popup card live inside shadow roots, so
    // `document.querySelectorAll('*')` returns the shadow HOSTS but not the
    // shadow children — and the role/name map built from the light DOM
    // misses 80%+ of the ARIA-tree candidates that Playwright's
    // `ariaSnapshot()` sees. Without the shadow-DOM walk in roleNameMap,
    // the buried candidates never get bbox lookups and silently leak to the LLM.
    page.setContent(
      """
      <!DOCTYPE html>
      <html>
      <body style="margin:0; padding:0;">
        <my-nav id="nav-host"></my-nav>
        <my-popup id="popup-host"></my-popup>
        <script>
          customElements.define('my-nav', class extends HTMLElement {
            connectedCallback() {
              const sr = this.attachShadow({ mode: 'open' });
              sr.innerHTML = '<button aria-label="Nav Inside Shadow" style="position:absolute;left:50px;top:200px;width:160px;height:40px;background:white;">Nav</button>';
            }
          });
          customElements.define('my-popup', class extends HTMLElement {
            connectedCallback() {
              const sr = this.attachShadow({ mode: 'open' });
              sr.innerHTML = '<div aria-label="Popup Inside Shadow" style="position:absolute;left:30px;top:180px;width:400px;height:200px;background:#222;">Popup</div>';
            }
          });
        </script>
      </body>
      </html>
      """.trimIndent(),
    )

    val text = PlaywrightScreenState(page, 1280, 800).viewHierarchyTextRepresentation!!
    assertFalse(
      text.contains("\"Nav Inside Shadow\""),
      "Shadow-DOM nav button under a shadow-DOM popup should be filtered as occluded; without the shadow-root walk, the role/name lookup misses both elements and the LLM sees the buried button. Got:\n$text",
    )
    assertTrue(text.contains("occluded elements hidden"), "Expected occluded summary:\n$text")
  }

  @Test
  fun `slotted light-DOM button inside shadow-DOM dialog stays visible via assignedSlot traversal`() {
    // Hermetic version of Shoelace's actual pattern: a `<sl-dialog>`-style
    // host with an open shadow root that contains a `<slot>`, plus a
    // light-DOM `<button>` slotted into the host's default slot. Visually
    // the button paints inside the dialog card. The Playwright port walks
    // up via `hitElement.assignedSlot ?? parentElementOrShadowHost(hitElement)`;
    // without `assignedSlot`, the walk goes button → light-DOM body and
    // never reaches the slot inside the shadow root, so the slotted button
    // gets falsely flagged as occluded by the dialog card. Pins the
    // `assignedSlot` branch of the upstream algorithm.
    page.setContent(
      """
      <!DOCTYPE html>
      <html>
      <body style="margin:0; padding:0;">
        <my-dialog id="host">
          <button id="slotted-btn" aria-label="Slotted OK">Slotted OK</button>
        </my-dialog>
        <script>
          customElements.define('my-dialog', class extends HTMLElement {
            connectedCallback() {
              const sr = this.attachShadow({ mode: 'open' });
              sr.innerHTML =
                '<div style="position:absolute;left:100px;top:100px;width:400px;height:300px;background:white;border:1px solid #ccc;">' +
                  '<h2>Dialog</h2>' +
                  '<slot></slot>' +
                '</div>';
            }
          });
          // Position the slotted button so it sits inside the dialog card.
          document.getElementById('slotted-btn').style.cssText =
            'position:absolute;left:250px;top:300px;width:140px;height:40px;background:#0066ff;color:white;';
        </script>
      </body>
      </html>
      """.trimIndent(),
    )

    val text = PlaywrightScreenState(page, 1280, 800).viewHierarchyTextRepresentation!!
    assertTrue(
      text.contains("\"Slotted OK\""),
      "Slotted light-DOM button painted inside the shadow dialog must stay visible — without assignedSlot traversal it would be falsely flagged occluded by the dialog card. Got:\n$text",
    )
    assertFalse(
      text.lines().any { it.contains("\"Slotted OK\"") && it.contains("(occluded)") },
      "Slotted button must not be annotated occluded. Got:\n$text",
    )
  }

  @Test
  fun `display contents wrapper does not break the hit-target walk`() {
    // The Playwright port's `display: contents` fix-up unshifts the
    // `elementFromPoint` result onto the `elementsFromPoint` stack when the
    // single element is a `display: contents` ancestor of the topmost element.
    // Without it, a `display: contents` wrapper between the hit target and
    // its visible descendants would let `elementsFromPoint` miss the
    // descendant entirely on some engines. Pins the unshift branch.
    page.setContent(
      """
      <!DOCTYPE html>
      <html>
      <body style="margin:0; padding:0;">
        <div style="display:contents;">
          <button id="contents-btn" aria-label="Contents OK"
                  style="position:absolute;left:50px;top:50px;width:160px;height:40px;background:white;">Contents OK</button>
        </div>
      </body>
      </html>
      """.trimIndent(),
    )

    val text = PlaywrightScreenState(page, 1280, 800).viewHierarchyTextRepresentation!!
    assertTrue(
      text.contains("\"Contents OK\""),
      "Button inside a display:contents wrapper must remain visible — the upstream unshift fix-up keeps it in the hit-target walk. Got:\n$text",
    )
    assertFalse(
      text.lines().any { it.contains("\"Contents OK\"") && it.contains("(occluded)") },
      "Button must not be annotated occluded. Got:\n$text",
    )
  }

  @Test
  fun `iframe popup occludes underlying button`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html>
      <body style="margin:0; padding:0;">
        <button id="nav" style="position:absolute;left:50px;top:200px;width:160px;height:40px;background:white;">Nav</button>
        <iframe aria-label="IframePopup" srcdoc="<body style='background:#222;color:white;margin:0;'>Popup content</body>"
                style="position:absolute;left:30px;top:180px;width:400px;height:200px;border:none;"></iframe>
      </body>
      </html>
      """.trimIndent(),
    )

    val text = PlaywrightScreenState(page, 1280, 800).viewHierarchyTextRepresentation!!
    assertFalse(
      text.contains("\"Nav\""),
      "Iframe popup should occlude the underlying nav button, but Nav is still listed:\n$text",
    )
    assertTrue(text.contains("occluded elements hidden"), "Expected occluded summary:\n$text")
  }

  /**
   * Same-page combination: one element offscreen and one element occluded.
   * By default both are filtered with their respective summary lines.
   */
  @Test
  fun `default filtering produces separate summary lines for offscreen and occluded`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html>
      <body style="margin:0; padding:0;">
        <button id="visible-btn" style="position: absolute; left: 50px; top: 50px; width: 120px; height: 40px;">Always Visible</button>
        <button id="covered-btn" style="position: absolute; left: 50px; top: 200px; width: 120px; height: 40px;">In-Viewport Covered</button>
        <div role="dialog" aria-label="Mid Overlay"
             style="position: fixed; left: 30px; top: 180px; width: 300px; height: 100px;
                    background: rgba(0,0,0,0.8); z-index: 999;"></div>
        <div style="height: 2000px;"></div>
        <button id="scroll-btn" style="position: absolute; left: 50px; top: 1200px; width: 120px; height: 40px;">Below Fold</button>
      </body>
      </html>
      """.trimIndent(),
    )

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    // Both kinds of hidden elements get their own summary line so the LLM sees
    // exactly which detail flag to request for which kind.
    assertTrue(text.contains("offscreen elements hidden"), "Should have offscreen summary:\n$text")
    assertTrue(text.contains("occluded elements hidden"), "Should have occluded summary:\n$text")
    assertTrue(text.contains("request OFFSCREEN_ELEMENTS"), "Should mention OFFSCREEN_ELEMENTS:\n$text")
    assertTrue(text.contains("request OCCLUDED_ELEMENTS"), "Should mention OCCLUDED_ELEMENTS:\n$text")
    // Visible-button stays.
    assertTrue(text.contains("\"Always Visible\""), "Visible button should remain:\n$text")
    // Both filtered elements are gone.
    assertFalse(text.contains("\"In-Viewport Covered\""), "Covered button should be filtered:\n$text")
    assertFalse(text.contains("\"Below Fold\""), "Offscreen button should be filtered:\n$text")
  }

  /**
   * Regression guard for the batched-bounds path: bounds emitted by
   * [BATCH_VIEWPORT_CHECK_JS] must match what [Locator.boundingBox] returns
   * (within rounding). The old [annotationElements] path did N CDP
   * [boundingBox] calls; the new path reads bounds out of the batched JS
   * payload. If the batched bounds drift from the locator-based bounds, the
   * set-of-mark overlay would draw boxes at the wrong positions.
   */
  @Test
  fun `annotation bounds from batched JS match locator-based boundingBox`() {
    page.setContent(testHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val annotations = screenState.annotationElements
    assertNotNull(annotations, "Should produce annotations")
    assertTrue(annotations.isNotEmpty(), "Should produce at least one annotation")

    for (annotation in annotations) {
      val refLabel = annotation.refLabel ?: continue
      val elementRef = screenState.elementIdMapping[refLabel] ?: continue
      val locator = PlaywrightAriaSnapshot.resolveElementRef(page, elementRef)
      if (locator.count() == 0) continue
      val box = locator.boundingBox() ?: continue
      // Allow ±2px rounding tolerance (Math.round vs. raw float)
      val tolerance = 2
      assertTrue(
        kotlin.math.abs(annotation.bounds.left - box.x.toInt()) <= tolerance,
        "$refLabel left mismatch: batched=${annotation.bounds.left} locator=${box.x.toInt()}",
      )
      assertTrue(
        kotlin.math.abs(annotation.bounds.top - box.y.toInt()) <= tolerance,
        "$refLabel top mismatch: batched=${annotation.bounds.top} locator=${box.y.toInt()}",
      )
      assertTrue(
        kotlin.math.abs((annotation.bounds.right - annotation.bounds.left) - box.width.toInt()) <= tolerance,
        "$refLabel width mismatch: batched=${annotation.bounds.right - annotation.bounds.left} locator=${box.width.toInt()}",
      )
      assertTrue(
        kotlin.math.abs((annotation.bounds.bottom - annotation.bounds.top) - box.height.toInt()) <= tolerance,
        "$refLabel height mismatch: batched=${annotation.bounds.bottom - annotation.bounds.top} locator=${box.height.toInt()}",
      )
    }
  }

  /**
   * Regression guard for https://github.com/block/trailblaze/issues/199: the `viewHierarchy`
   * tree is now enriched with bounds via a single batched `page.evaluate` instead of a per-node
   * Playwright locator round-trip. The batched bounds must still line up with
   * what `Locator.boundingBox` reports (within rounding) for the elements the
   * batch resolves — if the tree-traversal (role, name, nth) numbering drifted
   * from the batch resolver's, nodes would be tagged with the wrong element's
   * bounds.
   */
  @Test
  fun `viewHierarchy tree bounds match locator-based boundingBox`() {
    page.setContent(complexPageHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    // Flatten the enriched tree.
    fun flatten(node: xyz.block.trailblaze.api.ViewHierarchyTreeNode): List<xyz.block.trailblaze.api.ViewHierarchyTreeNode> =
      listOf(node) + node.children.flatMap { flatten(it) }
    val nodes = flatten(screenState.viewHierarchy)

    // At least some named, interactive nodes should have been enriched with bounds.
    val enriched = nodes.filter { it.x2 > it.x1 && it.y2 > it.y1 }
    assertTrue(enriched.isNotEmpty(), "Expected at least one node enriched with bounds")

    // Cross-check a representative in-viewport control against Playwright's own locator.
    val saveNode = nodes.find { it.className == "button" && it.text == "Save Changes" }
    assertNotNull(saveNode, "Should find the 'Save Changes' button node in the tree")
    assertTrue(saveNode.x2 > saveNode.x1 && saveNode.y2 > saveNode.y1, "Save button should have bounds")

    val locator = page.getByRole(
      com.microsoft.playwright.options.AriaRole.BUTTON,
      Page.GetByRoleOptions().setName("Save Changes").setExact(true),
    )
    val box = locator.boundingBox()
    assertNotNull(box, "Locator should resolve a bounding box for the Save button")
    val tolerance = 2
    assertTrue(
      kotlin.math.abs(saveNode.x1 - box.x.toInt()) <= tolerance,
      "Save button left mismatch: tree=${saveNode.x1} locator=${box.x.toInt()}",
    )
    assertTrue(
      kotlin.math.abs(saveNode.y1 - box.y.toInt()) <= tolerance,
      "Save button top mismatch: tree=${saveNode.y1} locator=${box.y.toInt()}",
    )
    assertTrue(
      kotlin.math.abs((saveNode.x2 - saveNode.x1) - box.width.toInt()) <= tolerance,
      "Save button width mismatch: tree=${saveNode.x2 - saveNode.x1} locator=${box.width.toInt()}",
    )
  }

  /**
   * Empty page (no interactive content): visibility computation must not
   * crash and must produce no annotations. Regression guard for the
   * `compact.elementIdMapping.isEmpty()` early-out.
   */
  @Test
  fun `empty page produces no annotations and does not crash visibility check`() {
    page.setContent("<!DOCTYPE html><html><body></body></html>")

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    // Text view shouldn't crash and shouldn't contain spurious summary lines.
    val text = screenState.viewHierarchyTextRepresentation!!
    assertFalse(text.contains("occluded elements hidden"))
    assertFalse(text.contains("offscreen elements hidden"))

    // No annotations to paint.
    val annotations = screenState.annotationElements
    assertTrue(
      annotations == null || annotations.isEmpty(),
      "Empty page should produce zero annotations, got $annotations",
    )
  }

  /**
   * Compose-Web-Wasm fail-open: on canvas-rendered pages, `elementFromPoint`
   * returns the canvas for every interior point. Naive occlusion-by-hit-test
   * would flag every accessibility-overlay element as occluded and we'd produce
   * zero annotations.
   *
   * The per-element fail-open in BATCH_VIEWPORT_CHECK_JS catches this: when
   * `elementFromPoint` returns CANVAS/HTML/BODY for an element's center, the
   * hit-test isn't a trustworthy signal for that element and we leave it
   * unflagged rather than falsely mark it occluded. Bounds + offscreen still
   * work since they don't depend on hit-testing.
   */
  @Test
  fun `canvas-rendered page does not falsely flag accessibility elements as occluded`() {
    // A canvas absolutely-positioned at top:0, left:0, full viewport — so
    // elementFromPoint(vw/2, vh/2) hits the canvas. The two role=button divs
    // sit at corners where the canvas is technically still on top but the buttons
    // are also at the same position. The point of this fixture is the sentinel:
    // because the viewport center hits a CANVAS tag, occlusionReliable=false and
    // we skip occlusion filtering entirely.
    page.setContent(
      """
      <!DOCTYPE html>
      <html>
      <body style="margin:0; padding:0;">
        <canvas width="1280" height="800" style="position: absolute; top: 0; left: 0; width: 1280px; height: 800px;"></canvas>
        <div role="button" aria-label="Wasm Button A"
             style="position: absolute; top: 100px; left: 100px; width: 80px; height: 40px;"></div>
        <div role="button" aria-label="Wasm Button B"
             style="position: absolute; top: 200px; left: 100px; width: 80px; height: 40px;"></div>
      </body>
      </html>
      """.trimIndent(),
    )

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    // Both wasm-style buttons should survive — the sentinel-canvas fail-open
    // drops the (unreliable) occlusion verdict and keeps them in the list.
    assertTrue(
      text.contains("\"Wasm Button A\""),
      "Canvas-page button A should NOT be filtered (occlusion-check unreliable on canvas pages):\n$text",
    )
    assertTrue(
      text.contains("\"Wasm Button B\""),
      "Canvas-page button B should NOT be filtered (occlusion-check unreliable on canvas pages):\n$text",
    )
    assertFalse(
      text.contains("occluded elements hidden"),
      "Should not have any occluded summary on a canvas-rendered page, but got:\n$text",
    )
  }

  /**
   * Regression guard for the `clampHitPoint` viewport-intersection logic added
   * in response to Codex P2 / Copilot review of PR #2917. A tall element
   * (height ≥ 1.5× viewport) anchored at top:100 has its geometric center far
   * below the viewport. Without clamping, `elementsFromPoint(cx, cy)` is
   * called below the viewport and returns nothing, so the element is falsely
   * marked occluded. With clamping, the hit-test point is taken from the
   * intersection of the element rect and the viewport, which correctly lands
   * on the visible top sliver of the element.
   */
  @Test
  fun `tall element with geometric center below viewport is not falsely flagged occluded`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html>
      <body style="margin:0; padding:0;">
        <!-- 2000px-tall section, top:100, so geometric center at y=1100 — well below the 800px viewport.
             Top sliver (y=100..800) is visible; the rest is below the fold. -->
        <section aria-label="Very Tall Section"
                 style="position:absolute;left:50px;top:100px;width:300px;height:2000px;background:white;border:1px solid black;">
        </section>
      </body>
      </html>
      """.trimIndent(),
    )

    val text = PlaywrightScreenState(page, 1280, 800).viewHierarchyTextRepresentation!!
    val sectionLine = text.lines().find { it.contains("\"Very Tall Section\"") }
    assertNotNull(
      sectionLine,
      "Tall section's visible top sliver should keep it in the snapshot — without viewport-clamped hit point it would be falsely occluded. Got:\n$text",
    )
    assertFalse(
      sectionLine.contains("(occluded)"),
      "Tall section must not be annotated occluded — its top sliver is visible:\n$sectionLine",
    )
  }

  /**
   * Regression guard for the per-candidate try/catch + `skipped` list. If
   * one candidate's DOM lookup throws (detached element, overridden getter,
   * etc.) the rest of the batch must still classify correctly — without the
   * inner try/catch a single throw would unwind through the outer
   * try/finally and zero out visibility for every candidate, so a button
   * that should have been filtered as offscreen would survive.
   */
  @Test
  fun `single throwing candidate does not unfilter the rest of the batch`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html>
      <body style="margin:0; padding:0;">
        <button id="visible-btn" aria-label="Stays Visible"
                style="position:absolute;left:50px;top:50px;width:160px;height:40px;">Stays Visible</button>
        <button id="throw-btn" aria-label="Throws During Bounds"
                style="position:absolute;left:50px;top:120px;width:160px;height:40px;">Throws During Bounds</button>
        <!-- Anchored well below the 800px viewport, so it should be filtered as offscreen
             ONLY IF the rest of the batch is still processed after throw-btn throws. -->
        <button id="offscreen-btn" aria-label="Way Below Fold"
                style="position:absolute;left:50px;top:1600px;width:160px;height:40px;">Way Below Fold</button>
        <script>
          // Surgically override getBoundingClientRect on this one element with an own-property
          // that shadows the prototype method. Only throw-btn throws; the rest of the DOM is intact.
          const t = document.getElementById('throw-btn');
          Object.defineProperty(t, 'getBoundingClientRect', {
            value: function() { throw new Error('synthetic-throw for skipped-list test'); },
            configurable: true,
          });
        </script>
      </body>
      </html>
      """.trimIndent(),
    )

    val text = PlaywrightScreenState(page, 1280, 800).viewHierarchyTextRepresentation!!
    assertTrue(
      text.contains("\"Stays Visible\""),
      "Visible button must remain — single-candidate throw must not unfilter the rest of the batch:\n$text",
    )
    assertFalse(
      text.contains("\"Way Below Fold\""),
      "Offscreen button must still be filtered — pre-fix, a throwing sibling would have unwound the loop and left every candidate unfiltered:\n$text",
    )
    assertTrue(
      text.contains("offscreen elements hidden"),
      "Offscreen summary must still appear — proves the offscreen classifier ran for siblings of the throwing candidate:\n$text",
    )
  }
}
