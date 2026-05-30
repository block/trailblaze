package xyz.block.trailblaze.docs

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.toolcalls.ResolvedTargetToolDetailRenderer

/**
 * Focused tests for [TargetToolBaselineGenerator]. The full `generate()` walks the
 * framework classpath (`AppTargetYamlLoader.discoverConfigs()` + companions) which is
 * exercised end-to-end by `./gradlew :internal-docs-generator:run` + CI's
 * `pr_static_checks.sh` `git diff --exit-code` assertion. This file pins the part of the
 * generator that *can* be tested without that setup: the per-tool sidecar orphan-prune
 * behavior, which is the dogfood contract with the workspace `ResolvedTargetReportEmitter`
 * — stale emitter-owned sidecars must be removed, hand-authored siblings must survive.
 */
class TargetToolBaselineGeneratorTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  private fun newDir(name: String): File {
    val parent = createTempDirectory("target-baseline-test").toFile()
    tempDirs += parent
    return File(parent, name).apply { mkdirs() }
  }

  @Test
  fun `pruneStaleSidecars deletes emitter-owned files not in the keep set`() {
    val sidecarDir = newDir("sidecars")
    val stale = File(sidecarDir, "stale_tool.md").apply {
      writeText(
        """
        ${ResolvedTargetToolDetailRenderer.GENERATED_BANNER}
        # stale
        """.trimIndent(),
      )
    }
    val current = File(sidecarDir, "current_tool.md").apply {
      writeText(
        """
        ${ResolvedTargetToolDetailRenderer.GENERATED_BANNER}
        # current
        """.trimIndent(),
      )
    }

    TargetToolBaselineGenerator(generatedDir = newDir("ignored"))
      .pruneStaleSidecars(sidecarDir = sidecarDir, keepNames = setOf("current_tool.md"))

    assertFalse("stale emitter-owned sidecar should be deleted") { stale.exists() }
    assertTrue("current emitter-owned sidecar should be preserved") { current.exists() }
  }

  @Test
  fun `pruneStaleSidecars leaves hand-authored files alone even if not in keep set`() {
    val sidecarDir = newDir("sidecars")
    val handAuthored = File(sidecarDir, "design_notes.md").apply {
      writeText("# Hand-authored — must survive\n\nLong-form design notes that don't belong to any tool.\n")
    }

    TargetToolBaselineGenerator(generatedDir = newDir("ignored"))
      .pruneStaleSidecars(sidecarDir = sidecarDir, keepNames = emptySet())

    assertTrue("hand-authored file without the banner must survive") { handAuthored.exists() }
  }

  @Test
  fun `pruneStaleSidecars is a no-op when the sidecar directory does not exist`() {
    // Catches the early-return path so it never throws if a target has never had any
    // tools (e.g. a newly added target that hasn't been generated yet).
    val nonexistent = File(newDir("parent"), "tools")
    TargetToolBaselineGenerator(generatedDir = newDir("ignored"))
      .pruneStaleSidecars(sidecarDir = nonexistent, keepNames = emptySet())
    // No exception thrown is the assertion; reaching here means the no-op path holds.
    assertFalse("dir should still not exist after a no-op prune") { nonexistent.exists() }
  }
}
