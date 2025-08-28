package xyz.block.trailblaze

import androidx.test.platform.app.InstrumentationRegistry
import xyz.block.trailblaze.InstrumentationUtil.withInstrumentation
import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.rules.TrailblazeLoggingRule

class TrailblazeAndroidLoggingRule(
  sendStartAndEndLogs: Boolean = true,
) : TrailblazeLoggingRule(
  sendStartAndEndLogs = sendStartAndEndLogs,
  logsBaseUrl = InstrumentationArgUtil.logsEndpoint(),
  writeLogToDisk = { currentTestName: String, log: TrailblazeLog ->
    try {
      val json = TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), log)
      val fileName = "${currentTestName}_${log.timestamp.toEpochMilliseconds()}.json"
      FileReadWriteUtil.writeToDownloadsFile(
        context = InstrumentationRegistry.getInstrumentation().context,
        fileName = fileName,
        contentBytes = json.toByteArray(),
        directory = LOGS_DIR,
      )
    } catch (e: Exception) {
      println("Error writing log to disk: ${e.message}")
    }
  },
  writeScreenshotToDisk = { sessionId: String, fileName: String, bytes: ByteArray ->
    try {
      FileReadWriteUtil.writeToDownloadsFile(
        context = InstrumentationRegistry.getInstrumentation().context,
        fileName = fileName,
        contentBytes = bytes,
        directory = LOGS_DIR,
      )
    } catch (e: Exception) {
      println("Error writing screenshot to disk: ${e.message}")
    }
  },
  writeTraceToDisk = { sessionId: String, json: String ->
    // Currently disabled due to exception on some API levels
    withInstrumentation {
      FileReadWriteUtil.writeToDownloadsFile(
        context = context,
        fileName = "$sessionId-trace.json",
        contentBytes = json.toByteArray(),
        directory = LOGS_DIR,
      )
    }
  },
) {
  companion object {
    private const val LOGS_DIR = "trailblaze-logs"
  }
}
