package xyz.block.trailblaze.report.models

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig

const val CATEGORY2_HEAL_DIFF_FILENAME = "category2_heal_diff.json"
const val CATEGORY2_HEAL_DIFF_UPLOAD_FILENAME = "category2_heal_diff_upload.json"

private val category2Json = Json { prettyPrint = true; encodeDefaults = true }

@Serializable
data class Category2EvidenceRecord(
  val schema_version: Int = 2,
  val session_id: SessionId,
  val case_id: String? = null,
  val test_key: String? = null,
  val device_classifier: String? = null,
  val selected_device_configuration: String? = null,
  val recording_resolution_chain: List<String> = emptyList(),
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
  val heal_diff_locator: HealDiffArtifactLocator? = null,
)

@Serializable
data class HealDiffArtifact(
  val schema_version: Int = 2,
  val session_id: SessionId,
  val source_job_id: String? = null,
  val case_id: String? = null,
  val test_key: String? = null,
  val device_classifier: String? = null,
  val ci_build_number: String? = null,
  val before_yaml: String,
  val after_yaml: String,
)

@Serializable
data class HealDiffArtifactLocator(
  val path: String,
  val source_job_id: String,
  val size_bytes: Long,
  val sha256: String,
  val uploaded: Boolean = false,
)

@Serializable
private data class HealDiffUploadConfirmation(
  val schema_version: Int,
  val session_id: SessionId,
  val path: String,
  val source_job_id: String,
  val size_bytes: Long,
  val sha256: String,
  val uploaded: Boolean,
)

internal data class Category2EvidenceContext(
  val sessionId: SessionId,
  val caseId: String?,
  val testKey: String?,
  val deviceClassifier: String?,
  val selectedDeviceConfiguration: String?,
  val recordingResolutionChain: List<String>,
  val appVersionName: String?,
  val appVersionCode: String?,
  val appBuildNumber: String?,
  val trailSourceRepo: String?,
  val trailSourceRef: String?,
  val ciBuildNumber: String?,
  val sourceJobId: String?,
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
      source_job_id = context.sourceJobId,
      case_id = context.caseId,
      test_key = context.testKey,
      device_classifier = context.deviceClassifier,
      ci_build_number = context.ciBuildNumber,
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

  val locator =
    context.sourceJobId
      ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
      ?.let { sourceJobId ->
        val sessionHash = context.sessionId.value.sha256()
        val expected = HealDiffArtifactLocator(
          path = "category2-heal-diffs/v1/$sourceJobId/$sessionHash.json",
          source_job_id = sourceJobId,
          size_bytes = artifactFile.length(),
          sha256 = artifactFile.readBytes().sha256(),
        )
        expected.copy(uploaded = sessionDir.confirmedUpload(context.sessionId, expected))
      }

  return Category2EvidenceRecord(
    session_id = context.sessionId,
    case_id = context.caseId,
    test_key = context.testKey,
    device_classifier = context.deviceClassifier,
    selected_device_configuration = context.selectedDeviceConfiguration,
    recording_resolution_chain = context.recordingResolutionChain,
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
    heal_diff_locator = locator,
  )
}

private fun ByteArray.sha256(): String =
  MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

private fun String.sha256(): String = encodeToByteArray().sha256()

private fun File.confirmedUpload(
  sessionId: SessionId,
  expected: HealDiffArtifactLocator,
): Boolean {
  val confirmationFile = resolve(CATEGORY2_HEAL_DIFF_UPLOAD_FILENAME)
  val confirmation =
    runCatching {
        category2Json.decodeFromString<HealDiffUploadConfirmation>(confirmationFile.readText())
      }
      .getOrNull() ?: return false
  return confirmation.schema_version == 1 &&
    confirmation.session_id == sessionId &&
    confirmation.path == expected.path &&
    confirmation.source_job_id == expected.source_job_id &&
    confirmation.size_bytes == expected.size_bytes &&
    confirmation.sha256 == expected.sha256 &&
    confirmation.uploaded
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
