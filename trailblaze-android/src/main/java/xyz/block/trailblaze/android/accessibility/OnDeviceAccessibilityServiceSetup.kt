package xyz.block.trailblaze.android.accessibility

import android.app.UiAutomation
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.android.AndroidSdkVersion
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.PollingUtils

/**
 * Enables the [TrailblazeAccessibilityService] from within an on-device instrumentation test.
 *
 * This is the on-device counterpart to [AccessibilityServiceSetupUtils] (which uses ADB from the
 * host). It uses [AdbCommandUtil.execShellCommand] (backed by UiAutomation) to modify system
 * settings directly, so no host connection is needed — suitable for test farms like Firebase Test
 * Lab or AWS Device Farm.
 *
 * The accessibility service is declared in the `trailblaze-android` library manifest and gets
 * merged into the consumer APK via Android manifest merger. The service component format is
 * `{package}/{fully.qualified.ServiceClass}`.
 */
object OnDeviceAccessibilityServiceSetup {

  private const val ACCESSIBILITY_SERVICE_CLASS =
    "xyz.block.trailblaze.android.accessibility.TrailblazeAccessibilityService"

  /**
   * Ensures the accessibility service is enabled and running in this process.
   *
   * @param hostPackage the package name of the APK hosting the service. Defaults to the
   *   instrumentation test APK's package name.
   * @param timeoutMs maximum time to wait for the service to start after enabling.
   */
  fun ensureAccessibilityServiceReady(
    hostPackage: String = getTestPackage(),
    timeoutMs: Long = 15_000,
  ) {
    val serviceComponent = "$hostPackage/$ACCESSIBILITY_SERVICE_CLASS"

    // UiAutomation suppresses accessibility services by default (flags=0). We must set
    // FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES so the system can bind to our service.
    // This must happen before any UiDevice/UiAutomation usage that could initialize the
    // connection with the default (suppressing) flags.
    ensureUiAutomationDoesNotSuppressAccessibility()

    if (isServiceRunning()) {
      Console.log("Accessibility service already running.")
      return
    }

    enableAccessibilityService(serviceComponent)
    waitForServiceRunning(serviceComponent, timeoutMs)
  }

  /**
   * Configures UiAutomation to NOT suppress accessibility services. By default, the
   * [UiAutomation] connection uses flags=0 which tells the system to disable all accessibility
   * services while automation is active. Setting [UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES]
   * allows our [TrailblazeAccessibilityService] to coexist with UiAutomation.
   *
   * Call this early (e.g., during server startup) so that when the host enables the
   * accessibility service via ADB, the system can bind it immediately.
   */
  fun ensureUiAutomationDoesNotSuppressAccessibility() {
    if (AndroidSdkVersion.isAtLeast(24)) {
      // Set the flag on UiAutomator's Configurator so any future UiDevice.getInstance() calls
      // also use the non-suppressing flag.
      Configurator.getInstance().uiAutomationFlags =
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
      // Force reconnect UiAutomation with the new flag (replaces any existing connection).
      InstrumentationRegistry.getInstrumentation()
        .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
      Console.log("UiAutomation configured to not suppress accessibility services.")
    }
  }

  private fun getTestPackage(): String =
    InstrumentationRegistry.getInstrumentation().context.packageName

  private fun enableAccessibilityService(serviceComponent: String) {
    Console.log("Enabling accessibility service: $serviceComponent")

    val currentServices =
      AdbCommandUtil.execShellCommand("settings get secure enabled_accessibility_services").trim()

    val newServices =
      when {
        currentServices.isBlank() || currentServices == "null" -> serviceComponent
        currentServices.contains(serviceComponent) -> {
          Console.log("Accessibility service already in enabled_accessibility_services.")
          // Still need to ensure accessibility is globally enabled
          AdbCommandUtil.execShellCommand("settings put secure accessibility_enabled 1")
          return
        }
        else -> "$currentServices:$serviceComponent"
      }

    AdbCommandUtil.execShellCommand(
      "settings put secure enabled_accessibility_services $newServices"
    )
    AdbCommandUtil.execShellCommand("settings put secure accessibility_enabled 1")
    Console.log("Accessibility service enabled in system settings.")
  }

  /**
   * Checks if the accessibility service is running using shell commands to avoid triggering
   * class loading of [TrailblazeAccessibilityService] which may fail on API < 30. Falls back to
   * the in-process static check if the service is running in the same process.
   */
  private fun isServiceRunning(): Boolean {
    // First try the in-process check (fast path if the service runs in our process)
    if (TrailblazeAccessibilityService.isServiceRunning()) return true

    // Fall back to shell-based check (works for cross-process or when class loading is deferred)
    val output =
      AdbCommandUtil.execShellCommand("dumpsys accessibility").trim()
    return output.contains(ACCESSIBILITY_SERVICE_CLASS) && output.contains("isConnected: true")
  }

  private fun waitForServiceRunning(serviceComponent: String, timeoutMs: Long) {
    Console.log("Waiting for accessibility service to start (timeout: ${timeoutMs}ms)...")
    PollingUtils.tryUntilSuccessOrThrowException(
      maxWaitMs = timeoutMs,
      intervalMs = 500,
      conditionDescription = "TrailblazeAccessibilityService should be running",
    ) {
      isServiceRunning()
    }
    Console.log("Accessibility service is running.")
  }
}
