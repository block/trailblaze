package xyz.block.trailblaze.model

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.EmptyTrailblazeToolSurface
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSurface
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
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
  )
