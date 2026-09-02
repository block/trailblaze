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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import xyz.block.trailblaze.report.models.SkippedTrail
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

  @Test
  fun `generateJsonReport names the trail file each row ran`() {
    // Two writers produce this artifact - this daemon-CLI path and the gradle CLI's
    // GenerateTestResultsCliCommand - and consumers read one schema. `trail_file_path` is what a
    // consumer asks "was a trail ever expected of this row?", so a row missing it here would look
    // like a test that was never a trail, which is how a report reader discounts a pass.
    val tempDir = Files.createTempDirectory("cli-report-gen-trail-path-test").toFile()
    try {
      val sessionId = SessionId("2026_09_01_trail_path_session")
      val deviceInfo = androidDeviceInfo()
      val started = Instant.parse("2026-09-01T12:00:00Z")

      writeLog(
        sessionDir = sessionDirFor(tempDir, sessionId),
        fileName = "001_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Started(
            trailConfig = null,
            trailFilePath = "trails/estate/C4242-checkout.trail.yaml",
            hasRecordedSteps = true,
            testMethodName = "payAtCheckout",
            testClassName = "CheckoutTest",
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
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 5_000),
          session = sessionId,
          timestamp = started.plus(5.seconds),
        ),
      )

      // A session with ONLY an Ended log - what a test that returns before calling the rule leaves
      // behind, since the Started log is emitted from inside it. This is the row the whole field
      // exists to identify.
      val harnessId = SessionId("2026_09_01_harness_session")
      writeLog(
        sessionDir = sessionDirFor(tempDir, harnessId),
        fileName = "001_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 48),
          session = harnessId,
          timestamp = started,
        ),
      )

      val logsRepo = LogsRepo(logsDir = tempDir, watchFileSystem = false)
      val output = generator.generateJsonReport(
        logsRepo,
        sessionIds = listOf(sessionId, harnessId),
      )
      assertNotNull(output, "generateJsonReport should have written a file")

      val text = output.readText()
      val report = Json { ignoreUnknownKeys = true }.decodeFromString<CiSummaryReport>(text)
      assertEquals(
        "trails/estate/C4242-checkout.trail.yaml",
        report.results.single { it.session_id == sessionId }.trail_file_path,
      )
      assertNull(report.results.single { it.session_id == harnessId }.trail_file_path)

      // Asserted on the encoded document, not the decoded object: a consumer distinguishes "named
      // no trail" from "this report predates the field" by whether the KEY is there, and decoding
      // erases that difference. This writer serializes with its own `Json` instance, separate from
      // the gradle CLI's, so an `explicitNulls = false` added to it would drop the key on exactly
      // these rows and silently restore the verdict this field exists to correct.
      val encodedHarnessRow = Json.parseToJsonElement(text)
        .jsonObject
        .getValue("results")
        .jsonArray
        .single { it.jsonObject.getValue("session_id").jsonPrimitive.content == harnessId.value }
        .jsonObject
      assertEquals(
        JsonNull,
        encodedHarnessRow["trail_file_path"],
        "expected an explicit null; got ${encodedHarnessRow["trail_file_path"]}",
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `generateJsonReport reports a blank trail file path as none at all`() {
    // A blank path answers "which trail did this row run?" with nothing, and a consumer reading the
    // field for null would disagree with one reading it for emptiness. Normalizing at the writer is
    // what keeps that from being two different verdicts on one row.
    val tempDir = Files.createTempDirectory("cli-report-gen-blank-trail-path-test").toFile()
    try {
      val sessionId = SessionId("2026_09_01_blank_path_session")
      val deviceInfo = androidDeviceInfo()
      val started = Instant.parse("2026-09-01T12:00:00Z")

      writeLog(
        sessionDir = sessionDirFor(tempDir, sessionId),
        fileName = "001_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Started(
            trailConfig = null,
            trailFilePath = "   ",
            hasRecordedSteps = true,
            testMethodName = "payAtCheckout",
            testClassName = "CheckoutTest",
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
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
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
      assertNull(report.results.single().trail_file_path)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `generateJsonReport lists the skips its sibling HTML report lists`() {
    // The two artifacts are halves of one report, generated from one call in ReportCommand, and
    // the JSON is documented as the machine-readable equal of what a reader sees. A held-back
    // trail present in one and absent from the other means neither can be trusted to be the run.
    val tempDir = Files.createTempDirectory("cli-report-gen-skips-test").toFile()
    try {
      val sessionId = SessionId("2026_08_27_android_ran_session")
      writeEndedSession(tempDir, sessionId)
      val logsRepo = LogsRepo(logsDir = tempDir, watchFileSystem = false)

      val output = generator.generateJsonReport(
        logsRepo,
        sessionIds = listOf(sessionId),
        skips = listOf(
          SkippedTrail(
            trail_path = "/repo/trails/checkout/refund.trail.yaml",
            title = "Refund a payment",
            test_key = "checkout/refund",
            reason = "backend outage, see #2194",
            platform = "android",
            device_classifier = "android-phone",
            recorded_at_epoch_ms = 1_700_000_000_000,
          ),
        ),
      )
      assertNotNull(output, "generateJsonReport should have written a file")

      val report = Json { ignoreUnknownKeys = true }
        .decodeFromString<CiSummaryReport>(output.readText())
      val skipped = report.results.single { it.outcome == Outcome.SKIPPED }
      assertEquals("checkout/refund", skipped.test_key)
      assertEquals("backend outage, see #2194", skipped.failure_reason)
      assertEquals(2, report.results.size, "the trail that ran must still be there")
    } finally {
      tempDir.deleteRecursively()
    }
  }

  /** A minimal session that reaches a terminal status, so `buildSessionResult` produces a row. */
  private fun writeEndedSession(logsDir: File, sessionId: SessionId) {
    val deviceInfo = androidDeviceInfo()
    val started = Instant.parse("2026-08-27T12:00:00Z")
    writeLog(
      sessionDir = sessionDirFor(logsDir, sessionId),
      fileName = "001_TrailblazeSessionStatusChangeLog.json",
      log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Started(
          trailConfig = null,
          trailFilePath = "trails/checkout/pay.trail.yaml",
          hasRecordedSteps = false,
          testMethodName = "pay",
          testClassName = "CheckoutTest",
          trailblazeDeviceInfo = deviceInfo,
          trailblazeDeviceId = deviceInfo.trailblazeDeviceId,
          rawYaml = null,
        ),
        session = sessionId,
        timestamp = started,
      ),
    )
    writeLog(
      sessionDir = sessionDirFor(logsDir, sessionId),
      fileName = "002_TrailblazeSessionStatusChangeLog.json",
      log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 5_000),
        session = sessionId,
        timestamp = started.plus(5.seconds),
      ),
    )
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
