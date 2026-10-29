package xyz.block.trailblaze.graph

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Locks down [WaypointGraphBuilder.applyScope] — the filter that backs the CLI's
 * `--target` / `--platform` flags and that the published waypoint-graph map sites
 * depend on for correct per-(target, platform) scoping.
 *
 * The end-to-end script run validates the function works once empirically, but a
 * regression in the cascade-to-trailheads logic (e.g. dropping the trailhead `to`
 * filter entirely) would silently produce structurally-wrong maps and only surface
 * during the next manual republish. This suite catches those mutations.
 */
class WaypointGraphBuilderTest {

  // ---------- target filter ----------

  @Test
  fun targetOnly_keepsMatchingPrefixDropsRest() {
    val (kept, trailheads) = WaypointGraphBuilder.applyScope(
      waypoints = listOf(
        node("alpha/home", platform = "android"),
        node("alpha/details", platform = "android"),
        node("beta/home", platform = "android"),
      ),
      trailheads = emptyList(),
      targetFilter = "alpha",
      platformFilter = null,
    )

    assertEquals(listOf("alpha/home", "alpha/details"), kept.map { it.id })
    assertTrue(trailheads.isEmpty())
  }

  // ---------- platform filter ----------

  @Test
  fun platformOnly_keepsMatchingPlatformDropsRest() {
    val (kept, _) = WaypointGraphBuilder.applyScope(
      waypoints = listOf(
        node("alpha/home", platform = "android"),
        node("alpha/profile", platform = "ios"),
        node("alpha/dashboard", platform = "web"),
        node("alpha/orphan", platform = null),
      ),
      trailheads = emptyList(),
      targetFilter = null,
      platformFilter = "android",
    )

    assertEquals(listOf("alpha/home"), kept.map { it.id })
  }

  @Test
  fun platformFilter_keepsCrossPlatformNodeThatDeclaresTheClassifier() {
    // Regression: a v2 waypoint at a platform-neutral path (`waypoints/<name>.waypoint.yaml`)
    // has path-derived platform=null but declares android+ios classifier blocks. It must survive
    // BOTH the android and ios scope (and be dropped from web) — previously platform=null filtered
    // it out of every platform-scoped view even though it declares matching classifier blocks.
    val fused = node("alpha/home", platform = null, platforms = listOf("android", "ios"))
    val webOnly = node("alpha/marketing", platform = "web", platforms = listOf("web"))
    fun scopeTo(p: String) = WaypointGraphBuilder.applyScope(
      waypoints = listOf(fused, webOnly),
      trailheads = emptyList(),
      targetFilter = null,
      platformFilter = p,
    ).first.map { it.id }

    assertEquals(listOf("alpha/home"), scopeTo("android"))
    assertEquals(listOf("alpha/home"), scopeTo("ios"))
    assertEquals(listOf("alpha/marketing"), scopeTo("web"))
  }

  @Test
  fun platformFilter_fallsBackToPathDerivedPlatformWhenNoClassifiersDeclared() {
    // The else branch of applyScope: a node with NO declared classifier blocks (platforms empty)
    // falls back to the path-derived single `platform`. A legacy path-scoped node matches only its
    // own platform; an id-only node (platforms empty AND platform null) matches no platform scope.
    val legacy = node("alpha/legacy", platform = "android", platforms = emptyList())
    val idOnly = node("alpha/id-only", platform = null, platforms = emptyList())
    fun scopeTo(p: String) = WaypointGraphBuilder.applyScope(
      waypoints = listOf(legacy, idOnly),
      trailheads = emptyList(),
      targetFilter = null,
      platformFilter = p,
    ).first.map { it.id }

    assertEquals(listOf("alpha/legacy"), scopeTo("android"))
    assertEquals(emptyList<String>(), scopeTo("ios"))
  }

  // ---------- both filters ----------

  @Test
  fun targetAndPlatform_intersectsBothConstraints() {
    val (kept, _) = WaypointGraphBuilder.applyScope(
      waypoints = listOf(
        node("alpha/home", platform = "android"),
        node("alpha/profile", platform = "ios"),
        node("beta/home", platform = "android"),
      ),
      trailheads = emptyList(),
      targetFilter = "alpha",
      platformFilter = "android",
    )

    assertEquals(listOf("alpha/home"), kept.map { it.id })
  }

  // ---------- passthrough ----------

  @Test
  fun noFilters_returnsInputUnchanged() {
    val nodes = listOf(node("alpha/home", platform = "android"), node("beta/home", platform = "ios"))
    val trailheads = listOf(trailhead("entry", to = "alpha/home"))

    val (keptNodes, keptTrailheads) = WaypointGraphBuilder.applyScope(
      waypoints = nodes,
      trailheads = trailheads,
      targetFilter = null,
      platformFilter = null,
    )

    // Reference-equality: the function must short-circuit when no scope applies, not
    // re-allocate copies of the input lists. The CLI's "no flags" path runs through
    // here on every invocation.
    assertTrue(keptNodes === nodes)
    assertTrue(keptTrailheads === trailheads)
  }

  // ---------- trailhead cascade ----------

  @Test
  fun trailheadWhoseToIsOutOfScope_isDropped() {
    val (_, keptTrailheads) = WaypointGraphBuilder.applyScope(
      waypoints = listOf(
        node("alpha/home", platform = "android"),
        node("beta/home", platform = "android"),
      ),
      trailheads = listOf(
        trailhead("alpha-entry", to = "alpha/home"),
        trailhead("beta-entry", to = "beta/home"),
      ),
      targetFilter = "alpha",
      platformFilter = null,
    )

    assertEquals(listOf("alpha-entry"), keptTrailheads.map { it.id })
  }

  // ---------- edge case: id with no slash ----------

  @Test
  fun idWithNoSlash_treatsWholeIdAsTarget() {
    // Mirrors the front-end's `wp.id.split('/')[0]` behavior: a malformed id without
    // a slash IS its own target, so `--target=lonely` matches it.
    val (kept, _) = WaypointGraphBuilder.applyScope(
      waypoints = listOf(
        node("lonely", platform = "android"),
        node("alpha/home", platform = "android"),
      ),
      trailheads = emptyList(),
      targetFilter = "lonely",
      platformFilter = null,
    )

    assertEquals(listOf("lonely"), kept.map { it.id })
  }

  // ---------- helpers ----------

  private fun node(id: String, platform: String?, platforms: List<String> = emptyList()): WaypointGraphNode =
    WaypointGraphNode(
      id = id,
      description = null,
      screenshotDataUri = null,
      sourceLabel = null,
      platform = platform,
      platforms = platforms,
    )

  private fun trailhead(id: String, to: String): WaypointGraphTrailhead =
    WaypointGraphTrailhead(id = id, description = null, to = to)
}
