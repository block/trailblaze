package xyz.block.trailblaze.util

import xyz.block.trailblaze.devices.TrailblazeDeviceId

/**
 * Utility for automating [TrailblazeAccessibilityService] lifecycle via ADB from the host.
 *
 * The accessibility service is declared in the `trailblaze-android` library manifest,
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
   * The service is declared in the `trailblaze-android` library manifest and gets
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
   * Disables the Trailblaze accessibility service on the device.
   *
   * This should be called before force-stopping the runner process, because a registered
   * accessibility service causes the system to immediately restart the process after
   * `am force-stop`, preventing a clean shutdown.
   */
  fun disableAccessibilityService(
    deviceId: TrailblazeDeviceId,
    hostPackage: String,
    sendProgressMessage: (String) -> Unit = {},
  ) {
    val serviceComponent = getServiceComponent(hostPackage)
    val currentServices = runAdbShellCommand(deviceId, "settings get secure enabled_accessibility_services")

    if (currentServices.isBlank() || currentServices.trim() == "null" || !currentServices.contains(serviceComponent)) {
      return
    }

    val newServices = currentServices.trim()
      .split(":")
      .filter { it != serviceComponent }
      .joinToString(":")

    if (newServices.isBlank()) {
      runAdbShellCommand(deviceId, "settings put secure enabled_accessibility_services null")
    } else {
      runAdbShellCommand(deviceId, "settings put secure enabled_accessibility_services $newServices")
    }
    sendProgressMessage("Accessibility service disabled.")
  }

  private fun runAdbShellCommand(deviceId: TrailblazeDeviceId, command: String): String =
    AndroidHostAdbUtils.execAdbShellCommand(deviceId, command.split(" "))
}
