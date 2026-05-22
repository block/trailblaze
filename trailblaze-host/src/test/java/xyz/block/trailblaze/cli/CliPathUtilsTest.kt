package xyz.block.trailblaze.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [CliPathUtils] — the shared workspace-walk-up + PATH-lookup primitives
 * used by both [CompileCommand] and [TypecheckCommand].
 *
 * The walk-up tests pin the same contract `CompileCommandTest` exercises against
 * `CompileCommand.findWorkspaceRoot` — only now the contract lives in one place. The
 * `isCommandOnPath` smoke test verifies that PATH-resident binaries are found without
 * having to mock the system PATH; we look for `/bin/sh` (or `cmd.exe` on Windows)
 * since those are guaranteed to exist on the build agents this test runs on.
 */
class CliPathUtilsTest {

  private val workDir: File = createTempDirectory("trailblaze-cli-path-utils-test").toFile()

  @AfterTest fun cleanup() {
    workDir.deleteRecursively()
  }

  @Test
  fun `findWorkspaceRoot returns the workspace itself when called at the root`() {
    val workspaceRoot = workDir
    File(workspaceRoot, "trails/config/packs").mkdirs()

    val found = CliPathUtils.findWorkspaceRoot(workspaceRoot.toPath())
    assertEquals(workspaceRoot.canonicalFile.toPath(), found?.toRealPath())
  }

  @Test
  fun `findWorkspaceRoot walks up from a deeply-nested subdir to the workspace root`() {
    File(workDir, "trails/config/packs").mkdirs()
    var deep = workDir
    repeat(15) { deep = File(deep, "level").apply { mkdirs() } }

    val found = CliPathUtils.findWorkspaceRoot(deep.toPath())
    assertEquals(workDir.canonicalFile.toPath(), found?.toRealPath())
  }

  @Test
  fun `findWorkspaceRoot returns null when no marker is found before the filesystem root`() {
    // workDir is /tmp/<random> with no `trails/config/packs/` anywhere up the tree.
    val isolated = File(workDir, "isolated").apply { mkdirs() }

    val found = CliPathUtils.findWorkspaceRoot(isolated.toPath())
    assertNull(found)
  }

  @Test
  fun `isCommandOnPath finds a binary that always exists on the test agent`() {
    // `/bin/sh` (POSIX) and `cmd.exe` (Windows) are universally on PATH for any agent
    // running these tests. If this assertion fails, either the agent is severely
    // broken or the PATH lookup logic is.
    val universal = if (System.getProperty("os.name").lowercase().contains("windows")) "cmd" else "sh"
    assertTrue(
      CliPathUtils.isCommandOnPath(universal),
      "expected '$universal' to resolve on the agent's PATH",
    )
  }

  @Test
  fun `isCommandOnPath returns false for a clearly-nonexistent binary`() {
    assertEquals(
      false,
      CliPathUtils.isCommandOnPath("definitely-not-a-real-binary-xyz-${System.nanoTime()}"),
    )
  }
}
