package xyz.block.trailblaze.util

import java.io.File
import xyz.block.trailblaze.util.Console

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
        Console.log("Warning: $envVar is not set in the environment")
      }
    }

    Console.log(
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
   * Checks if a command is available on the system PATH. Uses `where` on Windows and `which` on
   * other platforms.
   */
  fun isCommandAvailable(command: String): Boolean {
    return try {
      val whichCommand =
        if (System.getProperty("os.name")?.lowercase()?.contains("win") == true) "where" else "which"
      val result = createProcessBuilder(listOf(whichCommand, command)).runProcess {}
      val found = result.exitCode == 0
      if (found) {
        Console.log("$command installation is available on the PATH")
      } else {
        Console.log("$command installation is not available on the PATH")
      }
      found
    } catch (e: Throwable) {
      Console.log("$command installation not found")
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
