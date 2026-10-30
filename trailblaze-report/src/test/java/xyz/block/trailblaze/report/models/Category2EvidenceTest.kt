package xyz.block.trailblaze.report.models

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.junit.Test
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper

class Category2EvidenceTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `missing self-heal configuration is false`() {
    assertFalse(selfHealConfigured(null))
    assertFalse(selfHealConfigured(""))
    assertFalse(selfHealConfigured("false"))
    assertTrue(selfHealConfigured("true"))
  }

  @Test
  fun `qualified baseline writes an identity-linked diff artifact`() {
    val sessionDir = Files.createTempDirectory("category2-evidence").toFile()
    try {
      val evidence =
        writeCategory2Evidence(
          sessionDir = sessionDir,
          context = context(),
          originalYaml = yamlWithText("old selector"),
          healedYaml = yamlWithText("new selector"),
          healedStepIndexes = setOf(0),
        )

      assertNotNull(evidence)
      assertEquals(2, evidence.schema_version)
      assertEquals(SessionId("session-1"), evidence.session_id)
      assertEquals("12345", evidence.case_id)
      assertEquals("case-123", evidence.test_key)
      assertEquals("android-phone", evidence.device_classifier)
      assertEquals("counter-left", evidence.selected_device_configuration)
      assertEquals(
        listOf("counter-left", "android-phone", "android", "all"),
        evidence.recording_resolution_chain,
      )
      assertEquals("example/trails", evidence.trail_source_repo)
      assertEquals("main", evidence.trail_source_ref)
      assertEquals("42", evidence.ci_build_number)
      assertTrue(evidence.self_heal_configured)
      assertTrue(evidence.self_heal_ran)
      assertEquals(CATEGORY2_HEAL_DIFF_FILENAME, evidence.heal_diff_artifact)
      val locator = assertNotNull(evidence.heal_diff_locator)
      assertEquals("job-uuid-1", locator.source_job_id)
      assertEquals(
        "category2-heal-diffs/v1/job-uuid-1/84097828fc31a8c8d29210df48901a85de7fd013f686b17be77d1be29cb7a98b.json",
        locator.path,
      )
      assertTrue(locator.sha256.matches(Regex("[0-9a-f]{64}")))
      assertTrue(locator.size_bytes > 0)

      val artifactFile = sessionDir.resolve(evidence.heal_diff_artifact)
      assertTrue(artifactFile.isFile)
      val artifact = json.decodeFromString<HealDiffArtifact>(artifactFile.readText())
      assertEquals(2, artifact.schema_version)
      assertEquals(SessionId("session-1"), artifact.session_id)
      assertEquals("job-uuid-1", artifact.source_job_id)
      assertEquals("12345", artifact.case_id)
      assertEquals("case-123", artifact.test_key)
      assertEquals("android-phone", artifact.device_classifier)
      assertEquals("42", artifact.ci_build_number)
      assertTrue(artifact.before_yaml.contains("old selector"))
      assertTrue(artifact.after_yaml.contains("new selector"))
      assertEquals(yamlWithText("old selector"), artifact.source_yaml)
      assertEquals(locator.size_bytes, artifactFile.length())
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `matching upload confirmation survives report regeneration`() {
    val sessionDir = Files.createTempDirectory("category2-evidence").toFile()
    try {
      val initial =
        assertNotNull(
          writeCategory2Evidence(
            sessionDir = sessionDir,
            context = context(),
            originalYaml = yamlWithText("old selector"),
            healedYaml = yamlWithText("new selector"),
            healedStepIndexes = setOf(0),
          )
        )
      val locator = assertNotNull(initial.heal_diff_locator)
      sessionDir.resolve(CATEGORY2_HEAL_DIFF_UPLOAD_FILENAME).writeText(
        """{"schema_version":1,"session_id":"session-1","path":"${locator.path}","source_job_id":"${locator.source_job_id}","size_bytes":${locator.size_bytes},"sha256":"${locator.sha256}","uploaded":true}"""
      )

      val regenerated =
        assertNotNull(
          writeCategory2Evidence(
            sessionDir = sessionDir,
            context = context(),
            originalYaml = yamlWithText("old selector"),
            healedYaml = yamlWithText("new selector"),
            healedStepIndexes = setOf(0),
          )
        )

      assertTrue(assertNotNull(regenerated.heal_diff_locator).uploaded)
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `self-heal run success and artifact are all required`() {
    val sessionDir = Files.createTempDirectory("category2-evidence").toFile()
    try {
      val cases =
        listOf(
          context().copy(selfHealRan = false),
          context().copy(baselineOutcome = Outcome.FAILED),
        )
      cases.forEach { context ->
        assertNull(
          writeCategory2Evidence(
            sessionDir = sessionDir,
            context = context,
            originalYaml = yamlWithText("old selector"),
            healedYaml = yamlWithText("new selector"),
            healedStepIndexes = setOf(0),
          )
        )
      }
      assertNull(
        writeCategory2Evidence(
          sessionDir = sessionDir,
          context = context(),
          originalYaml = yamlWithText("same selector"),
          healedYaml = yamlWithText("same selector"),
          healedStepIndexes = setOf(0),
        )
      )
      assertNull(
        writeCategory2Evidence(
          sessionDir = sessionDir,
          context = context(),
          originalYaml = null,
          healedYaml = yamlWithText("new selector"),
          healedStepIndexes = setOf(0),
        )
      )
      assertFalse(sessionDir.resolve(CATEGORY2_HEAL_DIFF_FILENAME).exists())
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `changes outside the healed step do not produce category 2 evidence`() {
    val sessionDir = Files.createTempDirectory("category2-evidence").toFile()
    try {
      assertNull(
        writeCategory2Evidence(
          sessionDir = sessionDir,
          context = context(),
          originalYaml = yamlWithTwoSteps(healedText = "same selector", unrelatedText = "old"),
          healedYaml = yamlWithTwoSteps(healedText = "same selector", unrelatedText = "new"),
          healedStepIndexes = setOf(0),
        )
      )
      assertFalse(sessionDir.resolve(CATEGORY2_HEAL_DIFF_FILENAME).exists())
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `duplicate prompt text does not include an unhealed step`() {
    val sessionDir = Files.createTempDirectory("category2-evidence").toFile()
    try {
      assertNull(
        writeCategory2Evidence(
          sessionDir = sessionDir,
          context = context(),
          originalYaml = yamlWithDuplicateSteps(firstText = "same", secondText = "old"),
          healedYaml = yamlWithDuplicateSteps(firstText = "same", secondText = "new"),
          healedStepIndexes = setOf(0),
        )
      )
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `first regular step remains index zero when a trailhead exists`() {
    val sessionDir = Files.createTempDirectory("category2-evidence").toFile()
    try {
      val evidence =
        writeCategory2Evidence(
          sessionDir = sessionDir,
          context = context(),
          originalYaml = yamlWithTrailhead("old selector"),
          healedYaml = yamlWithTrailhead("new selector"),
          healedStepIndexes = setOf(0),
        )

      assertNotNull(evidence)
      val artifact =
        json.decodeFromString<HealDiffArtifact>(
          sessionDir.resolve(evidence.heal_diff_artifact).readText()
        )
      assertFalse(artifact.before_yaml.contains("Launch app"))
      assertTrue(artifact.before_yaml.contains("old selector"))
      assertTrue(artifact.after_yaml.contains("new selector"))
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `each healed step carries the screens before and after its heal`() {
    val sessionDir = Files.createTempDirectory("category2-evidence").toFile()
    try {
      val shot = webp(64)
      sessionDir.resolve("failed.webp").writeBytes(shot)
      writeCategory2Evidence(
        sessionDir = sessionDir,
        context = context(),
        originalYaml = yamlWithText("old selector"),
        healedYaml = yamlWithText("new selector"),
        healedStepIndexes = setOf(0),
        logs = healLogs(stepIndex = 0, beforeShot = "failed.webp", afterShot = "never-written.png", at = 0),
        platform = TrailblazeDevicePlatform.ANDROID,
      )

      val screens = artifact(sessionDir).screens
      assertEquals(listOf("before_heal", "after_heal"), screens.map { it.moment })
      assertTrue(screens.all { it.step_index == 0 })
      val before = screens[0]
      assertEquals("image/webp", before.screenshot_media_type)
      assertTrue(shot.contentEquals(Base64.getDecoder().decode(before.screenshot_base64)))
      assertTrue(assertNotNull(before.hierarchy).contains("Old screen"))
      assertNull(before.screenshot_missing)
      val after = screens[1]
      assertNull(after.screenshot_base64)
      assertEquals("the screenshot could not be read", after.screenshot_missing)
      assertTrue(assertNotNull(after.hierarchy).contains("New screen"))
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `screens that would overflow the artifact cap keep their hierarchy and drop screenshots`() {
    val sessionDir = Files.createTempDirectory("category2-evidence").toFile()
    try {
      sessionDir.resolve("large.webp").writeBytes(webp(500 * 1024))
      val logs = (0 until 3).flatMap { healLogs(stepIndex = 0, beforeShot = "large.webp", afterShot = "large.webp", at = it * 10) }
      val evidence =
        assertNotNull(
          writeCategory2Evidence(
            sessionDir = sessionDir,
            context = context(),
            originalYaml = yamlWithText("old selector"),
            healedYaml = yamlWithText("new selector"),
            healedStepIndexes = setOf(0),
            logs = logs,
            platform = TrailblazeDevicePlatform.ANDROID,
          )
        )

      assertTrue(assertNotNull(evidence.heal_diff_locator).size_bytes <= 2 * 1024 * 1024)
      val screens = artifact(sessionDir).screens
      assertEquals(6, screens.size)
      assertTrue(screens.all { it.screenshot_base64 == null && it.hierarchy != null })
      assertTrue(screens.all { it.screenshot_missing == "screenshots exceeded the heal evidence size cap" })
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `screen content is dropped when hierarchies alone overflow the artifact cap`() {
    val sessionDir = Files.createTempDirectory("category2-evidence").toFile()
    try {
      val logs =
        (0 until 3).flatMap {
          healLogs(stepIndex = 0, beforeShot = "none.webp", afterShot = "none.webp", at = it * 10, padding = "x".repeat(400 * 1024))
        }
      val evidence =
        assertNotNull(
          writeCategory2Evidence(
            sessionDir = sessionDir,
            context = context(),
            originalYaml = yamlWithText("old selector"),
            healedYaml = yamlWithText("new selector"),
            healedStepIndexes = setOf(0),
            logs = logs,
            platform = TrailblazeDevicePlatform.ANDROID,
          )
        )

      assertTrue(assertNotNull(evidence.heal_diff_locator).size_bytes <= 2 * 1024 * 1024)
      val screens = artifact(sessionDir).screens
      assertEquals(6, screens.size)
      assertTrue(screens.all { it.hierarchy == null && it.screenshot_base64 == null })
      assertTrue(screens.all { it.screenshot_missing == "screens exceeded the heal evidence size cap" })
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  @Test
  fun `a confirmed upload is kept when a screenshot can no longer be read`() {
    val sessionDir = Files.createTempDirectory("category2-evidence").toFile()
    try {
      sessionDir.resolve("failed.webp").writeBytes(webp(64))
      fun write() =
        assertNotNull(
          writeCategory2Evidence(
            sessionDir = sessionDir,
            context = context(),
            originalYaml = yamlWithText("old selector"),
            healedYaml = yamlWithText("new selector"),
            healedStepIndexes = setOf(0),
            logs = healLogs(stepIndex = 0, beforeShot = "failed.webp", afterShot = "failed.webp", at = 0),
            platform = TrailblazeDevicePlatform.ANDROID,
          )
        )
      val locator = assertNotNull(write().heal_diff_locator)
      sessionDir.resolve(CATEGORY2_HEAL_DIFF_UPLOAD_FILENAME).writeText(
        """{"schema_version":1,"session_id":"session-1","path":"${locator.path}","source_job_id":"${locator.source_job_id}","size_bytes":${locator.size_bytes},"sha256":"${locator.sha256}","uploaded":true}"""
      )
      sessionDir.resolve("failed.webp").delete()

      val regenerated = assertNotNull(write().heal_diff_locator)

      assertEquals(locator.copy(uploaded = true), regenerated)
      assertTrue(artifact(sessionDir).screens.all { it.screenshot_base64 != null })
    } finally {
      sessionDir.deleteRecursively()
    }
  }

  private fun artifact(sessionDir: File) =
    json.decodeFromString<HealDiffArtifact>(sessionDir.resolve(CATEGORY2_HEAL_DIFF_FILENAME).readText())

  private fun webp(size: Int) =
    "RIFF".encodeToByteArray() + ByteArray(4) + "WEBP".encodeToByteArray() + ByteArray(size)

  private fun healLogs(
    stepIndex: Int,
    beforeShot: String,
    afterShot: String,
    at: Int,
    padding: String = "",
  ): List<TrailblazeLog> {
    val session = SessionId("session-1")
    val prompt = DirectionStep(step = "Enter text")
    val tool = TrailblazeToolYamlWrapper(name = "inputText", trailblazeTool = InputTextTrailblazeTool(text = "old"))
    fun time(second: Int) = Instant.fromEpochSeconds(1_800_000_000L + at + second)
    fun screen(text: String, shot: String, second: Int) =
      TrailblazeLog.AgentDriverLog(
        viewHierarchy = ViewHierarchyTreeNode(text = text + padding, x2 = 100, y2 = 100),
        screenshotFile = shot,
        action = AgentDriverAction.TapPoint(x = 10, y = 10),
        durationMs = 1,
        session = session,
        timestamp = time(second),
        deviceHeight = 200,
        deviceWidth = 100,
      )
    return listOf(
      screen("Old screen", beforeShot, 0),
      TrailblazeLog.SelfHealInvokedLog(
        promptStep = prompt,
        session = session,
        timestamp = time(1),
        recordingResult =
          PromptRecordingResult.Failure(
            successfulTools = emptyList(),
            failedTool = tool,
            failureResult = TrailblazeToolResult.Error.ExceptionThrown("recording missed"),
          ),
        stepIndex = stepIndex,
      ),
      screen("New screen", afterShot, 2),
      TrailblazeLog.ObjectiveCompleteLog(
        promptStep = prompt,
        objectiveResult =
          AgentTaskStatus.Success.ObjectiveComplete(
            statusData =
              AgentTaskStatusData(
                taskId = TaskId.generate(),
                prompt = prompt.step,
                callCount = 1,
                taskStartTime = time(1),
                totalDurationMs = 1_000,
              ),
            llmExplanation = "healed",
          ),
        session = session,
        timestamp = time(3),
      ),
      // A later step's screen must not be read as this heal's result.
      screen("Next step", afterShot, 4),
    )
  }

  private fun context() =
    Category2EvidenceContext(
      sessionId = SessionId("session-1"),
      caseId = "12345",
      testKey = "case-123",
      deviceClassifier = "android-phone",
      selectedDeviceConfiguration = "counter-left",
      recordingResolutionChain = listOf("counter-left", "android-phone", "android", "all"),
      appVersionName = "6.60",
      appVersionCode = "6600000",
      appBuildNumber = null,
      trailSourceRepo = "example/trails",
      trailSourceRef = "main",
      ciBuildNumber = "42",
      sourceJobId = "job-uuid-1",
      baselineOutcome = Outcome.PASSED,
      selfHealRan = true,
    )

  private fun yamlWithText(text: String) =
    """
      config: {}
      trail:
        - step: Enter text
          recording:
            android-phone:
              - inputText:
                  text: $text
    """.trimIndent()

  private fun yamlWithTwoSteps(healedText: String, unrelatedText: String) =
    """
      config: {}
      trail:
        - step: Enter text
          recording:
            android-phone:
              - inputText:
                  text: $healedText
        - step: Unrelated step
          recording:
            android-phone:
              - inputText:
                  text: $unrelatedText
    """.trimIndent()

  private fun yamlWithDuplicateSteps(firstText: String, secondText: String) =
    """
      config: {}
      trail:
        - step: Tap Continue
          recording:
            android-phone:
              - inputText:
                  text: $firstText
        - step: Tap Continue
          recording:
            android-phone:
              - inputText:
                  text: $secondText
    """.trimIndent()

  private fun yamlWithTrailhead(text: String) =
    """
      config: {}
      trailhead:
        step: Launch app
        recording:
          android-phone: {}
      trail:
        - step: Enter text
          recording:
            android-phone:
              - inputText:
                  text: $text
    """.trimIndent()
}
