package xyz.block.trailblaze.util

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import java.io.File

/**
 * Interface for executing ADB commands on a host machine against an Android device.
 * This abstraction allows different implementations (e.g., dadb for host-side code)
 * while keeping the interface available in common modules.
 */
interface HostAdbExecutor {
  /**
   * Execute a shell command on the device.
   * @return The output of the command
   */
  fun shell(command: String): String

  /**
   * Execute a shell command with multiple arguments on the device.
   * @return The output of the command
   */
  fun shell(vararg args: String): String = shell(args.joinToString(" "))

  /**
   * Install an APK file on the device.
   * @param apkFile The APK file to install
   * @param reinstall If true, reinstall the app if it already exists
   * @param allowTestPackages If true, allow installation of test packages
   * @return true if installation was successful
   */
  fun install(apkFile: File, reinstall: Boolean = true, allowTestPackages: Boolean = true): Boolean

  /**
   * Uninstall an app from the device.
   * @param packageName The package name to uninstall
   * @return true if uninstallation was successful
   */
  fun uninstall(packageName: String): Boolean

  /**
   * Set up port forwarding from local port to device port.
   * @param localPort The local port to forward from
   * @param remotePort The device port to forward to
   */
  fun forward(localPort: Int, remotePort: Int = localPort)

  /**
   * Set up reverse port forwarding from device port to local port.
   * 
   * Note: This operation requires the adb binary as dadb doesn't support reverse forwarding natively.
   * 
   * @param remotePort The device port to forward from
   * @param localPort The local port to forward to
   */
  fun reverse(remotePort: Int, localPort: Int = remotePort)

  /**
   * The device ID this executor is connected to.
   */
  val deviceId: TrailblazeDeviceId

  companion object {
    /**
     * Factory for creating HostAdbExecutor instances.
     * Must be set by the host module before any ADB operations are performed.
     */
    var factory: Factory? = null

    fun create(deviceId: TrailblazeDeviceId): HostAdbExecutor {
      return factory?.create(deviceId)
        ?: error("HostAdbExecutor.factory has not been initialized. " +
            "Ensure the host module sets up the factory before using ADB operations.")
    }

    /**
     * List all connected Android devices.
     * @return List of device IDs
     */
    fun listDevices(): List<TrailblazeDeviceId> {
      return factory?.listDevices()
        ?: error("HostAdbExecutor.factory has not been initialized.")
    }
  }

  interface Factory {
    fun create(deviceId: TrailblazeDeviceId): HostAdbExecutor
    fun listDevices(): List<TrailblazeDeviceId>
  }
}
