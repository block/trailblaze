package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Pins the contract of [TrailCommand.resolveDefaultTrailDir] — the helper that supplies the
 * default `<workspace-root>/trails/` directory when `trailblaze run` is invoked with no path
 * argument. Returns null when no workspace root resolves OR when the workspace exists but has
 * no `trails/` directory next to its config; the caller surfaces a clear error in either case.
 */
class TrailCommandResolveDefaultTrailDirTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private fun writeWorkspaceConfig(root: File) {
    val configDir = File(root, "trails/config").apply { mkdirs() }
    File(configDir, "trailblaze.yaml").writeText("defaults:\n  target: sample\n")
  }

  @Test
  fun `returns the trails directory when both workspace config and trails dir exist`() {
    val root = tempFolder.root
    writeWorkspaceConfig(root)
    val trailsDir = File(root, "trails").apply { mkdirs() }

    val resolved = TrailCommand.resolveDefaultTrailDir(root.toPath())

    assertEquals(trailsDir.canonicalFile, resolved?.canonicalFile)
  }

  @Test
  fun `resolves the workspace from a subdirectory of the workspace root`() {
    val root = tempFolder.root
    writeWorkspaceConfig(root)
    File(root, "trails").mkdirs()
    val nested = File(root, "subdir/nested").apply { mkdirs() }

    // Walking up from a subdirectory should still discover the workspace root and its trails/.
    val resolved = TrailCommand.resolveDefaultTrailDir(nested.toPath())
    assertEquals(File(root, "trails").canonicalFile, resolved?.canonicalFile)
  }

  @Test
  fun `returns null in scratch mode when no trails directory exists at the start dir`() {
    val root = tempFolder.root
    // No workspace config AND no `trails/` directory — pure scratch mode with nothing to
    // default to. The helper returns null so the caller can print a clear error rather than
    // silently expanding to an empty list of trails.
    val resolved = TrailCommand.resolveDefaultTrailDir(root.toPath())
    assertNull(resolved)
  }

  @Test
  fun `returns the trails dir even when workspace config is absent if scratch root + trails coexist`() {
    val root = tempFolder.root
    // Scratch-mode workspace: no `trails/config/trailblaze.yaml`, but a `trails/` directory is
    // present. The helper honors this so users can drop a few trails into a directory and
    // start running them without committing to the full workspace config layout first.
    File(root, "trails").mkdirs()

    val resolved = TrailCommand.resolveDefaultTrailDir(root.toPath())
    assertEquals(File(root, "trails").canonicalFile, resolved?.canonicalFile)
  }
}
