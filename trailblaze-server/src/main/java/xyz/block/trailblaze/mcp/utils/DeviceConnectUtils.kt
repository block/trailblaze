package xyz.block.trailblaze.mcp.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.block.trailblaze.mcp.TrailblazeMcpSseSessionContext
import xyz.block.trailblaze.mcp.models.AdbDevice
import xyz.block.trailblaze.mcp.models.DeviceConnectionStatus
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object DeviceConnectUtils {

  val ioScope = CoroutineScope(Dispatchers.IO)

  // Helper to get git root directory
  private fun getGitRoot(): File? = try {
    val process = ProcessBuilder("git", "rev-parse", "--show-toplevel")
      .redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exit = process.waitFor()
    if (exit == 0 && output.isNotBlank()) File(output) else null
  } catch (e: Exception) {
    null
  }

  suspend fun startConnectionProcess(
    deviceId: String,
    sessionContext: TrailblazeMcpSseSessionContext,
  ): DeviceConnectionStatus {
    val completableDeferred: CompletableDeferred<DeviceConnectionStatus> = CompletableDeferred()
    val gitRoot = getGitRoot() ?: File(".")

    val port = 52526
    portForward(deviceId, port)

    sessionContext.sendIndeterminateProgressMessage("Building and Installing On-Device Trailblaze")
    // Start Gradle process
    val gradleProcess = ProcessBuilder(
      "./gradlew",
      ":trailblaze-android-ondevice-mcp:installDebugAndroidTest",
    ).directory(gitRoot).redirectErrorStream(true).start()

    // Log Gradle output
    val gradleSystemOutListenerThread = Thread {
      try {
        val reader = gradleProcess.inputStream.bufferedReader()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
          println("Gradle output: $line")
        }
      } catch (e: Exception) {
        println("Error reading Gradle output: ${e.message}")
      }
    }
    gradleSystemOutListenerThread.start()

    // Wait for Gradle to complete
    val gradleExit = gradleProcess.waitFor()
    gradleSystemOutListenerThread.interrupt() // Kill system out listening
    if (gradleExit != 0) {
      return DeviceConnectionStatus.ConnectionFailure("Gradle install failed with exit code $gradleExit")
    }
    sessionContext.sendIndeterminateProgressMessage("On-Device Trailblaze Installed. Connecting to Trailblaze...")

    val testAppId = "xyz.block.trailblaze.android.mcp.ondevice.test"

    val forceStopArgs = listOf(
      "adb",
      "-s",
      deviceId,
      "shell",
      "am",
      "force-stop",
      "xyz.block.trailblaze.android.mcp.ondevice.test",
    )

    sessionContext.sendIndeterminateProgressMessage("Ensuring old sessions are stopped...")
    ProcessBuilder(forceStopArgs)
      .directory(gitRoot)
      .redirectErrorStream(true)
      .start()
      .waitFor()

    val startInstrumentationArgs = listOf(
      "adb", "-s", deviceId,
      "shell", "am",
      "instrument", "-w", "-r",
      "-e", "class", "xyz.block.trailblaze.AndroidOnDeviceMcpServerTest",
      "-e", "trailblaze.ai.enabled", "true",
      "-e", "OPENAI_API_KEY", System.getenv("OPENAI_API_KEY"),
      "$testAppId/androidx.test.runner.AndroidJUnitRunner",
    )
    sessionContext.sendIndeterminateProgressMessage(
      "Connecting to Trailblaze On-Device using Android Test Instrumentation.",
    )
    // Start instrumentation
    val processBuilder = ProcessBuilder(startInstrumentationArgs)
      .directory(gitRoot)
      .redirectErrorStream(true)

    // Get the environment map
    System.getenv().keys.forEach { envVar ->
      val value = System.getenv(envVar)
      if (value != null) {
        processBuilder.environment()[envVar] = value
      } else {
        println("Warning: $envVar is not set in the environment")
      }
    }

    val instrProcess = processBuilder.start()

    var hasCallbackBeenCalled = false

    // Log instrumentation output
    ioScope.launch {
      try {
        val reader = instrProcess.inputStream.bufferedReader()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
          println("Instrumentation output: $line")
          // Update status based on output
          if (!hasCallbackBeenCalled && line?.contains("INSTRUMENTATION_STATUS_CODE:") == true) {
            sessionContext.sendIndeterminateProgressMessage("Trailblaze On-Device Connected Successfully!")
            println("INSTRUMENTATION_STATUS_CODE found in output: $line")
            completableDeferred.complete(
              DeviceConnectionStatus.TrailblazeInstrumentationRunning(
                deviceId = deviceId,
              ),
            )
            hasCallbackBeenCalled = true
          }
        }
      } catch (e: Exception) {
        val errorMessage = "Error connecting Trailblaze On-Device. ${e.message}"
        sessionContext.sendIndeterminateProgressMessage(errorMessage)
        completableDeferred.complete(
          DeviceConnectionStatus.ConnectionFailure(errorMessage),
        )
      }
    }
    return completableDeferred.await()
  }

  fun portForward(
    deviceId: String?,
    localPort: Int,
    remotePort: Int = localPort,
  ): Process = try {
    val args = mutableListOf<String>().apply {
      add("adb")
      if (!deviceId.isNullOrBlank()) {
        add("-s")
        add(deviceId)
      }
      this.addAll(listOf("forward", "tcp:$localPort", "tcp:$remotePort"))
    }
    ProcessBuilder(args).redirectErrorStream(true).start()
  } catch (e: Exception) {
    throw RuntimeException("Failed to start port forwarding for device $deviceId: ${e.message}")
  }

  // Function to get the device model name from adb
  fun getDeviceName(deviceId: String): String = try {
    val process = ProcessBuilder("adb", "-s", deviceId, "shell", "getprop", "ro.product.model").start()
    val reader = BufferedReader(InputStreamReader(process.inputStream))
    val name = reader.readLine()?.trim()
    if (!name.isNullOrBlank()) name else deviceId
  } catch (e: Exception) {
    deviceId
  }

  // Function to get devices from adb
  fun getAdbDevices(): List<AdbDevice> = try {
    val process = ProcessBuilder("adb", "devices").start()
    val reader = BufferedReader(InputStreamReader(process.inputStream))
    val lines = reader.readLines()
    lines.drop(1)
      .filter { it.isNotBlank() && it.contains("\tdevice") }
      .map { line ->
        val id = line.substringBefore("\t")
        AdbDevice(id, getDeviceName(id))
      }
  } catch (e: Exception) {
    emptyList()
  }
}
