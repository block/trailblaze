package xyz.block.trailblaze.cli

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [resolveWaypointRoot] and [maybeWarnNoTarget].
 *
 * The two helpers split a single concern that used to live in `resolveWaypointRoot`
 * alone: the original always emitted "Warning: no --target specified" when neither
 * flag was given, which fired on every successful `waypoint list` (where classpath
 * packs supply 100+ results) and was a daily-flow noise regression. The fix split
 * that into:
 *
 *  - `resolveWaypointRoot` — silent unless the user explicitly passed `--target` and
 *    it didn't resolve (a real user error worth flagging).
 *  - `maybeWarnNoTarget` — called by each command AFTER discovery, fires the
 *    "did you mean --target?" hint only when the result is actually empty.
 *
 * These tests pin both halves so a future "simplification" can't regress back to
 * the unconditional warning.
 */
class WaypointCommandSharedTest {

  private val tempDirs = mutableListOf<File>()
  private lateinit var capturedErr: ByteArrayOutputStream
  private lateinit var capturedOut: ByteArrayOutputStream

  @BeforeTest
  fun setUpCapture() {
    CliOutCapture.install()
    capturedErr = ByteArrayOutputStream()
    capturedOut = ByteArrayOutputStream()
  }

  @AfterTest
  fun cleanupTempDirs() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  // ==========================================================================
  // resolveWaypointRoot
  // ==========================================================================

  @Test
  fun `--root explicit override is returned as-is and emits no warnings`() {
    val explicitRoot = newTempDir()
    val resolved = withCapture {
      resolveWaypointRoot(rootOverride = explicitRoot, targetId = null)
    }
    assertEquals(explicitRoot, resolved)
    assertEquals("", capturedErr.toString(), "no warnings should fire when --root is explicit")
    assertEquals("", capturedOut.toString())
  }

  @Test
  fun `--root explicit override wins precedence over --target`() {
    val explicitRoot = newTempDir()
    val resolved = withCapture {
      resolveWaypointRoot(rootOverride = explicitRoot, targetId = "myapp")
    }
    assertEquals(explicitRoot, resolved)
    assertEquals(
      "",
      capturedErr.toString(),
      "no warning when --root is set; --target is ignored, not validated",
    )
  }

  @Test
  fun `--target resolves to workspace pack when one exists`() {
    val workspaceRoot = newTempDir()
    val packDir = File(workspaceRoot, "trails/config/packs/myapp/waypoints").apply {
      mkdirs()
    }
    // Workspace anchor must exist for TrailblazeWorkspaceConfigResolver to find it.
    File(workspaceRoot, "trails/config/trailblaze.yaml").writeText("")

    val resolved = withCapture {
      resolveWaypointRoot(
        rootOverride = null,
        targetId = "myapp",
        fromPath = workspaceRoot.toPath(),
      )
    }

    assertEquals(packDir.canonicalFile, resolved.canonicalFile)
    assertEquals(
      "",
      capturedErr.toString(),
      "no warning when --target resolves successfully",
    )
  }

  @Test
  fun `--target with no matching workspace pack warns and falls back to default`() {
    val workspaceRoot = newTempDir()
    val configDir = File(workspaceRoot, "trails/config").apply { mkdirs() }
    File(configDir, "trailblaze.yaml").writeText("")
    // No packs/missing-pack/ directory created.

    val resolved = withCapture {
      resolveWaypointRoot(
        rootOverride = null,
        targetId = "missing-pack",
        fromPath = workspaceRoot.toPath(),
      )
    }

    assertEquals(File(DEFAULT_WAYPOINT_ROOT), resolved)
    val err = capturedErr.toString()
    assertTrue(
      "Warning: --target missing-pack did not resolve" in err,
      "expected target-not-resolved warning, got: $err",
    )
    assertTrue(
      "pass --root" in err,
      "expected --root hint for classpath-bundled packs, got: $err",
    )
  }

  @Test
  fun `--target reads CliCallerContext callerCwd by default — daemon-forwarded calls find the user's workspace`() {
    // The load-bearing fix for daemon-forwarded `--target`: when the bash shim sends
    // the user's $PWD, executeForDaemon pins it via CliCallerContext.withCallerCwd,
    // and resolveWaypointRoot's default fromPath reads that thread-local. This test
    // exercises the full default-arg path: NO fromPath argument supplied, so
    // resolveWaypointRoot must consult the thread-local.
    val workspaceRoot = newTempDir()
    val packDir = File(workspaceRoot, "trails/config/packs/myapp/waypoints").apply { mkdirs() }
    File(workspaceRoot, "trails/config/trailblaze.yaml").writeText("")

    val resolved = withCapture {
      CliCallerContext.withCallerCwd(workspaceRoot.toPath()) {
        resolveWaypointRoot(rootOverride = null, targetId = "myapp")
      }
    }

    assertEquals(packDir.canonicalFile, resolved.canonicalFile)
    assertEquals(
      "",
      capturedErr.toString(),
      "no warning when --target resolves successfully via the caller's cwd",
    )
  }

  @Test
  fun `--target with no workspace anchor at all warns and falls back to default`() {
    // No trailblaze.yaml exists anywhere walking up from this dir.
    val orphanedRoot = newTempDir()

    val resolved = withCapture {
      resolveWaypointRoot(
        rootOverride = null,
        targetId = "myapp",
        fromPath = orphanedRoot.toPath(),
      )
    }

    assertEquals(File(DEFAULT_WAYPOINT_ROOT), resolved)
    val err = capturedErr.toString()
    assertTrue(
      "<no workspace anchor>" in err,
      "expected explicit '<no workspace anchor>' marker in warning, got: $err",
    )
  }

  @Test
  fun `neither flag silently returns default — no warning during resolution`() {
    val orphanedRoot = newTempDir()

    val resolved = withCapture {
      resolveWaypointRoot(
        rootOverride = null,
        targetId = null,
        fromPath = orphanedRoot.toPath(),
      )
    }

    assertEquals(File(DEFAULT_WAYPOINT_ROOT), resolved)
    assertEquals(
      "",
      capturedErr.toString(),
      "the no-flags case must be silent — classpath packs typically supply results, " +
        "so warning during resolution would fire on every successful 'waypoint list'",
    )
  }

  // ==========================================================================
  // maybeWarnNoTarget
  // ==========================================================================

  @Test
  fun `maybeWarnNoTarget stays silent when results are non-empty`() {
    withCapture {
      maybeWarnNoTarget(rootOverride = null, targetId = null, resultIsEmpty = false)
    }
    assertEquals(
      "",
      capturedErr.toString(),
      "no hint when discovery succeeded — that's the noise we explicitly avoid",
    )
  }

  @Test
  fun `maybeWarnNoTarget stays silent when --target was given even if results are empty`() {
    // The user already scoped via --target; if it came up empty, the resolveWaypointRoot
    // warning has already fired (or it found the right pack and just had no waypoints).
    // Don't double-warn.
    withCapture {
      maybeWarnNoTarget(rootOverride = null, targetId = "myapp", resultIsEmpty = true)
    }
    assertEquals("", capturedErr.toString())
  }

  @Test
  fun `maybeWarnNoTarget stays silent when --root was given even if results are empty`() {
    withCapture {
      maybeWarnNoTarget(
        rootOverride = newTempDir(),
        targetId = null,
        resultIsEmpty = true,
      )
    }
    assertEquals("", capturedErr.toString())
  }

  @Test
  fun `maybeWarnNoTarget fires the --target hint when results are empty AND no flags were given`() {
    withCapture {
      maybeWarnNoTarget(rootOverride = null, targetId = null, resultIsEmpty = true)
    }
    val err = capturedErr.toString()
    assertTrue(
      "no --target or --root specified" in err,
      "expected the missing-flags hint, got: $err",
    )
    assertTrue("Pass --target" in err, "expected actionable --target suggestion: $err")
    assertTrue("--root" in err, "expected actionable --root suggestion: $err")
  }

  // ==========================================================================
  // Test infrastructure.
  // ==========================================================================

  private fun newTempDir(): File {
    val dir = createTempDirectory(prefix = "waypoint-shared-test-").toFile()
    tempDirs += dir
    return dir
  }

  private fun <T> withCapture(block: () -> T): T =
    CliOutCapture.withCapture(capturedOut, capturedErr, block)
}
