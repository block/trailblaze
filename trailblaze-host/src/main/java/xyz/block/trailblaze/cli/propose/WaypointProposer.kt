package xyz.block.trailblaze.cli.propose

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver.ResolveResult
import xyz.block.trailblaze.api.waypoint.WaypointCondition
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.api.waypoint.WaypointVariant
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Pure-synthesis core for `trailblaze waypoint propose`. Takes a cluster fingerprint
 * (one line from `unmatched-clusters.jsonl`) plus the cluster's representative screen
 * state and returns either a draft [WaypointDefinition] or a typed skip reason.
 *
 * Deterministic v1 — picks the top two resource IDs (or top two key_texts on iOS where
 * resourceId is sparse) that the matcher confirms are actually present in the
 * representative screen. No LLM. See
 * `docs/internal/devlog/2026-05-19-waypoint-pack-detection.md` for the design and the
 * v2 LLM-synthesis path.
 *
 * No I/O — the CLI handles JSONL parsing, screen-state loading, and sidecar emission.
 */
object WaypointProposer {

  /**
   * Shape produced by `scripts/buildkite/waypoints_aggregate.sh` — one cluster line in
   * `unmatched-clusters.jsonl`. See devlog 2026-05-18-trailblaze-waypoints-pipeline
   * for the producer side.
   */
  @Serializable
  data class ClusterFingerprint(
    val count: Int,
    @kotlinx.serialization.SerialName("key_texts") val keyTexts: List<String>,
    @kotlinx.serialization.SerialName("example_log") val exampleLog: String,
    @kotlinx.serialization.SerialName("example_session") val exampleSession: String,
    @kotlinx.serialization.SerialName("example_resource_ids") val exampleResourceIds: List<String>,
  )

  /**
   * Result of a single-cluster synthesis attempt.
   */
  sealed class Synthesis {
    /** Successful draft. The CLI runs the sibling-overlap + cross-bleed gates next. */
    data class Ok(
      val definition: WaypointDefinition,
      val proposalKey: String,
      val rationale: String,
    ) : Synthesis()

    /**
     * Synthesizer declined to produce a draft. Captured in `rejected.json` so the
     * LLM-v2 path can pick the cluster up later.
     */
    data class Skipped(val reason: String, val cluster: ClusterFingerprint) : Synthesis()
  }

  /** Parse one JSONL line into a [ClusterFingerprint]. */
  fun parseCluster(line: String): ClusterFingerprint =
    JSON.decodeFromString(ClusterFingerprint.serializer(), line.trim())

  /**
   * Synthesize a draft waypoint for [cluster] anchored at the [exampleScreen] (loaded
   * from `cluster.exampleLog`). [targetId] becomes the trailmap-namespace prefix on the
   * generated waypoint id. Returns either a valid draft or a typed skip reason.
   *
   * The algorithm:
   *  1. Walk `cluster.exampleResourceIds` in frequency order; for each id, build the
   *     platform-appropriate accessibility selector and ask the resolver whether it
   *     hits the example screen. Take the first two that do.
   *  2. If fewer than one resource ID matched, fall back to `cluster.keyTexts[:2]` as
   *     text-regex selectors (escaped + anchored). Same matcher check.
   *  3. If neither path yields at least one selector, return [Synthesis.Skipped]. The
   *     cluster is *unsynthesizable deterministically* and is forwarded to the
   *     LLM-v2 path via `rejected.json`.
   */
  fun synthesize(
    cluster: ClusterFingerprint,
    exampleScreen: ScreenState,
    targetId: String,
  ): Synthesis {
    val platform = exampleScreen.trailblazeDevicePlatform
    val rootNode = exampleScreen.trailblazeNodeTree
      ?: return Synthesis.Skipped(
        reason = "example screen has no trailblazeNodeTree (matcher would skip)",
        cluster = cluster,
      )

    // Try resource IDs first — they're the most stable signal across app versions.
    // `cluster.exampleResourceIds` can contain duplicates (the aggregator's `top_resource_ids[:5]`
    // doesn't dedupe), so `.distinct()` first — otherwise a heavily-repeated id would burn
    // both `take(2)` slots and the second required entry would be redundant.
    val matchingIds = cluster.exampleResourceIds
      .distinct()
      .map { resourceIdSelector(it, platform) }
      .filter { entry -> entry.selector?.let { selectorMatchesAtLeastOnce(rootNode, it) } == true }
      .take(2)

    val required: List<WaypointCondition> = if (matchingIds.isNotEmpty()) {
      matchingIds
    } else {
      // Fallback path: pick text-regex selectors from key_texts. iOS hierarchies often
      // lack stable resource IDs, so this is the bread-and-butter path there.
      // Same dedupe guard: top key_texts can repeat across the cluster.
      cluster.keyTexts.distinct().take(2)
        .map { textSelector(it, platform) }
        .filter { entry -> entry.selector?.let { selectorMatchesAtLeastOnce(rootNode, it) } == true }
    }

    if (required.isEmpty()) {
      return Synthesis.Skipped(
        reason = "no candidate selector (resource id or key text) matched the example screen",
        cluster = cluster,
      )
    }

    val id = generateId(targetId, cluster.keyTexts, clusterDisambiguator(cluster))
    val description = buildDescription(cluster)
    return Synthesis.Ok(
      definition = WaypointDefinition(
        id = id,
        description = description,
        byClassifier = mapOf(
          platform.asTrailblazeDeviceClassifier().classifier to WaypointVariant(required = required),
        ),
      ),
      proposalKey = proposalKey(cluster),
      rationale = "Cluster of ${cluster.count} unmatched screen(s) sharing top-texts " +
        "${cluster.keyTexts}. Draft uses ${required.size} selector(s) confirmed present " +
        "in the representative session step (${cluster.exampleSession}). The `auto-` id " +
        "prefix flags this as machine-generated — reviewers should rename before merge.",
    )
  }

  /**
   * Stable cross-week dedupe key. Built from the cluster's sorted `key_texts` so two
   * runs that surface the same cluster from the same session set produce identical keys —
   * resource-id rotation or a future slug-generation change doesn't break dedupe.
   */
  fun proposalKey(cluster: ClusterFingerprint): String =
    "new|" + cluster.keyTexts.sorted().joinToString(",")

  /**
   * Generate a trailmap-scoped waypoint id with the `auto-` prefix that flags machine
   * authorship. Slug derived from the cluster's sorted key_texts; truncated to keep
   * filenames sane.
   *
   * If [disambiguator] is non-null, the function appends `-<disambiguator>` to the slug
   * when the raw slug would have been truncated (which can collide on identical 40-char
   * prefixes) OR when the key_texts produced an empty slug (so two empty-key_texts
   * clusters don't both flatten to `auto-untitled`). The synthesizer feeds in a stable
   * hash of `exampleResourceIds` for this purpose — keeps the suffix deterministic
   * across runs of the same cluster, but separates distinct clusters that would
   * otherwise collide. Pass null when callers don't have a disambiguator handy (tests).
   */
  fun generateId(
    targetId: String,
    keyTexts: List<String>,
    disambiguator: String? = null,
  ): String {
    val raw = keyTexts.sorted()
      .joinToString("-") { it.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') }
      .replace(Regex("-+"), "-")
      .trim('-')
    val truncated = raw.take(40)
    val needsSuffix = disambiguator != null && (raw.length > 40 || truncated.isEmpty())
    val base = truncated.ifEmpty { "untitled" }
    val slug = if (needsSuffix) "$base-$disambiguator" else base
    return "$targetId/auto-$slug"
  }

  /**
   * Stable short disambiguator derived from a cluster's `exampleResourceIds`. Same
   * cluster → same suffix; clusters with different resource-id fingerprints get
   * different suffixes even if their key_texts slugs collide after truncation.
   */
  internal fun clusterDisambiguator(cluster: ClusterFingerprint): String =
    cluster.exampleResourceIds.joinToString("|").hashCode().toUInt().toString(16).take(4)

  private fun buildDescription(cluster: ClusterFingerprint): String {
    val texts = cluster.keyTexts.joinToString(" / ")
    return "AUTO-PROPOSED. Cluster of ${cluster.count} unmatched session(s); top texts: $texts. " +
      "Reviewer: rename id, refine selectors, and add forbidden entries as needed before merge."
  }

  /**
   * Builds a resource-id selector. v1 emits `AndroidAccessibility.resourceIdRegex`
   * unconditionally — iOS clusters fall through (the matcher returns NoMatch when the
   * tree is iOS-shaped) and ultimately get a `Skipped` verdict, which is the correct
   * v1 behavior. iOS support is captured in the v2 LLM-synthesis follow-up.
   */
  private fun resourceIdSelector(
    resourceId: String,
    @Suppress("UNUSED_PARAMETER") platform: TrailblazeDevicePlatform,
  ): WaypointCondition {
    val regex = "^${Regex.escape(resourceId)}$"
    return WaypointCondition(
      selector = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(resourceIdRegex = regex),
      ),
      description = "auto: resource id $resourceId",
    )
  }

  /**
   * Builds a text-regex selector. Anchored with `^...$` to avoid the loose-match trap
   * where "Total" also matches "Subtotal".
   */
  private fun textSelector(
    text: String,
    @Suppress("UNUSED_PARAMETER") platform: TrailblazeDevicePlatform,
  ): WaypointCondition {
    val regex = "^${Regex.escape(text)}$"
    return WaypointCondition(
      selector = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = regex),
      ),
      description = "auto: text \"$text\"",
    )
  }

  private fun selectorMatchesAtLeastOnce(
    root: xyz.block.trailblaze.api.TrailblazeNode,
    selector: TrailblazeNodeSelector,
  ): Boolean = when (TrailblazeNodeSelectorResolver.resolve(root, selector)) {
    is ResolveResult.SingleMatch -> true
    is ResolveResult.MultipleMatches -> true
    is ResolveResult.NoMatch -> false
  }

  internal val JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }
}
