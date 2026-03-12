package xyz.block.trailblaze.playwright.recording

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ScreenshotAnimations
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.playwright.PlaywrightAriaSnapshot
import xyz.block.trailblaze.playwright.PlaywrightPageManager
import xyz.block.trailblaze.recording.DeviceScreenStream

/**
 * [DeviceScreenStream] backed by a Playwright [Page].
 * Screenshots are captured via `page.screenshot()` at a configurable interval.
 * Input forwarding uses the Playwright mouse/keyboard API.
 */
class PlaywrightDeviceScreenStream(
  private val pageManager: PlaywrightPageManager,
  private val frameIntervalMs: Long = 100,
) : DeviceScreenStream {

  override val deviceWidth: Int get() = pageManager.currentPage.viewportSize().width
  override val deviceHeight: Int get() = pageManager.currentPage.viewportSize().height

  override fun frames(): Flow<ByteArray> = flow {
    while (currentCoroutineContext().isActive) {
      try {
        val bytes = withContext(pageManager.playwrightDispatcher) { captureScreenshotBytes() }
        if (bytes != null) emit(bytes)
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        // Page may be navigating; skip this frame
      }
      delay(frameIntervalMs)
    }
  }

  override suspend fun tap(x: Int, y: Int) = withContext(pageManager.playwrightDispatcher) {
    pageManager.currentPage.mouse().click(x.toDouble(), y.toDouble())
  }

  override suspend fun longPress(x: Int, y: Int) = withContext(pageManager.playwrightDispatcher) {
    val mouse = pageManager.currentPage.mouse()
    mouse.move(x.toDouble(), y.toDouble())
    mouse.down()
    Thread.sleep(500) // Use blocking sleep to hold the Playwright thread
    mouse.up()
  }

  override suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int) =
    withContext(pageManager.playwrightDispatcher) {
      val mouse = pageManager.currentPage.mouse()
      mouse.move(startX.toDouble(), startY.toDouble())
      mouse.down()
      // Simulate a smooth drag with intermediate steps
      val steps = 10
      for (i in 1..steps) {
        val fraction = i.toDouble() / steps
        val cx = startX + (endX - startX) * fraction
        val cy = startY + (endY - startY) * fraction
        mouse.move(cx, cy)
      }
      mouse.up()
    }

  override suspend fun inputText(text: String) = withContext(pageManager.playwrightDispatcher) {
    pageManager.currentPage.keyboard().type(text)
  }

  override suspend fun pressKey(key: String) = withContext(pageManager.playwrightDispatcher) {
    pageManager.currentPage.keyboard().press(key)
  }

  override suspend fun getViewHierarchy(): ViewHierarchyTreeNode =
    withContext(pageManager.playwrightDispatcher) {
      val ariaYaml = PlaywrightAriaSnapshot.captureAriaSnapshot(pageManager.currentPage).yaml
      PlaywrightAriaSnapshot.ariaSnapshotToViewHierarchy(ariaYaml)
    }

  override suspend fun getScreenshot(): ByteArray =
    withContext(pageManager.playwrightDispatcher) {
      captureScreenshotBytes() ?: ByteArray(0)
    }

  /**
   * Returns the compact ARIA element list and ID mapping for the current page.
   * Used by [PlaywrightInteractionToolFactory] to resolve clicks to element refs.
   */
  fun getCompactAriaElements(): PlaywrightAriaSnapshot.CompactAriaElements {
    return kotlinx.coroutines.runBlocking(pageManager.playwrightDispatcher) {
      val ariaYaml = PlaywrightAriaSnapshot.captureAriaSnapshot(pageManager.currentPage).yaml
      PlaywrightAriaSnapshot.buildCompactElementList(ariaYaml)
    }
  }

  private fun captureScreenshotBytes(): ByteArray? {
    return try {
      pageManager.currentPage.screenshot(
        Page.ScreenshotOptions()
          .setFullPage(false)
          .setAnimations(ScreenshotAnimations.DISABLED)
          .setTimeout(500.0),
      )
    } catch (e: Exception) {
      if (e is kotlinx.coroutines.CancellationException) throw e
      null
    }
  }
}
