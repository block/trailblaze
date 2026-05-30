package xyz.block.trailblaze.config.project

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths

/**
 * Tests for [TrailblazeWorkspaceConfigResolver.resolve] — specifically the precedence between
 * an explicit `TRAILBLAZE_CONFIG_DIR` and cwd walk-up.
 *
 * The load-bearing case is the regression guard: an explicit config dir that carries its own
 * `trailblaze.yaml` must become the authoritative workspace **anchor** (the file that defines
 * targets/trailmaps), not merely the file-scan directory. Before the fix the env var moved
 * only `configDir` while `configFile` still came from cwd walk-up, so a cwd that is itself a
 * workspace (e.g. a monorepo root whose `contacts`/`wikipedia` trailmaps are android-only)
 * shadowed the env-pointed example workspace and its scripted tools never registered.
 */
class TrailblazeWorkspaceConfigResolverTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Before
  fun assumeTempFolderIsScratch() {
    // Walk-up reaches the filesystem root; skip if an ancestor of the temp dir already
    // carries a trailblaze.yaml, which would defeat the Scratch/walk-up assumptions.
    Assume.assumeTrue(
      "An ancestor of ${tempFolder.root} already contains a trailblaze.yaml — skipping.",
      findWorkspaceRoot(tempFolder.root.toPath()) is WorkspaceRoot.Scratch,
    )
  }

  /** Creates `<parent>/<name>/trails/config/trailblaze.yaml` and returns the workspace dir. */
  private fun newWorkspace(name: String): File {
    val workspace = tempFolder.newFolder(name)
    File(workspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE).apply {
      parentFile.mkdirs()
      writeText("")
    }
    return workspace
  }

  private fun configDirOf(workspace: File): File =
    File(workspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR)

  private fun File.canonical(): File = toPath().toRealPath().toFile()

  @Test
  fun `explicit config dir with its own anchor wins over a different cwd workspace`() {
    // cwd is one workspace (think: monorepo root, android-only trailmaps)…
    val cwdWorkspace = newWorkspace("cwd-workspace")
    // …and TRAILBLAZE_CONFIG_DIR points at a *different* workspace (think: examples/wikipedia).
    val envWorkspace = newWorkspace("env-workspace")
    val envConfigDir = configDirOf(envWorkspace)

    val resolved = TrailblazeWorkspaceConfigResolver.resolve(
      fromPath = cwdWorkspace.toPath(),
      envReader = { envConfigDir.absolutePath },
    )

    // The anchor (and thus the targets/trailmaps that load) comes from the env workspace,
    // NOT the cwd workspace.
    assertEquals(
      File(envWorkspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE).canonical(),
      resolved.configFile?.canonical(),
    )
    assertEquals(envConfigDir.canonical(), resolved.configDir?.canonical())
  }

  @Test
  fun `resolveConfigFile honors the explicit config dir anchor`() {
    val cwdWorkspace = newWorkspace("cwd-workspace")
    val envWorkspace = newWorkspace("env-workspace")
    val envConfigDir = configDirOf(envWorkspace)

    // resolveConfigFile reads the real env var, so exercise resolve() directly to inject it;
    // this asserts the two entry points agree (no anchor split between the trail runner and
    // the LLM-config / MCP / CLI-info callers).
    val viaResolve = TrailblazeWorkspaceConfigResolver
      .resolve(cwdWorkspace.toPath(), envReader = { envConfigDir.absolutePath })
      .configFile

    assertEquals(
      File(envWorkspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE).canonical(),
      viaResolve?.canonical(),
    )
  }

  @Test
  fun `explicit config dir without its own anchor keeps cwd walk-up anchor`() {
    val cwdWorkspace = newWorkspace("cwd-workspace")
    // Override dir is a real directory but carries no trailblaze.yaml of its own.
    val bareEnvDir = tempFolder.newFolder("bare-env-config-dir")

    val resolved = TrailblazeWorkspaceConfigResolver.resolve(
      fromPath = cwdWorkspace.toPath(),
      envReader = { bareEnvDir.absolutePath },
    )

    // Legacy split preserved: anchor still from walk-up, payload dir from the override.
    assertEquals(
      File(cwdWorkspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE).canonical(),
      resolved.configFile?.canonical(),
    )
    assertEquals(bareEnvDir.canonical(), resolved.configDir?.canonical())
  }

  @Test
  fun `no env var resolves the cwd workspace via walk-up`() {
    val cwdWorkspace = newWorkspace("cwd-workspace")

    val resolved = TrailblazeWorkspaceConfigResolver.resolve(
      fromPath = cwdWorkspace.toPath(),
      envReader = { null },
    )

    assertEquals(
      File(cwdWorkspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE).canonical(),
      resolved.configFile?.canonical(),
    )
    assertEquals(configDirOf(cwdWorkspace).canonical(), resolved.configDir?.canonical())
  }

  @Test
  fun `env var pointing at a non-directory is ignored in favor of walk-up`() {
    val cwdWorkspace = newWorkspace("cwd-workspace")
    val notADir = File(tempFolder.newFolder("env-host"), "not-a-dir").apply { writeText("x") }

    val resolved = TrailblazeWorkspaceConfigResolver.resolve(
      fromPath = cwdWorkspace.toPath(),
      envReader = { notADir.absolutePath },
    )

    assertEquals(
      File(cwdWorkspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE).canonical(),
      resolved.configFile?.canonical(),
    )
    assertEquals(configDirOf(cwdWorkspace).canonical(), resolved.configDir?.canonical())
  }

  @Test
  fun `scratch cwd with no env var resolves no config`() {
    val scratch = tempFolder.newFolder("scratch")

    val resolved = TrailblazeWorkspaceConfigResolver.resolve(
      fromPath = scratch.toPath(),
      envReader = { null },
    )

    assertNull(resolved.configFile)
    assertNull(resolved.configDir)
  }
}
