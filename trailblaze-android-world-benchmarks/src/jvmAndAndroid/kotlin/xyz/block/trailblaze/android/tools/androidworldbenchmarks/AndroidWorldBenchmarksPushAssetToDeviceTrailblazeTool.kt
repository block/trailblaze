package xyz.block.trailblaze.android.tools.androidworldbenchmarks

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.device.AdbJvmAndAndroidUtil.withAndroidDeviceCommandExecutor
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Copies a file from test resources to a path on the device.
 *
 * Uses [AndroidDeviceCommandExecutor.copyTestResourceToDevice] which reads from
 * test APK assets on Android, or classpath resources on JVM, and writes to the
 * specified device path. Useful for benchmark setup where pre-generated binary
 * files (audio, images) need to be placed on the device before the agent task runs.
 */
@Serializable
@TrailblazeToolClass(
  name = "androidworldbenchmarks_pushAssetToDevice",
  isForLlm = false,
)
@LLMDescription("Copies a file from test assets to a specified path on the device.")
data class AndroidWorldBenchmarksPushAssetToDeviceTrailblazeTool(
  @param:LLMDescription("Path to the file within the test assets (e.g., 'benchmarks/audio/song1.mp3').")
  val assetPath: String,
  @param:LLMDescription("Absolute destination path on the device (e.g., '/storage/emulated/0/Music/song1.mp3').")
  val devicePath: String,
) : ExecutableTrailblazeTool {
  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    return withAndroidDeviceCommandExecutor(toolExecutionContext) { executor ->
      try {
        executor.copyTestResourceToDevice(assetPath, devicePath)
        TrailblazeToolResult.Success(
          message = "Copied asset '$assetPath' to '$devicePath'.",
        )
      } catch (e: Exception) {
        TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "Failed to push asset '$assetPath' to '$devicePath': ${e.message}",
          command = this@AndroidWorldBenchmarksPushAssetToDeviceTrailblazeTool,
          stackTrace = e.stackTraceToString(),
        )
      }
    }
  }
}
