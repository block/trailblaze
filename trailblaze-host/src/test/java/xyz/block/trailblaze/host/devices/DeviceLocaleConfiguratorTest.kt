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

  private val configEnglish = "config: mcc310-mnc260-en-rUS-ldltr-sw411dp-normal-long-notround-keysexposed-v35\nabi: arm64-v8a"
  private val configSpanish = "config: es-ldltr-sw411dp-normal-long-notround-keysexposed-v35\nabi: arm64-v8a"

  @Test
  fun `spells a locale the way the running configuration does`() {
    // Verified on an API 35 emulator: the legacy qualifier only for a two-letter language with at
    // most a two-letter region; the BCP-47 form joined with `+` for everything else.
    assertEquals("es", DeviceLocaleConfigurator.androidConfigLocaleToken("es"))
    assertEquals("pt-rBR", DeviceLocaleConfigurator.androidConfigLocaleToken("pt-BR"))
    assertEquals("b+zh+Hans", DeviceLocaleConfigurator.androidConfigLocaleToken("zh-Hans"))
    assertEquals("b+es+419", DeviceLocaleConfigurator.androidConfigLocaleToken("es-419"))
    assertEquals("b+fil", DeviceLocaleConfigurator.androidConfigLocaleToken("fil"))
    assertEquals("b+zh+Hans+CN", DeviceLocaleConfigurator.androidConfigLocaleToken("zh-Hans-CN"))
    // Several variants are joined with `_`, not `+`; Unicode extensions are dropped, and a tag
    // that was legacy before its extension stays legacy without it.
    assertEquals("b+sl+rozaj_biske", DeviceLocaleConfigurator.androidConfigLocaleToken("sl-rozaj-biske"))
    assertEquals("b+ca+ES+valencia", DeviceLocaleConfigurator.androidConfigLocaleToken("ca-ES-valencia"))
    assertEquals("es", DeviceLocaleConfigurator.androidConfigLocaleToken("es-u-nu-latn"))
    assertEquals("de-rDE", DeviceLocaleConfigurator.androidConfigLocaleToken("de-DE-u-co-phonebk"))
    assertEquals("b+zh+Hans", DeviceLocaleConfigurator.androidConfigLocaleToken("zh-Hans-u-ca-chinese"))
  }

  @Test
  fun `reads the locale out of the running configuration as a whole qualifier`() {
    assertTrue(DeviceLocaleConfigurator.androidConfigHasLocale(configSpanish, "es"))
    assertFalse(DeviceLocaleConfigurator.androidConfigHasLocale(configEnglish, "es"))
    // `es` appears inside `keysexposed`; a substring match would call every English device Spanish.
    assertFalse(DeviceLocaleConfigurator.androidConfigHasLocale("config: en-rUS-ldltr-keysexposed-v35", "es"))
    assertTrue(DeviceLocaleConfigurator.androidConfigHasLocale("config: mcc310-mnc260-pt-rBR-ldltr-v35", "pt-BR"))
    assertFalse(DeviceLocaleConfigurator.androidConfigHasLocale("config: mcc310-mnc260-pt-rBR-ldltr-v35", "pt-PT"))
    assertTrue(DeviceLocaleConfigurator.androidConfigHasLocale("config: b+zh+Hans-ldltr-v35", "zh-Hans"))
    assertFalse(DeviceLocaleConfigurator.androidConfigHasLocale("config: b+zh+Hans-ldltr-v35", "zh"))
    assertFalse(DeviceLocaleConfigurator.androidConfigHasLocale(null, "es"))
    assertFalse(DeviceLocaleConfigurator.androidConfigHasLocale("", "es"))
  }

  @Test
  fun `Android locale apply falls back on an emulator without locale commands`() {
    val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
    val commands = mutableListOf<List<String>>()
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "1", "en", "", configSpanish))

    DeviceLocaleConfigurator.applyAndroid(deviceId, "es") { _, args, _ ->
      commands += args
      outputs.removeFirst()
    }

    assertEquals(
      listOf(
        DeviceLocaleConfigurator.androidGetLocaleCommand(),
        DeviceLocaleConfigurator.androidIsEmulatorCommand(),
        DeviceLocaleConfigurator.androidGetPersistedLocaleCommand(),
        DeviceLocaleConfigurator.androidEmulatorSetLocaleCommand("es"),
        DeviceLocaleConfigurator.androidGetConfigCommand(),
      ),
      commands,
    )
  }

  @Test
  fun `Android emulator fallback is judged on the running configuration, not the property`() {
    // The fallback's own script can time out after its `setprop` and before the framework is back:
    // the property then reads `es` while the UI is still English. Only the configuration is proof.
    val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "1", "en", "", configEnglish))

    val failure = assertFailsWith<IllegalStateException> {
      DeviceLocaleConfigurator.applyAndroid(deviceId, "es") { _, _, _ -> outputs.removeFirst() }
    }

    assertTrue(failure.message.orEmpty().contains("running configuration"))
  }

  @Test
  fun `Android locale apply leaves an emulator the lane already booted in that language alone`() {
    // A CI lane switches its emulator into the lane's language right after boot. A trail then
    // asking for the same locale must find the match and issue no framework restart: the restart
    // is the fallback's whole cost.
    val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
    val commands = mutableListOf<List<String>>()
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "1", "es", configSpanish))

    DeviceLocaleConfigurator.applyAndroid(deviceId, "es") { _, args, _ ->
      commands += args
      outputs.removeFirst()
    }

    assertEquals(
      listOf(
        DeviceLocaleConfigurator.androidGetLocaleCommand(),
        DeviceLocaleConfigurator.androidIsEmulatorCommand(),
        DeviceLocaleConfigurator.androidGetPersistedLocaleCommand(),
        DeviceLocaleConfigurator.androidGetConfigCommand(),
      ),
      commands,
    )
    assertFalse(commands.contains(DeviceLocaleConfigurator.androidEmulatorSetLocaleCommand("es")))
  }

  @Test
  fun `a persisted locale the framework has not picked up still gets the restart`() {
    // persist.sys.locale=es but the framework started English: the property matches, the running
    // configuration does not, and trusting the property here would leave the trail on an English UI.
    val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
    val commands = mutableListOf<List<String>>()
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "1", "es", configEnglish, "", configSpanish))

    DeviceLocaleConfigurator.applyAndroid(deviceId, "es") { _, args, _ ->
      commands += args
      outputs.removeFirst()
    }

    assertTrue(commands.contains(DeviceLocaleConfigurator.androidEmulatorSetLocaleCommand("es")))
    assertEquals(DeviceLocaleConfigurator.androidGetConfigCommand(), commands.last())
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
