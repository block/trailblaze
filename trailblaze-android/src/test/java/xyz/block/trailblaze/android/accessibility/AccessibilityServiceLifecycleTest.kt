package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AccessibilityServiceLifecycleTest {
  private val component = "test.package/TestService"

  @Test
  fun `shutdown removes only its own service and leaves other accessibility enabled`() {
    val commands = mutableListOf<String>()
    shutdownAccessibilityService(
      serviceComponent = component,
      timeoutMs = 1_000,
      execShell = { command, _ ->
        commands += command
        if (command == "settings get secure enabled_accessibility_services") {
          "other.package/First:$component:other.package/Second"
        } else ""
      },
      isRunning = { false },
      nowMs = { 0 },
      sleepMs = { error("The service is already stopped") },
    )

    assertEquals(
      listOf(
        "settings get secure enabled_accessibility_services",
        "settings put secure enabled_accessibility_services other.package/First:other.package/Second",
      ),
      commands,
    )
  }

  @Test
  fun `shutdown disables accessibility when removing the only enabled service`() {
    val commands = mutableListOf<String>()
    shutdownAccessibilityService(
      serviceComponent = component,
      timeoutMs = 1_000,
      execShell = { command, _ ->
        commands += command
        if (command == "settings get secure enabled_accessibility_services") component else ""
      },
      isRunning = { false },
      nowMs = { 0 },
      sleepMs = { error("The service is already stopped") },
    )

    assertTrue("settings delete secure enabled_accessibility_services" in commands)
    assertTrue("settings put secure accessibility_enabled 0" in commands)
  }

  @Test
  fun `shell commands and unbinding share a single shutdown budget`() {
    var time = 0L
    var unboundAt: Long? = null
    shutdownAccessibilityService(
      serviceComponent = component,
      timeoutMs = 300,
      execShell = { command, budget ->
        assertEquals(300 - time, budget)
        assertTrue(budget > 0)
        time += 50
        if (command == "settings get secure enabled_accessibility_services") {
          "other.package/Service:$component"
        } else {
          unboundAt = time + 100
          ""
        }
      },
      isRunning = { unboundAt == null || time < unboundAt!! },
      nowMs = { time },
      sleepMs = { time += it },
    )

    assertFalse(time < unboundAt!!)
    assertTrue(time < 300)
  }

  @Test
  fun `a settings read that exhausts the budget cannot start a write`() {
    var time = 0L
    assertFailsWith<IllegalStateException> {
      shutdownAccessibilityService(
        serviceComponent = component,
        timeoutMs = 100,
        execShell = { command, budget ->
          assertEquals("settings get secure enabled_accessibility_services", command)
          assertEquals(100L, budget)
          time += budget
          component
        },
        isRunning = { true },
        nowMs = { time },
        sleepMs = { error("No budget remains for polling") },
      )
    }
    assertEquals(100L, time)
  }

  @Test
  fun `shutdown times out if an already removed service remains bound`() {
    var time = 0L
    assertFailsWith<IllegalStateException> {
      shutdownAccessibilityService(
        serviceComponent = component,
        timeoutMs = 250,
        execShell = { command, _ ->
          assertEquals("settings get secure enabled_accessibility_services", command)
          "null"
        },
        isRunning = { true },
        nowMs = { time },
        sleepMs = { time += it },
      )
    }
    assertEquals(250L, time)
  }

  @Test
  fun `failed startup rolls back enablement and preserves the setup failure`() {
    var enabled = false
    val setupFailure = IllegalStateException("bind timed out")
    val thrown = assertFailsWith<IllegalStateException> {
      startAccessibilityServiceWithRollback(
        start = {
          enabled = true
          throw setupFailure
        },
        rollback = { enabled = false },
      )
    }
    assertSame(setupFailure, thrown)
    assertFalse(enabled)
  }

  @Test
  fun `a rollback failure is suppressed by the setup failure`() {
    val setupFailure = IllegalStateException("bind timed out")
    val rollbackFailure = IllegalArgumentException("settings unavailable")
    val thrown = assertFailsWith<IllegalStateException> {
      startAccessibilityServiceWithRollback(
        start = { throw setupFailure },
        rollback = { throw rollbackFailure },
      )
    }
    assertSame(setupFailure, thrown)
    assertEquals(listOf(rollbackFailure), thrown.suppressed.toList())
  }

  @Test
  fun `successful startup keeps the service enabled`() {
    var enabled = false
    startAccessibilityServiceWithRollback(
      start = { enabled = true },
      rollback = { error("Successful startup must not roll back") },
    )
    assertTrue(enabled)
  }
}
