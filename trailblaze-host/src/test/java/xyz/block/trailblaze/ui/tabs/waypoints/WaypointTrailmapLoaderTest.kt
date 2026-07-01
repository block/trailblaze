package xyz.block.trailblaze.ui.tabs.waypoints

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.project.LoadedTrailblazeTrailmapManifest
import xyz.block.trailblaze.config.project.TrailmapSource
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifest

/**
 * Pins the user-visible failure strings produced by [loadTrailmapWaypointAndExample] for
 * each of the error branches that flow into the visualizer's failure banner. The merge
 * test ([WaypointMergeTest]) stubs the loader, so without these tests the real
 * classpath/filesystem code path runs unchecked — a future refactor that swaps message
 * strings or drops a branch would silently regress.
 *
 * The tests use a `TrailmapSource.Filesystem` backed by a temp directory so we can stage
 * each failure mode by writing (or deliberately not writing) sibling files.
 */
class WaypointTrailmapLoaderTest {

  @Rule @JvmField val tempFolder = TemporaryFolder()

  @Test
  fun missingWaypointYaml_recordsFailureAndReturnsNull() {
    val (trailmap, failures) = setupTrailmap()
    // No waypoint yaml written — readSibling returns null.

    val parsed = loadTrailmapWaypointAndExample(trailmap, "waypoints/missing.waypoint.yaml", failures)

    assertNull(parsed)
    assertEquals(1, failures.size)
    assertTrue(
      failures[0].contains("waypoint yaml not found in trailmap source"),
      "Expected 'waypoint yaml not found' message, got: ${failures[0]}",
    )
    assertTrue(failures[0].startsWith("trailmap:test-trailmap — waypoints/missing.waypoint.yaml"))
  }

  @Test
  fun malformedWaypointYaml_recordsParseFailureAndReturnsNull() {
    val (trailmap, failures) = setupTrailmap {
      writeWaypointYaml("oops.waypoint.yaml", "not: valid: yaml: at: all: ::")
    }

    val parsed = loadTrailmapWaypointAndExample(trailmap, "waypoints/oops.waypoint.yaml", failures)

    assertNull(parsed)
    assertEquals(1, failures.size)
    assertTrue(
      failures[0].contains("failed to parse waypoint yaml"),
      "Expected parse-failure message, got: ${failures[0]}",
    )
  }

  @Test
  fun missingExampleJson_returnsTrailmapParseWithoutExampleAndAddsNoFailure() {
    val (trailmap, failures) = setupTrailmap {
      writeWaypointYaml(
        "ok.waypoint.yaml",
        """
        id: "trailmap/ok"
        """.trimIndent(),
      )
      // No example.json — that's a legitimate "no captured proof" state, not an error.
    }

    val parsed = loadTrailmapWaypointAndExample(trailmap, "waypoints/ok.waypoint.yaml", failures)

    assertNotNull(parsed)
    assertEquals("trailmap/ok", parsed.id)
    assertNull(parsed.example)
    assertTrue(failures.isEmpty(), "Missing example.json must not produce a banner entry")
  }

  @Test
  fun malformedExampleJson_recordsParseFailureAndReturnsParseWithoutExample() {
    val (trailmap, failures) = setupTrailmap {
      writeWaypointYaml(
        "ok.waypoint.yaml",
        """
        id: "trailmap/ok"
        """.trimIndent(),
      )
      writeFile("waypoints/ok.example.json", "{ this is not json }")
    }

    val parsed = loadTrailmapWaypointAndExample(trailmap, "waypoints/ok.waypoint.yaml", failures)

    assertNotNull(parsed)
    assertEquals("trailmap/ok", parsed.id)
    assertNull(parsed.example, "Malformed example.json should not yield a usable example")
    assertEquals(1, failures.size)
    assertTrue(
      failures[0].contains("failed to parse example.json"),
      "Expected example-parse-failure message, got: ${failures[0]}",
    )
    assertTrue(
      failures[0].contains("waypoint=trailmap/ok"),
      "Failure should attribute to the waypoint id, got: ${failures[0]}",
    )
  }

  @Test
  fun exampleJsonWithoutTrailblazeNodeTree_recordsMissingTreeFailure() {
    val (trailmap, failures) = setupTrailmap {
      writeWaypointYaml(
        "ok.waypoint.yaml",
        """
        id: "trailmap/ok"
        """.trimIndent(),
      )
      // Valid JSON but missing the trailblazeNodeTree field.
      writeFile(
        "waypoints/ok.example.json",
        """{ "screenshotFile": "shot.png", "deviceWidth": 100, "deviceHeight": 200 }""",
      )
    }

    val parsed = loadTrailmapWaypointAndExample(trailmap, "waypoints/ok.waypoint.yaml", failures)

    assertNotNull(parsed)
    assertEquals("trailmap/ok", parsed.id)
    assertNull(parsed.example, "example.json without trailblazeNodeTree must not produce an example")
    assertEquals(1, failures.size)
    assertTrue(
      failures[0].contains("example.json has no trailblazeNodeTree"),
      "Expected missing-tree message, got: ${failures[0]}",
    )
    assertTrue(failures[0].contains("waypoint=trailmap/ok"))
  }

  // ---------- helpers ----------

  /**
   * Spins up a synthetic trailmap with `id = "test-trailmap"` rooted in a temp folder. The block
   * gets a small DSL for writing trailmap-relative files; the returned pair carries the
   * [LoadedTrailblazeTrailmapManifest] and the shared failures-list the function appends to.
   */
  private fun setupTrailmap(stage: TrailmapStage.() -> Unit = {}): Pair<LoadedTrailblazeTrailmapManifest, MutableList<String>> {
    val trailmapDir = tempFolder.newFolder("test-trailmap")
    val stager = TrailmapStage(trailmapDir)
    stager.stage()
    val trailmap = LoadedTrailblazeTrailmapManifest(
      manifest = TrailblazeTrailmapManifest(id = "test-trailmap"),
      source = TrailmapSource.Filesystem(trailmapDir = trailmapDir),
    )
    return trailmap to mutableListOf()
  }

  private class TrailmapStage(val trailmapDir: File) {
    fun writeWaypointYaml(name: String, content: String) {
      writeFile("waypoints/$name", content)
    }
    fun writeFile(relativePath: String, content: String) {
      val target = File(trailmapDir, relativePath)
      target.parentFile.mkdirs()
      target.writeText(content)
    }
  }
}
