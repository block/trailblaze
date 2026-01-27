package xyz.block.trailblaze.util

import java.io.File

object TrailblazeProcessBuilderUtils {

  /**
   * Note: All System Environment Variables will be passed along to the target process
   */
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

  /**
   * Checks if a command is available on the system PATH using 'which'.
   * This is faster than running the command itself since it only searches PATH directories.
   */
  fun isCommandAvailable(command: String): Boolean {
    return try {
      val result = createProcessBuilder(listOf("which", command)).runProcess {}
      val found = result.exitCode == 0
      if (found) {
        println("$command installation is available on the PATH")
      } else {
        println("$command installation is not available on the PATH")
      }
      found
    } catch (e: Throwable) {
      println("$command installation not found")
      false
    }
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
        "Command failed with exit code $exitCode: $outputLines.joinToString(\"\\n\")",
      )
    }

    return CommandProcessResult(outputLines, exitCode)
  }
}
