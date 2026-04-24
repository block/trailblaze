package xyz.block.trailblaze.config.project

import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for [findWorkspaceRoot]. Covers the four entry-point shapes: CLI no-arg (cwd),
 * CLI with trail-file arg, desktop explicit pick, MCP explicit param — all of which funnel
 * into the same primitive.
 */
class WorkspaceRootTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  /**
   * macOS temp dirs resolve through a `/var → /private/var` symlink, so the tmp root's
   * real path is the only stable reference for equality checks.
   */
  private val realTempRoot get() = tempFolder.root.toPath().toRealPath()

  @Before
  fun assumeTempFolderIsScratch() {
    // Walk-up reaches the filesystem root; if any ancestor of the temp dir already
    // contains a trailblaze.yaml (unusual but possible in some CI / hermit layouts),
    // tests that rely on the "walk-up exhausts" path would mis-fire. Skip in that case
    // rather than assert on non-hermetic state.
    val result = findWorkspaceRoot(tempFolder.root.toPath())
    Assume.assumeTrue(
      "An ancestor of ${tempFolder.root} already contains a trailblaze.yaml — skipping.",
      result is WorkspaceRoot.Scratch,
    )
  }

  @Test
  fun `walk-up finds trailblaze_yaml in start directory`() {
    val workspace = newDir("workspace")
    val configFile = writeConfig(workspace)

    val result = findWorkspaceRoot(workspace.toPath())

    val configured = assertIs<WorkspaceRoot.Configured>(result)
    assertEquals(workspace.toPath().toRealPath(), configured.dir)
    assertEquals(configFile.toPath().toRealPath(), configured.configFile)
  }

  @Test
  fun `walk-up finds trailblaze_yaml several levels up`() {
    val workspace = newDir("workspace")
    writeConfig(workspace)
    val deep = File(workspace, "a/b/c").apply { mkdirs() }

    val result = findWorkspaceRoot(deep.toPath())

    val configured = assertIs<WorkspaceRoot.Configured>(result)
    assertEquals(workspace.toPath().toRealPath(), configured.dir)
  }

  @Test
  fun `walk-up exhausts at filesystem root returns Scratch anchored at start dir`() {
    // Put the start dir inside the tmp root but don't drop a trailblaze.yaml anywhere on
    // the walk-up. The walk exhausts at the filesystem root — every ancestor gets checked,
    // nothing matches, Scratch.
    val isolated = newDir("isolated-no-config/nested/deep")

    val result = findWorkspaceRoot(isolated.toPath())

    val scratch = assertIs<WorkspaceRoot.Scratch>(result)
    assertEquals(isolated.toPath().toRealPath(), scratch.dir)
  }

  @Test
  fun `starting path is a file walks up from its parent directory`() {
    val workspace = newDir("workspace")
    writeConfig(workspace)
    val subdir = File(workspace, "flows").apply { mkdirs() }
    val trailFile = File(subdir, "login.trail.yaml").apply { writeText("") }

    val result = findWorkspaceRoot(trailFile.toPath())

    val configured = assertIs<WorkspaceRoot.Configured>(result)
    assertEquals(workspace.toPath().toRealPath(), configured.dir)
  }

  @Test
  fun `starting path is a directory uses it as the search anchor`() {
    val workspace = newDir("workspace")
    writeConfig(workspace)
    val subdir = File(workspace, "nested").apply { mkdirs() }

    val result = findWorkspaceRoot(subdir.toPath())

    val configured = assertIs<WorkspaceRoot.Configured>(result)
    assertEquals(workspace.toPath().toRealPath(), configured.dir)
  }

  @Test
  fun `trailblaze_yaml must be a regular file — a directory of that name is ignored`() {
    val workspace = newDir("not-really-configured")
    // Decoy: a *directory* named trailblaze.yaml must not short-circuit walk-up.
    File(workspace, "trailblaze.yaml").apply { mkdirs() }

    val result = findWorkspaceRoot(workspace.toPath())

    assertIs<WorkspaceRoot.Scratch>(result)
  }

  @Test
  fun `symlinked workspace resolves to the real path`() {
    // Skip on filesystems that refuse symlink creation (Windows without dev-mode). The
    // plan's Open Question 2 flags Windows canonicalization for Phase 6; here we just
    // assert the macOS/Linux behaviour the primitive relies on.
    Assume.assumeTrue("Symlink support required", supportsSymlinks())

    val realWorkspace = newDir("real-workspace")
    writeConfig(realWorkspace)
    val link = File(tempFolder.root, "link-to-workspace").toPath()
    Files.createSymbolicLink(link, realWorkspace.toPath())

    val result = findWorkspaceRoot(link)

    val configured = assertIs<WorkspaceRoot.Configured>(result)
    // Configured.dir must be the resolved real path — not the symlink location — so that
    // Phase 6 preferences keying can't create duplicate entries for the same workspace
    // via a user's symlinked clone.
    assertEquals(realWorkspace.toPath().toRealPath(), configured.dir)
  }

  @Test
  fun `non-existent start path still returns Scratch anchored at absolute form`() {
    val missing = File(tempFolder.root, "does-not-exist-yet")
    // Sanity: we're not creating `missing`, the primitive must still produce a Scratch
    // result without throwing.
    assertTrue(!missing.exists())

    val result = findWorkspaceRoot(missing.toPath())

    val scratch = assertIs<WorkspaceRoot.Scratch>(result)
    // The tmp root exists, so its real path is defined; `missing` is resolved beneath it.
    assertEquals(realTempRoot.resolve("does-not-exist-yet"), scratch.dir)
  }

  @Test
  fun `relative start path is resolved against the process working directory`() {
    // Regression guard: a naive implementation that calls `.parent` on a bare relative
    // path (e.g. `Paths.get("")`) gets null immediately and skips walk-up. The primitive
    // must absolutize first so CLI entry points passing `Paths.get("")` behave sensibly.
    val relative = java.nio.file.Paths.get("")

    val result = findWorkspaceRoot(relative)

    // Either Configured (if the build is running inside a repo that has trailblaze.yaml
    // somewhere up the tree) or Scratch — both are valid outcomes. What matters is that
    // the call doesn't crash and the result's dir is absolute.
    assertTrue(result.dir.isAbsolute, "Result dir should be absolute, got: ${result.dir}")
  }

  @Test
  fun `closest trailblaze_yaml wins when ancestors also have one`() {
    // Nested-workspace case: a parent has trailblaze.yaml and a child has its own. The
    // child's trailblaze.yaml must win — this is how `!include` / monorepo factoring
    // from the plan (parent-traversal refs) stays consistent with walk-up.
    val outer = newDir("outer")
    writeConfig(outer, contents = "# outer")
    val inner = File(outer, "inner").apply { mkdirs() }
    val innerConfig = writeConfig(inner, contents = "# inner")
    val deep = File(inner, "a/b").apply { mkdirs() }

    val result = findWorkspaceRoot(deep.toPath())

    val configured = assertIs<WorkspaceRoot.Configured>(result)
    assertEquals(inner.toPath().toRealPath(), configured.dir)
    assertEquals(innerConfig.toPath().toRealPath(), configured.configFile)
  }

  private fun newDir(relativePath: String): File {
    val dir = File(tempFolder.root, relativePath)
    dir.mkdirs()
    return dir
  }

  private fun writeConfig(dir: File, contents: String = ""): File {
    val file = File(dir, TrailblazeProjectConfigLoader.CONFIG_FILENAME)
    file.writeText(contents)
    return file
  }

  private fun supportsSymlinks(): Boolean = try {
    val probeTarget = File(tempFolder.root, "_symlink-probe-target").apply { mkdirs() }
    val probeLink = File(tempFolder.root, "_symlink-probe-link").toPath()
    Files.createSymbolicLink(probeLink, probeTarget.toPath())
    Files.deleteIfExists(probeLink)
    probeTarget.delete()
    true
  } catch (_: Exception) {
    false
  }
}
