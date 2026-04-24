package xyz.block.trailblaze.android.tools.androidworldbenchmarks

import ai.koog.agents.core.tools.annotations.LLMDescription
import java.util.Base64
import java.io.File
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.android.tools.shellEscape
import xyz.block.trailblaze.device.AdbJvmAndAndroidUtil.withAndroidDeviceCommandExecutor
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Creates a file with the given content at a specified path on the device.
 *
 * Since we run inside Android instrumentation, this uses direct file I/O
 * when the path is accessible, falling back to adb shell for paths that
 * require elevated permissions.
 */
@Serializable
@TrailblazeToolClass(
  name = "androidworldbenchmarks_createFileOnDevice",
  isForLlm = false,
)
@LLMDescription("Creates a file with specified content at a given path on the device.")
data class AndroidWorldBenchmarksCreateFileOnDeviceTrailblazeTool(
  @param:LLMDescription("The directory path on the device where the file should be created.")
  val devicePath: String,
  @param:LLMDescription("The name of the file to create.")
  val fileName: String,
  @param:LLMDescription("The text content to write to the file.")
  val content: String = "",
) : ExecutableTrailblazeTool {
  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    try {
      val dir = File(devicePath)
      val file = File(dir, fileName)

      if (dir.exists() || dir.mkdirs()) {
        // Direct file I/O — fast and handles multi-line content correctly
        file.writeText(content)
      } else {
        // Fallback to shell for paths that need elevated permissions
        return withAndroidDeviceCommandExecutor(toolExecutionContext) { executor ->
          executor.executeShellCommand("mkdir -p ${devicePath.shellEscape()}")
          val fullPath = file.absolutePath
          // Write via shell using base64 to safely handle arbitrary content
          val encoded = Base64.getEncoder().encodeToString(content.toByteArray())
          executor.executeShellCommand(
            "echo $encoded | base64 -d > ${fullPath.shellEscape()}",
          )
          TrailblazeToolResult.Success(
            message = "Created file '$fullPath' with ${content.length} characters.",
          )
        }
      }

      return TrailblazeToolResult.Success(
        message = "Created file '${file.absolutePath}' with ${content.length} characters.",
      )
    } catch (e: Exception) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to create file $fileName at $devicePath: ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }
  }
}
