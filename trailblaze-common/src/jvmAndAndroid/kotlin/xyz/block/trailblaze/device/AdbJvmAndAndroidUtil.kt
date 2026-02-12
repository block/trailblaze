package xyz.block.trailblaze.device

import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

object AdbJvmAndAndroidUtil {
  /**
   * Executes the given [block] with an [AndroidDeviceCommandExecutor], or returns an error
   * if the executor is not available in the [toolExecutionContext].
   */
  suspend fun withAndroidDeviceCommandExecutor(
    toolExecutionContext: TrailblazeToolExecutionContext,
    block: suspend (AndroidDeviceCommandExecutor) -> TrailblazeToolResult,
  ): TrailblazeToolResult {
    val executor = toolExecutionContext.androidDeviceCommandExecutor
      ?: return TrailblazeToolResult.Error.ExceptionThrown("AndroidDeviceCommandExecutor is not provided")
    return block(executor)
  }
}
