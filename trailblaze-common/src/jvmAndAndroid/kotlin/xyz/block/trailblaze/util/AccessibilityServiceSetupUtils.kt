package xyz.block.trailblaze.util

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.util.AndroidHostAdbUtils.createAdbCommandProcessBuilder
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess

/**
 * Utility for automating [TrailblazeAccessibilityService] lifecycle via ADB from the host.
 *
 * The accessibility service is declared in the `trailblaze-accessibility` library manifest,
 * which gets merged into any consumer APK (e.g., the on-device runner test APK). This means
 * the service runs in the same process as the instrumentation test, allowing the static
 * singleton to work for cross-component communication.
 *
 * Provides commands to:
 * - Enable the accessibility service in system settings
 * - Verify the service is enabled
 */
object AccessibilityServiceSetupUtils {

  private const val ACCESSIBILITY_SERVICE_CLASS =
    "xyz.block.trailblaze.android.accessibility.TrailblazeAccessibilityService"

  /**
   * Builds the accessibility service component string for ADB settings.
   *
   * The service is declared in the `trailblaze-accessibility` library manifest and gets
   * merged into the host APK (e.g., `xyz.block.trailblaze.runner`). The component format
   * is `package/fully.qualified.ServiceClass`.
   */
  private fun getServiceComponent(hostPackage: String): String =
    "$hostPackage/$ACCESSIBILITY_SERVICE_CLASS"

  /**
   * Enables the Trailblaze accessibility service on the device via ADB settings commands.
   *
   * This appends the service to the `enabled_accessibility_services` setting so existing
   * services are not disrupted.
   *
   * @param hostPackage the package name of the APK that hosts the accessibility service
   *   (e.g., the test runner package)
   */
  fun enableAccessibilityService(
    deviceId: TrailblazeDeviceId,
    hostPackage: String,
    sendProgressMessage: (String) -> Unit = {},
  ) {
    val serviceComponent = getServiceComponent(hostPackage)
    sendProgressMessage("Enabling Trailblaze accessibility service ($serviceComponent)...")

    // Get current enabled services
    val currentServices = runAdbShellCommand(deviceId, "settings get secure enabled_accessibility_services")

    val newServices =
      if (currentServices.isBlank() || currentServices.trim() == "null") {
        serviceComponent
      } else if (currentServices.contains(serviceComponent)) {
        // Already enabled
        sendProgressMessage("Accessibility service already enabled.")
        return
      } else {
        "${currentServices.trim()}:$serviceComponent"
      }

    runAdbShellCommand(
      deviceId,
      "settings put secure enabled_accessibility_services $newServices",
    )

    // Also ensure accessibility is globally enabled
    runAdbShellCommand(deviceId, "settings put secure accessibility_enabled 1")

    sendProgressMessage("Accessibility service enabled.")
  }

  /**
   * Checks if the accessibility service is currently enabled in system settings.
   */
  fun isAccessibilityServiceEnabled(deviceId: TrailblazeDeviceId, hostPackage: String): Boolean {
    val services = runAdbShellCommand(deviceId, "settings get secure enabled_accessibility_services")
    return services.contains(getServiceComponent(hostPackage))
  }

  /**
   * Ensures the accessibility service is enabled and ready.
   *
   * The service is declared in the `trailblaze-accessibility` library manifest which gets
   * merged into the host APK. No separate APK installation is needed — the service is
   * part of the test runner APK.
   *
   * @param hostPackage the package name of the APK that hosts the accessibility service
   */
  fun ensureAccessibilityServiceReady(
    deviceId: TrailblazeDeviceId,
    hostPackage: String,
    sendProgressMessage: (String) -> Unit = {},
  ) {
    if (!isAccessibilityServiceEnabled(deviceId, hostPackage)) {
      enableAccessibilityService(deviceId, hostPackage, sendProgressMessage)
    } else {
      sendProgressMessage("Accessibility service is already enabled.")
    }

    // Give the system a moment to start the service after enabling
    sendProgressMessage("Waiting for accessibility service to start...")
    Thread.sleep(2000)
  }

  private fun runAdbShellCommand(deviceId: TrailblazeDeviceId, command: String): String {
    val process = createAdbCommandProcessBuilder(
      deviceId = deviceId,
      args = listOf("shell") + command.split(" "),
    )
    val result = process.runProcess { }
    return result.fullOutput
  }
}
