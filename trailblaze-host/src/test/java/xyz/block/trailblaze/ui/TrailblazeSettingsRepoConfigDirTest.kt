package xyz.block.trailblaze.ui

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader
import xyz.block.trailblaze.config.project.WorkspaceRoot
import xyz.block.trailblaze.config.project.findWorkspaceRoot
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * Integration coverage for [TrailblazeSettingsRepo.getCurrentTrailblazeConfigDir] — the
 * workspace-root discovery path the repo routes through.
 */
class TrailblazeSettingsRepoConfigDirTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Before
  fun assumeTempFolderIsScratch() {
    // Walk-up reaches the filesystem root. If an ancestor of the temp dir happens to
    // contain trailblaze.yaml, tests that rely on Scratch semantics behave as if a
    // workspace were present. Skip rather than fail in that case.
    val result = findWorkspaceRoot(tempFolder.root.toPath())
    Assume.assumeTrue(
      "An ancestor of ${tempFolder.root} already contains a trailblaze.yaml — skipping.",
      result is WorkspaceRoot.Scratch,
    )
  }

  @Test
  fun `walk-up finds trails config trailblaze_yaml and returns the workspace config dir`() {
    val workspace = tempFolder.newFolder("workspace")
    File(workspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE).apply {
      parentFile.mkdirs()
      writeText("")
    }
    val expectedConfigSubdir = workspace.toPath().toRealPath()
      .resolve(TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR).toFile()
    val repo = newRepo(initial = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()))

    val resolved = repo.getCurrentTrailblazeConfigDir(cwd = workspace.toPath())

    assertEquals(expectedConfigSubdir, resolved)
  }

  @Test
  fun `workspace without trails config subdir returns null`() {
    val workspace = tempFolder.newFolder("workspace-no-subdir")
    File(workspace, "trails").mkdirs()
    val repo = newRepo(initial = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()))

    val resolved = repo.getCurrentTrailblazeConfigDir(cwd = workspace.toPath())

    assertNull(resolved)
  }

  @Test
  fun `no trailblaze_yaml and no legacy settings returns null`() {
    val scratch = tempFolder.newFolder("scratch-workspace")
    val repo = newRepo(initial = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()))

    val resolved = repo.getCurrentTrailblazeConfigDir(cwd = scratch.toPath())

    assertNull(resolved)
  }

  @Test
  fun `TRAILBLAZE_CONFIG_DIR env var wins over walk-up when set to a directory`() {
    val workspace = tempFolder.newFolder("workspace")
    File(workspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE).apply {
      parentFile.mkdirs()
      writeText("")
    }
    val envDir = tempFolder.newFolder("env-config-dir")
    val repo = newRepo(initial = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()))

    val resolved = repo.getCurrentTrailblazeConfigDir(
      cwd = workspace.toPath(),
      envReader = { envDir.absolutePath },
    )

    assertEquals(envDir, resolved)
  }

  @Test
  fun `TRAILBLAZE_CONFIG_DIR env var pointing at a non-directory falls through`() {
    val workspace = tempFolder.newFolder("workspace")
    File(workspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE).apply {
      parentFile.mkdirs()
      writeText("")
    }
    val expectedConfigSubdir = workspace.toPath().toRealPath()
      .resolve(TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR).toFile()
    val envAsFile = File(tempFolder.newFolder("env-host-dir"), "not-a-dir").apply { writeText("x") }
    val repo = newRepo(initial = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()))

    val resolved = repo.getCurrentTrailblazeConfigDir(
      cwd = workspace.toPath(),
      envReader = { envAsFile.absolutePath },
    )

    assertEquals(expectedConfigSubdir, resolved)
  }

  @Test
  fun `saveConfig recreates missing parent directory`() {
    val initial = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap())
    val repo = newRepo(initial = initial)
    val settingsDir = repo.settingsFile.parentFile
    settingsDir.deleteRecursively()

    repo.saveConfig(initial)

    assertTrue(repo.settingsFile.exists())
  }

  private fun newRepo(initial: SavedTrailblazeAppConfig): TrailblazeSettingsRepo {
    val settingsFile = File(tempFolder.newFolder("settings"), "trailblaze-settings.json")
    return TrailblazeSettingsRepo(
      settingsFile = settingsFile,
      initialConfig = initial,
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      allTargetApps = { emptySet() },
      supportedDriverTypes = setOf(TrailblazeDriverType.DEFAULT_ANDROID, TrailblazeDriverType.IOS_HOST),
    )
  }
}
