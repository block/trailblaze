package xyz.block.trailblaze.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.api.EffectiveScreenshotScalingConfig
import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig
import xyz.block.trailblaze.ui.tabs.session.SessionViewMode
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import xyz.block.trailblaze.util.Console

class TrailblazeSettingsRepo(
  val settingsFile: File = File("build/${TrailblazeDesktopUtil.SETTINGS_FILENAME}"),
  initialConfig: SavedTrailblazeAppConfig,
  private val defaultHostAppTarget: TrailblazeHostAppTarget,
  allTargetApps: () -> Set<TrailblazeHostAppTarget>,
  private val supportedDriverTypes: Set<TrailblazeDriverType>,
) {
  @Volatile
  private var allTargetApps: () -> Set<TrailblazeHostAppTarget> = allTargetApps

  /**
   * Re-points target resolution ([getCurrentSelectedTargetApp]) at a live provider.
   *
   * The repo is constructed before [TrailblazeDeviceManager] (it feeds the desktop config's
   * startup discovery), so the constructor lambda necessarily reads the startup-frozen
   * `availableAppTargets` lazy. The manager calls this at its own construction to swap in its
   * live, additively-updatable set — otherwise a target appended by
   * `TrailblazeDeviceManager.registerNewTarget` would be selectable but resolve to null here,
   * dropping it from `GetTargetApps`, recording tool discovery, and run dispatch.
   */
  fun bindLiveTargetProvider(provider: () -> Set<TrailblazeHostAppTarget>) {
    allTargetApps = provider
  }

  private val trailblazeJson: Json = TrailblazeJson.defaultWithoutToolsInstance

  fun saveConfig(trailblazeSettings: SavedTrailblazeAppConfig) {
    val serialized = trailblazeJson.encodeToString(
      SavedTrailblazeAppConfig.serializer(),
      trailblazeSettings,
    )
    settingsFile.parentFile?.mkdirs()
    settingsFile.writeText(serialized)
  }

  fun load(
    initialConfig: SavedTrailblazeAppConfig,
  ): SavedTrailblazeAppConfig {
    val config = try {
      Console.log("Loading Settings from: ${settingsFile.absolutePath}")
      trailblazeJson.decodeFromString(
        SavedTrailblazeAppConfig.serializer(),
        settingsFile.readText(),
      ).copy(
        // Clear session-specific state on app restart
        currentSessionId = null,
        currentSessionViewMode = SessionViewMode.DEFAULT.name,
      )
    } catch (e: Exception) {
      Console.log("Error loading settings, using default: ${e.message}")
      initialConfig.also {
        saveConfig(initialConfig)
      }
    }
    return config
  }

  fun updateState(stateUpdater: (TrailblazeServerState) -> TrailblazeServerState) {
    serverStateFlow.value = stateUpdater(serverStateFlow.value)
  }

  fun updateAppConfig(appConfigUpdater: (SavedTrailblazeAppConfig) -> SavedTrailblazeAppConfig) {
    updateState { currentState: TrailblazeServerState ->
      currentState.copy(appConfig = appConfigUpdater(currentState.appConfig))
    }
  }

  fun applyTestingEnvironment(environment: TrailblazeServerState.TestingEnvironment) {
    updateAppConfig { config ->
      val existingDriverTypes = config.selectedTrailblazeDriverTypes
      val defaults = when (environment) {
        TrailblazeServerState.TestingEnvironment.MOBILE -> mapOf(
          TrailblazeDevicePlatform.ANDROID to TrailblazeDriverType.DEFAULT_ANDROID,
          TrailblazeDevicePlatform.IOS to TrailblazeDriverType.IOS_HOST,
        )
        TrailblazeServerState.TestingEnvironment.WEB -> mapOf(
          TrailblazeDevicePlatform.WEB to TrailblazeDriverType.PLAYWRIGHT_NATIVE,
        )
      }
      val merged = existingDriverTypes + defaults.filterKeys { it !in existingDriverTypes }
      config.copy(
        testingEnvironment = environment,
        selectedTrailblazeDriverTypes = merged,
        showDevicesTab = environment == TrailblazeServerState.TestingEnvironment.WEB,
      )
    }
  }

  fun targetAppSelected(targetApp: TrailblazeHostAppTarget) {
    updateAppConfig { appConfig: SavedTrailblazeAppConfig ->
      appConfig.copy(selectedTargetAppId = targetApp.id)
    }
  }

  fun getCurrentTrailsDir(): File {
    return File(serverStateFlow.value.appConfig.trailsDirectory ?: ".")
  }

  /**
   * Per-workspace `trails/config/` directory — or `null` if none resolves.
   *
   * Lookup order:
   *  1. `TRAILBLAZE_CONFIG_DIR` env var — explicit override for CI / scripting.
   *  2. Walk up to the current workspace's `trails/config/trailblaze.yaml`, then use
   *     that owning `trails/config/` directory when present.
   *  3. `null` — classpath-only discovery, framework defaults only.
   */
  fun getCurrentTrailblazeConfigDir(): File? =
    getCurrentTrailblazeConfigDir(cwd = Paths.get(""))

  /**
   * Testable overload: callers can pass an explicit [cwd] and [envReader] without depending
   * on JVM cwd or mutating real process environment variables.
   */
  internal fun getCurrentTrailblazeConfigDir(
    cwd: Path,
    envReader: () -> String? = { System.getenv(TrailblazeWorkspaceConfigResolver.CONFIG_DIR_ENV_VAR) },
  ): File? =
    TrailblazeWorkspaceConfigResolver.resolve(cwd, envReader).configDir

  fun getAllSupportedDriverTypes(): Set<TrailblazeDriverType> {
    return supportedDriverTypes
  }

  /**
   * Get the set of currently enabled driver types (values from the map)
   */
  fun getEnabledDriverTypes(): Set<TrailblazeDriverType> {
    return serverStateFlow.value.appConfig.selectedTrailblazeDriverTypes.values.toSet()
  }

  fun getCurrentSelectedTargetApp(): TrailblazeHostAppTarget? {
    return allTargetApps().firstOrNull { appTarget ->
      appTarget.id == serverStateFlow.value.appConfig.selectedTargetAppId
    }
  }

  /** Manages HTTP/HTTPS port resolution (runtime CLI overrides + persisted fallback). */
  val portManager = TrailblazePortManager(
    persistedConfigProvider = { serverStateFlow.value.appConfig },
  )

  val serverStateFlow = MutableStateFlow(
    TrailblazeServerState(
      appConfig = load(initialConfig).also {
        // Publish the user's screenshot config immediately on load so call sites
        // resolving `EffectiveScreenshotScalingConfig.effective` during early startup
        // (before the collector below runs) see the user values, not the framework default.
        // Pass null when nothing is overridden so the web path can fall back to its own default
        // (see EffectiveScreenshotScalingConfig.effectiveForWeb).
        EffectiveScreenshotScalingConfig.setEffectiveDefault(it.screenshotScalingConfigOrNull())
      },
    ),
  ).also { serverStateFlow ->
    CoroutineScope(Dispatchers.IO).launch {
      serverStateFlow
        .distinctUntilChangedBy { newState -> newState }
        .collect { newState ->
          // Defensive: the launched scope is unstructured (no parent
          // cancellation tied to this repo's lifetime), so the collector
          // outlives instances whose `settingsFile` may have disappeared —
          // e.g. JUnit's `TemporaryFolder` cleaning up a per-test temp dir
          // before this dispatcher fires. Without the catch, the resulting
          // `FileNotFoundException` propagates as an uncaught exception on
          // a `DefaultDispatcher` thread, which Gradle's test runner reports
          // as a test-level failure (observed on `TrailblazeSettingsRepo
          // ConfigDirTest` in PR CI despite the affected test having no
          // functional relationship to the write).
          //
          // `runCatching` swallows ONLY the persistence-side error; the
          // in-memory state-flow update has already succeeded by the time
          // we got here, and the `setEffectiveDefault` below is safe to run
          // independently. The proper fix is a structured scope tied to a
          // `dispose()` lifecycle hook on the repo — deferred (callers
          // would need updates).
          runCatching {
            saveConfig(newState.appConfig)
          }.onFailure { e ->
            Console.log(
              "[TrailblazeSettingsRepo] saveConfig from state-flow collector failed " +
                "(${settingsFile.absolutePath}): ${e.message}. Continuing — most likely " +
                "a stale collector firing after a test's temp dir was cleaned up.",
            )
          }
          EffectiveScreenshotScalingConfig.setEffectiveDefault(
            newState.appConfig.screenshotScalingConfigOrNull(),
          )
        }
    }
  }
}
