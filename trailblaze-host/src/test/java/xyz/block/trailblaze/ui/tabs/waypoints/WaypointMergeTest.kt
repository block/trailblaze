package xyz.block.trailblaze.ui.tabs.waypoints

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.ui.waypoints.WaypointExample

/**
 * Locks down the trailmap-vs-filesystem provenance rule that
 * [WaypointDiscovery]'s trailmap-first dedup leaves to the merge step:
 *
 * if a definition's id appears in [TrailmapContents.ids], the def we got back from discovery
 * is the trailmap's. The matching same-id filesystem file under `root` is *shadowed* and
 * must NOT contribute its source label or example — otherwise the visualizer would
 * describe a trailmap waypoint with a screenshot that doesn't belong to it.
 *
 * That bug was caught manually in review. This test exists so a future refactor can't
 * silently regress it.
 */
class WaypointMergeTest {

  @Rule @JvmField val tempFolder = TemporaryFolder()

  @Test
  fun trailmapProvenance_winsOverShadowedFilesystemFile() {
    val root = tempFolder.newFolder("root")
    val shadowedFile = File(root, "ready.waypoint.yaml").apply { writeText("ignored") }
    val trailmapExample = exampleWith(testTag = "trailmap")
    val filesystemExample = exampleWith(testTag = "filesystem")

    val items = mergeWaypointSources(
      definitions = listOf(WaypointDefinition(id = "shared/ready")),
      idToFile = mapOf("shared/ready" to shadowedFile),
      trailmapContents = TrailmapContents(
        ids = setOf("shared/ready"),
        examples = mapOf(
          "shared/ready" to TrailmapExample(
            sourceLabel = "trailmap:shared — ready.waypoint.yaml",
            example = trailmapExample,
          ),
        ),
      ),
      root = root,
      loadFilesystemExample = { _, _ -> filesystemExample },
    )

    val item = items.single()
    assertEquals("shared/ready", item.definition.id)
    assertEquals(
      "trailmap:shared — ready.waypoint.yaml",
      item.sourceLabel,
      "Shadowed filesystem file must not surface its path as the source label.",
    )
    // Reference equality — the trailmap's example must be reused, not the loader's filesystem result.
    assertTrue(
      item.example === trailmapExample,
      "Shadowed filesystem example must not be loaded for a trailmap-provided id.",
    )
  }

  @Test
  fun trailmapOnlyDefinition_usesTrailmapExampleAndLabel() {
    val root = tempFolder.newFolder("root")
    val trailmapExample = exampleWith(testTag = "trailmap-only")

    val items = mergeWaypointSources(
      definitions = listOf(WaypointDefinition(id = "clock/alarm-tab")),
      idToFile = emptyMap(),
      trailmapContents = TrailmapContents(
        ids = setOf("clock/alarm-tab"),
        examples = mapOf(
          "clock/alarm-tab" to TrailmapExample(
            sourceLabel = "trailmap:clock — clock-tab.waypoint.yaml",
            example = trailmapExample,
          ),
        ),
      ),
      root = root,
      loadFilesystemExample = { _, _ -> error("filesystem loader must not be called for trailmap-only ids") },
    )

    val item = items.single()
    assertEquals("trailmap:clock — clock-tab.waypoint.yaml", item.sourceLabel)
    assertTrue(item.example === trailmapExample)
  }

  @Test
  fun trailmapOnlyWithoutExample_usesIdToTrailmapPathLabel() {
    val root = tempFolder.newFolder("root")

    val items = mergeWaypointSources(
      definitions = listOf(WaypointDefinition(id = "myapp/home")),
      idToFile = emptyMap(),
      trailmapContents = TrailmapContents(
        ids = setOf("myapp/home"),
        examples = emptyMap(),
        idToTrailmapPath = mapOf("myapp/home" to "trailmap:myapp — waypoints/android/home.waypoint.yaml"),
      ),
      root = root,
      loadFilesystemExample = { _, _ -> error("must not be called") },
    )

    val item = items.single()
    // Even without a captured example, trailmap-provided ids surface their manifest path so
    // platform derivation (which splits the source label on `/` and matches `android` /
    // `ios` / `web` as a path *segment*, not a substring) still works.
    assertEquals(
      "trailmap:myapp — waypoints/android/home.waypoint.yaml",
      item.sourceLabel,
    )
    assertNull(item.example)
  }

  @Test
  fun trailmapOnlyWithoutExample_orPathFallsBackToBundledLabel() {
    val root = tempFolder.newFolder("root")

    val items = mergeWaypointSources(
      definitions = listOf(WaypointDefinition(id = "clock/no-example")),
      idToFile = emptyMap(),
      trailmapContents = TrailmapContents(
        ids = setOf("clock/no-example"),
        examples = emptyMap(),
      ),
      root = root,
      loadFilesystemExample = { _, _ -> error("must not be called") },
    )

    val item = items.single()
    assertEquals(
      "(trailmap-bundled)",
      item.sourceLabel,
      "When neither example nor manifest path is available, the bundled-label fallback still kicks in.",
    )
    assertNull(item.example)
  }

  @Test
  fun filesystemOnlyDefinition_usesFilesystemPathAndExample() {
    val root = tempFolder.newFolder("root")
    val file = File(root, "local.waypoint.yaml").apply { writeText("ignored") }
    val filesystemExample = exampleWith(testTag = "fs-only")

    val items = mergeWaypointSources(
      definitions = listOf(WaypointDefinition(id = "local/only")),
      idToFile = mapOf("local/only" to file),
      trailmapContents = TrailmapContents(ids = emptySet(), examples = emptyMap()),
      root = root,
      loadFilesystemExample = { f, id ->
        // Sanity-check: loader is invoked with the right pair, then returns our fixture.
        assertEquals(file, f)
        assertEquals("local/only", id)
        filesystemExample
      },
    )

    val item = items.single()
    assertEquals("local.waypoint.yaml", item.sourceLabel)
    assertTrue(item.example === filesystemExample)
  }

  @Test
  fun filesystemOnlyWithoutExample_keepsRelativePathLabelAndNullExample() {
    val root = tempFolder.newFolder("root")
    val file = File(root, "deep/local.waypoint.yaml").apply {
      parentFile.mkdirs()
      writeText("ignored")
    }

    // Track invocations so a future regression that skips the loader for filesystem-only
    // ids can't pass with output == null. The lambda runs at most once per definition.
    val loaderCalls = mutableListOf<Pair<File, String>>()
    val items = mergeWaypointSources(
      definitions = listOf(WaypointDefinition(id = "local/bare")),
      idToFile = mapOf("local/bare" to file),
      trailmapContents = TrailmapContents(ids = emptySet(), examples = emptyMap()),
      root = root,
      loadFilesystemExample = { f, id ->
        loaderCalls += f to id
        null
      },
    )

    val item = items.single()
    // toRelativeString uses File.separator on the host — pin the exact relative path
    // (separator-aware) rather than a loose suffix, so an accidental absolute or
    // extra-segment label can't slip past.
    assertEquals("deep${File.separator}local.waypoint.yaml", item.sourceLabel)
    assertNull(item.example)
    // Loader must have been invoked exactly once with the right (file, id) pair.
    assertEquals(listOf(file to "local/bare"), loaderCalls)
  }

  // ---------- helpers ----------

  private fun exampleWith(testTag: String): WaypointExample = WaypointExample(
    tree = TrailblazeNode(
      nodeId = 1L,
      driverDetail = DriverNodeDetail.Compose(testTag = testTag),
    ),
    screenshotBytes = null,
    deviceWidth = 100,
    deviceHeight = 200,
  )
}
