package xyz.block.trailblaze.report

import com.github.ajalt.clikt.core.main
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
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
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.TrailConfig

/**
 * Exercises the orphan-MCP / MCP-helper session filtering in [GenerateTestResultsCliCommand].
 *
 * The MCP server opens a side-session (testClass=MCP, no trailConfig, no trailFilePath) every
 * time it takes a snapshot or answers a tool call — those aren't real test runs and should not
 * appear in the CI report. Orphan directories without any SessionStatusChangeLog should also be
 * skipped silently rather than showing up as errors. Real MCP-driven trail runs (which carry a
 * trailConfig) must still be included.
 */
class GenerateTestResultsCliCommandTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `run ignores orphan mcp-only session directories`() {
    val logsDir = Files.createTempDirectory("trailblaze-report-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val realSessionId = SessionId("2026_04_23_real_session")
      val orphanSessionId = SessionId("bf3b47c9_77dc_4bb4_bbf8_d52775ba5aea")
      val mcpHelperSessionId = SessionId("2026_04_23_mcp_helper_session")
      val deviceInfo = webDeviceInfo()

      writeLog(
        logsDir = logsDir,
        sessionId = realSessionId,
        fileName = "001_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
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
          session = realSessionId,
          timestamp = Instant.parse("2026-04-23T18:10:00Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = realSessionId,
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 5_000),
          session = realSessionId,
          timestamp = Instant.parse("2026-04-23T18:10:05Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = orphanSessionId,
        fileName = "00a_ObjectiveStartLog.json",
        log = TrailblazeLog.ObjectiveStartLog(
          promptStep = DirectionStep(step = "Capture screen state"),
          session = orphanSessionId,
          timestamp = Instant.parse("2026-04-23T18:10:01Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = mcpHelperSessionId,
        fileName = "001_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Started(
            trailConfig = null,
            trailFilePath = null,
            hasRecordedSteps = false,
            testMethodName = "Capture screen state",
            testClassName = "MCP",
            trailblazeDeviceInfo = deviceInfo,
            trailblazeDeviceId = deviceInfo.trailblazeDeviceId,
            rawYaml = null,
          ),
          session = mcpHelperSessionId,
          timestamp = Instant.parse("2026-04-23T10:00:00Z"),
        ),
      )

      val outputBuffer = captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      assertEquals(1, report.results.size)
      assertEquals(realSessionId, report.results.single().session_id)
      assertFalse(outputBuffer.contains("PROCESSING ERRORS"))
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `run includes sessions with self-heal succeeded status in report as passed`() {
    val logsDir = Files.createTempDirectory("trailblaze-report-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val selfHealSessionId = SessionId("2026_04_23_self_heal_session")
      val deviceInfo = webDeviceInfo()

      writeLog(
        logsDir = logsDir,
        sessionId = selfHealSessionId,
        fileName = "001_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Started(
            trailConfig = null,
            trailFilePath = "trails/sample-app/self-heal.trail.yaml",
            hasRecordedSteps = true,
            testMethodName = "selfHealTest",
            testClassName = "WebSelfHealTest",
            trailblazeDeviceInfo = deviceInfo,
            trailblazeDeviceId = deviceInfo.trailblazeDeviceId,
            rawYaml = null,
          ),
          session = selfHealSessionId,
          timestamp = Instant.parse("2026-04-23T18:10:00Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = selfHealSessionId,
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.SucceededWithSelfHeal(durationMs = 8_000),
          session = selfHealSessionId,
          timestamp = Instant.parse("2026-04-23T18:10:08Z"),
        ),
      )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      assertEquals(1, report.results.size)
      val result = report.results.single()
      assertEquals(selfHealSessionId, result.session_id)
      assertEquals(Outcome.PASSED, result.outcome)
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `run includes sessions with self-heal failed status in report as failed`() {
    val logsDir = Files.createTempDirectory("trailblaze-report-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val selfHealSessionId = SessionId("2026_04_23_self_heal_failed_session")
      val deviceInfo = webDeviceInfo()

      writeLog(
        logsDir = logsDir,
        sessionId = selfHealSessionId,
        fileName = "001_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Started(
            trailConfig = null,
            trailFilePath = "trails/sample-app/self-heal.trail.yaml",
            hasRecordedSteps = true,
            testMethodName = "selfHealFailedTest",
            testClassName = "WebSelfHealFailedTest",
            trailblazeDeviceInfo = deviceInfo,
            trailblazeDeviceId = deviceInfo.trailblazeDeviceId,
            rawYaml = null,
          ),
          session = selfHealSessionId,
          timestamp = Instant.parse("2026-04-23T18:10:00Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = selfHealSessionId,
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.FailedWithSelfHeal(
            durationMs = 8_000,
            exceptionMessage = "self-heal could not recover the recording",
          ),
          session = selfHealSessionId,
          timestamp = Instant.parse("2026-04-23T18:10:08Z"),
        ),
      )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      assertEquals(1, report.results.size)
      val result = report.results.single()
      assertEquals(selfHealSessionId, result.session_id)
      assertEquals(Outcome.FAILED, result.outcome)
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `run excludes multiple mcp helper sessions while keeping real ones`() {
    val logsDir = Files.createTempDirectory("trailblaze-report-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val passedSessionId = SessionId("2026_04_23_passed_session")
      val failedSessionId = SessionId("2026_04_23_failed_session")
      val mcpHelper1 = SessionId("2026_04_23_mcp_helper_1")
      val mcpHelper2 = SessionId("2026_04_23_mcp_helper_2")
      val mcpHelper3 = SessionId("2026_04_23_mcp_helper_3")
      val deviceInfo = webDeviceInfo()

      writeLog(
        logsDir = logsDir,
        sessionId = passedSessionId,
        fileName = "001_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
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
          session = passedSessionId,
          timestamp = Instant.parse("2026-04-23T18:10:00Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = passedSessionId,
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 5_000),
          session = passedSessionId,
          timestamp = Instant.parse("2026-04-23T18:10:05Z"),
        ),
      )

      writeLog(
        logsDir = logsDir,
        sessionId = failedSessionId,
        fileName = "001_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Started(
            trailConfig = null,
            trailFilePath = "trails/sample-app/login.trail.yaml",
            hasRecordedSteps = false,
            testMethodName = "loginTest",
            testClassName = "WebLoginTest",
            trailblazeDeviceInfo = deviceInfo,
            trailblazeDeviceId = deviceInfo.trailblazeDeviceId,
            rawYaml = null,
          ),
          session = failedSessionId,
          timestamp = Instant.parse("2026-04-23T18:11:00Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = failedSessionId,
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Failed(
            durationMs = 3_000,
            exceptionMessage = "Element not found",
          ),
          session = failedSessionId,
          timestamp = Instant.parse("2026-04-23T18:11:03Z"),
        ),
      )

      for ((i, mcpId) in listOf(mcpHelper1, mcpHelper2, mcpHelper3).withIndex()) {
        writeLog(
          logsDir = logsDir,
          sessionId = mcpId,
          fileName = "001_TrailblazeSessionStatusChangeLog.json",
          log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
            sessionStatus = SessionStatus.Started(
              trailConfig = null,
              trailFilePath = null,
              hasRecordedSteps = false,
              testMethodName = "Capture screen state",
              testClassName = "MCP",
              trailblazeDeviceInfo = deviceInfo,
              trailblazeDeviceId = deviceInfo.trailblazeDeviceId,
              rawYaml = null,
            ),
            session = mcpId,
            timestamp = Instant.parse("2026-04-23T18:1${i}:00Z"),
          ),
        )
      }

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      assertEquals(2, report.results.size)
      val resultIds = report.results.map { it.session_id }.toSet()
      assertTrue(passedSessionId in resultIds)
      assertTrue(failedSessionId in resultIds)
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `run handles mixed real sessions and orphan directories without errors`() {
    val logsDir = Files.createTempDirectory("trailblaze-report-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val realSession1 = SessionId("2026_04_23_real_session_1")
      val realSession2 = SessionId("2026_04_23_real_session_2")
      val orphanSessionId = SessionId("abc12345_orphan_no_status")
      val mcpHelperSessionId = SessionId("2026_04_23_mcp_helper")
      val deviceInfo = webDeviceInfo()

      writeLog(
        logsDir = logsDir,
        sessionId = realSession1,
        fileName = "001_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
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
          session = realSession1,
          timestamp = Instant.parse("2026-04-23T18:10:00Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = realSession1,
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 5_000),
          session = realSession1,
          timestamp = Instant.parse("2026-04-23T18:10:05Z"),
        ),
      )

      writeLog(
        logsDir = logsDir,
        sessionId = realSession2,
        fileName = "001_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Started(
            trailConfig = null,
            trailFilePath = "trails/sample-app/checkout.trail.yaml",
            hasRecordedSteps = false,
            testMethodName = "checkoutTest",
            testClassName = "WebCheckoutTest",
            trailblazeDeviceInfo = deviceInfo,
            trailblazeDeviceId = deviceInfo.trailblazeDeviceId,
            rawYaml = null,
          ),
          session = realSession2,
          timestamp = Instant.parse("2026-04-23T18:11:00Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = realSession2,
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 7_000),
          session = realSession2,
          timestamp = Instant.parse("2026-04-23T18:11:07Z"),
        ),
      )

      writeLog(
        logsDir = logsDir,
        sessionId = orphanSessionId,
        fileName = "00a_ObjectiveStartLog.json",
        log = TrailblazeLog.ObjectiveStartLog(
          promptStep = DirectionStep(step = "Capture screen state"),
          session = orphanSessionId,
          timestamp = Instant.parse("2026-04-23T18:10:01Z"),
        ),
      )

      writeLog(
        logsDir = logsDir,
        sessionId = mcpHelperSessionId,
        fileName = "001_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Started(
            trailConfig = null,
            trailFilePath = null,
            hasRecordedSteps = false,
            testMethodName = "Capture screen state",
            testClassName = "MCP",
            trailblazeDeviceInfo = deviceInfo,
            trailblazeDeviceId = deviceInfo.trailblazeDeviceId,
            rawYaml = null,
          ),
          session = mcpHelperSessionId,
          timestamp = Instant.parse("2026-04-23T10:00:00Z"),
        ),
      )

      val outputBuffer = captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      assertEquals(2, report.results.size)
      assertFalse(outputBuffer.contains("PROCESSING ERRORS"))
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `mcp session with trail config is not filtered as helper`() {
    val logsDir = Files.createTempDirectory("trailblaze-report-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val mcpTrailSessionId = SessionId("2026_04_23_mcp_trail_session")
      val realSessionId = SessionId("2026_04_23_real_session")
      val deviceInfo = webDeviceInfo()

      writeLog(
        logsDir = logsDir,
        sessionId = mcpTrailSessionId,
        fileName = "001_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Started(
            trailConfig = TrailConfig(id = "mcp-trail", title = "MCP Trail Test"),
            trailFilePath = null,
            hasRecordedSteps = false,
            testMethodName = "MCP Trail Test",
            testClassName = "MCP",
            trailblazeDeviceInfo = deviceInfo,
            trailblazeDeviceId = deviceInfo.trailblazeDeviceId,
            rawYaml = null,
          ),
          session = mcpTrailSessionId,
          timestamp = Instant.parse("2026-04-23T18:10:00Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = mcpTrailSessionId,
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 6_000),
          session = mcpTrailSessionId,
          timestamp = Instant.parse("2026-04-23T18:10:06Z"),
        ),
      )

      writeLog(
        logsDir = logsDir,
        sessionId = realSessionId,
        fileName = "001_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
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
          session = realSessionId,
          timestamp = Instant.parse("2026-04-23T18:11:00Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = realSessionId,
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 5_000),
          session = realSessionId,
          timestamp = Instant.parse("2026-04-23T18:11:05Z"),
        ),
      )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      assertEquals(2, report.results.size)
      val resultIds = report.results.map { it.session_id }.toSet()
      assertTrue(mcpTrailSessionId in resultIds)
      assertTrue(realSessionId in resultIds)
    } finally {
      logsDir.deleteRecursively()
    }
  }

  private fun writeLog(
    logsDir: File,
    sessionId: SessionId,
    fileName: String,
    log: TrailblazeLog,
  ) {
    val sessionDir = File(logsDir, sessionId.value).apply { mkdirs() }
    File(sessionDir, fileName).writeText(TrailblazeJsonInstance.encodeToString<TrailblazeLog>(log))
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

  private inline fun captureStdout(block: () -> Unit): String {
    val original = System.out
    val buffer = ByteArrayOutputStream()
    System.setOut(PrintStream(buffer, true))
    try {
      block()
    } finally {
      System.setOut(original)
    }
    return buffer.toString()
  }
}
