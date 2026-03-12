package xyz.block.trailblaze.android.tools.androidworldbenchmarks

import ai.koog.agents.core.tools.annotations.LLMDescription
import java.io.File
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.android.tools.shellEscape
import xyz.block.trailblaze.device.AdbJvmAndAndroidUtil.withAndroidDeviceCommandExecutor
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Asserts that a file exists (or does not exist) at a given path on the device.
 *
 * Tries direct [File.exists] first, falls back to shell `test -e` for
 * paths requiring elevated permissions. Useful for validating that photos were
 * taken, recordings saved, notes created or deleted, drawings exported, etc.
 */
@Serializable
@TrailblazeToolClass(
  name = "androidworldbenchmarks_assertFileExistsOnDevice",
  isForLlm = false,
)
@LLMDescription("Asserts that a file exists (or does not exist) at the given path on the device.")
data class AndroidWorldBenchmarksAssertFileExistsOnDeviceTrailblazeTool(
  @param:LLMDescription("Absolute path to the file on the device.")
  val filePath: String,
  @param:LLMDescription("If true, asserts the file exists. If false, asserts it does NOT exist. Defaults to true.")
  val shouldExist: Boolean = true,
) : ExecutableTrailblazeTool {
  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    try {
      val exists = if (File(filePath).exists()) {
        true
      } else {
        // Fallback for permission-restricted paths
        return withAndroidDeviceCommandExecutor(toolExecutionContext) { executor ->
          val escaped = filePath.shellEscape()
          val output = executor.executeShellCommand("test -e $escaped && echo EXISTS || echo MISSING")
          val shellExists = output.trim() == "EXISTS"
          checkAssertion(shellExists)
        }
      }

      return checkAssertion(exists)
    } catch (e: Exception) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to check file existence at '$filePath': ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }
  }

  private fun checkAssertion(exists: Boolean): TrailblazeToolResult {
    return if (exists == shouldExist) {
      TrailblazeToolResult.Success(
        message = "File existence assertion passed for '$filePath': " +
          "expected ${if (shouldExist) "exists" else "missing"}, observed ${if (exists) "exists" else "missing"}.",
      )
    } else {
      val expectation = if (shouldExist) "to exist" else "to NOT exist"
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Assertion failed: expected '$filePath' $expectation, but it ${if (exists) "exists" else "does not exist"}.",
        command = this,
      )
    }
  }
}
