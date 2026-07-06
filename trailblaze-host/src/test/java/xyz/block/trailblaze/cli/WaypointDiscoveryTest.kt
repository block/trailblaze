package xyz.block.trailblaze.cli

import java.io.File
import java.net.URLClassLoader
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.scripting.AnalyzerScriptedToolEnrichment
import xyz.block.trailblaze.scripting.MetaOnlyDescriptorTestFixture

/**
 * Tests for [WaypointDiscovery] — the helper that combines workspace trailmap waypoints,
 * classpath-bundled trailmap waypoints, and the legacy `--root` filesystem walk into a
 * single deduplicated list for the `trailblaze waypoint` CLI commands.
 *
 * Scenarios covered:
 *  - classpath-only trailmap waypoints (no workspace anchor)
 *  - workspace + classpath trailmaps both contribute
 *  - workspace trailmap shadows same-id classpath trailmap waypoint
 *  - `--root` filesystem walk shadowed by same-id trailmap waypoint (trailmap wins)
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
  fun `classpath-only trailmap waypoints surface when no workspace anchor exists`() {
    val classpathRoot = newTempDir()
    addClasspathTrailmap(
      classpathRoot,
      trailmapId = "framework",
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
    addClasspathTrailmap(
      classpathRoot,
      trailmapId = "framework",
      waypoints = mapOf(
        "fw.waypoint.yaml" to "id: \"framework/fw\"\ndescription: \"From classpath.\"",
      ),
    )
    val workspaceRoot = newTempDir()
    addWorkspaceTrailmap(
      workspaceRoot,
      trailmapId = "myapp",
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
  fun `workspace trailmap waypoints shadow same-id classpath trailmap waypoints`() {
    // Both trailmaps declare a waypoint with the same id; the workspace trailmap should win
    // because the workspace trailmap itself wholesale shadows the classpath trailmap of the
    // same id at resolution time. (See TrailblazeResolvedConfig kdoc.)
    val classpathRoot = newTempDir()
    addClasspathTrailmap(
      classpathRoot,
      trailmapId = "shared",
      waypoints = mapOf(
        "ready.waypoint.yaml" to
          "id: \"shared/ready\"\ndescription: \"Classpath version (should be shadowed).\"",
      ),
    )
    val workspaceRoot = newTempDir()
    addWorkspaceTrailmap(
      workspaceRoot,
      trailmapId = "shared",
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
  fun `trailmap waypoint shadows same-id --root filesystem waypoint`() {
    // A user-authored waypoint file under --root with the same id as a trailmap-bundled
    // waypoint must NOT replace the trailmap version. The CLI's "first match wins"
    // semantics put trailmap waypoints first; the filesystem walk only contributes ids
    // that aren't already covered.
    val classpathRoot = newTempDir()
    addClasspathTrailmap(
      classpathRoot,
      trailmapId = "shared",
      waypoints = mapOf(
        "ready.waypoint.yaml" to
          "id: \"shared/ready\"\ndescription: \"Trailmap version.\"",
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
    assertEquals("Trailmap version.", waypoint.description)
  }

  @Test
  fun `--root filesystem waypoints with unique ids surface alongside trailmap waypoints`() {
    val classpathRoot = newTempDir()
    addClasspathTrailmap(
      classpathRoot,
      trailmapId = "framework",
      waypoints = mapOf(
        "fw.waypoint.yaml" to "id: \"framework/fw\"\ndescription: \"From trailmap.\"",
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
    addClasspathTrailmap(
      classpathRoot,
      trailmapId = "framework",
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
      "trailmaps:\n  -- not yaml --\n: malformed",
    )
    val emptyRoot = File(workspaceRoot, "trails-not-here")

    val result = withClasspathRoot(classpathRoot) {
      WaypointDiscovery.discover(root = emptyRoot, fromPath = workspaceRoot.toPath())
    }

    // Bundled clock waypoint still loads despite the broken workspace config.
    assertEquals(listOf("framework/ready"), result.definitions.map(WaypointDefinition::id))
  }

  @Test
  fun `trailmapLoadFailed is true when workspace config is malformed`() {
    val classpathRoot = newTempDir()
    addClasspathTrailmap(
      classpathRoot,
      trailmapId = "framework",
      waypoints = mapOf(
        "ready.waypoint.yaml" to "id: \"framework/ready\"\ndescription: \"Bundled.\"",
      ),
    )
    val workspaceRoot = newTempDir()
    val configDir = File(workspaceRoot, "trails/config").apply { mkdirs() }
    File(configDir, "trailblaze.yaml").writeText(
      "trailmaps:\n  -- not yaml --\n: malformed",
    )
    val emptyRoot = File(workspaceRoot, "trails-not-here")

    val result = withClasspathRoot(classpathRoot) {
      WaypointDiscovery.discover(root = emptyRoot, fromPath = workspaceRoot.toPath())
    }

    // Bundled framework waypoint still surfaces — but the flag should be set so the
    // CLI can hint that some trailmaps failed (otherwise the user staring at the result
    // wouldn't know there was a typed error logged above).
    assertEquals(listOf("framework/ready"), result.definitions.map(WaypointDefinition::id))
    assertTrue(result.trailmapLoadFailed, "trailmapLoadFailed should be true when workspace YAML is malformed")
  }

  @Test
  fun `trailmapLoadFailed is false when everything loads successfully`() {
    val classpathRoot = newTempDir()
    addClasspathTrailmap(
      classpathRoot,
      trailmapId = "framework",
      waypoints = mapOf(
        "ready.waypoint.yaml" to "id: \"framework/ready\"\ndescription: \"Bundled.\"",
      ),
    )
    val workspaceRoot = newTempDir()
    val emptyRoot = File(workspaceRoot, "trails-not-here")

    val result = withClasspathRoot(classpathRoot) {
      WaypointDiscovery.discover(root = emptyRoot, fromPath = workspaceRoot.toPath())
    }

    assertTrue(!result.trailmapLoadFailed, "trailmapLoadFailed should be false on a clean load")
  }

  @Test
  fun `discover with --root pointing at a regular file still surfaces trailmap waypoints`() {
    val classpathRoot = newTempDir()
    addClasspathTrailmap(
      classpathRoot,
      trailmapId = "framework",
      waypoints = mapOf(
        "ready.waypoint.yaml" to "id: \"framework/ready\"\ndescription: \"Bundled.\"",
      ),
    )
    val workspaceRoot = newTempDir()
    // --root pointing at a regular file is a user error (`--root some-trail.yaml`
    // instead of a directory). The CLI should still surface trailmap waypoints — the
    // misuse only affects the filesystem-walk contributor — but log a visible
    // warning so the user notices.
    val rootAsFile = File(workspaceRoot, "definitely-a-file.txt").apply {
      writeText("not a directory")
    }

    val result = withClasspathRoot(classpathRoot) {
      WaypointDiscovery.discover(root = rootAsFile, fromPath = workspaceRoot.toPath())
    }

    // Trailmap waypoints unaffected by the --root misuse.
    assertEquals(listOf("framework/ready"), result.definitions.map(WaypointDefinition::id))
  }

  @Test
  fun `scripted-tool workspace trailmap waypoints surface once analyzer enrichment is wired in`() {
    // Regression test for https://github.com/block/trailblaze/issues/196:
    // `WaypointDiscovery`'s two trailmap-load call sites
    // used to call TrailblazeProjectConfigLoader without `scriptedToolEnrichment`, so any
    // workspace trailmap carrying a `.ts` scripted-tool descriptor threw during resolution
    // and its waypoints were silently dropped (even though `trailblaze check`/`run` resolved
    // the same trailmap fine via `AnalyzerScriptedToolEnrichment.resolveFromEnvironment()`).
    val enrichment = AnalyzerScriptedToolEnrichment.resolveFromEnvironment()
    assumeTrue(MetaOnlyDescriptorTestFixture.ANALYZER_UNAVAILABLE_SKIP_MESSAGE, enrichment != null)

    val classpathRoot = newTempDir()
    val workspaceRoot = newTempDir()
    addWorkspaceScriptedToolTrailmap(workspaceRoot, trailmapId = "myapp")
    val emptyRoot = File(workspaceRoot, "trails-not-here")

    val result = withClasspathRoot(classpathRoot) {
      WaypointDiscovery.discover(root = emptyRoot, fromPath = workspaceRoot.toPath())
    }

    assertEquals(listOf("myapp/ready"), result.definitions.map(WaypointDefinition::id))
    assertTrue(
      !result.trailmapLoadFailed,
      "a workspace trailmap with a scripted (.ts) tool should resolve cleanly, not be " +
        "dropped for lack of wired-in analyzer enrichment",
    )
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

  /** Drops a fake framework trailmap at `<root>/trails/config/trailmaps/<trailmapId>/...`. */
  private fun addClasspathTrailmap(
    root: File,
    trailmapId: String,
    waypoints: Map<String, String>,
  ) {
    val trailmapDir = File(root, "trails/config/trailmaps/$trailmapId").apply { mkdirs() }
    val waypointDir = File(trailmapDir, "waypoints").apply { mkdirs() }
    val waypointRefs = waypoints.keys.joinToString("\n") { "  - waypoints/$it" }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: $trailmapId
      target:
        display_name: $trailmapId
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
   * (the convention enforced by `findWorkspaceRoot`) and the trailmap itself at
   * `<workspaceRoot>/trails/config/trailmaps/<trailmapId>/trailmap.yaml`.
   */
  private fun addWorkspaceTrailmap(
    workspaceRoot: File,
    trailmapId: String,
    waypoints: Map<String, String>,
  ) {
    val configDir = File(workspaceRoot, "trails/config").apply { mkdirs() }
    val trailmapDir = File(configDir, "trailmaps/$trailmapId").apply { mkdirs() }
    val waypointDir = File(trailmapDir, "waypoints").apply { mkdirs() }
    val waypointRefs = waypoints.keys.joinToString("\n") { "  - waypoints/$it" }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: $trailmapId
      target:
        display_name: $trailmapId
      waypoints:
      $waypointRefs
      """.trimIndent(),
    )
    waypoints.forEach { (filename, content) ->
      File(waypointDir, filename).writeText(content)
    }
    val workspaceConfig = File(configDir, "trailblaze.yaml")
    val existingTrailmaps = if (workspaceConfig.isFile) {
      workspaceConfig.readText().lines().filter { it.trimStart().startsWith("- ") }
    } else {
      emptyList()
    }
    val trailmapsBlock = (existingTrailmaps + listOf("  - trailmaps/$trailmapId/trailmap.yaml"))
      .joinToString("\n")
    workspaceConfig.writeText("trailmaps:\n$trailmapsBlock\n")
  }

  /**
   * Drops a workspace trailmap at `<workspaceRoot>/trails/config/trailmaps/<trailmapId>/`
   * carrying one meta-only scripted (`.ts`) tool plus one waypoint, and wires it into
   * `trailblaze.yaml`. Mirrors [MetaOnlyDescriptorTestFixture]'s authoring shape but adds a
   * `waypoints:` entry, since that fixture (shared with [CompileCommandTest]) has no need
   * for waypoints of its own.
   */
  private fun addWorkspaceScriptedToolTrailmap(workspaceRoot: File, trailmapId: String) {
    val configDir = File(workspaceRoot, "trails/config").apply { mkdirs() }
    val trailmapDir = File(configDir, "trailmaps/$trailmapId").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: $trailmapId
      target:
        display_name: $trailmapId
        tools:
          - sampleTool
      waypoints:
        - waypoints/ready.waypoint.yaml
      """.trimIndent(),
    )
    val toolsDir = File(trailmapDir, "tools").apply { mkdirs() }
    File(toolsDir, "sampleTool.yaml").writeText(
      """
      script: ./sampleTool.ts
      _meta:
        trailblaze/requiresContext: true
      """.trimIndent(),
    )
    File(toolsDir, "sampleTool.ts").writeText(
      """
      import { trailblaze } from "@trailblaze/scripting";

      export interface SampleArgs {
        who: string;
      }

      export const sampleTool = trailblaze.tool<SampleArgs>(async (input) => {
        return `hello, ${'$'}{input.who}`;
      });
      """.trimIndent(),
    )
    val waypointDir = File(trailmapDir, "waypoints").apply { mkdirs() }
    File(waypointDir, "ready.waypoint.yaml").writeText(
      "id: \"$trailmapId/ready\"\ndescription: \"Ready.\"",
    )
    File(configDir, "trailblaze.yaml").writeText(
      "trailmaps:\n  - trailmaps/$trailmapId/trailmap.yaml\n",
    )
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
