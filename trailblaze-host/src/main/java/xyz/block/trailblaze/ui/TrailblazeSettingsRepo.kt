package xyz.block.trailblaze.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.config.project.WorkspaceRoot
import xyz.block.trailblaze.config.project.findWorkspaceRoot
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
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
  private val allTargetApps: () -> Set<TrailblazeHostAppTarget>,
  private val supportedDriverTypes: Set<TrailblazeDriverType>,
) {
  private val trailblazeJson: Json = TrailblazeJson.defaultWithoutToolsInstance

  fun saveConfig(trailblazeSettings: SavedTrailblazeAppConfig) {
    val serialized = trailblazeJson.encodeToString(
      SavedTrailblazeAppConfig.serializer(),
      trailblazeSettings,
    )
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
   * Per-project `trailblaze-config/` directory — or `null` if none resolves.
   *
   * Name retained for backwards compatibility during the workspace-root transition; a later
   * phase replaces this function entirely by reading targets / toolsets / tools / providers
   * directly out of `trailblaze.yaml`. Today it returns either a walk-up-discovered
   * `trailblaze-config/` subdir, an explicit user setting, or the legacy auto-sibling dir.
   *
   * Lookup order:
   *  1. `TRAILBLAZE_CONFIG_DIR` env var — CI / scripting override. Highest precedence so
   *     scripted callers keep explicit control during the workspace-root transition.
   *  2. **Walk-up `trailblaze.yaml` + sibling `trailblaze-config/`** (Phase 2 — preferred
   *     discovery path). When the JVM cwd sits inside a workspace and that workspace
   *     contains a `trailblaze-config/` subdirectory, return that subdir. The subdirectory
   *     is what [FilesystemConfigResourceSource] expects as `rootDir` (it strips the
   *     `trailblaze-config/` prefix from lookup paths), so returning the workspace root
   *     itself would silently break filesystem-layer target/toolset discovery.
   *  3. `SavedTrailblazeAppConfig.trailblazeConfigDirectory` — explicit user setting
   *     (desktop UI / CLI / settings file). Legacy fallback, unchanged.
   *  4. **Auto-sibling convention** — if [trailsDirectory][SavedTrailblazeAppConfig.trailsDirectory]
   *     is set and `${trailsDirectory}/../trailblaze-config/` exists on disk, use it.
   *     Legacy fallback, unchanged.
   *  5. `null` — classpath-only discovery, framework defaults only.
   *
   * Callers that need just the path (not a File) can read the underlying settings fields
   * directly; this method does the resolution so discovery-layer code stays dumb.
   */
  fun getCurrentTrailblazeConfigDir(): File? =
    getCurrentTrailblazeConfigDir(cwd = Paths.get(""))

  /**
   * Testable overload: callers can pass an explicit [cwd] and [envReader] so the walk-up
   * and env-var-override layers are exercisable without depending on JVM cwd or mutating
   * real process environment variables. Both default to the production wiring.
   */
  internal fun getCurrentTrailblazeConfigDir(
    cwd: Path,
    envReader: () -> String? = { System.getenv("TRAILBLAZE_CONFIG_DIR") },
  ): File? {
    val envOverride = envReader()?.takeIf { it.isNotBlank() }
    if (envOverride != null) {
      val envDir = File(envOverride)
      if (envDir.isDirectory) return envDir
      Console.log("TRAILBLAZE_CONFIG_DIR='$envOverride' is not a directory — ignoring.")
    }
    val workspace = cachedFindWorkspaceRoot(cwd)
    if (workspace is WorkspaceRoot.Configured) {
      val legacyConfigSubdir = File(workspace.dir.toFile(), TrailblazeConfigPaths.CONFIG_DIR)
      if (legacyConfigSubdir.isDirectory) return legacyConfigSubdir
      // Workspace has trailblaze.yaml but no trailblaze-config/ subdir — legacy discovery
      // has nothing to read from this workspace in Phase 2. Fall through so the explicit
      // setting / auto-sibling fallbacks still get a chance (Phase 4 will replace this
      // layer entirely by reading targets/toolsets/tools/providers from trailblaze.yaml).
    }
    val appConfig = serverStateFlow.value.appConfig
    appConfig.trailblazeConfigDirectory?.takeIf { it.isNotBlank() }?.let { explicit ->
      val explicitDir = File(explicit)
      if (explicitDir.isDirectory) return explicitDir
      Console.log("trailblazeConfigDirectory='$explicit' is not a directory — ignoring.")
    }
    val trailsDir = appConfig.trailsDirectory?.takeIf { it.isNotBlank() }?.let(::File)
    if (trailsDir != null) {
      // `absoluteFile` before `parentFile` because a relative path like "trails" has a null
      // parentFile — the sibling lookup would silently do nothing. Absolute resolution is
      // relative to the JVM cwd, matching how `File(".").absolutePath` behaves everywhere
      // else in Trailblaze.
      val sibling = trailsDir.absoluteFile.parentFile?.let { File(it, "trailblaze-config") }
      if (sibling != null && sibling.isDirectory) return sibling
    }
    return null
  }

  /**
   * Walk-up caching. [getCurrentTrailblazeConfigDir] is called many times per CLI
   * invocation (target resolution, toolset lookup, etc.), and `findWorkspaceRoot` issues a
   * `Files.isRegularFile` check per ancestor plus a `toRealPath()` syscall on a hit. The
   * JVM cwd can't change mid-process, but the testable overload can pass different paths,
   * so we key the cache by the canonical absolute form. Mirrors the same `@Volatile`
   * sentinel pattern [CliConfigHelper.findGitRoot] uses for its git-root cache.
   *
   * Only the walk-up step is cached — env-var reads and the settings-flow lookups stay
   * live so that runtime setting changes (e.g. user updates `trailblazeConfigDirectory`
   * via the desktop UI) are still reflected on the next call.
   */
  @Volatile private var cachedWorkspaceLookup: Pair<Path, WorkspaceRoot>? = null

  private fun cachedFindWorkspaceRoot(cwd: Path): WorkspaceRoot {
    val canonicalCwd = cwd.toAbsolutePath().normalize()
    cachedWorkspaceLookup?.let { (cachedCwd, cachedResult) ->
      if (cachedCwd == canonicalCwd) return cachedResult
    }
    val result = findWorkspaceRoot(canonicalCwd)
    cachedWorkspaceLookup = canonicalCwd to result
    return result
  }

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
    return allTargetApps()
      .filter { it.id != defaultHostAppTarget.id }
      .firstOrNull { appTarget ->
        appTarget.id == serverStateFlow.value.appConfig.selectedTargetAppId
      }
  }

  /** Manages HTTP/HTTPS port resolution (runtime CLI overrides + persisted fallback). */
  val portManager = TrailblazePortManager(
    persistedConfigProvider = { serverStateFlow.value.appConfig },
  )

  val serverStateFlow = MutableStateFlow(
    TrailblazeServerState(
      appConfig = load(initialConfig),
    ),
  ).also { serverStateFlow ->
    CoroutineScope(Dispatchers.IO).launch {
      serverStateFlow
        .distinctUntilChangedBy { newState -> newState }
        .collect { newState ->
          saveConfig(newState.appConfig)
        }
    }
  }
}
