package xyz.block.trailblaze.report

import com.github.ajalt.clikt.core.main
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.CaptureCoverage
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.TrailblazeLlmModels
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.TrailblazeTargetAppInfo
import xyz.block.trailblaze.report.models.CiSummaryReport
import xyz.block.trailblaze.report.models.ExecutionMode
import xyz.block.trailblaze.report.models.Outcome
import xyz.block.trailblaze.report.models.SessionResult
import xyz.block.trailblaze.report.models.TriageReport
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
  fun `session-start targetAppInfo is carried into the per-session app fields`() {
    val logsDir = Files.createTempDirectory("trailblaze-report-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val sessionId = SessionId("2026_07_06_app_info_session")
      val deviceInfo = webDeviceInfo()

      writeLog(
        logsDir = logsDir,
        sessionId = sessionId,
        fileName = "001_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Started(
            trailConfig = null,
            trailFilePath = "trails/sample-app/app-info.trail.yaml",
            hasRecordedSteps = true,
            testMethodName = "appInfoTest",
            testClassName = "AppInfoTest",
            trailblazeDeviceInfo = deviceInfo,
            trailblazeDeviceId = deviceInfo.trailblazeDeviceId,
            rawYaml = null,
            targetAppInfo = TrailblazeTargetAppInfo(
              appId = "com.example.pos",
              versionName = "6.53.2",
              versionCode = "6532000",
              buildNumber = "6515",
            ),
          ),
          session = sessionId,
          timestamp = Instant.parse("2026-07-06T07:53:39Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = sessionId,
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 5_000),
          session = sessionId,
          timestamp = Instant.parse("2026-07-06T07:53:44Z"),
        ),
      )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      val result = report.results.single()
      assertEquals("com.example.pos", result.app_id)
      assertEquals("6.53.2", result.app_version_name)
      assertEquals("6532000", result.app_version_code)
      assertEquals("6515", result.app_build_number)
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

  @Test
  fun `host_ci_context sidecar without logs_zip_url field still decodes`() {
    // Back-compat guard: the sidecar shape grew a `logs_zip_url` field for #3388, but
    // any zip that was uploaded before that change carries the older two-field shape.
    // The report generator must still decode those without dropping ci_job_id /
    // logs_zip_filename — only the URL is allowed to be null.
    val logsDir = Files.createTempDirectory("trailblaze-report-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val sessionId = SessionId("2026_05_26_session_no_url")
      val deviceInfo = webDeviceInfo()

      writeLog(
        logsDir = logsDir,
        sessionId = sessionId,
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
          session = sessionId,
          timestamp = Instant.parse("2026-05-26T18:10:00Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = sessionId,
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 4_000),
          session = sessionId,
          timestamp = Instant.parse("2026-05-26T18:10:04Z"),
        ),
      )
      // Old-shape sidecar — no logs_zip_url. Strict decoders that don't tolerate missing
      // fields would throw and the report generator's fallback would mask the bug; this
      // assertion catches that regression.
      File(logsDir, sessionId.value).resolve(GenerateTestResultsCliCommand.HOST_CI_CONTEXT_FILENAME)
        .writeText(
          """
          {
            "ci_job_id": "job-uuid-abc",
            "logs_zip_filename": "logs_smoke_0__${sessionId.value}.zip"
          }
          """.trimIndent(),
        )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      val row = report.results.single { it.session_id == sessionId }
      assertEquals("job-uuid-abc", row.ci_job_id)
      assertEquals("logs_smoke_0__${sessionId.value}.zip", row.logs_zip_filename)
      assertEquals(null, row.logs_zip_url)
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `host_ci_context sidecar with unknown keys still decodes (forward-compat)`() {
    // Forward-compat guard: a future upload-script revision may add keys this reader
    // hasn't been taught about yet. The sidecar decoder is configured with
    // `ignoreUnknownKeys = true` so the new key is dropped and the existing fields
    // still propagate. Without that, the strict default would throw inside the try /
    // catch and the report generator would silently fall back to env vars — losing the
    // CI provenance the sidecar was supposed to carry.
    val logsDir = Files.createTempDirectory("trailblaze-report-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val sessionId = SessionId("2026_05_26_session_future_key")
      val deviceInfo = webDeviceInfo()

      writeLog(
        logsDir = logsDir,
        sessionId = sessionId,
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
          session = sessionId,
          timestamp = Instant.parse("2026-05-26T18:12:00Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = sessionId,
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 2_000),
          session = sessionId,
          timestamp = Instant.parse("2026-05-26T18:12:02Z"),
        ),
      )
      // Sidecar contains an unknown `future_field` AND a normal `logs_zip_filename`.
      // Strict decoding would throw; lenient should accept the known fields.
      File(logsDir, sessionId.value).resolve(GenerateTestResultsCliCommand.HOST_CI_CONTEXT_FILENAME)
        .writeText(
          """
          {
            "ci_job_id": "job-future",
            "logs_zip_filename": "logs_future_0__${sessionId.value}.zip",
            "future_field": "something a later writer added"
          }
          """.trimIndent(),
        )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      val row = report.results.single { it.session_id == sessionId }
      assertEquals("job-future", row.ci_job_id)
      assertEquals("logs_future_0__${sessionId.value}.zip", row.logs_zip_filename)
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `host_ci_context sidecar logs_zip_url is decoded but not propagated`() {
    // Back-compat check: old sidecars may carry authenticated artifact URLs. The report generator must
    // still decode the sidecar for ci_job_id and logs_zip_filename, but it must not propagate the
    // old authenticated URL into raw JSON reports. Internal CI rewrites logs_zip_url to the
    // CloudFront/S3 immutable run URL before publishing report artifacts.
    val logsDir = Files.createTempDirectory("trailblaze-report-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val sessionId = SessionId("2026_05_26_session_with_url")
      val deviceInfo = webDeviceInfo()
      val expectedUrl = "https://buildkite.com/organizations/example-org/pipelines/example-pipeline/builds/42/jobs/job-uuid-xyz/artifacts/artifact-uuid-123"

      writeLog(
        logsDir = logsDir,
        sessionId = sessionId,
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
          session = sessionId,
          timestamp = Instant.parse("2026-05-26T18:11:00Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = sessionId,
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 3_000),
          session = sessionId,
          timestamp = Instant.parse("2026-05-26T18:11:03Z"),
        ),
      )
      File(logsDir, sessionId.value).resolve(GenerateTestResultsCliCommand.HOST_CI_CONTEXT_FILENAME)
        .writeText(
          """
          {
            "ci_job_id": "job-uuid-xyz",
            "logs_zip_filename": "logs_login_0__${sessionId.value}.zip",
            "logs_zip_url": "$expectedUrl"
          }
          """.trimIndent(),
        )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      val row = report.results.single { it.session_id == sessionId }
      assertEquals("job-uuid-xyz", row.ci_job_id)
      assertEquals("logs_login_0__${sessionId.value}.zip", row.logs_zip_filename)
      assertEquals(null, row.logs_zip_url)
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `accessibility_truncation rolls up captureCoverage from driver logs into the JSON`() {
    // End-to-end guard for the report wiring of PR #4143: an AgentDriverLog carrying a
    // looksTruncated CaptureCoverage must surface in the report JSON under
    // accessibility_truncation.captures_truncated. The aggregator's own unit tests
    // (AccessibilityTruncationSummaryTest) cover fromLogs(); this pins the call-site wiring in
    // GenerateTestResultsCliCommand and the JSON shape downstream consumers will read.
    val logsDir = Files.createTempDirectory("trailblaze-report-a11y-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val sessionId = SessionId("2026_06_26_android_truncated_session")
      val deviceInfo = androidDeviceInfo()
      val started = Instant.parse("2026-06-26T12:00:00Z")

      writeLog(
        logsDir = logsDir,
        sessionId = sessionId,
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
      // Two captures: one flagged truncated, one fine. Aggregator should count both in
      // captures_total and only the first in captures_truncated.
      writeLog(
        logsDir = logsDir,
        sessionId = sessionId,
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
        logsDir = logsDir,
        sessionId = sessionId,
        fileName = "003_AgentDriverLog.json",
        log = TrailblazeLog.AgentDriverLog(
          viewHierarchy = ViewHierarchyTreeNode(),
          screenshotFile = "screenshot_complete.png",
          action = AgentDriverAction.TapPoint(x = 540, y = 1500),
          captureCoverage = CaptureCoverage(
            contentNodes = 14,
            zeroBoundsContentNodes = 0,
            horizontalCoverage = 0.94,
            verticalCoverage = 0.88,
            looksTruncated = false,
            reason = "content spans 94% of width / 88% of height — looks complete",
          ),
          durationMs = 280,
          session = sessionId,
          timestamp = started.plus(4.seconds),
          deviceHeight = 2400,
          deviceWidth = 1080,
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = sessionId,
        fileName = "004_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 5_000),
          session = sessionId,
          timestamp = started.plus(5.seconds),
        ),
      )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      val summary = report.results.single().accessibility_truncation
      assertNotNull(summary, "accessibility_truncation must be populated when logs carry coverage")
      assertEquals(2, summary.captures_total)
      assertEquals(1, summary.captures_truncated)
      assertEquals(1, summary.examples.size)
      assertTrue(
        summary.examples.single().reason.contains("right edge"),
        "the example should carry the detector's reason verbatim — got ${summary.examples.single().reason}",
      )
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `recorded session that made an LLM call is reported as RECORDING_WITH_AI`() {
    // End-to-end guard for the AI_ONLY-mislabel fix: a recorded trail (hasRecordedSteps = true)
    // that emits even one TrailblazeLlmRequestLog must surface as RECORDING_WITH_AI, not AI_ONLY.
    // This pins the production wiring the ExecutionMode.classify unit tests can't: that the call
    // site passes hasRecordedSteps, AND that SessionRecordingInfo.fromLogs still maps "has an LLM
    // request log" -> available = false (made LLM calls).
    val logsDir = Files.createTempDirectory("trailblaze-report-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val sessionId = SessionId("2026_04_23_recorded_with_ai_session")
      val deviceInfo = webDeviceInfo()

      writeLog(
        logsDir = logsDir,
        sessionId = sessionId,
        fileName = "001_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Started(
            trailConfig = null,
            trailFilePath = "trails/sample-app/recorded-with-verify.trail.yaml",
            hasRecordedSteps = true,
            testMethodName = "recordedWithVerifyTest",
            testClassName = "WebRecordedWithVerifyTest",
            trailblazeDeviceInfo = deviceInfo,
            trailblazeDeviceId = deviceInfo.trailblazeDeviceId,
            rawYaml = null,
          ),
          session = sessionId,
          timestamp = Instant.parse("2026-04-23T18:10:00Z"),
        ),
      )
      // One LLM request (e.g. an LLM-backed `verify`) inside an otherwise-recorded trail.
      writeLog(
        logsDir = logsDir,
        sessionId = sessionId,
        fileName = "002_TrailblazeLlmRequestLog.json",
        log = llmRequestLog(sessionId, Instant.parse("2026-04-23T18:10:02Z")),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = sessionId,
        fileName = "003_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 5_000),
          session = sessionId,
          timestamp = Instant.parse("2026-04-23T18:10:05Z"),
        ),
      )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      val result = report.results.single()
      assertEquals(ExecutionMode.RECORDING_WITH_AI, result.execution_mode)
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `normalizeFailureSignature strips variable content and keeps only the first line`() {
    val cmd = GenerateTestResultsCliCommand()

    // null -> stable placeholder so all reason-less failures group together
    assertEquals("(no failure reason)", cmd.normalizeFailureSignature(null))

    // session ids collapse to a stable token
    assertEquals(
      "Trail failed for <session_id>",
      cmd.normalizeFailureSignature("Trail failed for 2026_06_15_14_03_22_sampleSession"),
    )

    // hex addresses and long hex hashes
    assertEquals("NPE at <addr>", cmd.normalizeFailureSignature("NPE at 0xDEADBEEF"))
    assertEquals(
      "artifact <hash> missing",
      cmd.normalizeFailureSignature("artifact a1b2c3d4e5f6 missing"),
    )

    // absolute file paths
    assertEquals(
      "could not read <path>",
      cmd.normalizeFailureSignature("could not read /var/folders/xy/abc/screenshot.png"),
    )

    // Multi-line reasons keep only the headline (first line), not the trailing stack trace.
    // This is the behavior the comment promised but the original ordering (collapse-then-split)
    // did not deliver — newlines became spaces, so the whole reason survived as one line.
    assertEquals(
      "Element not found: More",
      cmd.normalizeFailureSignature("Element not found: More\n  at Foo.bar()\n  at Baz.qux()"),
    )

    // Two failures that differ only by their session id normalize to the same signature, so
    // they group together in the triage report.
    assertEquals(
      cmd.normalizeFailureSignature("Trail failed for 2026_06_15_14_03_22_alpha"),
      cmd.normalizeFailureSignature("Trail failed for 2026_06_14_09_11_00_bravo"),
    )
  }

  @Test
  fun `triage report counts genuine retries but not all-passing duplicate logs`() {
    val logsDir = Files.createTempDirectory("trailblaze-triage-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val deviceInfo = webDeviceInfo()

      // Group 1: genuine flaky — failed first, passed on the retry.
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_flaky_attempt1"),
        trailFilePath = "trails/sample-app/flaky.trail.yaml",
        startedAt = "2026-06-15T10:00:00Z",
        ended = SessionStatus.Ended.Failed(durationMs = 3_000, exceptionMessage = "Element not found: More"),
      )
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_flaky_attempt2"),
        trailFilePath = "trails/sample-app/flaky.trail.yaml",
        startedAt = "2026-06-15T10:05:00Z",
        ended = SessionStatus.Ended.Succeeded(durationMs = 4_000),
      )

      // Group 2: all-passing duplicate logs — both attempts passed, with NO prior failure.
      // The old `total_attempts > 1` heuristic would wrongly count this as passed-on-retry;
      // keying on replaced_failure_reasons must NOT.
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_dupe_attempt1"),
        trailFilePath = "trails/sample-app/dupe.trail.yaml",
        startedAt = "2026-06-15T10:00:00Z",
        ended = SessionStatus.Ended.Succeeded(durationMs = 5_000),
      )
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_dupe_attempt2"),
        trailFilePath = "trails/sample-app/dupe.trail.yaml",
        startedAt = "2026-06-15T10:05:00Z",
        ended = SessionStatus.Ended.Succeeded(durationMs = 5_000),
      )

      // Group 3: persistent failure — failed on every attempt.
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_broken_attempt1"),
        trailFilePath = "trails/sample-app/broken.trail.yaml",
        startedAt = "2026-06-15T10:00:00Z",
        ended = SessionStatus.Ended.Failed(durationMs = 2_000, exceptionMessage = "Delivery not visible"),
      )
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_broken_attempt2"),
        trailFilePath = "trails/sample-app/broken.trail.yaml",
        startedAt = "2026-06-15T10:05:00Z",
        ended = SessionStatus.Ended.Failed(durationMs = 2_000, exceptionMessage = "Delivery not visible"),
      )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON", "--triage"),
        )
      }

      // The standard report is always deduplicated (no flag needed): 6 attempts -> 3 test cases.
      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      assertEquals(3, report.results.size)

      // --triage emits the triage report alongside the standard report.
      val triageFile = File(logsDir, "trailblaze_triage_report.json")
      assertTrue(triageFile.exists(), "triage report should be written")
      val triage = json.decodeFromString<TriageReport>(triageFile.readText())

      // Each retry group collapses to a single test case.
      assertEquals(3, triage.summary.total_test_cases)
      assertEquals(2, triage.summary.passed)
      assertEquals(1, triage.summary.failed)

      // Only the genuine flaky counts as passed-on-retry; the all-passing duplicate must not.
      assertEquals(1, triage.retries.passed_on_retry)
      assertEquals(1, triage.retries.failed_after_retries)
      assertEquals(6, triage.retries.total_attempts)
      assertEquals(3, triage.retries.unique_test_cases)

      // Exactly one failing signature group (the persistent failure) covering all failures.
      assertEquals(1, triage.failure_signatures.size)
      assertEquals(1, triage.failure_signatures.single().count)
      assertEquals(1.0, triage.failure_signatures.single().share)

      // by_platform axis: 2 passed / 1 failed on web.
      val webBucket = triage.failure_axes.by_platform["web"]
      assertEquals(2, webBucket?.passed)
      assertEquals(1, webBucket?.failed)
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `without --triage the report is deduplicated but no triage file is written`() {
    // Triage is opt-in (it's a combined/cross-device aggregation, emitted only by the
    // aggregation step). A plain run still deduplicates retries — it just doesn't write the
    // triage artifact.
    val logsDir = Files.createTempDirectory("trailblaze-no-triage-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val deviceInfo = webDeviceInfo()
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_flaky_attempt1"),
        trailFilePath = "trails/sample-app/flaky.trail.yaml",
        startedAt = "2026-06-15T10:00:00Z",
        ended = SessionStatus.Ended.Failed(durationMs = 3_000, exceptionMessage = "Element not found: More"),
      )
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_flaky_attempt2"),
        trailFilePath = "trails/sample-app/flaky.trail.yaml",
        startedAt = "2026-06-15T10:05:00Z",
        ended = SessionStatus.Ended.Succeeded(durationMs = 4_000),
      )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      // Still deduplicated: the two attempts collapse to one passing test.
      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      assertEquals(1, report.results.size)
      assertEquals(Outcome.PASSED, report.results.single().outcome)

      // But no triage artifact without --triage.
      assertFalse(
        File(logsDir, "trailblaze_triage_report.json").exists(),
        "triage report should not be written without --triage",
      )
    } finally {
      logsDir.deleteRecursively()
    }
  }

  /** A minimal [TrailblazeLog.TrailblazeLlmRequestLog] — only its presence matters here. */
  private fun llmRequestLog(
    sessionId: SessionId,
    timestamp: Instant,
  ): TrailblazeLog.TrailblazeLlmRequestLog = TrailblazeLog.TrailblazeLlmRequestLog(
    agentTaskStatus = AgentTaskStatus.InProgress(
      statusData = AgentTaskStatusData(
        taskId = TaskId.generate(),
        prompt = "verify the screen",
        callCount = 0,
        taskStartTime = timestamp,
        totalDurationMs = 0,
      ),
    ),
    viewHierarchy = ViewHierarchyTreeNode(),
    instructions = "",
    trailblazeLlmModel = TrailblazeLlmModels.GPT_4O_MINI,
    llmMessages = emptyList(),
    llmResponse = emptyList(),
    actions = emptyList(),
    toolOptions = emptyList(),
    screenshotFile = null,
    durationMs = 0,
    session = sessionId,
    timestamp = timestamp,
    traceId = TraceId.generate(TraceId.Companion.TraceOrigin.LLM),
    deviceHeight = 0,
    deviceWidth = 0,
  )

  /** Writes a single trail run (Started + Ended status logs) for a session under [logsDir]. */
  private fun writeTrailRun(
    logsDir: File,
    deviceInfo: TrailblazeDeviceInfo,
    sessionId: SessionId,
    trailFilePath: String,
    startedAt: String,
    ended: SessionStatus.Ended,
  ) {
    writeLog(
      logsDir = logsDir,
      sessionId = sessionId,
      fileName = "001_TrailblazeSessionStatusChangeLog.json",
      log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Started(
          trailConfig = null,
          trailFilePath = trailFilePath,
          hasRecordedSteps = false,
          testMethodName = "run",
          testClassName = "WebTrailTest",
          trailblazeDeviceInfo = deviceInfo,
          trailblazeDeviceId = deviceInfo.trailblazeDeviceId,
          rawYaml = null,
        ),
        session = sessionId,
        timestamp = Instant.parse(startedAt),
      ),
    )
    writeLog(
      logsDir = logsDir,
      sessionId = sessionId,
      fileName = "002_TrailblazeSessionStatusChangeLog.json",
      log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = ended,
        session = sessionId,
        // Ended must be strictly after Started, otherwise the latest-status resolution is
        // ambiguous and the run can be read as still-Started (non-PASSED).
        timestamp = Instant.parse(startedAt).plus(10.seconds),
      ),
    )
  }

  @Test
  fun `each session directory gets its own session_result sidecar`() {
    // The sidecar is the session's own entry from the report's results[], written into
    // the session directory so a per-session log zip is self-describing (the upload
    // script zips session directories after report generation).
    val logsDir = Files.createTempDirectory("trailblaze-report-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val sessionId = SessionId("2026_07_13_sidecar_session")
      val deviceInfo = webDeviceInfo()

      writeLog(
        logsDir = logsDir,
        sessionId = sessionId,
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
          session = sessionId,
          timestamp = Instant.parse("2026-07-13T10:00:00Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = sessionId,
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 5_000),
          session = sessionId,
          timestamp = Instant.parse("2026-07-13T10:00:05Z"),
        ),
      )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      val sidecarFile = File(logsDir, sessionId.value)
        .resolve(GenerateTestResultsCliCommand.SESSION_RESULT_FILENAME)
      assertTrue(
        sidecarFile.exists(),
        "expected ${GenerateTestResultsCliCommand.SESSION_RESULT_FILENAME} next to the session logs",
      )
      val sidecar = json.decodeFromString<SessionResult>(sidecarFile.readText())
      assertEquals(report.results.single(), sidecar)
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `retry sidecars keep each attempt's own outcome and put the roll-up on the winner`() {
    // Two attempts of the same test (same trailFilePath → same stable test key): the
    // report keeps one deduplicated row for the winning attempt, but EVERY attempt's
    // session directory gets a sidecar — the winner's carries the retry roll-up
    // (attempt, total_attempts, replaced_session_ids), the superseded attempt keeps
    // its own raw outcome so its zip still explains what happened in that run.
    val logsDir = Files.createTempDirectory("trailblaze-report-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val failedSessionId = SessionId("2026_07_13_attempt_one")
      val passedSessionId = SessionId("2026_07_13_attempt_two")
      val deviceInfo = webDeviceInfo()

      writeLog(
        logsDir = logsDir,
        sessionId = failedSessionId,
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
          session = failedSessionId,
          timestamp = Instant.parse("2026-07-13T10:00:00Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = failedSessionId,
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Failed(durationMs = 4_000, exceptionMessage = "boom"),
          session = failedSessionId,
          timestamp = Instant.parse("2026-07-13T10:00:04Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = passedSessionId,
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
          session = passedSessionId,
          timestamp = Instant.parse("2026-07-13T10:01:00Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = passedSessionId,
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 5_000),
          session = passedSessionId,
          timestamp = Instant.parse("2026-07-13T10:01:05Z"),
        ),
      )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      val winner = report.results.single()
      assertEquals(passedSessionId, winner.session_id)
      assertEquals(2, winner.total_attempts)
      assertEquals(listOf(failedSessionId), winner.replaced_session_ids)

      val winnerSidecar = json.decodeFromString<SessionResult>(
        File(logsDir, passedSessionId.value)
          .resolve(GenerateTestResultsCliCommand.SESSION_RESULT_FILENAME).readText(),
      )
      assertEquals(winner, winnerSidecar)

      val replacedSidecar = json.decodeFromString<SessionResult>(
        File(logsDir, failedSessionId.value)
          .resolve(GenerateTestResultsCliCommand.SESSION_RESULT_FILENAME).readText(),
      )
      assertEquals(Outcome.FAILED, replacedSidecar.outcome)
      assertEquals("boom", replacedSidecar.failure_reason)
      // The roll-up lives on the winner; the superseded attempt keeps its raw record.
      assertEquals(1, replacedSidecar.total_attempts)
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
