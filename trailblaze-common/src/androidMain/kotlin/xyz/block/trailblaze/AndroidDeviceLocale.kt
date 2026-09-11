package xyz.block.trailblaze

import java.util.Locale

/** Applies a trail's device-language request from either Android instrumentation runner. */
object AndroidDeviceLocale {

  private val BCP_47_TAG = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{1,8})*")

  fun apply(
    configuredLocale: String,
    exec: (String) -> String = AdbCommandUtil::execShellCommand,
  ): Boolean {
    val locale = normalizedLocale(configuredLocale)
    val currentLocale = exec("cmd locale get-device-locale")
    if (localeReadbackMatches(locale, currentLocale)) return false

    if (currentLocale.contains("unknown command", ignoreCase = true)) {
      val persistedLocale = exec("getprop persist.sys.locale")
      check(localeReadbackMatches(locale, persistedLocale)) {
        "Could not verify device locale `$locale` on this Android device. " +
          "The Android 16 `cmd locale` commands are unavailable and the host-side emulator " +
          "fallback was not applied; persisted locale: ${persistedLocale.trim()}."
      }
      return false
    }

    val setOutput = exec("cmd locale set-device-locale $locale")
    val updatedLocale = exec("cmd locale get-device-locale")
    check(localeReadbackMatches(locale, updatedLocale)) {
      "Could not apply device locale `$locale` to this Android device. " +
        "This requires the Android 16 `cmd locale set-device-locale` command; setter output: " +
        "${setOutput.trim()}, readback: ${updatedLocale.trim()}."
    }
    return true
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
