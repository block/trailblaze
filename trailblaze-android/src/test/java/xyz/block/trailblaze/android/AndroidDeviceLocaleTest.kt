package xyz.block.trailblaze.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.block.trailblaze.AndroidDeviceLocale
import xyz.block.trailblaze.android.AndroidTrailblazeRule.Companion.shouldForceStopTargetApp

class AndroidDeviceLocaleTest {

  @Test
  fun `locale requests force a fresh target process`() {
    assertTrue(shouldForceStopTargetApp(requested = false, locale = "es"))
    assertTrue(shouldForceStopTargetApp(requested = true, locale = null))
    assertFalse(shouldForceStopTargetApp(requested = false, locale = null))
  }

  @Test
  fun `invalid locale is rejected before it reaches the shell`() {
    assertFailsWith<IllegalArgumentException> {
      AndroidDeviceLocale.apply("es; reboot") { error("invalid locale reached the shell") }
    }
  }

  @Test
  fun `already configured locale does not write device state`() {
    val commands = mutableListOf<String>()

    val changed = AndroidDeviceLocale.apply("es") { command ->
      commands += command
      "es"
    }

    assertEquals(false, changed)
    assertEquals(listOf("cmd locale get-device-locale"), commands)
  }

  @Test
  fun `different locale is set and verified before returning`() {
    val commands = mutableListOf<String>()
    val outputs = ArrayDeque(
      listOf(
        "en",
        "",
        "es",
      ),
    )

    val changed = AndroidDeviceLocale.apply("es") { command ->
      commands += command
      outputs.removeFirst()
    }

    assertEquals(true, changed)
    assertEquals(
      listOf(
        "cmd locale get-device-locale",
        "cmd locale set-device-locale es",
        "cmd locale get-device-locale",
      ),
      commands,
    )
  }

  @Test
  fun `setter failure is surfaced by unchanged readback`() {
    val outputs = ArrayDeque(listOf("en", "unsupported locale", "en"))

    assertFailsWith<IllegalStateException> {
      AndroidDeviceLocale.apply("es") { outputs.removeFirst() }
    }
  }

  @Test
  fun `missing locale command accepts a host-configured emulator locale`() {
    val commands = mutableListOf<String>()
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "es"))

    val changed = AndroidDeviceLocale.apply("es") { command ->
      commands += command
      outputs.removeFirst()
    }

    assertFalse(changed)
    assertEquals(listOf("cmd locale get-device-locale", "getprop persist.sys.locale"), commands)
  }

  @Test
  fun `missing locale command fails when the host did not configure the emulator`() {
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "en"))

    val failure = assertFailsWith<IllegalStateException> {
      AndroidDeviceLocale.apply("es") { outputs.removeFirst() }
    }

    assertTrue(failure.message.orEmpty().contains("host-side emulator fallback was not applied"))
  }
}
