package xyz.block.trailblaze.trailrunner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.waypoint.WaypointLoader

/**
 * The Trailmaps UI's "new waypoint" flow ([buildNewComponentResponse]) writes a scaffold file the
 * user immediately loads and edits. After the v1→v2 hard cut, that scaffold MUST be valid v2 — a
 * top-level `required:`/`forbidden:` skeleton would be rejected by [WaypointLoader] the moment the
 * freshly-created file is loaded. This guards that the generated skeleton round-trips through the
 * strict parser.
 */
class TrailmapWaypointSkeletonTest {

  @Test
  fun `the new-waypoint scaffold parses under the strict v2 parser`() {
    val skeleton = trailmapComponentSkeleton(kind = "waypoints", trailmap = "myapp", name = "home")

    val def = WaypointLoader.yaml.decodeFromString(
      xyz.block.trailblaze.api.waypoint.WaypointDefinition.serializer(),
      skeleton,
    )

    assertEquals("myapp/home", def.id)
    // Scaffold defaults to an android classifier block with a single empty required condition.
    assertTrue(def.byClassifier.containsKey("android"), "scaffold should emit an android: block: $skeleton")
    assertEquals(1, def.byClassifier.getValue("android").required.size)
  }
}
