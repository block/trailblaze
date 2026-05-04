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
 * Locks down the pack-vs-filesystem provenance rule that
 * [WaypointDiscovery]'s pack-first dedup leaves to the merge step:
 *
 * if a definition's id appears in [PackContents.ids], the def we got back from discovery
 * is the pack's. The matching same-id filesystem file under `root` is *shadowed* and
 * must NOT contribute its source label or example — otherwise the visualizer would
 * describe a pack waypoint with a screenshot that doesn't belong to it.
 *
 * That bug was caught manually in review. This test exists so a future refactor can't
 * silently regress it.
 */
class WaypointMergeTest {

  @Rule @JvmField val tempFolder = TemporaryFolder()

  @Test
  fun packProvenance_winsOverShadowedFilesystemFile() {
    val root = tempFolder.newFolder("root")
    val shadowedFile = File(root, "ready.waypoint.yaml").apply { writeText("ignored") }
    val packExample = exampleWith(testTag = "pack")
    val filesystemExample = exampleWith(testTag = "filesystem")

    val items = mergeWaypointSources(
      definitions = listOf(WaypointDefinition(id = "shared/ready")),
      idToFile = mapOf("shared/ready" to shadowedFile),
      packContents = PackContents(
        ids = setOf("shared/ready"),
        examples = mapOf(
          "shared/ready" to PackExample(
            sourceLabel = "pack:shared — ready.waypoint.yaml",
            example = packExample,
          ),
        ),
      ),
      root = root,
      loadFilesystemExample = { _, _ -> filesystemExample },
    )

    val item = items.single()
    assertEquals("shared/ready", item.definition.id)
    assertEquals(
      "pack:shared — ready.waypoint.yaml",
      item.sourceLabel,
      "Shadowed filesystem file must not surface its path as the source label.",
    )
    // Reference equality — the pack's example must be reused, not the loader's filesystem result.
    assertTrue(
      item.example === packExample,
      "Shadowed filesystem example must not be loaded for a pack-provided id.",
    )
  }

  @Test
  fun packOnlyDefinition_usesPackExampleAndLabel() {
    val root = tempFolder.newFolder("root")
    val packExample = exampleWith(testTag = "pack-only")

    val items = mergeWaypointSources(
      definitions = listOf(WaypointDefinition(id = "clock/alarm-tab")),
      idToFile = emptyMap(),
      packContents = PackContents(
        ids = setOf("clock/alarm-tab"),
        examples = mapOf(
          "clock/alarm-tab" to PackExample(
            sourceLabel = "pack:clock — clock-tab.waypoint.yaml",
            example = packExample,
          ),
        ),
      ),
      root = root,
      loadFilesystemExample = { _, _ -> error("filesystem loader must not be called for pack-only ids") },
    )

    val item = items.single()
    assertEquals("pack:clock — clock-tab.waypoint.yaml", item.sourceLabel)
    assertTrue(item.example === packExample)
  }

  @Test
  fun packOnlyWithoutExample_fallsBackToBundledLabel() {
    val root = tempFolder.newFolder("root")

    val items = mergeWaypointSources(
      definitions = listOf(WaypointDefinition(id = "clock/no-example")),
      idToFile = emptyMap(),
      packContents = PackContents(
        ids = setOf("clock/no-example"),
        examples = emptyMap(),
      ),
      root = root,
      loadFilesystemExample = { _, _ -> error("must not be called") },
    )

    val item = items.single()
    assertEquals(
      "(pack-bundled)",
      item.sourceLabel,
      "A pack-provided id with no captured example must still describe itself as pack-bundled.",
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
      packContents = PackContents(ids = emptySet(), examples = emptyMap()),
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
      packContents = PackContents(ids = emptySet(), examples = emptyMap()),
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
