package xyz.block.trailblaze.toolcalls

import kotlin.reflect.KClass
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget

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
