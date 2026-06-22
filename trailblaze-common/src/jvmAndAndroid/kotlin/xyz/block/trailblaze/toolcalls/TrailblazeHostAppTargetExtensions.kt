package xyz.block.trailblaze.toolcalls

import kotlin.reflect.KClass
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.util.Console

/**
 * The resolved set of tools the LLM agent sees at session start for [target] running on
 * [driverType]. Mirrors the runtime composition in
 * `TrailblazeMcpServer.kt`'s inner-agent-tools-provider: declared toolsets resolved through
 * the driver-aware catalog, plus the target's custom tools, minus its driver-scoped
 * exclusions. The [surfaceToLlm] filter (applied at descriptor build time via
 * `toKoogToolDescriptor()`) is applied here too so callers don't have to re-filter.
 *
 * This is the single authoritative resolver — the `trailblaze check` markdown report, the
 * `toolbox` discovery surface, and any other "what does the LLM see?" caller should route
 * through this function so they stay consistent with what actually registers at session
 * start. Diverging here is the source of bug
 * `2026-05-22-agent-toolbox-report-driver-leak.md`.
 *
 * The LLM-visible surface has three kinds: class-backed ([toolClasses]), YAML-defined
 * ([yamlToolNames]), and scripted (`.ts` / `.js`) tools delivered by a toolset's `tools:`
 * ([scriptedToolNames]). Scripted tools are bundled + registered through a separate per-session
 * runtime, but they're still advertised to the LLM, so a resolver that claims to answer "what
 * does the LLM see?" must include them. Omitting them is what let `openUrl` — converted from a
 * class-backed tool to a scripted one in PR #3803 — silently disappear from this resolver while
 * the `trailblaze check` report still surfaced it (its driver-agnostic "Scripted tools" section).
 */
data class ResolvedAgentToolbox(
  override val toolClasses: Set<KClass<out TrailblazeTool>>,
  override val yamlToolNames: Set<ToolName>,
  override val scriptedToolNames: Set<ToolName> = emptySet(),
) : TrailblazeToolSurface

fun TrailblazeHostAppTarget.getAgentToolboxForDriver(
  driverType: TrailblazeDriverType,
  catalog: List<ToolSetCatalogEntry> = TrailblazeToolSetCatalog.defaultEntries(),
  yamlToolConfigsByName: Map<ToolName, ToolYamlConfig> =
    TrailblazeSerializationInitializer.buildYamlDefinedTools(),
): ResolvedAgentToolbox {
  val declaredToolSetIds = getDeclaredToolSetIdsForDriver(driverType)
  val resolvedFromTrailmap = TrailblazeToolSetCatalog.resolveForDriver(
    driverType = driverType,
    requestedIds = declaredToolSetIds,
    catalog = catalog,
  )
  val customClasses = getCustomToolsForDriver(driverType)
  val customYamlNames = getCustomYamlToolNamesForDriver(driverType)
  // Single entry point for the target's `excluded_tools:` opt-outs across all three backings
  // (class / YAML / scripted) — see [getExcludedToolSurfaceForDriver]. Reading the surface once
  // here is what keeps this resolver from re-introducing the "subtract class + YAML but forget
  // scripted" drift that hand-rolled per-partition unions repeatedly caused.
  val excluded = getExcludedToolSurfaceForDriver(driverType)
  val toolClasses = (resolvedFromTrailmap.toolClasses + customClasses - excluded.toolClasses)
    // Fail-fast like `KClass.toKoogToolDescriptor()` at LLM-registration time: a tool
    // class without a `@TrailblazeToolClass` annotation is a configuration bug, not a
    // recoverable condition.
    .filter { it.trailblazeToolClassAnnotation().surfaceToLlm }
    .toSet()
  // Mirror `KoogToolExt.buildDescriptorsForYamlDefined`: skip YAML tool names with no
  // registered config (with a warning, same shape as the runtime) and drop names whose
  // config declared `surface_to_llm: false`. Without the missing-config filter the
  // resolver over-reports — a typo in `tool_sets:` would appear visible here but get
  // skipped at LLM registration.
  val candidateYamlNames = resolvedFromTrailmap.yamlToolNames + customYamlNames - excluded.yamlToolNames
  val yamlToolNames = candidateYamlNames
    .filter { name ->
      val config = yamlToolConfigsByName[name]
      when {
        config == null -> {
          Console.log(
            "getAgentToolboxForDriver: no YAML config registered for tool '${name.toolName}' " +
              "on driver=${driverType.yamlKey}; will be skipped at LLM registration.",
          )
          false
        }
        config.surfaceToLlm == false -> false
        else -> true
      }
    }
    .toSet()
  // Scripted (`.ts` / `.js`) tool names the LLM sees — the canonical advertised surface (report /
  // discovery / CLI). Three sources:
  //   1. Toolset-delivered (`resolveForDriver(...).scriptedToolNames`) — e.g. `openUrl` via
  //      `core_interaction`. `resolveForDriver` already dropped toolsets incompatible with this
  //      driver, so `openUrl` surfaces under android/ios but not web (core_interaction is
  //      mobile-only). The daemon registers these per session via
  //      `TrailblazeMcpServer.ensureSessionScriptToolRuntime`.
  //   2. Target custom scripted tools (`getCustomScriptedToolNamesForDriver(driverType)`) — a
  //      scripted name listed directly in the target's `platforms.<p>.tools:`. This is the
  //      inclusion-side mirror of `customYamlNames`/`customClasses` above; before it, such a name
  //      was classified into neither the class nor YAML inclusion bucket and silently dropped as an
  //      "unknown tool". (The daemon's in-process scripted-tool launcher bundles only catalog-
  //      delivered scripted tools today, so wiring a *bare-`tools:`* scripted tool through the live
  //      runtime is follow-up parity work — this resolver advertises it either way.)
  //   3. Target-root inline tools (`target.tools:`, via `getInlineScriptTools()`) — synthesized
  //      as a subprocess at runtime, but still advertised to the LLM, so they belong here too.
  //      Omitting them under-reported the surface for targets that author their own scripted
  //      tools (Codex review on PR #3851).
  // Then subtract the target's `excluded_tools:` scripted opt-outs (`excluded.scriptedToolNames`)
  // — the scripted-partition parallel of the class / YAML exclusions applied above, from the same
  // surface. Before this, a target's `excluded_tools: [openUrl]` was honored for class-backed and
  // YAML tools but silently ignored for toolset-delivered scripted tools, so `openUrl` stayed
  // advertised to the LLM. No `surfaceToLlm` filtering still: scripted tools have no
  // `@TrailblazeToolClass` annotation to read; the report's "Scripted tools" section — the surface
  // this resolver is pinned against — applies the same exclusion subtraction.
  val customScriptedNames = getCustomScriptedToolNamesForDriver(driverType)
  val scriptedToolNames = (
    resolvedFromTrailmap.scriptedToolNames +
      customScriptedNames +
      getInlineScriptTools().map { ToolName(it.name) }.toSet()
    ) - excluded.scriptedToolNames
  return ResolvedAgentToolbox(
    toolClasses = toolClasses,
    yamlToolNames = yamlToolNames,
    scriptedToolNames = scriptedToolNames,
  )
}

/**
 * Returns the initial LLM tool class surface for this target running on a specific
 * [driverType]. Canonical "target + driver aware" composition — target is the primary axis
 * (the app's own config), driver is the secondary axis (which platform the app is being
 * exercised on).
 *
 * Layering, from most general to most specific:
 *
 *   1. [TrailblazeToolSetCatalog.defaultToolClassesForDriver] — every catalog entry
 *      compatible with [driverType]
 *   2. `+` [TrailblazeHostAppTarget.getCustomToolsForDriver] — target-specific tool classes
 *      declared in its YAML `platforms.<driver>.tool_sets:` / `tools:`
 *   3. `-` [TrailblazeHostAppTarget.getExcludedToolsForDriver] — target opt-outs declared in
 *      its YAML `platforms.<driver>.excluded_tools:` (e.g. a target that wants to force the
 *      LLM away from x/y coordinate taps could exclude `tapOnPoint`).
 *      **Exclusion wins over custom** — if the target both custom-adds and excludes the same
 *      tool, the tool is absent from the result.
 *
 * Naming follows the target's existing `get*ForDriver` convention
 * ([TrailblazeHostAppTarget.getCustomToolsForDriver],
 * [TrailblazeHostAppTarget.getExcludedToolsForDriver]) and ties to the
 * [xyz.block.trailblaze.model.CustomTrailblazeTools] field it typically feeds:
 * `initialToolRepoToolClasses`.
 *
 * Implemented as an extension function rather than a method on [TrailblazeHostAppTarget]
 * because the target lives in `trailblaze-models` (which doesn't depend on `trailblaze-common`
 * where the catalog lives); keeping this here preserves the module direction while still
 * reading as `target.getInitialToolClassesForDriver(driverType)` at call sites. If more
 * target-scoped extensions arrive later, they belong alongside this one.
 */
fun TrailblazeHostAppTarget.getInitialToolClassesForDriver(
  driverType: TrailblazeDriverType,
  catalog: List<ToolSetCatalogEntry> = TrailblazeToolSetCatalog.defaultEntries(),
): Set<KClass<out TrailblazeTool>> {
  val base = TrailblazeToolSetCatalog.defaultToolClassesForDriver(driverType, catalog)
  val custom = getCustomToolsForDriver(driverType)
  val excluded = getExcludedToolSurfaceForDriver(driverType)
  return (base + custom) - excluded.toolClasses
}

/**
 * The target's resolved tool *exclusions* for [driverType] — the `excluded_tools:` opt-outs split
 * by backing (class-backed / YAML-defined / scripted), exposed as one [TrailblazeToolSurface].
 *
 * This is the exclusion-side mirror of the inclusion surface ([TrailblazeToolSurface.allToolNames]):
 * a **single entry point** so consumers read one shape instead of re-unioning
 * [TrailblazeHostAppTarget.getExcludedToolsForDriver] +
 * [TrailblazeHostAppTarget.getExcludedYamlToolNamesForDriver] +
 * [TrailblazeHostAppTarget.getExcludedScriptedToolNamesForDriver] by hand. Hand-rolled unions are
 * exactly what dropped the scripted partition before — a target's `excluded_tools: [openUrl]` was
 * honored for class/YAML tools but silently ignored for scripted ones. Every site that subtracts a
 * target's `excluded_tools:` (this resolver, the daemon inner-agent provider, the scripted-tool
 * runtime repo, the discovery layer, on-device rule wiring) routes through here so a future tool
 * backing can't be excluded in some sites but not others.
 *
 * An extension (not a method on [TrailblazeHostAppTarget]) for the same module-direction reason as
 * [getAgentToolboxForDriver]: [TrailblazeToolSurface] lives in `trailblaze-common`, the target in
 * `trailblaze-models`.
 */
fun TrailblazeHostAppTarget.getExcludedToolSurfaceForDriver(
  driverType: TrailblazeDriverType,
): ResolvedToolExclusions = ResolvedToolExclusions(
  toolClasses = getExcludedToolsForDriver(driverType),
  yamlToolNames = getExcludedYamlToolNamesForDriver(driverType),
  scriptedToolNames = getExcludedScriptedToolNamesForDriver(driverType),
)

/**
 * The three-way split of a target's `excluded_tools:` opt-outs for a driver, as a
 * [TrailblazeToolSurface]. Produced by [getExcludedToolSurfaceForDriver]; the exclusion-side analog
 * of [ResolvedAgentToolbox].
 */
data class ResolvedToolExclusions(
  override val toolClasses: Set<KClass<out TrailblazeTool>>,
  override val yamlToolNames: Set<ToolName>,
  override val scriptedToolNames: Set<ToolName>,
) : TrailblazeToolSurface
