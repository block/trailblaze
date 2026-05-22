package xyz.block.trailblaze.config.project

/**
 * Walks a pack's full dependency closure and computes the **transitive union** of every
 * pack-in-closure's per-platform `tool_sets:` declarations. This is the *runtime registry*
 * layer of the three-layer pack-typing model — distinct from [PackDependencyResolver]'s
 * closest-wins overlay, which serves the *agent toolbox* layer.
 *
 * ## Three-layer model recap
 *
 * | Layer | Composition rule | Implemented by |
 * | --- | --- | --- |
 * | **Runtime registry** — what CAN dispatch | **Transitive union** of `platforms.*.tool_sets:` across the closure | this resolver |
 * | **Agent toolbox** — what the LLM sees | Closest-wins on `defaults:` | [PackDependencyResolver.resolveTarget] |
 * | **Typed surface per pack** — what `.ts` author can call | Pack's OWN platforms + transitive `exports:` | per-pack codegen (Phase B) |
 *
 * The runtime registry must be a transitive union because a library pack's internal scripted
 * tools dispatch against tools they reach through their own `platforms.<platform>.tool_sets:`
 * declarations. Those declarations are deliberately invisible to a consumer's agent toolbox
 * (closest-wins, target-controlled), but they MUST be live at runtime — otherwise an
 * `await client.callTool('web_navigate', …)` inside a library's `createMerchant` script would
 * dispatch into a void. Transitive union is the only composition rule that guarantees every
 * tool reachable inside any pack-in-closure stays reachable at runtime.
 *
 * ## Where pack platform declarations live
 *
 * - **Library packs** (no `target:` block) declare per-platform configuration at the manifest
 *   top-level via [TrailblazePackManifest.platforms].
 * - **Target packs** declare per-platform configuration under [PackTargetConfig.platforms].
 *
 * Both sources contribute identically to the union — this resolver reads whichever applies
 * for each pack in the closure. Target packs MUST NOT set top-level `platforms:` (enforced
 * by [TrailblazePackManifestLoader.enforceLibraryPackContract]); library packs MUST NOT have
 * a `target:` block, so the two sources are mutually exclusive.
 *
 * ## Walk semantics
 *
 * - **Root included.** The closure starts with [rootPackId]; that pack's own declarations
 *   participate in the union the same way its deps' do.
 * - **Transitive.** Every reachable pack via `dependencies:` is visited, depth-first.
 * - **Cycle / missing-dep failures.** Both throw [TrailblazeProjectConfigException] with the
 *   chain in the message — same diagnostic shape as [PackDependencyResolver]. Callers may
 *   catch and skip the consumer pack (atomic-per-pack failure model).
 * - **Diamond deps short-circuit on second visit.** A `visited` set tracks packs whose
 *   subtree has already been walked; reaching a pack a second time (e.g. via A→D and B→D)
 *   skips the redundant subtree walk. The set semantics of the output mean omitting this
 *   short-circuit would still produce the correct union — it's a pure optimization, kept
 *   here because diamond patterns are common enough (every consumer depending on the
 *   framework `trailblaze` pack plus any other library that also depends on it) that the
 *   redundant walks would compound at non-trivial closures.
 *
 * ## Output shape
 *
 * Returns a [Map] keyed by platform name (`android`, `ios`, `web`, `compose`) to the set of
 * `tool_set` names declared (in any pack in the closure) for that platform. Platforms not
 * referenced by any pack in the closure are absent from the map; an empty `tool_sets:` list
 * for a platform yields an empty set (the platform key is present, no tool sets contribute).
 *
 * Callers downstream — the daemon materializing the per-target runtime registry, the per-pack
 * `client.d.ts` codegen in Phase B — can intersect this union against whatever supported-
 * platform set they care about. This resolver intentionally stays narrow: union only, no
 * filtering, no transformation. Mirrors how `PackDependencyResolver.resolveTarget` returns
 * a fully-merged shape without taking opinions about downstream consumption.
 *
 * ## Relationship to [PackDependencyResolver]
 *
 * **No coupling.** This resolver does NOT call into the closest-wins overlay path. The two
 * resolvers are sibling concerns that walk the same dep graph with different composition
 * rules — `PackDependencyResolver` overlays `defaults:` field-by-field for the agent toolbox;
 * this one unions `platforms.*.tool_sets:` for the runtime registry. Changes to one MUST NOT
 * be propagated mechanically to the other; the kdoc on `PackDependencyResolver.closestWinsOverlay`
 * documents *deliberate* no-list-concatenation semantics that are wrong for the runtime layer.
 */
internal object PackRuntimeRegistryResolver {

  /**
   * Returns `platform → set of tool_set names` — the transitive union across [rootPackId]
   * and every pack reachable through its `dependencies:` closure in [packsById]. Each pack's
   * own declarations participate.
   *
   * Throws [TrailblazeProjectConfigException] when the closure references an unknown pack or
   * contains a cycle, with the offending chain in the message.
   */
  fun resolveRuntimeToolSets(
    rootPackId: String,
    packsById: Map<String, ResolvedPack>,
  ): Map<String, Set<String>> {
    val accumulator = mutableMapOf<String, MutableSet<String>>()
    val visiting = mutableSetOf<String>()
    val visited = mutableSetOf<String>()
    walk(
      packId = rootPackId,
      packsById = packsById,
      visiting = visiting,
      visited = visited,
      accumulator = accumulator,
    )
    return accumulator.mapValues { (_, set) -> set.toSet() }
  }

  /**
   * DFS that contributes every visited pack's `platforms.*.tool_sets:` to [accumulator].
   *
   * - [visiting] is the active recursion stack used for cycle detection. Self-cycles
   *   (a pack listing itself in `dependencies:`) are caught when the root is re-entered.
   * - [visited] short-circuits diamond paths — visiting a pack twice produces the same
   *   contribution, so once is enough. This is purely an optimization: omitting it would
   *   still produce the correct union (sets dedupe naturally).
   */
  private fun walk(
    packId: String,
    packsById: Map<String, ResolvedPack>,
    visiting: MutableSet<String>,
    visited: MutableSet<String>,
    accumulator: MutableMap<String, MutableSet<String>>,
  ) {
    if (packId in visiting) {
      throw TrailblazeProjectConfigException(
        "Cycle detected in pack dependencies involving '$packId' " +
          "(chain: ${visiting.joinToString(" -> ")} -> $packId)",
      )
    }
    if (packId in visited) return
    val pack = packsById[packId]
      ?: throw TrailblazeProjectConfigException(
        "Pack '$packId' not found " +
          "(declared in 'dependencies:' chain: ${visiting.joinToString(" -> ")} -> $packId)",
      )
    visiting += packId
    contribute(pack, accumulator)
    pack.manifest.dependencies.forEach { childDep ->
      walk(childDep, packsById, visiting, visited, accumulator)
    }
    visiting -= packId
    visited += packId
  }

  /**
   * Reads the per-platform configuration for [pack] — top-level [TrailblazePackManifest.platforms]
   * for library packs, [PackTargetConfig.platforms] for target packs — and unions each platform's
   * `tool_sets:` list into [accumulator]. Packs with no platforms map (or no tool_sets on any
   * platform) contribute nothing.
   *
   * A platform with an explicit empty list (`tool_sets: []`) still seeds the platform key in
   * [accumulator] so callers can distinguish "platform declared but empty" from "platform never
   * mentioned." Set union with `addAll(emptyList())` is a no-op on members but the surrounding
   * `getOrPut` ensures the key materializes.
   */
  private fun contribute(
    pack: ResolvedPack,
    accumulator: MutableMap<String, MutableSet<String>>,
  ) {
    val platforms = pack.manifest.platforms ?: pack.manifest.target?.platforms ?: return
    for ((platformKey, platformConfig) in platforms) {
      val toolSets = platformConfig.toolSets ?: continue
      val bucket = accumulator.getOrPut(platformKey) { mutableSetOf() }
      bucket.addAll(toolSets)
    }
  }
}
