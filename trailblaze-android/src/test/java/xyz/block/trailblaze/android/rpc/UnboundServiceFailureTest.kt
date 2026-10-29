package xyz.block.trailblaze.android.rpc

import xyz.block.trailblaze.android.ACCESSIBILITY_SERVICE_NOT_RUNNING
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceScreenStateNotReadyException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Pins what [AccessibilityScreenStateCaptor] answers when the accessibility service is unbound: a
 * readiness poll keeps its stable not-ready signal, and any other capture names the real cause
 * and the fix, so a service that died mid-run doesn't read as a cold start. Neither is traced.
 */
class UnboundServiceFailureTest {

  private fun failure(serviceRunning: Boolean = false, requireService: Boolean, migrationMode: Boolean = false) =
    AccessibilityScreenStateCaptor.unboundServiceFailure(serviceRunning, requireService, migrationMode)

  @Test
  fun `a running service always proceeds`() {
    for (require in listOf(true, false)) {
      for (migration in listOf(true, false)) {
        assertNull(failure(serviceRunning = true, requireService = require, migrationMode = migration))
      }
    }
  }

  @Test
  fun `a readiness poll gets the stable not-ready signal`() {
    for (migration in listOf(true, false)) {
      val e = assertIs<OnDeviceScreenStateNotReadyException>(failure(requireService = true, migrationMode = migration))
      assertEquals(AccessibilityScreenStateCaptor.NOT_YET_BOUND, e.message)
    }
  }

  @Test
  fun `any other capture names the cause and the re-bind fix, untraced`() {
    // Typed not-ready so the handler answers in one line: the recording mirror re-polls every frame
    // while the service is down, and a stack trace per poll would bury the message.
    val e = assertIs<OnDeviceScreenStateNotReadyException>(failure(requireService = false))
    assertEquals(ACCESSIBILITY_SERVICE_NOT_RUNNING, e.message)
  }

  @Test
  fun `migration mode proceeds on UiAutomator outside a readiness poll`() {
    assertNull(failure(requireService = false, migrationMode = true))
  }
}
