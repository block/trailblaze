package xyz.block.trailblaze.android.accessibility

import xyz.block.trailblaze.toolcalls.commands.SettledScreen
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AccessibilityAppIdleProbeTest {

  @AfterTest
  fun cleanup() {
    SettledScreen.appIdleProbe = null
  }

  @Test
  fun `the accessibility driver gives selector waits an idle signal`() {
    SettledScreen.appIdleProbe = null

    AccessibilityDeviceManager()

    assertNotNull(SettledScreen.appIdleProbe, "a device manager must install the app-idle signal")
  }

  @Test
  fun `with no accessibility service the app is never called idle`() {
    // No events can arrive without a service, so "quiet" would hold forever and stop every wait.
    assertFalse(TrailblazeAccessibilityService.isServiceRunning())
    assertFalse(AccessibilityAppIdleProbe.isAppIdle(quietWindowMs = 0))
  }

  @Test
  fun `the app is idle once no UI event has arrived for the quiet window`() {
    assertFalse(AccessibilityAppIdleProbe.eventQuiet(nowMs = 2_000, lastUiEventMs = 1_000, quietWindowMs = 1_500))
    assertTrue(AccessibilityAppIdleProbe.eventQuiet(nowMs = 2_500, lastUiEventMs = 1_000, quietWindowMs = 1_500))
  }

  @Test
  fun `the in-process idle detector's reply decides when it answers`() {
    assertTrue(AccessibilityAppIdleProbe.isIdleReply("IDLE 12"))
    assertFalse(AccessibilityAppIdleProbe.isIdleReply("TIMEOUT 200"))
  }
}
