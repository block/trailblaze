package xyz.block.trailblaze.waypoint

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
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
  private fun writeWorkspace(root: File): File {
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
      """.trimIndent(),
    )
    File(File(trailmapDir, "waypoints").apply { mkdirs() }, "home.waypoint.yaml").writeText(
      """
      id: "sampleapp/home"
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
