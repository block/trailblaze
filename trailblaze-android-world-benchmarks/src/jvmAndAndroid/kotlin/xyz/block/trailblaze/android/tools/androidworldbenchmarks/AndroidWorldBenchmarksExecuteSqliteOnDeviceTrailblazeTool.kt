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
 * Executes SQL statements on a SQLite database on the device.
 *
 * Useful for benchmark setup — e.g., inserting calendar events, expense rows,
 * recipe entries, or clearing database tables before a test run.
 *
 * Optionally force-stops the owning app first so the database file isn't locked.
 */
@Serializable
@TrailblazeToolClass(
  name = "androidworldbenchmarks_executeSqliteOnDevice",
  isForLlm = false,
)
@LLMDescription("Executes SQL statements on a SQLite database on the device.")
data class AndroidWorldBenchmarksExecuteSqliteOnDeviceTrailblazeTool(
  @param:LLMDescription("Absolute path to the SQLite database on the device.")
  val dbPath: String,
  @param:LLMDescription("List of SQL statements to execute sequentially.")
  val sqlStatements: List<String>,
  @param:LLMDescription("Package name of the app that owns the database. If set, the app will be force-stopped before accessing the DB to avoid locks.")
  val appPackageName: String? = null,
) : ExecutableTrailblazeTool {
  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    return withAndroidDeviceCommandExecutor(toolExecutionContext) { executor ->
      // Force-stop the app if specified to release any DB locks
      if (appPackageName != null) {
        executor.forceStopApp(appPackageName)
      }

      sqlStatements.forEach { sql ->
        try {
          val escapedDbPath = dbPath.shellEscape()
          val escapedSql = sql.shellEscape()
          executor.executeShellCommand("sqlite3 $escapedDbPath $escapedSql")
        } catch (e: Exception) {
          return@withAndroidDeviceCommandExecutor TrailblazeToolResult.Error.ExceptionThrown(
            errorMessage = "Failed to execute SQL on $dbPath: ${e.message}",
            command = this@AndroidWorldBenchmarksExecuteSqliteOnDeviceTrailblazeTool,
            stackTrace = e.stackTraceToString(),
          )
        }
      }

      val sqlPreview = sqlStatements
        .joinToString(separator = " | ")
      val appStopContext = appPackageName?.let { " after force-stopping '$it'" } ?: ""
      TrailblazeToolResult.Success(
        message = "Executed ${sqlStatements.size} SQL statement(s) on '$dbPath'$appStopContext. " +
          "Statement preview: $sqlPreview",
      )
    }
  }
}
