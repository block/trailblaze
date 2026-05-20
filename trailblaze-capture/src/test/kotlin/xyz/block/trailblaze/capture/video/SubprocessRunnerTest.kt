package xyz.block.trailblaze.capture.video

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Direct unit tests for [runSubprocessWithTimeout]. Uses shell built-ins (`sh -c`, `printf`,
 * `sleep`) rather than ffmpeg/ffprobe so the tests are independent of the surrounding video
 * tooling and run on any POSIX-y agent. The few branches that the helper claims to handle —
 * success, non-zero exit, missing binary, timeout — are each covered here, plus the contract
 * preconditions (empty command, non-positive timeout) and the log-output sanitization helper.
 */
class SubprocessRunnerTest {

  @Test
  fun `successful command captures stdout and returns exitCode zero`() {
    val result = runSubprocessWithTimeout(listOf("sh", "-c", "printf hello"), timeoutSeconds = 5)
    assertNotNull(result, "expected a SubprocessResult for a successful command")
    assertEquals(0, result.exitCode)
    assertEquals("hello", result.output.trim())
  }

  @Test
  fun `non-zero exit returns SubprocessResult with that exit code`() {
    val result = runSubprocessWithTimeout(listOf("sh", "-c", "printf oops; exit 7"), timeoutSeconds = 5)
    assertNotNull(result, "expected a SubprocessResult even on non-zero exit")
    assertEquals(7, result.exitCode)
    // Output is captured even when the subprocess exits non-zero — important because the
    // failure-diagnostic logs in callers (concatSegments, wrapSingleSegment, frame extraction,
    // sprite tiling) embed the stdout/stderr to make CI triage possible.
    assertEquals("oops", result.output.trim())
  }

  @Test
  fun `missing binary returns null without throwing`() {
    val result =
      runSubprocessWithTimeout(
        listOf("/nonexistent/definitely/not/a/binary-${System.nanoTime()}"),
        timeoutSeconds = 5,
      )
    assertNull(result, "expected null for a binary that doesn't exist")
  }

  @Test
  fun `timeout destroys the subprocess and returns null`() {
    // 10s sleep against a 1s timeout — the helper must destroy the subprocess and return
    // null. Measure elapsed wall-clock to confirm we didn't sit on the sleep (would mean
    // destroyForcibly wasn't reached).
    val start = System.nanoTime()
    val result = runSubprocessWithTimeout(listOf("sh", "-c", "sleep 10"), timeoutSeconds = 1)
    val elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)
    assertNull(result, "expected null when the subprocess times out")
    assertTrue(
      elapsedSeconds < 5,
      "expected the helper to return within ~1s+slack on timeout; took ${elapsedSeconds}s — destroyForcibly probably didn't fire",
    )
  }

  @Test
  fun `empty command list throws IllegalArgumentException`() {
    try {
      runSubprocessWithTimeout(emptyList(), timeoutSeconds = 5)
      fail("expected IllegalArgumentException for empty command")
    } catch (_: IllegalArgumentException) {
      // Expected — empty command is a programming bug, not a runtime condition.
    }
  }

  @Test
  fun `non-positive timeout throws IllegalArgumentException`() {
    for (badTimeout in listOf(0L, -1L)) {
      try {
        runSubprocessWithTimeout(listOf("sh", "-c", "printf x"), timeoutSeconds = badTimeout)
        fail("expected IllegalArgumentException for timeoutSeconds=$badTimeout")
      } catch (_: IllegalArgumentException) {
        // Expected.
      }
    }
  }

  // ── sanitizeSubprocessOutputForLog ────────────────────────────────────────────

  @Test
  fun `sanitize collapses control characters and ANSI escapes`() {
    //  is ESC, the first char of an ANSI color escape.  is BEL. Both should be
    // replaced with `?`. Newlines and tabs survive (they're often part of useful diagnostics).
    val raw = "fine\nline\tone[31mredbell"
    val sanitized = sanitizeSubprocessOutputForLog(raw)
    assertFalse(sanitized.contains(''), "ESC should have been replaced")
    assertFalse(sanitized.contains(''), "BEL should have been replaced")
    assertTrue(sanitized.contains('\n'), "newline should survive")
    assertTrue(sanitized.contains('\t'), "tab should survive")
  }

  @Test
  fun `sanitize truncates long output with a marker`() {
    val raw = "x".repeat(1_000)
    val sanitized = sanitizeSubprocessOutputForLog(raw, maxChars = 100)
    assertTrue(sanitized.length < raw.length, "expected truncation")
    assertTrue(sanitized.startsWith("x".repeat(100)), "expected the head to survive")
    assertTrue(sanitized.contains("truncated"), "expected a truncation marker")
  }

  @Test
  fun `sanitize empty output returns the empty-marker placeholder`() {
    assertEquals("(empty)", sanitizeSubprocessOutputForLog(""))
  }
}
