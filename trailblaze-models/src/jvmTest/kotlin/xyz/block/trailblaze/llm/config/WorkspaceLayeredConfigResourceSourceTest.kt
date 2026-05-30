package xyz.block.trailblaze.llm.config

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the contract on [workspaceLayeredConfigResourceSource]:
 *
 * 1. With a valid workspace `trails/config/`, the returned source layers classpath under the
 *    workspace dir + its `dist/` subdir.
 * 2. With a null or non-directory dir, the source falls back to
 *    [ClasspathConfigResourceSource] so daemons started outside a workspace root keep working.
 * 3. With a valid `config/` but a missing `dist/` (the common pre-`trailblaze compile` state),
 *    the composite still surfaces workspace-authored files instead of degrading to classpath-only.
 *
 * The composite-source mechanics (precedence, key collision) are covered by
 * [CompositeConfigResourceSourceTest]; this test focuses purely on the wiring choice the
 * helper makes — which sources are included and in what order.
 */
class WorkspaceLayeredConfigResourceSourceTest {

  private lateinit var tempRoot: File

  @BeforeTest
  fun setUp() {
    tempRoot = Files.createTempDirectory("workspace-layered-config-test").toFile()
  }

  @AfterTest
  fun tearDown() {
    tempRoot.deleteRecursively()
  }

  @Test
  fun `null configDir falls back to classpath-only source`() {
    // Verifies the documented fallback — a daemon started outside a workspace has nowhere
    // sensible to walk the filesystem from, so the helper must not return a composite
    // that contains a bogus FilesystemConfigResourceSource pointing at a missing root.
    val source = workspaceLayeredConfigResourceSource(configDir = null)
    assertTrue(
      source is ClasspathConfigResourceSource,
      "null configDir must use ClasspathConfigResourceSource directly (no filesystem layer)",
    )
  }

  @Test
  fun `non-directory configDir falls back to classpath-only source`() {
    // Covers the case where the caller passed something that exists but isn't a directory —
    // e.g. a regular file at the same path, or a deleted-mid-flight workspace dir. Should
    // degrade gracefully, not crash.
    val fileNotDir = File(tempRoot, "not-a-dir.txt").apply { writeText("oops") }
    val source = workspaceLayeredConfigResourceSource(configDir = fileNotDir)
    assertTrue(
      source is ClasspathConfigResourceSource,
      "non-directory configDir must fall back, not blow up at composite-build time",
    )
  }

  @Test
  fun `missing configDir falls back to classpath-only source`() {
    val source = workspaceLayeredConfigResourceSource(configDir = File(tempRoot, "doesnotexist"))
    assertTrue(
      source is ClasspathConfigResourceSource,
      "missing configDir must fall back to classpath",
    )
  }

  @Test
  fun `existing configDir builds a composite source that surfaces a workspace-authored trailhead`() {
    // Author a single trailhead.yaml under the workspace's trailmap layout — `<configDir>/trailmaps/
    // <trailmap>/trailheads/<name>.trailhead.yaml`. The helper must layer the filesystem source so
    // discoverAndLoadRecursive on TRAILMAPS_DIR returns its content. Reading the YAML directly via
    // the recursive API is the actual interaction `ToolYamlLoader.discoverTrailmapBundledTool
    // Contents` uses, so this test pins the integration point.
    val configDir = File(tempRoot, "config").apply { mkdirs() }
    val trailmapTrailheadsDir = File(configDir, "trailmaps/samplepack/trailheads").apply { mkdirs() }
    File(trailmapTrailheadsDir, "samplepack_android_launch.trailhead.yaml").writeText(
      """
      id: samplepack_android_launch
      description: Launch the sample trailmap app.
      trailhead:
        to: samplepack/android/home
      tools:
        - tap: { selector: "ok" }
      """.trimIndent(),
    )
    // Also create a top-level config file the workspace layer (NOT the dist layer) is meant
    // to find — verifies layer 2 (workspace) is wired, not just layer 3 (dist).
    File(configDir, "trailblaze.yaml").writeText("# marker for workspace layer test")

    val source = workspaceLayeredConfigResourceSource(configDir)

    val entries = source.discoverAndLoadRecursive(
      directoryPath = "trailmaps",
      suffix = ".trailhead.yaml",
    )
    val match = entries.entries.firstOrNull { (key, _) ->
      key.endsWith("samplepack_android_launch.trailhead.yaml")
    }
    assertNotNull(
      match,
      "workspace-authored trailhead must be discoverable through the layered source. Got keys: ${entries.keys}",
    )
    assertTrue(
      match.value.contains("id: samplepack_android_launch"),
      "filesystem layer must return the file's actual content",
    )
  }

  @Test
  fun `existing configDir without dist subdir still surfaces workspace-authored trailhead`() {
    // The "pre-compile" state: a freshly-checked-out workspace where `trails/config/` exists
    // and has hand-authored YAMLs, but `trails/config/dist/` does NOT exist yet because
    // `trailblaze compile` hasn't run. A regression that adds a `dist.exists()` precondition
    // to the helper would silently break this common case — the trailhead would stop appearing
    // in `toolbox trailheads`. Pin it.
    val configDir = File(tempRoot, "config").apply { mkdirs() }
    val trailmapTrailheadsDir = File(configDir, "trailmaps/samplepack/trailheads").apply { mkdirs() }
    File(trailmapTrailheadsDir, "samplepack_pre_compile.trailhead.yaml").writeText(
      """
      id: samplepack_pre_compile
      description: Workspace trailhead before any compile run.
      trailhead:
        to: samplepack/android/home
      tools:
        - tap: { selector: "ok" }
      """.trimIndent(),
    )
    // Sanity: confirm the `dist/` subdir does NOT exist for this scenario.
    assertEquals(
      false, File(configDir, TrailblazeConfigPaths.WORKSPACE_DIST_SUBDIR).exists(),
      "test invariant: dist/ subdir must be absent for this test",
    )

    val source = workspaceLayeredConfigResourceSource(configDir)

    val entries = source.discoverAndLoadRecursive(
      directoryPath = "trailmaps",
      suffix = ".trailhead.yaml",
    )
    val match = entries.entries.firstOrNull { (key, _) ->
      key.endsWith("samplepack_pre_compile.trailhead.yaml")
    }
    assertNotNull(
      match,
      "trailhead must still surface when dist/ is missing — FilesystemConfigResourceSource handles missing dirs by returning empty maps, not by crashing. Got keys: ${entries.keys}",
    )
  }
}
