package xyz.block.trailblaze.report

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.model.TrailblazeTargetAppInfo
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.BunBinaryResolver

/**
 * Pure-logic tests for the headless report generator's metadata mapping — the contract the viewer's
 * status badge, meta strip, and error banner consume. These don't touch bun, a device, or a logs
 * dir; the subprocess path is exercised by the JS-side run-report-core.test.ts + driver smoke test.
 */
class RunReportGeneratorTest {

  private fun info(
    status: SessionStatus,
    durationMs: Long = 12_345L,
    targetAppInfo: TrailblazeTargetAppInfo? = null,
  ) = SessionInfo(
    sessionId = SessionId("sess-1"),
    latestStatus = status,
    timestamp = Instant.fromEpochMilliseconds(1_700_000_000_000L),
    durationMs = durationMs,
    trailFilePath = "trails/Login/login.trail.yaml",
    hasRecordedSteps = true,
    targetAppInfo = targetAppInfo,
  )

  @Test
  fun statusLabel_mapsEachTerminalStatusToAViewerBadgeClass() {
    assertEquals("passed", RunReportGenerator.statusLabel(SessionStatus.Ended.Succeeded(1)))
    assertEquals("passed", RunReportGenerator.statusLabel(SessionStatus.Ended.SucceededWithSelfHeal(1)))
    assertEquals("failed", RunReportGenerator.statusLabel(SessionStatus.Ended.Failed(1, "boom")))
    assertEquals("failed", RunReportGenerator.statusLabel(SessionStatus.Ended.FailedWithSelfHeal(1, "boom")))
    assertEquals("failed", RunReportGenerator.statusLabel(SessionStatus.Ended.TimeoutReached(1, "slow")))
    assertEquals("failed", RunReportGenerator.statusLabel(SessionStatus.Ended.MaxCallsLimitReached(1, 5, "do x")))
    assertEquals("cancelled", RunReportGenerator.statusLabel(SessionStatus.Ended.Cancelled(1, "stopped")))
    assertEquals("unknown", RunReportGenerator.statusLabel(SessionStatus.Unknown))
  }

  @Test
  fun formatDuration_isHumanReadableAcrossScales() {
    assertEquals("450ms", RunReportGenerator.formatDuration(450))
    assertEquals("12.3s", RunReportGenerator.formatDuration(12_345))
    assertEquals("2m 5s", RunReportGenerator.formatDuration(125_000))
  }

  @Test
  fun sessionMetaJson_carriesTitleStatusDurationAndRerunCommand() {
    val meta = RunReportGenerator.sessionMetaJson(info(SessionStatus.Ended.Succeeded(12_345)), SessionStatus.Ended.Succeeded(12_345))
    // Title falls back to the trail file's short name when there's no explicit config title.
    assertEquals("Login/login", meta["title"]!!.jsonPrimitive.content)
    assertEquals("passed", meta["status"]!!.jsonPrimitive.content)
    assertEquals("12.3s", meta["duration"]!!.jsonPrimitive.content)
    assertEquals("trailblaze run trails/Login/login.trail.yaml", meta["cmd"]!!.jsonPrimitive.content)
    // A passing run has no error banner.
    assertNull(meta["error"])
  }

  @Test
  fun sessionMetaJson_carriesTargetAppIdentityAndComposedVersion() {
    val passed = SessionStatus.Ended.Succeeded(1)
    // Android shape: versionName + versionCode.
    val android = RunReportGenerator.sessionMetaJson(
      info(passed, targetAppInfo = TrailblazeTargetAppInfo(appId = "com.example.pos", versionName = "5.58.0.0", versionCode = "67500009")),
      passed,
    )
    assertEquals("com.example.pos", android["appId"]!!.jsonPrimitive.content)
    assertEquals("5.58.0.0 (67500009)", android["appVersion"]!!.jsonPrimitive.content)
    // iOS shape: the app-specific buildNumber wins over CFBundleVersion in the display string.
    val ios = RunReportGenerator.sessionMetaJson(
      info(passed, targetAppInfo = TrailblazeTargetAppInfo(appId = "com.example.pos", versionName = "6.94", versionCode = "6940515", buildNumber = "6515")),
      passed,
    )
    assertEquals("6.94 (6515)", ios["appVersion"]!!.jsonPrimitive.content)
    // App resolved but version probe came up empty: id still carried, no version row.
    val idOnly = RunReportGenerator.sessionMetaJson(
      info(passed, targetAppInfo = TrailblazeTargetAppInfo(appId = "com.example.pos")),
      passed,
    )
    assertEquals("com.example.pos", idOnly["appId"]!!.jsonPrimitive.content)
    assertNull(idOnly["appVersion"])
    // Sessions without a captured target app carry neither field.
    val none = RunReportGenerator.sessionMetaJson(info(passed), passed)
    assertNull(none["appId"])
    assertNull(none["appVersion"])
  }

  @Test
  fun sessionMetaJson_surfacesFailureReasonAsErrorBanner() {
    val failed = SessionStatus.Ended.Failed(900, "Could not find Pay button")
    val meta = RunReportGenerator.sessionMetaJson(info(failed), failed)
    assertEquals("failed", meta["status"]!!.jsonPrimitive.content)
    assertTrue(meta["error"]!!.jsonPrimitive.content.contains("Pay button"))
  }

  @Test
  fun sessionMetaJson_marksSelfHealRunsWithoutChangingTheirPassFailBadge() {
    val healed = SessionStatus.Ended.SucceededWithSelfHeal(5_000)
    val meta = RunReportGenerator.sessionMetaJson(info(healed), healed)
    assertEquals("passed", meta["status"]!!.jsonPrimitive.content)
    assertEquals(true, meta["selfHeal"]!!.jsonPrimitive.content.toBoolean())
    // A plain pass carries no self-heal marker.
    val plain = RunReportGenerator.sessionMetaJson(info(SessionStatus.Ended.Succeeded(1)), SessionStatus.Ended.Succeeded(1))
    assertNull(plain["selfHeal"])
  }

  /**
   * Full path: writes a real session to disk, then drives the actual classpath-resource load + bun
   * subprocess to produce the self-contained HTML. Skipped (vacuous pass) when bun isn't resolvable
   * so a bun-less local checkout doesn't fail; CI always has bun (a hard build prerequisite).
   */
  @Test
  fun generate_buildsSelfContainedReport_endToEnd() {
    val bun = BunBinaryResolver.resolveBunBinary() ?: return
    val tmp = Files.createTempDirectory("rrg-e2e-").toFile()
    try {
      val logsRepo = LogsRepo(logsDir = tmp, watchFileSystem = false)
      val sessionId = SessionId("e2etestsession")
      logsRepo.saveLogToDisk(
        TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 5_000L),
          session = sessionId,
          timestamp = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        ),
      )

      val report = RunReportGenerator(bunBinary = bun).generate(logsRepo, listOf(sessionId))

      assertNotNull(report, "generate() should produce a report file")
      assertTrue(report.exists() && report.length() > 0, "report file should be non-empty")
      val html = report.readText()
      assertTrue(html.contains("<!doctype html>"), "should be a full HTML document")
      assertTrue(html.contains("RUN_REPORT_VIEWER"), "should embed the standalone viewer")
      assertTrue(html.contains("e2etestsession"), "title falls back to the session id")
      assertTrue(html.contains("\"status\":\"passed\""), "succeeded session maps to a passed badge")
    } finally {
      tmp.deleteRecursively()
    }
  }
}
