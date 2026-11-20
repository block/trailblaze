package xyz.block.trailblaze

import androidx.test.platform.app.InstrumentationRegistry
import xyz.block.trailblaze.InstrumentationUtil.withInstrumentation
import xyz.block.trailblaze.android.AndroidTrailblazeDeviceInfoUtil.getCurrentLocale
import xyz.block.trailblaze.android.AndroidTrailblazeDeviceInfoUtil.getDeviceMetadata
import xyz.block.trailblaze.android.AndroidTrailblazeDeviceInfoUtil.getDeviceOrientation
import xyz.block.trailblaze.android.AndroidTrailblazeDeviceInfoUtil.getDisplayMetrics
import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.devices.CatalogDeviceArgs
import xyz.block.trailblaze.devices.DeviceClassifierKey
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.OnDeviceLogFileName
import xyz.block.trailblaze.logs.client.TrailblazeCompactJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeScreenStateLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import xyz.block.trailblaze.util.Console

class TrailblazeAndroidLoggingRule internal constructor(
  private val physicalDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  private val trailblazeDeviceClassifiersProvider: () -> List<TrailblazeDeviceClassifier>,
  logsBaseUrl: String,
  initialDriverType: TrailblazeDriverType,
  private val requestedDeviceClassifiersProvider: () -> List<TrailblazeDeviceClassifier>,
  additionalLogEmitter: LogEmitter? = null,
  noLogging: Boolean = false,
) : TrailblazeLoggingRule(
  logsBaseUrl = logsBaseUrl,
  noLogging = noLogging,
  additionalLogEmitter = additionalLogEmitter,
  writeLogToDisk = { currentTestName: SessionId, log: TrailblazeLog ->
    try {
      val json = TrailblazeCompactJsonInstance.encodeToString(TrailblazeLog.serializer(), log)
      val fileName = OnDeviceLogFileName.forLog(currentTestName, log.timestamp.toEpochMilliseconds())
      FileReadWriteUtil.writeToDownloadsFile(
        context = InstrumentationRegistry.getInstrumentation().context,
        fileName = fileName,
        contentBytes = json.toByteArray(),
        directory = LOGS_DIR,
      )
    } catch (e: Exception) {
      Console.log("Error writing log to disk: ${e.message}")
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
      Console.log("Error writing screenshot to disk: ${e.message}")
    }
  },
  writeTraceToDisk = { sessionId: SessionId, json: String ->
    try {
      withInstrumentation {
        FileReadWriteUtil.writeToDownloadsFile(
          context = context,
          fileName = "${sessionId.value}-trace.json",
          contentBytes = json.toByteArray(),
          directory = LOGS_DIR,
        )
      }
    } catch (e: Exception) {
      Console.log("Error writing trace file to disk: ${e.message}")
    }
  },
) {

  constructor(
    trailblazeDeviceIdProvider: () -> TrailblazeDeviceId,
    trailblazeDeviceClassifiersProvider: () -> List<TrailblazeDeviceClassifier>,
    additionalLogEmitter: LogEmitter? = null,
  ) : this(
    physicalDeviceInfoProvider = {
      val displayMetrics = getDisplayMetrics()
      TrailblazeDeviceInfo(
        trailblazeDeviceId = trailblazeDeviceIdProvider(),
        trailblazeDriverType = TrailblazeDriverType.DEFAULT_ANDROID,
        locale = getCurrentLocale().toLanguageTag(),
        orientation = getDeviceOrientation(),
        widthPixels = displayMetrics.widthPixels,
        heightPixels = displayMetrics.heightPixels,
        metadata = getDeviceMetadata(),
      )
    },
    trailblazeDeviceClassifiersProvider = trailblazeDeviceClassifiersProvider,
    logsBaseUrl = InstrumentationArgUtil.logsEndpoint(),
    initialDriverType = InstrumentationArgUtil.driverType() ?: TrailblazeDriverType.DEFAULT_ANDROID,
    requestedDeviceClassifiersProvider = CatalogDeviceArgs::classifiers,
    additionalLogEmitter = additionalLogEmitter,
  )

  protected override val useBinaryLogTransport: Boolean = true

  /** These timestamps — trace spans and agent logs — are stamped by the device's own clock, which drifts from the host's by seconds. */
  protected override val useDeviceClock: Boolean = true

  /**
   * Override the driver type reported in session logs. Set this before calling
   * [AndroidTrailblazeRule.runSuspend] so that the [SessionStatus.Started] log
   * reflects the actual runtime driver (e.g., when `--driver ANDROID_ONDEVICE_ACCESSIBILITY`
   * is passed via CLI or `-e trailblaze.driverType` instrumentation arg).
   *
   * Defaults to the value from the `trailblaze.driverType` instrumentation arg if set,
   * otherwise [TrailblazeDriverType.DEFAULT_ANDROID], so this stays accurate if that default moves.
   */
  var driverTypeOverride: TrailblazeDriverType =
    initialDriverType

  /** Host-validated locale/variant classifiers for the current run. Reset on every request. */
  var deviceClassifiersOverride: List<TrailblazeDeviceClassifier> = emptyList()

  /** The catalog device a device-farm run says it stands for; see [CatalogDeviceArgs]. */
  private val requestedDeviceClassifiers: List<TrailblazeDeviceClassifier> by lazy {
    requestedDeviceClassifiersProvider()
  }

  var recordingClassifiers: List<TrailblazeDeviceClassifier>? = null

  fun selectionClassifiers(): List<TrailblazeDeviceClassifier> = selectionClassifiersFor(deviceClassifiersOverride)

  internal fun selectionClassifiersFor(override: List<TrailblazeDeviceClassifier>): List<TrailblazeDeviceClassifier> = override.ifEmpty {
    DeviceClassifierKey.refine(trailblazeDeviceClassifiersProvider(), requestedDeviceClassifiers)
  }

  override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo = {
    deviceInfoFor(selectionClassifiers(), recordingClassifiers)
  }

  internal fun deviceInfoFor(
    selection: List<TrailblazeDeviceClassifier>,
    recordings: List<TrailblazeDeviceClassifier>?,
  ): TrailblazeDeviceInfo {
    val info = physicalDeviceInfoProvider()
    return info.copy(
      trailblazeDriverType = driverTypeOverride,
      classifiers = recordings ?: selection,
      metadata = info.metadata + (DeviceClassifierKey.EXECUTION_DEVICE_CLASS to selection.joinToString("-") { it.classifier }),
    )
  }

  companion object {
    private const val LOGS_DIR = "trailblaze-logs"
  }
}
