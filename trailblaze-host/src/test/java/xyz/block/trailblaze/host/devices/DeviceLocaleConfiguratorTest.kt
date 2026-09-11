package xyz.block.trailblaze.host.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.ios.SimctlCli

class DeviceLocaleConfiguratorTest {

  @Test
  fun `normalizes supported BCP-47 locale tags`() {
    assertEquals("es", DeviceLocaleConfigurator.normalizedLocale("es"))
    assertEquals("fr-CA", DeviceLocaleConfigurator.normalizedLocale("fr-ca"))
  }

  @Test
  fun `rejects a locale value that cannot become a BCP-47 language tag`() {
    assertFailsWith<IllegalArgumentException> {
      DeviceLocaleConfigurator.normalizedLocale("es; reboot")
    }
  }

  @Test
  fun `builds device-language commands without app-specific launch arguments`() {
    assertEquals(
      listOf("cmd", "locale", "set-device-locale", "es"),
      DeviceLocaleConfigurator.androidSetLocaleCommand("es"),
    )
    assertEquals(
      listOf("getprop", "persist.sys.locale"),
      DeviceLocaleConfigurator.androidGetPersistedLocaleCommand(),
    )
    assertEquals(
      listOf("defaults", "write", "-g", "AppleLanguages", "-array", "es"),
      DeviceLocaleConfigurator.iosSetLanguageCommand("es"),
    )
    assertEquals(
      listOf("defaults", "read", "-g", "AppleLanguages"),
      DeviceLocaleConfigurator.iosGetLanguagesCommand(),
    )
    assertEquals(listOf("killall", "SpringBoard"), DeviceLocaleConfigurator.iosRestartSpringBoardCommand())
  }

  @Test
  fun `accepts only the requested Android locale readback`() {
    assertTrue(DeviceLocaleConfigurator.localeReadbackMatches("fr-CA", "fr-ca\n"))
    assertFalse(DeviceLocaleConfigurator.localeReadbackMatches("fr-CA", "fr-FR\n"))
    assertFalse(DeviceLocaleConfigurator.localeReadbackMatches("fr-CA", null))
  }

  @Test
  fun `reads the first iOS language from defaults output`() {
    assertTrue(
      DeviceLocaleConfigurator.iosLanguageReadbackMatches(
        "fr-CA",
        """
          (
              "fr-ca",
              "en"
          )
        """.trimIndent(),
      ),
    )
    assertFalse(DeviceLocaleConfigurator.iosLanguageReadbackMatches("fr-CA", "(\n    en\n)"))
  }

  @Test
  fun `Android locale apply reads before writing and verifies after`() {
    val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
    val commands = mutableListOf<List<String>>()
    val outputs = ArrayDeque(listOf("en", "", "es"))

    DeviceLocaleConfigurator.applyAndroid(deviceId, "es") { _, args, _ ->
      commands += args
      outputs.removeFirst()
    }

    assertEquals(
      listOf(
        DeviceLocaleConfigurator.androidGetLocaleCommand(),
        DeviceLocaleConfigurator.androidSetLocaleCommand("es"),
        DeviceLocaleConfigurator.androidGetLocaleCommand(),
      ),
      commands,
    )
  }

  @Test
  fun `Android locale apply fails when the requested language is not active`() {
    val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
    val outputs = ArrayDeque(listOf("en", "unsupported locale", "en"))

    val failure = assertFailsWith<IllegalStateException> {
      DeviceLocaleConfigurator.applyAndroid(deviceId, "es") { _, _, _ -> outputs.removeFirst() }
    }

    assertTrue(failure.message.orEmpty().contains("Could not apply device locale `es`"))
  }

  @Test
  fun `Android locale apply falls back on an emulator without locale commands`() {
    val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
    val commands = mutableListOf<List<String>>()
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "1", "", "es"))

    DeviceLocaleConfigurator.applyAndroid(deviceId, "es") { _, args, _ ->
      commands += args
      outputs.removeFirst()
    }

    assertEquals(
      listOf(
        DeviceLocaleConfigurator.androidGetLocaleCommand(),
        DeviceLocaleConfigurator.androidIsEmulatorCommand(),
        DeviceLocaleConfigurator.androidEmulatorSetLocaleCommand("es"),
        DeviceLocaleConfigurator.androidGetPersistedLocaleCommand(),
      ),
      commands,
    )
  }

  @Test
  fun `Android locale apply does not use the root fallback on a physical device`() {
    val deviceId = TrailblazeDeviceId("physical-device", TrailblazeDevicePlatform.ANDROID)
    val commands = mutableListOf<List<String>>()
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "0", "unsupported", "en"))

    assertFailsWith<IllegalStateException> {
      DeviceLocaleConfigurator.applyAndroid(deviceId, "es") { _, args, _ ->
        commands += args
        outputs.removeFirst()
      }
    }

    assertFalse(commands.contains(DeviceLocaleConfigurator.androidEmulatorSetLocaleCommand("es")))
  }

  @Test
  fun `iOS locale apply writes verifies and restarts SpringBoard`() {
    val commands = mutableListOf<List<String>>()
    val results = ArrayDeque(
      listOf(
        SimctlCli.Result(0, "(\n    en\n)", ""),
        SimctlCli.Result(0, "", ""),
        SimctlCli.Result(0, "(\n    es\n)", ""),
        SimctlCli.Result(0, "", ""),
      ),
    )

    DeviceLocaleConfigurator.applyIosSimulator("SIM-UDID", "es") { _, command ->
      commands += command
      results.removeFirst()
    }

    assertEquals(
      listOf(
        DeviceLocaleConfigurator.iosGetLanguagesCommand(),
        DeviceLocaleConfigurator.iosSetLanguageCommand("es"),
        DeviceLocaleConfigurator.iosGetLanguagesCommand(),
        DeviceLocaleConfigurator.iosRestartSpringBoardCommand(),
      ),
      commands,
    )
  }

  @Test
  fun `iOS locale apply fails before restart when the language cannot be verified`() {
    val commands = mutableListOf<List<String>>()
    val results = ArrayDeque(
      listOf(
        SimctlCli.Result(0, "(\n    en\n)", ""),
        SimctlCli.Result(0, "", ""),
        SimctlCli.Result(0, "(\n    en\n)", ""),
      ),
    )

    assertFailsWith<IllegalStateException> {
      DeviceLocaleConfigurator.applyIosSimulator("SIM-UDID", "es") { _, command ->
        commands += command
        results.removeFirst()
      }
    }

    assertFalse(commands.contains(DeviceLocaleConfigurator.iosRestartSpringBoardCommand()))
  }
}
