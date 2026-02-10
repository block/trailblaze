package xyz.block.trailblaze.desktop

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.llm.LlmProviderEnvVarUtil
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.model.AppVersionInfo
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
  abstract val customEnvVarNames: List<String>

  /**
   * Gets the status of LLM tokens for a specific provider.
   * 
   * This method checks token availability without triggering any authentication flows.
   * Override in subclasses to provide provider-specific token checking (e.g., custom OAuth flows).
   * 
   * @param provider The LLM provider to check token status for.
   * @return The token status for the given provider.
   */
  open fun getLlmTokenStatus(provider: TrailblazeLlmProvider): LlmTokenStatus {
    // Default implementation checks environment variables for standard open source providers
    val envVarName = getEnvironmentVariableForProvider(provider)

    return when {
      envVarName == null -> LlmTokenStatus.Available(provider) // Provider doesn't need a token (e.g., Ollama)
      System.getenv(envVarName)?.isNotBlank() == true -> LlmTokenStatus.Available(provider)
      else -> LlmTokenStatus.NotAvailable(provider)
    }
  }

  /**
   * Gets the environment variable name for a provider's API key.
   * Returns null if the provider doesn't require an API key.
   * 
   * Override in subclasses to add support for additional providers.
   */
  open fun getEnvironmentVariableForProvider(provider: TrailblazeLlmProvider): String? {
    return LlmProviderEnvVarUtil.getEnvironmentVariableKeyForProvider(provider)
  }

  /**
   * Gets all supported LLM model lists for this configuration.
   * Unlike [getCurrentlyAvailableLlmModelLists], this returns ALL supported providers
   * regardless of whether they have tokens configured.
   * 
   * Used by the `auth` command to show status of all providers.
   */
  abstract fun getAllSupportedLlmModelLists(): Set<TrailblazeLlmModelList>

  /**
   * Gets the status of all supported LLM providers.
   * 
   * @return A map of provider to token status for ALL supported providers (not just available ones).
   */
  fun getAllLlmTokenStatuses(): Map<TrailblazeLlmProvider, LlmTokenStatus> {
    return getAllSupportedLlmModelLists()
      .map { it.provider }
      .distinct()
      .associateWith { getLlmTokenStatus(it) }
  }

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

  /**
   * Gets version information for an installed app on the specified device.
   * Override in subclasses if custom version retrieval is needed.
   */
  open fun getAppVersionInfo(trailblazeDeviceId: TrailblazeDeviceId, appId: String): AppVersionInfo? {
    return MobileDeviceUtils.getAppVersionInfo(trailblazeDeviceId, appId)
  }

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
      TrailblazeBuiltInTabs.homeTab(
        trailblazeSettingsRepo = trailblazeSettingsRepo,
      ),
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
