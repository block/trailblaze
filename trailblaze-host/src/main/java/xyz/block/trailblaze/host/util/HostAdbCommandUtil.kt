package xyz.block.trailblaze.host.util

import kotlinx.datetime.Clock

object HostAdbCommandUtil {

  // Helper to properly escape shell arguments
  private fun shellEscape(s: String) = "'${s.replace("'", "'\\''")}'"

  fun intentToAdbBroadcastCommand(
    action: String,
    component: String,
    extras: Map<String, String>,
  ): String {
    val sb = StringBuilder("am broadcast")

    if (action.isNotEmpty()) {
      sb.append(" -a ").append(action)
    }

    if (component.isNotEmpty()) {
      sb.append(" -n ").append(component)
    }

    extras.keys.forEach { key ->
      val value = extras.get(key)
      if (value is String) {
        sb.append(" --es ${shellEscape(key)} ${shellEscape(value)}")
      }
      // Extend this if you need more types (e.g., --ez for booleans, etc.)
    }

    return sb.toString()
  }

  fun execTerminal(fullCommand: List<String>): String {
    println("Executing command: ${fullCommand.joinToString(" ")}")
    val process = ProcessBuilder(fullCommand)
      .redirectErrorStream(true)
      .start()

    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()

    if (exitCode != 0) {
      throw RuntimeException("ADB command failed with exit code $exitCode: $output")
    }

    return output.trim()
  }

  fun execShellCommand(shellCommand: String): String {
    val fullCommand = listOf("adb", "shell") + shellCommand.split(" ")
    return execTerminal(fullCommand)
  }

  fun grantPermission(
    targetAppPackageName: String,
    permission: String,
  ) {
    execShellCommand("pm grant $targetAppPackageName $permission")
  }

  fun clearPackageData(targetAppPackageName: String) {
    execShellCommand("pm clear $targetAppPackageName")
  }

  fun isAppRunning(appId: String): Boolean {
    val output = execShellCommand("pidof $appId")
    println("pidof $appId: $output")
    val isRunning = output.trim().isNotEmpty()
    return isRunning
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
    if (successful == false) {
      error("Timed out (${maxWaitMs}ms limit) met [$conditionDescription]")
    }
  }

  fun launchApp(
    appId: String,
  ) {
    execShellCommand("monkey -p $appId 1")
  }

  fun clearAppData(
    appId: String,
  ) {
    execShellCommand("pm clear $appId")
  }

  fun forceStop(
    appId: String,
  ) {
    execShellCommand("am force-stop $appId")
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
    var elapsedTimeMs = 0L
    while (elapsedTimeMs < maxWaitMs) {
      val conditionResult: Boolean = try {
        condition()
      } catch (e: Exception) {
        println(
          "Ignored Exception while computing Condition [$conditionDescription], Exception [${e.message}]",
        )
        false
      }
      if (conditionResult) {
        println("Condition [$conditionDescription] met after ${elapsedTimeMs}ms")
        return true
      } else {
        println(
          "Condition [$conditionDescription] not yet met after ${elapsedTimeMs}ms with timeout of ${maxWaitMs}ms",
        )
        Thread.sleep(intervalMs)
        elapsedTimeMs = Clock.System.now().toEpochMilliseconds() - startTime.toEpochMilliseconds()
      }
    }
    println("Timed out (${maxWaitMs}ms limit) met [$conditionDescription] after ${elapsedTimeMs}ms")
    return false
  }
}
