package xyz.block.trailblaze.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.CaptureCoverage
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.report.models.CiSummaryReport
import xyz.block.trailblaze.report.models.Outcome
import xyz.block.trailblaze.report.utils.LogsRepo

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

  @Test
  fun `generateJsonReport surfaces accessibility_truncation for android sessions with coverage`() {
    // End-to-end guard for PR #4143's CLI report path: AccessibilityTruncationSummary.fromLogs
    // is called from CliReportGenerator.buildSessionResult and the result is serialized into the
    // daemon CLI JSON. Without this test the wiring is silently lost on a future refactor.
    val tempDir = Files.createTempDirectory("cli-report-gen-a11y-test").toFile()
    try {
      val sessionId = SessionId("2026_06_26_android_truncated_session")
      val deviceInfo = androidDeviceInfo()
      val started = Instant.parse("2026-06-26T12:00:00Z")

      writeLog(
        sessionDir = sessionDirFor(tempDir, sessionId),
        fileName = "001_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Started(
            trailConfig = null,
            trailFilePath = "trails/sample-app/android-a11y.trail.yaml",
            hasRecordedSteps = false,
            testMethodName = "exerciseTruncation",
            testClassName = "AndroidA11yTest",
            trailblazeDeviceInfo = deviceInfo,
            trailblazeDeviceId = deviceInfo.trailblazeDeviceId,
            rawYaml = null,
          ),
          session = sessionId,
          timestamp = started,
        ),
      )
      writeLog(
        sessionDir = sessionDirFor(tempDir, sessionId),
        fileName = "002_AgentDriverLog.json",
        log = TrailblazeLog.AgentDriverLog(
          viewHierarchy = ViewHierarchyTreeNode(),
          screenshotFile = "screenshot_truncated.png",
          action = AgentDriverAction.TapPoint(x = 540, y = 1200),
          captureCoverage = CaptureCoverage(
            contentNodes = 6,
            zeroBoundsContentNodes = 0,
            horizontalCoverage = 0.17,
            verticalCoverage = 0.92,
            looksTruncated = true,
            reason = "content spans 17% of width, jammed against the right edge " +
              "(left 82% empty) across 6 node(s)",
          ),
          durationMs = 320,
          session = sessionId,
          timestamp = started.plus(2.seconds),
          deviceHeight = 2400,
          deviceWidth = 1080,
        ),
      )
      writeLog(
        sessionDir = sessionDirFor(tempDir, sessionId),
        fileName = "003_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 5_000),
          session = sessionId,
          timestamp = started.plus(5.seconds),
        ),
      )

      val logsRepo = LogsRepo(logsDir = tempDir, watchFileSystem = false)
      val output = generator.generateJsonReport(logsRepo, sessionIds = listOf(sessionId))
      assertNotNull(output, "generateJsonReport should have written a file")

      val report = Json { ignoreUnknownKeys = true }
        .decodeFromString<CiSummaryReport>(output.readText())
      val summary = report.results.single().accessibility_truncation
      assertNotNull(summary, "accessibility_truncation must be populated when logs carry coverage")
      assertEquals(1, summary.captures_total)
      assertEquals(1, summary.captures_truncated)
      assertTrue(
        summary.examples.single().reason.contains("right edge"),
        "the example should carry the detector's reason verbatim — got ${summary.examples.single().reason}",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  private fun sessionDirFor(logsDir: File, sessionId: SessionId): File =
    File(logsDir, sessionId.value).apply { mkdirs() }

  private fun writeLog(sessionDir: File, fileName: String, log: TrailblazeLog) {
    File(sessionDir, fileName).writeText(TrailblazeJsonInstance.encodeToString<TrailblazeLog>(log))
  }

  private fun androidDeviceInfo(): TrailblazeDeviceInfo {
    val deviceId = TrailblazeDeviceId(
      instanceId = "android-emulator",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    )
    return TrailblazeDeviceInfo(
      trailblazeDeviceId = deviceId,
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      widthPixels = 1080,
      heightPixels = 2400,
      classifiers = listOf(TrailblazeDevicePlatform.ANDROID.asTrailblazeDeviceClassifier()),
    )
  }
}
