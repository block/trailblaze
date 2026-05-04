package xyz.block.trailblaze.ui.tabs.waypoints

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.project.LoadedTrailblazePackManifest
import xyz.block.trailblaze.config.project.PackSource
import xyz.block.trailblaze.config.project.TrailblazePackManifest

/**
 * Pins the user-visible failure strings produced by [loadPackWaypointAndExample] for
 * each of the error branches that flow into the visualizer's failure banner. The merge
 * test ([WaypointMergeTest]) stubs the loader, so without these tests the real
 * classpath/filesystem code path runs unchecked — a future refactor that swaps message
 * strings or drops a branch would silently regress.
 *
 * The tests use a `PackSource.Filesystem` backed by a temp directory so we can stage
 * each failure mode by writing (or deliberately not writing) sibling files.
 */
class WaypointPackLoaderTest {

  @Rule @JvmField val tempFolder = TemporaryFolder()

  @Test
  fun missingWaypointYaml_recordsFailureAndReturnsNull() {
    val (pack, failures) = setupPack()
    // No waypoint yaml written — readSibling returns null.

    val parsed = loadPackWaypointAndExample(pack, "waypoints/missing.waypoint.yaml", failures)

    assertNull(parsed)
    assertEquals(1, failures.size)
    assertTrue(
      failures[0].contains("waypoint yaml not found in pack source"),
      "Expected 'waypoint yaml not found' message, got: ${failures[0]}",
    )
    assertTrue(failures[0].startsWith("pack:test-pack — waypoints/missing.waypoint.yaml"))
  }

  @Test
  fun malformedWaypointYaml_recordsParseFailureAndReturnsNull() {
    val (pack, failures) = setupPack {
      writeWaypointYaml("oops.waypoint.yaml", "not: valid: yaml: at: all: ::")
    }

    val parsed = loadPackWaypointAndExample(pack, "waypoints/oops.waypoint.yaml", failures)

    assertNull(parsed)
    assertEquals(1, failures.size)
    assertTrue(
      failures[0].contains("failed to parse waypoint yaml"),
      "Expected parse-failure message, got: ${failures[0]}",
    )
  }

  @Test
  fun missingExampleJson_returnsPackParseWithoutExampleAndAddsNoFailure() {
    val (pack, failures) = setupPack {
      writeWaypointYaml(
        "ok.waypoint.yaml",
        """
        id: "pack/ok"
        required: []
        forbidden: []
        """.trimIndent(),
      )
      // No example.json — that's a legitimate "no captured proof" state, not an error.
    }

    val parsed = loadPackWaypointAndExample(pack, "waypoints/ok.waypoint.yaml", failures)

    assertNotNull(parsed)
    assertEquals("pack/ok", parsed.id)
    assertNull(parsed.example)
    assertTrue(failures.isEmpty(), "Missing example.json must not produce a banner entry")
  }

  @Test
  fun malformedExampleJson_recordsParseFailureAndReturnsParseWithoutExample() {
    val (pack, failures) = setupPack {
      writeWaypointYaml(
        "ok.waypoint.yaml",
        """
        id: "pack/ok"
        required: []
        forbidden: []
        """.trimIndent(),
      )
      writeFile("waypoints/ok.example.json", "{ this is not json }")
    }

    val parsed = loadPackWaypointAndExample(pack, "waypoints/ok.waypoint.yaml", failures)

    assertNotNull(parsed)
    assertEquals("pack/ok", parsed.id)
    assertNull(parsed.example, "Malformed example.json should not yield a usable example")
    assertEquals(1, failures.size)
    assertTrue(
      failures[0].contains("failed to parse example.json"),
      "Expected example-parse-failure message, got: ${failures[0]}",
    )
    assertTrue(
      failures[0].contains("waypoint=pack/ok"),
      "Failure should attribute to the waypoint id, got: ${failures[0]}",
    )
  }

  @Test
  fun exampleJsonWithoutTrailblazeNodeTree_recordsMissingTreeFailure() {
    val (pack, failures) = setupPack {
      writeWaypointYaml(
        "ok.waypoint.yaml",
        """
        id: "pack/ok"
        required: []
        forbidden: []
        """.trimIndent(),
      )
      // Valid JSON but missing the trailblazeNodeTree field.
      writeFile(
        "waypoints/ok.example.json",
        """{ "screenshotFile": "shot.png", "deviceWidth": 100, "deviceHeight": 200 }""",
      )
    }

    val parsed = loadPackWaypointAndExample(pack, "waypoints/ok.waypoint.yaml", failures)

    assertNotNull(parsed)
    assertEquals("pack/ok", parsed.id)
    assertNull(parsed.example, "example.json without trailblazeNodeTree must not produce an example")
    assertEquals(1, failures.size)
    assertTrue(
      failures[0].contains("example.json has no trailblazeNodeTree"),
      "Expected missing-tree message, got: ${failures[0]}",
    )
    assertTrue(failures[0].contains("waypoint=pack/ok"))
  }

  // ---------- helpers ----------

  /**
   * Spins up a synthetic pack with `id = "test-pack"` rooted in a temp folder. The block
   * gets a small DSL for writing pack-relative files; the returned pair carries the
   * [LoadedTrailblazePackManifest] and the shared failures-list the function appends to.
   */
  private fun setupPack(stage: PackStage.() -> Unit = {}): Pair<LoadedTrailblazePackManifest, MutableList<String>> {
    val packDir = tempFolder.newFolder("test-pack")
    val stager = PackStage(packDir)
    stager.stage()
    val pack = LoadedTrailblazePackManifest(
      manifest = TrailblazePackManifest(id = "test-pack"),
      source = PackSource.Filesystem(packDir = packDir),
    )
    return pack to mutableListOf()
  }

  private class PackStage(val packDir: File) {
    fun writeWaypointYaml(name: String, content: String) {
      writeFile("waypoints/$name", content)
    }
    fun writeFile(relativePath: String, content: String) {
      val target = File(packDir, relativePath)
      target.parentFile.mkdirs()
      target.writeText(content)
    }
  }
}
