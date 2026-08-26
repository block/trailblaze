package xyz.block.trailblaze.report.models

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig

const val CATEGORY2_HEAL_DIFF_FILENAME = "category2_heal_diff.json"

private val category2Json = Json { prettyPrint = true; encodeDefaults = true }

@Serializable
data class Category2EvidenceRecord(
  val schema_version: Int = 1,
  val session_id: SessionId,
  val case_id: String? = null,
  val test_key: String? = null,
  val device_classifier: String? = null,
  val app_version_name: String? = null,
  val app_version_code: String? = null,
  val app_build_number: String? = null,
  val trail_source_repo: String? = null,
  val trail_source_ref: String? = null,
  val ci_build_number: String? = null,
  val baseline_outcome: Outcome,
  val self_heal_configured: Boolean,
  val self_heal_ran: Boolean,
  val heal_diff_artifact: String,
)

@Serializable
data class HealDiffArtifact(
  val schema_version: Int = 1,
  val session_id: SessionId,
  val before_yaml: String,
  val after_yaml: String,
)

internal data class Category2EvidenceContext(
  val sessionId: SessionId,
  val caseId: String?,
  val testKey: String?,
  val deviceClassifier: String?,
  val appVersionName: String?,
  val appVersionCode: String?,
  val appBuildNumber: String?,
  val trailSourceRepo: String?,
  val trailSourceRef: String?,
  val ciBuildNumber: String?,
  val baselineOutcome: Outcome,
  val selfHealRan: Boolean,
)

internal fun selfHealConfigured(environmentValue: String?): Boolean =
  environmentValue?.trim()?.lowercase()?.toBooleanStrictOrNull() ?: false

internal fun writeCategory2Evidence(
  sessionDir: File,
  context: Category2EvidenceContext,
  originalYaml: String?,
  healedYaml: String?,
  healedStepIndexes: Set<Int>,
): Category2EvidenceRecord? {
  if (
    context.baselineOutcome != Outcome.PASSED ||
      !context.selfHealRan
  ) {
    return null
  }
  val before = originalYaml?.takeIf { it.isNotBlank() } ?: return null
  val after = healedYaml?.takeIf { it.isNotBlank() } ?: return null
  if (healedStepIndexes.isEmpty()) return null
  val yaml = createTrailblazeYaml()
  val focusedTrails =
    runCatching {
        val beforeTrail = yaml.decodeUnifiedTrail(before)
        val afterTrail = yaml.decodeUnifiedTrail(after)
        beforeTrail.focusedOn(healedStepIndexes) to afterTrail.focusedOn(healedStepIndexes)
      }
      .getOrElse {
        Console.log(
          "⚠️  Could not compare self-heal recording for ${context.sessionId.value}: ${it.message}"
        )
        return null
      }
  val (focusedBefore, focusedAfter) = focusedTrails
  if (focusedBefore.trailhead == null && focusedBefore.trail.isEmpty()) return null
  if (focusedBefore == focusedAfter) return null

  val artifact =
    HealDiffArtifact(
      session_id = context.sessionId,
      before_yaml = yaml.encodeUnifiedTrailToString(focusedBefore),
      after_yaml = yaml.encodeUnifiedTrailToString(focusedAfter),
    )
  val artifactFile = File(sessionDir, CATEGORY2_HEAL_DIFF_FILENAME)
  try {
    artifactFile.writeText(category2Json.encodeToString(artifact))
  } catch (e: Exception) {
    Console.log(
      "⚠️  Could not write $CATEGORY2_HEAL_DIFF_FILENAME for ${context.sessionId.value}: ${e.message}"
    )
    return null
  }

  return Category2EvidenceRecord(
    session_id = context.sessionId,
    case_id = context.caseId,
    test_key = context.testKey,
    device_classifier = context.deviceClassifier,
    app_version_name = context.appVersionName,
    app_version_code = context.appVersionCode,
    app_build_number = context.appBuildNumber,
    trail_source_repo = context.trailSourceRepo,
    trail_source_ref = context.trailSourceRef,
    ci_build_number = context.ciBuildNumber,
    baseline_outcome = context.baselineOutcome,
    self_heal_configured = true,
    self_heal_ran = true,
    heal_diff_artifact = CATEGORY2_HEAL_DIFF_FILENAME,
  )
}

private fun UnifiedTrail.focusedOn(stepIndexes: Set<Int>): UnifiedTrail {
  return UnifiedTrail(
    config = UnifiedTrailConfig(),
    // Trailheads run in a separate runPromptSuspend call and are never self-healed. The logged
    // index is therefore always relative to the regular trail list.
    trailhead = null,
    trail = trail.filterIndexed { index, _ -> index in stepIndexes },
  )
}
