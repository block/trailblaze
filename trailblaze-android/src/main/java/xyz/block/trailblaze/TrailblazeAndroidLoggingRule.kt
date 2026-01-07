package xyz.block.trailblaze

import androidx.test.platform.app.InstrumentationRegistry
import xyz.block.trailblaze.InstrumentationUtil.withInstrumentation
import xyz.block.trailblaze.android.AndroidTrailblazeDeviceInfoUtil.getCurrentLocale
import xyz.block.trailblaze.android.AndroidTrailblazeDeviceInfoUtil.getDeviceMetadata
import xyz.block.trailblaze.android.AndroidTrailblazeDeviceInfoUtil.getDeviceOrientation
import xyz.block.trailblaze.android.AndroidTrailblazeDeviceInfoUtil.getDisplayMetrics
import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeScreenStateLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.rules.TrailblazeLoggingRule

class TrailblazeAndroidLoggingRule(
  trailblazeDeviceIdProvider: () -> TrailblazeDeviceId,
  trailblazeDeviceClassifiersProvider: () -> List<TrailblazeDeviceClassifier>,
) : TrailblazeLoggingRule(
  logsBaseUrl = InstrumentationArgUtil.logsEndpoint(),
  writeLogToDisk = { currentTestName: SessionId, log: TrailblazeLog ->
    try {
      val json = TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), log)
      val fileName = "${currentTestName.value}_${log.timestamp.toEpochMilliseconds()}.json"
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
  writeScreenshotToDisk = { screenshot: TrailblazeScreenStateLog ->
    try {
      FileReadWriteUtil.writeToDownloadsFile(
        context = InstrumentationRegistry.getInstrumentation().context,
        fileName = screenshot.fileName,
        contentBytes = screenshot.screenState.screenshotBytes!!,
        directory = LOGS_DIR,
      )
    } catch (e: Exception) {
      println("Error writing screenshot to disk: ${e.message}")
    }
  },
  writeTraceToDisk = { sessionId: SessionId, json: String ->
    try {
      // Currently disabled due to exception on some API levels
      withInstrumentation {
        FileReadWriteUtil.writeToDownloadsFile(
          context = context,
          fileName = "${sessionId.value}-trace.json",
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
    val displayMetrics = getDisplayMetrics()
    TrailblazeDeviceInfo(
      trailblazeDeviceId = trailblazeDeviceIdProvider(),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      locale = getCurrentLocale().toLanguageTag(),
      orientation = getDeviceOrientation(),
      widthPixels = displayMetrics.widthPixels,
      heightPixels = displayMetrics.heightPixels,
      classifiers = trailblazeDeviceClassifiersProvider(),
      metadata = getDeviceMetadata(),
    )
  }

  companion object {
    private const val LOGS_DIR = "trailblaze-logs"
  }
}
