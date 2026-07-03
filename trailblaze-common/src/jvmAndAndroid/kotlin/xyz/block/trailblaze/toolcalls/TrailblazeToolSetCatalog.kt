package xyz.block.trailblaze.toolcalls

import kotlin.reflect.KClass
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.config.ToolSetYamlLoader
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.config.bundledConfigResourceSource

/**
 * A single entry in the toolset catalog that the LLM can browse and enable.
 *
 * [compatibleDriverTypes] mirrors the YAML `drivers:` / `platforms:` declaration; an empty set
 * means "compatible with every driver" (the default for framework toolsets like `meta`).
 */
data class ToolSetCatalogEntry(
  val id: String,
  val description: String,
  override val toolClasses: Set<KClass<out TrailblazeTool>>,
  override val yamlToolNames: Set<ToolName> = emptySet(),
  override val scriptedToolNames: Set<ToolName> = emptySet(),
  val alwaysEnabled: Boolean = false,
  val compatibleDriverTypes: Set<TrailblazeDriverType> = emptySet(),
) : TrailblazeToolSurface {
  // The reference union — class-backed, YAML-defined, and scripted names together, via the single
  // [allToolNames] accessor so this can't drift from how every other surface enumerates tools.
  val toolNames: List<String> by lazy { allToolNames.map { it.toolName } }

  fun isCompatibleWith(driverType: TrailblazeDriverType): Boolean =
    compatibleDriverTypes.isEmpty() || driverType in compatibleDriverTypes
}

/**
 * The result of [TrailblazeToolSetCatalog.resolve] — the Kotlin-backed tool classes plus the
 * names of YAML-defined (`tools:` mode) and scripted (`.ts` / `.js`) tools that should be
 * reachable to the LLM.
 *
 * YAML-defined tools don't have a KClass because their behavior is composed from primitives in
 * a YAML file; they are identified by their `id:` string and constructed at execute time via
 * [xyz.block.trailblaze.config.YamlDefinedTrailblazeTool]. Scripted tools likewise have no
 * KClass — they're authored in TypeScript/JS and dispatched through the per-session scripted-tool
 * runtime (host bun/QuickJS, on-device QuickJS bundle). Both are advertised to the LLM by name;
 * the separation lets the bundling layer tell which names need a scripted runtime registered.
 */
data class ResolvedToolSet(
  override val toolClasses: Set<KClass<out TrailblazeTool>>,
  override val yamlToolNames: Set<ToolName> = emptySet(),
  override val scriptedToolNames: Set<ToolName> = emptySet(),
) : TrailblazeToolSurface

/**
 * Catalog of available TrailblazeTool sets.
 *
 * Entries are discovered from `.yaml` files under `trails/config/trailmaps/<id>/toolsets/` on
 * the classpath. YAML is the single source of truth — there are no Kotlin-declared toolsets
 * here. Authoring a new toolset is a matter of dropping a YAML file into the correct
 * trailmap's `toolsets/` directory.
 *
 * The catalog is resolved once per session to build the initial tool surface (the whole
 * driver-compatible set is advertised up front — there is no runtime switching) and to scope a
 * `verify:` step to its driver-appropriate verification tools.
 */
object TrailblazeToolSetCatalog {

  /**
   * Returns the catalog entries — classpath-discovered + workspace overlay merged. The classpath
   * set is lazy-cached on first call (discovery is not free); the workspace overlay is registered
   * by `AppTargetDiscovery.discover()` via [registerWorkspaceToolSets] after walking the
   * workspace's `trails/config/trailmaps/<id>/toolsets/` directories. On id collision the
   * workspace overlay wins — same precedence rule as the filesystem resource source layering
   * in `AppTargetDiscovery` and the YAML-tool overlay in `TrailblazeSerializationInitializer`.
   */
  fun defaultEntries(): List<ToolSetCatalogEntry> {
    val classpath = defaultEntriesCache
    val overlay = workspaceCatalogEntries
    if (overlay.isEmpty()) return classpath
    // Merge by id with workspace winning. `associateBy` + `+` is the kotlin-idiomatic
    // last-write-wins for Map shape; convert back to a sorted list to keep stable iteration
    // order (`defaultEntries()` callers depend on it for log determinism).
    val merged = (classpath + overlay).associateBy { it.id }
    return merged.values.sortedBy { it.id }
  }

  private val defaultEntriesCache: List<ToolSetCatalogEntry> by lazy {
    // Pin to the BUNDLED view: this cache is the classpath snapshot that the
    // `workspaceCatalogEntries` overlay is layered on top of. With the JVM platform default
    // now workspace-aware (PR #3518), letting these calls inherit the default would bake
    // workspace toolsets into the cache, then `registerWorkspaceToolSets` would register
    // them again as the overlay — `getClasspathEntries()` would lose its "classpath-only"
    // KDoc guarantee, and `defaultEntries()` would produce different results depending on
    // whether the cache was warmed before or after `registerWorkspaceToolSets` ran.
    //
    // `bundledConfigResourceSource()` is platform-aware: `ClasspathConfigResourceSource`
    // on JVM, the AssetManager-backed source on Android (where there is no workspace
    // concept on device, so the bundled view IS the platform default). Same pinning shape
    // applied to `TrailblazeSerializationInitializer.buildYamlDefinedTools` / `buildAllTools`
    // for the same reason.
    val bundled = bundledConfigResourceSource()
    val resolver = ToolNameResolver.fromBuiltInAndCustomTools(resourceSource = bundled)
    ToolSetYamlLoader.discoverAndLoadAll(resolver, resourceSource = bundled)
      .values
      .map { it.toCatalogEntry() }
      .sortedBy { it.id }
  }

  // Workspace-discovered toolset entries that don't ship on the JVM classpath — they live in
  // a user's `<workspace>/trails/config/trailmaps/<id>/toolsets/<name>.yaml`. Populated
  // lazily from `AppTargetDiscovery` via [registerWorkspaceToolSets].
  // Held in a separate field rather than mutating [defaultEntriesCache] so the classpath
  // cache keeps its set-once contract and so a workspace-rediscovery can replace the
  // workspace overlay without invalidating the classpath cache.
  //
  // Read by [defaultEntries] which merges classpath + workspace before returning. The
  // `TrailblazeMcpServer.ensureSessionScriptToolRuntime` and inner-agent-tools-provider
  // call sites use `defaultEntries()` (directly or via `resolveForDriver` / `resolve`),
  // so this overlay is what makes workspace-toolset-pulled YAML tool names reachable at
  // dispatch time.
  @Volatile private var workspaceCatalogEntries: List<ToolSetCatalogEntry> = emptyList()

  /**
   * Registers workspace-discovered toolset entries as an overlay on top of the cached
   * classpath-discovered set. Called from `AppTargetDiscovery.discover()` once per discovery
   * pass with every toolset the workspace pipeline resolved (including workspace files,
   * workspace trailmap toolsets, and any other source the discovery's composite resource source
   * picked up).
   *
   * Idempotent for identical inputs; subsequent calls REPLACE the overlay rather than
   * appending — the host-side discovery pipeline is the single source of truth for "what
   * workspace toolsets should be visible right now."
   *
   * **Override contract.** When a workspace toolset's id collides with a classpath-bundled
   * toolset's id, the overlay wins in [defaultEntries]'s merge — same precedence as the
   * filesystem resource source layering in `AppTargetDiscovery` (where workspace files
   * override bundled framework files on filename collision) and the YAML-tool overlay in
   * `TrailblazeSerializationInitializer.registerWorkspaceYamlTools`. Pre-filtering the input
   * by "not-already-in-classpath" would silently discard such overrides — don't.
   *
   * Tests that exercise multiple workspaces in a single JVM should call this with
   * `emptyList()` between cases to reset the overlay.
   */
  fun registerWorkspaceToolSets(entries: List<ToolSetCatalogEntry>) {
    workspaceCatalogEntries = entries
  }

  /**
   * Returns the cached **classpath-only** catalog set, distinct from [defaultEntries] which
   * returns the merged view. Diagnostics that need to differentiate "this entry came from
   * the framework JAR" from "this entry came from the workspace" use this accessor to
   * compute the breakdown without round-tripping the overlay. Forces the classpath cache to
   * be computed if it isn't yet (lazy-init contract identical to [defaultEntries]).
   */
  fun getClasspathEntries(): List<ToolSetCatalogEntry> = defaultEntriesCache

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
    val scriptedToolNames = selected.flatMap { it.scriptedToolNames }.toSet()
    return ResolvedToolSet(
      toolClasses = toolClasses,
      yamlToolNames = yamlToolNames,
      scriptedToolNames = scriptedToolNames,
    )
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
   * Returns the scripted (`.ts` / `.js`) tool names for a single catalog entry's [id], ignoring
   * the `alwaysEnabled` auto-inclusion [resolve] performs. Symmetric with [entryYamlToolNames] for
   * the scripted-tool case — a toolset can list a scripted tool by bare name (resolved via
   * [xyz.block.trailblaze.config.ScriptedToolNameDiscoverer]); the per-session scripted-tool
   * runtime is responsible for bundling + dispatching it.
   *
   * Returns an empty set if [id] is not in the catalog.
   */
  fun entryScriptedToolNames(
    id: String,
    catalog: List<ToolSetCatalogEntry> = defaultEntries(),
  ): Set<ToolName> = catalog.firstOrNull { it.id == id }?.scriptedToolNames ?: emptySet()

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
   * Conditional resolver used by session-attached callsites (initial repo construction in
   * [TrailblazeToolRepo.withDynamicToolSets] and MCP tool registration in `TrailblazeMcpServer`)
   * that may or may not know the driver yet. Routes through [resolveForDriver] when [driverType] is
   * non-null — entries that declare incompatible `drivers:` are filtered out — and falls back to the
   * driver-agnostic [resolve] otherwise.
   *
   * Centralizes the `if (driver != null) resolveForDriver else resolve` conditional so the
   * "what the LLM sees" callsites can't drift — the same risk class that caused the report/runtime
   * mismatch fixed in `docs/internal/devlog/2026-05-22-agent-toolbox-report-driver-leak.md`.
   */
  fun resolveForSession(
    driverType: TrailblazeDriverType?,
    requestedIds: List<String>,
    catalog: List<ToolSetCatalogEntry> = defaultEntries(),
  ): ResolvedToolSet =
    if (driverType != null) {
      resolveForDriver(driverType, requestedIds, catalog)
    } else {
      resolve(requestedIds, catalog)
    }

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
   * Returns every scripted (`.ts` / `.js`) tool name from the catalog compatible with
   * [driverType]. Symmetric with [defaultYamlToolNamesForDriver] for the scripted-tool case.
   *
   * Use this whenever a caller wants the default driver-compatible scripted tools that toolsets
   * deliver, so the per-session scripted-tool runtime can ensure each is bundled + registered.
   */
  fun defaultScriptedToolNamesForDriver(
    driverType: TrailblazeDriverType,
    catalog: List<ToolSetCatalogEntry> = defaultEntries(),
  ): Set<ToolName> =
    compatibleEntries(driverType, catalog).flatMap { it.scriptedToolNames }.toSet()
}
