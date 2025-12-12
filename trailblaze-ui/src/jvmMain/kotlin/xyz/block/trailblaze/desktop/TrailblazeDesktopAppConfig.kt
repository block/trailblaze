package xyz.block.trailblaze.desktop

import maestro.device.Device
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.ui.recordings.RecordedTrailsRepo
import java.io.File

/**
 * A common interface containing what is needed for desktop app configuration.
 */
abstract class TrailblazeDesktopAppConfig(
  /** The LLM model that should be used as default if none is selected. */
  private val defaultLlmModel: TrailblazeLlmModel,
  /** The default model list. */
  private val defaultProviderModelList: TrailblazeLlmModelList,
) {

  abstract fun getCurrentlyAvailableLlmModelLists(): Set<TrailblazeLlmModelList>

  /** That's where logs are stored and managed. */
  abstract val logsRepo: LogsRepo

  abstract val trailblazeSettingsRepo: TrailblazeSettingsRepo

  abstract val defaultAppDataDir: File
  abstract val recordedTrailsRepo: RecordedTrailsRepo

  abstract val appIconProvider: AppIconProvider

  abstract val defaultAppTarget: TrailblazeHostAppTarget

  abstract val availableAppTargets: Set<TrailblazeHostAppTarget>

  abstract fun getInstalledAppIds(connectedMaestroDevice: Device.Connected): Set<String>

  fun getCurrentLlmModel(): TrailblazeLlmModel {
    val serverState = trailblazeSettingsRepo.serverStateFlow.value
    val savedProviderId = serverState.appConfig.llmProvider
    val savedModelId: String = serverState.appConfig.llmModel
    val currentProviderModelList =
      getCurrentlyAvailableLlmModelLists().firstOrNull { it.provider.id == savedProviderId }
        ?: defaultProviderModelList

    val selectedTrailblazeLlmModel: TrailblazeLlmModel =
      currentProviderModelList.entries.firstOrNull { it.modelId == savedModelId }
        ?: defaultLlmModel
    return selectedTrailblazeLlmModel
  }

  val deviceManager by lazy {
    TrailblazeDeviceManager(
      settingsRepo = trailblazeSettingsRepo,
      availableAppTargets = availableAppTargets,
      appIconProvider = appIconProvider,
      defaultHostAppTarget = defaultAppTarget,
      getInstalledAppIds = ::getInstalledAppIds
    ).apply {
      // Start polling device status to detect running sessions on Android devices
      startPollingDeviceStatus()
    }
  }

}