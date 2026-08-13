package xyz.block.trailblaze.report

import com.github.ajalt.clikt.core.main
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
import xyz.block.trailblaze.report.models.CombinedVerdict
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
  fun `junit identity is carried alongside the trail-derived title`() {
    // A JUnit-harness session whose trail config has a title: the title labels the
    // result, but the JUnit class#method identity must still ride along so consumers
    // speaking the JUnit namespace (expected-tests validation against a farm manifest)
    // can bind the result without token overlap between the two names.
    val logsDir = Files.createTempDirectory("trailblaze-report-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val sessionId = SessionId("2026_07_22_junit_identity_session")
      val deviceInfo = webDeviceInfo()

      writeLog(
        logsDir = logsDir,
        sessionId = sessionId,
        fileName = "001_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Started(
            trailConfig = TrailConfig(title = "Test app launch recording"),
            trailFilePath = null,
            hasRecordedSteps = true,
            testMethodName = "launchNoCrash",
            testClassName = "com.example.smoke.LaunchSmokeTest",
            trailblazeDeviceInfo = deviceInfo,
            trailblazeDeviceId = deviceInfo.trailblazeDeviceId,
            rawYaml = null,
          ),
          session = sessionId,
          timestamp = Instant.parse("2026-07-22T07:53:39Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = sessionId,
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 5_000),
          session = sessionId,
          timestamp = Instant.parse("2026-07-22T07:53:44Z"),
        ),
      )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      val result = report.results.single()
      assertEquals("Test app launch recording", result.title)
      assertEquals("com.example.smoke.LaunchSmokeTest", result.test_class)
      assertEquals("launchNoCrash", result.test_name)
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
      // Every archive predating the agent-name field looks exactly like this one. Absent must
      // mean null, not a decode failure that would drop ci_job_id along with it.
      assertEquals(null, row.ci_agent_name)
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `host_ci_context sidecar ci_agent_name reaches the result row`() {
    // Why this field exists: two attempts of the same test that carry the same agent name ran on
    // the same worker. Without it, a retry that cleared a wedged host and a genuine reproduction
    // are the same row in the report. It has to survive the sidecar → report hop to answer that,
    // since deferred report-gen runs in a different job and cannot read the test job's env.
    val logsDir = Files.createTempDirectory("trailblaze-report-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val sessionId = SessionId("2026_05_26_session_agent_name")
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
      // Dots are the realistic shape — every mac-mini agent name has them. Pinning a dotted name
      // here is what keeps the writer's charset from being narrowed back to the ci_job_id one.
      File(logsDir, sessionId.value).resolve(GenerateTestResultsCliCommand.HOST_CI_CONTEXT_FILENAME)
        .writeText(
          """
          {
            "ci_job_id": "job-uuid-abc",
            "ci_agent_name": "build-agent-07.example.internal",
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
      assertEquals("build-agent-07.example.internal", row.ci_agent_name)
      assertEquals("job-uuid-abc", row.ci_job_id)
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
  fun `a test that passed first and failed on a later attempt is not counted as a rescue`() {
    val logsDir = Files.createTempDirectory("trailblaze-pass-then-fail").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val deviceInfo = webDeviceInfo()

      // Re-running a test that already passed is the only thing that can produce this ordering,
      // so only whole-shard retry reaches it -- which is exactly what step-level retry is. The
      // retry rescued nothing here; it broke something that was already green.
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_destabilized_attempt1"),
        trailFilePath = "trails/sample-app/destabilized.trail.yaml",
        startedAt = "2026-06-15T10:00:00Z",
        ended = SessionStatus.Ended.Succeeded(durationMs = 4_000),
      )
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_destabilized_attempt2"),
        trailFilePath = "trails/sample-app/destabilized.trail.yaml",
        startedAt = "2026-06-15T10:05:00Z",
        ended = SessionStatus.Ended.Failed(durationMs = 2_000, exceptionMessage = "Element not found: More"),
      )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON", "--triage"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      assertEquals(1, report.results.size)

      // Pinned so that narrowing the retry counter can never be mistaken for -- or quietly drift
      // into -- a change to which attempt wins. A pass anywhere is still the verdict.
      assertEquals(Outcome.PASSED, report.results.single().outcome)

      val triage =
        json.decodeFromString<TriageReport>(File(logsDir, "trailblaze_triage_report.json").readText())

      // Nothing was rescued, so nothing may be reported as rescued.
      assertEquals(0, triage.retries.passed_on_retry)
      // Nor is it a persistent failure -- the kept outcome is a pass.
      assertEquals(0, triage.retries.failed_after_retries)

      assertEquals(2, triage.retries.total_attempts)
      assertEquals(1, triage.retries.unique_test_cases)
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `a rescue whose failed attempt recorded no message is still counted as a rescue`() {
    val logsDir = Files.createTempDirectory("trailblaze-reasonless-rescue").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val deviceInfo = webDeviceInfo()

      // A failure with no exception message. Keying "was this rescued?" on the presence of a
      // reason string made this indistinguishable from a shard that a job-level CI retry re-ran
      // for its own reasons -- so a real red that a retry papered over reported the same as a
      // healthy leg. Every attempt has an outcome even when it has nothing to say.
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_quiet_attempt1"),
        trailFilePath = "trails/sample-app/quiet.trail.yaml",
        startedAt = "2026-06-15T10:00:00Z",
        ended = SessionStatus.Ended.Failed(durationMs = 3_000, exceptionMessage = null),
      )
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_quiet_attempt2"),
        trailFilePath = "trails/sample-app/quiet.trail.yaml",
        startedAt = "2026-06-15T10:05:00Z",
        ended = SessionStatus.Ended.Succeeded(durationMs = 4_000),
      )

      // Control: two clean runs of a different trail. The rule must separate these two groups,
      // which is the whole difficulty -- both have a superseded attempt carrying no reason.
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_clean_attempt1"),
        trailFilePath = "trails/sample-app/clean.trail.yaml",
        startedAt = "2026-06-15T10:00:00Z",
        ended = SessionStatus.Ended.Succeeded(durationMs = 5_000),
      )
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_clean_attempt2"),
        trailFilePath = "trails/sample-app/clean.trail.yaml",
        startedAt = "2026-06-15T10:05:00Z",
        ended = SessionStatus.Ended.Succeeded(durationMs = 5_000),
      )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON", "--triage"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      fun resultFor(trail: String) = report.results.single { it.title.contains(trail) }

      val quiet = resultFor("quiet")
      assertEquals(
        listOf(Outcome.FAILED),
        quiet.replaced_outcomes,
        "the superseded attempt's outcome is the only surviving evidence it failed",
      )
      assertTrue(
        quiet.replaced_failure_reasons.isEmpty(),
        "this fixture is only meaningful if the failure genuinely recorded no reason",
      )

      assertEquals(
        listOf(Outcome.PASSED),
        resultFor("clean").replaced_outcomes,
        "an attempt superseded without failing still reports its own outcome",
      )

      // End-to-end: the classification is computed during report generation, so it must reach
      // the JSON. Both of these results report outcome PASSED, which is exactly why the outcome
      // alone cannot tell a rescued test from a clean one.
      assertEquals(CombinedVerdict.RESCUED, quiet.combined_verdict)
      assertEquals(CombinedVerdict.PASSED, resultFor("clean").combined_verdict)

      val triage =
        json.decodeFromString<TriageReport>(File(logsDir, "trailblaze_triage_report.json").readText())
      assertEquals(1, triage.retries.passed_on_retry)
      assertEquals(0, triage.retries.failed_after_retries)
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `dedup keeps the failure classification of the attempts it replaced`() {
    val logsDir = Files.createTempDirectory("trailblaze-replaced-kinds").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val deviceInfo = webDeviceInfo()

      // Rescued: the only attempt that carried a classification is the one dedup drops. Without
      // it being carried forward, "what went wrong the first time" is unanswerable from the report.
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_rescued_attempt1"),
        trailFilePath = "trails/sample-app/rescued.trail.yaml",
        startedAt = "2026-06-15T10:00:00Z",
        ended = SessionStatus.Ended.Failed(
          durationMs = 3_000,
          exceptionMessage = "TRAILHEAD FAILED: could not reach the starting state",
          failureKind = "TRAILHEAD",
        ),
      )
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_rescued_attempt2"),
        trailFilePath = "trails/sample-app/rescued.trail.yaml",
        startedAt = "2026-06-15T10:05:00Z",
        ended = SessionStatus.Ended.Succeeded(durationMs = 4_000),
      )

      // Failed twice for DIFFERENT reasons. The surviving attempt's own kind is null, so reading
      // only the survivor would call this an ordinary failure and lose that the run began by
      // never reaching the trail at all.
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_mixed_attempt1"),
        trailFilePath = "trails/sample-app/mixed.trail.yaml",
        startedAt = "2026-06-15T10:00:00Z",
        ended = SessionStatus.Ended.Failed(
          durationMs = 2_000,
          exceptionMessage = "TRAILHEAD FAILED: could not reach the starting state",
          failureKind = "TRAILHEAD",
        ),
      )
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_mixed_attempt2"),
        trailFilePath = "trails/sample-app/mixed.trail.yaml",
        startedAt = "2026-06-15T10:05:00Z",
        ended = SessionStatus.Ended.Failed(durationMs = 2_000, exceptionMessage = "Delivery not visible"),
      )

      // Neither attempt carried a kind: the list stays empty rather than gaining a placeholder,
      // so "no classification" and "classification lost in dedup" cannot be confused.
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_plain_attempt1"),
        trailFilePath = "trails/sample-app/plain.trail.yaml",
        startedAt = "2026-06-15T10:00:00Z",
        ended = SessionStatus.Ended.Failed(durationMs = 2_000, exceptionMessage = "Delivery not visible"),
      )
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_plain_attempt2"),
        trailFilePath = "trails/sample-app/plain.trail.yaml",
        startedAt = "2026-06-15T10:05:00Z",
        ended = SessionStatus.Ended.Succeeded(durationMs = 4_000),
      )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      assertEquals(3, report.results.size)
      fun resultFor(trail: String) = report.results.single { it.title.contains(trail) }

      val rescued = resultFor("rescued")
      assertEquals(Outcome.PASSED, rescued.outcome)
      assertEquals(listOf("TRAILHEAD"), rescued.replaced_failure_kinds)

      val mixed = resultFor("mixed")
      assertEquals(listOf("TRAILHEAD"), mixed.replaced_failure_kinds)
      // The pair is distinguishable from "failed the same way twice" only because the survivor's
      // own kind and the replaced one differ.
      assertEquals(null, mixed.failure_kind)

      val plain = resultFor("plain")
      assertEquals(emptyList<String>(), plain.replaced_failure_kinds)
      // Sparse by construction: an attempt was replaced, it just had nothing to classify.
      assertEquals(1, plain.replaced_session_ids.size)
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `each failure signature carries the identity of every failure behind it`() {
    val logsDir = Files.createTempDirectory("trailblaze-triage-identity").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val deviceInfo = webDeviceInfo()

      // Two different cases whose reasons normalize to the SAME signature — the shape that
      // used to reach triage as one signature and a count of 2, with nothing saying which
      // cases were behind it.
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_case_a"),
        trailFilePath = "trails/cases/suite_71172/section_838951/case_4837766/trail.yaml",
        startedAt = "2026-06-15T10:00:00Z",
        ended = SessionStatus.Ended.Failed(durationMs = 1_000, exceptionMessage = "Element not found: Save"),
      )
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_06_15_case_b"),
        trailFilePath = "trails/cases/suite_71172/section_838949/case_4866622/trail.yaml",
        startedAt = "2026-06-15T10:05:00Z",
        ended = SessionStatus.Ended.Failed(durationMs = 1_000, exceptionMessage = "Element not found: Save"),
      )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON", "--triage"),
        )
      }

      val triage = json.decodeFromString<TriageReport>(File(logsDir, "trailblaze_triage_report.json").readText())
      val group = triage.failure_signatures.single()
      assertEquals(2, group.count)

      // Every counted failure is enumerated, and each one names its own case.
      assertEquals(group.count, group.affected_failures.size)
      assertEquals(
        listOf("4837766", "4866622"),
        group.affected_failures.mapNotNull { it.case_id }.sorted(),
      )
      val first = group.affected_failures.single { it.case_id == "4837766" }
      assertEquals("cases/suite_71172/section_838951/case_4837766", first.test_key)
      assertEquals("Element not found: Save", first.reason)
      assertEquals("2026_06_15_case_a", first.session_id)
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

  @Test
  fun `a test that passed then failed reports the pass, not the later failure`() {
    // Whole-shard retry re-runs cases that already passed, so a case can pass on attempt 1 and
    // fail on attempt 2. The pass is the verdict: the case ran what it was meant to run and the
    // product answered correctly, which makes the later failure evidence about the rerun rather
    // than about the product. This is the same rule `combinedVerdictOf` states when it calls any
    // sequence containing a pass RESCUED, and the two have to agree — a row reading
    // `outcome = FAILED` beside `combined_verdict = RESCUED` is not a verdict anyone can act on.
    //
    // Asserted in both orders on purpose. The rescue direction is covered by `retry sidecars keep
    // each attempt's own outcome and put the roll-up on the winner` above; this is its mirror, and
    // it is the one that only exists because retry re-runs passing tests.
    val logsDir = Files.createTempDirectory("trailblaze-report-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val passedFirstSessionId = SessionId("2026_07_13_attempt_one_passed")
      val failedLaterSessionId = SessionId("2026_07_13_attempt_two_failed")
      val deviceInfo = webDeviceInfo()

      fun writeAttempt(sessionId: SessionId, startedAt: String, ended: SessionStatus.Ended, endedAt: String) {
        writeLog(
          logsDir = logsDir,
          sessionId = sessionId,
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
            timestamp = Instant.parse(endedAt),
          ),
        )
      }

      writeAttempt(
        sessionId = passedFirstSessionId,
        startedAt = "2026-07-13T10:00:00Z",
        ended = SessionStatus.Ended.Succeeded(durationMs = 5_000),
        endedAt = "2026-07-13T10:00:05Z",
      )
      writeAttempt(
        sessionId = failedLaterSessionId,
        startedAt = "2026-07-13T10:01:00Z",
        ended = SessionStatus.Ended.Failed(durationMs = 4_000, exceptionMessage = "regressed on the rerun"),
        endedAt = "2026-07-13T10:01:04Z",
      )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())
      // `single()` is itself the control that the two attempts really did group as one test —
      // if they hadn't, this would be two rows and the outcome assertion below would be vacuous.
      val kept = report.results.single()
      // The case passed once, so the row is a pass — and carries no failure reason, since the
      // reason belongs to the superseded attempt, not to the verdict.
      assertEquals(Outcome.PASSED, kept.outcome)
      assertEquals(passedFirstSessionId, kept.session_id)
      assertNull(kept.failure_reason)
      assertEquals(2, kept.total_attempts)
      // The rerun's failure is not discarded — it is the only record that this case is unstable,
      // which is what makes the row distinguishable from a clean first-try pass.
      assertEquals(listOf(failedLaterSessionId), kept.replaced_session_ids)
      assertEquals(listOf("regressed on the rerun"), kept.replaced_failure_reasons)
      // And the classifier reaches the same conclusion from the same attempts.
      assertEquals(CombinedVerdict.RESCUED, kept.combined_verdict)
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `the winner carries the superseded attempt's own reason and attempt number`() {
    // A step-level retry runs attempt 2 in a different job, so the two attempts arrive as two
    // independent session directories. The merged row is then the ONLY place attempt 1's failure
    // survives: if `replaced_failure_reasons` came back empty — or held some other run's text — a
    // rescued flake would render as a clean first-try pass, which is the exact misreport the
    // retry design must not produce.
    //
    // Deliberately NOT re-asserted here, because sibling tests already pin it: the merge itself
    // (one row, winning session id, total_attempts) is covered by `retry sidecars keep each
    // attempt's own outcome…`, and "distinct trails stay separate" by `triage report counts
    // genuine retries…`. What neither pins is the CONTENT of the roll-up — `attempt` is asserted
    // nowhere in the repo, and `replaced_failure_reasons` only ever reaches an assertion through a
    // downstream count, which still passes when the list holds the wrong reason.
    val logsDir = Files.createTempDirectory("trailblaze-report-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val deviceInfo = webDeviceInfo()

      // The retried test: attempt 1 fails, attempt 2 passes.
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_07_13_retried_attempt1"),
        trailFilePath = "trails/sample-app/checkout.trail.yaml",
        startedAt = "2026-07-13T10:00:00Z",
        ended = SessionStatus.Ended.Failed(durationMs = 4_000, exceptionMessage = "attempt one reason"),
      )
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_07_13_retried_attempt2"),
        trailFilePath = "trails/sample-app/checkout.trail.yaml",
        startedAt = "2026-07-13T10:01:00Z",
        ended = SessionStatus.Ended.Succeeded(durationMs = 5_000),
      )

      // An unrelated test that also failed, same device, same run, interleaved in time. Its reason
      // must not be attributed to the retried test — a grouping bug that widened the key would
      // otherwise show up as someone else's failure on the winner's roll-up.
      writeTrailRun(
        logsDir, deviceInfo, SessionId("2026_07_13_unrelated"),
        trailFilePath = "trails/sample-app/search.trail.yaml",
        startedAt = "2026-07-13T10:00:30Z",
        ended = SessionStatus.Ended.Failed(durationMs = 3_000, exceptionMessage = "unrelated reason"),
      )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      val report = json.decodeFromString<CiSummaryReport>(outputFile.readText())

      val winner = report.results.single { it.test_key == "sample-app/checkout" }
      assertEquals(Outcome.PASSED, winner.outcome)
      assertEquals(2, winner.attempt)
      assertEquals(2, winner.total_attempts)
      assertEquals(listOf("attempt one reason"), winner.replaced_failure_reasons)

      val unrelated = report.results.single { it.test_key == "sample-app/search" }
      assertEquals(Outcome.FAILED, unrelated.outcome)
      assertEquals(1, unrelated.attempt)
      assertEquals(1, unrelated.total_attempts)
      assertEquals(emptyList<String>(), unrelated.replaced_failure_reasons)
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `the retry roll-up carries the superseded attempt's agent, not just the winner's`() {
    // Deduplication publishes one row per test, so without this the report shows only the host
    // the retry landed on — the question the field exists to answer ("did the retry clear a
    // wedged host, or re-run on it?") needs BOTH hosts on the surviving row.
    val logsDir = Files.createTempDirectory("trailblaze-report-test").toFile()
    val outputFile = File(logsDir, "results.json")
    try {
      val failedSessionId = SessionId("2026_08_11_attempt_one")
      val passedSessionId = SessionId("2026_08_11_attempt_two")
      val deviceInfo = webDeviceInfo()

      listOf(
        Triple(failedSessionId, "10:00", "agent-alpha.example.internal"),
        Triple(passedSessionId, "10:01", "agent-beta.example.internal"),
      ).forEach { (sessionId, minute, agent) ->
        writeLog(
          logsDir = logsDir,
          sessionId = sessionId,
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
            session = sessionId,
            timestamp = Instant.parse("2026-08-11T$minute:00Z"),
          ),
        )
        File(logsDir, sessionId.value).resolve(GenerateTestResultsCliCommand.HOST_CI_CONTEXT_FILENAME)
          .writeText("""{ "ci_agent_name": "$agent" }""")
      }
      writeLog(
        logsDir = logsDir,
        sessionId = failedSessionId,
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Failed(durationMs = 4_000, exceptionMessage = "boom"),
          session = failedSessionId,
          timestamp = Instant.parse("2026-08-11T10:00:04Z"),
        ),
      )
      writeLog(
        logsDir = logsDir,
        sessionId = passedSessionId,
        fileName = "002_TrailblazeSessionStatusChangeLog.json",
        log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 5_000),
          session = passedSessionId,
          timestamp = Instant.parse("2026-08-11T10:01:05Z"),
        ),
      )

      captureStdout {
        GenerateTestResultsCliCommand().main(
          arrayOf(logsDir.absolutePath, outputFile.absolutePath, "--output-format", "JSON"),
        )
      }

      val winner = json.decodeFromString<CiSummaryReport>(outputFile.readText()).results.single()
      assertEquals(passedSessionId, winner.session_id)
      assertEquals("agent-beta.example.internal", winner.ci_agent_name)
      assertEquals(listOf("agent-alpha.example.internal"), winner.replaced_agent_names)
      // The whole point: the two differ, so this row proves the retry moved hosts.
      assertEquals(false, winner.replaced_agent_names.contains(winner.ci_agent_name))
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
