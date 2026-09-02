package xyz.block.trailblaze.cli

import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * A local `--snapshot-baseline` ref is relative to the user's shell, but a delegated run resolves
 * it inside the daemon, which was launched somewhere else entirely. Anchoring it at the caller's
 * cwd before dispatch is what makes `--snapshot-baseline previous.zip` mean the same file whether
 * the run executes in the daemon or in-process.
 */
class SnapshotBaselineRefResolutionTest {

  private fun refFrom(cwd: String, ref: String?): String? {
    val command = TrailCommand()
    command.snapshotBaseline = ref
    return CliCallerContext.withCallerCwd(Paths.get(cwd)) { command.resolvedSnapshotBaseline() }
  }

  @Test
  fun `a relative local ref is anchored at the caller's working directory`() {
    assertEquals("/home/dev/project/previous.zip", refFrom("/home/dev/project", "previous.zip"))
    assertEquals("/home/dev/logs/run-a", refFrom("/home/dev/project", "../logs/run-a"))
  }

  @Test
  fun `absolute paths and http refs pass through unchanged`() {
    assertEquals("/tmp/baseline.zip", refFrom("/home/dev/project", "/tmp/baseline.zip"))
    assertEquals(
      "https://example.invalid/results/latest_success.zip",
      refFrom("/home/dev/project", "https://example.invalid/results/latest_success.zip"),
    )
  }

  @Test
  fun `no ref and a blank ref stay absent rather than becoming the working directory`() {
    assertNull(refFrom("/home/dev/project", null))
    assertNull(refFrom("/home/dev/project", "   "))
  }
}
