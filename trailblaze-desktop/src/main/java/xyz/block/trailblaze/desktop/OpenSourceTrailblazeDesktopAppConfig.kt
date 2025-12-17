package xyz.block.trailblaze.desktop

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.providers.AnthropicTrailblazeLlmModelList
import xyz.block.trailblaze.llm.providers.GoogleTrailblazeLlmModelList
import xyz.block.trailblaze.llm.providers.OllamaTrailblazeLlmModelList
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
import xyz.block.trailblaze.mcp.utils.JvmLLMProvidersUtil
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.recordings.RecordedTrailsRepoJvm
import java.io.File

/**
 * Default Configuration for Open Source Desktop App
 */
class OpenSourceTrailblazeDesktopAppConfig : TrailblazeDesktopAppConfig(
  defaultLlmModel = OpenAITrailblazeLlmModelList.OPENAI_GPT_4_1,
  defaultProviderModelList = OpenAITrailblazeLlmModelList
) {

  private val initialDriverTypes = setOf(
    TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    TrailblazeDriverType.ANDROID_HOST,
    TrailblazeDriverType.IOS_HOST
  )

  // Start with no platforms enabled by default - user must explicitly enable them
  private val initialDriverTypesMap: Map<TrailblazeDevicePlatform, TrailblazeDriverType> = mapOf(
    TrailblazeDevicePlatform.ANDROID to TrailblazeDriverType.ANDROID_HOST,
    TrailblazeDevicePlatform.IOS to TrailblazeDriverType.IOS_HOST,
  )

  override val defaultAppDataDir: File = TrailblazeDesktopUtil.getDefaultAppDataDirectory().apply { mkdirs() }
  override val trailblazeSettingsRepo = TrailblazeSettingsRepo(
    settingsFile = File(defaultAppDataDir, TrailblazeDesktopUtil.SETTINGS_FILENAME),
    initialConfig = TrailblazeServerState.SavedTrailblazeAppConfig(initialDriverTypesMap),
    supportedDriverTypes = initialDriverTypes,
  )
  val logsDir = File(
    TrailblazeDesktopUtil.getEffectiveLogsDirectory(
      trailblazeSettingsRepo.serverStateFlow.value.appConfig,
    ),
  ).apply { mkdirs() }

  override val logsRepo = LogsRepo(logsDir)

  val trailsDir = File(
    TrailblazeDesktopUtil.getEffectiveTrailsDirectory(
      trailblazeSettingsRepo.serverStateFlow.value.appConfig,
    ),
  ).apply { mkdirs() }

  override val recordedTrailsRepo = RecordedTrailsRepoJvm(trailsDirectory = trailsDir)

  override fun getCurrentlyAvailableLlmModelLists(): Set<TrailblazeLlmModelList> {
    val modelLists = JvmLLMProvidersUtil.getAvailableTrailblazeLlmProviderModelLists(ALL_MODEL_LISTS)
    return modelLists
  }

  override val availableAppTargets: Set<TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget> =
    setOf(TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget)
  override val appIconProvider: AppIconProvider = AppIconProvider.DefaultAppIconProvider
  override val defaultAppTarget: TrailblazeHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget
  override fun getInstalledAppIds(trailblazeDeviceId: TrailblazeDeviceId): Set<String> {
    return MobileDeviceUtils.getInstalledAppIds(trailblazeDeviceId)
  }

  companion object {
    /** All supported LLM model lists for open source host mode. */
    private val ALL_MODEL_LISTS = setOf(
      AnthropicTrailblazeLlmModelList,
      GoogleTrailblazeLlmModelList,
      OllamaTrailblazeLlmModelList,
      OpenAITrailblazeLlmModelList,
    )
  }

}
