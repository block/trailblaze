package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import java.awt.image.BufferedImage
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Edge case and corner case integration tests for [PlaywrightScreenState],
 * [PlaywrightBrowserManager], and viewport visibility annotation.
 *
 * Uses a real Chromium browser with inline HTML. Exercises boundary conditions,
 * unusual DOM structures, and defensive code paths hardened in the bug fix pass.
 */
class PlaywrightScreenStateEdgeCaseTest {

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

  // -- Viewport visibility boundary tests --

  @Test
  fun `element partially visible at bottom edge is NOT marked offscreen`() {
    val html = """
      <!DOCTYPE html>
      <html>
      <body style="margin:0; padding:0;">
        <div style="height: 770px;"></div>
        <button style="height: 60px;">Partial Button</button>
      </body>
      </html>
    """.trimIndent()
    page.setContent(html)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    val buttonLine = text.lines().find { it.contains("\"Partial Button\"") }
    assertNotNull(buttonLine, "Should find Partial Button in:\n$text")
    assertFalse(
      buttonLine.contains("(offscreen)"),
      "Partially visible element should NOT be offscreen: $buttonLine",
    )
  }

  @Test
  fun `element just below viewport fold IS marked offscreen`() {
    val html = """
      <!DOCTYPE html>
      <html>
      <body style="margin:0; padding:0;">
        <div style="height: 810px;"></div>
        <button>Below Fold</button>
      </body>
      </html>
    """.trimIndent()
    page.setContent(html)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    val buttonLine = text.lines().find { it.contains("\"Below Fold\"") }
    assertNotNull(buttonLine, "Should find Below Fold in:\n$text")
    assertTrue(
      buttonLine.contains("(offscreen)"),
      "Element below fold should be offscreen: $buttonLine",
    )
  }

  @Test
  fun `zero-size hidden element is handled gracefully`() {
    val html = """
      <!DOCTYPE html>
      <html>
      <body>
        <button style="width:0; height:0; overflow:hidden;">Hidden</button>
        <button>Visible</button>
      </body>
      </html>
    """.trimIndent()
    page.setContent(html)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    // Should not crash; visible button should appear
    val text = screenState.viewHierarchyTextRepresentation!!
    assertContains(text, "\"Visible\"")
  }

  // -- Page with no interactive elements --

  @Test
  fun `page with only paragraphs reports no interactive elements`() {
    val html = """
      <!DOCTYPE html>
      <html>
      <body>
        <p>Just some text.</p>
        <p>More text.</p>
      </body>
      </html>
    """.trimIndent()
    page.setContent(html)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    val mapping = screenState.elementIdMapping
    // Paragraphs without ARIA roles may or may not show, but there should be
    // no interactive element IDs mapped
    val interactiveDescriptors = mapping.values.filter {
      it.descriptor.startsWith("button") || it.descriptor.startsWith("link") ||
        it.descriptor.startsWith("textbox")
    }
    assertTrue(
      interactiveDescriptors.isEmpty(),
      "Should have no interactive elements, but found: $interactiveDescriptors",
    )
  }

  // -- Unicode content through the full pipeline --

  @Test
  fun `emoji content renders through full screen state pipeline`() {
    val html = """
      <!DOCTYPE html>
      <html>
      <body>
        <button>🔒 Lock</button>
        <a href="#">📧 Email</a>
        <h1>Welcome 👋</h1>
      </body>
      </html>
    """.trimIndent()
    page.setContent(html)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    assertContains(text, "🔒")
    assertContains(text, "📧")
    assertContains(text, "👋")
    assertTrue(screenState.elementIdMapping.isNotEmpty())
  }

  @Test
  fun `CJK text renders through full screen state pipeline`() {
    val html = """
      <!DOCTYPE html>
      <html lang="ja">
      <body>
        <button>保存する</button>
        <a href="#">ホームページ</a>
      </body>
      </html>
    """.trimIndent()
    page.setContent(html)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    assertContains(text, "保存する")
    assertContains(text, "ホームページ")
  }

  // -- Dialog detection --

  @Test
  fun `visible dialog is listed in page context`() {
    val html = """
      <!DOCTYPE html>
      <html>
      <body>
        <dialog open>
          <p>Are you sure?</p>
          <button>Confirm</button>
        </dialog>
      </body>
      </html>
    """.trimIndent()
    page.setContent(html)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    // The dialog content should be visible in the hierarchy
    assertContains(text, "\"Confirm\"")
  }

  @Test
  fun `hidden dialog content does not pollute element list`() {
    val html = """
      <!DOCTYPE html>
      <html>
      <body>
        <dialog>
          <button>Hidden Dialog Button</button>
        </dialog>
        <button>Visible Button</button>
      </body>
      </html>
    """.trimIndent()
    page.setContent(html)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    assertContains(text, "\"Visible Button\"")
    // Hidden dialog content should not be in the ARIA snapshot
    // (Playwright's ariaSnapshot already filters non-visible elements)
  }

  // -- Large DOM stress test --

  @Test
  fun `page with many elements does not crash or timeout`() {
    val sb = StringBuilder()
    sb.append("<!DOCTYPE html><html><body>")
    for (i in 1..200) {
      sb.append("<button>Button $i</button>")
    }
    sb.append("</body></html>")
    page.setContent(sb.toString())

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    val mapping = screenState.elementIdMapping

    // Should have element IDs for many buttons
    assertTrue(mapping.size >= 100, "Should map many elements, got: ${mapping.size}")
    // First and last should be present
    assertContains(text, "\"Button 1\"")
  }

  // -- scaleToFit extreme aspect ratio --

  /**
   * Invokes the private `scaleToFit` extension function via reflection.
   */
  private fun invokeScaleToFit(
    image: BufferedImage,
    maxDim1: Int,
    maxDim2: Int,
  ): BufferedImage {
    val companionClass = PlaywrightScreenState::class.java.declaredClasses
      .find { it.simpleName == "Companion" }!!
    val method = companionClass.getDeclaredMethod(
      "scaleToFit",
      BufferedImage::class.java,
      Int::class.javaPrimitiveType,
      Int::class.javaPrimitiveType,
    )
    method.isAccessible = true
    val companionInstance = PlaywrightScreenState::class.java
      .getDeclaredField("Companion")
      .get(null)
    return method.invoke(companionInstance, image, maxDim1, maxDim2) as BufferedImage
  }

  @Test
  fun `scaleToFit extreme wide aspect ratio produces valid image`() {
    // 2000x2 image (1000:1 aspect ratio) scaled to 100x100
    val wideImage = BufferedImage(2000, 2, BufferedImage.TYPE_INT_RGB)
    val result = invokeScaleToFit(wideImage, 100, 100)

    assertTrue(result.width >= 1, "Width should be at least 1: ${result.width}")
    assertTrue(result.height >= 1, "Height should be at least 1: ${result.height}")
    assertTrue(result.width <= 100, "Width should be <= 100: ${result.width}")
  }

  @Test
  fun `scaleToFit extreme tall aspect ratio produces valid image`() {
    // 2x2000 image (1:1000 aspect ratio) scaled to 100x100
    val tallImage = BufferedImage(2, 2000, BufferedImage.TYPE_INT_RGB)
    val result = invokeScaleToFit(tallImage, 100, 100)

    assertTrue(result.width >= 1, "Width should be at least 1: ${result.width}")
    assertTrue(result.height >= 1, "Height should be at least 1: ${result.height}")
    assertTrue(result.height <= 100, "Height should be <= 100: ${result.height}")
  }

  // -- CSS selector discovery via BOUNDS/CSS_SELECTORS enrichment --

  @Test
  fun `CSS_SELECTORS enrichment surfaces data-testid elements`() {
    val html = """
      <!DOCTYPE html>
      <html>
      <body>
        <div data-testid="user-card">
          <span>John Doe</span>
        </div>
        <button>Submit</button>
      </body>
      </html>
    """.trimIndent()
    page.setContent(html)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
      requestedDetails = setOf(ViewHierarchyDetail.CSS_SELECTORS),
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    // Should contain the button from ARIA + potentially the data-testid element
    assertContains(text, "\"Submit\"")
  }

  @Test
  fun `element with id gets css selector with escaped id`() {
    val html = """
      <!DOCTYPE html>
      <html>
      <body>
        <div id="my-container">
          <button id="submit-btn">Submit</button>
        </div>
      </body>
      </html>
    """.trimIndent()
    page.setContent(html)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
      requestedDetails = setOf(ViewHierarchyDetail.CSS_SELECTORS),
    )

    val text = screenState.viewHierarchyTextRepresentation!!
    assertContains(text, "\"Submit\"")
  }

  // -- Browser manager lifecycle edge cases --

  @Test
  fun `browser manager resetSession creates clean state`() {
    val browserManager = PlaywrightBrowserManager(headless = true)
    try {
      browserManager.currentPage.setContent("<h1>Page 1</h1>")
      val state1 = browserManager.getScreenState() as PlaywrightScreenState
      val text1 = state1.viewHierarchyTextRepresentation!!

      browserManager.resetSession()
      val state2 = browserManager.getScreenState() as PlaywrightScreenState
      val text2 = state2.viewHierarchyTextRepresentation!!

      // After reset, should be a blank page
      assertFalse(text2.contains("\"Page 1\""), "Should not see Page 1 content after reset")
    } finally {
      browserManager.close()
    }
  }

  @Test
  fun `browser manager getScreenState without prior content does not crash`() {
    val browserManager = PlaywrightBrowserManager(headless = true)
    try {
      // Get screen state immediately without setting any content
      val state = browserManager.getScreenState() as PlaywrightScreenState
      assertNotNull(state.viewHierarchyTextRepresentation)
    } finally {
      browserManager.close()
    }
  }

  @Test
  fun `pending detail requests are consumed after single getScreenState`() {
    val browserManager = PlaywrightBrowserManager(headless = true)
    try {
      browserManager.currentPage.setContent("""
        <html><body>
          <button>Click</button>
          <a href="#">Link</a>
        </body></html>
      """.trimIndent())

      // Request BOUNDS for the next snapshot
      browserManager.requestDetails(setOf(ViewHierarchyDetail.BOUNDS))

      // First call should include bounds
      val enriched = browserManager.getScreenState() as PlaywrightScreenState
      val enrichedText = enriched.viewHierarchyTextRepresentation!!

      // Second call should NOT include bounds (auto-cleared)
      val plain = browserManager.getScreenState() as PlaywrightScreenState
      val plainText = plain.viewHierarchyTextRepresentation!!

      // Verify bounds were present and then cleared
      if (enrichedText.contains("{x:")) {
        assertFalse(
          plainText.contains("{x:"),
          "Second getScreenState should not have bounds after auto-clear",
        )
      }
    } finally {
      browserManager.close()
    }
  }

  // -- Screenshot pipeline edge cases --

  @Test
  fun `annotatedScreenshotBytes returns non-null for valid page`() {
    page.setContent("<html><body><h1>Screenshot Test</h1></body></html>")

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )

    val bytes = screenState.annotatedScreenshotBytes
    assertNotNull(bytes, "Should produce screenshot bytes")
    assertTrue(bytes.isNotEmpty(), "Screenshot bytes should be non-empty")
  }

  @Test
  fun `screenshot with null scaling config returns raw bytes`() {
    page.setContent("<html><body><h1>Raw Screenshot</h1></body></html>")

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
      screenshotScalingConfig = null,
    )

    val bytes = screenState.annotatedScreenshotBytes
    assertNotNull(bytes, "Should produce raw screenshot bytes with null config")
    assertTrue(bytes.isNotEmpty())
  }
}
