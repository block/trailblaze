package xyz.block.trailblaze.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanRunDesktopGuiTest {

  private fun canRun(
    os: DesktopOsType,
    headless: Boolean = false,
    linuxOptIn: String? = null,
    hasDisplay: Boolean = true,
  ): Boolean =
    canRunDesktopGui(os = os, headless = headless, linuxOptIn = linuxOptIn, hasDisplay = { hasDisplay })

  @Test
  fun `macOS with a display runs the GUI without any opt-in`() {
    assertTrue(canRun(DesktopOsType.MAC_OS))
  }

  @Test
  fun `a headless JVM never runs the GUI, even when opted in`() {
    assertFalse(canRun(DesktopOsType.MAC_OS, headless = true))
    assertFalse(canRun(DesktopOsType.LINUX, headless = true, linuxOptIn = "true"))
  }

  @Test
  fun `Linux with a display stays headless unless opted in`() {
    assertFalse(canRun(DesktopOsType.LINUX, linuxOptIn = null))
    assertFalse(canRun(DesktopOsType.LINUX, linuxOptIn = "false"))
    assertFalse(canRun(DesktopOsType.LINUX, linuxOptIn = ""))
  }

  @Test
  fun `Linux opted in runs the GUI when a display is reachable`() {
    for (value in listOf("true", "1", " TRUE ")) {
      assertEquals("opt-in '$value'", true, canRun(DesktopOsType.LINUX, linuxOptIn = value))
    }
  }

  @Test
  fun `Linux opted in with no display stays headless`() {
    assertFalse(canRun(DesktopOsType.LINUX, linuxOptIn = "true", hasDisplay = false))
  }

  @Test
  fun `Windows is not opted in by the Linux variable`() {
    assertFalse(canRun(DesktopOsType.WINDOWS, linuxOptIn = "true"))
  }
}
