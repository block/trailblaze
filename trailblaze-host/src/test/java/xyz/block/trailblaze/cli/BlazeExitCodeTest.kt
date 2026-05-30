package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [blazeExitCode] in `CliOutputFormatters.kt`. The function maps an MCP
 * tool result to one of the four [TrailblazeExitCode] classes. The exit-code
 * policy is load-bearing for downstream CI consumers and shell scripts that
 * compose CLI calls — `script_foo && script_bar` needs to know whether a non-zero
 * exit means "user typed a wrong tool name" (retry-with-correct-name is the right
 * move) vs "the device is gone" (no amount of argv massaging helps).
 *
 * The headline regression this guards: an `Unknown tool` markdown response
 * previously returned [TrailblazeExitCode.INFRA_FAILED] because the daemon's
 * `StepResult.toMarkdown()` output (prefix `**❌ Error** — `) was caught by the
 * generic `"❌ Error" in text` branch and bucketed as infrastructure failure —
 * but a user typing the wrong tool name is input misuse, not an infra problem.
 * Worse, the same code path reached `ToolCommand` higher up the stack, where
 * the rejection branch returned `SUCCESS (0)` despite the error message; the
 * Web FTUX validator reported `Unknown tool: tap_on_text. Use toolbox() to see
 * available tools.` exiting `0`. This test pins the new verdict at both layers:
 * input mistakes return [TrailblazeExitCode.MISUSE], with the misuse-marker
 * check ordered before the generic `❌ Error` catch so the more-specific
 * verdict wins.
 */
class BlazeExitCodeTest {

  @Test
  fun `isError result returns INFRA_FAILED`() {
    val result = CliMcpClient.ToolResult(content = "anything", isError = true)
    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, blazeExitCode(result))
  }

  @Test
  fun `JSON error field returns INFRA_FAILED`() {
    val result = CliMcpClient.ToolResult(
      content = """{"error": "daemon failed to bind device"}""",
    )
    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, blazeExitCode(result))
  }

  @Test
  fun `JSON without error returns SUCCESS`() {
    val result = CliMcpClient.ToolResult(content = """{"screenSummary": "ok"}""")
    assertEquals(TrailblazeExitCode.SUCCESS.code, blazeExitCode(result))
  }

  @Test
  fun `markdown with x-FAILED returns INFRA_FAILED`() {
    val result = CliMcpClient.ToolResult(content = "**❌ FAILED** — assertion did not hold")
    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, blazeExitCode(result))
  }

  @Test
  fun `markdown without error markers returns SUCCESS`() {
    val result = CliMcpClient.ToolResult(content = "**✓ Executed** — tap on Sign In")
    assertEquals(TrailblazeExitCode.SUCCESS.code, blazeExitCode(result))
  }

  @Test
  fun `Unknown tool markdown returns MISUSE not INFRA_FAILED`() {
    // The exact shape the daemon emits for an unknown tool name. Both the
    // ❌-Error marker AND the misuse marker appear in the same string — the
    // misuse check has to take precedence or the user gets INFRA_FAILED for a
    // typo, which is wrong per the [TrailblazeExitCode] policy.
    val result = CliMcpClient.ToolResult(
      content = "**❌ Error** — Unknown tool: tap_on_text. Use toolbox() to see available tools.",
    )
    assertEquals(TrailblazeExitCode.MISUSE.code, blazeExitCode(result))
  }

  @Test
  fun `Unknown tools plural markdown returns MISUSE`() {
    // The daemon pluralizes the message when multiple unknown tool names are
    // rejected in one batch (StepToolSet emits "Unknown tools: a, b, c."). The
    // substring match is on "Unknown tool" so both singular and plural land in
    // the misuse bucket.
    val result = CliMcpClient.ToolResult(
      content = "**❌ Error** — Unknown tools: tap_on_text, press_back. Use toolbox().",
    )
    assertEquals(TrailblazeExitCode.MISUSE.code, blazeExitCode(result))
  }

  @Test
  fun `not-valid-for-current-device markdown returns MISUSE`() {
    // The other rejection path: tool name IS registered but doesn't apply to
    // the currently-bound driver/target (e.g. openUrl on a Playwright device).
    // Per policy: still user-input mistake → MISUSE.
    val result = CliMcpClient.ToolResult(
      content = "**❌ Error** — Tool 'openUrl' is not valid for the current device/target.",
    )
    assertEquals(TrailblazeExitCode.MISUSE.code, blazeExitCode(result))
  }

  @Test
  fun `unknown-tool substring alone without error marker still returns MISUSE`() {
    // Defense in depth: if a future daemon-side format change drops the
    // ❌-Error marker but keeps the body text, we still recognize the misuse
    // shape. (This is what the regression guards: blazeExitCode previously
    // returned SUCCESS when neither marker pattern matched, even though the
    // body text clearly described a user-input mistake.)
    val result = CliMcpClient.ToolResult(content = "Unknown tool: foo. Use toolbox().")
    assertEquals(TrailblazeExitCode.MISUSE.code, blazeExitCode(result))
  }

  @Test
  fun `Unknown tool with x-FAILED marker also returns MISUSE not INFRA_FAILED`() {
    // The precedence guarantee covers BOTH generic-error markers, not just
    // "❌ Error". A future daemon format change that swapped the prefix to
    // "❌ FAILED" while keeping the body text must still bucket as MISUSE,
    // otherwise `blazeExitCode` would silently slip back to INFRA_FAILED for
    // user typos. The misuse check comes first in the conditional chain —
    // this test pins that ordering against drift.
    val result = CliMcpClient.ToolResult(
      content = "**❌ FAILED** — Unknown tool: tap_on_text. Use toolbox().",
    )
    assertEquals(TrailblazeExitCode.MISUSE.code, blazeExitCode(result))
  }
}
