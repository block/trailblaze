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
 * to [ToolYamlLoader.discoverShortcutsAndTrailheads]. Workspace trailmaps live on the
 * filesystem at `<workspace>/trails/config/trailmaps/<id>/{tools,shortcuts,trailheads}/`,
 * not on the classpath, so without a filesystem-aware resource source the loader
 * silently misses them — calendar's 67 shortcuts and contacts' 81 shortcuts would
 * never appear as graph edges in the standalone CLI export.
 *
 * The classpath path is resource-source-aware as of #2802 and routes through
 * [ConfigResourceSource.discoverAndLoadRecursive], so a [FilesystemConfigResourceSource]
 * at the workspace `trails/config/` root walks trailmap-tool YAMLs the same way classpath
 * discovery does. These tests pin that contract end-to-end: instantiate the source,
 * call the loader, assert the YAMLs surface as `ToolYamlConfig` with the expected
 * shortcut/trailhead metadata.
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
  fun `workspace-trailmap shortcut surfaces via FilesystemConfigResourceSource`() {
    val configDir = newTempDir()
    val trailmapDir = trailmapsRoot(configDir, "calendar").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: calendar
      target:
        display_name: Calendar
      """.trimIndent(),
    )
    val shortcutsDir = File(trailmapDir, "shortcuts").apply { mkdirs() }
    File(shortcutsDir, "open_event.shortcut.yaml").writeText(
      """
      id: open_event
      description: Tap a day cell to open its event composer.
      shortcut:
        from: calendar/day-view
        to:   calendar/event-composer
      tools:
        - tap: { selector: "day_cell" }
      """.trimIndent(),
    )

    val resolved = ToolYamlLoader.discoverShortcutsAndTrailheads(
      resourceSource = FilesystemConfigResourceSource(rootDir = configDir),
    )
    assertTrue(
      ToolName("open_event") in resolved,
      "Expected workspace-trailmap shortcut to surface, got: ${resolved.keys}",
    )
    assertEquals("calendar/day-view", resolved[ToolName("open_event")]?.shortcut?.from)
  }

  @Test
  fun `workspace-trailmap shortcut in nested subdirectory surfaces`() {
    // The workspace path uses listSiblingsRecursive, so a `.shortcut.yaml` placed under
    // a subdir like `<trailmap>/shortcuts/web/` must register the same as a flat-`shortcuts/`
    // file. Mirrors the classpath-side `trailmap-bundled YAML at multiple depths under tools
    // subdirectory all register` test in ToolYamlLoaderTrailmapBundledTest. Without this
    // pinning, a regression to non-recursive `listSiblings` would silently skip
    // platform-grouped shortcuts (a target trailmap's `shortcuts/{android,web}/...` layout
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
    val nestedShortcutsDir = File(trailmapDir, "shortcuts/web").apply { mkdirs() }
    File(nestedShortcutsDir, "open_dashboard.shortcut.yaml").writeText(
      """
      id: myapp_web_open_dashboard
      description: Open the MyApp dashboard.
      shortcut:
        from: myapp/web/home
        to:   myapp/web/dashboard
      tools:
        - tap: { selector: "dashboard_link" }
      """.trimIndent(),
    )

    val resolved = ToolYamlLoader.discoverShortcutsAndTrailheads(
      resourceSource = FilesystemConfigResourceSource(rootDir = configDir),
    )
    assertTrue(
      ToolName("myapp_web_open_dashboard") in resolved,
      "Expected nested workspace-trailmap shortcut to surface, got: ${resolved.keys}",
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

    val resolved = ToolYamlLoader.discoverShortcutsAndTrailheads(
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
    // A sibling shortcut in the same library trailmap should still surface — the guard is
    // narrow to trailheads, not "library trailmaps can't ship tools at all."
    val shortcutsDir = File(trailmapDir, "shortcuts").apply { mkdirs() }
    File(shortcutsDir, "fade_in.shortcut.yaml").writeText(
      """
      id: shared_fade_in
      description: Fade-in shortcut.
      shortcut:
        from: app/loading
        to:   app/ready
      tools:
        - tap: { selector: "ok" }
      """.trimIndent(),
    )

    val resolved = ToolYamlLoader.discoverShortcutsAndTrailheads(
      resourceSource = FilesystemConfigResourceSource(rootDir = configDir),
    )
    assertFalse(
      ToolName("shared_go_home") in resolved,
      "Library-trailmap trailhead must not register; got: ${resolved.keys}",
    )
    assertTrue(
      ToolName("shared_fade_in") in resolved,
      "Library-trailmap shortcut must still register; got: ${resolved.keys}",
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
    val shortcutsDir = File(trailmapDir, "shortcuts").apply { mkdirs() }
    File(shortcutsDir, "open_event.shortcut.yaml").writeText(
      """
      id: open_event_composite
      description: Composite test shortcut.
      shortcut:
        from: calendar/day-view
        to:   calendar/event-composer
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
    val resolved = ToolYamlLoader.discoverShortcutsAndTrailheads(resourceSource = composite)
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
   * `trails/config/trailmaps/<id>/shortcuts/` resolves to `<rootDir>/trailmaps/<id>/shortcuts/`.
   */
  private fun trailmapsRoot(configDir: File, trailmapId: String): File =
    File(configDir, "trailmaps/$trailmapId")
}
