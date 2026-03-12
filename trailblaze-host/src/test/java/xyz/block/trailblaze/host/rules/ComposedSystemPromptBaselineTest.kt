package xyz.block.trailblaze.host.rules

import java.io.File
import org.junit.Test
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.util.Console

/**
 * Snapshot tests for every platform's composed system prompt.
 *
 * Each test composes the prompt exactly as production code does (base + platform layer),
 * then compares it against a checked-in baseline. This lets you see the full prompt
 * each platform's LLM receives, and any change to any layer shows up in PR diffs.
 *
 * To rebaseline after intentional changes, run:
 * ```
 * ./gradlew :trailblaze-host:updateSystemPromptBaselines
 * ```
 * Then commit the updated baseline files.
 */
class ComposedSystemPromptBaselineTest {

  // -- Mobile (default) --

  @Test
  fun `composed mobile prompt matches baseline`() {
    assertBaseline(
      baselineName = "composed_mobile_prompt.txt",
      actual = TrailblazeRunner.composeSystemPrompt(),
    )
  }

  // -- Playwright Native --

  @Test
  fun `composed playwright native prompt matches baseline`() {
    assertBaseline(
      baselineName = "composed_playwright_native_prompt.txt",
      actual = TrailblazeRunner.composeSystemPrompt(
        BasePlaywrightNativeTest.PLAYWRIGHT_NATIVE_SYSTEM_PROMPT,
      ),
    )
  }

  // -- Playwright Electron --

  @Test
  fun `composed playwright electron prompt matches baseline`() {
    assertBaseline(
      baselineName = "composed_playwright_electron_prompt.txt",
      actual = TrailblazeRunner.composeSystemPrompt(
        BasePlaywrightElectronTest.PLAYWRIGHT_ELECTRON_SYSTEM_PROMPT,
      ),
    )
  }

  // -- Compose Desktop --

  @Test
  fun `composed compose desktop prompt matches baseline`() {
    assertBaseline(
      baselineName = "composed_compose_desktop_prompt.txt",
      actual = TrailblazeRunner.composeSystemPrompt(BaseComposeTest.COMPOSE_SYSTEM_PROMPT),
    )
  }

  // -- Web (generic) --

  @Test
  fun `composed web prompt matches baseline`() {
    assertBaseline(
      baselineName = "composed_web_prompt.txt",
      actual = TrailblazeRunner.composeSystemPrompt(BaseWebTrailblazeTest.WEB_SYSTEM_PROMPT),
    )
  }

  companion object {
    private val UPDATE_BASELINES = System.getenv("UPDATE_BASELINES")?.toBoolean() == true

    private val BASELINES_DIR: File = run {
      val buildResourcesDir =
        ComposedSystemPromptBaselineTest::class.java.getResource("/baselines")?.toURI()?.let {
          File(it)
        }
      if (buildResourcesDir != null && !UPDATE_BASELINES) {
        buildResourcesDir
      } else {
        File("src/test/resources/baselines").also { it.mkdirs() }
      }
    }

    private fun assertBaseline(baselineName: String, actual: String) {
      val baselineFile = File(BASELINES_DIR, baselineName)

      if (UPDATE_BASELINES) {
        val sourceFile = File("src/test/resources/baselines", baselineName)
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(actual)
        Console.log("Updated baseline: ${sourceFile.absolutePath}")
        return
      }

      if (!baselineFile.exists()) {
        val sourceFile = File("src/test/resources/baselines", baselineName)
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(actual)
        throw AssertionError(
          "Baseline file '$baselineName' did not exist. Created it at: ${sourceFile.absolutePath}\n" +
            "Commit this file, or run: ./gradlew :trailblaze-host:updateSystemPromptBaselines",
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
            appendLine("  ./gradlew :trailblaze-host:updateSystemPromptBaselines")
            appendLine()
            appendLine("Then commit the updated baseline files in src/test/resources/baselines/.")
            appendLine("═══════════════════════════════════════════════════════════════")
          },
        )
      }
    }

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
              appendLine("  $exp")
              inDiff = false
            }
          }
        }
      }
    }
  }
}
