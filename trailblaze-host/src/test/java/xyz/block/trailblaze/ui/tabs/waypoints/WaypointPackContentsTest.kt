package xyz.block.trailblaze.ui.tabs.waypoints

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.project.LoadedTrailblazePackManifest
import xyz.block.trailblaze.config.project.PackSource
import xyz.block.trailblaze.config.project.TrailblazePackManifest

/**
 * Locks down the auto-discovery branch in [buildPackContents] — the load-bearing fix
 * for the bug where every modern pack had `manifest.waypoints = emptyList()`. Without
 * this fallback, `idToPackPath` stays empty, source labels collapse to `(pack-bundled)`,
 * the platform-from-source-label derivation in `WaypointGraphBuilder` returns null on
 * every node, and the graph viewer's platform filter pills silently disappear.
 *
 * `WaypointMergeTest` exercises the *consumer* of `idToPackPath` (with a pre-built map)
 * but does not cover the *population* step — a future refactor that drops or reorders
 * the auto-discovery would silently revert the bug. This suite catches that.
 *
 * Uses `PackSource.Filesystem` against a temp folder so we can stage realistic pack
 * directory shapes (sub-platforms, missing `waypoints/`, malformed yaml, etc.) without
 * touching the actual classpath.
 */
class WaypointPackContentsTest {

  @Rule @JvmField val tempFolder = TemporaryFolder()

  @Test
  fun emptyManifestList_fallsBackToFilesystemWalk() {
    // The shape we'd find for a modern pack on the classpath: `manifest.waypoints` is
    // empty (deprecated field), but `<pack>/waypoints/<platform>/...` files exist on
    // disk and are the source of truth.
    val pack = stagePack("myapp") {
      writeWaypoint("waypoints/android/home.waypoint.yaml", id = "myapp/home")
      writeWaypoint("waypoints/android/profile.waypoint.yaml", id = "myapp/profile")
    }
    val failures = mutableListOf<String>()

    val contents = buildPackContents(listOf(pack), failures)

    assertEquals(setOf("myapp/home", "myapp/profile"), contents.ids)
    assertEquals(
      "pack:myapp — waypoints/android/home.waypoint.yaml",
      contents.idToPackPath["myapp/home"],
      "idToPackPath must surface the real on-disk path so platform-from-source-label works",
    )
    assertEquals(
      "pack:myapp — waypoints/android/profile.waypoint.yaml",
      contents.idToPackPath["myapp/profile"],
    )
    assertTrue(failures.isEmpty(), "Auto-discovery of well-formed waypoints must not produce failures")
  }

  @Test
  fun nestedPlatformDirs_walksAtAnyDepth() {
    // Square's web pack has waypoint files four levels deep
    // (`waypoints/web/dashboard/items/categories.waypoint.yaml`). The auto-discovery
    // walk has to recurse, not stop at depth 1.
    val pack = stagePack("webapp") {
      writeWaypoint("waypoints/web/dashboard/items/categories.waypoint.yaml", id = "webapp/items")
      writeWaypoint("waypoints/web/sign-in/email.waypoint.yaml", id = "webapp/signin")
    }
    val failures = mutableListOf<String>()

    val contents = buildPackContents(listOf(pack), failures)

    assertEquals(setOf("webapp/items", "webapp/signin"), contents.ids)
    assertEquals(
      "pack:webapp — waypoints/web/dashboard/items/categories.waypoint.yaml",
      contents.idToPackPath["webapp/items"],
    )
  }

  @Test
  fun explicitManifestList_takesPrecedenceOverFilesystemWalk() {
    // Legacy packs that still enumerate `waypoints:` in `pack.yaml` should not also
    // get a filesystem walk piled on top — the explicit list is authoritative. This
    // pins the `takeIf { it.isNotEmpty() }` branch in `buildPackContents`.
    val pack = stagePackWithManifest(
      id = "legacy",
      manifestWaypoints = listOf("waypoints/listed.waypoint.yaml"),
    ) {
      writeWaypoint("waypoints/listed.waypoint.yaml", id = "legacy/listed")
      // This file exists on disk but is NOT in the manifest list — must be ignored.
      writeWaypoint("waypoints/unlisted.waypoint.yaml", id = "legacy/unlisted")
    }
    val failures = mutableListOf<String>()

    val contents = buildPackContents(listOf(pack), failures)

    assertEquals(setOf("legacy/listed"), contents.ids)
    assertNull(contents.idToPackPath["legacy/unlisted"], "Filesystem-only files must be ignored when manifest enumerates")
  }

  @Test
  fun noWaypointsDirAtAll_returnsEmptyContentsWithoutFailure() {
    // A library pack (no target) has no `waypoints/` dir. `listSiblingsRecursive`
    // returns an empty list for non-existent dirs (per its kdoc) — confirm we don't
    // synthesize a spurious failure entry for that case.
    val pack = stagePack("library-only") { /* no waypoints staged */ }
    val failures = mutableListOf<String>()

    val contents = buildPackContents(listOf(pack), failures)

    assertTrue(contents.ids.isEmpty())
    assertTrue(contents.idToPackPath.isEmpty())
    assertTrue(failures.isEmpty(), "Pack with no waypoints/ dir must not produce a failure entry")
  }

  @Test
  fun multiplePacks_eachContributesItsOwnIds() {
    // The classpath typically has multiple packs co-resident. Each must contribute
    // independently and the `idToPackPath` map must distinguish them by pack id.
    val packA = stagePack("alpha") {
      writeWaypoint("waypoints/android/home.waypoint.yaml", id = "alpha/home")
    }
    val packB = stagePack("beta") {
      writeWaypoint("waypoints/ios/home.waypoint.yaml", id = "beta/home")
    }
    val failures = mutableListOf<String>()

    val contents = buildPackContents(listOf(packA, packB), failures)

    assertEquals(setOf("alpha/home", "beta/home"), contents.ids)
    assertEquals("pack:alpha — waypoints/android/home.waypoint.yaml", contents.idToPackPath["alpha/home"])
    assertEquals("pack:beta — waypoints/ios/home.waypoint.yaml", contents.idToPackPath["beta/home"])
  }

  @Test
  fun firstPackWithIdWins_subsequentDuplicatesAreShadowed() {
    // Mirrors `WaypointDiscovery`'s pack-first dedup: if two packs declare the same id,
    // the first one's path is the one we surface. Pins the `putIfAbsent` semantics.
    val packA = stagePack("alpha") {
      writeWaypoint("waypoints/android/home.waypoint.yaml", id = "shared/home")
    }
    val packB = stagePack("beta") {
      writeWaypoint("waypoints/ios/home.waypoint.yaml", id = "shared/home")
    }
    val failures = mutableListOf<String>()

    val contents = buildPackContents(listOf(packA, packB), failures)

    assertEquals(
      "pack:alpha — waypoints/android/home.waypoint.yaml",
      contents.idToPackPath["shared/home"],
      "First pack to claim an id wins (matches WaypointDiscovery dedup)",
    )
  }

  // ---------- helpers ----------

  private fun stagePack(id: String, stage: PackStage.() -> Unit): LoadedTrailblazePackManifest =
    stagePackWithManifest(id = id, manifestWaypoints = emptyList(), stage = stage)

  private fun stagePackWithManifest(
    id: String,
    manifestWaypoints: List<String>,
    stage: PackStage.() -> Unit,
  ): LoadedTrailblazePackManifest {
    val packDir = tempFolder.newFolder(id)
    PackStage(packDir).stage()
    return LoadedTrailblazePackManifest(
      manifest = TrailblazePackManifest(id = id, waypoints = manifestWaypoints),
      source = PackSource.Filesystem(packDir = packDir),
    )
  }

  private class PackStage(val packDir: File) {
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
      val target = File(packDir, relativePath)
      target.parentFile.mkdirs()
      target.writeText(content)
    }
  }
}
