package xyz.block.trailblaze.android.tools.androidworldbenchmarks

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.device.AdbJvmAndAndroidUtil.withAndroidDeviceCommandExecutor
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Runs an adb shell command and asserts the output matches an expected value.
 *
 * Useful for benchmark validation — e.g., checking system settings values,
 * verifying file existence, reading file content, checking the foreground app.
 */
@Serializable
@TrailblazeToolClass(
  name = "androidworldbenchmarks_assertAdbShellOutput",
  isForLlm = false,
)
@LLMDescription("Runs an adb shell command and asserts the output matches expected value.")
data class AndroidWorldBenchmarksAssertAdbShellOutputTrailblazeTool(
  @param:LLMDescription("The adb shell command to execute.")
  val command: String,
  @param:LLMDescription("The expected output to match against.")
  val expectedOutput: String,
  @param:LLMDescription("How to match: 'contains', 'exact', or 'regex'. Defaults to 'contains'.")
  val matchMode: String = "contains",
) : ExecutableTrailblazeTool {
  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    return withAndroidDeviceCommandExecutor(toolExecutionContext) { executor ->
      try {
        val actualOutput = executor.executeShellCommand(command).trim()
        val matches = when (matchMode) {
          "exact" -> actualOutput == expectedOutput.trim()
          "regex" -> try {
            Regex(expectedOutput).containsMatchIn(actualOutput)
          } catch (e: Exception) {
            return@withAndroidDeviceCommandExecutor TrailblazeToolResult.Error.ExceptionThrown(
              errorMessage = "Invalid regex pattern '$expectedOutput': ${e.message}",
              command = this@AndroidWorldBenchmarksAssertAdbShellOutputTrailblazeTool,
            )
          }
          else -> actualOutput.contains(expectedOutput.trim())
        }

        if (matches) {
          TrailblazeToolResult.Success(
            message = "ADB shell assertion passed for command '$command' " +
              "using '$matchMode' match. Output: '${actualOutput.take(200)}'",
          )
        } else {
          TrailblazeToolResult.Error.ExceptionThrown(
            errorMessage = "Assertion failed for command '$command'. " +
              "Expected ($matchMode): '$expectedOutput', " +
              "Actual: '$actualOutput'",
            command = this@AndroidWorldBenchmarksAssertAdbShellOutputTrailblazeTool,
          )
        }
      } catch (e: Exception) {
        TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "Failed to execute command '$command': ${e.message}",
          command = this@AndroidWorldBenchmarksAssertAdbShellOutputTrailblazeTool,
          stackTrace = e.stackTraceToString(),
        )
      }
    }
  }
}
