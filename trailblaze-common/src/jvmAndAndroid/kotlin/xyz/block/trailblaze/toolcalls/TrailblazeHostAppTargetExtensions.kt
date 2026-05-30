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
 */
data class ResolvedAgentToolbox(
  val toolClasses: Set<KClass<out TrailblazeTool>>,
  val yamlToolNames: Set<ToolName>,
)

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
  val excludedClasses = getExcludedToolsForDriver(driverType)
  val customYamlNames = getCustomYamlToolNamesForDriver(driverType)
  val excludedYamlNames = getExcludedYamlToolNamesForDriver(driverType)
  val toolClasses = (resolvedFromTrailmap.toolClasses + customClasses - excludedClasses)
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
  val candidateYamlNames = resolvedFromTrailmap.yamlToolNames + customYamlNames - excludedYamlNames
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
  return ResolvedAgentToolbox(toolClasses = toolClasses, yamlToolNames = yamlToolNames)
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
  val excluded = getExcludedToolsForDriver(driverType)
  return (base + custom) - excluded
}
