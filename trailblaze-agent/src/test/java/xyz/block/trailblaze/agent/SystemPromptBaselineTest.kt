package xyz.block.trailblaze.agent

import java.io.File
import org.junit.Test
import xyz.block.trailblaze.util.Console

/**
 * Snapshot tests for composed system prompts.
 *
 * These tests compare the actual composed system prompt against a checked-in baseline file.
 * When prompts change intentionally, the test will fail on CI — making changes visible in PRs.
 *
 * To rebaseline after an intentional change, run:
 * ```
 * ./gradlew :trailblaze-agent:updateSystemPromptBaselines
 * ```
 * Then commit the updated baseline files.
 */
class SystemPromptBaselineTest {

  @Test
  fun `base system prompt matches baseline`() {
    assertBaseline(
      baselineName = "base_system_prompt.txt",
      actual = TrailblazeRunner.baseSystemPrompt,
    )
  }

  @Test
  fun `default platform prompt matches baseline`() {
    assertBaseline(
      baselineName = "default_platform_prompt.txt",
      actual = TrailblazeRunner.defaultPlatformPrompt,
    )
  }

  @Test
  fun `composed default prompt matches baseline`() {
    assertBaseline(
      baselineName = "composed_default_prompt.txt",
      actual = TrailblazeRunner.composeSystemPrompt(),
    )
  }

  @Test
  fun `composed prompt with custom platform prompt matches baseline`() {
    val customPlatform = "**Custom platform instructions.**\n- Do something special."
    assertBaseline(
      baselineName = "composed_custom_platform_prompt.txt",
      actual = TrailblazeRunner.composeSystemPrompt(customPlatform),
    )
  }

  companion object {
    private val UPDATE_BASELINES = System.getenv("UPDATE_BASELINES")?.toBoolean() == true

    /**
     * Directory where baseline files are stored.
     * Resolves to `src/test/resources/baselines/` relative to the module root.
     */
    private val BASELINES_DIR: File = run {
      // The resource classloader points into build/resources/test, but we want src/test/resources
      val buildResourcesDir = SystemPromptBaselineTest::class.java
        .getResource("/baselines")?.toURI()?.let { File(it) }

      if (buildResourcesDir != null && !UPDATE_BASELINES) {
        buildResourcesDir
      } else {
        // When updating baselines or baselines dir doesn't exist yet in build output,
        // write to the source directory so files get checked in
        File("src/test/resources/baselines").also { it.mkdirs() }
      }
    }

    private fun assertBaseline(baselineName: String, actual: String) {
      val baselineFile = File(BASELINES_DIR, baselineName)

      if (UPDATE_BASELINES) {
        // Write to source directory for check-in
        val sourceFile = File("src/test/resources/baselines", baselineName)
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(actual)
        Console.log("Updated baseline: ${sourceFile.absolutePath}")
        return
      }

      if (!baselineFile.exists()) {
        // First run — create the baseline and fail with instructions
        val sourceFile = File("src/test/resources/baselines", baselineName)
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(actual)
        throw AssertionError(
          "Baseline file '$baselineName' did not exist. Created it at: ${sourceFile.absolutePath}\n" +
            "Commit this file, or run: ./gradlew :trailblaze-agent:updateSystemPromptBaselines",
        )
      }

      val expected = baselineFile.readText()
      if (actual != expected) {
        val diff = buildCompactDiff(expected, actual)
        throw AssertionError(
          buildString {
            appendLine("═══════════════════════════════════════════════════════════════")
            appendLine("SYSTEM PROMPT BASELINE CHANGED: $baselineName")
            appendLine("═══════════════════════════════════════════════════════════════")
            appendLine()
            appendLine("Your changes have modified the system prompt that is sent to the LLM.")
            appendLine("This affects how the AI agent behaves during test execution.")
            appendLine()
            appendLine("What changed:")
            appendLine(diff)
            appendLine()
            appendLine("If this change is intentional, update the baseline:")
            appendLine()
            appendLine("  ./gradlew :trailblaze-agent:updateSystemPromptBaselines")
            appendLine()
            appendLine("Then commit the updated baseline files in src/test/resources/baselines/.")
            appendLine("═══════════════════════════════════════════════════════════════")
          },
        )
      }
    }

    /**
     * Produces a compact line-by-line diff showing only added/removed lines with context.
     */
    private fun buildCompactDiff(expected: String, actual: String): String {
      val expectedLines = expected.lines()
      val actualLines = actual.lines()
      return buildString {
        val maxLines = maxOf(expectedLines.size, actualLines.size)
        var inDiff = false
        for (i in 0 until maxLines) {
          val exp = expectedLines.getOrNull(i)
          val act = actualLines.getOrNull(i)
          if (exp != act) {
            if (!inDiff) {
              // Show one line of context before the diff
              if (i > 0) appendLine("  ${expectedLines.getOrNull(i - 1)}")
              inDiff = true
            }
            if (exp != null && act != null) {
              appendLine("- $exp")
              appendLine("+ $act")
            } else if (exp != null) {
              appendLine("- $exp")
            } else if (act != null) {
              appendLine("+ $act")
            }
          } else {
            if (inDiff) {
              // Show one line of context after the diff
              appendLine("  $exp")
              inDiff = false
            }
          }
        }
      }
    }
  }
}
