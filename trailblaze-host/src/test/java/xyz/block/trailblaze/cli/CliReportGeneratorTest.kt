package xyz.block.trailblaze.cli

import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.report.models.Outcome
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [CliReportGenerator.mapStatusToOutcome] — small but load-bearing because the
 * markdown report's pass/fail rendering depends on every [SessionStatus] subtype mapping
 * to the right [Outcome] enum value.
 */
class CliReportGeneratorTest {

  private val generator = CliReportGenerator()

  @Test
  fun `Succeeded maps to PASSED`() {
    val status = SessionStatus.Ended.Succeeded(durationMs = 1_000L)
    assertEquals(Outcome.PASSED, generator.mapStatusToOutcome(status))
  }

  @Test
  fun `SucceededWithSelfHeal maps to PASSED`() {
    val status = SessionStatus.Ended.SucceededWithSelfHeal(durationMs = 1_000L)
    assertEquals(Outcome.PASSED, generator.mapStatusToOutcome(status))
  }

  @Test
  fun `Failed maps to FAILED`() {
    val status = SessionStatus.Ended.Failed(durationMs = 1_000L, exceptionMessage = "boom")
    assertEquals(Outcome.FAILED, generator.mapStatusToOutcome(status))
  }

  @Test
  fun `FailedWithSelfHeal maps to FAILED`() {
    val status = SessionStatus.Ended.FailedWithSelfHeal(
      durationMs = 1_000L,
      exceptionMessage = "kaboom",
    )
    assertEquals(Outcome.FAILED, generator.mapStatusToOutcome(status))
  }

  @Test
  fun `Cancelled maps to CANCELLED`() {
    val status = SessionStatus.Ended.Cancelled(durationMs = 1_000L, cancellationMessage = null)
    assertEquals(Outcome.CANCELLED, generator.mapStatusToOutcome(status))
  }

  @Test
  fun `TimeoutReached maps to TIMEOUT`() {
    val status = SessionStatus.Ended.TimeoutReached(durationMs = 1_000L, message = null)
    assertEquals(Outcome.TIMEOUT, generator.mapStatusToOutcome(status))
  }

  @Test
  fun `MaxCallsLimitReached maps to MAX_CALLS_REACHED`() {
    val status = SessionStatus.Ended.MaxCallsLimitReached(
      durationMs = 1_000L,
      maxCalls = 100,
      objectivePrompt = "do the thing",
    )
    assertEquals(Outcome.MAX_CALLS_REACHED, generator.mapStatusToOutcome(status))
  }

  @Test
  fun `Unknown maps to ERROR`() {
    assertEquals(Outcome.ERROR, generator.mapStatusToOutcome(SessionStatus.Unknown))
    // SessionStatus.Started also maps to ERROR but requires a full TrailblazeDeviceInfo
    // to construct — covered indirectly by integration tests; the mapping is mechanical
    // and visible in mapStatusToOutcome's `is Started -> Outcome.ERROR` branch.
  }

  @Test
  fun `generateJsonReport returns null for empty session list`() {
    val tempDir = Files.createTempDirectory("cli-report-gen-test").toFile()
    try {
      val logsRepo = LogsRepo(logsDir = tempDir, watchFileSystem = false)
      assertNull(generator.generateJsonReport(logsRepo, sessionIds = emptyList()))
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `generateJsonReport returns null when no sessions resolve`() {
    // Non-empty input but the session IDs don't correspond to any on-disk sessions →
    // buildSessionResult returns null for each, the results list is empty, and the
    // method short-circuits with null instead of writing an empty-results JSON.
    val tempDir = Files.createTempDirectory("cli-report-gen-test").toFile()
    try {
      val logsRepo = LogsRepo(logsDir = tempDir, watchFileSystem = false)
      val nonExistentSessionIds = listOf(SessionId("does-not-exist-1"), SessionId("does-not-exist-2"))
      assertNull(generator.generateJsonReport(logsRepo, sessionIds = nonExistentSessionIds))
    } finally {
      tempDir.deleteRecursively()
    }
  }
}
