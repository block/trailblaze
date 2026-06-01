package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the behavior of [TrailCommand.failureBodyAlreadyStreamed] — the guard that
 * stops `trailblaze run` from reprinting the full multi-line `AgentTaskStatus`
 * block (prompt JSON, `Status Type:`, `Status:`, llmExplanation) under the
 * `❌ FAILED` line when the same content was already streamed via the progress
 * callback.
 *
 * The streamed-progress format from `DesktopYamlRunner.prefixedProgressMessage`
 * is `[<device>] Error: <body>`; the run-result `errorMessage` field is the
 * bare `<body>`. The dedup is a suffix match (after trim) so the `[device]
 * Error: ` head doesn't defeat it.
 *
 * Codex review feedback on PR #3629 flagged the earlier `contains(firstLine)`
 * shape as too loose — a generic first line ("Error", "Failed", "Timeout")
 * could appear in unrelated progress output. The current `endsWith(body)`
 * check is what the false-positive cases in this file pin against.
 */
class TrailCommandFailureBodyDedupTest {

  private val cmd = TrailCommand()

  @Test
  fun `matches when progress ends with the full error body`() {
    val errorBody = """
      Failed to successfully run prompt with AI {"verify":"X is visible"}
      Status Type: xyz.block.trailblaze.agent.model.AgentTaskStatus${'$'}Failure${'$'}ObjectiveFailed
      Status: {"prompt":"X is visible","callCount":1}
    """.trimIndent()
    val streamedProgress = "[playwright-native] Error: $errorBody"

    assertTrue(
      cmd.failureBodyAlreadyStreamed(streamedProgress, errorBody),
      "the runner emits `[device] Error: <body>` then sets <body> on the result — endsWith must match",
    )
  }

  @Test
  fun `matches across trailing whitespace differences`() {
    val errorBody = "Connection lost: read timed out after 30s"
    val streamedProgress = "[android-emulator-5554] Error: $errorBody   \n"

    assertTrue(
      cmd.failureBodyAlreadyStreamed(streamedProgress, errorBody),
      "trailing whitespace and newlines should not defeat the dedup",
    )
  }

  @Test
  fun `rejects a generic first-line false positive (Copilot review feedback)`() {
    // The earlier `contains(firstLine)` check would treat this as a duplicate
    // because the progress message contains the word "Error" — but the
    // *bodies* are entirely different. The error reports a network timeout;
    // the progress reports a step-execution generic failure. Reprinting the
    // error body in this case is the correct behavior; suppressing it would
    // hide a real signal.
    val streamedProgress = "[ios-iphone] Error: step 3 of 5 failed — retrying"
    val errorBody = "Error: daemon unreachable at http://localhost:53555/cli/run-status"

    assertFalse(
      cmd.failureBodyAlreadyStreamed(streamedProgress, errorBody),
      "unrelated bodies that share a generic prefix must NOT be treated as duplicates",
    )
  }

  @Test
  fun `rejects a single-word error overlap`() {
    // The previous contains(firstLine) implementation would have matched here
    // because the first non-blank line of `error` ("Timeout") appears
    // verbatim inside the streamed progress message. The endsWith form
    // rejects it correctly.
    val streamedProgress = "[android] Timeout while waiting for device boot"
    val errorBody = "Timeout"

    assertFalse(
      cmd.failureBodyAlreadyStreamed(streamedProgress, errorBody),
      "a one-word error body that's substring of progress must NOT dedup",
    )
  }

  @Test
  fun `returns false when no progress was streamed`() {
    assertFalse(
      cmd.failureBodyAlreadyStreamed(null, "any error"),
      "missing progress means nothing was streamed; print the body in full",
    )
    assertFalse(
      cmd.failureBodyAlreadyStreamed("", "any error"),
      "blank progress means nothing was streamed; print the body in full",
    )
  }

  @Test
  fun `returns false when error body is blank`() {
    assertFalse(
      cmd.failureBodyAlreadyStreamed("[device] Error: something", ""),
      "blank error body has nothing to suppress",
    )
    assertFalse(
      cmd.failureBodyAlreadyStreamed("[device] Error: something", "   \n  "),
      "whitespace-only error body has nothing to suppress",
    )
  }
}
