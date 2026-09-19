package xyz.block.trailblaze.cli

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What `trailblaze verify` actually prints. The verdict is the whole output of this command, so
 * saying it three ways — a heading, a breadcrumb verb, and a message that restates the verb —
 * makes a two-word answer take four lines, and leaves a reader hunting for the reason among
 * three copies of the result.
 *
 * These drive [formatVerifyResultAgent] against the daemon payloads it really receives and
 * assert the whole rendered block, because the defect is what is printed TOGETHER.
 */
class VerifyOutputRenderingTest {

  private fun render(content: String, isError: Boolean = false): Pair<String, Int> {
    val captured = captureConsole {
      formatVerifyResultAgent(CliMcpClient.ToolResult(content = content, isError = isError))
    }
    return captured.out.trimEnd('\n') to captured.result
  }

  @Test
  fun `a passing verify states the verdict once`() {
    // StepResult.toMarkdown for a verify the daemon judged true, with the model's prose summary
    // in place of an element list (Android accessibility returns no tree for this screen).
    val (rendered, code) = render(
      "**✅ PASSED** — Assertion passed\n\n" +
        "**Screen:** Sample app detail screen for Item 25, showing the heading 'Detail for Item 25'.",
    )

    assertEquals(
      """
      → PASSED
      ### Screen summary
      Sample app detail screen for Item 25, showing the heading 'Detail for Item 25'.
      """.trimIndent(),
      rendered,
    )
    assertEquals(TrailblazeExitCode.SUCCESS.code, code)
  }

  @Test
  fun `a failing verify keeps the reason it failed for`() {
    val (rendered, code) = render(
      "**❌ FAILED** — The Sign In button is not visible\n\n" +
        "**Screen:** [n12] \"Create account\" | [n13] \"Forgot password\"",
    )

    assertEquals(
      """
      → FAILED — The Sign In button is not visible
      ### Screen
      [n12] "Create account" | [n13] "Forgot password"
      """.trimIndent(),
      rendered,
    )
    assertEquals(TrailblazeExitCode.ASSERTION_FAILED.code, code)
  }

  @Test
  fun `a daemon that sends no status header still reports the verdict`() {
    // The regression this guards: without the daemon's `**✅ PASSED**` header there is no
    // breadcrumb to carry the verdict, so the heading has to.
    val (rendered, code) = render("✅ PASSED\n\n**Screen:** [n12] \"Home\"")

    assertTrue(rendered.startsWith("### Passed"), "verdict must survive a headerless payload: $rendered")
    assertEquals(TrailblazeExitCode.SUCCESS.code, code)
  }

  @Test
  fun `the JSON shape does not print the verdict twice either`() {
    val (rendered, code) = render("""{"passed":true,"result":"Assertion passed"}""")

    assertEquals("### Passed", rendered)
    assertEquals(TrailblazeExitCode.SUCCESS.code, code)
  }

  @Test
  fun `the JSON shape keeps a result that says something`() {
    val (rendered, code) = render("""{"passed":false,"result":"Found 0 items in the cart, expected 3"}""")

    assertEquals(
      """
      ### Failed
      Found 0 items in the cart, expected 3
      """.trimIndent(),
      rendered,
    )
    assertEquals(TrailblazeExitCode.ASSERTION_FAILED.code, code)
  }

  @Test
  fun `a transport failure is still an infrastructure exit, not a verdict`() {
    val (_, code) = render("Daemon unreachable", isError = true)
    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, code)
  }
}
