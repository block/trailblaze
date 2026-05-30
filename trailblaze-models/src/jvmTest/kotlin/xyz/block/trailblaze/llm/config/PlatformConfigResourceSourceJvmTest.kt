package xyz.block.trailblaze.llm.config

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the JVM `actual` for [platformConfigResourceSource]:
 *
 * 1. When [WorkspaceConfigDirHolder.resolver] returns a valid workspace `trails/config/` dir, the
 *    default source is workspace-layered — a tool authored under
 *    `<configDir>/trailmaps/<id>/tools/<name>.tool.yaml` surfaces in
 *    `discoverAndLoadRecursive(TRAILMAPS_DIR, ".tool.yaml")` results.
 * 2. When the holder returns `null` (no workspace bootstrap installed, or no workspace
 *    resolves), the default collapses to [ClasspathConfigResourceSource] — same behavior
 *    consumers had before the workspace-aware default landed.
 * 3. On same-relPath collision between workspace and classpath, the workspace wins —
 *    pins the precedence rule end-to-end through the JVM default, not just at the
 *    composite layer.
 *
 * The only authoring layout the framework supports is trailmap-scoped: workspaces drop
 * `<workspace>/trails/config/trailmaps/<id>/tools/<name>.tool.yaml`, and the classpath ships
 * the same shape. The flat `trails/config/tools/` directory is no longer scanned.
 *
 * Sets and clears the holder around each test so we don't bleed workspace state into other
 * tests in this module.
 */
class PlatformConfigResourceSourceJvmTest {

  private lateinit var tempRoot: File
  private lateinit var priorResolver: () -> File?

  @BeforeTest
  fun setUp() {
    tempRoot = Files.createTempDirectory("platform-config-source-test").toFile()
    priorResolver = WorkspaceConfigDirHolder.resolver
  }

  @AfterTest
  fun tearDown() {
    WorkspaceConfigDirHolder.resolver = priorResolver
    tempRoot.deleteRecursively()
  }

  @Test
  fun `holder returning null collapses to classpath-only source`() {
    // The pre-bootstrap default is `{ null }`. Consumers that never install
    // TrailblazeWorkspaceConfigBootstrap (the docs generators, tests that don't care about
    // workspace) keep getting the same classpath-only behavior they had before.
    WorkspaceConfigDirHolder.resolver = { null }
    val source = platformConfigResourceSource()
    assertTrue(
      source is ClasspathConfigResourceSource,
      "null-returning resolver must produce ClasspathConfigResourceSource directly",
    )
  }

  @Test
  fun `holder returning valid configDir surfaces workspace-authored trailmap-scoped files`() {
    val configDir = File(tempRoot, "trails/config").apply { mkdirs() }
    val toolsDir = File(configDir, "trailmaps/myWorkspaceTrailmap/tools").apply { mkdirs() }
    val toolFile = File(toolsDir, "fixture.tool.yaml").apply {
      writeText("id: fixture\nclass: com.example.FixtureTool\n")
    }
    WorkspaceConfigDirHolder.resolver = { configDir }

    val source = platformConfigResourceSource()
    val results = source.discoverAndLoadRecursive(TrailblazeConfigPaths.TRAILMAPS_DIR, ".tool.yaml")

    assertEquals(
      toolFile.readText(),
      results["myWorkspaceTrailmap/tools/fixture.tool.yaml"],
      "workspace trailmap-scoped tool file must be surfaced through the default platform source",
    )
  }

  @Test
  fun `workspace wins on same-relPath collision with classpath bundle`() {
    // This module ships its own classpath fixture under a trailmap-scoped path
    // `src/jvmTest/resources/trails/config/trailmaps/jvmTestFixture/tools/platformConfigResourceSourceFixture.tool.yaml`
    // so the collision is testable WITHOUT taking a runtime dependency on trailblaze-common
    // (which ships the production `*.tool.yaml` files). Without a local fixture, the classpath
    // side of this collision is empty in the trailblaze-models test runtime and the workspace
    // would "win" vacuously — i.e. the test would pass even if the precedence rule were broken.
    val fixtureRelPath = "jvmTestFixture/tools/platformConfigResourceSourceFixture.tool.yaml"
    val classpathBody = ClasspathConfigResourceSource
      .discoverAndLoadRecursive(TrailblazeConfigPaths.TRAILMAPS_DIR, ".tool.yaml")[fixtureRelPath]
    assertNotNull(
      classpathBody,
      "test precondition: the bundled fixture must be on the classpath — verify " +
        "src/jvmTest/resources/trails/config/trailmaps/jvmTestFixture/tools/" +
        "platformConfigResourceSourceFixture.tool.yaml exists",
    )

    val configDir = File(tempRoot, "trails/config").apply { mkdirs() }
    val toolsDir = File(configDir, "trailmaps/jvmTestFixture/tools").apply { mkdirs() }
    val overrideBody = "id: platformConfigResourceSourceFixture\ndescription: workspace override body\n"
    File(toolsDir, "platformConfigResourceSourceFixture.tool.yaml").writeText(overrideBody)
    WorkspaceConfigDirHolder.resolver = { configDir }

    assertNotEquals(
      classpathBody,
      overrideBody,
      "test design check: workspace and classpath bodies must differ so a passing assertion " +
        "below proves the workspace value was returned (not just that 'one of them' was)",
    )

    val source = platformConfigResourceSource()
    val results = source.discoverAndLoadRecursive(TrailblazeConfigPaths.TRAILMAPS_DIR, ".tool.yaml")

    assertEquals(
      overrideBody,
      results[fixtureRelPath],
      "workspace tool must override the classpath-bundled tool at the same trailmap-scoped relPath",
    )
  }
}
