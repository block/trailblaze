package xyz.block.trailblaze.android.test

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import xyz.block.trailblaze.devices.TrailblazeDevicePort

/**
 * The instrumentation surface this driver needs: androidTest APK assets, instrumentation args, and
 * a whole-device screenshot.
 *
 * Deliberately a small local implementation rather than a dependency on `:trailblaze-android`'s
 * `AndroidAssetsUtil` / `InstrumentationArgUtil`. This module compiles into an app's own
 * instrumentation harness, and depending on that module would drag Maestro and UiAutomator in with
 * it — the thing this driver exists to avoid.
 */
internal object AndroidTestInstrumentation {

  /**
   * Assets are read from the *instrumentation* context, i.e. the androidTest APK — that is where
   * `src/androidTest/assets/trails/…` lands, and where the Gradle plugin stages generated trails.
   */
  private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

  fun assetExists(assetPath: String): Boolean =
    try {
      assets.open(assetPath).close()
      true
    } catch (_: Exception) {
      false
    }

  fun readAssetAsString(assetPath: String): String =
    try {
      assets.open(assetPath).reader().use { it.readText() }
    } catch (e: Exception) {
      error("Could not read trail asset at '$assetPath': ${e.message}")
    }

  fun stringArg(name: String): String? =
    InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }

  fun booleanArg(name: String, default: Boolean): Boolean =
    stringArg(name)?.toBooleanStrictOrNull() ?: default

  /**
   * Where session logs are streamed when a Trailblaze log server is reachable.
   *
   * `10.0.2.2` is the host loopback as seen from an emulator, which is the shape every farm run
   * has. A physical device reaches the host through `adb reverse` instead, so under
   * `trailblaze.reverseProxy` the default is plain localhost — same rule as
   * `InstrumentationArgUtil.logsEndpoint`, and getting it wrong costs every test method a probe
   * timeout before falling back.
   *
   * When nothing is listening the logging rule writes each log to the device's Downloads
   * directory, which is what CI pulls.
   */
  fun logsEndpoint(): String {
    val port = stringArg(TrailblazeDevicePort.HTTPS_PORT_INSTRUMENTATION_ARG_KEY)?.toIntOrNull()
      ?: TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT
    // Lenient `toBoolean`, not [booleanArg]'s strict parse, so this reads a given value exactly the
    // way `InstrumentationArgUtil` does — the two must not disagree about the same argument.
    val host = if (stringArg("trailblaze.reverseProxy").toBoolean()) "localhost" else "10.0.2.2"
    return stringArg("trailblaze.logsEndpoint") ?: "https://$host:$port"
  }

  /**
   * A screenshot of the whole device, including anything drawn outside the Activity's window
   * (dialogs, IME, system UI).
   *
   * Returns null rather than throwing: a missing frame degrades the report, it must not fail the
   * trail that produced it.
   */
  fun deviceScreenshot(): Bitmap? =
    runCatching { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() }
      .getOrNull()
}
