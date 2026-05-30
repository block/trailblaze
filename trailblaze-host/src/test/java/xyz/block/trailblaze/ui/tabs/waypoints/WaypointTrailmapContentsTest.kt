package xyz.block.trailblaze.ui.tabs.waypoints

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.project.LoadedTrailblazeTrailmapManifest
import xyz.block.trailblaze.config.project.TrailmapSource
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifest

/**
 * Locks down the auto-discovery branch in [buildTrailmapContents] — the load-bearing fix
 * for the bug where every modern trailmap had `manifest.waypoints = emptyList()`. Without
 * this fallback, `idToTrailmapPath` stays empty, source labels collapse to `(trailmap-bundled)`,
 * the platform-from-source-label derivation in `WaypointGraphBuilder` returns null on
 * every node, and the graph viewer's platform filter pills silently disappear.
 *
 * `WaypointMergeTest` exercises the *consumer* of `idToTrailmapPath` (with a pre-built map)
 * but does not cover the *population* step — a future refactor that drops or reorders
 * the auto-discovery would silently revert the bug. This suite catches that.
 *
 * Uses `TrailmapSource.Filesystem` against a temp folder so we can stage realistic trailmap
 * directory shapes (sub-platforms, missing `waypoints/`, malformed yaml, etc.) without
 * touching the actual classpath.
 */
class WaypointTrailmapContentsTest {

  @Rule @JvmField val tempFolder = TemporaryFolder()

  @Test
  fun emptyManifestList_fallsBackToFilesystemWalk() {
    // The shape we'd find for a modern trailmap on the classpath: `manifest.waypoints` is
    // empty (deprecated field), but `<trailmap>/waypoints/<platform>/...` files exist on
    // disk and are the source of truth.
    val trailmap = stageTrailmap("myapp") {
      writeWaypoint("waypoints/android/home.waypoint.yaml", id = "myapp/home")
      writeWaypoint("waypoints/android/profile.waypoint.yaml", id = "myapp/profile")
    }
    val failures = mutableListOf<String>()

    val contents = buildTrailmapContents(listOf(trailmap), failures)

    assertEquals(setOf("myapp/home", "myapp/profile"), contents.ids)
    assertEquals(
      "trailmap:myapp — waypoints/android/home.waypoint.yaml",
      contents.idToTrailmapPath["myapp/home"],
      "idToTrailmapPath must surface the real on-disk path so platform-from-source-label works",
    )
    assertEquals(
      "trailmap:myapp — waypoints/android/profile.waypoint.yaml",
      contents.idToTrailmapPath["myapp/profile"],
    )
    assertTrue(failures.isEmpty(), "Auto-discovery of well-formed waypoints must not produce failures")
  }

  @Test
  fun nestedPlatformDirs_walksAtAnyDepth() {
    // Square's web trailmap has waypoint files four levels deep
    // (`waypoints/web/dashboard/items/categories.waypoint.yaml`). The auto-discovery
    // walk has to recurse, not stop at depth 1.
    val trailmap = stageTrailmap("webapp") {
      writeWaypoint("waypoints/web/dashboard/items/categories.waypoint.yaml", id = "webapp/items")
      writeWaypoint("waypoints/web/sign-in/email.waypoint.yaml", id = "webapp/signin")
    }
    val failures = mutableListOf<String>()

    val contents = buildTrailmapContents(listOf(trailmap), failures)

    assertEquals(setOf("webapp/items", "webapp/signin"), contents.ids)
    assertEquals(
      "trailmap:webapp — waypoints/web/dashboard/items/categories.waypoint.yaml",
      contents.idToTrailmapPath["webapp/items"],
    )
  }

  @Test
  fun explicitManifestList_takesPrecedenceOverFilesystemWalk() {
    // Legacy trailmaps that still enumerate `waypoints:` in `trailmap.yaml` should not also
    // get a filesystem walk piled on top — the explicit list is authoritative. This
    // pins the `takeIf { it.isNotEmpty() }` branch in `buildTrailmapContents`.
    val trailmap = stageTrailmapWithManifest(
      id = "legacy",
      manifestWaypoints = listOf("waypoints/listed.waypoint.yaml"),
    ) {
      writeWaypoint("waypoints/listed.waypoint.yaml", id = "legacy/listed")
      // This file exists on disk but is NOT in the manifest list — must be ignored.
      writeWaypoint("waypoints/unlisted.waypoint.yaml", id = "legacy/unlisted")
    }
    val failures = mutableListOf<String>()

    val contents = buildTrailmapContents(listOf(trailmap), failures)

    assertEquals(setOf("legacy/listed"), contents.ids)
    assertNull(contents.idToTrailmapPath["legacy/unlisted"], "Filesystem-only files must be ignored when manifest enumerates")
  }

  @Test
  fun noWaypointsDirAtAll_returnsEmptyContentsWithoutFailure() {
    // A library trailmap (no target) has no `waypoints/` dir. `listSiblingsRecursive`
    // returns an empty list for non-existent dirs (per its kdoc) — confirm we don't
    // synthesize a spurious failure entry for that case.
    val trailmap = stageTrailmap("library-only") { /* no waypoints staged */ }
    val failures = mutableListOf<String>()

    val contents = buildTrailmapContents(listOf(trailmap), failures)

    assertTrue(contents.ids.isEmpty())
    assertTrue(contents.idToTrailmapPath.isEmpty())
    assertTrue(failures.isEmpty(), "Trailmap with no waypoints/ dir must not produce a failure entry")
  }

  @Test
  fun multiplePacks_eachContributesItsOwnIds() {
    // The classpath typically has multiple trailmaps co-resident. Each must contribute
    // independently and the `idToTrailmapPath` map must distinguish them by trailmap id.
    val trailmapA = stageTrailmap("alpha") {
      writeWaypoint("waypoints/android/home.waypoint.yaml", id = "alpha/home")
    }
    val trailmapB = stageTrailmap("beta") {
      writeWaypoint("waypoints/ios/home.waypoint.yaml", id = "beta/home")
    }
    val failures = mutableListOf<String>()

    val contents = buildTrailmapContents(listOf(trailmapA, trailmapB), failures)

    assertEquals(setOf("alpha/home", "beta/home"), contents.ids)
    assertEquals("trailmap:alpha — waypoints/android/home.waypoint.yaml", contents.idToTrailmapPath["alpha/home"])
    assertEquals("trailmap:beta — waypoints/ios/home.waypoint.yaml", contents.idToTrailmapPath["beta/home"])
  }

  @Test
  fun firstTrailmapWithIdWins_subsequentDuplicatesAreShadowed() {
    // Mirrors `WaypointDiscovery`'s trailmap-first dedup: if two trailmaps declare the same id,
    // the first one's path is the one we surface. Pins the `putIfAbsent` semantics.
    val trailmapA = stageTrailmap("alpha") {
      writeWaypoint("waypoints/android/home.waypoint.yaml", id = "shared/home")
    }
    val trailmapB = stageTrailmap("beta") {
      writeWaypoint("waypoints/ios/home.waypoint.yaml", id = "shared/home")
    }
    val failures = mutableListOf<String>()

    val contents = buildTrailmapContents(listOf(trailmapA, trailmapB), failures)

    assertEquals(
      "trailmap:alpha — waypoints/android/home.waypoint.yaml",
      contents.idToTrailmapPath["shared/home"],
      "First trailmap to claim an id wins (matches WaypointDiscovery dedup)",
    )
  }

  // ---------- helpers ----------

  private fun stageTrailmap(id: String, stage: TrailmapStage.() -> Unit): LoadedTrailblazeTrailmapManifest =
    stageTrailmapWithManifest(id = id, manifestWaypoints = emptyList(), stage = stage)

  private fun stageTrailmapWithManifest(
    id: String,
    manifestWaypoints: List<String>,
    stage: TrailmapStage.() -> Unit,
  ): LoadedTrailblazeTrailmapManifest {
    val trailmapDir = tempFolder.newFolder(id)
    TrailmapStage(trailmapDir).stage()
    return LoadedTrailblazeTrailmapManifest(
      manifest = TrailblazeTrailmapManifest(id = id, waypoints = manifestWaypoints),
      source = TrailmapSource.Filesystem(trailmapDir = trailmapDir),
    )
  }

  private class TrailmapStage(val trailmapDir: File) {
    fun writeWaypoint(relativePath: String, id: String) {
      writeFile(
        relativePath,
        """
        id: "$id"
        required: []
        forbidden: []
        """.trimIndent(),
      )
    }

    fun writeFile(relativePath: String, content: String) {
      val target = File(trailmapDir, relativePath)
      target.parentFile.mkdirs()
      target.writeText(content)
    }
  }
}
