package xyz.block.trailblaze.report.models

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.HasScreenshot
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyCompactFormatter
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig

const val CATEGORY2_HEAL_DIFF_FILENAME = "category2_heal_diff.json"
const val CATEGORY2_HEAL_DIFF_UPLOAD_FILENAME = "category2_heal_diff_upload.json"
// The upload step and the reviewers that read it both refuse a larger artifact.
private const val MAX_HEAL_DIFF_BYTES = 2 * 1024 * 1024
private const val MAX_SCREENSHOT_BYTES = 512 * 1024

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
  // The exact trail text the run started from; the focused YAML above is re-encoded and drops comments.
  val source_yaml: String? = null,
  val screens: List<HealScreen> = emptyList(),
)

/** What the device showed around one healed step, so whoever reviews the heal can see the run it rules on. */
@Serializable
data class HealScreen(
  val step_index: Int,
  /** `before_heal`: the last screen captured before the heal. `after_heal`: the last screen the heal saw. */
  val moment: String,
  val hierarchy: String? = null,
  val screenshot_media_type: String? = null,
  val screenshot_base64: String? = null,
  val screenshot_missing: String? = null,
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
  logs: List<TrailblazeLog> = emptyList(),
  platform: TrailblazeDevicePlatform? = null,
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

  val artifactFile = File(sessionDir, CATEGORY2_HEAL_DIFF_FILENAME)
  fun locator(): HealDiffArtifactLocator? =
    context.sourceJobId
      ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) && artifactFile.isFile }
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
  // Screenshot fetches can fail on a later report run; rewriting would orphan the confirmed upload.
  if (locator()?.uploaded != true) {
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
        source_yaml = before,
        screens = healScreens(logs, healedStepIndexes, sessionDir, platform),
      )
    fun withoutScreenContent(hierarchy: Boolean, reason: String) =
      artifact.copy(
        screens =
          artifact.screens.map {
            it.copy(
              hierarchy = it.hierarchy?.takeIf { hierarchy },
              screenshot_media_type = null,
              screenshot_base64 = null,
              screenshot_missing = if (hierarchy) it.screenshot_missing ?: reason else reason,
            )
          }
      )
    val emptied = withoutScreenContent(hierarchy = false, reason = "screens exceeded the heal evidence size cap")
    val encoded =
      sequenceOf(
          artifact,
          withoutScreenContent(hierarchy = true, reason = "screenshots exceeded the heal evidence size cap"),
          emptied,
        )
        .map { category2Json.encodeToString(it) }
        .firstOrNull { it.encodeToByteArray().size <= MAX_HEAL_DIFF_BYTES }
        ?: category2Json.encodeToString(emptied)
    try {
      artifactFile.writeText(encoded)
    } catch (e: Exception) {
      Console.log(
        "⚠️  Could not write $CATEGORY2_HEAL_DIFF_FILENAME for ${context.sessionId.value}: ${e.message}"
      )
      return null
    }
  }
  val locator = locator()

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

private fun healScreens(
  logs: List<TrailblazeLog>,
  healedStepIndexes: Set<Int>,
  sessionDir: File,
  platform: TrailblazeDevicePlatform?,
): List<HealScreen> {
  val ordered = logs.sortedBy { it.timestamp }
  return ordered.withIndex().flatMap { (position, log) ->
    val stepIndex = (log as? TrailblazeLog.SelfHealInvokedLog)?.stepIndex
    if (stepIndex == null || stepIndex !in healedStepIndexes) return@flatMap emptyList()
    val heal = ordered.drop(position + 1).takeWhile { it !is TrailblazeLog.ObjectiveCompleteLog }
    val before = ordered.take(position).lastOrNull { it.screenHierarchy() != null }
    val after = heal.lastOrNull { it.screenHierarchy() != null }
    listOfNotNull(
      before?.let { it.toHealScreen(stepIndex, "before_heal", sessionDir, platform) },
      after?.let { it.toHealScreen(stepIndex, "after_heal", sessionDir, platform) },
    )
  }
}

private fun TrailblazeLog.screenHierarchy(): ViewHierarchyTreeNode? = when (this) {
  is TrailblazeLog.AgentDriverLog -> viewHierarchy
  is TrailblazeLog.TrailblazeLlmRequestLog -> viewHierarchy
  is TrailblazeLog.TrailblazeSnapshotLog -> viewHierarchy
  else -> null
}

private fun TrailblazeLog.toHealScreen(
  stepIndex: Int,
  moment: String,
  sessionDir: File,
  platform: TrailblazeDevicePlatform?,
): HealScreen {
  val screen = this as HasScreenshot
  val hierarchy =
    platform?.let {
      runCatching {
          ViewHierarchyCompactFormatter.format(screenHierarchy()!!, it, screen.deviceWidth, screen.deviceHeight)
        }
        .getOrNull()
    }
  val reference = screen.screenshotFile
  val bytes = reference?.let { runCatching { readScreenshot(it, sessionDir) }.getOrNull() }
  return HealScreen(
    step_index = stepIndex,
    moment = moment,
    hierarchy = hierarchy,
    screenshot_media_type = bytes?.imageMediaType(),
    screenshot_base64 = bytes?.let { Base64.getEncoder().encodeToString(it) },
    screenshot_missing =
      when {
        bytes != null -> null
        reference == null -> "the run logged no screenshot for this screen"
        else -> "the screenshot could not be read"
      },
  )
}

private fun readScreenshot(reference: String, sessionDir: File): ByteArray? {
  val bytes =
    if (reference.startsWith("https://")) {
      // Farm runs log screenshots as artifact URLs rather than files beside the logs.
      HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()
        .send(
          HttpRequest.newBuilder(URI(reference)).timeout(Duration.ofSeconds(20)).build(),
          HttpResponse.BodyHandlers.ofByteArray(),
        )
        .takeIf { it.statusCode() == 200 }
        ?.body()
    } else {
      File(sessionDir, reference).takeIf { it.isFile }?.readBytes()
    }
  return bytes?.takeIf { it.size <= MAX_SCREENSHOT_BYTES && it.imageMediaType() != null }
}

private fun ByteArray.imageMediaType(): String? =
  when {
    size > 12 && String(copyOfRange(8, 12), Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
    size > 4 && this[0] == 0x89.toByte() && String(copyOfRange(1, 4), Charsets.ISO_8859_1) == "PNG" -> "image/png"
    size > 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() -> "image/jpeg"
    else -> null
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
