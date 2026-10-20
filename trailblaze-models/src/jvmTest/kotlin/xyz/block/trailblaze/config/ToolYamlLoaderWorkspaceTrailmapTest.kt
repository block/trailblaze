package xyz.block.trailblaze.config

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.llm.config.ClasspathConfigResourceSource
import xyz.block.trailblaze.llm.config.CompositeConfigResourceSource
import xyz.block.trailblaze.llm.config.FilesystemConfigResourceSource
import xyz.block.trailblaze.toolcalls.ToolName

/**
 * Pins workspace-trailmap tool discovery via [FilesystemConfigResourceSource] passed
 * to [ToolYamlLoader.discoverTrailheads]. Workspace trailmaps live on the
 * filesystem at `<workspace>/trails/config/trailmaps/<id>/{tools,trailheads}/`,
 * not on the classpath, so without a filesystem-aware resource source the loader
 * silently misses them and their trailheads never appear in the standalone CLI graph export.
 *
 * The classpath path is resource-source-aware as of #2802 and routes through
 * [ConfigResourceSource.discoverAndLoadRecursive], so a [FilesystemConfigResourceSource]
 * at the workspace `trails/config/` root walks trailmap-tool YAMLs the same way classpath
 * discovery does. These tests pin that contract end-to-end: instantiate the source,
 * call the loader, assert the YAMLs surface as `ToolYamlConfig` with the expected
 * trailhead metadata.
 *
 * Library-trailmap trailhead guard is symmetric with the classpath path
 * ([ToolYamlLoaderTrailmapBundledTest.`library trailmap trailhead tool is skipped at discovery time`]):
 * a workspace trailmap with no `target:` block in `trailmap.yaml` cannot ship `*.trailhead.yaml`.
 */
class ToolYamlLoaderWorkspaceTrailmapTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `workspace-trailmap trailhead in nested subdirectory surfaces`() {
    // The workspace path uses listSiblingsRecursive, so a `.trailhead.yaml` placed under
    // a subdir like `<trailmap>/trailheads/web/` must register the same as a flat-`trailheads/`
    // file. Mirrors the classpath-side `trailmap-bundled YAML at multiple depths under tools
    // subdirectory all register` test in ToolYamlLoaderTrailmapBundledTest. Without this
    // pinning, a regression to non-recursive `listSiblings` would silently skip
    // platform-grouped trailheads (a target trailmap's `trailheads/{android,web}/...` layout
    // when loaded as a workspace trailmap).
    val configDir = newTempDir()
    val trailmapDir = trailmapsRoot(configDir, "myapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: myapp
      target:
        display_name: MyApp
      """.trimIndent(),
    )
    val nestedTrailheadsDir = File(trailmapDir, "trailheads/web").apply { mkdirs() }
    File(nestedTrailheadsDir, "open_dashboard.trailhead.yaml").writeText(
      """
      id: myapp_web_open_dashboard
      description: Open the MyApp dashboard.
      trailhead:
        to: myapp/web/dashboard
      tools:
        - tap: { selector: "dashboard_link" }
      """.trimIndent(),
    )

    val resolved = ToolYamlLoader.discoverTrailheads(
      resourceSource = FilesystemConfigResourceSource(rootDir = configDir),
    )
    assertTrue(
      ToolName("myapp_web_open_dashboard") in resolved,
      "Expected nested workspace-trailmap trailhead to surface, got: ${resolved.keys}",
    )
  }

  @Test
  fun `workspace-trailmap trailhead in target trailmap surfaces`() {
    val configDir = newTempDir()
    val trailmapDir = trailmapsRoot(configDir, "calendar").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: calendar
      target:
        display_name: Calendar
      """.trimIndent(),
    )
    val trailheadsDir = File(trailmapDir, "trailheads").apply { mkdirs() }
    File(trailheadsDir, "launch.trailhead.yaml").writeText(
      """
      id: calendar_launchApp
      class: xyz.block.trailblaze.config.FakeToolYamlLoaderTool
      trailhead:
        to: calendar/day-view
      """.trimIndent(),
    )

    val resolved = ToolYamlLoader.discoverTrailheads(
      resourceSource = FilesystemConfigResourceSource(rootDir = configDir),
    )
    assertTrue(
      ToolName("calendar_launchApp") in resolved,
      "Expected target-trailmap trailhead to surface, got: ${resolved.keys}",
    )
    assertEquals("calendar/day-view", resolved[ToolName("calendar_launchApp")]?.trailhead?.to)
  }

  @Test
  fun `workspace-trailmap trailhead in library trailmap is skipped`() {
    // Symmetric with the classpath-side library-trailmap trailhead guard. A workspace trailmap
    // with no `target:` in `trailmap.yaml` is a library trailmap; trailheads bootstrap to a
    // known waypoint and only make sense inside a target trailmap.
    val configDir = newTempDir()
    val trailmapDir = trailmapsRoot(configDir, "shared-utils").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: shared-utils
      """.trimIndent(),
    )
    val trailheadsDir = File(trailmapDir, "trailheads").apply { mkdirs() }
    File(trailheadsDir, "go_home.trailhead.yaml").writeText(
      """
      id: shared_go_home
      class: xyz.block.trailblaze.config.FakeToolYamlLoaderTool
      trailhead:
        to: app/home
      """.trimIndent(),
    )

    val resolved = ToolYamlLoader.discoverTrailheads(
      resourceSource = FilesystemConfigResourceSource(rootDir = configDir),
    )
    assertFalse(
      ToolName("shared_go_home") in resolved,
      "Library-trailmap trailhead must not register; got: ${resolved.keys}",
    )
  }

  @Test
  fun `composite source merges classpath and filesystem trailmap tools`() {
    // The graph builder constructs a `CompositeConfigResourceSource(classpath + filesystem)`
    // so framework-classpath trailmaps and workspace trailmaps both surface in the same call.
    // Pin that the composite shape works — a workspace-trailmap tool surfaces alongside whatever
    // the classpath-bundled `trailblaze` library trailmap contributes, with no collisions.
    val configDir = newTempDir()
    val trailmapDir = trailmapsRoot(configDir, "calendar").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: calendar
      target:
        display_name: Calendar
      """.trimIndent(),
    )
    val trailheadsDir = File(trailmapDir, "trailheads").apply { mkdirs() }
    File(trailheadsDir, "open_event.trailhead.yaml").writeText(
      """
      id: open_event_composite
      description: Composite test trailhead.
      trailhead:
        to: calendar/event-composer
      tools:
        - tap: { selector: "day_cell" }
      """.trimIndent(),
    )

    val composite = CompositeConfigResourceSource(
      sources = listOf(
        ClasspathConfigResourceSource,
        FilesystemConfigResourceSource(rootDir = configDir),
      ),
    )
    val resolved = ToolYamlLoader.discoverTrailheads(resourceSource = composite)
    assertTrue(
      ToolName("open_event_composite") in resolved,
      "Composite source must surface filesystem-bundled tools; got: ${resolved.keys}",
    )
  }

  private fun newTempDir(): File =
    createTempDirectory("tool-yaml-loader-workspace-trailmap-test").toFile().also { tempDirs += it }

  /**
   * Workspace trailmaps live at `<configDir>/trailmaps/<id>/`. Mirrors the on-disk layout the
   * graph builder's resource source resolves against — `FilesystemConfigResourceSource`
   * strips the `trails/config/` prefix, so a request for
   * `trails/config/trailmaps/<id>/trailheads/` resolves to `<rootDir>/trailmaps/<id>/trailheads/`.
   */
  private fun trailmapsRoot(configDir: File, trailmapId: String): File =
    File(configDir, "trailmaps/$trailmapId")
}
