package xyz.block.trailblaze.graph

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Locks down [WaypointGraphBuilder.applyScope] — the filter that backs the CLI's
 * `--target` / `--platform` flags and that the deployed Blockcell maps depend on for
 * correct per-(target, platform) scoping.
 *
 * The end-to-end script run validates the function works once empirically, but a
 * regression in the cascade-to-edges logic (e.g. flipping a shortcut endpoint check
 * from `from in keptIds && to in keptIds` to `from in keptIds || to in keptIds`, or
 * dropping the trailhead `to` filter entirely) would silently produce
 * structurally-wrong maps and only surface during the next manual republish. This
 * suite catches those mutations.
 */
class WaypointGraphBuilderTest {

  // ---------- target filter ----------

  @Test
  fun targetOnly_keepsMatchingPrefixDropsRest() {
    val (kept, shortcuts, trailheads) = WaypointGraphBuilder.applyScope(
      waypoints = listOf(
        node("alpha/home", platform = "android"),
        node("alpha/details", platform = "android"),
        node("beta/home", platform = "android"),
      ),
      shortcuts = emptyList(),
      trailheads = emptyList(),
      targetFilter = "alpha",
      platformFilter = null,
    )

    assertEquals(listOf("alpha/home", "alpha/details"), kept.map { it.id })
    assertTrue(shortcuts.isEmpty())
    assertTrue(trailheads.isEmpty())
  }

  // ---------- platform filter ----------

  @Test
  fun platformOnly_keepsMatchingPlatformDropsRest() {
    val (kept, _, _) = WaypointGraphBuilder.applyScope(
      waypoints = listOf(
        node("alpha/home", platform = "android"),
        node("alpha/profile", platform = "ios"),
        node("alpha/dashboard", platform = "web"),
        node("alpha/orphan", platform = null),
      ),
      shortcuts = emptyList(),
      trailheads = emptyList(),
      targetFilter = null,
      platformFilter = "android",
    )

    assertEquals(listOf("alpha/home"), kept.map { it.id })
  }

  // ---------- both filters ----------

  @Test
  fun targetAndPlatform_intersectsBothConstraints() {
    val (kept, _, _) = WaypointGraphBuilder.applyScope(
      waypoints = listOf(
        node("alpha/home", platform = "android"),
        node("alpha/profile", platform = "ios"),
        node("beta/home", platform = "android"),
      ),
      shortcuts = emptyList(),
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
    val edges = listOf(shortcut("alpha-to-beta", from = "alpha/home", to = "beta/home"))
    val trailheads = listOf(trailhead("entry", to = "alpha/home"))

    val (keptNodes, keptShortcuts, keptTrailheads) = WaypointGraphBuilder.applyScope(
      waypoints = nodes,
      shortcuts = edges,
      trailheads = trailheads,
      targetFilter = null,
      platformFilter = null,
    )

    // Reference-equality: the function must short-circuit when no scope applies, not
    // re-allocate copies of the input lists. The CLI's "no flags" path runs through
    // here on every invocation.
    assertTrue(keptNodes === nodes)
    assertTrue(keptShortcuts === edges)
    assertTrue(keptTrailheads === trailheads)
  }

  // ---------- shortcut cascade ----------

  @Test
  fun shortcutWhoseToIsOutOfScope_isDropped() {
    val (_, keptShortcuts, _) = WaypointGraphBuilder.applyScope(
      waypoints = listOf(
        node("alpha/home", platform = "android"),
        node("beta/home", platform = "android"),
      ),
      shortcuts = listOf(
        // Both endpoints in scope — must survive.
        shortcut("alpha-internal", from = "alpha/home", to = "alpha/home"),
        // `from` survives but `to` is filtered out — must be dropped, not kept as a
        // dangling edge pointing at a node that's no longer in the graph.
        shortcut("alpha-to-beta", from = "alpha/home", to = "beta/home"),
      ),
      trailheads = emptyList(),
      targetFilter = "alpha",
      platformFilter = null,
    )

    assertEquals(listOf("alpha-internal"), keptShortcuts.map { it.id })
  }

  @Test
  fun shortcutWhoseFromIsOutOfScope_isDropped() {
    val (_, keptShortcuts, _) = WaypointGraphBuilder.applyScope(
      waypoints = listOf(
        node("alpha/home", platform = "android"),
        node("beta/home", platform = "android"),
      ),
      shortcuts = listOf(
        shortcut("beta-to-alpha", from = "beta/home", to = "alpha/home"),
      ),
      trailheads = emptyList(),
      targetFilter = "alpha",
      platformFilter = null,
    )

    assertTrue(keptShortcuts.isEmpty(), "Inbound edge from out-of-scope target must be dropped")
  }

  // ---------- trailhead cascade ----------

  @Test
  fun trailheadWhoseToIsOutOfScope_isDropped() {
    val (_, _, keptTrailheads) = WaypointGraphBuilder.applyScope(
      waypoints = listOf(
        node("alpha/home", platform = "android"),
        node("beta/home", platform = "android"),
      ),
      shortcuts = emptyList(),
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
    val (kept, _, _) = WaypointGraphBuilder.applyScope(
      waypoints = listOf(
        node("lonely", platform = "android"),
        node("alpha/home", platform = "android"),
      ),
      shortcuts = emptyList(),
      trailheads = emptyList(),
      targetFilter = "lonely",
      platformFilter = null,
    )

    assertEquals(listOf("lonely"), kept.map { it.id })
  }

  // ---------- helpers ----------

  private fun node(id: String, platform: String?): WaypointGraphNode = WaypointGraphNode(
    id = id,
    description = null,
    screenshotDataUri = null,
    sourceLabel = null,
    platform = platform,
  )

  private fun shortcut(id: String, from: String, to: String): WaypointGraphShortcut =
    WaypointGraphShortcut(
      id = id,
      description = null,
      from = from,
      to = to,
      variant = null,
    )

  private fun trailhead(id: String, to: String): WaypointGraphTrailhead =
    WaypointGraphTrailhead(id = id, description = null, to = to)
}
