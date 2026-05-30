package xyz.block.trailblaze.config.project

/**
 * Walks a trailmap's full dependency closure and computes the **transitive union** of every
 * trailmap-in-closure's per-platform `tool_sets:` declarations. This is the *runtime registry*
 * layer of the three-layer trailmap-typing model — distinct from [TrailmapDependencyResolver]'s
 * closest-wins overlay, which serves the *agent toolbox* layer.
 *
 * ## Three-layer model recap
 *
 * | Layer | Composition rule | Implemented by |
 * | --- | --- | --- |
 * | **Runtime registry** — what CAN dispatch | **Transitive union** of `platforms.*.tool_sets:` across the closure | this resolver |
 * | **Agent toolbox** — what the LLM sees | Closest-wins on `defaults:` | [TrailmapDependencyResolver.resolveTarget] |
 * | **Typed surface per trailmap** — what `.ts` author can call | Trailmap's OWN platforms + transitive `exports:` | per-trailmap codegen (Phase B) |
 *
 * The runtime registry must be a transitive union because a library trailmap's internal scripted
 * tools dispatch against tools they reach through their own `platforms.<platform>.tool_sets:`
 * declarations. Those declarations are deliberately invisible to a consumer's agent toolbox
 * (closest-wins, target-controlled), but they MUST be live at runtime — otherwise an
 * `await client.callTool('web_navigate', …)` inside a library's `createMerchant` script would
 * dispatch into a void. Transitive union is the only composition rule that guarantees every
 * tool reachable inside any trailmap-in-closure stays reachable at runtime.
 *
 * ## Where trailmap platform declarations live
 *
 * - **Library trailmaps** (no `target:` block) declare per-platform configuration at the manifest
 *   top-level via [TrailblazeTrailmapManifest.platforms].
 * - **Target trailmaps** declare per-platform configuration under [TrailmapTargetConfig.platforms].
 *
 * Both sources contribute identically to the union — this resolver reads whichever applies
 * for each trailmap in the closure. Target trailmaps MUST NOT set top-level `platforms:` (enforced
 * by [TrailblazeTrailmapManifestLoader.enforceLibraryTrailmapContract]); library trailmaps MUST NOT have
 * a `target:` block, so the two sources are mutually exclusive.
 *
 * ## Walk semantics
 *
 * - **Root included.** The closure starts with [rootTrailmapId]; that trailmap's own declarations
 *   participate in the union the same way its deps' do.
 * - **Transitive.** Every reachable trailmap via `dependencies:` is visited, depth-first.
 * - **Cycle / missing-dep failures.** Both throw [TrailblazeProjectConfigException] with the
 *   chain in the message — same diagnostic shape as [TrailmapDependencyResolver]. Callers may
 *   catch and skip the consumer trailmap (atomic-per-trailmap failure model).
 * - **Diamond deps short-circuit on second visit.** A `visited` set tracks trailmaps whose
 *   subtree has already been walked; reaching a trailmap a second time (e.g. via A→D and B→D)
 *   skips the redundant subtree walk. The set semantics of the output mean omitting this
 *   short-circuit would still produce the correct union — it's a pure optimization, kept
 *   here because diamond patterns are common enough (every consumer depending on the
 *   framework `trailblaze` trailmap plus any other library that also depends on it) that the
 *   redundant walks would compound at non-trivial closures.
 *
 * ## Output shape
 *
 * Returns a [Map] keyed by platform name (`android`, `ios`, `web`, `compose`) to the set of
 * `tool_set` names declared (in any trailmap in the closure) for that platform. Platforms not
 * referenced by any trailmap in the closure are absent from the map; an empty `tool_sets:` list
 * for a platform yields an empty set (the platform key is present, no tool sets contribute).
 *
 * Callers downstream — the daemon materializing the per-target runtime registry, the per-trailmap
 * `client.d.ts` codegen in Phase B — can intersect this union against whatever supported-
 * platform set they care about. This resolver intentionally stays narrow: union only, no
 * filtering, no transformation. Mirrors how `TrailmapDependencyResolver.resolveTarget` returns
 * a fully-merged shape without taking opinions about downstream consumption.
 *
 * ## Relationship to [TrailmapDependencyResolver]
 *
 * **No coupling.** This resolver does NOT call into the closest-wins overlay path. The two
 * resolvers are sibling concerns that walk the same dep graph with different composition
 * rules — `TrailmapDependencyResolver` overlays `defaults:` field-by-field for the agent toolbox;
 * this one unions `platforms.*.tool_sets:` for the runtime registry. Changes to one MUST NOT
 * be propagated mechanically to the other; the kdoc on `TrailmapDependencyResolver.closestWinsOverlay`
 * documents *deliberate* no-list-concatenation semantics that are wrong for the runtime layer.
 */
internal object TrailmapRuntimeRegistryResolver {

  /**
   * Returns `platform → set of tool_set names` — the transitive union across [rootTrailmapId]
   * and every trailmap reachable through its `dependencies:` closure in [trailmapsById]. Each trailmap's
   * own declarations participate.
   *
   * Throws [TrailblazeProjectConfigException] when the closure references an unknown trailmap or
   * contains a cycle, with the offending chain in the message.
   */
  fun resolveRuntimeToolSets(
    rootTrailmapId: String,
    trailmapsById: Map<String, ResolvedTrailmap>,
  ): Map<String, Set<String>> {
    val accumulator = mutableMapOf<String, MutableSet<String>>()
    val visiting = mutableSetOf<String>()
    val visited = mutableSetOf<String>()
    walk(
      trailmapId = rootTrailmapId,
      trailmapsById = trailmapsById,
      visiting = visiting,
      visited = visited,
      accumulator = accumulator,
    )
    return accumulator.mapValues { (_, set) -> set.toSet() }
  }

  /**
   * DFS that contributes every visited trailmap's `platforms.*.tool_sets:` to [accumulator].
   *
   * - [visiting] is the active recursion stack used for cycle detection. Self-cycles
   *   (a trailmap listing itself in `dependencies:`) are caught when the root is re-entered.
   * - [visited] short-circuits diamond paths — visiting a trailmap twice produces the same
   *   contribution, so once is enough. This is purely an optimization: omitting it would
   *   still produce the correct union (sets dedupe naturally).
   */
  private fun walk(
    trailmapId: String,
    trailmapsById: Map<String, ResolvedTrailmap>,
    visiting: MutableSet<String>,
    visited: MutableSet<String>,
    accumulator: MutableMap<String, MutableSet<String>>,
  ) {
    if (trailmapId in visiting) {
      throw TrailblazeProjectConfigException(
        "Cycle detected in trailmap dependencies involving '$trailmapId' " +
          "(chain: ${visiting.joinToString(" -> ")} -> $trailmapId)",
      )
    }
    if (trailmapId in visited) return
    val trailmap = trailmapsById[trailmapId]
      ?: throw TrailblazeProjectConfigException(
        "Trailmap '$trailmapId' not found " +
          "(declared in 'dependencies:' chain: ${visiting.joinToString(" -> ")} -> $trailmapId)",
      )
    visiting += trailmapId
    contribute(trailmap, accumulator)
    trailmap.manifest.dependencies.forEach { childDep ->
      walk(childDep, trailmapsById, visiting, visited, accumulator)
    }
    visiting -= trailmapId
    visited += trailmapId
  }

  /**
   * Reads the per-platform configuration for [trailmap] — top-level [TrailblazeTrailmapManifest.platforms]
   * for library trailmaps, [TrailmapTargetConfig.platforms] for target trailmaps — and unions each platform's
   * `tool_sets:` list into [accumulator]. Trailmaps with no platforms map (or no tool_sets on any
   * platform) contribute nothing.
   *
   * A platform with an explicit empty list (`tool_sets: []`) still seeds the platform key in
   * [accumulator] so callers can distinguish "platform declared but empty" from "platform never
   * mentioned." Set union with `addAll(emptyList())` is a no-op on members but the surrounding
   * `getOrPut` ensures the key materializes.
   */
  private fun contribute(
    trailmap: ResolvedTrailmap,
    accumulator: MutableMap<String, MutableSet<String>>,
  ) {
    val platforms = trailmap.manifest.platforms ?: trailmap.manifest.target?.platforms ?: return
    for ((platformKey, platformConfig) in platforms) {
      val toolSets = platformConfig.toolSets ?: continue
      val bucket = accumulator.getOrPut(platformKey) { mutableSetOf() }
      bucket.addAll(toolSets)
    }
  }
}
