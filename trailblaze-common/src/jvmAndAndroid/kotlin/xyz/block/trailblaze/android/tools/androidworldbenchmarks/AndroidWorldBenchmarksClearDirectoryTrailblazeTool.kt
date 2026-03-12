package xyz.block.trailblaze.android.tools.androidworldbenchmarks

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.android.tools.shellEscape
import xyz.block.trailblaze.device.AdbJvmAndAndroidUtil.withAndroidDeviceCommandExecutor
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Clears all contents of a directory on the device without removing the
 * directory itself.
 *
 * Includes a safety check that refuses to clear dangerous paths like `/`,
 * `/data`, `/system`, etc. Used for benchmark setup/teardown to wipe app
 * data directories (e.g., Markor notes, camera photos, audio recordings).
 */
@Serializable
@TrailblazeToolClass(
  name = "androidworldbenchmarks_clearDirectory",
  isForLlm = false,
)
@LLMDescription("Clears all contents of a directory on the device.")
data class AndroidWorldBenchmarksClearDirectoryTrailblazeTool(
  @param:LLMDescription("Absolute path to the directory to clear.")
  val directoryPath: String,
) : ExecutableTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val normalized = directoryPath.trimEnd('/')
    if (normalized in FORBIDDEN_PATHS || normalized.count { it == '/' } < 2) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Refusing to clear '$directoryPath' — path is too broad or protected.",
        command = this,
      )
    }

    return withAndroidDeviceCommandExecutor(toolExecutionContext) { executor ->
      try {
        executor.executeShellCommand("rm -rf ${normalized.shellEscape()}/*")
        TrailblazeToolResult.Success(
          message = "Cleared all contents inside '$directoryPath'.",
        )
      } catch (e: Exception) {
        TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "Failed to clear directory '$directoryPath': ${e.message}",
          command = this@AndroidWorldBenchmarksClearDirectoryTrailblazeTool,
          stackTrace = e.stackTraceToString(),
        )
      }
    }
  }

  companion object {
    private val FORBIDDEN_PATHS = setOf(
      "", "/", "/data", "/system", "/vendor", "/storage",
      "/storage/emulated", "/storage/emulated/0",
      "/sdcard", "/mnt",
    )
  }
}
