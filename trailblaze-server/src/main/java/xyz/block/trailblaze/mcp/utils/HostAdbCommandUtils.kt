package xyz.block.trailblaze.mcp.utils

import java.io.File

object HostAdbCommandUtils {
  fun createAdbCommandProcessBuilder(
    args: List<String>,
    deviceId: String?,
    workingDir: File? = null,
  ): ProcessBuilder {
    val args = mutableListOf<String>().apply {
      add("adb")
      if (!deviceId.isNullOrBlank()) {
        add("-s")
        add(deviceId)
      }
      this.addAll(args)
    }
    return createProcessBuilder(args)
  }

  fun createProcessBuilder(
    args: List<String>,
    workingDir: File? = null,
  ): ProcessBuilder = try {
    val processBuilder = ProcessBuilder(args)
      .redirectErrorStream(true)

    if (workingDir != null) {
      processBuilder.directory(workingDir)
    }

    // Get the environment map
    System.getenv().keys.forEach { envVar ->
      val value = System.getenv(envVar)
      if (value != null) {
        processBuilder.environment()[envVar] = value
      } else {
        println("Warning: $envVar is not set in the environment")
      }
    }

    println(
      buildString {
        append("Starting process: ")
        append(processBuilder.command().joinToString(" "))
      },
    )

    processBuilder
  } catch (e: Exception) {
    throw RuntimeException("Failed to start process for $args. Error: ${e.message}", e)
  }

  fun ProcessBuilder.runProcess(
    outputLineCallback: (String) -> Unit,
  ): CommandProcessResult {
    val processBuilder = this
    processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
    val process = processBuilder.start()
    val outputLines = mutableListOf<String>()

    // Read output line by line and call callback for each line
    process.inputStream.bufferedReader().use { reader ->
      var line: String?
      while (reader.readLine().also { line = it } != null) {
        line?.let {
          outputLines.add(it)
          outputLineCallback(it)
        }
      }
    }

    val exitCode = process.waitFor()

    if (exitCode != 0) {
      return CommandProcessResult(
        outputLines,
        exitCode,
        "ADB command failed with exit code $exitCode: $outputLines.joinToString(\"\\n\")",
      )
    }

    return CommandProcessResult(outputLines, exitCode)
  }
}
