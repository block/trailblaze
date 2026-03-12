package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import java.awt.image.BufferedImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeNavigateTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeNavigateTool.NavigationAction
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [PlaywrightScreenState] image scaling, [PlaywrightNativeNavigateTool] null history
 * handling, and [PlaywrightBrowserManager] idempotent close.
 *
 * Uses a real Chromium browser with inline HTML pages.
 */
class PlaywrightScreenStateTest {

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

  private fun buildContext(screenState: PlaywrightScreenState? = null): TrailblazeToolExecutionContext {
    return TrailblazeToolExecutionContext(
      screenState = screenState,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test-browser",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
        ),
        trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
        widthPixels = 1280,
        heightPixels = 800,
      ),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(
          sessionId = SessionId("test-session"),
          startTime = Clock.System.now(),
        )
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
    )
  }

  // -- scaleToFit tests (via reflection since method is private) --

  /**
   * Invokes the private `scaleToFit` extension function on BufferedImage
   * from [PlaywrightScreenState.Companion] via reflection.
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
  fun `scaleToFit with zero-dimension image does not crash`() {
    // A 1x1 image scaled to very small target — should produce at least 1x1
    val tinyImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    val result = invokeScaleToFit(tinyImage, 1, 1)

    assertTrue(result.width >= 1, "Width should be at least 1")
    assertTrue(result.height >= 1, "Height should be at least 1")
  }

  @Test
  fun `scaleToFit with TYPE_CUSTOM image falls back to TYPE_INT_ARGB`() {
    // Create a TYPE_CUSTOM image using a ComponentColorModel
    val colorSpace = java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_sRGB)
    val colorModel = java.awt.image.ComponentColorModel(
      colorSpace, intArrayOf(8, 8, 8), false, false,
      java.awt.Transparency.OPAQUE, java.awt.image.DataBuffer.TYPE_BYTE,
    )
    val raster = colorModel.createCompatibleWritableRaster(200, 200)
    val customImage = BufferedImage(colorModel, raster, false, null)
    assertEquals(BufferedImage.TYPE_CUSTOM, customImage.type, "Should be TYPE_CUSTOM")

    // Scale down — should not crash despite TYPE_CUSTOM
    val result = invokeScaleToFit(customImage, 50, 50)

    assertTrue(result.width in 1..50, "Width should be scaled down: ${result.width}")
    assertTrue(result.height in 1..50, "Height should be scaled down: ${result.height}")
    assertEquals(
      BufferedImage.TYPE_INT_ARGB, result.type,
      "TYPE_CUSTOM should fall back to TYPE_INT_ARGB",
    )
  }

  @Test
  fun `scaleToFit normal scaling produces correctly sized image`() {
    val largeImage = BufferedImage(2000, 1000, BufferedImage.TYPE_INT_RGB)
    val result = invokeScaleToFit(largeImage, 1024, 512)

    // Should be scaled down maintaining aspect ratio
    assertTrue(result.width <= 1024, "Width should be <= 1024: ${result.width}")
    assertTrue(result.height <= 512, "Height should be <= 512: ${result.height}")
    assertTrue(result.width > 0 && result.height > 0, "Dimensions should be positive")

    // Aspect ratio should be approximately maintained (2:1)
    val ratio = result.width.toFloat() / result.height.toFloat()
    assertTrue(ratio in 1.8f..2.2f, "Aspect ratio should be ~2:1, got $ratio")
  }

  @Test
  fun `scaleToFit does not upscale small images`() {
    val smallImage = BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB)
    val result = invokeScaleToFit(smallImage, 1024, 512)

    // Should return the original image without upscaling
    assertEquals(100, result.width)
    assertEquals(50, result.height)
  }

  // -- Navigate back/forward null history handling tests --

  @Test
  fun `navigate back with no history returns error not crash`() {
    page.setContent("<html><body><h1>Single Page</h1></body></html>")

    val tool = PlaywrightNativeNavigateTool(action = NavigationAction.BACK)
    val context = buildContext()
    val result = runBlocking { tool.executeWithPlaywright(page, context) }

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertContains(result.errorMessage, "history")
  }

  @Test
  fun `navigate forward with no history returns error not crash`() {
    page.setContent("<html><body><h1>Single Page</h1></body></html>")

    val tool = PlaywrightNativeNavigateTool(action = NavigationAction.FORWARD)
    val context = buildContext()
    val result = runBlocking { tool.executeWithPlaywright(page, context) }

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertContains(result.errorMessage, "history")
  }

  // -- PlaywrightBrowserManager double-close test --

  @Test
  fun `double-close browser manager is no-op no exception`() {
    val browserManager = PlaywrightBrowserManager(headless = true)
    try {
      // Load some content to ensure browser is fully initialized
      browserManager.currentPage.setContent("<h1>Test</h1>")
    } finally {
      // First close
      browserManager.close()
      // Second close should be a no-op — no exception thrown
      browserManager.close()
    }
    // If we reach here, the test passed — no exception from double-close
  }
}
