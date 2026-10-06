package xyz.block.trailblaze.desktop

import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.llm.LlmProviderEnvVarUtil
import xyz.block.trailblaze.llm.config.BuiltInLlmModelRegistry
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.model.AppVersionInfo
import xyz.block.trailblaze.host.driver.HostDriverDescriptor
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.trailrunner.DefaultTrailRunnerExtension
import xyz.block.trailblaze.trailrunner.TrailRunnerExtension
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
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
    val envVarValue = LlmProviderEnvVarUtil.getEnvironmentVariableValueForProvider(provider)

    return when {
      envVarName == null -> LlmTokenStatus.Available(provider) // Provider doesn't need a token (e.g., Ollama)
      envVarValue?.isNotBlank() == true -> LlmTokenStatus.Available(provider)
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
   * Additional instrumentation args to pass to YAML runs for this configuration.
   */
  open suspend fun additionalInstrumentationArgs(): Map<String, String> = emptyMap()

  abstract fun getCurrentlyAvailableLlmModelLists(): Set<TrailblazeLlmModelList>

  /**
   * Filesystem path where logs are stored. Side-effect-free path-only accessor —
   * implemented as a separate field from [logsRepo] so callers that just need the
   * directory (e.g. the `waypoint capture-example` CLI auto-search) can read it
   * without triggering construction of the full [LogsRepo] (which spawns
   * non-daemon file-watcher threads). Subclasses must declare this independently
   * of `logsRepo` and pass it into the LogsRepo constructor.
   */
  abstract val logsDir: java.io.File

  /** That's where logs are stored and managed. */
  abstract val logsRepo: LogsRepo

  abstract val trailblazeSettingsRepo: TrailblazeSettingsRepo

  abstract val defaultAppDataDir: File
  abstract val recordedTrailsRepo: RecordedTrailsRepo

  /**
   * The drivers this app plugs in — see [HostDriverDescriptor].
   *
   * Declaring one here is what makes its devices discoverable, listable, runnable and
   * capturable; an app that omits a driver doesn't get it at all, and there is no fallback path
   * that would quietly pick it up.
   *
   * Empty by default so a distribution opts in to each driver rather than inheriting whatever
   * happens to be on the classpath.
   */
  open val hostDriverDescriptors: Set<HostDriverDescriptor> = emptySet()

  /**
   * [hostDriverDescriptors] as the registry every host call site reads.
   *
   * No separate "does this cover what we support?" check, because each app config derives the
   * supported set it hands `TrailblazeSettingsRepo` from these same descriptors — the two cannot
   * disagree, so such a check could never fail. What a driver being unplugged costs is instead
   * reported where it bites: `forDriver` throws naming the driver and the remedy.
   */
  val hostDriverDescriptorRegistry: HostDriverDescriptorRegistry by lazy {
    HostDriverDescriptorRegistry(hostDriverDescriptors)
  }

  /**
   * The Trail Runner web-UI extension seam. Downstream builds override this to layer their own
   * behavior (integrations, analytics, LLM authoring assists, pluggable capture) onto the
   * open-source Trail Runner backend. Defaults to [DefaultTrailRunnerExtension] (a no-op), so an
   * open-source build gets a working-but-unadorned Trail Runner. Consumed by
   * `TrailRunnerEndpoint.register(...)`.
   */
  open val trailRunnerExtension: TrailRunnerExtension = DefaultTrailRunnerExtension

  /**
   * The workspace `trails/config` directory, or null when no workspace resolves. Shared anchor for
   * app-target discovery, the workspace-aware config resolver, and the target-drift check, so all
   * three follow the same workspace. `TRAILBLAZE_CONFIG_DIR` wins (CI / scripting / alternate
   * workspace launches) to match [TrailblazeWorkspaceConfigResolver]'s contract; otherwise it's the
   * configured trails dir's `config/`. Desktop apps install this into
   * [xyz.block.trailblaze.llm.config.WorkspaceConfigDirHolder] at startup so the tool catalog, LSP
   * schema, and scripted-tool lookups follow the trails directory the user picks in settings
   * rather than whatever directory the daemon happened to launch from.
   *
   * Both workspace layouts are honored — see [resolveWorkspaceConfigDir] for the resolution
   * rules. Only the trails directory itself must be real — a bogus/unset setting still resolves
   * to null. Every read-side consumer of [WorkspaceConfigDirHolder] applies its own
   * `isDirectory` / file-exists guard, so a not-yet-created dir degrades to the same empty
   * results null did.
   */
  fun workspaceConfigDirOrNull(): File? {
    System.getenv(TrailblazeWorkspaceConfigResolver.CONFIG_DIR_ENV_VAR)
      ?.takeIf { it.isNotBlank() }
      ?.let { File(it) }
      ?.takeIf { it.isDirectory }
      ?.let { return it }
    // A workspace that declares `trails:` hands back its own config dir. Re-deriving it by
    // walking up from the trails dir only works while the config dir is an ANCESTOR of it,
    // which a declaration is free to break: `legacy-trails/` and `trailblaze-config/` are
    // siblings, and an absolute declaration can leave the repo entirely. In the legacy layout
    // the walk-up would find no `trailblaze-config/` at all and fall back to
    // `<declared-dir>/config` — a directory that doesn't exist — silently emptying target,
    // trailmap, and scripted-tool discovery for the very workspace that asked to be used.
    // The declaration is the one behind the trails dir IN EFFECT, not the launch cwd's, so a
    // workspace switch on a running daemon moves the config dir along with the trails dir.
    val appConfig = trailblazeSettingsRepo.serverStateFlow.value.appConfig
    TrailblazeDesktopUtil.effectiveWorkspaceConfigDir(appConfig)?.let { return it }
    val trailsDir = File(TrailblazeDesktopUtil.getEffectiveTrailsDirectory(appConfig))
    if (!trailsDir.isDirectory) return null
    return resolveWorkspaceConfigDir(trailsDir)
  }

  /**
   * The neutral fallback target applied when no explicit / persisted / workspace-default target
   * resolves, and the id the daemon treats as the neutral-"default" sentinel
   * ([xyz.block.trailblaze.ui.TrailblazeSettingsRepo.getCurrentSelectedTargetApp]).
   *
   * **Contract: its `id` MUST equal [TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id].**
   * The CLI's target-attribution surfaces hardcode that compile-time static as their neutral
   * sentinel (`authoritativeSelectedTargetId` in `CliInfrastructure.kt`), while the daemon uses
   * this injected value. If a distribution overrides this with a target whose id differs, CLI
   * attribution and daemon run-resolution silently disagree on what "no explicit selection" means.
   * Every shipped distribution injects [TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget]
   * (a previous hardcoded per-distribution default moved to a committed workspace
   * `defaults.target` instead) — keep it that way.
   */
  abstract val defaultAppTarget: TrailblazeHostAppTarget

  /**
   * The app-target set as of daemon startup — the once-per-JVM `by lazy` seed of
   * [rediscoverAppTargets]. This value itself is frozen for the process lifetime; the live set the
   * UI and run dispatch read is the device manager's, which grows additively via
   * [xyz.block.trailblaze.ui.TrailblazeDeviceManager.registerNewTarget] and is replaced wholesale
   * on a workspace switch via [xyz.block.trailblaze.ui.TrailblazeDeviceManager.reloadAppTargets].
   */
  abstract val availableAppTargets: Set<TrailblazeHostAppTarget>

  /**
   * Runs full workspace app-target discovery against the CURRENT on-disk config. This is the
   * single source of truth for discovery: [availableAppTargets] is a `by lazy` over it, and the
   * device manager's live-registration path re-invokes it to pick up a newly-created target — so
   * the startup set and the live-discovered set are computed by identical code and can't drift.
   *
   * [failFast] is forwarded to `AppTargetDiscovery.discover`: false (the default, and what the
   * startup seed uses) masks a discovery failure as the one-target fallback set so the daemon
   * still boots, true rethrows so a caller replacing an existing set can tell failure apart from
   * an empty workspace. See that method's kdoc.
   */
  abstract fun rediscoverAppTargets(failFast: Boolean = false): Set<TrailblazeHostAppTarget>

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

    // `trailblaze config llm none` disables the LLM entirely. Return a sentinel model so
    // downstream client resolution picks NoOpLlmClient, which fails fast if any step needs
    // live inference — rather than silently falling through to the hardcoded default.
    if (savedProviderId == TrailblazeLlmProvider.NONE.id) {
      return TrailblazeLlmModel.fallback(TrailblazeLlmProvider.NONE, savedModelId)
    }

    val currentProviderModelList =
      getCurrentlyAvailableLlmModelLists().firstOrNull { it.provider.id == savedProviderId }
        ?: defaultProviderModelList

    val selectedTrailblazeLlmModel: TrailblazeLlmModel =
      resolveSavedModelWithinProvider(
        entries = currentProviderModelList.entries,
        savedModelId = savedModelId,
        providerDefaultModelId = BuiltInLlmModelRegistry.defaultModelForProvider(
          currentProviderModelList.provider,
        ),
      ) ?: defaultLlmModel
    return selectedTrailblazeLlmModel
  }

  /**
   * Resolves a [TrailblazeLlmModel] from explicit provider/model ID strings.
   * Used by the CLI `--llm-provider` / `--llm-model` flags.
   *
   * Searches all available model lists for a matching entry. If [providerId] is given,
   * only models from that provider are considered. If [modelId] is given, it must match
   * exactly. Returns null if no match is found.
   */
  fun resolveLlmModel(providerId: String?, modelId: String?): TrailblazeLlmModel? {
    // `--llm-provider none` disables the LLM; no model list lookup needed.
    if (providerId.equals(TrailblazeLlmProvider.NONE.id, ignoreCase = true)) {
      return TrailblazeLlmModel.fallback(TrailblazeLlmProvider.NONE, modelId ?: TrailblazeLlmProvider.NONE.id)
    }
    val allModelLists = getAllSupportedLlmModelLists()
    val providerLists = if (providerId != null) {
      allModelLists.filter { it.provider.id.equals(providerId, ignoreCase = true) }
    } else {
      allModelLists
    }
    if (modelId != null) {
      for (list in providerLists) {
        val match = list.entries.firstOrNull { it.modelId.equals(modelId, ignoreCase = true) }
        if (match != null) return match
      }
      return null
    }
    // Provider given but no model — return first model from that provider
    return providerLists.firstOrNull()?.entries?.firstOrNull()
  }

}

/**
 * Resolves a persisted model selection against the models its provider currently offers.
 *
 * Exact id match wins. When the saved id is gone — the catalog retired it, or a workspace
 * `llm.providers` block narrowed the provider to a different set — this falls back to the
 * provider's own `default_model` rather than letting the caller drop to its global default.
 * That global default is `NONE` in the OSS desktop distribution, so without this step
 * retiring a model id silently disables a user's LLM on upgrade instead of moving them to
 * the current default for the provider they chose.
 *
 * Returns null only when neither the saved id nor the provider default is present, which
 * leaves the caller's own fallback in charge.
 */
internal fun resolveSavedModelWithinProvider(
  entries: List<TrailblazeLlmModel>,
  savedModelId: String,
  providerDefaultModelId: String?,
): TrailblazeLlmModel? = entries.firstOrNull { it.modelId == savedModelId }
  ?: providerDefaultModelId?.let { defaultId -> entries.firstOrNull { it.modelId == defaultId } }

/**
 * Resolves the workspace config directory for a picked trails directory, honoring both
 * workspace layouts. Each ancestor is probed against
 * [TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR_CANDIDATES] — standalone `trailblaze-config/`
 * first, then legacy `trails/config/` — so the closest ancestor wins and the standalone layout
 * only breaks ties at the same ancestor. Resolution order:
 *
 *  1. A candidate inside [trailsDir] or as its sibling, existence alone sufficing — the picked
 *     dir is the workspace root or one level under it, and a freshly-scaffolded workspace may
 *     not have authored `trailblaze.yaml` yet.
 *  2. A candidate at any higher ancestor, but only when it carries the `trailblaze.yaml`
 *     workspace anchor — the picked dir may be a trail library nested deep in the workspace
 *     (that's the standalone layout's point), and requiring the anchor keeps a stray
 *     same-named directory further up from hijacking the workspace.
 *  3. The legacy `<trailsDir>/config` — NOT required to exist: a brand-new workspace has
 *     nothing authored yet, and Trail Runner's Create Target flow must be able to scaffold
 *     `trails/config/trailmaps/<id>/` inside it (`ToolSourceFiles.newTrailmapBaseDir`).
 *
 * Probing the legacy candidate at each ancestor (not just as the rung-3 fallback under
 * [trailsDir]) is what lets a person pick the REPO ROOT of a legacy-layout workspace. That is the
 * only pick that works for a repo which keeps its config at `trails/config/` but its trails
 * elsewhere (co-located with the features they test, say `jobs/<job>/trails/`): picking the root
 * used to derive `<root>/config`, a directory that doesn't exist, so the workspace contributed no
 * trailmaps and none of its app targets appeared — while picking `<root>/trails` fixed the config
 * dir but emptied the trails list. This mirrors what `findWorkspaceRoot` and `CliPathUtils`
 * already do, which is why the CLI resolved such a workspace from the same directory all along.
 *
 * Pure over the filesystem (no settings/env reads) so it's unit-testable with temp dirs.
 */
internal fun resolveWorkspaceConfigDir(trailsDir: File): File {
  var ancestor: File? = trailsDir
  var depth = 0
  while (ancestor != null) {
    val current = ancestor
    val match = TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR_CANDIDATES
      .map { File(current, it) }
      .firstOrNull { candidate ->
        candidate.isDirectory &&
          (depth <= 1 || File(candidate, TrailblazeConfigPaths.CONFIG_FILENAME).isFile)
      }
    if (match != null) return match
    ancestor = current.parentFile
    depth++
  }
  return File(trailsDir, TrailblazeConfigPaths.WORKSPACE_CONFIG_SUBDIR)
}
