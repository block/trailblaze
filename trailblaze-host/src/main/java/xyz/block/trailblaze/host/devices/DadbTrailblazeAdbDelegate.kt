package xyz.block.trailblaze.host.devices

import xyz.block.trailblaze.adb.TrailblazeAdb
import xyz.block.trailblaze.adb.TrailblazeAdbDelegate
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.util.HostAdbExecutor

/**
 * Implementation of [TrailblazeAdbDelegate] that wraps a [HostAdbExecutor].
 * 
 * This allows the multiplatform [TrailblazeAdb] to use dadb on the JVM side.
 */
class DadbTrailblazeAdbDelegate(
  private val executor: HostAdbExecutor,
) : TrailblazeAdbDelegate {

  override fun shell(command: String): String {
    return executor.shell(command)
  }

  override fun isAppRunning(packageName: String): Boolean {
    val output = shell("pidof $packageName")
    println("pidof $packageName: $output")
    return output.trim().isNotEmpty()
  }

  override fun forceStopApp(packageName: String) {
    if (isAppRunning(packageName)) {
      shell("am force-stop $packageName")
    } else {
      println("App $packageName does not have an active process, no need to force stop")
    }
  }

  override fun clearAppData(packageName: String) {
    shell("pm clear $packageName")
  }

  override fun grantPermission(packageName: String, permission: String) {
    shell("pm grant $packageName $permission")
  }

  override fun listInstalledPackages(): List<String> {
    val output = shell("pm list packages")
    return output.lines()
      .filter { it.isNotBlank() && it.startsWith("package:") }
      .map { it.substringAfter("package:") }
  }

  override fun getSerialNumber(): String {
    return shell("getprop ro.boot.serialno").trim()
  }

  companion object {
    /**
     * Creates a [DadbTrailblazeAdbDelegate] for the specified device and sets it as
     * the active delegate for [TrailblazeAdb].
     * 
     * @param deviceId The device to connect to
     * @return The created delegate
     */
    fun initializeForDevice(deviceId: TrailblazeDeviceId): DadbTrailblazeAdbDelegate {
      val adbExecutor = HostAdbExecutor.create(deviceId)
      val delegate = DadbTrailblazeAdbDelegate(adbExecutor)
      TrailblazeAdb.delegate = delegate
      return delegate
    }
  }
}
