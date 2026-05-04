package xyz.block.trailblaze.cli

import java.io.File
import java.net.URLClassLoader
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.waypoint.WaypointDefinition

/**
 * Tests for [WaypointDiscovery] — the helper that combines workspace pack waypoints,
 * classpath-bundled pack waypoints, and the legacy `--root` filesystem walk into a
 * single deduplicated list for the `trailblaze waypoint` CLI commands.
 *
 * Scenarios covered:
 *  - classpath-only pack waypoints (no workspace anchor)
 *  - workspace + classpath packs both contribute
 *  - workspace pack shadows same-id classpath pack waypoint
 *  - `--root` filesystem walk shadowed by same-id pack waypoint (pack wins)
 *  - malformed workspace `trailblaze.yaml` falls back to classpath-only
 *  - empty everything → empty result
 *
 * Each test sets up an isolated classpath via [URLClassLoader] and an isolated
 * workspace via a temp directory, so production code's actual classpath and cwd
 * never bleed into the test results.
 */
class WaypointDiscoveryTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanupTempDirs() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `classpath-only pack waypoints surface when no workspace anchor exists`() {
    val classpathRoot = newTempDir()
    addClasspathPack(
      classpathRoot,
      packId = "framework",
      waypoints = mapOf(
        "ready.waypoint.yaml" to "id: \"framework/ready\"\ndescription: \"Framework ready.\"",
      ),
    )
    val workspaceRoot = newTempDir()
    val emptyRoot = File(workspaceRoot, "trails-not-here")

    val result = withClasspathRoot(classpathRoot) {
      WaypointDiscovery.discover(root = emptyRoot, fromPath = workspaceRoot.toPath())
    }

    assertEquals(listOf("framework/ready"), result.definitions.map(WaypointDefinition::id))
  }

  @Test
  fun `workspace and classpath waypoints both surface when ids do not collide`() {
    val classpathRoot = newTempDir()
    addClasspathPack(
      classpathRoot,
      packId = "framework",
      waypoints = mapOf(
        "fw.waypoint.yaml" to "id: \"framework/fw\"\ndescription: \"From classpath.\"",
      ),
    )
    val workspaceRoot = newTempDir()
    addWorkspacePack(
      workspaceRoot,
      packId = "myapp",
      waypoints = mapOf(
        "app.waypoint.yaml" to "id: \"myapp/app\"\ndescription: \"From workspace.\"",
      ),
    )
    val emptyRoot = File(workspaceRoot, "trails-not-here")

    val result = withClasspathRoot(classpathRoot) {
      WaypointDiscovery.discover(root = emptyRoot, fromPath = workspaceRoot.toPath())
    }

    val ids = result.definitions.map(WaypointDefinition::id).toSet()
    assertEquals(setOf("framework/fw", "myapp/app"), ids)
  }

  @Test
  fun `workspace pack waypoints shadow same-id classpath pack waypoints`() {
    // Both packs declare a waypoint with the same id; the workspace pack should win
    // because the workspace pack itself wholesale shadows the classpath pack of the
    // same id at resolution time. (See TrailblazeResolvedConfig kdoc.)
    val classpathRoot = newTempDir()
    addClasspathPack(
      classpathRoot,
      packId = "shared",
      waypoints = mapOf(
        "ready.waypoint.yaml" to
          "id: \"shared/ready\"\ndescription: \"Classpath version (should be shadowed).\"",
      ),
    )
    val workspaceRoot = newTempDir()
    addWorkspacePack(
      workspaceRoot,
      packId = "shared",
      waypoints = mapOf(
        "ready.waypoint.yaml" to
          "id: \"shared/ready\"\ndescription: \"Workspace version (should win).\"",
      ),
    )
    val emptyRoot = File(workspaceRoot, "trails-not-here")

    val result = withClasspathRoot(classpathRoot) {
      WaypointDiscovery.discover(root = emptyRoot, fromPath = workspaceRoot.toPath())
    }

    val waypoint = result.definitions.single()
    assertEquals("shared/ready", waypoint.id)
    assertEquals("Workspace version (should win).", waypoint.description)
  }

  @Test
  fun `pack waypoint shadows same-id --root filesystem waypoint`() {
    // A user-authored waypoint file under --root with the same id as a pack-bundled
    // waypoint must NOT replace the pack version. The CLI's "first match wins"
    // semantics put pack waypoints first; the filesystem walk only contributes ids
    // that aren't already covered.
    val classpathRoot = newTempDir()
    addClasspathPack(
      classpathRoot,
      packId = "shared",
      waypoints = mapOf(
        "ready.waypoint.yaml" to
          "id: \"shared/ready\"\ndescription: \"Pack version.\"",
      ),
    )
    val workspaceRoot = newTempDir()
    val rootDir = File(workspaceRoot, "trails").apply { mkdirs() }
    File(rootDir, "user-shared-ready.waypoint.yaml").writeText(
      "id: \"shared/ready\"\ndescription: \"User-authored shadow (should be ignored).\"",
    )

    val result = withClasspathRoot(classpathRoot) {
      WaypointDiscovery.discover(root = rootDir, fromPath = workspaceRoot.toPath())
    }

    val waypoint = result.definitions.single()
    assertEquals("Pack version.", waypoint.description)
  }

  @Test
  fun `--root filesystem waypoints with unique ids surface alongside pack waypoints`() {
    val classpathRoot = newTempDir()
    addClasspathPack(
      classpathRoot,
      packId = "framework",
      waypoints = mapOf(
        "fw.waypoint.yaml" to "id: \"framework/fw\"\ndescription: \"From pack.\"",
      ),
    )
    val workspaceRoot = newTempDir()
    val rootDir = File(workspaceRoot, "trails").apply { mkdirs() }
    File(rootDir, "scratch.waypoint.yaml").writeText(
      "id: \"scratch/user\"\ndescription: \"Hand-authored waypoint.\"",
    )

    val result = withClasspathRoot(classpathRoot) {
      WaypointDiscovery.discover(root = rootDir, fromPath = workspaceRoot.toPath())
    }

    val ids = result.definitions.map(WaypointDefinition::id).toSet()
    assertEquals(setOf("framework/fw", "scratch/user"), ids)
  }

  @Test
  fun `malformed workspace trailblaze yaml falls back to classpath-only waypoints`() {
    val classpathRoot = newTempDir()
    addClasspathPack(
      classpathRoot,
      packId = "framework",
      waypoints = mapOf(
        "ready.waypoint.yaml" to "id: \"framework/ready\"\ndescription: \"Bundled.\"",
      ),
    )
    val workspaceRoot = newTempDir()
    // Create the workspace anchor with intentionally-broken YAML so the loader
    // throws TrailblazeProjectConfigException. WaypointDiscovery must catch the
    // typed error, log a warning, and fall back to classpath-only resolution.
    val configDir = File(workspaceRoot, "trails/config").apply { mkdirs() }
    File(configDir, "trailblaze.yaml").writeText(
      "packs:\n  -- not yaml --\n: malformed",
    )
    val emptyRoot = File(workspaceRoot, "trails-not-here")

    val result = withClasspathRoot(classpathRoot) {
      WaypointDiscovery.discover(root = emptyRoot, fromPath = workspaceRoot.toPath())
    }

    // Bundled clock waypoint still loads despite the broken workspace config.
    assertEquals(listOf("framework/ready"), result.definitions.map(WaypointDefinition::id))
  }

  @Test
  fun `packLoadFailed is true when workspace config is malformed`() {
    val classpathRoot = newTempDir()
    addClasspathPack(
      classpathRoot,
      packId = "framework",
      waypoints = mapOf(
        "ready.waypoint.yaml" to "id: \"framework/ready\"\ndescription: \"Bundled.\"",
      ),
    )
    val workspaceRoot = newTempDir()
    val configDir = File(workspaceRoot, "trails/config").apply { mkdirs() }
    File(configDir, "trailblaze.yaml").writeText(
      "packs:\n  -- not yaml --\n: malformed",
    )
    val emptyRoot = File(workspaceRoot, "trails-not-here")

    val result = withClasspathRoot(classpathRoot) {
      WaypointDiscovery.discover(root = emptyRoot, fromPath = workspaceRoot.toPath())
    }

    // Bundled framework waypoint still surfaces — but the flag should be set so the
    // CLI can hint that some packs failed (otherwise the user staring at the result
    // wouldn't know there was a typed error logged above).
    assertEquals(listOf("framework/ready"), result.definitions.map(WaypointDefinition::id))
    assertTrue(result.packLoadFailed, "packLoadFailed should be true when workspace YAML is malformed")
  }

  @Test
  fun `packLoadFailed is false when everything loads successfully`() {
    val classpathRoot = newTempDir()
    addClasspathPack(
      classpathRoot,
      packId = "framework",
      waypoints = mapOf(
        "ready.waypoint.yaml" to "id: \"framework/ready\"\ndescription: \"Bundled.\"",
      ),
    )
    val workspaceRoot = newTempDir()
    val emptyRoot = File(workspaceRoot, "trails-not-here")

    val result = withClasspathRoot(classpathRoot) {
      WaypointDiscovery.discover(root = emptyRoot, fromPath = workspaceRoot.toPath())
    }

    assertTrue(!result.packLoadFailed, "packLoadFailed should be false on a clean load")
  }

  @Test
  fun `discover with --root pointing at a regular file still surfaces pack waypoints`() {
    val classpathRoot = newTempDir()
    addClasspathPack(
      classpathRoot,
      packId = "framework",
      waypoints = mapOf(
        "ready.waypoint.yaml" to "id: \"framework/ready\"\ndescription: \"Bundled.\"",
      ),
    )
    val workspaceRoot = newTempDir()
    // --root pointing at a regular file is a user error (`--root some-trail.yaml`
    // instead of a directory). The CLI should still surface pack waypoints — the
    // misuse only affects the filesystem-walk contributor — but log a visible
    // warning so the user notices.
    val rootAsFile = File(workspaceRoot, "definitely-a-file.txt").apply {
      writeText("not a directory")
    }

    val result = withClasspathRoot(classpathRoot) {
      WaypointDiscovery.discover(root = rootAsFile, fromPath = workspaceRoot.toPath())
    }

    // Pack waypoints unaffected by the --root misuse.
    assertEquals(listOf("framework/ready"), result.definitions.map(WaypointDefinition::id))
  }

  @Test
  fun `empty classpath and empty root yields empty result`() {
    val classpathRoot = newTempDir()
    val workspaceRoot = newTempDir()
    val emptyRoot = File(workspaceRoot, "trails-not-here")

    val result = withClasspathRoot(classpathRoot) {
      WaypointDiscovery.discover(root = emptyRoot, fromPath = workspaceRoot.toPath())
    }

    assertTrue(result.definitions.isEmpty())
  }

  // ==========================================================================
  // Test infrastructure.
  // ==========================================================================

  /** Drops a fake framework pack at `<root>/trailblaze-config/packs/<packId>/...`. */
  private fun addClasspathPack(
    root: File,
    packId: String,
    waypoints: Map<String, String>,
  ) {
    val packDir = File(root, "trailblaze-config/packs/$packId").apply { mkdirs() }
    val waypointDir = File(packDir, "waypoints").apply { mkdirs() }
    val waypointRefs = waypoints.keys.joinToString("\n") { "  - waypoints/$it" }
    File(packDir, "pack.yaml").writeText(
      """
      id: $packId
      target:
        display_name: $packId
      waypoints:
      $waypointRefs
      """.trimIndent(),
    )
    waypoints.forEach { (filename, content) ->
      File(waypointDir, filename).writeText(content)
    }
  }

  /**
   * Drops the workspace anchor at `<workspaceRoot>/trails/config/trailblaze.yaml`
   * (the convention enforced by `findWorkspaceRoot`) and the pack itself at
   * `<workspaceRoot>/trails/config/packs/<packId>/pack.yaml`.
   */
  private fun addWorkspacePack(
    workspaceRoot: File,
    packId: String,
    waypoints: Map<String, String>,
  ) {
    val configDir = File(workspaceRoot, "trails/config").apply { mkdirs() }
    val packDir = File(configDir, "packs/$packId").apply { mkdirs() }
    val waypointDir = File(packDir, "waypoints").apply { mkdirs() }
    val waypointRefs = waypoints.keys.joinToString("\n") { "  - waypoints/$it" }
    File(packDir, "pack.yaml").writeText(
      """
      id: $packId
      target:
        display_name: $packId
      waypoints:
      $waypointRefs
      """.trimIndent(),
    )
    waypoints.forEach { (filename, content) ->
      File(waypointDir, filename).writeText(content)
    }
    val workspaceConfig = File(configDir, "trailblaze.yaml")
    val existingPacks = if (workspaceConfig.isFile) {
      workspaceConfig.readText().lines().filter { it.trimStart().startsWith("- ") }
    } else {
      emptyList()
    }
    val packsBlock = (existingPacks + listOf("  - packs/$packId/pack.yaml"))
      .joinToString("\n")
    workspaceConfig.writeText("packs:\n$packsBlock\n")
  }

  private fun newTempDir(): File =
    createTempDirectory("waypoint-discovery-test").toFile().also { tempDirs += it }

  private fun <T> withClasspathRoot(root: File, block: () -> T): T {
    val classLoader = URLClassLoader(arrayOf(root.toURI().toURL()), null)
    val originalCcl = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = classLoader
    try {
      return block()
    } finally {
      Thread.currentThread().contextClassLoader = originalCcl
      classLoader.close()
    }
  }
}
