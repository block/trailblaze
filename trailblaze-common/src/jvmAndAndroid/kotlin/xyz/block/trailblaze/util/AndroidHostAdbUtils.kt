package xyz.block.trailblaze.util

import xyz.block.trailblaze.android.tools.shellEscape
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.model.AppVersionInfo
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess
import java.io.File

object AndroidHostAdbUtils {

  /**
   * Builds the argv list for `adb shell am broadcast ...`. Every user-supplied arg is
   * wrapped in single quotes via [shellEscape] because `adb shell` joins argv with
   * spaces and hands the result to the device's `sh`, which would otherwise split
   * on whitespace or interpret metacharacters (`;`, `$`, backtick, etc.) inside
   * action, component, extra keys, or extra values.
   */
  fun intentToAdbBroadcastCommandArgs(
    action: String,
    component: String,
    extras: Map<String, Any>,
  ): List<String> {
    val args = buildList<String> {
      add("am")
      add("broadcast")
      if (action.isNotEmpty()) {
        add("-a")
        add(action.shellEscape())
      }
      if (component.isNotEmpty()) {
        add("-n")
        add(component.shellEscape())
      }
      extras.forEach { (key, value) ->
        val flag = when (value) {
          is Boolean -> "--ez"
          is Int -> "--ei"
          is Long -> "--el"
          is Float -> "--ef"
          else -> "--es"
        }
        add(flag)
        add(key.shellEscape())
        add(value.toString().shellEscape())
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
      add(AdbPathResolver.ADB_COMMAND)
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
      Console.log("Port forward tcp:$localPort -> tcp:$remotePort already exists")
      ProcessBuilder("echo", "Port forward already exists").start()
    } else {
      Console.log("Setting up port forward tcp:$localPort -> tcp:$remotePort")
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
      Console.log("Port reverse tcp:$localPort -> tcp:$remotePort already exists")
      ProcessBuilder("echo", "Port reverse already exists").start()
    } else {
      Console.log("Setting up port forward tcp:$localPort -> tcp:$remotePort")
      createAdbCommandProcessBuilder(
        deviceId = deviceId,
        args = listOf("reverse", "tcp:$localPort", "tcp:$remotePort"),
      ).start()
    }
  } catch (e: Exception) {
    throw RuntimeException("Failed to start port forwarding: ${e.message}")
  }

  fun execAdbShellCommand(deviceId: TrailblazeDeviceId, args: List<String>): String {
    Console.log("adb shell ${args.joinToString(" ")}")
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
    Console.log("pidof $appId: $output")
    val isRunning = output.trim().isNotEmpty()
    return isRunning
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
      PollingUtils.tryUntilSuccessOrThrowException(
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
      Console.log("App $appId does not have an active process, no need to force stop")
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
   * Gets version information for an installed Android app.
   *
   * Uses `adb shell dumpsys package <packageName>` to retrieve version details.
   * Parses output like: `versionCode=67500009 minSdk=28 targetSdk=34`
   *
   * @param deviceId The device to query
   * @param packageName The package name of the app (e.g., "com.example.app")
   * @return AppVersionInfo with version details, or null if the app is not installed or parsing fails
   */
  fun getAppVersionInfo(deviceId: TrailblazeDeviceId, packageName: String): AppVersionInfo? = try {
    val output = execAdbShellCommand(
      deviceId = deviceId,
      args = listOf("dumpsys", "package", packageName),
    )

    // Find the versionCode line that appears after a codePath (to get the installed version).
    // The output contains multiple versionCode entries; we want the one for the installed app.
    // Match both user-installed apps (/data/app) and system apps (/system/, /product/).
    val lines = output.lines()
    var foundCodePath = false
    var versionCode: String? = null
    var minSdk: Int? = null
    var versionName: String? = null

    for (line in lines) {
      // Look for codePath that indicates the app location
      if (line.trimStart().startsWith("codePath=")) {
        foundCodePath = true
      }

      // After finding the app path, look for version info
      if (foundCodePath && line.contains("versionCode=")) {
        // Parse: versionCode=67500009 minSdk=28 targetSdk=34
        val versionCodeMatch = Regex("versionCode=(\\d+)").find(line)
        val minSdkMatch = Regex("minSdk=(\\d+)").find(line)

        versionCode = versionCodeMatch?.groupValues?.get(1)
        minSdk = minSdkMatch?.groupValues?.get(1)?.toIntOrNull()
        // Don't break yet - versionName comes after versionCode
      }

      // Also look for versionName (comes after versionCode in the output)
      if (foundCodePath && line.trim().startsWith("versionName=")) {
        versionName = line.trim().substringAfter("versionName=")
        // Now we have all the info we need
        if (versionCode != null) break
      }
    }

    if (versionCode != null) {
      AppVersionInfo(
        trailblazeDeviceId = deviceId,
        versionCode = versionCode,
        versionName = versionName,
        minOsVersion = minSdk,
      )
    } else {
      null
    }
  } catch (e: Exception) {
    Console.log("Failed to get version info for $packageName: ${e.message}")
    null
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
      Console.log("adb install output: $line")
    }

    // Check if installation was successful
    // Use case-insensitive check to handle any variations in adb output
    val success = result.fullOutput.contains("Success", ignoreCase = true) && result.exitCode == 0

    if (!success) {
      Console.log("APK installation failed. Output: ${result.fullOutput}")
    }

    return success
  }
}
