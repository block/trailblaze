package xyz.block.trailblaze.desktop

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.LlmLogCostEnricher
import xyz.block.trailblaze.llm.config.BuiltInLlmModelRegistry
import xyz.block.trailblaze.llm.config.LlmAuthResolver
import xyz.block.trailblaze.llm.config.LlmConfig
import xyz.block.trailblaze.llm.config.LlmConfigLoader
import xyz.block.trailblaze.llm.config.LlmConfigResolver
import xyz.block.trailblaze.llm.providers.AnthropicTrailblazeLlmModelList
import xyz.block.trailblaze.llm.providers.GoogleTrailblazeLlmModelList
import xyz.block.trailblaze.llm.providers.NoneTrailblazeLlmModelList
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
  // OSS distro defaults to "no LLM configured" — mirrors the
  // SavedTrailblazeAppConfig.llmProvider/llmModel = NONE data-class default. Today
  // the saved-provider=NONE early-return in TrailblazeDesktopAppConfig.getCurrentLlmModel()
  // shadows these fallback constructor args in the common case; pinning them to NONE
  // anyway means a corrupted settings file or an unrecognized provider id resolves to
  // NONE rather than silently auto-claiming a user's OPENAI_API_KEY. External CLI users
  // driving Trailblaze through Claude Code, Codex, etc. opt in explicitly.
  defaultLlmModel = TrailblazeLlmModel.fallback(
    provider = TrailblazeLlmProvider.NONE,
    modelId = TrailblazeLlmProvider.NONE.id,
  ),
  defaultProviderModelList = NoneTrailblazeLlmModelList,
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
  override val logsDir = File(
    TrailblazeDesktopUtil.getEffectiveLogsDirectory(
      trailblazeSettingsRepo.serverStateFlow.value.appConfig,
    ),
  ).apply { mkdirs() }

  private val costEnricher = LlmLogCostEnricher { modelId ->
    // Check YAML-configured models first (covers custom providers), then built-in registry
    resolvedModelLists.flatMap { it.entries }.firstOrNull { it.modelId == modelId }
      ?: BuiltInLlmModelRegistry.find(modelId)
  }

  // Lazy so that consumers which only need [logsDir] (e.g. the `waypoint capture-example`
  // CLI auto-search) don't accidentally trigger LogsRepo construction — the file watchers
  // it spawns are non-daemon and prevent a one-shot CLI JVM from exiting cleanly.
  //
  // For the daemon, `MainTrailblazeApp` accesses `logsRepo.logsDir` during boot (the
  // `setLogsDirectory` call), which forces initialization on the daemon's main thread.
  // So this is effectively eager for the daemon and only lazy for one-shot CLI commands
  // that read `config.logsDir` (path-only) without ever touching `logsRepo`. Don't remove
  // the boot-time access thinking it's redundant: it pins WHEN initialization happens, on
  // a known thread, before any UI/HTTP code path could trigger it under load (which would
  // then cache a construction failure for every subsequent access).
  override val logsRepo by lazy { LogsRepo(logsDir, costEnricher = costEnricher::enrich) }

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
