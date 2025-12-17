package xyz.block.trailblaze.util

import kotlinx.datetime.Clock
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.model.TrailblazeOnDeviceInstrumentationTarget
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess
import java.io.File

object AndroidHostAdbUtils {

  fun intentToAdbBroadcastCommandArgs(
    action: String,
    component: String,
    extras: Map<String, String>,
  ): List<String> {
    val args = buildList<String> {
      add("am")
      add("broadcast")
      if (action.isNotEmpty()) {
        add("-a")
        add(action)
      }
      if (component.isNotEmpty()) {
        add("-n")
        add(component)
      }
      extras.forEach { (key, value) ->
        add("--es")
        add(key)
        add(value)
        // Extend this if you need more types (e.g., --ez for booleans, etc.)
      }
    }
    return args
  }

  fun uninstallApp(
    deviceId: TrailblazeDeviceId,
    appPackageId: String,
  ) {
    createAdbCommandProcessBuilder(
      deviceId = deviceId,
      args = listOf(
        "uninstall",
        appPackageId,
      ),
    ).runProcess {}
  }

  fun createAdbCommandProcessBuilder(
    args: List<String>,
    deviceId: TrailblazeDeviceId?,
  ): ProcessBuilder {
    val args = mutableListOf<String>().apply {
      add("adb")
      if (deviceId != null) {
        add("-s")
        add(deviceId.instanceId)
      }
      this.addAll(args)
    }
    return TrailblazeProcessBuilderUtils.createProcessBuilder(args)
  }

  suspend fun isAppInstalled(appId: String, deviceId: TrailblazeDeviceId): Boolean =
    listInstalledPackages(deviceId).any { it == appId }

  fun adbPortForward(
    deviceId: TrailblazeDeviceId,
    localPort: Int,
    remotePort: Int = localPort,
  ): Process = try {
    // Check if forward already exists
    if (isPortForwardAlreadyActive(
        trailblazeDeviceId = deviceId,
        localPort = localPort,
        remotePort = remotePort
      )
    ) {
      println("Port forward tcp:$localPort -> tcp:$remotePort already exists")
      ProcessBuilder("echo", "Port forward already exists").start()
    } else {
      println("Setting up port forward tcp:$localPort -> tcp:$remotePort")
      createAdbCommandProcessBuilder(
        deviceId = deviceId,
        args = listOf("forward", "tcp:$localPort", "tcp:$remotePort"),
      ).start()
    }
  } catch (e: Exception) {
    throw RuntimeException("Failed to start port forwarding: ${e.message}")
  }

  // Simplified helper to check if a port reverse already exists
  private fun isPortReverseAlreadyActive(deviceId: TrailblazeDeviceId, localPort: Int, remotePort: Int): Boolean = try {
    val result = createAdbCommandProcessBuilder(
      deviceId = deviceId,
      args = listOf("reverse", "--list"),
    ).runProcess({})

    result.outputLines.any { line ->
      line.contains("tcp:$localPort") && line.contains("tcp:$remotePort")
    }
  } catch (e: Exception) {
    false // If we can't check, assume it doesn't exist
  }

  // Simplified helper to check if a port forward already exists
  private fun isPortForwardAlreadyActive(
    trailblazeDeviceId: TrailblazeDeviceId, localPort: Int, remotePort: Int
  ): Boolean = try {
    val result = createAdbCommandProcessBuilder(
      deviceId = trailblazeDeviceId,
      args = listOf("forward", "--list"),
    ).runProcess({})

    result.outputLines.any { line ->
      line.contains("tcp:$localPort") && line.contains("tcp:$remotePort")
    }
  } catch (e: Exception) {
    false // If we can't check, assume it doesn't exist
  }

  fun adbPortReverse(
    deviceId: TrailblazeDeviceId,
    localPort: Int,
    remotePort: Int = localPort,
  ): Process = try {
    // Check if forward already exists
    if (isPortReverseAlreadyActive(deviceId, localPort, remotePort)) {
      println("Port reverse tcp:$localPort -> tcp:$remotePort already exists")
      ProcessBuilder("echo", "Port reverse already exists").start()
    } else {
      println("Setting up port forward tcp:$localPort -> tcp:$remotePort")
      createAdbCommandProcessBuilder(
        deviceId = deviceId,
        args = listOf("reverse", "tcp:$localPort", "tcp:$remotePort"),
      ).start()
    }
  } catch (e: Exception) {
    throw RuntimeException("Failed to start port forwarding: ${e.message}")
  }

  fun execAdbShellCommand(deviceId: TrailblazeDeviceId, args: List<String>): String {
    println("adb shell ${args.joinToString(" ")}")
    return createAdbCommandProcessBuilder(
      deviceId = deviceId,
      args = listOf(
        "shell",
      ) + args,
    ).runProcess {}.fullOutput
  }

  fun isAppRunning(deviceId: TrailblazeDeviceId, appId: String): Boolean {
    val output = execAdbShellCommand(
      deviceId = deviceId,
      args = listOf("pidof", appId)
    )
    println("pidof $appId: $output")
    val isRunning = output.trim().isNotEmpty()
    return isRunning
  }

  /**
   * @return true if the condition was met within the timeout, false otherwise
   */
  fun tryUntilSuccessOrTimeout(
    maxWaitMs: Long,
    intervalMs: Long,
    conditionDescription: String,
    condition: () -> Boolean,
  ): Boolean {
    val startTime = Clock.System.now()
    var elapsedTime = 0L
    while (elapsedTime < maxWaitMs) {
      val conditionResult: Boolean = try {
        condition()
      } catch (e: Exception) {
        println("Ignored Exception while computing Condition [$conditionDescription], Exception [${e.message}]")
        false
      }
      if (conditionResult) {
        println("Condition [$conditionDescription] met after ${elapsedTime}ms")
        return true
      } else {
        println("Condition [$conditionDescription] not yet met after ${elapsedTime}ms with timeout of ${maxWaitMs}ms")
        Thread.sleep(intervalMs)
        elapsedTime = Clock.System.now().toEpochMilliseconds() - startTime.toEpochMilliseconds()
      }
    }
    println("Timed out (${maxWaitMs}ms limit) met [$conditionDescription] after ${elapsedTime}ms")
    return false
  }

  /**
   * @return true if the condition was met within the timeout, false otherwise
   */
  fun tryUntilSuccessOrThrowException(
    maxWaitMs: Long,
    intervalMs: Long,
    conditionDescription: String,
    condition: () -> Boolean,
  ) {
    val successful = tryUntilSuccessOrTimeout(
      maxWaitMs = maxWaitMs,
      intervalMs = intervalMs,
      conditionDescription = conditionDescription,
      condition = condition,
    )
    if (!successful) {
      error("Timed out (${maxWaitMs}ms limit) met [$conditionDescription]")
    }
  }

  fun launchAppWithAdbMonkey(
    deviceId: TrailblazeDeviceId,
    appId: String,
  ) {
    execAdbShellCommand(
      deviceId = deviceId,
      args = listOf("monkey", "-p", appId, "1"),
    )
  }

  fun clearAppData(deviceId: TrailblazeDeviceId, appId: String) {
    execAdbShellCommand(
      deviceId = deviceId,
      args = listOf("pm", "clear", appId),
    )
  }

  fun forceStopApp(
    deviceId: TrailblazeDeviceId,
    appId: String,
  ) {
    if (isAppRunning(deviceId = deviceId, appId)) {
      execAdbShellCommand(
        deviceId = deviceId,
        args = listOf("am", "force-stop", appId),
      )
      tryUntilSuccessOrThrowException(
        maxWaitMs = 30_000,
        intervalMs = 200,
        conditionDescription = "App $appId should be force stopped",
      ) {
        execAdbShellCommand(
          deviceId = deviceId,
          args = listOf("dumpsys", "package", appId, "|", "grep", "stopped=true"),
        ).contains("stopped=true")
      }
    } else {
      println("App $appId does not have an active process, no need to force stop")
    }
  }

  fun grantPermission(
    deviceId: TrailblazeDeviceId,
    targetAppPackageName: String,
    permission: String,
  ) {
    execAdbShellCommand(
      deviceId = deviceId,
      args = listOf(
        "pm",
        "grant",
        targetAppPackageName,
        permission,
      ),
    )
  }

  // Function to list installed packages on device
  fun listInstalledPackages(deviceId: TrailblazeDeviceId): List<String> = try {
    val processBuilder = createAdbCommandProcessBuilder(
      deviceId = deviceId,
      args = listOf(
        "shell",
        "pm",
        "list",
        "packages",
      ),
    )

    val processResult = processBuilder.runProcess {}

    processResult.outputLines
      .filter { it.isNotBlank() && it.startsWith("package:") }
      .map { line ->
        line.substringAfter("package:")
      }
  } catch (e: Exception) {
    emptyList()
  }

  /**
   * Installs an APK file using adb install command.
   */
  fun installApkFile(apkFile: File, trailblazeDeviceId: TrailblazeDeviceId): Boolean {
    val processBuilder = createAdbCommandProcessBuilder(
      deviceId = trailblazeDeviceId,
      args = listOf(
        "install",
        "-r", // Replace existing application
        "-t", // Allow test packages
        apkFile.absolutePath,
      ),
    )

    val result = processBuilder.runProcess { line ->
      println("adb install output: $line")
    }

    // Check if installation was successful
    // Use case-insensitive check to handle any variations in adb output
    val success = result.fullOutput.contains("Success", ignoreCase = true) && result.exitCode == 0

    if (!success) {
      println("APK installation failed. Output: ${result.fullOutput}")
    }

    return success
  }
}
