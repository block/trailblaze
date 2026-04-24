package xyz.block.trailblaze.ui

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
 *
 * The primitive itself (`findWorkspaceRoot`) is covered by `WorkspaceRootTest`. These tests
 * prove the repo actually consults the primitive and places it above legacy fallbacks —
 * easy to regress in a future refactor that reorders the resolution chain.
 */
class TrailblazeSettingsRepoConfigDirTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Before
  fun assumeTempFolderIsScratch() {
    // Phase 2 walk-up reaches the filesystem root. If an ancestor of the temp dir happens
    // to contain trailblaze.yaml (unusual, but possible in hermit / CI layouts), tests
    // that rely on Scratch semantics will behave as if a workspace were present. Skip
    // rather than fail in that case.
    val result = findWorkspaceRoot(tempFolder.root.toPath())
    Assume.assumeTrue(
      "An ancestor of ${tempFolder.root} already contains a trailblaze.yaml — skipping.",
      result is WorkspaceRoot.Scratch,
    )
  }

  @Test
  fun `walk-up finds trailblaze_yaml and returns the workspace trailblaze-config subdir`() {
    val workspace = tempFolder.newFolder("workspace")
    File(workspace, TrailblazeProjectConfigLoader.CONFIG_FILENAME).writeText("")
    // Phase 2 returns the legacy `trailblaze-config/` subdir under the workspace (not the
    // workspace root itself) — FilesystemConfigResourceSource expects that layout. When
    // the subdir is absent, the chain falls through. Real-path comparison because
    // workspace.dir has been canonicalized through the /var → /private/var symlink on
    // macOS.
    File(workspace, TrailblazeConfigPaths.CONFIG_DIR).apply { mkdirs() }
    val expectedConfigSubdir = workspace.toPath().toRealPath()
      .resolve(TrailblazeConfigPaths.CONFIG_DIR).toFile()
    val repo = newRepo(initial = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()))

    val resolved = repo.getCurrentTrailblazeConfigDir(cwd = workspace.toPath())

    assertEquals(expectedConfigSubdir, resolved)
  }

  @Test
  fun `walk-up wins over explicit trailblazeConfigDirectory setting when subdir exists`() {
    val workspace = tempFolder.newFolder("workspace")
    File(workspace, TrailblazeProjectConfigLoader.CONFIG_FILENAME).writeText("")
    File(workspace, TrailblazeConfigPaths.CONFIG_DIR).apply { mkdirs() }
    val expectedConfigSubdir = workspace.toPath().toRealPath()
      .resolve(TrailblazeConfigPaths.CONFIG_DIR).toFile()
    val legacyExplicit = tempFolder.newFolder("legacy-explicit-config-dir")
    val repo = newRepo(
      initial = SavedTrailblazeAppConfig(
        selectedTrailblazeDriverTypes = emptyMap(),
        trailblazeConfigDirectory = legacyExplicit.absolutePath,
      ),
    )

    val resolved = repo.getCurrentTrailblazeConfigDir(cwd = workspace.toPath())

    assertEquals(expectedConfigSubdir, resolved)
  }

  @Test
  fun `workspace without trailblaze-config subdir falls through to legacy explicit setting`() {
    // Phase 2 compat: a workspace with only trailblaze.yaml (no trailblaze-config/ subdir)
    // has nothing the legacy FilesystemConfigResourceSource can read. The chain must
    // continue to the explicit setting — Phase 4 will fix this properly by reading the
    // config out of trailblaze.yaml itself.
    val workspace = tempFolder.newFolder("workspace-no-subdir")
    File(workspace, TrailblazeProjectConfigLoader.CONFIG_FILENAME).writeText("")
    val legacyExplicit = tempFolder.newFolder("legacy-explicit-config-dir")
    val repo = newRepo(
      initial = SavedTrailblazeAppConfig(
        selectedTrailblazeDriverTypes = emptyMap(),
        trailblazeConfigDirectory = legacyExplicit.absolutePath,
      ),
    )

    val resolved = repo.getCurrentTrailblazeConfigDir(cwd = workspace.toPath())

    assertEquals(legacyExplicit, resolved)
  }

  @Test
  fun `no trailblaze_yaml falls through to legacy explicit setting`() {
    val scratch = tempFolder.newFolder("scratch-workspace")
    val legacyExplicit = tempFolder.newFolder("legacy-explicit-config-dir")
    val repo = newRepo(
      initial = SavedTrailblazeAppConfig(
        selectedTrailblazeDriverTypes = emptyMap(),
        trailblazeConfigDirectory = legacyExplicit.absolutePath,
      ),
    )

    val resolved = repo.getCurrentTrailblazeConfigDir(cwd = scratch.toPath())

    assertEquals(legacyExplicit, resolved)
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
    // First layer in the resolution chain — the env-var escape hatch for CI / scripted
    // callers. Even if a workspace is reachable via walk-up, the env var dominates.
    val workspace = tempFolder.newFolder("workspace")
    File(workspace, TrailblazeProjectConfigLoader.CONFIG_FILENAME).writeText("")
    File(workspace, TrailblazeConfigPaths.CONFIG_DIR).apply { mkdirs() }
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
    // Malformed env var shouldn't short-circuit the chain — the walk-up layer still gets
    // a chance. Regression guard for a quiet `if (envDir.isDirectory) return envDir`
    // branch that otherwise has no negative-path coverage.
    val workspace = tempFolder.newFolder("workspace")
    File(workspace, TrailblazeProjectConfigLoader.CONFIG_FILENAME).writeText("")
    File(workspace, TrailblazeConfigPaths.CONFIG_DIR).apply { mkdirs() }
    val expectedConfigSubdir = workspace.toPath().toRealPath()
      .resolve(TrailblazeConfigPaths.CONFIG_DIR).toFile()
    val envAsFile = File(tempFolder.newFolder("env-host-dir"), "not-a-dir").apply { writeText("x") }
    val repo = newRepo(initial = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()))

    val resolved = repo.getCurrentTrailblazeConfigDir(
      cwd = workspace.toPath(),
      envReader = { envAsFile.absolutePath },
    )

    assertEquals(expectedConfigSubdir, resolved)
  }

  @Test
  fun `workspace trailblaze-config exists as a file falls through`() {
    // Edge case: the `.isDirectory` guard prevents a regular file at <workspace>/trailblaze-config
    // from being treated as the legacy config dir. Regression guard for anyone loosening
    // that check to `.exists()`.
    val workspace = tempFolder.newFolder("workspace-with-file")
    File(workspace, TrailblazeProjectConfigLoader.CONFIG_FILENAME).writeText("")
    File(workspace, TrailblazeConfigPaths.CONFIG_DIR).writeText("I'm a regular file, not a dir")
    val legacyExplicit = tempFolder.newFolder("legacy-explicit-config-dir")
    val repo = newRepo(
      initial = SavedTrailblazeAppConfig(
        selectedTrailblazeDriverTypes = emptyMap(),
        trailblazeConfigDirectory = legacyExplicit.absolutePath,
      ),
    )

    val resolved = repo.getCurrentTrailblazeConfigDir(cwd = workspace.toPath())

    assertEquals(legacyExplicit, resolved)
  }

  @Test
  fun `auto-sibling of trails directory still resolves when no trailblaze_yaml exists`() {
    val scratch = tempFolder.newFolder("scratch-workspace")
    val projectRoot = tempFolder.newFolder("legacy-project")
    File(projectRoot, "trails").mkdirs()
    val siblingConfig = File(projectRoot, "trailblaze-config").apply { mkdirs() }
    val repo = newRepo(
      initial = SavedTrailblazeAppConfig(
        selectedTrailblazeDriverTypes = emptyMap(),
        trailsDirectory = File(projectRoot, "trails").absolutePath,
      ),
    )

    val resolved = repo.getCurrentTrailblazeConfigDir(cwd = scratch.toPath())

    assertEquals(siblingConfig, resolved)
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
