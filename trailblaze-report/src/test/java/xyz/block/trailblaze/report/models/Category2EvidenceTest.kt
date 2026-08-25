package xyz.block.trailblaze.report.models

import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Test
import xyz.block.trailblaze.logs.model.SessionId

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
      assertEquals(1, evidence.schema_version)
      assertEquals(SessionId("session-1"), evidence.session_id)
      assertEquals("12345", evidence.case_id)
      assertEquals("case-123", evidence.test_key)
      assertEquals("android-phone", evidence.device_classifier)
      assertEquals("example/trails", evidence.trail_source_repo)
      assertEquals("main", evidence.trail_source_ref)
      assertEquals("42", evidence.ci_build_number)
      assertTrue(evidence.self_heal_configured)
      assertTrue(evidence.self_heal_ran)
      assertEquals(CATEGORY2_HEAL_DIFF_FILENAME, evidence.heal_diff_artifact)

      val artifactFile = sessionDir.resolve(evidence.heal_diff_artifact)
      assertTrue(artifactFile.isFile)
      val artifact = json.decodeFromString<HealDiffArtifact>(artifactFile.readText())
      assertEquals(SessionId("session-1"), artifact.session_id)
      assertTrue(artifact.before_yaml.contains("old selector"))
      assertTrue(artifact.after_yaml.contains("new selector"))
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

  private fun context() =
    Category2EvidenceContext(
      sessionId = SessionId("session-1"),
      caseId = "12345",
      testKey = "case-123",
      deviceClassifier = "android-phone",
      appVersionName = "6.60",
      appVersionCode = "6600000",
      appBuildNumber = null,
      trailSourceRepo = "example/trails",
      trailSourceRef = "main",
      ciBuildNumber = "42",
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
