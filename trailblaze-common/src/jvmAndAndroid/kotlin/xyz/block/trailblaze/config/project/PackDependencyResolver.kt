package xyz.block.trailblaze.config.project

import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.PlatformConfig

/**
 * Walks a pack's dependency graph and applies closest-to-root-wins inheritance from each
 * dependency's `defaults:` map onto the consumer's `target.platforms`.
 *
 * ## Mental model
 *
 * Composition mirrors a Gradle classpath. A consumer pack declares `dependencies: [...]`;
 * resolution is transitive; each pack contributes its `defaults` (per-platform field
 * defaults) to dependers. The framework ships a single `trailblaze` pack that publishes
 * the standard per-platform `defaults:`. Built-in toolsets themselves (`core_interaction`,
 * `web_core`, etc.) are discovered globally from `trailblaze-config/toolsets/` on the
 * classpath — the `trailblaze` pack only references them by id from its `defaults:`.
 * The conventional consumer preamble is `dependencies: [trailblaze]`.
 *
 * ## Resolution rules
 *
 * - **Field-level closest-wins.** For each platform key the consumer declares, every
 *   field is resolved independently: the consumer's own non-null value wins, otherwise
 *   the dep-graph walk picks the closest depth's value. *No list concatenation.* A
 *   consumer that writes `tool_sets:` for a platform overrides the inherited list
 *   entirely. This preserves the per-platform listing as visible documentation —
 *   authors who want explicit `tool_sets:` listings in every platform keep them; authors
 *   who want a one-line target file omit the field and inherit.
 * - **Tie-break: later-declared at same depth wins.** When two contributors at the same
 *   depth supply a field for the same platform (sibling direct deps, or two transitive
 *   contributors that happen to be reached at the same depth), the later one in DFS
 *   declaration order wins. Mirrors how language module systems resolve sibling-overlay
 *   conflicts.
 * - **Platform set comes from the consumer.** Defaults only fill in fields for platforms
 *   the consumer explicitly declares. A consumer that wants e.g. `ios` with all defaults
 *   writes `ios: {}` — the empty map is the explicit signal "this platform exists, fill
 *   in everything from defaults."
 * - **Cycle / missing-dep failures throw [TrailblazeProjectConfigException].** Callers
 *   are expected to catch and skip the consumer pack with a logged error so sibling
 *   packs continue to load.
 *
 * ## Implementation: collect-then-merge by depth
 *
 * The walk records every contributing pack as a [DefaultsContribution] tagged with its
 * depth from the root and its DFS-encounter order. After the walk, contributions are
 * sorted **farthest depth first**, then by encounter order ascending, and applied with
 * field-level overlay-overwrite semantics. The last write wins, so the deepest
 * (least-close) contributions land first and the closest contributions overlay last.
 * This is the only way to honor "closest-depth wins" across branches: a naive DFS that
 * overlays each subtree as it returns lets a deep value from a later sibling overwrite
 * a shallower value from an earlier sibling — which would violate the documented
 * semantics.
 *
 * ## Scope: JVM/host-side only
 *
 * The framework `trailblaze` pack is auto-discovered via JVM classpath only — see
 * [TrailblazePackManifestLoader.discoverAndLoadFromClasspath]. On-device builds (the
 * Android on-device MCP server) don't run that discovery, so a consumer pack with
 * `dependencies: [trailblaze]` will fail dep resolution on-device, get caught by the
 * loader's atomic-per-pack handler, and be silently skipped. This is intentional today
 * (pack discovery is a JVM-host CLI concern); on-device pack discovery is out of scope
 * until there's a real requirement for it.
 *
 * ## Parity contract with build-logic
 *
 * `build-logic/src/main/kotlin/TrailblazeBundledConfigTasks.kt`'s `PackTargetGenerator`
 * (specifically [`mergeInheritedDefaults`](../../../../../../../../build-logic/src/main/kotlin/TrailblazeBundledConfigTasks.kt))
 * implements the SAME closest-wins semantics described above against pack manifests
 * walked at Gradle build time. It exists as a sibling implementation because build-logic
 * is a Gradle includedBuild that intentionally avoids depending on `:trailblaze-common`
 * (whose koog/MCP/jackson graph would inflate every Gradle build's classpath). The two
 * implementations MUST stay in lockstep — any change to the rules documented above
 * (closest-wins, no list concat, tie-break, cycle / missing-dep behavior, scope of
 * defaults to consumer-declared platforms) must be mirrored in both files. Both sides
 * carry a "behavioral contract" test (`PackDependencyResolverTest` here,
 * `PackTargetGeneratorTest` in build-logic) that pins the same set of cases — if either
 * test gets modified, the other should be examined too.
 */
internal object PackDependencyResolver {

  /**
   * Returns [ownTarget] with `platforms` field-level enriched by closest-wins defaults
   * walked from [ownDependencies] through [packsById]. Throws when [ownDependencies]
   * references an unknown pack or the graph contains a cycle.
   */
  fun resolveTarget(
    ownTarget: AppTargetYamlConfig,
    ownDependencies: List<String>,
    packsById: Map<String, TrailblazeProjectConfigLoader.ResolvedPack>,
    rootPackId: String,
  ): AppTargetYamlConfig {
    val ownPlatforms = ownTarget.platforms
    if (ownDependencies.isEmpty() || ownPlatforms.isNullOrEmpty()) {
      return ownTarget
    }
    val contributions = mutableListOf<DefaultsContribution>()
    val visiting = mutableSetOf(rootPackId)
    ownDependencies.forEach { depId ->
      walk(
        depId = depId,
        depth = 1,
        packsById = packsById,
        visiting = visiting,
        contributions = contributions,
      )
    }
    if (contributions.isEmpty()) return ownTarget
    val collectedDefaults = mergeByDepth(contributions)
    if (collectedDefaults.isEmpty()) return ownTarget
    val mergedPlatforms = ownPlatforms.mapValues { (key, ownPlatform) ->
      val defaultPlatform = collectedDefaults[key] ?: return@mapValues ownPlatform
      // Own values win on every field they set. Defaults fill in nulls only.
      closestWinsOverlay(base = defaultPlatform, overlay = ownPlatform)
    }
    return ownTarget.copy(platforms = mergedPlatforms)
  }

  /**
   * DFS walk that records each pack's `defaults:` (when present) as a
   * [DefaultsContribution] tagged with its depth from the root and its encounter index.
   *
   * [visiting] is the active recursion stack used for cycle detection. The caller seeds
   * it with the root pack id so a self-referential `dependencies: [<root>]` declaration
   * is caught.
   *
   * **Recording order is load-bearing.** [DefaultsContribution.encounterOrder] is read
   * directly from `contributions.size` at the moment of recording — i.e., it equals the
   * sequence in which DFS encounters each contributing pack. The merge step
   * ([mergeByDepth]) breaks same-depth ties by ascending encounter order so
   * later-declared siblings win, which is the documented semantics. Any future refactor
   * that batches the walk, parallelizes it, or otherwise reorders insertions into
   * [contributions] MUST preserve this invariant — either by threading an explicit
   * counter or by sorting on a different stable key. Don't swap the recursion shape
   * without re-establishing how the tie-break is determined.
   *
   * Diamond deps (D reached via both A→D and B→D) cause D's subtree to be walked twice.
   * That's intentional: there's no contribution cache, so the algorithm stays trivially
   * correct under multi-path graphs. At current pack counts (single-digit depth, low
   * double-digit breadth) the redundant work is negligible. If a benchmark ever shows
   * it matters, memoization is a localized change here.
   */
  private fun walk(
    depId: String,
    depth: Int,
    packsById: Map<String, TrailblazeProjectConfigLoader.ResolvedPack>,
    visiting: MutableSet<String>,
    contributions: MutableList<DefaultsContribution>,
  ) {
    if (depId in visiting) {
      throw TrailblazeProjectConfigException(
        "Cycle detected in pack dependencies involving '$depId' " +
          "(chain: ${visiting.joinToString(" -> ")} -> $depId)",
      )
    }
    val depPack = packsById[depId]
      ?: throw TrailblazeProjectConfigException(
        "Pack '$depId' not found " +
          "(declared in 'dependencies:' chain: ${visiting.joinToString(" -> ")} -> $depId)",
      )
    visiting += depId
    depPack.manifest.defaults?.let { defaults ->
      // contributions.size IS the encounter sequence — see kdoc above on why this
      // recording order matters for the same-depth tie-break.
      contributions += DefaultsContribution(
        depId = depId,
        depth = depth,
        encounterOrder = contributions.size,
        defaults = defaults,
      )
    }
    depPack.manifest.dependencies.forEach { childDep ->
      walk(childDep, depth + 1, packsById, visiting, contributions)
    }
    visiting -= depId
  }

  /**
   * Sorts contributions farthest-depth-first (so the closest contributions are applied
   * last and win), with ties broken by ascending encounter order (so later-declared
   * contributions at the same depth are applied last and win at that level). Then folds
   * them into a single platforms map via [overlayInto].
   */
  private fun mergeByDepth(contributions: List<DefaultsContribution>): Map<String, PlatformConfig> {
    val sorted = contributions.sortedWith(
      compareByDescending<DefaultsContribution> { it.depth }.thenBy { it.encounterOrder },
    )
    val accumulated = mutableMapOf<String, PlatformConfig>()
    sorted.forEach { contribution -> overlayInto(accumulated, contribution.defaults) }
    return accumulated
  }

  private fun overlayInto(
    target: MutableMap<String, PlatformConfig>,
    overlay: Map<String, PlatformConfig>,
  ) {
    for ((key, overlayConfig) in overlay) {
      val existing = target[key]
      target[key] = if (existing == null) overlayConfig else closestWinsOverlay(existing, overlayConfig)
    }
  }

  /**
   * Field-level "overlay wins if non-null, else base." For every nullable field on
   * [PlatformConfig] the overlay's value wins when set, otherwise the base's value
   * carries through. No list concatenation — the overlay's `tool_sets:` (or any list
   * field) replaces the base's.
   */
  private fun closestWinsOverlay(
    base: PlatformConfig,
    overlay: PlatformConfig,
  ): PlatformConfig = PlatformConfig(
    appIds = overlay.appIds ?: base.appIds,
    toolSets = overlay.toolSets ?: base.toolSets,
    tools = overlay.tools ?: base.tools,
    excludedTools = overlay.excludedTools ?: base.excludedTools,
    drivers = overlay.drivers ?: base.drivers,
    baseUrl = overlay.baseUrl ?: base.baseUrl,
    minBuildVersion = overlay.minBuildVersion ?: base.minBuildVersion,
  )

  /**
   * One pack's contribution to the resolved defaults pool, paired with the [depth] from
   * the consuming pack and the global DFS [encounterOrder]. The merge step uses these
   * to apply contributions in farthest→closest order so closest-depth wins regardless
   * of which subtree the value came from.
   */
  private data class DefaultsContribution(
    val depId: String,
    val depth: Int,
    val encounterOrder: Int,
    val defaults: Map<String, PlatformConfig>,
  )
}
