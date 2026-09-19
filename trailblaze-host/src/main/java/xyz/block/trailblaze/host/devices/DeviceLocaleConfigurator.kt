package xyz.block.trailblaze.host.devices

import java.util.Locale
import xyz.block.trailblaze.android.tools.shellEscape
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.ios.SimctlCli
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console

/** Applies a trail's resolved per-device locale before the target app is launched. */
internal object DeviceLocaleConfigurator {

  private const val ANDROID_LOCALE_COMMAND_TIMEOUT_MS = 10_000L
  private const val ANDROID_EMULATOR_RESTART_TIMEOUT_MS = 90_000L
  private val BCP_47_TAG = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{1,8})*")

  fun apply(deviceId: TrailblazeDeviceId, configuredLocale: String) {
    val locale = normalizedLocale(configuredLocale)
    when (deviceId.trailblazeDevicePlatform) {
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

  internal fun androidGetPersistedLocaleCommand(): List<String> =
    listOf("getprop", "persist.sys.locale")

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

  internal fun androidEmulatorSetLocaleCommand(locale: String): List<String> {
    val script =
      "setprop persist.sys.locale $locale; stop; sleep 5; start; " +
        "i=0; until cmd activity get-config 2>/dev/null | grep -q '^config:'; do " +
        "i=\$((i + 1)); [ \"\$i\" -ge 60 ] && exit 1; sleep 1; done"
    return listOf("su", "0", "sh", "-c", script.shellEscape())
  }

  internal fun iosSetLanguageCommand(locale: String): List<String> =
    listOf("defaults", "write", "-g", "AppleLanguages", "-array", locale)

  internal fun iosGetLanguagesCommand(): List<String> = listOf("defaults", "read", "-g", "AppleLanguages")

  internal fun iosRestartSpringBoardCommand(): List<String> = listOf("killall", "SpringBoard")

  internal fun localeReadbackMatches(expected: String, actualOutput: String?): Boolean =
    actualOutput?.trim()?.equals(expected, ignoreCase = true) == true

  internal fun androidLocaleCommandUnavailable(output: String?): Boolean =
    output?.contains("unknown command", ignoreCase = true) == true

  internal fun iosLanguageReadbackMatches(expected: String, actualOutput: String?): Boolean =
    actualOutput
      ?.lineSequence()
      ?.map { it.trim().trim('(', ')', ',', '"') }
      ?.firstOrNull { it.isNotBlank() }
      ?.equals(expected, ignoreCase = true) == true

  internal fun applyAndroid(
    deviceId: TrailblazeDeviceId,
    locale: String,
    exec: (TrailblazeDeviceId, List<String>, Long) -> String? = { id, args, timeoutMs ->
      AndroidHostAdbUtils.execAdbShellCommandWithTimeout(id, args, timeoutMs)
    },
  ) {
    val currentLocale = exec(deviceId, androidGetLocaleCommand(), ANDROID_LOCALE_COMMAND_TIMEOUT_MS)
    if (localeReadbackMatches(locale, currentLocale)) return

    if (androidLocaleCommandUnavailable(currentLocale)) {
      val emulator = exec(deviceId, androidIsEmulatorCommand(), ANDROID_LOCALE_COMMAND_TIMEOUT_MS)
      if (emulator?.trim() == "1") {
        // An emulator that was already switched into this language when it booted needs nothing,
        // and the fallback below must not restart its framework a second time for nothing. The
        // persisted property alone is not proof — it can hold the new value while the framework
        // still runs the old one (a restart that never completed) — so the running configuration
        // is what decides, here and after the restart.
        val persistedBefore =
          exec(deviceId, androidGetPersistedLocaleCommand(), ANDROID_LOCALE_COMMAND_TIMEOUT_MS)
        if (localeReadbackMatches(locale, persistedBefore)) {
          val configBefore = exec(deviceId, androidGetConfigCommand(), ANDROID_LOCALE_COMMAND_TIMEOUT_MS)
          if (androidConfigHasLocale(configBefore, locale)) return
        }
        val fallbackOutput =
          exec(
            deviceId,
            androidEmulatorSetLocaleCommand(locale),
            ANDROID_EMULATOR_RESTART_TIMEOUT_MS,
          )
        val configAfter = exec(deviceId, androidGetConfigCommand(), ANDROID_LOCALE_COMMAND_TIMEOUT_MS)
        check(androidConfigHasLocale(configAfter, locale)) {
          "Could not apply device locale `$locale` to Android emulator `${deviceId.instanceId}` " +
            "with the persist.sys.locale fallback; output: ${fallbackOutput?.trim().orEmpty()}, " +
            "running configuration: ${configAfter?.trim().orEmpty()}."
        }
        Console.log("[device-locale] set Android emulator ${deviceId.instanceId} to $locale")
        return
      }
    }

    val setOutput = exec(deviceId, androidSetLocaleCommand(locale), ANDROID_LOCALE_COMMAND_TIMEOUT_MS)
    val updatedLocale = exec(deviceId, androidGetLocaleCommand(), ANDROID_LOCALE_COMMAND_TIMEOUT_MS)
    check(localeReadbackMatches(locale, updatedLocale)) {
      "Could not apply device locale `$locale` to Android device `${deviceId.instanceId}`. " +
        "This requires the Android 16 `cmd locale set-device-locale` command; setter output: " +
        "${setOutput?.trim().orEmpty()}, readback: ${updatedLocale?.trim().orEmpty()}."
    }
    Console.log("[device-locale] set Android device ${deviceId.instanceId} to $locale")
  }

  internal fun applyIosSimulator(
    udid: String,
    locale: String,
    spawn: (String, List<String>) -> SimctlCli.Result = { device, command ->
      SimctlCli.spawn(device, command)
    },
  ) {
    val currentLanguages = spawn(udid, iosGetLanguagesCommand())
    if (currentLanguages.success && iosLanguageReadbackMatches(locale, currentLanguages.stdout)) return

    val setLanguage = spawn(udid, iosSetLanguageCommand(locale))
    check(setLanguage.success) {
      "Could not apply device locale `$locale` to iOS Simulator `$udid`: " +
        setLanguage.stderr.trim().ifBlank { "exit ${setLanguage.exitCode}" }
    }
    val updatedLanguages = spawn(udid, iosGetLanguagesCommand())
    check(
      updatedLanguages.success &&
        iosLanguageReadbackMatches(locale, updatedLanguages.stdout),
    ) {
      "Could not verify device locale `$locale` on iOS Simulator `$udid`: " +
        updatedLanguages.stderr.trim().ifBlank { updatedLanguages.stdout.trim() }
    }
    // SpringBoard reads the global language preference. Restarting it lets the simulator's system
    // UI use the new language before the target application launches.
    val restartSpringBoard = spawn(udid, iosRestartSpringBoardCommand())
    check(restartSpringBoard.success) {
      "Applied device locale `$locale` to iOS Simulator `$udid`, but could not restart SpringBoard: " +
        restartSpringBoard.stderr.trim().ifBlank { "exit ${restartSpringBoard.exitCode}" }
    }
    Console.log("[device-locale] set iOS Simulator $udid to $locale")
  }
}
