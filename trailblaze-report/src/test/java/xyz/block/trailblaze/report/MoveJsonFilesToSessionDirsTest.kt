package xyz.block.trailblaze.report

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus

/**
 * [moveJsonFilesToSessionDirs] iterates every `.json` file under the logs dir and tries to
 * decode each as [TrailblazeLog]. The aggregate `trailblaze_test_report.json` produced by
 * [GenerateTestResultsCliCommand] is a different schema and would surface as a noisy
 * `Class discriminator was missing` runtime error — even though the report generation itself
 * succeeds. This test pins the filename-level skip so the noise doesn't return.
 */
class MoveJsonFilesToSessionDirsTest {

  @Test
  fun `skips aggregate trailblaze_test_report file but processes real log events`() {
    val logsDir = Files.createTempDirectory("trailblaze-move-json-test").toFile()
    try {
      val deviceInfo = webDeviceInfo()
      val realLog = TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Started(
          trailConfig = null,
          trailFilePath = "trails/sample-app/smoke.trail.yaml",
          hasRecordedSteps = false,
          testMethodName = "smokeTest",
          testClassName = "WebSmokeTest",
          trailblazeDeviceInfo = deviceInfo,
          trailblazeDeviceId = deviceInfo.trailblazeDeviceId,
          rawYaml = null,
        ),
        session = SessionId("2026_05_18_sample"),
        timestamp = Instant.parse("2026-05-18T18:00:00Z"),
      )
      val realLogFile = File(logsDir, "001_TrailblazeSessionStatusChangeLog.json").apply {
        writeText(TrailblazeJsonInstance.encodeToString<TrailblazeLog>(realLog))
      }

      // Aggregate test-report shape: NOT a TrailblazeLog. Older CI paths left this alongside
      // raw log events and the polymorphic decode threw `Class discriminator was missing`.
      val aggregateReport = File(logsDir, "trailblaze_test_report.json").apply {
        writeText("""{"metadata":{"git_commit":"deadbeef"},"results":[]}""")
      }

      moveJsonFilesToSessionDirs(logsDir)

      // The aggregate report stays where it is — neither processed nor deleted.
      assertTrue(aggregateReport.exists(), "trailblaze_test_report.json should be left untouched")

      // The real log was moved into its session subdir.
      assertFalse(realLogFile.exists(), "real log file should be moved out of the top-level dir")
      val sessionDir = File(logsDir, "2026_05_18_sample")
      assertTrue(sessionDir.isDirectory, "session subdir should have been created")
      assertTrue(
        sessionDir.listFiles().orEmpty().any { it.extension == "json" },
        "session subdir should now contain the moved log JSON",
      )
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `skips suffixed trailblaze_test_report_context file too`() {
    val logsDir = Files.createTempDirectory("trailblaze-move-json-test-suffixed").toFile()
    try {
      // CI now writes per-context aggregate reports (`trailblaze_test_report_<context>.json`)
      // — also not TrailblazeLogs.
      val suffixed = File(logsDir, "trailblaze_test_report_sample_app.json").apply {
        writeText("""{"metadata":{},"results":[]}""")
      }

      moveJsonFilesToSessionDirs(logsDir)

      assertTrue(suffixed.exists(), "context-suffixed aggregate report should also be skipped")
    } finally {
      logsDir.deleteRecursively()
    }
  }

  private fun webDeviceInfo(): TrailblazeDeviceInfo {
    val deviceId = TrailblazeDeviceId(
      instanceId = "web",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
    )
    return TrailblazeDeviceInfo(
      trailblazeDeviceId = deviceId,
      trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      widthPixels = 1280,
      heightPixels = 720,
      classifiers = listOf(TrailblazeDevicePlatform.WEB.asTrailblazeDeviceClassifier()),
    )
  }
}
