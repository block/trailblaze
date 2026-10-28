package xyz.block.trailblaze.waypoint

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pins the observable contract of [WaypointRegistryResolver]: it is process-cached (a second call
 * for the same working directory returns the same resolver instance rather than rebuilding), an id
 * the registry doesn't contain resolves to null, and — the point of #4279 — an app waypoint declared
 * in the active workspace's `trailblaze.yaml` resolves (not just classpath-bundled framework ones).
 * The full match behavior is covered by `WaypointAssertionTest` / `WaypointMatcherTest`.
 */
class WaypointRegistryResolverTest {

  @AfterTest
  fun tearDown() {
    // Don't leak the cached resolver or a test-installed provider into other tests in this JVM.
    WaypointRegistryResolver.clearCache()
    WaypointRegistryResolver.workspaceConfigDirProvider = { null }
  }

  /** Writes a minimal workspace (trailblaze.yaml + one app trailmap declaring one waypoint). */
  private fun writeWorkspace(root: File, waypointId: String = "sampleapp/home", targetId: String? = null): File {
    val configDir = File(root, "trails/config").apply { mkdirs() }
    File(configDir, "trailblaze.yaml").writeText("targets:\n  - sampleapp\n")
    val trailmapDir = File(configDir, "trailmaps/sampleapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sampleapp
      target:
        display_name: Sample App
      waypoints:
        - waypoints/home.waypoint.yaml
      """.trimIndent().let { yaml -> targetId?.let { yaml.replace("target:\n", "target:\n  id: $it\n") } ?: yaml },
    )
    File(File(trailmapDir, "waypoints").apply { mkdirs() }, "home.waypoint.yaml").writeText(
      """
      id: "$waypointId"
      description: "App waypoint that only resolves via the workspace config."
      """.trimIndent(),
    )
    return configDir
  }

  @Test
  fun `resolver is cached per process - second call returns the same instance`() {
    WaypointRegistryResolver.clearCache()
    val first = WaypointRegistryResolver.resolver()
    val second = WaypointRegistryResolver.resolver()
    // getOrPut returns the same closure; rebuilding would re-walk trailmaps (and, with enrichment
    // installed, re-run the analyzer subprocess) on every assertWaypoint call.
    assertSame(first, second, "resolver() must return the cached instance for the same working dir")
  }

  @Test
  fun `unknown waypoint id resolves to null`() {
    WaypointRegistryResolver.clearCache()
    val resolver = WaypointRegistryResolver.resolver()
    assertNull(
      resolver("definitely/not/a/real/waypoint/id"),
      "an id absent from the registry must resolve to null (assertWaypoint renders this as unknown)",
    )
  }

  @Test
  fun `resolves an app waypoint via the working-dir walk-up`() {
    // #4279 regression: an app/workspace-declared waypoint (not a classpath-bundled framework one)
    // must resolve. The resolver walks up from the working dir to `trails/config/trailblaze.yaml`,
    // whose declared `targets:` pull the app trailmap (and its waypoints) into scope. Before the
    // fix — with an empty TrailblazeProjectConfig() — this returned null (WaypointNotFound).
    val root = createTempDirectory(prefix = "wp-registry-walkup-").toFile()
    try {
      writeWorkspace(root)
      val resolver = WaypointRegistryResolver.build(root)
      assertNotNull(
        resolver("sampleapp/home"),
        "an app waypoint declared in the workspace trailblaze.yaml must resolve via walk-up (#4279)",
      )
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `selecting another workspace in the same process resolves that workspace's waypoints`() {
    val first = createTempDirectory(prefix = "wp-registry-switch-a-").toFile()
    val second = createTempDirectory(prefix = "wp-registry-switch-b-").toFile()
    try {
      val firstConfig = writeWorkspace(first, waypointId = "sampleapp/home")
      val secondConfig = writeWorkspace(second, waypointId = "sampleapp/settings")
      WaypointRegistryResolver.workspaceConfigDirProvider = { firstConfig }
      assertNotNull(WaypointRegistryResolver.resolver()("sampleapp/home"))

      WaypointRegistryResolver.workspaceConfigDirProvider = { secondConfig }
      val resolver = WaypointRegistryResolver.resolver()
      assertNotNull(resolver("sampleapp/settings"), "the newly selected workspace's waypoint must resolve")
      assertNull(resolver("sampleapp/home"), "the previous workspace's waypoint must not linger")
    } finally {
      first.deleteRecursively()
      second.deleteRecursively()
    }
  }

  @Test
  fun `a selected workspace that fails to load is reported unloaded, then picked up once it recovers`() {
    val root = createTempDirectory(prefix = "wp-registry-broken-").toFile()
    try {
      val configDir = writeWorkspace(root, waypointId = "sampleapp/settings")
      val manifest = File(configDir, "trailblaze.yaml")
      val good = manifest.readText()
      manifest.writeText("targets: [unclosed")
      WaypointRegistryResolver.workspaceConfigDirProvider = { configDir }
      assertFailsWith<IllegalStateException> { WaypointRegistryResolver.forTarget("sampleapp") }

      manifest.writeText(good)
      assertNotNull(WaypointRegistryResolver.resolver()("sampleapp/settings"))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `a target's waypoints are its trailmap's, even when the trailmap gives the target another id`() {
    val root = createTempDirectory(prefix = "wp-registry-target-id-").toFile()
    try {
      val configDir = writeWorkspace(root, waypointId = "sampleapp/home", targetId = "sample-pos")
      WaypointRegistryResolver.workspaceConfigDirProvider = { configDir }
      assertEquals(listOf("sampleapp/home"), WaypointRegistryResolver.forTarget("sample-pos").map { it.id })
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `resolves an app waypoint via the active-workspace holder even when the working dir is not a workspace`() {
    // Honors a workspace selected in the desktop app / Trail Runner, which installs the active
    // config dir via WorkspaceConfigDirHolder WITHOUT changing the JVM cwd. Build from an unrelated
    // empty dir (no workspace to walk up to) and point the holder-backed provider at the fixture —
    // resolution must come from the provider, not the working dir.
    val workspaceRoot = createTempDirectory(prefix = "wp-registry-holder-ws-").toFile()
    val unrelatedWorkingDir = createTempDirectory(prefix = "wp-registry-holder-cwd-").toFile()
    try {
      val configDir = writeWorkspace(workspaceRoot)
      WaypointRegistryResolver.workspaceConfigDirProvider = { configDir }
      val resolver = WaypointRegistryResolver.build(unrelatedWorkingDir)
      assertNotNull(
        resolver("sampleapp/home"),
        "an app waypoint must resolve from the holder-provided workspace, not the working dir (#4279)",
      )
    } finally {
      workspaceRoot.deleteRecursively()
      unrelatedWorkingDir.deleteRecursively()
    }
  }

  @Test
  fun `falls back to the working-dir walk-up when the holder dir has no trailblaze_yaml`() {
    // The 3-tier chain must fall through: holder returns a dir with no `trailblaze.yaml`, so tier 1
    // yields nothing and tier 2 (working-dir walk-up) resolves. Guards the `?:` fallback ordering.
    val cwdWorkspace = createTempDirectory(prefix = "wp-registry-fallback-cwd-").toFile()
    val emptyHolderDir = createTempDirectory(prefix = "wp-registry-fallback-holder-").toFile()
    try {
      writeWorkspace(cwdWorkspace)
      WaypointRegistryResolver.workspaceConfigDirProvider = { emptyHolderDir }
      val resolver = WaypointRegistryResolver.build(cwdWorkspace)
      assertNotNull(
        resolver("sampleapp/home"),
        "a holder dir without trailblaze.yaml must fall through to the working-dir walk-up",
      )
    } finally {
      cwdWorkspace.deleteRecursively()
      emptyHolderDir.deleteRecursively()
    }
  }
}
