package xyz.block.trailblaze.desktop

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.TrailblazeBuiltInTabs
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.composables.DefaultDeviceClassifierIconProvider
import xyz.block.trailblaze.ui.composables.DeviceClassifierIconProvider
import xyz.block.trailblaze.ui.model.TrailblazeAppTab
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.ui.models.TrailblazeServerState
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

  /**
   * Environment variables that should be surfaced in UI/settings for this configuration.
   */
  open val customEnvVarNames: List<String> = emptyList()

  /**
   * Additional global settings content to render in the Settings tab.
   */
  open val globalSettingsContent: @Composable ColumnScope.(TrailblazeServerState) -> Unit = {}

  /**
   * Additional instrumentation args to pass to YAML runs for this configuration.
   */
  open suspend fun additionalInstrumentationArgs(): Map<String, String> = emptyMap()

  abstract fun getCurrentlyAvailableLlmModelLists(): Set<TrailblazeLlmModelList>

  /** That's where logs are stored and managed. */
  abstract val logsRepo: LogsRepo

  abstract val trailblazeSettingsRepo: TrailblazeSettingsRepo

  abstract val defaultAppDataDir: File
  abstract val recordedTrailsRepo: RecordedTrailsRepo

  abstract val appIconProvider: AppIconProvider

  /**
   * Provider for device classifier icons. Internal builds can override
   * to add proprietary icons like PNG resources for "squid" devices.
   */
  open val deviceClassifierIconProvider: DeviceClassifierIconProvider =
    DefaultDeviceClassifierIconProvider

  abstract val defaultAppTarget: TrailblazeHostAppTarget

  abstract val availableAppTargets: Set<TrailblazeHostAppTarget>

  abstract fun getInstalledAppIds(trailblazeDeviceId: TrailblazeDeviceId): Set<String>

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

  /**
   * Returns the list of tabs for this desktop app configuration.
   * Override in subclasses to customize which tabs are shown.
   *
   * @param deviceManager runtime device manager
   * @param yamlRunner runner for YAML executions
   * @param additionalInstrumentationArgs provider for instrumentation args (defaults to config implementation)
   */
  open fun getTabs(
    deviceManager: TrailblazeDeviceManager,
    yamlRunner: (DesktopAppRunYamlParams) -> Unit,
    additionalInstrumentationArgsProvider: suspend () -> Map<String, String> = { additionalInstrumentationArgs() },
  ): List<TrailblazeAppTab> {
    return getStandardTabs(
      deviceManager = deviceManager,
      yamlRunner = yamlRunner,
      additionalInstrumentationArgsProvider = additionalInstrumentationArgsProvider,
      globalSettingsContent = globalSettingsContent,
      customEnvVarNames = customEnvVarNames,
    )
  }

  /**
   * Creates the standard set of tabs (Sessions, Trails, Devices, YAML, Settings).
   * Subclasses can call this and add/remove tabs as needed.
   */
  protected fun getStandardTabs(
    deviceManager: TrailblazeDeviceManager,
    yamlRunner: (DesktopAppRunYamlParams) -> Unit,
    additionalInstrumentationArgsProvider: suspend () -> Map<String, String>,
    globalSettingsContent: @Composable ColumnScope.(TrailblazeServerState) -> Unit,
    customEnvVarNames: List<String>,
  ): List<TrailblazeAppTab> {
    return listOf(
      TrailblazeBuiltInTabs.sessionsTab(
        logsRepo = logsRepo,
        trailblazeSettingsRepo = trailblazeSettingsRepo,
        deviceManager = deviceManager,
        recordedTrailsRepo = recordedTrailsRepo,
      ),
      TrailblazeBuiltInTabs.trailsTab(
        trailblazeSettingsRepo = trailblazeSettingsRepo,
      ),
      TrailblazeBuiltInTabs.devicesTab(
        deviceManager = deviceManager,
        trailblazeSettingsRepo = trailblazeSettingsRepo,
      ),
      TrailblazeBuiltInTabs.yamlTab(
        deviceManager = deviceManager,
        trailblazeSettingsRepo = trailblazeSettingsRepo,
        currentTrailblazeLlmModelProvider = { getCurrentLlmModel() },
        yamlRunner = yamlRunner,
        additionalInstrumentationArgs = additionalInstrumentationArgsProvider,
      ),
      TrailblazeBuiltInTabs.settingsTab(
        trailblazeSettingsRepo = trailblazeSettingsRepo,
        logsRepo = logsRepo,
        globalSettingsContent = globalSettingsContent,
        availableModelLists = getCurrentlyAvailableLlmModelLists(),
        customEnvVarNames = customEnvVarNames,
      ),
    )
  }
}
