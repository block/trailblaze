package xyz.block.trailblaze.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.block.trailblaze.AndroidDeviceLocale
import xyz.block.trailblaze.devices.TrailDeviceLocale
import xyz.block.trailblaze.android.AndroidTrailblazeRule.Companion.shouldForceStopTargetApp

class AndroidDeviceLocaleTest {

  @Test
  fun `a language change forces a fresh target process`() {
    assertTrue(shouldForceStopTargetApp(requested = false, localeChanged = true))
    assertTrue(shouldForceStopTargetApp(requested = true, localeChanged = false))
  }

  @Test
  fun `a reusable trailhead hand-off skips the clean start but not a locale change`() {
    assertFalse(shouldForceStopTargetApp(requested = true, localeChanged = false, keepForReuse = true))
    assertTrue(shouldForceStopTargetApp(requested = true, localeChanged = true, keepForReuse = true))
    assertFalse(shouldForceStopTargetApp(requested = false, localeChanged = false))
  }

  @Test
  fun `a trail without a locale runs in its catalog device's locale`() {
    assertEquals("es", AndroidDeviceLocale.forTrail(trailLocale = null) { "es" })
  }

  @Test
  fun `a trail's own locale wins over its catalog device's`() {
    assertEquals("fr-CA", AndroidDeviceLocale.forTrail(trailLocale = "fr-CA") { "es" })
  }

  @Test
  fun `a base device with no catalog locale leaves the device language alone`() {
    assertEquals(null, AndroidDeviceLocale.forTrail(trailLocale = null) { null })
  }

  @Test
  fun `a locale the device already runs needs no fresh app process`() {
    assertTrue(AndroidDeviceLocale.isCurrent("es") { "es" })
    // Before Android 16 the persisted system locale is the readback.
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "es,en-US"))
    assertTrue(AndroidDeviceLocale.isCurrent("es") { outputs.removeFirst() })
  }

  @Test
  fun `a locale the device does not run yet is not current`() {
    assertFalse(AndroidDeviceLocale.isCurrent("es") { "en-US" })
    val outputs = ArrayDeque(listOf("", "en-US"))
    assertFalse(AndroidDeviceLocale.isCurrent("es") { outputs.removeFirst() })
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

    val failure = assertFailsWith<IllegalStateException> {
      AndroidDeviceLocale.apply("es") { outputs.removeFirst() }
    }
    // The setter refused, so nothing changed: a plain failure, not a partial change.
    assertFalse(failure is TrailDeviceLocale.PartiallyAppliedException)
  }

  @Test
  fun `a setter that accepted the locale but reads back wrong counts as a partial change`() {
    val outputs = ArrayDeque(listOf("en", "", "en"))

    assertFailsWith<TrailDeviceLocale.PartiallyAppliedException> {
      AndroidDeviceLocale.apply("es") { outputs.removeFirst() }
    }
  }

  @Test
  fun `before Android 16 an already configured locale is left alone`() {
    val commands = mutableListOf<String>()
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "es,en-US"))

    val changed = AndroidDeviceLocale.apply(
      "es",
      updateSystemLocales = { error("an already configured locale must not be rewritten") },
    ) { command ->
      commands += command
      outputs.removeFirst()
    }

    assertFalse(changed)
    assertEquals(listOf("cmd locale get-device-locale", SYSTEM_LOCALES), commands)
  }

  @Test
  fun `before Android 16 a host-configured emulator locale is accepted`() {
    val commands = mutableListOf<String>()
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "null", "es"))

    val changed = AndroidDeviceLocale.apply(
      "es",
      updateSystemLocales = { error("a host-configured locale must not be rewritten") },
    ) { command ->
      commands += command
      outputs.removeFirst()
    }

    assertFalse(changed)
    assertEquals(listOf("cmd locale get-device-locale", SYSTEM_LOCALES, PERSIST_SYS_LOCALE), commands)
  }

  @Test
  fun `before Android 16 a stale persist sys locale does not hide the configured locale`() {
    // A configuration update writes system_locales, which then wins over persist.sys.locale at
    // boot, so a persist.sys.locale that matches is not proof the device runs in that locale.
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "en-US", "es"))
    val updated = mutableListOf<String>()

    val changed = AndroidDeviceLocale.apply("es", updateSystemLocales = { updated += it }) {
      outputs.removeFirst()
    }

    assertTrue(changed)
    assertEquals(listOf("es"), updated)
  }

  @Test
  fun `before Android 16 the system configuration is updated and verified`() {
    val commands = mutableListOf<String>()
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "null", "", "en-US", "es-MX"))
    val updated = mutableListOf<String>()

    val changed = AndroidDeviceLocale.apply("es-mx", updateSystemLocales = { updated += it }) { command ->
      commands += command
      outputs.removeFirst()
    }

    assertTrue(changed)
    assertEquals(listOf("es-MX"), updated)
    assertEquals(
      listOf("cmd locale get-device-locale", SYSTEM_LOCALES, PERSIST_SYS_LOCALE, PRODUCT_LOCALE, SYSTEM_LOCALES),
      commands,
    )
  }

  @Test
  fun `before Android 16 an update that does not take fails with the readback after waiting`() {
    val sleeps = mutableListOf<Long>()

    // The update returned, so the running configuration may have changed: partial.
    val failure = assertFailsWith<TrailDeviceLocale.PartiallyAppliedException> {
      AndroidDeviceLocale.apply("es", updateSystemLocales = {}, sleep = { sleeps += it }) { command ->
        if (command == "cmd locale get-device-locale") "Unknown command: get-device-locale" else "en-US"
      }
    }

    assertTrue(failure.message.orEmpty().contains("did not take; persisted locale: en-US"))
    assertTrue(sleeps.sum() in 1_000L..2_000L, "waited ${sleeps.sum()} ms")
  }

  @Test
  fun `before Android 16 a readback that persists late is accepted`() {
    val readbacks = ArrayDeque(listOf("en-US", "en-US", "en-US", "es"))
    var sleeps = 0

    val changed = AndroidDeviceLocale.apply("es", updateSystemLocales = {}, sleep = { sleeps++ }) { command ->
      if (command == SYSTEM_LOCALES) readbacks.removeFirst() else "Unknown command: get-device-locale"
    }

    assertTrue(changed)
    assertEquals(2, sleeps)
  }

  @Test
  fun `Android 10 to 12 have no locale service and take the configuration update`() {
    // No `locale` service before Android 13. `cmd` reports that on stderr, so the captured output
    // is either the message or nothing at all.
    for (noService in listOf("cmd: Can't find service: locale", "")) {
      val outputs = ArrayDeque(listOf(noService, "en-US", "es"))
      val updated = mutableListOf<String>()

      val changed = AndroidDeviceLocale.apply("es", updateSystemLocales = { updated += it }) {
        outputs.removeFirst()
      }

      assertTrue(changed, "for get-device-locale output `$noService`")
      assertEquals(listOf("es"), updated)
    }
  }

  @Test
  fun `before Android 16 an update that throws fails with its cause`() {
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "en-US", "en-US"))

    val failure = assertFailsWith<IllegalStateException> {
      AndroidDeviceLocale.apply(
        "es",
        updateSystemLocales = { throw SecurityException("CHANGE_CONFIGURATION denied") },
      ) { outputs.removeFirst() }
    }

    assertTrue(failure.message.orEmpty().contains("CHANGE_CONFIGURATION denied"))
    assertFalse(failure is TrailDeviceLocale.PartiallyAppliedException)
  }

  private companion object {
    const val SYSTEM_LOCALES = "settings get system system_locales"
    const val PERSIST_SYS_LOCALE = "getprop persist.sys.locale"
    const val PRODUCT_LOCALE = "getprop ro.product.locale"
  }

  @Test
  fun `before Android 16 a fresh image already in the default is left alone`() {
    // A fresh image sets neither system_locales nor persist.sys.locale: it boots in the build's
    // locale. Every trail that declares no locale asks for en-US, and must not count as a change.
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "null", "", "en-US"))

    val changed = AndroidDeviceLocale.apply(
      "en-US",
      updateSystemLocales = { error("a fresh en-US image must not be updated") },
    ) { outputs.removeFirst() }

    assertFalse(changed)
  }
}
