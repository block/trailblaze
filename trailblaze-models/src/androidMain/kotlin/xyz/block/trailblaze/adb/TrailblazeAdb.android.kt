package xyz.block.trailblaze.adb

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/**
 * Android implementation of [TrailblazeAdb] using UiDevice.
 * 
 * This runs inside an Android instrumentation process and executes shell commands
 * directly on the device using UiDevice.executeShellCommand().
 */
actual object TrailblazeAdb {
  
  private val uiDevice: UiDevice by lazy {
    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
  }

  actual fun shell(command: String): String {
    println("TrailblazeAdb.shell: $command")
    return uiDevice.executeShellCommand(command)
  }

  actual fun isAppRunning(packageName: String): Boolean {
    val output = shell("pidof $packageName")
    println("pidof $packageName: $output")
    return output.trim().isNotEmpty()
  }

  actual fun forceStopApp(packageName: String) {
    if (isAppRunning(packageName)) {
      shell("am force-stop $packageName")
    } else {
      println("App $packageName does not have an active process, no need to force stop")
    }
  }

  actual fun clearAppData(packageName: String) {
    shell("pm clear $packageName")
  }

  actual fun grantPermission(packageName: String, permission: String) {
    shell("pm grant $packageName $permission")
  }

  actual fun listInstalledPackages(): List<String> {
    val output = shell("pm list packages")
    return output.lines()
      .filter { it.isNotBlank() && it.startsWith("package:") }
      .map { it.substringAfter("package:") }
  }

  actual fun getSerialNumber(): String {
    return shell("getprop ro.boot.serialno").trim()
  }
}
