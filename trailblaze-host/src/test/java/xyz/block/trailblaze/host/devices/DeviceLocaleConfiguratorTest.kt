package xyz.block.trailblaze.host.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailDeviceLocale
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
      listOf("defaults", "write", "-g", "AppleLanguages", "-array", "es"),
      DeviceLocaleConfigurator.iosSetLanguageCommand("es"),
    )
    assertEquals(
      listOf("defaults", "read", "-g", "AppleLanguages"),
      DeviceLocaleConfigurator.iosGetLanguagesCommand(),
    )
    // The iOS 26 runtime has no `killall`; `launchctl` is one of the few programs it ships.
    assertEquals(listOf("launchctl", "stop", "com.apple.SpringBoard"), DeviceLocaleConfigurator.iosStopSpringBoardCommand())
  }

  @Test
  fun `accepts only the requested Android locale readback`() {
    assertTrue(DeviceLocaleConfigurator.localeReadbackMatches("fr-CA", "fr-ca\n"))
    assertFalse(DeviceLocaleConfigurator.localeReadbackMatches("fr-CA", "fr-FR\n"))
    assertFalse(DeviceLocaleConfigurator.localeReadbackMatches("fr-CA", null))
  }

  @Test
  fun `reads the first iOS language from defaults output`() {
    assertEquals(
      "fr-ca",
      DeviceLocaleConfigurator.iosFirstLanguage(
        """
          (
              "fr-ca",
              "en"
          )
        """.trimIndent(),
      ),
    )
    assertTrue(DeviceLocaleConfigurator.iosLanguageMatches("fr-CA", "fr-ca\n"))
    assertFalse(DeviceLocaleConfigurator.iosLanguageMatches("fr-CA", "en"))
    assertFalse(DeviceLocaleConfigurator.iosLanguageMatches("fr-CA", null))
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
    // The setter refused, so nothing changed: a plain failure, not a partial change.
    assertFalse(failure is TrailDeviceLocale.PartiallyAppliedException)
  }

  private val configEnglish = "config: mcc310-mnc260-en-rUS-ldltr-sw411dp-normal-long-notround-keysexposed-v35\nabi: arm64-v8a"
  private val configSpanish = "config: es-ldltr-sw411dp-normal-long-notround-keysexposed-v35\nabi: arm64-v8a"

  @Test
  fun `an Android setter that accepted the locale but reads back wrong counts as a partial change`() {
    val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
    val outputs = ArrayDeque(listOf("en", "", "en"))

    assertFailsWith<TrailDeviceLocale.PartiallyAppliedException> {
      DeviceLocaleConfigurator.applyAndroid(deviceId, "es") { _, _, _ -> outputs.removeFirst() }
    }
  }

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
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "1", configEnglish, "0", "", configSpanish))

    val changed = DeviceLocaleConfigurator.applyAndroid(deviceId, "es") { _, args, _ ->
      commands += args
      outputs.removeFirst()
    }

    assertTrue(changed)
    assertEquals(
      listOf(
        DeviceLocaleConfigurator.androidGetLocaleCommand(),
        DeviceLocaleConfigurator.androidIsEmulatorCommand(),
        DeviceLocaleConfigurator.androidGetConfigCommand(),
        DeviceLocaleConfigurator.androidRootCheckCommand(),
        DeviceLocaleConfigurator.androidEmulatorSetLocaleCommand("es"),
        DeviceLocaleConfigurator.androidGetConfigCommand(),
      ),
      commands,
    )
  }

  @Test
  fun `the emulator fallback writes the system_locales setting before it restarts the framework`() {
    // A framework that restarts prefers system_locales over persist.sys.locale, and the on-device
    // path writes it, so writing only the property would bring the framework back in the old one.
    val script = DeviceLocaleConfigurator.androidEmulatorSetLocaleCommand("es").last()

    assertTrue(script.contains("settings put system system_locales es"))
    assertTrue(script.indexOf("system_locales") < script.indexOf("stop"))
  }

  @Test
  fun `Android emulator fallback is judged on the running configuration, not the property`() {
    // The fallback's own script can time out after its `setprop` and before the framework is back:
    // the property then reads `es` while the UI is still English. Only the configuration is proof.
    val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "1", configEnglish, "0", "", configEnglish))

    // Partial, not plain failure: the framework restarted either way, so the app process is gone.
    val failure = assertFailsWith<TrailDeviceLocale.PartiallyAppliedException> {
      DeviceLocaleConfigurator.applyAndroid(deviceId, "es") { _, _, _ -> outputs.removeFirst() }
    }

    assertTrue(failure.message.orEmpty().contains("running configuration"))
  }

  @Test
  fun `an emulator without root is left untouched and its failure is not a change`() {
    val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
    val commands = mutableListOf<List<String>>()
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "1", configSpanish, "/system/bin/sh: su: not found"))
    val apply = {
      DeviceLocaleConfigurator.applyAndroid(deviceId, "en-US") { _, args, _ ->
        commands += args
        outputs.removeFirst()
      }
    }

    // Plain failure, so the default's best effort counts it as no change and keeps the app running.
    assertFalse(TrailDeviceLocale.applyBestEffort(TrailDeviceLocale.forTrail(null), "device") { apply() })
    assertFalse(commands.contains(DeviceLocaleConfigurator.androidEmulatorSetLocaleCommand("en-US")))
  }

  @Test
  fun `Android locale apply leaves an emulator the lane already booted in that language alone`() {
    // A CI lane switches its emulator into the lane's language right after boot. A trail then
    // asking for the same locale must find the match and issue no framework restart: the restart
    // is the fallback's whole cost.
    val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
    val commands = mutableListOf<List<String>>()
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "1", configSpanish))

    val changed = DeviceLocaleConfigurator.applyAndroid(deviceId, "es") { _, args, _ ->
      commands += args
      outputs.removeFirst()
    }

    assertFalse(changed)
    assertEquals(
      listOf(
        DeviceLocaleConfigurator.androidGetLocaleCommand(),
        DeviceLocaleConfigurator.androidIsEmulatorCommand(),
        DeviceLocaleConfigurator.androidGetConfigCommand(),
      ),
      commands,
    )
    assertFalse(commands.contains(DeviceLocaleConfigurator.androidEmulatorSetLocaleCommand("es")))
  }

  @Test
  fun `a fresh emulator already running the default gets no framework restart`() {
    // A fresh API 34 image runs en-US with persist.sys.locale empty. Every trail that declares no
    // locale asks for en-US, so judging by the property would restart the framework every time.
    val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
    val commands = mutableListOf<List<String>>()
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "1", configEnglish))

    val changed = DeviceLocaleConfigurator.applyAndroid(deviceId, "en-US") { _, args, _ ->
      commands += args
      outputs.removeFirst()
    }

    assertFalse(changed)
    assertFalse(commands.contains(DeviceLocaleConfigurator.androidEmulatorSetLocaleCommand("en-US")))
  }

  @Test
  fun `a locale the framework is not running still gets the restart`() {
    // persist.sys.locale may already read es while the framework started English; only the running
    // configuration says what the UI is in.
    val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
    val commands = mutableListOf<List<String>>()
    val outputs = ArrayDeque(listOf("Unknown command: get-device-locale", "1", configEnglish, "0", "", configSpanish))

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

  /**
   * A simulator whose language lives in [languages] and whose SpringBoard restarts as a real one
   * does: the old process is gone at once, and the new one posts `finishedstartup` only after
   * [pollsUntilStarted] reads of the notification.
   */
  private class FakeSimulator(
    var language: String?,
    var springBoardPid: Long? = 500L,
    val pollsUntilStarted: Int = 2,
    val applyWrites: Boolean = true,
    val stopSucceeds: Boolean = true,
    var killsInterrupted: Int = 0,
  ) : DeviceLocaleConfigurator.IosSimulatorHost {
    val events = mutableListOf<String>()
    private var startedPid: Long = springBoardPid ?: 400L
    private var restarted = false
    private var polls = 0
    private var now = 0L

    override fun spawn(udid: String, command: List<String>): SimctlCli.Result {
      events += command.joinToString(" ")
      return when (command) {
        DeviceLocaleConfigurator.iosSpringBoardStartedCommand() -> {
          if (restarted && ++polls >= pollsUntilStarted) startedPid = 900L
          SimctlCli.Result(0, "com.apple.springboard.finishedstartup $startedPid\n", "")
        }
        DeviceLocaleConfigurator.iosStopSpringBoardCommand() -> {
          restarted = stopSucceeds
          SimctlCli.Result(if (stopSucceeds) 0 else 1, "", "")
        }
        else -> {
          if (applyWrites) language = command.last()
          SimctlCli.Result(0, "", "")
        }
      }
    }

    override fun primaryLanguage(udid: String): String? = language.also { events += "read" }

    override fun springBoardPid(udid: String): Long? = springBoardPid

    override fun terminate(pid: Long): Boolean {
      if (killsInterrupted-- > 0) throw InterruptedException()
      events += "kill $pid"
      restarted = true
      return true
    }

    override fun nanoTime(): Long = now

    override fun sleep(ms: Long) {
      now += ms * 1_000_000
    }
  }

  @Test
  fun `iOS locale apply with the language already set reads the host file and nothing else`() {
    val simulator = FakeSimulator(language = "en-US")

    assertFalse(DeviceLocaleConfigurator.applyIosSimulator("SIM-UDID", "en-US", simulator))

    assertEquals(listOf("read"), simulator.events)
  }

  @Test
  fun `iOS locale apply writes, verifies, restarts this SpringBoard and waits for the new one`() {
    val simulator = FakeSimulator(language = "en-US")

    assertTrue(DeviceLocaleConfigurator.applyIosSimulator("SIM-UDID", "es", simulator))

    assertEquals(
      listOf(
        "read",
        "defaults write -g AppleLanguages -array es",
        "read",
        "kill 500",
        "notifyutil -g com.apple.springboard.finishedstartup",
        "notifyutil -g com.apple.springboard.finishedstartup",
      ),
      simulator.events,
    )
  }

  @Test
  fun `iOS locale apply restarts SpringBoard inside the simulator when the host cannot find it`() {
    val simulator = FakeSimulator(language = "en-US", springBoardPid = null)

    assertTrue(DeviceLocaleConfigurator.applyIosSimulator("SIM-UDID", "es", simulator))

    val restart = simulator.events.drop(3)
    // The state before the stop is what the new SpringBoard's post must differ from.
    assertEquals("notifyutil -g com.apple.springboard.finishedstartup", restart.first())
    assertEquals("launchctl stop com.apple.SpringBoard", restart[1])
    assertFalse(restart.any { it.startsWith("kill") })
  }

  @Test
  fun `an iOS write that cannot be verified counts as partial and restores the old language`() {
    val simulator = FakeSimulator(language = "en-US", applyWrites = false)

    // The write went through, so a retry must not find the language set and skip the restart.
    val failure = assertFailsWith<TrailDeviceLocale.PartiallyAppliedException> {
      DeviceLocaleConfigurator.applyIosSimulator("SIM-UDID", "es", simulator)
    }

    assertTrue(failure.message.orEmpty().contains("reads `en-US`"))
    assertTrue(failure.message.orEmpty().contains("Restored `en-US`"))
    assertFalse(simulator.events.any { it.startsWith("kill") })
  }

  @Test
  fun `an iOS language change still counts when SpringBoard is slow to say it is back`() {
    val simulator = FakeSimulator(language = "en-US", pollsUntilStarted = Int.MAX_VALUE)

    assertTrue(DeviceLocaleConfigurator.applyIosSimulator("SIM-UDID", "es", simulator))
    assertTrue(simulator.events.any { it.startsWith("kill") })
  }

  @Test
  fun `an iOS language written but never shown is put back, so the next attempt restarts again`() {
    val simulator = FakeSimulator(language = "en-US", springBoardPid = null, stopSucceeds = false)

    val failure = assertFailsWith<TrailDeviceLocale.PartiallyAppliedException> {
      DeviceLocaleConfigurator.applyIosSimulator("SIM-UDID", "es", simulator)
    }

    assertTrue(failure.message.orEmpty().contains("Restored `en-US`"))
    assertEquals("en-US", simulator.language)
  }

  @Test
  fun `SpringBoard counts as back only once a different process posted that it started`() {
    assertFalse(DeviceLocaleConfigurator.springBoardRestarted(previous = 500, startedPid = 500))
    assertFalse(DeviceLocaleConfigurator.springBoardRestarted(previous = 500, startedPid = 0))
    assertFalse(DeviceLocaleConfigurator.springBoardRestarted(previous = 500, startedPid = null))
    assertTrue(DeviceLocaleConfigurator.springBoardRestarted(previous = 500, startedPid = 900))
    assertEquals(
      77807L,
      DeviceLocaleConfigurator.springBoardStartedPid("com.apple.springboard.finishedstartup 77807\n"),
    )
    assertNull(DeviceLocaleConfigurator.springBoardStartedPid(""))
  }

  @Test
  fun `finds this simulator's SpringBoard through its own launchd_sim, never by name alone`() {
    assertEquals(
      listOf("pgrep", "-f", "launchd_sim .*SIM-UDID"),
      DeviceLocaleConfigurator.launchdSimPgrepCommand("SIM-UDID"),
    )
    assertEquals(
      listOf("pgrep", "-P", "4242", "-f", "SpringBoard.app/SpringBoard$"),
      DeviceLocaleConfigurator.springBoardPgrepCommand(4242),
    )
    assertEquals(
      "/Users/me/Library/Developer/CoreSimulator/Devices/SIM-UDID/data/Library/Preferences/.GlobalPreferences.plist",
      DeviceLocaleConfigurator.iosGlobalPreferencesFile("SIM-UDID", "/Users/me").path,
    )
  }

  @Test
  fun `a trail with no locale applies the default, best effort`() {
    val web = TrailblazeDeviceId("chrome", TrailblazeDevicePlatform.WEB)
    // Web has no device language: the default is skipped rather than refused.
    assertFalse(DeviceLocaleConfigurator.applyForTrail(web, TrailDeviceLocale.forTrail(null)))
    assertFailsWith<IllegalStateException> {
      DeviceLocaleConfigurator.applyForTrail(web, TrailDeviceLocale.forTrail("es"))
    }
    assertEquals(TrailDeviceLocale.Request("en-US", declared = false), TrailDeviceLocale.forTrail(null))
    assertFalse(TrailDeviceLocale.applyBestEffort(TrailDeviceLocale.forTrail(null), "device") { error("no command") })
    // A default that half-applied already disturbed the device, so it counts as a change.
    assertTrue(
      TrailDeviceLocale.applyBestEffort(TrailDeviceLocale.forTrail(null), "device") {
        throw TrailDeviceLocale.PartiallyAppliedException("restarted, language did not take")
      },
    )
    // A cancel landing mid-switch stops the trail instead of passing for a failed default.
    assertFailsWith<InterruptedException> {
      TrailDeviceLocale.applyBestEffort(TrailDeviceLocale.forTrail(null), "device") { throw InterruptedException() }
    }
    Thread.interrupted()
    assertFailsWith<IllegalStateException> {
      TrailDeviceLocale.applyBestEffort(TrailDeviceLocale.forTrail("es"), "device") { error("no command") }
    }
  }

  @Test
  fun `an interrupted adb read stops before the setter runs`() {
    val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
    val commands = mutableListOf<List<String>>()

    try {
      assertFailsWith<InterruptedException> {
        DeviceLocaleConfigurator.applyAndroid(deviceId, "es") { _, args, _ ->
          commands += args
          // What the adb helper does when its wait is interrupted.
          Thread.currentThread().interrupt()
          null
        }
      }
    } finally {
      Thread.interrupted()
    }
    assertEquals(listOf(DeviceLocaleConfigurator.androidGetLocaleCommand()), commands)
  }

  @Test
  fun `a switch a cancel cut short counts as a change on the next run`() {
    val deviceId = TrailblazeDeviceId("cut-short-switch", TrailblazeDevicePlatform.IOS)
    val request = TrailDeviceLocale.forTrail("es")

    assertFailsWith<InterruptedException> {
      DeviceLocaleConfigurator.applyForTrail(deviceId, request) { throw InterruptedException() }
    }
    Thread.interrupted()

    // The language took before the cancel, so the device now reads as already in `es`.
    assertTrue(DeviceLocaleConfigurator.applyForTrail(deviceId, request) { false })
    DeviceLocaleConfigurator.cleanupDone(deviceId)
    assertFalse(DeviceLocaleConfigurator.applyForTrail(deviceId, request) { false })
  }

  @Test
  fun `a change counts again until its app restart is confirmed`() {
    val deviceId = TrailblazeDeviceId("restart-never-confirmed", TrailblazeDevicePlatform.ANDROID)
    val request = TrailDeviceLocale.forTrail("es")

    assertTrue(DeviceLocaleConfigurator.applyForTrail(deviceId, request) { true })
    // That run failed before restarting the app; the retry finds the device already in `es`.
    assertTrue(DeviceLocaleConfigurator.applyForTrail(deviceId, request) { false })
    DeviceLocaleConfigurator.cleanupDone(deviceId)
    assertFalse(DeviceLocaleConfigurator.applyForTrail(deviceId, request) { false })
  }

  @Test
  fun `a declared locale left half done counts as a change on the retry`() {
    val deviceId = TrailblazeDeviceId("declared-half-done", TrailblazeDevicePlatform.ANDROID)
    val request = TrailDeviceLocale.forTrail("es")

    assertFailsWith<TrailDeviceLocale.PartiallyAppliedException> {
      DeviceLocaleConfigurator.applyForTrail(deviceId, request) {
        throw TrailDeviceLocale.PartiallyAppliedException("readback disagreed")
      }
    }
    assertTrue(DeviceLocaleConfigurator.applyForTrail(deviceId, request) { false })
  }

  @Test
  fun `a default left half done is told apart from a finished change`() {
    val request = TrailDeviceLocale.forTrail(null)
    assertEquals(
      TrailDeviceLocale.Outcome.PARTIAL,
      TrailDeviceLocale.applyWithOutcome(request, "test device") {
        throw TrailDeviceLocale.PartiallyAppliedException("readback disagreed")
      },
    )
    assertEquals(TrailDeviceLocale.Outcome.CHANGED, TrailDeviceLocale.applyWithOutcome(request, "test device") { true })
    assertEquals(TrailDeviceLocale.Outcome.UNCHANGED, TrailDeviceLocale.applyWithOutcome(request, "test device") { false })
  }

  @Test
  fun `a cancel between the iOS language write and the SpringBoard restart leaves the restart owed`() {
    val simulator = FakeSimulator(language = "en-US", killsInterrupted = 1)

    assertFailsWith<InterruptedException> {
      DeviceLocaleConfigurator.applyIosSimulator("CANCELLED-SIM", "es", simulator)
    }
    assertEquals("es", simulator.language)

    // The language reads as set, but SpringBoard still shows English.
    assertTrue(DeviceLocaleConfigurator.applyIosSimulator("CANCELLED-SIM", "es", simulator))
    assertTrue(simulator.events.any { it.startsWith("kill") })
    assertFalse(DeviceLocaleConfigurator.applyIosSimulator("CANCELLED-SIM", "es", simulator))
  }
}
