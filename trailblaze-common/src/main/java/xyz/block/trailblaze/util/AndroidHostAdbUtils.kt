package xyz.block.trailblaze.util

import kotlinx.datetime.Clock
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import java.io.File

/**
 * Utility object for executing ADB commands from the host machine.
 * 
 * Note: This uses [HostAdbExecutor] for actual ADB operations. The factory must be
 * initialized before using these methods (typically done in the host module startup).
 */
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
    val adbExecutor = HostAdbExecutor.create(deviceId)
    adbExecutor.uninstall(appPackageId)
  }

  suspend fun isAppInstalled(appId: String, deviceId: TrailblazeDeviceId): Boolean =
    listInstalledPackages(deviceId).any { it == appId }

  fun adbPortForward(
    deviceId: TrailblazeDeviceId,
    localPort: Int,
    remotePort: Int = localPort,
  ) {
    val adbExecutor = HostAdbExecutor.create(deviceId)
    adbExecutor.forward(localPort, remotePort)
  }

  fun adbPortReverse(
    deviceId: TrailblazeDeviceId,
    localPort: Int,
    remotePort: Int = localPort,
  ) {
    val adbExecutor = HostAdbExecutor.create(deviceId)
    adbExecutor.reverse(remotePort, localPort)
  }

  fun execAdbShellCommand(deviceId: TrailblazeDeviceId, args: List<String>): String {
    println("adb shell ${args.joinToString(" ")}")
    val adbExecutor = HostAdbExecutor.create(deviceId)
    return adbExecutor.shell(args.joinToString(" "))
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
    val adbExecutor = HostAdbExecutor.create(deviceId)
    val output = adbExecutor.shell("pm list packages")
    
    output.lines()
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
    val adbExecutor = HostAdbExecutor.create(trailblazeDeviceId)
    return adbExecutor.install(
      apkFile = apkFile,
      reinstall = true,
      allowTestPackages = true,
    )
  }
}
