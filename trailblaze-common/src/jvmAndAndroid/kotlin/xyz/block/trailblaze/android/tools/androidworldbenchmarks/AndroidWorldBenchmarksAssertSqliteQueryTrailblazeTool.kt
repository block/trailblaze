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
 * Runs a SQL query on a SQLite database on the device and asserts the output.
 *
 * Useful for benchmark validation — e.g., checking that calendar events were
 * added, expenses were deleted, playlists were created correctly.
 */
@Serializable
@TrailblazeToolClass(
  name = "androidworldbenchmarks_assertSqliteQuery",
  isForLlm = false,
)
@LLMDescription("Runs a SQL query on a device SQLite database and asserts the output matches expected value.")
data class AndroidWorldBenchmarksAssertSqliteQueryTrailblazeTool(
  @param:LLMDescription("Absolute path to the SQLite database on the device.")
  val dbPath: String,
  @param:LLMDescription("The SQL query to execute (e.g., SELECT statement).")
  val query: String,
  @param:LLMDescription("The expected output to match against.")
  val expectedOutput: String,
  @param:LLMDescription("How to match: 'contains', 'exact', or 'regex'. Defaults to 'contains'.")
  val matchMode: String = "contains",
  @param:LLMDescription("Package name of the app that owns the database. If set, the app will be force-stopped before querying.")
  val appPackageName: String? = null,
) : ExecutableTrailblazeTool {
  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    return withAndroidDeviceCommandExecutor(toolExecutionContext) { executor ->
      try {
        if (appPackageName != null) {
          executor.forceStopApp(appPackageName)
        }

        val actualOutput =
          executor.executeShellCommand("sqlite3 ${dbPath.shellEscape()} ${query.shellEscape()}")
            .trim()

        val matches = when (matchMode) {
          "exact" -> actualOutput == expectedOutput.trim()
          "regex" -> try {
            Regex(expectedOutput).containsMatchIn(actualOutput)
          } catch (e: Exception) {
            return@withAndroidDeviceCommandExecutor TrailblazeToolResult.Error.ExceptionThrown(
              errorMessage = "Invalid regex pattern '$expectedOutput': ${e.message}",
              command = this@AndroidWorldBenchmarksAssertSqliteQueryTrailblazeTool,
            )
          }
          else -> actualOutput.contains(expectedOutput.trim())
        }

        if (matches) {
          TrailblazeToolResult.Success(
            message = "SQLite assertion passed for '$dbPath' using '$matchMode' match. " +
              "Query: '${query.take(120)}'. Output: '${actualOutput.take(200)}'",
          )
        } else {
          TrailblazeToolResult.Error.ExceptionThrown(
            errorMessage = "SQLite assertion failed for query '$query' on $dbPath. " +
              "Expected ($matchMode): '$expectedOutput', " +
              "Actual: '$actualOutput'",
            command = this@AndroidWorldBenchmarksAssertSqliteQueryTrailblazeTool,
          )
        }
      } catch (e: Exception) {
        TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "Failed to execute SQLite query on $dbPath: ${e.message}",
          command = this@AndroidWorldBenchmarksAssertSqliteQueryTrailblazeTool,
          stackTrace = e.stackTraceToString(),
        )
      }
    }
  }
}
