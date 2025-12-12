package xyz.block.trailblaze

import androidx.test.platform.app.InstrumentationRegistry
import xyz.block.trailblaze.InstrumentationUtil.withInstrumentation
import xyz.block.trailblaze.android.AndroidTrailblazeDeviceInfoUtil
import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.rules.TrailblazeLoggingRule

class TrailblazeAndroidLoggingRule(
  trailblazeDeviceClassifiersProvider: () -> List<TrailblazeDeviceClassifier>,
) : TrailblazeLoggingRule(
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
    try {
      // Currently disabled due to exception on some API levels
      withInstrumentation {
        FileReadWriteUtil.writeToDownloadsFile(
          context = context,
          fileName = "$sessionId-trace.json",
          contentBytes = json.toByteArray(),
          directory = LOGS_DIR,
        )
      }
    } catch (e: Exception) {
      println("Error writing trace file to disk: ${e.message}")
    }
  },
) {
  override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo = {
    AndroidTrailblazeDeviceInfoUtil.collectCurrentDeviceInfo(
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      trailblazeDeviceClassifiers = trailblazeDeviceClassifiersProvider(),
    )
  }

  companion object {
    private const val LOGS_DIR = "trailblaze-logs"
  }
}
