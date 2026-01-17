package xyz.block.trailblaze.host.devices

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.HostAdbExecutor
import java.io.File

/**
 * Implementation of [HostAdbExecutor] using the adb binary via ProcessBuilder.
 * This is the traditional approach that spawns adb processes for each operation.
 * 
 * Use this implementation when:
 * - You need maximum compatibility with all adb features
 * - You're debugging adb-related issues
 * - dadb has issues with specific device/emulator configurations
 * 
 * @see DadbHostAdbExecutor for the dadb-based alternative
 */
class AdbBinaryHostAdbExecutor(
  override val deviceId: TrailblazeDeviceId,
) : HostAdbExecutor {

  override fun shell(command: String): String {
    println("adb shell: $command")
    return runAdbCommand("shell", command)
  }

  override fun install(apkFile: File, reinstall: Boolean, allowTestPackages: Boolean): Boolean {
    return try {
      println("adb install: ${apkFile.absolutePath} (reinstall=$reinstall, allowTestPackages=$allowTestPackages)")
      val args = mutableListOf("install")
      if (reinstall) args.add("-r")
      if (allowTestPackages) args.add("-t")
      args.add(apkFile.absolutePath)
      
      val output = runAdbCommand(*args.toTypedArray())
      val success = output.contains("Success", ignoreCase = true)
      
      if (success) {
        println("APK installation succeeded")
      } else {
        println("APK installation failed: $output")
      }
      success
    } catch (e: Exception) {
      println("APK installation failed: ${e.message}")
      false
    }
  }

  override fun uninstall(packageName: String): Boolean {
    return try {
      println("adb uninstall: $packageName")
      val output = runAdbCommand("uninstall", packageName)
      val success = output.contains("Success", ignoreCase = true)
      if (!success) {
        println("Uninstall output: $output")
      }
      success
    } catch (e: Exception) {
      println("Uninstall failed: ${e.message}")
      false
    }
  }

  override fun forward(localPort: Int, remotePort: Int) {
    println("adb forward: tcp:$localPort -> tcp:$remotePort")
    runAdbCommand("forward", "tcp:$localPort", "tcp:$remotePort")
  }

  override fun reverse(remotePort: Int, localPort: Int) {
    println("adb reverse: tcp:$remotePort -> tcp:$localPort")
    runAdbCommand("reverse", "tcp:$remotePort", "tcp:$localPort")
  }

  /**
   * Runs an adb command using the adb binary.
   */
  private fun runAdbCommand(vararg args: String): String {
    val command = mutableListOf("adb", "-s", deviceId.instanceId)
    command.addAll(args)
    
    val process = ProcessBuilder(command)
      .redirectErrorStream(true)
      .start()
    
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    
    if (exitCode != 0) {
      println("adb command failed with exit code $exitCode: $output")
    }
    
    return output
  }

  companion object Factory : HostAdbExecutor.Factory {
    
    override fun create(deviceId: TrailblazeDeviceId): HostAdbExecutor {
      require(deviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.ANDROID) {
        "AdbBinaryHostAdbExecutor only supports Android devices, got: ${deviceId.trailblazeDevicePlatform}"
      }
      return AdbBinaryHostAdbExecutor(deviceId)
    }

    override fun listDevices(): List<TrailblazeDeviceId> {
      val process = ProcessBuilder("adb", "devices")
        .redirectErrorStream(true)
        .start()
      
      val output = process.inputStream.bufferedReader().readText()
      process.waitFor()
      
      return output.lines()
        .drop(1) // Skip "List of devices attached" header
        .filter { it.isNotBlank() && it.contains("\tdevice") }
        .map { line ->
          val instanceId = line.substringBefore("\t")
          TrailblazeDeviceId(
            trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
            instanceId = instanceId,
          )
        }
    }

    /**
     * Initialize the HostAdbExecutor factory to use adb binary.
     * Call this early in application startup.
     */
    fun initialize() {
      HostAdbExecutor.factory = this
    }
  }
}
