package xyz.block.trailblaze.graph

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue
import xyz.block.trailblaze.scripting.AnalyzerScriptedToolEnrichment
import xyz.block.trailblaze.scripting.MetaOnlyDescriptorTestFixture

/**
 * A trailhead written as a TypeScript tool declares `trailhead:` in its spec, not in a
 * `*.trailhead.yaml`. The waypoint graph must still draw it, or every TS trailhead silently
 * drops off the graph and its destination looks unreachable.
 */
class WaypointGraphScriptedTrailheadTest {

  private val workspaceRoot: File = createTempDirectory("waypoint-graph-scripted-trailhead").toFile()

  @AfterTest
  fun cleanup() {
    workspaceRoot.deleteRecursively()
  }

  @Test
  fun `a TypeScript trailhead appears in the graph with its destination`() {
    assumeAnalyzerAvailable()
    writeMyAppTrailmap()

    val graph = buildGraph()

    val trailhead = graph.trailheads.single { it.id == "myapp_launchHome" }
    assertEquals("myapp/home", trailhead.to)
    assertEquals(false, trailhead.dynamic)
  }

  @Test
  fun `a target the workspace does not load cannot hide another target's trailhead`() {
    assumeAnalyzerAvailable()
    writeMyAppTrailmap()
    // A draft trailmap outside `targets:` whose dependency doesn't exist. Resolving every target
    // would fail on it; resolving only the workspace's selection never touches it.
    val draftDir = File(workspaceRoot, "trails/config/trailmaps/draft").apply { mkdirs() }
    File(draftDir, "trailmap.yaml").writeText(
      """
      id: draft
      dependencies:
        - not_installed
      target:
        display_name: draft
      """.trimIndent(),
    )

    val graph = buildGraph()

    assertEquals("myapp/home", graph.trailheads.single { it.id == "myapp_launchHome" }.to)
  }

  @Test
  fun `a platform-scoped TypeScript trailhead carries its platforms`() {
    assumeAnalyzerAvailable()
    writeMyAppTrailmap(androidOnlyTrailhead = true)

    val graph = buildGraph()

    assertEquals(listOf("android"), graph.trailheads.single { it.id == "myapp_launchHome" }.platforms)
  }

  @Test
  fun `a platform view drops a trailhead whose tool cannot run there`() {
    val home = WaypointGraphNode(
      id = "app/home",
      description = null,
      screenshotDataUri = null,
      sourceLabel = null,
      platform = null,
      platforms = listOf("android", "ios"),
    )
    val androidOnly = WaypointGraphTrailhead(id = "launch", description = null, to = "app/home", platforms = listOf("android"))
    val anywhere = WaypointGraphTrailhead(id = "open", description = null, to = "app/home")

    fun kept(platform: String) = WaypointGraphBuilder.applyScope(
      waypoints = listOf(home),
      trailheads = listOf(androidOnly, anywhere),
      targetFilter = null,
      platformFilter = platform,
    ).second.map { it.id }

    assertEquals(listOf("launch", "open"), kept("android"))
    assertEquals(listOf("open"), kept("ios"))
  }

  @Test
  fun `one name landing on two destinations keeps both, and identical copies collapse`() {
    val shared = WaypointGraphTrailhead(id = "launch", description = null, to = "app/home")
    val overridden = WaypointGraphTrailhead(id = "launch", description = null, to = "other/home")
    val unique = WaypointGraphTrailhead(id = "solo", description = null, to = "app/home")

    val result = WaypointGraphBuilder.disambiguateTrailheads(listOf(shared, shared, overridden, unique))

    assertEquals(
      listOf("launch → app/home" to "app/home", "launch → other/home" to "other/home", "solo" to "app/home"),
      result.map { it.id to it.to },
    )
  }

  private fun assumeAnalyzerAvailable() {
    assumeTrue(
      MetaOnlyDescriptorTestFixture.ANALYZER_UNAVAILABLE_SKIP_MESSAGE,
      AnalyzerScriptedToolEnrichment.resolveFromEnvironment() != null,
    )
  }

  private fun buildGraph(): WaypointGraphData = WaypointGraphBuilder.build(
    root = File(workspaceRoot, "no-waypoints-here"),
    fromPath = workspaceRoot.toPath(),
  )

  private fun writeMyAppTrailmap(androidOnlyTrailhead: Boolean = false) {
    val configDir = File(workspaceRoot, "trails/config").apply { mkdirs() }
    val trailmapDir = File(configDir, "trailmaps/myapp").apply { mkdirs() }
    File(configDir, "trailblaze.yaml").writeText("targets:\n  - myapp\n")
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: myapp
      target:
        display_name: myapp
      """.trimIndent() + if (androidOnlyTrailhead) {
        "\n  platforms:\n    android:\n      tools:\n        - myapp_launchHome\n"
      } else {
        "\n  tools:\n    - myapp_launchHome\n"
      },
    )
    File(trailmapDir, "tools").mkdirs()
    val platforms = if (androidOnlyTrailhead) "supportedPlatforms: [\"android\"]," else ""
    File(trailmapDir, "tools/myapp_launchHome.ts").writeText(
      """
      import { trailblaze } from "@trailblaze/scripting";

      /** Launch MyApp onto its home screen. */
      export const myapp_launchHome = trailblaze.tool(
        { requiresContext: true, $platforms trailhead: { to: "myapp/home" } },
        async (_input, ctx) => {
          return "launched";
        },
      );
      """.trimIndent(),
    )
  }
}
