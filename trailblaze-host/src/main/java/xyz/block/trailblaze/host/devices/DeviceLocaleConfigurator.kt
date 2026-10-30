package xyz.block.trailblaze.host.devices

import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.android.tools.shellEscape
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailDeviceLocale
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.ios.SimctlCli
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console

/** Applies a trail's resolved per-device locale before the target app is launched. */
internal object DeviceLocaleConfigurator {

  private const val ANDROID_LOCALE_COMMAND_TIMEOUT_MS = 10_000L
  private const val ANDROID_EMULATOR_RESTART_TIMEOUT_MS = 90_000L
  private val BCP_47_TAG = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{1,8})*")

  /**
   * Applies the language a trail runs in ([TrailDeviceLocale.forTrail]: its resolved `locale:`, or
   * the default when it declares none), so a device a localized trail switched does not carry that
   * language into the next trail. Returns whether the device's language changed, so the caller
   * restarts the target app only then. Web and desktop have no device language: the default is
   * skipped there, and a declared locale is rejected by [apply].
   */
  fun applyForTrail(deviceId: TrailblazeDeviceId, request: TrailDeviceLocale.Request): Boolean =
    applyForTrail(deviceId, request) { apply(deviceId, it) }

  /**
   * The host side of [TrailDeviceLocale.changeNeedingCleanup]: true also when an earlier change's
   * app restart was never confirmed. The caller restarts the app, then calls [cleanupDone].
   */
  internal fun applyForTrail(
    deviceId: TrailblazeDeviceId,
    request: TrailDeviceLocale.Request,
    applyLocale: (String) -> Boolean,
  ): Boolean {
    val hasDeviceLanguage = when (deviceId.trailblazeDevicePlatform) {
      TrailblazeDevicePlatform.ANDROID, TrailblazeDevicePlatform.IOS -> true
      TrailblazeDevicePlatform.WEB, TrailblazeDevicePlatform.DESKTOP -> false
    }
    if (!request.declared && !hasDeviceLanguage) return false
    return TrailDeviceLocale.changeNeedingCleanup(deviceId.toString()) {
      TrailDeviceLocale.applyBestEffort(
        request = request,
        deviceLabel = "${deviceId.trailblazeDevicePlatform.displayName} device ${deviceId.instanceId}",
        apply = applyLocale,
      )
    }
  }

  /** The target app on [deviceId] was restarted after a change [applyForTrail] reported. */
  fun cleanupDone(deviceId: TrailblazeDeviceId) = TrailDeviceLocale.cleanupDone(deviceId.toString())

  /** Puts the device in [configuredLocale] and returns whether that changed its language. */
  fun apply(deviceId: TrailblazeDeviceId, configuredLocale: String): Boolean {
    val locale = normalizedLocale(configuredLocale)
    return when (deviceId.trailblazeDevicePlatform) {
      TrailblazeDevicePlatform.ANDROID -> applyAndroid(deviceId, locale)
      TrailblazeDevicePlatform.IOS -> applyIosSimulator(deviceId.instanceId, locale)
      TrailblazeDevicePlatform.WEB, TrailblazeDevicePlatform.DESKTOP ->
        error("Device locale is only supported for Android devices and iOS Simulators, not ${deviceId.trailblazeDevicePlatform}.")
    }
  }

  /** Normalizes and validates the BCP-47 tag before it is put on a device command line. */
  internal fun normalizedLocale(value: String): String {
    require(BCP_47_TAG.matches(value)) {
      "Device locale must be a BCP-47 language tag such as `es` or `fr-CA`, but was `$value`."
    }
    val locale = Locale.forLanguageTag(value)
    require(locale.language.isNotBlank() && locale.language != "und") {
      "Device locale must be a BCP-47 language tag such as `es` or `fr-CA`, but was `$value`."
    }
    return locale.toLanguageTag()
  }

  internal fun androidSetLocaleCommand(locale: String): List<String> =
    listOf("cmd", "locale", "set-device-locale", locale)

  internal fun androidGetLocaleCommand(): List<String> = listOf("cmd", "locale", "get-device-locale")

  internal fun androidIsEmulatorCommand(): List<String> = listOf("getprop", "ro.kernel.qemu")

  /** The running framework's configuration; the only readback of what the UI is actually in. */
  internal fun androidGetConfigCommand(): List<String> = listOf("cmd", "activity", "get-config")

  /**
   * How `cmd activity get-config` spells [locale], mirroring
   * `Configuration.localesToResourceQualifier`. The legacy qualifier (`es`, `pt-rBR`) is used only
   * for a two-letter language with no script or variant and at most a two-letter region. Anything
   * else is `b+` followed by the components joined with `+`, where Java already joins several
   * variants with `_` (`sl-rozaj-biske` -> `b+sl+rozaj_biske`). Unicode extensions are not
   * components, so they fall away as they do on the device (`es-u-nu-latn` -> `es`,
   * `de-DE-u-co-phonebk` -> `de-rDE`). Verified on an API 35 emulator.
   */
  internal fun androidConfigLocaleToken(locale: String): String {
    val parsed = Locale.forLanguageTag(locale)
    val legacy =
      parsed.language.length == 2 &&
        parsed.script.isEmpty() &&
        parsed.variant.isEmpty() &&
        (parsed.country.isEmpty() || parsed.country.length == 2)
    return when {
      !legacy ->
        listOf(parsed.language, parsed.script, parsed.country, parsed.variant)
          .filter { it.isNotEmpty() }
          .joinToString(separator = "+", prefix = "b+")
      parsed.country.isEmpty() -> parsed.language
      else -> "${parsed.language}-r${parsed.country}"
    }
  }

  /**
   * True when the `config:` line of `cmd activity get-config` output carries [locale]. Qualifiers
   * are joined by `-`, so the locale must sit between two of them; `es` inside `keysexposed` and
   * `zh` inside `b+zh+Hans` do not count.
   */
  internal fun androidConfigHasLocale(configOutput: String?, locale: String): Boolean {
    val line =
      configOutput
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("config:") }
        ?.removePrefix("config:")
        ?.trim()
        ?: return false
    return "-$line-".contains("-${androidConfigLocaleToken(locale)}-")
  }

  /**
   * The root fallback for an emulator without `cmd locale set-device-locale`: restarts the
   * framework in [locale]. A started framework takes its language from the `system_locales`
   * setting when one is set (the on-device path writes it) and from `persist.sys.locale`
   * otherwise, so both are written. The setting reaches disk up to two seconds after the write,
   * and a framework stopped before then starts in the old language, hence the pause. It then waits
   * for the running configuration to carry [locale]: a framework still starting prints a `config:`
   * line with no locale in it yet.
   */
  internal fun androidEmulatorSetLocaleCommand(locale: String): List<String> {
    val script =
      "settings put system system_locales $locale; setprop persist.sys.locale $locale; sleep 3; " +
        "stop; sleep 5; start; " +
        "i=0; until cmd activity get-config 2>/dev/null | grep '^config:' | " +
        "sed 's/^config: */-/; s/\$/-/' | grep -qF -- '-${androidConfigLocaleToken(locale)}-'; do " +
        "i=\$((i + 1)); [ \"\$i\" -ge 60 ] && exit 1; sleep 1; done"
    return listOf("su", "0", "sh", "-c", script.shellEscape())
  }

  /** Prints `0` when the fallback's `su 0` can run; anything else means it would touch nothing. */
  internal fun androidRootCheckCommand(): List<String> = listOf("su", "0", "id", "-u")

  internal fun iosSetLanguageCommand(locale: String): List<String> =
    listOf("defaults", "write", "-g", "AppleLanguages", "-array", locale)

  internal fun iosGetLanguagesCommand(): List<String> = listOf("defaults", "read", "-g", "AppleLanguages")

  internal fun localeReadbackMatches(expected: String, actualOutput: String?): Boolean =
    actualOutput?.trim()?.equals(expected, ignoreCase = true) == true

  internal fun androidLocaleCommandUnavailable(output: String?): Boolean =
    output?.contains("unknown command", ignoreCase = true) == true

  /** The first language in `defaults read -g AppleLanguages` output, or null when it lists none. */
  internal fun iosFirstLanguage(defaultsOutput: String): String? =
    defaultsOutput
      .lineSequence()
      .map { it.trim().trim('(', ')', ',', '"') }
      .firstOrNull { it.isNotBlank() }

  internal fun applyAndroid(
    deviceId: TrailblazeDeviceId,
    locale: String,
    adbShell: (TrailblazeDeviceId, List<String>, Long) -> String? = { id, args, timeoutMs ->
      AndroidHostAdbUtils.execAdbShellCommandWithTimeout(id, args, timeoutMs)
    },
  ): Boolean {
    // The adb helper answers an interrupt with null and the flag set. Read as a mismatch, that
    // would send the setter after the run was cancelled, so an interrupt stops here instead.
    val exec = { id: TrailblazeDeviceId, args: List<String>, timeoutMs: Long ->
      if (Thread.currentThread().isInterrupted) throw InterruptedException("Device locale apply was interrupted.")
      adbShell(id, args, timeoutMs).also {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Device locale apply was interrupted.")
      }
    }
    val currentLocale = exec(deviceId, androidGetLocaleCommand(), ANDROID_LOCALE_COMMAND_TIMEOUT_MS)
    if (localeReadbackMatches(locale, currentLocale)) return false

    if (androidLocaleCommandUnavailable(currentLocale)) {
      val emulator = exec(deviceId, androidIsEmulatorCommand(), ANDROID_LOCALE_COMMAND_TIMEOUT_MS)
      if (emulator?.trim() == "1") {
        // An emulator already running in this language needs nothing, and the fallback below must
        // not restart its framework for nothing: a fresh image runs en-US with persist.sys.locale
        // empty, and every trail that declares no locale asks for en-US. The persisted property is
        // no proof either way — it can hold the new value while the framework still runs the old
        // one (a restart that never completed) — so the running configuration is what decides,
        // here and after the restart.
        val configBefore = exec(deviceId, androidGetConfigCommand(), ANDROID_LOCALE_COMMAND_TIMEOUT_MS)
        if (androidConfigHasLocale(configBefore, locale)) return false
        // Without root the fallback never reaches the device, so this stays a plain failure.
        val rootUid = exec(deviceId, androidRootCheckCommand(), ANDROID_LOCALE_COMMAND_TIMEOUT_MS)
        check(rootUid?.trim() == "0") {
          "Could not apply device locale `$locale` to Android emulator `${deviceId.instanceId}`: it " +
            "has no `cmd locale set-device-locale` and no root for the persist.sys.locale fallback " +
            "(`su 0 id -u` printed `${rootUid?.trim().orEmpty()}`)."
        }
        val fallbackOutput =
          exec(
            deviceId,
            androidEmulatorSetLocaleCommand(locale),
            ANDROID_EMULATOR_RESTART_TIMEOUT_MS,
          )
        val configAfter = exec(deviceId, androidGetConfigCommand(), ANDROID_LOCALE_COMMAND_TIMEOUT_MS)
        // The fallback restarted the framework whether or not the language took, so a failure here
        // is partial: the app process a trailhead handed on is gone either way.
        if (!androidConfigHasLocale(configAfter, locale)) {
          throw TrailDeviceLocale.PartiallyAppliedException(
            "Could not apply device locale `$locale` to Android emulator `${deviceId.instanceId}` " +
              "with the persist.sys.locale fallback; output: ${fallbackOutput?.trim().orEmpty()}, " +
              "running configuration: ${configAfter?.trim().orEmpty()}.",
          )
        }
        Console.log("[device-locale] set Android emulator ${deviceId.instanceId} to $locale")
        return true
      }
    }

    val setOutput = exec(deviceId, androidSetLocaleCommand(locale), ANDROID_LOCALE_COMMAND_TIMEOUT_MS)
    val updatedLocale = exec(deviceId, androidGetLocaleCommand(), ANDROID_LOCALE_COMMAND_TIMEOUT_MS)
    if (!localeReadbackMatches(locale, updatedLocale)) {
      val message =
        "Could not apply device locale `$locale` to Android device `${deviceId.instanceId}`. " +
          "This requires the Android 16 `cmd locale set-device-locale` command; setter output: " +
          "${setOutput?.trim().orEmpty()}, readback: ${updatedLocale?.trim().orEmpty()}."
      // The setter prints only when it refuses. A silent one (or one that timed out) may have
      // changed the language even though the readback disagrees, so that counts as partial.
      if (!setOutput.isNullOrBlank()) error(message)
      throw TrailDeviceLocale.PartiallyAppliedException(message)
    }
    Console.log("[device-locale] set Android device ${deviceId.instanceId} to $locale")
    return true
  }

  /**
   * Puts the simulator's system language to [locale] and returns whether it changed anything.
   *
   * The language is read on the host, from the device's global preferences file, which takes
   * milliseconds where a `simctl spawn defaults read` takes over half a second; it reflects an
   * in-simulator `defaults write` at once. The write itself stays inside the simulator: a file
   * written from the host while the device is booted is not picked up. SpringBoard reads the
   * language when it starts, so a change restarts it and waits until it is back (see
   * [restartSpringBoard]).
   */
  internal fun applyIosSimulator(
    udid: String,
    locale: String,
    simulator: IosSimulatorHost = IosSimulatorHost.Live,
  ): Boolean {
    val previousLanguage = simulator.primaryLanguage(udid)
    val restartOwed = udid in springBoardRestartOwed
    if (iosLanguageMatches(locale, previousLanguage) && !restartOwed) return false
    val startedAt = simulator.nanoTime()

    // From the write on, a failure is partial: the language may have changed while SpringBoard and
    // the app still show the old one. Put the old one back, or the next attempt would find the
    // language already set and skip the restart for good.
    fun partial(reason: String): Nothing {
      val restored = previousLanguage?.let { simulator.spawn(udid, iosSetLanguageCommand(it)).success } == true
      // Back in the language SpringBoard is showing, so nothing is owed, unless it already was.
      if (restored && !restartOwed) springBoardRestartOwed.remove(udid)
      throw TrailDeviceLocale.PartiallyAppliedException(
        "$reason " + if (restored) "Restored `$previousLanguage`." else "Could not restore the previous language.",
      )
    }
    if (!iosLanguageMatches(locale, previousLanguage)) {
      // Owed until SpringBoard is back, so a cancel before then leaves the restart to the next run.
      springBoardRestartOwed.add(udid)
      val setLanguage = simulator.spawn(udid, iosSetLanguageCommand(locale))
      check(setLanguage.success) {
        "Could not apply device locale `$locale` to iOS Simulator `$udid`: " +
          setLanguage.stderr.trim().ifBlank { "exit ${setLanguage.exitCode}" }
      }
      val updatedLanguage = simulator.primaryLanguage(udid)
      if (!iosLanguageMatches(locale, updatedLanguage)) {
        partial(
          "Could not verify device locale `$locale` on iOS Simulator `$udid`: the first preferred " +
            "language reads `${updatedLanguage.orEmpty()}`.",
        )
      }
    }
    val springBoardBackMs =
      try {
        restartSpringBoard(udid, simulator)
      } catch (e: IllegalStateException) {
        partial(e.message.orEmpty())
      }
    springBoardRestartOwed.remove(udid)
    val springBoard =
      springBoardBackMs?.let { "SpringBoard back after $it ms" }
        ?: ("SpringBoard not confirmed back within ${SPRINGBOARD_RESTART_TIMEOUT_MS / 1000} s; the next " +
          "launch waits for it")
    Console.log(
      "[device-locale] set iOS Simulator $udid to $locale in " +
        "${(simulator.nanoTime() - startedAt) / 1_000_000} ms ($springBoard)",
    )
    return true
  }

  /**
   * Simulators whose language was written but whose SpringBoard has not been restarted since,
   * because the switch failed or was cancelled in between. The language reads as set, but the
   * screen still shows the old one, so the next switch to it restarts SpringBoard anyway.
   */
  private val springBoardRestartOwed: MutableSet<String> = ConcurrentHashMap.newKeySet()

  internal fun iosLanguageMatches(expected: String, actual: String?): Boolean =
    actual?.trim()?.equals(expected, ignoreCase = true) == true

  /**
   * Restarts this simulator's SpringBoard and returns once the new one has finished starting, in
   * milliseconds, or null when it has not said so within [SPRINGBOARD_RESTART_TIMEOUT_MS]. Only this device's SpringBoard is touched: every booted simulator's SpringBoard
   * is a process on the Mac, so it is found as a child of the `launchd_sim` that runs this UDID,
   * never by name. Its `launchd_sim` starts a new one. When the process cannot be found from the
   * host, `launchctl stop` inside the simulator restarts it instead (about half a second slower).
   *
   * Until the new SpringBoard is up the screen is black, and `simctl launch`, `openurl` and AXe
   * block on it rather than fail, so a launch right after the restart takes about as long overall
   * as waiting here first. Waiting here keeps the device usable for anything a trailhead does next.
   * A wait that runs out does not fail the trail: the language is already written and verified,
   * and on a heavily loaded Mac SpringBoard can take longer than the limit and still come back. The
   * signal is SpringBoard's own `com.apple.springboard.finishedstartup` notification, whose state
   * is the process id of the SpringBoard that posted it.
   */
  internal fun restartSpringBoard(udid: String, simulator: IosSimulatorHost): Long? {
    val startedAt = simulator.nanoTime()
    val hostPid = simulator.springBoardPid(udid)
    val previous: Long
    if (hostPid != null && simulator.terminate(hostPid)) {
      previous = hostPid
    } else {
      previous = springBoardStartedPid(simulator.spawn(udid, iosSpringBoardStartedCommand()).stdout) ?: 0L
      val stop = simulator.spawn(udid, iosStopSpringBoardCommand())
      check(stop.success) {
        "Applied a device locale to iOS Simulator `$udid`, but could not restart SpringBoard: " +
          stop.stderr.trim().ifBlank { "exit ${stop.exitCode}" }
      }
    }
    val deadline = startedAt + SPRINGBOARD_RESTART_TIMEOUT_MS * 1_000_000
    while (true) {
      val started = springBoardStartedPid(simulator.spawn(udid, iosSpringBoardStartedCommand()).stdout)
      if (springBoardRestarted(previous, started)) return (simulator.nanoTime() - startedAt) / 1_000_000
      if (simulator.nanoTime() >= deadline) return null
      simulator.sleep(SPRINGBOARD_POLL_INTERVAL_MS)
    }
  }

  private const val SPRINGBOARD_RESTART_TIMEOUT_MS = 30_000L
  private const val SPRINGBOARD_POLL_INTERVAL_MS = 100L

  /** True once a SpringBoard other than [previous] has posted that it finished starting. */
  internal fun springBoardRestarted(previous: Long, startedPid: Long?): Boolean =
    startedPid != null && startedPid != 0L && startedPid != previous

  internal fun iosSpringBoardStartedCommand(): List<String> =
    listOf("notifyutil", "-g", "com.apple.springboard.finishedstartup")

  internal fun iosStopSpringBoardCommand(): List<String> = listOf("launchctl", "stop", "com.apple.SpringBoard")

  /** The state of `notifyutil -g <name>` output (`<name> <state>`), or null when there is none. */
  internal fun springBoardStartedPid(notifyutilOutput: String): Long? =
    notifyutilOutput.trim().substringAfterLast(' ', "").toLongOrNull()

  /** The `pgrep` that finds the `launchd_sim` running [udid]: its arguments contain the device path. */
  internal fun launchdSimPgrepCommand(udid: String): List<String> = listOf("pgrep", "-f", "launchd_sim .*$udid")

  /** The `pgrep` that finds the SpringBoard among [launchdSimPid]'s children. */
  internal fun springBoardPgrepCommand(launchdSimPid: Long): List<String> =
    listOf("pgrep", "-P", launchdSimPid.toString(), "-f", "SpringBoard.app/SpringBoard$")

  /** This UDID's global preferences file in the default simulator device set. */
  internal fun iosGlobalPreferencesFile(udid: String, userHome: String): File =
    File(userHome, "Library/Developer/CoreSimulator/Devices/$udid/data/Library/Preferences/.GlobalPreferences.plist")

  internal fun iosPrimaryLanguageFromPlistCommand(file: File): List<String> =
    listOf("plutil", "-extract", "AppleLanguages.0", "raw", "-o", "-", file.path)

  /** The simulator as [applyIosSimulator] sees it, so tests can stand in for the Mac and the device. */
  internal interface IosSimulatorHost {
    fun spawn(udid: String, command: List<String>): SimctlCli.Result

    /**
     * The simulator's first preferred language, read from the host where the preferences file is
     * readable, else with `defaults read` inside the simulator; null when neither answers.
     */
    fun primaryLanguage(udid: String): String?

    /** The process id of [udid]'s SpringBoard, or null when the host cannot find it. */
    fun springBoardPid(udid: String): Long?

    /** Sends SIGTERM to [pid]; false when the process is already gone. */
    fun terminate(pid: Long): Boolean

    fun nanoTime(): Long = System.nanoTime()

    fun sleep(ms: Long) = Thread.sleep(ms)

    object Live : IosSimulatorHost {
      override fun spawn(udid: String, command: List<String>): SimctlCli.Result = SimctlCli.spawn(udid, command)

      override fun primaryLanguage(udid: String): String? {
        val file = iosGlobalPreferencesFile(udid, System.getProperty("user.home"))
        if (file.isFile) {
          host(iosPrimaryLanguageFromPlistCommand(file))?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val defaults = spawn(udid, iosGetLanguagesCommand())
        return defaults.stdout.takeIf { defaults.success }?.let(::iosFirstLanguage)
      }

      override fun springBoardPid(udid: String): Long? {
        val launchdSim = host(launchdSimPgrepCommand(udid))?.lines()?.singleOrNull { it.isNotBlank() }?.trim()?.toLongOrNull()
          ?: return null
        return host(springBoardPgrepCommand(launchdSim))?.lines()?.singleOrNull { it.isNotBlank() }?.trim()?.toLongOrNull()
      }

      override fun terminate(pid: Long): Boolean = ProcessHandle.of(pid).map { it.destroy() }.orElse(false)

      /** Stdout of a short host command, or null when it fails. */
      private fun host(command: List<String>): String? = try {
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        process.outputStream.close()
        if (!SimctlCli.waitForOrKill(process, HOST_COMMAND_TIMEOUT_SECONDS)) {
          process.destroyForcibly()
          null
        } else {
          val stdout = process.inputStream.bufferedReader().use { it.readText() }
          stdout.takeIf { process.exitValue() == 0 }
        }
      } catch (_: IOException) {
        null
      }

      private const val HOST_COMMAND_TIMEOUT_SECONDS = 5L
    }
  }
}
