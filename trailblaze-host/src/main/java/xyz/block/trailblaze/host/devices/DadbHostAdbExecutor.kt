package xyz.block.trailblaze.host.devices

import dadb.Dadb
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.HostAdbExecutor
import java.io.File
import java.io.IOException
import java.net.SocketException

/**
 * Implementation of [HostAdbExecutor] using the dadb library.
 * This provides direct ADB communication without spawning adb processes for most operations.
 * 
 * Note: Reverse port forwarding (`reverse()`) uses the adb binary as dadb doesn't support it natively.
 */
class DadbHostAdbExecutor private constructor(
  override val deviceId: TrailblazeDeviceId,
  private var dadb: Dadb,
) : HostAdbExecutor {

  /**
   * Execute a dadb operation with automatic reconnection on connection failures.
   */
  private inline fun <T> withReconnect(operation: (Dadb) -> T): T {
    return try {
      operation(dadb)
    } catch (e: Exception) {
      when (e) {
        is SocketException, is IOException -> {
          println("Connection error (${e.javaClass.simpleName}: ${e.message}), attempting to reconnect...")
          reconnect()
          operation(dadb)
        }
        else -> throw e
      }
    }
  }

  /**
   * Reconnect to the device by getting a fresh Dadb instance.
   */
  private fun reconnect() {
    try {
      dadb.close()
    } catch (_: Exception) {
      // Ignore close errors
    }
    
    dadb = Factory.refreshConnection(deviceId.instanceId)
    println("Reconnected to device ${deviceId.instanceId}")
  }

  override fun shell(command: String): String {
    println("dadb shell: $command")
    return withReconnect { dadb ->
      val response = dadb.shell(command)
      val output = response.allOutput
      if (response.exitCode != 0) {
        println("Shell command failed with exit code ${response.exitCode}: $output")
      }
      output
    }
  }

  override fun install(apkFile: File, reinstall: Boolean, allowTestPackages: Boolean): Boolean {
    return try {
      println("dadb install: ${apkFile.absolutePath} (reinstall=$reinstall, allowTestPackages=$allowTestPackages)")
      val options = buildList {
        if (reinstall) add("-r")
        if (allowTestPackages) add("-t")
      }
      withReconnect { dadb ->
        dadb.install(apkFile, *options.toTypedArray())
      }
      println("APK installation succeeded")
      true
    } catch (e: Exception) {
      println("APK installation failed: ${e.message}")
      false
    }
  }

  override fun uninstall(packageName: String): Boolean {
    return try {
      println("dadb uninstall: $packageName")
      withReconnect { dadb ->
        dadb.uninstall(packageName)
      }
      true
    } catch (e: Exception) {
      println("Uninstall failed: ${e.message}")
      false
    }
  }

  override fun forward(localPort: Int, remotePort: Int) {
    println("dadb forward: tcp:$localPort -> tcp:$remotePort")
    withReconnect { dadb ->
      dadb.tcpForward(localPort, remotePort)
    }
  }

  /**
   * Reverse port forwarding requires the adb binary as dadb doesn't support it natively.
   */
  override fun reverse(remotePort: Int, localPort: Int) {
    println("adb reverse: tcp:$remotePort -> tcp:$localPort")
    runAdbCommand("reverse", "tcp:$remotePort", "tcp:$localPort")
  }

  /**
   * Runs an adb command using the adb binary.
   * Only used for `reverse` which dadb doesn't support.
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
    private val dadbCache = mutableMapOf<String, Dadb>()
    private val executorCache = mutableMapOf<String, DadbHostAdbExecutor>()

    override fun create(deviceId: TrailblazeDeviceId): HostAdbExecutor {
      require(deviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.ANDROID) {
        "DadbHostAdbExecutor only supports Android devices, got: ${deviceId.trailblazeDevicePlatform}"
      }
      
      return executorCache.getOrPut(deviceId.instanceId) {
        val dadb = getOrCreateDadb(deviceId.instanceId)
        DadbHostAdbExecutor(deviceId, dadb)
      }
    }

    private fun getOrCreateDadb(instanceId: String): Dadb {
      return dadbCache.getOrPut(instanceId) {
        Dadb.list().find { it.toString() == instanceId }
          ?: Dadb.discover()
          ?: error("Unable to find Android device with id $instanceId")
      }
    }

    /**
     * Refresh the connection for a device, creating a new Dadb instance.
     */
    internal fun refreshConnection(instanceId: String): Dadb {
      // Remove old connection from cache
      dadbCache.remove(instanceId)?.let { oldDadb ->
        try { oldDadb.close() } catch (_: Exception) {}
      }
      
      // Create new connection
      val newDadb = Dadb.list().find { it.toString() == instanceId }
        ?: Dadb.discover()
        ?: error("Unable to find Android device with id $instanceId")
      
      dadbCache[instanceId] = newDadb
      return newDadb
    }

    override fun listDevices(): List<TrailblazeDeviceId> {
      return Dadb.list().map { dadb ->
        TrailblazeDeviceId(
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
          instanceId = dadb.toString(),
        )
      }
    }

    /**
     * Initialize the HostAdbExecutor factory to use dadb.
     * Call this early in application startup.
     */
    fun initialize() {
      HostAdbExecutor.factory = this
    }

    /**
     * Clear the dadb connection cache.
     * Useful when devices are connected/disconnected.
     */
    fun clearCache() {
      dadbCache.values.forEach { 
        try { it.close() } catch (_: Exception) {} 
      }
      dadbCache.clear()
      executorCache.clear()
    }
  }
}
