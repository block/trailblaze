package xyz.block.trailblaze

import android.Manifest
import android.os.Build
import android.os.LocaleList
import xyz.block.trailblaze.InstrumentationUtil.withUiAutomation
import xyz.block.trailblaze.devices.CatalogDeviceArgs
import xyz.block.trailblaze.devices.TrailDeviceLocale
import java.lang.reflect.InvocationTargetException
import java.util.Locale

/** Applies a trail's device-language request from either Android instrumentation runner. */
object AndroidDeviceLocale {

  private val BCP_47_TAG = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{1,8})*")

  /**
   * The locale a trail asks for: its own `locale:`, else the locale of the catalog device the run
   * stands for ([CatalogDeviceArgs.locale]), so an `android-phone-es` entry that omits `locale:`
   * still runs in Spanish on a device farm, as it does on a host-booted emulator. Null when
   * neither is set; the trail then runs in [TrailDeviceLocale.DEFAULT].
   */
  fun forTrail(
    trailLocale: String?,
    catalogDeviceLocale: () -> String? = CatalogDeviceArgs::locale,
  ): String? = trailLocale ?: catalogDeviceLocale()

  /**
   * Whether the device already runs in [configuredLocale], by the same readback [apply] checks
   * before changing anything. Lets a runner keep the target app's process when applying the
   * locale would change nothing.
   */
  fun isCurrent(
    configuredLocale: String,
    exec: (String) -> String = AdbCommandUtil::execShellCommand,
  ): Boolean {
    val locale = normalizedLocale(configuredLocale)
    val currentLocale = exec("cmd locale get-device-locale")
    if (localeReadbackMatches(locale, currentLocale)) return true
    return deviceLocaleCommandUnavailable(currentLocale) &&
      localeReadbackMatches(locale, persistedSystemLocale(exec))
  }

  /**
   * Applies the language a trail runs in: its resolved `locale:` ([declaredLocale]) or its catalog
   * device's ([forTrail]), or [TrailDeviceLocale.DEFAULT] when neither is set. Returns whether the
   * device's language changed, so the caller restarts the target app only then.
   */
  fun applyForTrail(declaredLocale: String?): Boolean =
    TrailDeviceLocale.applyBestEffort(TrailDeviceLocale.forTrail(forTrail(declaredLocale)), deviceLabel = "this Android device") {
      apply(it)
    }

  /**
   * Makes [configuredLocale] the device locale and proves it took, returning whether anything
   * changed.
   *
   * Android 16+ has `cmd locale set-device-locale`. Older images have no shell command for the
   * system locale, so [updateSystemLocales] changes it in place from this process (see
   * [updateSystemLocalesAsShell]), and [persistedSystemLocale] is the readback, polled briefly
   * because the system persists it after the update returns.
   */
  fun apply(
    configuredLocale: String,
    updateSystemLocales: (String) -> Unit = ::updateSystemLocalesAsShell,
    sleep: (Long) -> Unit = Thread::sleep,
    exec: (String) -> String = AdbCommandUtil::execShellCommand,
  ): Boolean {
    val locale = normalizedLocale(configuredLocale)
    val currentLocale = exec("cmd locale get-device-locale")
    if (localeReadbackMatches(locale, currentLocale)) return false

    if (deviceLocaleCommandUnavailable(currentLocale)) {
      if (localeReadbackMatches(locale, persistedSystemLocale(exec))) return false
      val updateFailure = runCatching { updateSystemLocales(locale) }.exceptionOrNull()
      if (updateFailure is InterruptedException) throw updateFailure.also { Thread.currentThread().interrupt() }
      val persistedLocale =
        if (updateFailure == null) awaitPersistedSystemLocale(locale, exec, sleep) else persistedSystemLocale(exec)
      if (updateFailure == null && localeReadbackMatches(locale, persistedLocale)) return true
      val message =
        "Could not apply device locale `$locale` to this Android device. Updating the system " +
          "configuration ${updateFailure?.let { "failed: $it" } ?: "did not take"}; " +
          "persisted locale: $persistedLocale."
      // An update that returned may have changed the running configuration even though the
      // readback disagrees, so that counts as partial; one that threw changed nothing.
      if (updateFailure != null) error(message)
      throw TrailDeviceLocale.PartiallyAppliedException(message)
    }

    val setOutput = exec("cmd locale set-device-locale $locale")
    val updatedLocale = exec("cmd locale get-device-locale")
    if (localeReadbackMatches(locale, updatedLocale)) return true
    val message =
      "Could not apply device locale `$locale` to this Android device. " +
        "This requires the Android 16 `cmd locale set-device-locale` command; setter output: " +
        "${setOutput.trim()}, readback: ${updatedLocale.trim()}."
    // The setter prints only when it refuses; a silent one may have changed the language even
    // though the readback disagrees, so that counts as partial.
    if (setOutput.isNotBlank()) error(message)
    throw TrailDeviceLocale.PartiallyAppliedException(message)
  }

  /**
   * Changes the system locale the way the Settings language picker does, borrowing the shell's
   * `CHANGE_CONFIGURATION` and `WRITE_SETTINGS` (both required) through UiAutomation for the one
   * call. Works on a live
   * device with no reboot or framework restart, so it runs inside instrumentation — including on
   * device-farm images that were booted without any host-side locale setup.
   */
  private fun updateSystemLocalesAsShell(locale: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      throw IllegalStateException(
        "Setting the device locale needs Android 10 (API 29) or newer; this device is " +
          "API ${Build.VERSION.SDK_INT}."
      )
    }
    // Hidden, but on the platform's allowed (unsupported) list for every targetSdk.
    val updateLocales = Class.forName("com.android.internal.app.LocalePicker")
      .getMethod("updateLocales", LocaleList::class.java)
    withUiAutomation {
      adoptShellPermissionIdentity(
        Manifest.permission.CHANGE_CONFIGURATION,
        Manifest.permission.WRITE_SETTINGS,
      )
      try {
        updateLocales.invoke(null, LocaleList.forLanguageTags(locale))
      } catch (e: InvocationTargetException) {
        throw e.targetException
      } finally {
        dropShellPermissionIdentity()
      }
    }
  }

  /**
   * Whether `cmd locale get-device-locale` answered that the command doesn't exist rather than with
   * a locale: Android 13–15 have a `locale` service without that subcommand ("Unknown command"),
   * and Android 10–12 have no `locale` service at all ("Can't find service", written to stderr, so
   * the captured output can be empty).
   */
  private fun deviceLocaleCommandUnavailable(output: String): Boolean =
    output.isBlank() ||
      output.contains("unknown command", ignoreCase = true) ||
      output.contains("can't find service", ignoreCase = true)

  /** [persistedSystemLocale] once it reads [locale], or its last value after the timeout. */
  private fun awaitPersistedSystemLocale(
    locale: String,
    exec: (String) -> String,
    sleep: (Long) -> Unit,
  ): String {
    var persistedLocale = persistedSystemLocale(exec)
    repeat(READBACK_ATTEMPTS - 1) {
      if (localeReadbackMatches(locale, persistedLocale)) return persistedLocale
      sleep(READBACK_INTERVAL_MS)
      persistedLocale = persistedSystemLocale(exec)
    }
    return persistedLocale
  }

  private const val READBACK_ATTEMPTS = 20
  private const val READBACK_INTERVAL_MS = 100L

  /**
   * The locale the system configuration boots with before Android 16: the first of the
   * `system_locales` setting, which a configuration update writes and which then wins over
   * `persist.sys.locale` at boot; or `persist.sys.locale` while that setting is unset, as on an
   * image whose locale only the host's root-shell fallback set; or, on a fresh image where both are
   * unset, the build's `ro.product.locale`. Without that last one every fresh device would read as
   * changed the first time a trail asked for the default.
   */
  private fun persistedSystemLocale(exec: (String) -> String): String {
    val systemLocales = exec("settings get system system_locales").trim()
    if (systemLocales.isNotEmpty() && systemLocales != "null") return systemLocales.substringBefore(',')
    return exec("getprop persist.sys.locale").trim().ifEmpty { exec("getprop ro.product.locale").trim() }
  }

  private fun normalizedLocale(value: String): String {
    require(BCP_47_TAG.matches(value)) {
      "Device locale must be a BCP-47 language tag such as `es` or `fr-CA`, but was `$value`."
    }
    val locale = Locale.forLanguageTag(value)
    require(locale.language.isNotBlank() && locale.language != "und") {
      "Device locale must be a BCP-47 language tag such as `es` or `fr-CA`, but was `$value`."
    }
    return locale.toLanguageTag()
  }

  private fun localeReadbackMatches(expected: String, actual: String): Boolean =
    actual.trim().equals(expected, ignoreCase = true)
}
