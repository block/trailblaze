package xyz.block.trailblaze.desktop

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.LlmLogCostEnricher
import xyz.block.trailblaze.llm.config.BuiltInLlmModelRegistry
import xyz.block.trailblaze.llm.config.LlmAuthResolver
import xyz.block.trailblaze.llm.config.LlmConfig
import xyz.block.trailblaze.llm.config.LlmConfigLoader
import xyz.block.trailblaze.llm.config.LlmConfigResolver
import xyz.block.trailblaze.llm.providers.AnthropicTrailblazeLlmModelList
import xyz.block.trailblaze.llm.providers.GoogleTrailblazeLlmModelList
import xyz.block.trailblaze.llm.providers.OllamaTrailblazeLlmModelList
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
import xyz.block.trailblaze.llm.providers.OpenRouterTrailblazeLlmModelList
import xyz.block.trailblaze.mcp.utils.JvmLLMProvidersUtil
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.revyl.RevylCliClient
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.recordings.RecordedTrailsRepoJvm
import xyz.block.trailblaze.util.Console
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
    TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    TrailblazeDriverType.IOS_HOST,
    TrailblazeDriverType.IOS_AXE,
    TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
    TrailblazeDriverType.REVYL_ANDROID,
    TrailblazeDriverType.REVYL_IOS,
  )

  // Start with no platforms enabled by default - user must explicitly enable them
  private val initialDriverTypesMap: Map<TrailblazeDevicePlatform, TrailblazeDriverType> = mapOf(
    TrailblazeDevicePlatform.ANDROID to TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    TrailblazeDevicePlatform.IOS to TrailblazeDriverType.IOS_HOST,
  )

  override val defaultAppDataDir: File = TrailblazeDesktopUtil.getDefaultAppDataDirectory().apply { mkdirs() }

  override val defaultAppTarget: TrailblazeHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget
  override val trailblazeSettingsRepo = TrailblazeSettingsRepo(
    settingsFile = File(defaultAppDataDir, TrailblazeDesktopUtil.SETTINGS_FILENAME),
    initialConfig = TrailblazeServerState.SavedTrailblazeAppConfig(initialDriverTypesMap),
    supportedDriverTypes = initialDriverTypes,
    defaultHostAppTarget = defaultAppTarget,
    allTargetApps = { availableAppTargets }
  )

  // Lazy so the settings repo is fully constructed before discovery touches it. Workspace
  // discovery now flows through the shared `trailblaze.yaml` + `trailblaze-config/`
  // resolver used by both LLM config loading and target discovery. Discovery runs on first
  // access and caches. See `AppTargetDiscovery` — same helper `BlockAppTargets` uses, just
  // with opensource defaults (no companions, DefaultTrailblazeHostAppTarget fallback).
  override val availableAppTargets: Set<TrailblazeHostAppTarget> by lazy {
    xyz.block.trailblaze.host.AppTargetDiscovery.discover(
      logPrefix = "[OpenSourceAppTargets]",
    )
  }
  val logsDir = File(
    TrailblazeDesktopUtil.getEffectiveLogsDirectory(
      trailblazeSettingsRepo.serverStateFlow.value.appConfig,
    ),
  ).apply { mkdirs() }

  private val costEnricher = LlmLogCostEnricher { modelId ->
    // Check YAML-configured models first (covers custom providers), then built-in registry
    resolvedModelLists.flatMap { it.entries }.firstOrNull { it.modelId == modelId }
      ?: BuiltInLlmModelRegistry.find(modelId)
  }

  override val logsRepo = LogsRepo(logsDir, costEnricher = costEnricher::enrich)

  val trailsDir = File(
    TrailblazeDesktopUtil.getEffectiveTrailsDirectory(
      trailblazeSettingsRepo.serverStateFlow.value.appConfig,
    ),
  ).apply { mkdirs() }

  override val recordedTrailsRepo = RecordedTrailsRepoJvm(
    trailsDirectory = trailsDir
  )
  override val customEnvVarNames: List<String> = buildList {
    BUILT_IN_MODEL_LISTS.mapNotNullTo(this) { trailblazeLlmModelList ->
      JvmLLMProvidersUtil.getEnvironmentVariableKeyForLlmProvider(trailblazeLlmModelList.provider)
    }
    loadedConfig.providers.values.mapNotNullTo(this) { it.auth.envVar }
    add(RevylCliClient.REVYL_API_KEY_ENV)
  }.distinct()

  override fun getCurrentlyAvailableLlmModelLists(): Set<TrailblazeLlmModelList> {
    return JvmLLMProvidersUtil.getAvailableTrailblazeLlmProviderModelLists(
      getAllSupportedLlmModelLists(),
      loadedConfig,
    )
  }

  override fun getAllSupportedLlmModelLists(): Set<TrailblazeLlmModelList> {
    return resolvedModelLists
  }

  override suspend fun additionalInstrumentationArgs(): Map<String, String> {
    val config = LlmConfigLoader.load()
    val auths = LlmAuthResolver.resolveAll(config)
    val appConfig = trailblazeSettingsRepo.serverStateFlow.value.appConfig
    val selectedProvider = appConfig.llmProvider
    val selectedModel = appConfig.llmModel
    return LlmAuthResolver.toInstrumentationArgs(
      auths = auths,
      selectedProviderId = selectedProvider,
      defaultModel = "$selectedProvider/$selectedModel",
    )
  }

  override val appIconProvider: AppIconProvider = AppIconProvider.DefaultAppIconProvider
  override fun getInstalledAppIds(trailblazeDeviceId: TrailblazeDeviceId): Set<String> {
    return MobileDeviceUtils.getInstalledAppIds(trailblazeDeviceId)
  }

  companion object {
    /** Built-in hardcoded model lists (fallback when no YAML config exists). */
    private val BUILT_IN_MODEL_LISTS: Set<TrailblazeLlmModelList> = setOf(
      AnthropicTrailblazeLlmModelList,
      GoogleTrailblazeLlmModelList,
      OllamaTrailblazeLlmModelList,
      OpenAITrailblazeLlmModelList,
      OpenRouterTrailblazeLlmModelList,
    )

    /** Loaded YAML config, falling back to empty config on failure. */
    val loadedConfig: LlmConfig by lazy {
      try {
        LlmConfigLoader.load()
      } catch (e: Exception) {
        Console.log("Warning: Failed to load LLM config: ${e.message}")
        LlmConfig()
      }
    }

    /**
     * Resolved model lists from YAML config, falling back to built-in defaults.
     * Loaded lazily on first access.
     */
    val resolvedModelLists: Set<TrailblazeLlmModelList> by lazy {
      try {
        if (loadedConfig.providers.isEmpty()) {
          BUILT_IN_MODEL_LISTS
        } else {
          val resolved = LlmConfigResolver.resolve(loadedConfig, BuiltInLlmModelRegistry)
          resolved.modelLists.toSet()
        }
      } catch (e: Exception) {
        Console.log("Warning: Failed to resolve LLM config, using built-in defaults: ${e.message}")
        BUILT_IN_MODEL_LISTS
      }
    }
  }

}
