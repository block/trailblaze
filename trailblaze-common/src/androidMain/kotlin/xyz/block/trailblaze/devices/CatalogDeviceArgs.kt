package xyz.block.trailblaze.devices

import androidx.test.platform.app.InstrumentationRegistry

/**
 * The catalog device a hostless run (a device farm) says it stands for, as instrumentation args.
 * A locale-derived device such as `android-phone-es` boots its base device's image, so these are
 * the only way the on-device runner learns which device it is: [classifiers] selects that device's
 * recordings and trail config, and [locale] is the device's language when the trail names none.
 * Read the same way by the accessibility and in-process runners.
 */
object CatalogDeviceArgs {

  /** The catalog device key, e.g. `android-phone-es`; the on-device `--device-classifier`. */
  const val CLASSIFIER_ARG_KEY = "trailblaze.deviceClassifier"

  /** The catalog device's own locale, e.g. `es`. */
  const val LOCALE_ARG_KEY = "trailblaze.deviceLocale"

  /** The classifiers [CLASSIFIER_ARG_KEY] names, or none when the run didn't pass it. */
  fun classifiers(): List<TrailblazeDeviceClassifier> =
    arg(CLASSIFIER_ARG_KEY)?.let(DeviceClassifierKey::parse).orEmpty()

  fun locale(): String? = arg(LOCALE_ARG_KEY)

  private fun arg(key: String): String? =
    InstrumentationRegistry.getArguments().getString(key)?.takeIf { it.isNotBlank() }
}
