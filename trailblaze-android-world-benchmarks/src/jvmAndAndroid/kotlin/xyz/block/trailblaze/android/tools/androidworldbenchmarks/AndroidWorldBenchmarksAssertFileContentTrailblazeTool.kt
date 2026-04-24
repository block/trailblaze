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
 * Reads a file on the device and asserts its content matches an expected value.
 *
 * Supports exact, contains, regex, and fuzzy matching modes. Fuzzy matching
 * normalizes whitespace and is case-insensitive, which is useful for validating
 * user-entered text in apps like Markor where formatting may vary slightly.
 */
@Serializable
@TrailblazeToolClass(
  name = "androidworldbenchmarks_assertFileContent",
  isForLlm = false,
)
@LLMDescription("Reads a file on the device and asserts its content matches expected value.")
data class AndroidWorldBenchmarksAssertFileContentTrailblazeTool(
  @param:LLMDescription("Absolute path to the file on the device.")
  val filePath: String,
  @param:LLMDescription("The expected content to match against.")
  val expectedContent: String,
  @param:LLMDescription("How to match: 'contains', 'exact', 'regex', or 'fuzzy'. Defaults to 'contains'.")
  val matchMode: String = "contains",
) : ExecutableTrailblazeTool {
  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    try {
      val file = File(filePath)
      val actualContent = if (file.exists() && file.canRead()) {
        file.readText()
      } else {
        // Fallback for permission-restricted paths
        return withAndroidDeviceCommandExecutor(toolExecutionContext) { executor ->
          val shellContent = executor.executeShellCommand("cat ${filePath.shellEscape()}")
          checkContentAssertion(shellContent)
        }
      }

      return checkContentAssertion(actualContent)
    } catch (e: Exception) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to read file '$filePath': ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }
  }

  private fun checkContentAssertion(actualContent: String): TrailblazeToolResult {
    val matches = when (matchMode) {
      "exact" -> actualContent.trim() == expectedContent.trim()
      "regex" -> try {
        Regex(expectedContent).containsMatchIn(actualContent)
      } catch (e: Exception) {
        return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "Invalid regex pattern '$expectedContent': ${e.message}",
          command = this,
        )
      }
      "fuzzy" -> normalizeForFuzzyMatch(actualContent).contains(
        normalizeForFuzzyMatch(expectedContent),
      )
      else -> actualContent.contains(expectedContent)
    }

    return if (matches) {
      TrailblazeToolResult.Success(
        message = "File content assertion passed for '$filePath' " +
          "using '$matchMode' match.",
      )
    } else {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "File content assertion failed for '$filePath'. " +
          "Expected ($matchMode): '${expectedContent.take(200)}', " +
          "Actual: '${actualContent.take(200)}'",
        command = this,
      )
    }
  }

  private fun normalizeForFuzzyMatch(text: String): String =
    text.trim().lowercase().replace(Regex("\\s+"), " ")
}
