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
import java.util.concurrent.ConcurrentHashMap
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

  /**
   * Resolves the daemon-wide selected target app, or `null` when none resolves (callers then
   * apply the neutral [defaultHostAppTarget]).
   *
   * Effective-target precedence for a run / tool dispatch:
   *  1. Explicit per-run / per-session target — a trail's `config.target`, `--target`, or an
   *     active session override. Applied by callers ABOVE this function.
   *  2. Persisted user selection — [SavedTrailblazeAppConfig.selectedTargetAppId], set when the
   *     user picks a target in the app or via `trailblaze config target`. A persisted id equal
   *     to the neutral [defaultHostAppTarget]'s id is treated as "no explicit selection" for
   *     precedence (legacy CLI code auto-persisted it — see the inline comment) and only
   *     resolves when rung 3 doesn't.
   *  3. Workspace default — `defaults.target` in the workspace `trails/config/trailblaze.yaml`
   *     (committed team-wide default; validated against the loaded targets).
   *  4. Neutral [TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget] — applied by callers
   *     when this returns `null`.
   *
   * This function covers rungs 2–3. It returns `null` (rather than the neutral default) when
   * neither resolves, preserving the nullable contract callers rely on to distinguish "no
   * effective target" (a `(not set)` display) from an actual selection.
   */
  fun getCurrentSelectedTargetApp(): TrailblazeHostAppTarget? =
    getCurrentSelectedTargetApp(cwd = workspaceAnchorSeedPath())

  /**
   * Effective target for a CLI-dispatched `run`, anchoring the workspace `defaults.target` rung
   * (rung 3) at the run caller's cwd ([callerWorkspaceDir], forwarded over the wire) instead of
   * this daemon's frozen [workspaceAnchorSeedPath].
   *
   * Without this, a daemon-dispatched `trailblaze run` fell back to the daemon's own
   * `defaults.target`, so the target actually run could diverge from what
   * `trailblaze config get target` reports — that prediction resolves rung 3 from the caller's
   * cwd, while the daemon resolves it from wherever `app start` was launched. Anchoring here at
   * the same cwd keeps the two in agreement.
   *
   * A null / blank / unparseable / non-absolute [callerWorkspaceDir] (older CLI shim, or a
   * non-CLI submission that never sets it) falls back to [workspaceAnchorSeedPath] —
   * byte-identical to the prior daemon-anchored behavior. Only the rung-3 anchor moves; rungs 2
   * (persisted selection) and 4 (neutral default) are unaffected, as is the run determinism
   * contract (per-terminal `--target` / `TRAILBLAZE_TARGET` pins are deliberately NOT consulted
   * for a run).
   *
   * The absolute-path requirement matches the field's contract (the CLI forwards
   * `callerCwd().toAbsolutePath()`): a *relative* value would otherwise resolve against the
   * daemon's own process cwd and key [workspaceDefaultTargetCache] under a surprising path —
   * silently reintroducing the daemon-anchored behavior this method exists to avoid. Anything
   * not absolute degrades to the daemon anchor instead.
   */
  internal fun getCurrentSelectedTargetAppForCallerCwd(
    callerWorkspaceDir: String?,
    envReader: () -> String? = { System.getenv(TrailblazeWorkspaceConfigResolver.CONFIG_DIR_ENV_VAR) },
  ): TrailblazeHostAppTarget? {
    val callerCwd = callerWorkspaceDir?.trim()?.takeIf { it.isNotEmpty() }
      ?.let { runCatching { Paths.get(it) }.getOrNull() }
      ?.takeIf { it.isAbsolute }
    return getCurrentSelectedTargetApp(cwd = callerCwd ?: workspaceAnchorSeedPath(), envReader = envReader)
  }

  /**
   * Seed for the workspace-anchor walk-up: the user's configured trails directory when it
   * exists, else JVM cwd. Target discovery anchors at the configured trails dir (see
   * `workspaceConfigDirOrNull` in `TrailblazeDesktopAppConfig`) — the `defaults.target` rung
   * must follow the same workspace, or a daemon launched outside the workspace (app bundle,
   * home dir) would discover its targets but never see its committed default.
   *
   * Recomputed per call (NOT frozen per process): `trailsDirectory` is user-configurable at
   * runtime via the app settings, and [getCurrentSelectedTargetApp] now feeds run dispatch
   * and attribution — freezing the first directory seen would keep resolving workspace A's
   * `defaults.target` after the user switches the app to workspace B. The per-call cost is
   * one `isDirectory` stat; the expensive walk-up + YAML parse stays memoized per seed in
   * [workspaceDefaultTargetCache], so a directory *switch* re-anchors (new cache key) while
   * an in-place edit of the same workspace's `trailblaze.yaml` still needs a restart (the
   * per-seed freeze documented on the cache).
   */
  private fun workspaceAnchorSeedPath(): Path =
    File(TrailblazeDesktopUtil.getEffectiveTrailsDirectory(serverStateFlow.value.appConfig))
      .takeIf { it.isDirectory }
      ?.toPath()
      ?: Paths.get("")

  /**
   * Testable overload: callers can pass an explicit [cwd] (used to discover the workspace
   * `trailblaze.yaml` for the `defaults.target` rung) and an [envReader] for the
   * `TRAILBLAZE_CONFIG_DIR` override, without depending on JVM cwd or mutating real process
   * environment.
   */
  internal fun getCurrentSelectedTargetApp(
    cwd: Path,
    envReader: () -> String? = { System.getenv(TrailblazeWorkspaceConfigResolver.CONFIG_DIR_ENV_VAR) },
  ): TrailblazeHostAppTarget? {
    val loadedTargets = allTargetApps()
    val persistedId = serverStateFlow.value.appConfig.selectedTargetAppId
    // Rung 2: persisted user selection. The neutral default's own id is NOT authoritative on
    // this rung: historical CLI code (`CliConfigHelper.defaultConfig` / `hydrateDefaults`)
    // auto-persisted it without any user intent, so a stored "default" is indistinguishable
    // from a fabricated one and must not mask a committed workspace `defaults.target`. It
    // still resolves (below) when the workspace declares nothing, and `--target default`
    // remains the explicit per-run escape hatch.
    val persistedMatch = loadedTargets.firstOrNull { it.id == persistedId }
    // The neutral-default sentinel is shared with the CLI target surfaces via
    // TrailblazeWorkspaceConfigResolver.authoritativeSelectedTargetId — same logic, but the
    // neutral id here is this distribution's injected defaultHostAppTarget.id rather than the
    // CLI's compile-time static. Keeping both callers on one implementation is the CLI/daemon
    // parity guarantee (see CliDaemonTargetSentinelParityTest).
    val authoritativePersistedId = TrailblazeWorkspaceConfigResolver.authoritativeSelectedTargetId(
      selectedTargetAppId = persistedId,
      neutralDefaultId = defaultHostAppTarget.id,
    )
    if (persistedMatch != null && authoritativePersistedId != null) {
      return persistedMatch
    }
    // Rung 3: workspace `defaults.target`.
    return resolveWorkspaceDefaultTargetApp(loadedTargets, cwd, envReader) ?: persistedMatch
  }

  /**
   * The workspace's declared `defaults.target` id (blank-normalized to null) plus the anchor
   * file it came from, memoized per walk-up seed path. The filesystem walk-up + YAML parse
   * runs once per seed for the process lifetime — `getCurrentSelectedTargetApp` sits on hot
   * paths (Compose recomposition, per-dispatch MCP), and freezing the workspace view per
   * process matches `availableAppTargets`' once-per-JVM discovery semantics.
   */
  private data class WorkspaceDefaultTarget(val declaredId: String?, val configFilePath: String?)

  private val workspaceDefaultTargetCache = ConcurrentHashMap<Path, WorkspaceDefaultTarget>()

  /** Ids already warned about as unknown `defaults.target` values — warn once, not per call. */
  private val warnedUnknownDefaultTargetIds = ConcurrentHashMap.newKeySet<String>()

  /**
   * Reads `defaults.target` from the workspace `trails/config/trailblaze.yaml` discovered from
   * [cwd] and resolves it against [loadedTargets] (ids match case-sensitively, like every other
   * target-id comparison). Returns `null` when no workspace file resolves, the field is absent
   * or blank, or the declared id matches no loaded target (logged once, never thrown) — so a
   * stale / mistyped id degrades to the neutral default rather than crashing every
   * target-resolving call in that workspace.
   *
   * Note the deliberate precedence inversion vs `defaults.maxLlmCalls`
   * ([xyz.block.trailblaze.cli.TrailCommand.resolveEffectiveMaxLlmCalls]): there the workspace
   * default outranks the persisted per-machine value (a committed team cap should win); here
   * the persisted user selection outranks the workspace default (an explicit target pick must
   * stick).
   */
  internal fun resolveWorkspaceDefaultTargetApp(
    loadedTargets: Set<TrailblazeHostAppTarget>,
    cwd: Path,
    envReader: () -> String?,
  ): TrailblazeHostAppTarget? {
    val workspaceDefault = workspaceDefaultTargetCache.getOrPut(cwd) {
      val loaded = TrailblazeWorkspaceConfigResolver.loadWorkspaceDefaults(
        fromPath = cwd,
        consumer = "defaults.target",
        envReader = envReader,
      )
      val declaredId = loaded?.defaults?.target?.takeIf { it.isNotBlank() }
      if (declaredId != null) {
        // Once per process (cache population): the answer to "why is this target selected
        // when I never picked one?" — the successful rung-3 flip is otherwise invisible.
        // Known limitation: emission is tied to cache population, so if the first
        // getCurrentSelectedTargetApp() for a cwd happens inside a Console.runQuiet {} scope
        // (a forwarded `/cli/exec snapshot`/`tool`), this line is suppressed AND the cache is
        // now warm, so it won't reappear this process. Accepted — it's an observability nicety,
        // not a correctness signal; grep the daemon log at startup to see it in the common case.
        Console.log(
          "Using workspace defaults.target=\"$declaredId\" from ${loaded.configFile.absolutePath} " +
            "when no explicit target is selected.",
        )
      }
      WorkspaceDefaultTarget(
        declaredId = declaredId,
        configFilePath = loaded?.configFile?.absolutePath,
      )
    }
    val declaredId = workspaceDefault.declaredId ?: return null
    val match = loadedTargets.firstOrNull { it.id == declaredId }
    if (match == null && warnedUnknownDefaultTargetIds.add(declaredId)) {
      Console.log(
        "Workspace ${workspaceDefault.configFilePath} defaults.target=\"$declaredId\" matches " +
          "no loaded target (${loadedTargets.map { it.id }.sorted()}); falling back to the " +
          "default target.",
      )
    }
    return match
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
