package xyz.block.trailblaze.toolcalls

import kotlin.reflect.KClass
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.config.ToolSetYamlLoader
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * A single entry in the toolset catalog that the LLM can browse and enable.
 *
 * [compatibleDriverTypes] mirrors the YAML `drivers:` / `platforms:` declaration; an empty set
 * means "compatible with every driver" (the default for framework toolsets like `meta`).
 */
data class ToolSetCatalogEntry(
  val id: String,
  val description: String,
  val toolClasses: Set<KClass<out TrailblazeTool>>,
  val yamlToolNames: Set<ToolName> = emptySet(),
  val alwaysEnabled: Boolean = false,
  val compatibleDriverTypes: Set<TrailblazeDriverType> = emptySet(),
) {
  val toolNames: List<String> by lazy {
    toolClasses.map { it.toolName().toolName } + yamlToolNames.map { it.toolName }
  }

  fun isCompatibleWith(driverType: TrailblazeDriverType): Boolean =
    compatibleDriverTypes.isEmpty() || driverType in compatibleDriverTypes
}

/**
 * The result of [TrailblazeToolSetCatalog.resolve] — both the Kotlin-backed tool classes and the
 * names of YAML-defined (`tools:` mode) tools that should be reachable to the LLM.
 *
 * YAML-defined tools don't have a KClass because their behavior is composed from primitives in
 * a YAML file; they are identified by their `id:` string and constructed at execute time via
 * [xyz.block.trailblaze.config.YamlDefinedTrailblazeTool].
 */
data class ResolvedToolSet(
  val toolClasses: Set<KClass<out TrailblazeTool>>,
  val yamlToolNames: Set<ToolName> = emptySet(),
)

/**
 * Catalog of available TrailblazeTool sets that can be dynamically enabled/disabled.
 *
 * Entries are discovered from `.yaml` files under `trailblaze-config/toolsets/` on the classpath.
 * YAML is the single source of truth — there are no Kotlin-declared toolsets here. Authoring a
 * new toolset is a matter of dropping a YAML file into the correct resources directory.
 *
 * The LLM starts with only the `alwaysEnabled` toolsets and can request additional ones via the
 * `setActiveToolSets` MCP tool. Each entry carries an id, description, and tool list so the LLM
 * can preview what's available without loading the full tool definitions.
 */
object TrailblazeToolSetCatalog {

  /**
   * Returns the default catalog entries, discovered from YAML toolset files on the classpath.
   * Cached after the first call since classpath discovery is not free.
   */
  fun defaultEntries(): List<ToolSetCatalogEntry> = defaultEntriesCache

  private val defaultEntriesCache: List<ToolSetCatalogEntry> by lazy {
    val resolver = ToolNameResolver.fromBuiltInAndCustomTools()
    ToolSetYamlLoader.discoverAndLoadAll(resolver).values
      .map { it.toCatalogEntry() }
      .sortedBy { it.id }
  }

  /**
   * Resolves a set of toolset IDs to the combined set of tool classes and YAML-defined tool
   * names. Always includes any entries marked `alwaysEnabled`.
   */
  fun resolve(
    requestedIds: List<String>,
    catalog: List<ToolSetCatalogEntry>,
  ): ResolvedToolSet {
    val selected = catalog.filter { it.alwaysEnabled || it.id in requestedIds }
    val toolClasses = selected.flatMap { it.toolClasses }.toSet()
    val yamlToolNames = selected.flatMap { it.yamlToolNames }.toSet()
    return ResolvedToolSet(toolClasses = toolClasses, yamlToolNames = yamlToolNames)
  }

  /**
   * Returns the class-backed tool classes for a single catalog entry's [id], ignoring the
   * `alwaysEnabled` auto-inclusion [resolve] performs. Use this when composing "just this
   * entry's tools in isolation" — e.g. a single-toolset category lookup or an app-specific
   * surface that will be combined with other sources — so that `meta` / `core_interaction`
   * tools don't silently ride along.
   *
   * Returns an empty set if [id] is not in the catalog (compatible with
   * `ToolYamlLoader.resolveClassBackedConfigsLeniently` semantics for missing entries).
   *
   * See Invariant 3 in `2026-04-21-yaml-tool-invariants.md` for the design rationale.
   */
  fun entryToolClasses(
    id: String,
    catalog: List<ToolSetCatalogEntry> = defaultEntries(),
  ): Set<KClass<out TrailblazeTool>> = catalog.firstOrNull { it.id == id }?.toolClasses ?: emptySet()

  /**
   * Returns the YAML-defined tool names for a single catalog entry's [id], ignoring the
   * `alwaysEnabled` auto-inclusion [resolve] performs. Symmetric with [entryToolClasses] for the
   * `tools:` composition case — entries like `navigation` include YAML-only tools (e.g.
   * `pressBack`) that have no backing [KClass].
   *
   * Returns an empty set if [id] is not in the catalog.
   */
  fun entryYamlToolNames(
    id: String,
    catalog: List<ToolSetCatalogEntry> = defaultEntries(),
  ): Set<ToolName> = catalog.firstOrNull { it.id == id }?.yamlToolNames ?: emptySet()

  /**
   * Returns catalog entries compatible with [driverType] (i.e. entries whose YAML `drivers:`
   * list includes [driverType] or is absent/empty, meaning "driver-agnostic").
   *
   * Shared plumbing for the three driver-aware public helpers ([resolveForDriver],
   * [defaultToolClassesForDriver], and the [TrailblazeHostAppTarget.getInitialToolClassesForDriver]
   * extension) so they all apply the same filter.
   */
  private fun compatibleEntries(
    driverType: TrailblazeDriverType,
    catalog: List<ToolSetCatalogEntry>,
  ): List<ToolSetCatalogEntry> = catalog.filter { it.isCompatibleWith(driverType) }

  /**
   * Driver-aware counterpart to [resolve]. Applies the `alwaysEnabled || id in requestedIds`
   * selection over only the entries compatible with [driverType].
   *
   * Use this for **opt-in / progressive disclosure**: a caller knows which toolsets it wants
   * to activate (`requestedIds`), and you want only the driver-compatible ones. For "every
   * driver-compatible tool class" (the flat, eager surface), use [defaultToolClassesForDriver]
   * instead; for "every driver-compatible tool class combined with a target's custom overlay
   * and exclusions," use [TrailblazeHostAppTarget.getInitialToolClassesForDriver].
   */
  fun resolveForDriver(
    driverType: TrailblazeDriverType,
    requestedIds: List<String>,
    catalog: List<ToolSetCatalogEntry> = defaultEntries(),
  ): ResolvedToolSet = resolve(requestedIds, compatibleEntries(driverType, catalog))

  /**
   * Returns every class-backed tool class from the catalog compatible with [driverType].
   * Respects each entry's `compatibleDriverTypes` (declared as YAML `drivers:` on each toolset).
   *
   * Driver-aware replacement for [TrailblazeToolSet.DefaultLlmTrailblazeTools]. Use this
   * for the **eager, flat** case: a caller knows its driver and wants every catalog tool
   * that can run on it. For progressive opt-in to specific toolsets, use [resolveForDriver];
   * for target-scoped composition, use [TrailblazeHostAppTarget.getInitialToolClassesForDriver].
   *
   * Avoids advertising mobile-only tools (e.g. `hideKeyboard`, `tapOnPoint` from
   * `core_interaction.yaml`) in Playwright / Compose / Revyl sessions, and vice versa.
   */
  fun defaultToolClassesForDriver(
    driverType: TrailblazeDriverType,
    catalog: List<ToolSetCatalogEntry> = defaultEntries(),
  ): Set<KClass<out TrailblazeTool>> =
    compatibleEntries(driverType, catalog).flatMap { it.toolClasses }.toSet()

  /**
   * Returns every YAML-defined tool name from the catalog compatible with [driverType].
   * Symmetric with [defaultToolClassesForDriver] for the class-backed case.
   *
   * Use this whenever a caller wants to advertise the default driver-compatible YAML tools
   * (the ones declared under `tools:` in toolset YAML files that don't have a backing
   * [KClass]) without having to enumerate them by name. Pairs with [defaultToolClassesForDriver]
   * at rule/repo construction time.
   */
  fun defaultYamlToolNamesForDriver(
    driverType: TrailblazeDriverType,
    catalog: List<ToolSetCatalogEntry> = defaultEntries(),
  ): Set<ToolName> =
    compatibleEntries(driverType, catalog).flatMap { it.yamlToolNames }.toSet()

  /**
   * Formats the catalog as a human-readable summary for embedding in tool descriptions.
   */
  fun formatCatalogSummary(catalog: List<ToolSetCatalogEntry>): String = buildString {
    appendLine("Available toolsets:")
    for (entry in catalog) {
      val toolNamesPreview = entry.toolNames.joinToString(", ")
      val marker = if (entry.alwaysEnabled) " [always enabled]" else ""
      appendLine("- **${entry.id}**$marker: ${entry.description} Tools: [$toolNamesPreview]")
    }
  }
}
