package xyz.block.trailblaze.examples.sampleapp

import android.graphics.Bitmap
import org.junit.Assert.assertNotNull
import org.junit.Test
import xyz.block.trailblaze.InstrumentationUtil
import xyz.block.trailblaze.InstrumentationUtil.withUiAutomation

/**
 * On-device verification for [InstrumentationUtil.withUiAutomation] — the shared accessor that the
 * accessibility service's `captureScreenshot()` and the UiAutomator screen state both funnel
 * through. Guards the dedicated-lock change that moved the `getUiAutomation()` evaluation inside
 * the stale-handle recovery block: the accessor must still capture a screenshot and must still
 * reconnect after the cached handle is dropped.
 *
 * Runs under AndroidJUnitRunner so a live UiAutomation connection is available; the screenshot is
 * of whatever is currently on screen, so no specific app state is required. Lives in this module
 * because it is the wired on-device (connected) test harness, alongside other framework-feature
 * on-device tests such as [QuickJsToolBundleOnDeviceTest].
 *
 * This is a happy-path + reconnect smoke; it does not attempt to reproduce the transient "Cannot
 * call disconnect() while connecting" acquisition race itself (that fires from the platform's
 * internal connect/disconnect state machine and cannot be triggered deterministically).
 */
class InstrumentationUtilUiAutomationTest {

  @Test
  fun withUiAutomation_capturesScreenshot() {
    val bitmap: Bitmap? = withUiAutomation { takeScreenshot() }
    assertNotNull("withUiAutomation { takeScreenshot() } should return a bitmap on-device", bitmap)
  }

  @Test
  fun withUiAutomation_reconnectsAfterCachedHandleCleared() {
    assertNotNull(withUiAutomation { takeScreenshot() })
    // Drop the cached UiAutomation handle exactly the way the recovery path does. Because
    // withUiAutomation now locks on a dedicated object (not on the getUiAutomation() result),
    // the getter runs inside the accessor and must transparently reconnect on the next call.
    val cleared = InstrumentationUtil.clearInstrumentationUiAutomationCache()
    assertNotNull(
      "withUiAutomation must reconnect after the cached handle is cleared (cleared=$cleared)",
      withUiAutomation { takeScreenshot() },
    )
  }
}
