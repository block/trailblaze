package xyz.block.trailblaze.report

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.BoundedLogFileName
import xyz.block.trailblaze.logs.client.OnDeviceLogFileName
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

  @Test
  fun `a log from a long session id still lands in its session directory`() {
    // Real session ids carry a test's full suite/section/case identity and run past 220 bytes.
    // The device bounds its own file name, but moving the log appends the class name, which is
    // enough to push a name that was just under the limit back over it.
    val logsDir = Files.createTempDirectory("trailblaze-move-json-test-long").toFile()
    try {
      val session = SessionId("a".repeat(220))
      val deviceName = OnDeviceLogFileName.forLog(session, 1_758_512_345_678L)
      File(logsDir, deviceName).writeText(statusLogJson(session))

      moveJsonFilesToSessionDirs(logsDir)

      val moved = File(logsDir, session.value).listFiles().orEmpty().filter { it.extension == "json" }
      assertEquals(1, moved.size, "the log should have been moved into its session directory")
      assertTrue(
        moved.single().name.toByteArray().size <= BoundedLogFileName.NAME_MAX,
        "moved name must fit the filesystem limit: ${moved.single().name}",
      )
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `a log that cannot be moved is left where it was`() {
    // Deleting the source before the copy exists turns any failed write into a lost log.
    val logsDir = Files.createTempDirectory("trailblaze-move-json-test-unwritable").toFile()
    try {
      val session = SessionId("2026_09_23_blocked")
      // A plain file where the session directory should go makes the copy impossible.
      File(logsDir, session.value).writeText("not a directory")
      val source = File(logsDir, "${session.value}_1758512345678_000001.json").apply {
        writeText(statusLogJson(session))
      }

      moveJsonFilesToSessionDirs(logsDir)

      assertTrue(source.exists(), "a log whose copy failed must not be deleted")
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `a session whose only log carries a screenshot keeps both the log and the image`() {
    // The first log a session sees creates its directory. When that log carries a screenshot,
    // both files must end up there, or LogsRepo never reads the log.
    val logsDir = Files.createTempDirectory("trailblaze-move-json-test-screenshot").toFile()
    try {
      val session = SessionId("sess_one")
      val screenshotName = "sess_one_1.png"
      val screenshotBytes = byteArrayOf(1, 2, 3)
      File(logsDir, screenshotName).writeBytes(screenshotBytes)
      val snapshot = TrailblazeLog.TrailblazeSnapshotLog(
        displayName = null,
        screenshotFile = screenshotName,
        viewHierarchy = ViewHierarchyTreeNode(),
        deviceWidth = 1280,
        deviceHeight = 720,
        session = session,
        timestamp = Instant.parse("2026-09-24T18:00:00Z"),
      )
      File(logsDir, "sess_one_1_1.json").writeText(
        TrailblazeJsonInstance.encodeToString<TrailblazeLog>(snapshot),
      )

      // Both passes, in the order ReportMain.run runs them.
      moveScreenshotsToSessionDirs(logsDir, moveJsonFilesToSessionDirs(logsDir))

      assertEquals(
        setOf(session.value),
        logsDir.listFiles().orEmpty().map { it.name }.toSet(),
        "everything should have moved into the session directory",
      )
      val sessionDir = File(logsDir, session.value)
      val movedLog = sessionDir.listFiles().orEmpty().single { it.extension == "json" }
      val decoded = TrailblazeJsonInstance.decodeFromString<TrailblazeLog>(movedLog.readText())
      assertEquals(
        screenshotName,
        (decoded as TrailblazeLog.TrailblazeSnapshotLog).screenshotFile,
        "readers resolve screenshotFile against the session directory, so it stays a bare name",
      )
      assertTrue(
        File(sessionDir, screenshotName).readBytes().contentEquals(screenshotBytes),
        "the screenshot should sit beside its log",
      )
    } finally {
      logsDir.deleteRecursively()
    }
  }

  private fun statusLogJson(session: SessionId): String {
    val deviceInfo = webDeviceInfo()
    val log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = SessionStatus.Started(
        trailConfig = null,
        trailFilePath = null,
        hasRecordedSteps = false,
        testMethodName = "longTest",
        testClassName = "LongTest",
        trailblazeDeviceInfo = deviceInfo,
        trailblazeDeviceId = deviceInfo.trailblazeDeviceId,
        rawYaml = null,
      ),
      session = session,
      timestamp = Instant.parse("2026-09-23T18:00:00Z"),
    )
    return TrailblazeJsonInstance.encodeToString<TrailblazeLog>(log)
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
