package xyz.block.trailblaze.graph

import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.api.waypoint.WaypointExampleRef
import xyz.block.trailblaze.api.waypoint.WaypointVariant
import xyz.block.trailblaze.config.project.LoadedTrailblazeTrailmapManifest
import xyz.block.trailblaze.config.project.TrailmapSource
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifest
import xyz.block.trailblaze.logs.client.TrailblazeJson

/**
 * Which example file the waypoint graph shows for a waypoint. `capture-example` writes a
 * classifier-labeled `<base>.example.<classifier>.json` (and no unlabeled default) when the
 * source session records a device classifier, so both loaders — filesystem and trailmap — must
 * find those, or a freshly captured waypoint renders as "(no screenshot)".
 */
class WaypointExampleResolutionTest {

  @Rule @JvmField val tempFolder = TemporaryFolder()

  @Test
  fun candidates_preferDefaultThenClassifierLabeledSortedByName() {
    val candidates = waypointExampleCandidates(
      basename = "home",
      definition = WaypointDefinition(id = "app/home"),
      siblingFileNames = {
        listOf(
          "home.example.ios-iphone.json",
          "home.waypoint.yaml",
          "home.example.android-phone.json",
          "home.example.android-phone.webp",
          "home.example.json",
          "homepage.example.json",
          "other.example.json",
        )
      },
    ).toList()

    assertEquals(
      listOf("home.example.json", "home.example.android-phone.json", "home.example.ios-iphone.json"),
      candidates,
    )
  }

  @Test
  fun candidates_listTheDirectoryOnlyOnceTheDefaultIsPassedOver() {
    var listed = false
    val candidates = waypointExampleCandidates("home", WaypointDefinition(id = "app/home")) {
      listed = true
      listOf("home.example.android-phone.json")
    }

    assertEquals("home.example.json", candidates.first())
    assertFalse(listed, "The default example must be tried before the directory is listed")
    assertEquals(listOf("home.example.json", "home.example.android-phone.json"), candidates.toList())
    assertTrue(listed)
  }

  @Test
  fun candidates_includeExamplesDeclaredByClassifierBlocks() {
    val definition = WaypointDefinition(
      id = "app/home",
      byClassifier = mapOf(
        "android" to WaypointVariant(example = WaypointExampleRef(file = "home.android.example.json")),
        "android-tablet" to WaypointVariant(example = WaypointExampleRef(file = "home.example.android-tablet.json")),
        "web" to WaypointVariant(),
      ),
    )

    val candidates = waypointExampleCandidates(
      basename = "home",
      definition = definition,
      siblingFileNames = { listOf("home.example.android-tablet.json") },
    ).toList()

    assertEquals(
      listOf("home.example.json", "home.android.example.json", "home.example.android-tablet.json"),
      candidates,
    )
  }

  @Test
  fun filesystem_classifierLabeledOnlyExample_isLoadedWithItsScreenshot() {
    val dir = tempFolder.newFolder("waypoints")
    val waypointFile = File(dir, "home.waypoint.yaml").apply { writeText("id: app/home") }
    writeExample(dir, "home.example.android-phone", testTag = "phone")

    val failures = mutableListOf<String>()
    val example = tryLoadFilesystemExample(waypointFile, WaypointDefinition(id = "app/home"), failures, dir)

    assertNotNull(example, "A classifier-labeled example must be found when no default exists")
    assertEquals("phone", (example.tree.driverDetail as DriverNodeDetail.Compose).testTag)
    assertContentEquals(screenshotBytesFor("home.example.android-phone"), example.screenshotBytes)
    assertTrue(failures.isEmpty(), "Unexpected failures: $failures")
  }

  @Test
  fun filesystem_defaultExample_winsOverClassifierLabeled() {
    val dir = tempFolder.newFolder("waypoints")
    val waypointFile = File(dir, "home.waypoint.yaml").apply { writeText("id: app/home") }
    writeExample(dir, "home.example.android-phone", testTag = "phone")
    writeExample(dir, "home.example", testTag = "default")

    val example = tryLoadFilesystemExample(waypointFile, WaypointDefinition(id = "app/home"), mutableListOf(), dir)

    assertEquals("default", (assertNotNull(example).tree.driverDetail as DriverNodeDetail.Compose).testTag)
  }

  @Test
  fun filesystem_noExampleAtAll_returnsNullWithoutFailure() {
    val dir = tempFolder.newFolder("waypoints")
    val waypointFile = File(dir, "home.waypoint.yaml").apply { writeText("id: app/home") }

    val failures = mutableListOf<String>()
    val example = tryLoadFilesystemExample(waypointFile, WaypointDefinition(id = "app/home"), failures, dir)

    assertNull(example)
    assertTrue(failures.isEmpty(), "A missing example is not a failure: $failures")
  }

  @Test
  fun trailmap_classifierLabeledOnlyExample_isLoadedWithItsScreenshot() {
    val trailmapDir = tempFolder.newFolder("test-trailmap")
    val waypointsDir = File(trailmapDir, "waypoints/android").apply { mkdirs() }
    File(waypointsDir, "home.waypoint.yaml").writeText("id: \"trailmap/home\"")
    writeExample(waypointsDir, "home.example.android-phone", testTag = "phone")
    val trailmap = LoadedTrailblazeTrailmapManifest(
      manifest = TrailblazeTrailmapManifest(id = "test-trailmap"),
      source = TrailmapSource.Filesystem(trailmapDir = trailmapDir),
    )

    val failures = mutableListOf<String>()
    val parsed = loadTrailmapWaypointAndExample(trailmap, "waypoints/android/home.waypoint.yaml", failures)

    val example = assertNotNull(assertNotNull(parsed).example, "A classifier-labeled example must be found")
    assertEquals("phone", (example.tree.driverDetail as DriverNodeDetail.Compose).testTag)
    assertContentEquals(screenshotBytesFor("home.example.android-phone"), example.screenshotBytes)
    assertTrue(failures.isEmpty(), "Unexpected failures: $failures")
  }

  /** Writes `<stem>.json` plus the `<stem>.png` screenshot it names, like `capture-example` does. */
  private fun writeExample(dir: File, stem: String, testTag: String) {
    val tree = TrailblazeJson.defaultWithoutToolsInstance.encodeToString(
      TrailblazeNode.serializer(),
      TrailblazeNode(nodeId = 1L, driverDetail = DriverNodeDetail.Compose(testTag = testTag)),
    )
    File(dir, "$stem.json").writeText(
      """{ "screenshotFile": "$stem.png", "deviceWidth": 100, "deviceHeight": 200, "trailblazeNodeTree": $tree }""",
    )
    File(dir, "$stem.png").writeBytes(screenshotBytesFor(stem))
  }

  private fun screenshotBytesFor(stem: String): ByteArray = stem.toByteArray()
}
