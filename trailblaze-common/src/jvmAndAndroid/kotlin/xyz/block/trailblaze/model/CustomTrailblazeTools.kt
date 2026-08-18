package xyz.block.trailblaze.model

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.EmptyTrailblazeToolSurface
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSurface
import xyz.block.trailblaze.toolcalls.allToolNames
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.ResolvedTargetToolScope
import xyz.block.trailblaze.toolcalls.ResolvedToolExclusions
import xyz.block.trailblaze.toolcalls.logDeclaredToolSetProblemsOnce
import xyz.block.trailblaze.toolcalls.resolveToolScopeForDriver
import xyz.block.trailblaze.toolcalls.toolName
import kotlin.reflect.KClass

data class CustomTrailblazeTools(
  /** App Specific Tools given to the LLM by Default */
  val registeredAppSpecificLlmTools: Set<KClass<out TrailblazeTool>>,
  /** Configuration for Trailblaze test execution */
  val config: TrailblazeConfig,
  /**
   * Driver this target runs on. When set, downstream tool repo construction (both
   * [initialToolRepoToolClasses]' default value and [TrailblazeToolRepo.withDynamicToolSets])
   * filters catalog entries by [ToolSetCatalogEntry.compatibleDriverTypes] so mobile-only tools
   * don't leak into Playwright/Compose/Revyl sessions and vice versa. Leave null if the driver
   * isn't known at construction time — callers get the full `DefaultLlmTrailblazeTools` surface.
   */
  val driverType: TrailblazeDriverType? = null,
  /**
   * App Specific YAML-defined tool names given to the LLM by default. Symmetric with
   * [registeredAppSpecificLlmTools] for the class-backed case. Use this for tools declared as
   * YAML configs under the `trails/config/trailmaps/<id>/tools/` resource directory (no backing [KClass])
   * that the rule wants visible to the LLM without going through a toolset-id indirection.
   * Defaults to empty; rules that only reference class-backed tools don't need to set it.
   */
  val registeredAppSpecificYamlToolNames: Set<ToolName> = emptySet(),
  /**
   * App Specific scripted (`.ts` / `.js`) tool names given to the LLM by default. Symmetric with
   * [registeredAppSpecificYamlToolNames] for the scripted case. Use this for tools delivered by a
   * toolset's `tools:` (e.g. `openUrl` via `core_interaction`) or listed in a target's
   * `platforms.<p>.tools:` — advertised to the LLM but dispatched through the per-session
   * scripted-tool runtime. Defaults to empty; rules that reference no scripted tools don't set it.
   */
  val registeredAppSpecificScriptedToolNames: Set<ToolName> = emptySet(),
  /** App Specific Tools that can be registered to the LLM, but are not by default */
  val otherAppSpecificLlmTools: Set<KClass<out TrailblazeTool>> = setOf(),
  /** App Specific Tools that cannot be registered to the LLM */
  val nonLlmAppSpecificTools: Set<KClass<out TrailblazeTool>> = setOf(),
  /**
   * Initial set of tool classes given to the LLM via a [TrailblazeToolRepo]. If [driverType] is
   * set, the default is driver-filtered from the catalog; otherwise it's the full
   * [TrailblazeToolSet.DefaultLlmTrailblazeTools] surface. Callers can override to compose an
   * explicit set.
   *
   * Note: this default is computed from the classpath-discovered catalog
   * ([TrailblazeToolSetCatalog.defaultEntries]), not [toolSetCatalog], because data-class
   * default values can't cleanly reference a field declared after them. Callers who supply a
   * custom [toolSetCatalog] AND want the driver-filtered default should pass
   * `initialToolRepoToolClasses` explicitly via
   * `TrailblazeToolSetCatalog.defaultToolClassesForDriver(driverType, catalog = toolSetCatalog)`.
   */
  val initialToolRepoToolClasses: Set<KClass<out TrailblazeTool>> =
    (
      driverType?.let { TrailblazeToolSetCatalog.defaultToolClassesForDriver(it) }
        ?: TrailblazeToolSet.DefaultLlmTrailblazeTools
      ) + registeredAppSpecificLlmTools,
  /**
   * Initial set of YAML-defined tool names given to the LLM via a [TrailblazeToolRepo].
   * Symmetric with [initialToolRepoToolClasses] for the YAML-backed case. If [driverType] is
   * set, the default is the driver-compatible YAML surface from the catalog; otherwise empty
   * (pre-existing behavior was to ignore YAML names on this path).
   *
   * Same catalog-scope caveat as [initialToolRepoToolClasses] applies — the default uses the
   * classpath-discovered catalog. Override explicitly to compose against a custom
   * [toolSetCatalog].
   */
  val initialToolRepoYamlToolNames: Set<ToolName> =
    (
      driverType?.let { TrailblazeToolSetCatalog.defaultYamlToolNamesForDriver(it) }
        ?: emptySet()
      ) + registeredAppSpecificYamlToolNames,
  /**
   * Initial set of scripted (`.ts` / `.js`) tool names given to the LLM via a [TrailblazeToolRepo].
   * Symmetric with [initialToolRepoYamlToolNames] for the scripted-backed case. If [driverType] is
   * set, the default is the driver-compatible scripted surface from the catalog; otherwise empty.
   *
   * Same catalog-scope caveat as [initialToolRepoToolClasses] applies — the default uses the
   * classpath-discovered catalog. Override explicitly to compose against a custom
   * [toolSetCatalog].
   */
  val initialToolRepoScriptedToolNames: Set<ToolName> =
    (
      driverType?.let { TrailblazeToolSetCatalog.defaultScriptedToolNamesForDriver(it) }
        ?: emptySet()
      ) + registeredAppSpecificScriptedToolNames,
  /** Optional custom toolset catalog for dynamic toolset switching. */
  val toolSetCatalog: List<ToolSetCatalogEntry>? = null,
  /**
   * The target's `excluded_tools:` opt-outs for [driverType], split by backing
   * (class / YAML / scripted) as a [TrailblazeToolSurface]. Forwarded by [toTrailblazeToolRepo] to
   * [TrailblazeToolRepo.withDynamicToolSets] so every partition is subtracted from the repo's
   * initial surface.
   *
   * This is load-bearing for **scripted** exclusions specifically: a scripted tool delivered by an
   * always-enabled toolset (e.g. `openUrl` via `core_interaction`) is re-added inside
   * `withDynamicToolSets` from the catalog's `coreTools`, so it can't be pre-subtracted into
   * [initialToolRepoToolClasses] / [initialToolRepoYamlToolNames] the way the class/YAML opt-outs
   * are — it has to ride through here to actually drop on the on-device path. Populate from
   * `target.getExcludedToolSurfaceForDriver(driverType)`. Defaults to no exclusions.
   */
  val initialToolRepoExclusions: TrailblazeToolSurface = EmptyTrailblazeToolSurface,
  /**
   * Catalog toolset ids the tool repo's surface is scoped to — a target's trailmap `tool_sets:`
   * declarations for [driverType]. Null (the default) keeps the whole-catalog surface for callers
   * that have no target in hand. Forwarded by [toTrailblazeToolRepo] to
   * [TrailblazeToolRepo.withDynamicToolSets]; populate it from
   * `target.getDeclaredToolSetIdsForDriver(driverType)` so an on-device session advertises the same
   * tools `getAgentToolboxForDriver` reports for that target and driver.
   *
   * Declared last so adding it doesn't shift the generated `componentN()` / `copy()` positions of
   * the existing parameters — those positions are public API.
   */
  val toolSetIds: List<String>? = null,
) {
  fun allForSerializationTools(): Set<KClass<out TrailblazeTool>> = buildSet {
    addAll(registeredAppSpecificLlmTools)
    addAll(otherAppSpecificLlmTools)
    addAll(nonLlmAppSpecificTools)
    addAll(initialToolRepoToolClasses)
    addAll(TrailblazeToolSet.DefaultLlmTrailblazeTools)
    addAll(TrailblazeToolSet.NonLlmTrailblazeTools)
    add(ObjectiveStatusTrailblazeTool::class)
  }

  fun allForSerializationToolsByName(): Map<ToolName, KClass<out TrailblazeTool>> = allForSerializationTools().associateBy { it.toolName() }
}

/**
 * Canonical "turn this [CustomTrailblazeTools] into a live [TrailblazeToolRepo]" helper.
 *
 * Forwards every field that matters for repo construction to
 * [TrailblazeToolRepo.withDynamicToolSets]:
 * - [CustomTrailblazeTools.initialToolRepoToolClasses] — class-backed custom tools
 * - [CustomTrailblazeTools.initialToolRepoYamlToolNames] — YAML-defined custom tools
 * - [CustomTrailblazeTools.initialToolRepoScriptedToolNames] — scripted (`.ts` / `.js`) custom tools
 * - [CustomTrailblazeTools.initialToolRepoExclusions] — `excluded_tools:` opt-outs (all backings)
 * - [CustomTrailblazeTools.toolSetCatalog] — dynamic-toolset catalog (falls back to classpath)
 * - [CustomTrailblazeTools.driverType] — driver filter for `always_enabled` entries
 * - [CustomTrailblazeTools.toolSetIds] — trailmap `tool_sets:` scope for the catalog surface
 *
 * Used by `AndroidTrailblazeRule` and downstream rule types so every rule type shares a
 * single wiring path. Having one callable to forward the fields makes it impossible for a
 * future refactor to drop one field from one rule and leave the other inconsistent (a
 * failure mode that has bitten this migration before during code review).
 */
fun CustomTrailblazeTools.toTrailblazeToolRepo(): TrailblazeToolRepo =
  TrailblazeToolRepo.withDynamicToolSets(
    customToolClasses = initialToolRepoToolClasses,
    customYamlToolNames = initialToolRepoYamlToolNames,
    customScriptedToolNames = initialToolRepoScriptedToolNames,
    // Forward all three exclusion partitions. The class/YAML opt-outs are usually already removed
    // from initialToolRepo* by the caller, so subtracting them again is idempotent; the scripted
    // partition is the one that *must* arrive here, since always-enabled scripted tools are
    // re-added from the catalog inside withDynamicToolSets and can't be pre-subtracted upstream.
    excludedToolClasses = initialToolRepoExclusions.toolClasses,
    excludedYamlToolNames = initialToolRepoExclusions.yamlToolNames,
    excludedScriptedToolNames = initialToolRepoExclusions.scriptedToolNames,
    catalog = toolSetCatalog ?: TrailblazeToolSetCatalog.defaultEntries(),
    driverType = driverType,
    toolSetIds = toolSetIds,
  )

/**
 * The [CustomTrailblazeTools] any runtime should run this target with on [driverType] — the
 * trailmap-declared toolsets plus the target's own tools, minus its `excluded_tools:`.
 *
 * **The one composer.** On-device rules, the host runner, and the daemon all build their session
 * tool repo here, off the same [resolveToolScopeForDriver] scope that
 * [xyz.block.trailblaze.toolcalls.getAgentToolboxForDriver] reports from. Composing per runtime is
 * what let them disagree: the host and daemon resolved against the whole catalog while on-device
 * resolved against the trailmap, so the same (target, driver) got a different tool array depending
 * on where it ran — and the device's exceeded the providers' 128-tool cap.
 *
 * Deliberately WIDER than the advertised surface: no `surfaceToLlm` or YAML-config-presence filter
 * runs here, because the repo must still *dispatch* a tool the LLM was never shown (a recorded step
 * replaying an internal step, or a scripted tool composed by a sibling). Advertisement is gated
 * later, inside the repo. Narrowing this to the advertised set would break recorded replays.
 *
 * The declared ids ride through on [CustomTrailblazeTools.toolSetIds] rather than only being
 * pre-resolved into the initial sets, because the scripted partition is re-derived from the catalog
 * inside the repo ([TrailblazeToolRepo.allCatalogScriptedToolNames]) and would otherwise still be
 * catalog-wide.
 */
fun TrailblazeHostAppTarget.toCustomTrailblazeToolsForDriver(
  driverType: TrailblazeDriverType,
  config: TrailblazeConfig = TrailblazeConfig.DEFAULT,
  catalog: List<ToolSetCatalogEntry> = TrailblazeToolSetCatalog.defaultEntries(),
  /**
   * Tools a runtime contributes that this target's YAML can't name — the host's driver-specific
   * web classes, or the daemon's OTHER bound targets. All three backings, because a runtime that
   * contributes class-backed tools generally contributes YAML and scripted ones too; taking only
   * classes here silently dropped the daemon's sibling-target YAML/scripted tools to
   * "Unknown tool". The target's `excluded_tools:` still wins over anything added here.
   */
  additional: TrailblazeToolSurface = EmptyTrailblazeToolSurface,
  /**
   * Opt-outs the runtime imposes on top of the target's own `excluded_tools:` — a caller that
   * suppresses specific tool classes for its harness. Unioned with the target's exclusions, so
   * either source removing a tool removes it.
   */
  additionalExclusions: TrailblazeToolSurface = EmptyTrailblazeToolSurface,
): CustomTrailblazeTools =
  resolveToolScopeForDriver(driverType, catalog)
    .toCustomTrailblazeTools(config, catalog, additional, additionalExclusions)

/**
 * The same composer for a caller that already resolved the scope. Threading the scope rather than
 * re-deriving it is what makes "resolved once" structural instead of a convention.
 */
fun ResolvedTargetToolScope.toCustomTrailblazeTools(
  config: TrailblazeConfig = TrailblazeConfig.DEFAULT,
  catalog: List<ToolSetCatalogEntry> = TrailblazeToolSetCatalog.defaultEntries(),
  additional: TrailblazeToolSurface = EmptyTrailblazeToolSurface,
  additionalExclusions: TrailblazeToolSurface = EmptyTrailblazeToolSurface,
): CustomTrailblazeTools {
  val scope = this
  // The target's `excluded_tools:` unioned with whatever the runtime suppresses. One combined
  // surface so every subtraction below reads from the same place — splitting them is how the
  // scripted partition kept getting dropped from one site and not another.
  val excluded = ResolvedToolExclusions(
    toolClasses = scope.excluded.toolClasses + additionalExclusions.toolClasses,
    yamlToolNames = scope.excluded.yamlToolNames + additionalExclusions.yamlToolNames,
    scriptedToolNames = scope.excluded.scriptedToolNames + additionalExclusions.scriptedToolNames,
  )
  // Logged in the composer so on-device, host, and daemon all get it — this is the line that
  // answers "why can't the agent call tool X here?" after a session comes up narrower than
  // expected. On-device used to log it at the rule; the other two logged nothing.
  // NOT exclusion-subtracted: `registeredAppSpecific*` also feeds `allForSerializationTools()`,
  // which is the YAML *decoder* registry. Dropping an excluded tool from it turns "declared but not
  // advertised" into "recorded trail fails to parse". Exclusions apply to the repo surface below.
  val customTools = scope.customToolClasses + additional.toolClasses
  val customYaml = scope.customYamlToolNames + additional.yamlToolNames
  val customScripted = scope.customScriptedToolNames + additional.scriptedToolNames
  val repoToolClasses = scope.toolClasses + customTools - excluded.toolClasses
  val repoYamlToolNames = scope.yamlToolNames + customYaml - excluded.yamlToolNames
  val repoScriptedToolNames = scope.scriptedToolNames + customScripted - excluded.scriptedToolNames
  // Every number here is a number the repo actually ends up with. Logging the SCOPE's counts
  // instead under-reported any runtime contribution and, worse, any runtime exclusion: on the
  // daemon that is precisely the sibling-target surface, so the one line meant to explain "why
  // can't the agent call tool X here?" omitted the reason.
  Console.log(
    "Resolved tools for target='${scope.targetId}' driver=${driverType.yamlKey}: " +
      "trailmapToolSets=${scope.declaredToolSetIds.ifEmpty { "<unconfigured — whole catalog>" }} " +
      "class=${repoToolClasses.size} yaml=${repoYamlToolNames.size} " +
      "scripted=${repoScriptedToolNames.size} excluded=${excluded.allToolNames.size}",
  )
  // Deduped inside, so this stays one report per misconfiguration no matter how often a session
  // recomposes — the daemon skips caching a runtime for exactly the targets whose `tool_sets:`
  // resolved to nothing, so "compose once" was never a real guarantee for them.
  scope.logDeclaredToolSetProblemsOnce()
  return CustomTrailblazeTools(
    registeredAppSpecificLlmTools = customTools,
    config = config,
    driverType = driverType,
    registeredAppSpecificYamlToolNames = customYaml,
    registeredAppSpecificScriptedToolNames = customScripted,
    // Straight off the scope — the unconfigured-target fallback to the whole driver catalog is
    // already baked into `scope.fromTrailmap`, so this composer no longer re-decides it. Re-deciding
    // here is what let the daemon and this repo disagree about an unconfigured target.
    initialToolRepoToolClasses = repoToolClasses,
    initialToolRepoYamlToolNames = repoYamlToolNames,
    initialToolRepoScriptedToolNames = repoScriptedToolNames,
    toolSetCatalog = catalog,
    // The whole exclusion surface rides through so the SCRIPTED opt-outs drop too — those are
    // re-added from the catalog inside `withDynamicToolSets`, so pre-subtracting can't reach them.
    initialToolRepoExclusions = excluded,
    // Null when the target declared nothing for this driver — see [ResolvedTargetToolScope.isScoped].
    // An unconfigured target keeps the whole driver-compatible catalog rather than collapsing to
    // `always_enabled`, which would leave the agent unable to verify or navigate.
    toolSetIds = scope.declaredToolSetIds.takeIf { scope.isScoped },
  )
}

/**
 * **The session tool repo, for every runtime.** On-device rules, the host runner, and the daemon
 * all build theirs here, so a given (target, driver) gets the same tools wherever it runs.
 *
 * Nullable receiver because a session can legitimately have no target (a bare `trailblaze run`
 * against a device with no trailmap bound). With no target there is nothing to scope to, so that
 * case — and only that case — keeps the driver-compatible whole-catalog surface.
 *
 * @param additional tools the runtime contributes that the target's YAML can't name — the host's
 *   driver-specific web classes, or the daemon's other bound targets. All three backings; the
 *   target's `excluded_tools:` still wins over them.
 */
fun TrailblazeHostAppTarget?.toSessionToolRepo(
  driverType: TrailblazeDriverType,
  config: TrailblazeConfig = TrailblazeConfig.DEFAULT,
  catalog: List<ToolSetCatalogEntry> = TrailblazeToolSetCatalog.defaultEntries(),
  additional: TrailblazeToolSurface = EmptyTrailblazeToolSurface,
  additionalExclusions: TrailblazeToolSurface = EmptyTrailblazeToolSurface,
): TrailblazeToolRepo = this
  ?.toCustomTrailblazeToolsForDriver(
    driverType = driverType,
    config = config,
    catalog = catalog,
    additional = additional,
    additionalExclusions = additionalExclusions,
  )
  ?.toTrailblazeToolRepo()
  ?: TrailblazeToolRepo.withDynamicToolSets(
    customToolClasses = additional.toolClasses,
    customYamlToolNames = additional.yamlToolNames,
    customScriptedToolNames = additional.scriptedToolNames,
    // No target means no `excluded_tools:`, but a runtime's own opt-outs still apply.
    excludedToolClasses = additionalExclusions.toolClasses,
    excludedYamlToolNames = additionalExclusions.yamlToolNames,
    excludedScriptedToolNames = additionalExclusions.scriptedToolNames,
    catalog = catalog,
    driverType = driverType,
  )

private fun catalogToolClasses(d: TrailblazeDriverType, c: List<ToolSetCatalogEntry>) =
  TrailblazeToolSetCatalog.defaultToolClassesForDriver(d, c)

private fun catalogYamlNames(d: TrailblazeDriverType, c: List<ToolSetCatalogEntry>) =
  TrailblazeToolSetCatalog.defaultYamlToolNamesForDriver(d, c)

private fun catalogScriptedNames(d: TrailblazeDriverType, c: List<ToolSetCatalogEntry>) =
  TrailblazeToolSetCatalog.defaultScriptedToolNamesForDriver(d, c)
