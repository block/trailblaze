package xyz.block.trailblaze.report.strings

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.api.ExtractedString
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.VisibleStringExtractor
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest

/** First line of the file: what this run was, so a diff of two files is self-describing. */
@Serializable
data class VisibleStringsRunLine(
  val v: Int = VisibleStringsLog.FORMAT_VERSION,
  val kind: String = "run",
  val session: String,
  val platform: String? = null,
  val locale: String? = null,
  val device: String? = null,
  val appId: String? = null,
  val appVersion: String? = null,
  val trail: String? = null,
  val startedAt: String? = null,
)

/** One screen capture and everything a person could read on it. */
@Serializable
data class VisibleStringsScreenLine(
  val v: Int = VisibleStringsLog.FORMAT_VERSION,
  val kind: String = "screen",
  /** Position in the trail. The only key that survives a locale change, so the only join key. */
  val stepIndex: Int,
  /**
   * The screenshot's filename, which is the durable link back to the image. A farm run records a
   * signed URL built as `<baseArtifactUrl><filename>`, and [VisibleStringsLog.captureIdFrom] pulls
   * the same name back out of either form — so a local run and a farm run of one trail agree,
   * which is what a cross-run diff prints.
   */
  val captureId: String,
  /** The full reference the log recorded, when that was more than a bare filename: a signed farm
   *  URL. Absent on a local run. It expires, which is why it is not [captureId]. */
  val captureUrl: String? = null,
  val logType: String,
  /** True when [captureId] is the set-of-mark overlay rather than the clean screenshot. */
  val screenshotIsAnnotated: Boolean = false,
  val timestamp: String,
  val traceId: String? = null,
  /**
   * A label for the reader, never part of the join key: the three capture sources do not name an
   * action the same way. A driver log reports its `AgentDriverAction` type, a snapshot log its
   * `displayName` or the literal `snapshot`, an LLM log its `llmRequestLabel` — which is often
   * absent, because that log carries a *list* of actions and no single name for them.
   */
  val action: String? = null,
  /**
   * The device's own dimensions, which are what make [ExtractedString.bounds] usable. Host runs
   * scale the screenshot bytes down while the tree keeps native coordinates, so the image on disk
   * is routinely smaller than the space the bounds are in; a reader scales by the image's own
   * dimensions over these.
   */
  val deviceWidth: Int,
  val deviceHeight: Int,
  /** Content hash of [strings]. Collapses repeats within a run; useless across locales. */
  val screenId: String,
  /**
   * True when the capture is known to have lost part of the tree, so absent strings prove
   * nothing. Absent means unknown: only Android computes coverage, so no other driver can say
   * either way. A diff must treat unknown as inconclusive, not as complete.
   *
   * Two situations set it. Android's coverage assessment says the tree looks truncated, or the
   * capture carried no tree at all — a screenshot whose hierarchy failed, which is every string
   * lost rather than some.
   */
  val partialCapture: Boolean? = null,
  /** Set when this capture said exactly what an earlier step already said. */
  val repeatOfStepIndex: Int? = null,
  val strings: List<ExtractedString> = emptyList(),
)

/**
 * Builds `visible-strings.ndjson` for a session out of logs already on disk.
 *
 * Reads, never captures. Every log that carries a screenshot also carries the view tree that
 * produced it, so this runs against finished sessions — including ones recorded long before this
 * file existed.
 */
object VisibleStringsLog {

  const val FILE_NAME: String = "visible-strings.ndjson"
  const val FORMAT_VERSION: Int = 1

  private val IMAGE_EXTENSION = Regex("\\.(png|webp|jpe?g|gif)$", RegexOption.IGNORE_CASE)

  private val JSON = Json {
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
  }

  /**
   * Writes the file into the session's own directory and returns it, or null with no captures.
   *
   * Reads the session exactly once. The header and the screen lines both need the full log set,
   * and that set — view hierarchies plus the cumulative LLM transcript — is the largest thing the
   * repo deserializes, so `getSessionInfoDirect` here would parse all of it a second time.
   *
   * Neither read goes through the cached `getSessionInfo`: that one retains every session it
   * touches, and a sweep over a large logs directory would then hold all of them at once. This
   * walks sessions one at a time and never needs the previous one again.
   */
  fun write(logsRepo: LogsRepo, sessionId: SessionId, collapseRepeats: Boolean = true): File? {
    val logs = logsRepo.getLogsForSession(sessionId)
    val lines = render(
      sessionId = sessionId,
      sessionInfo = logsRepo.sessionInfoFrom(logs),
      logs = logs,
      collapseRepeats = collapseRepeats,
    ) ?: return null
    return File(logsRepo.getSessionDir(sessionId), FILE_NAME).apply { writeText(lines) }
  }

  /** Returns the file's contents, or null when the session holds no readable capture. */
  fun render(
    sessionId: SessionId,
    sessionInfo: SessionInfo?,
    logs: List<TrailblazeLog>,
    collapseRepeats: Boolean = true,
  ): String? {
    val captures = logs.sortedBy { it.timestamp }.mapNotNull { it.toCapture() }
    if (captures.isEmpty()) return null

    val seenScreens = mutableMapOf<String, Int>()
    val screenLines = captures.mapIndexed { stepIndex, capture ->
      val strings = capture.extractStrings()
      val screenId = screenId(strings.orEmpty())
      VisibleStringsScreenLine(
        stepIndex = stepIndex,
        captureId = captureIdFrom(capture.screenshotFile),
        captureUrl = capture.screenshotFile.takeIf { it.contains('/') },
        logType = capture.logType,
        screenshotIsAnnotated = capture.annotated,
        timestamp = capture.timestamp.toString(),
        traceId = capture.traceId,
        action = capture.action,
        deviceWidth = capture.deviceWidth,
        deviceHeight = capture.deviceHeight,
        screenId = screenId,
        // A capture with no tree lost all of it, which is the strongest form of this claim and
        // true on every platform, so it does not wait on the Android-only coverage assessment.
        partialCapture = if (strings == null) true else capture.partialCapture,
        // `putIfAbsent`, so a repeat always names the FIRST step that carried these strings. With
        // `put`, the third showing of a screen would point at the second — itself emitted with
        // `strings` emptied below — and the pointer would dead-end on a blank line.
        //
        // A hierarchy-less capture sits out entirely, neither claiming nor becoming a repeat: it
        // hashes to the empty-list id, so left in it would say two failed captures showed the
        // same screen, or that a failure repeated a screen that genuinely had no text.
        repeatOfStepIndex = strings
          ?.let { seenScreens.putIfAbsent(screenId, stepIndex) }
          .takeIf { collapseRepeats },
        strings = strings.orEmpty(),
      )
    }

    return buildString {
      appendLine(JSON.encodeToString(runLine(sessionId, sessionInfo)))
      screenLines.forEach { line ->
        // A repeat keeps its place in the sequence but not its payload; the step it points at
        // already holds the strings, and a trail re-reads the same screen after every action.
        val emitted = if (line.repeatOfStepIndex == null) line else line.copy(strings = emptyList())
        appendLine(JSON.encodeToString(emitted))
      }
    }
  }

  private fun runLine(sessionId: SessionId, info: SessionInfo?) = VisibleStringsRunLine(
    session = sessionId.value,
    platform = info?.trailblazeDeviceInfo?.platform?.name,
    // The measured locale, falling back to the one the trail asked for. Only the Android
    // on-device rule records what the device reported, so without the fallback a host-driven or
    // iOS run labels itself null — and a locale diff of two unlabelled files is unreadable.
    locale = info?.trailblazeDeviceInfo?.locale ?: info?.trailConfig?.locale,
    device = info?.trailblazeDeviceId?.instanceId,
    appId = info?.targetAppInfo?.appId,
    appVersion = info?.targetAppInfo?.versionName,
    trail = info?.trailFilePath,
    startedAt = info?.timestamp?.toString(),
  )

  /**
   * The durable image name inside whatever the log recorded, which is a bare filename locally and
   * a signed URL on a farm run.
   *
   * The farm publishes artifact links in two shapes, and the repo already had to learn this once
   * for its event streams:
   *
   * - **Path-carried**: the name is the last path segment. Drop the query string, THEN decode,
   *   THEN take the last segment. Decoding first would fold a `%2F`-encoded object path into the
   *   name, and would let a signature parameter's own escaped slashes into it too.
   * - **Query-carried**: the URL path is bare `/` and the whole object key rides in a query
   *   parameter, so the rule above yields an empty string. Fall back to the last decoded query
   *   value whose final segment ends in an image extension. Signature parameters cannot
   *   false-positive, because their values do not end in one.
   *
   * When neither rule finds an image name, the whole recorded reference is kept rather than the
   * nearest path segment. A segment guessed out of an unrecognized shape is usually constant
   * across the run (`…/download?id=42`), and a constant id merges every capture onto one line in
   * a diff — a worse failure than a long id, which at least still names one image. A local run
   * cannot reach this branch in a bad way: its reference already *is* the bare filename.
   */
  internal fun captureIdFrom(screenshotFile: String): String {
    val beforeFragment = screenshotFile.substringBefore('#')
    val fromPath = decode(beforeFragment.substringBefore('?')).substringAfterLast('/')
    if (IMAGE_EXTENSION.containsMatchIn(fromPath)) return fromPath
    return beforeFragment.substringAfter('?', "")
      .split('&')
      .map { decode(it.substringAfter('=', "")).substringAfterLast('/') }
      .lastOrNull { IMAGE_EXTENSION.containsMatchIn(it) }
      ?: screenshotFile
  }

  private fun decode(value: String): String =
    runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

  private fun screenId(strings: List<ExtractedString>): String {
    val canonical = strings.joinToString("\n") { "${it.source}\t${it.text}" }
    return MessageDigest.getInstance("SHA-256")
      .digest(canonical.encodeToByteArray())
      .joinToString("") { byte -> byte.toInt().and(0xFF).toString(16).padStart(2, '0') }
      .take(16)
  }

  private class Capture(
    val screenshotFile: String,
    val tree: TrailblazeNode?,
    val legacyTree: ViewHierarchyTreeNode?,
    val deviceWidth: Int,
    val deviceHeight: Int,
    val logType: String,
    val annotated: Boolean,
    val traceId: String?,
    val action: String?,
    val partialCapture: Boolean?,
    val timestamp: Instant,
  ) {
    /**
     * Null when the capture carried no tree at all, which is not the same as a screen with no
     * text. iOS degrades to a hierarchy-less log when `describe-ui` fails, keeping the screenshot
     * — so without the distinction a tooling failure files as an empty screen, and a diff then
     * reports every string on it as deleted.
     */
    fun extractStrings(): List<ExtractedString>? = when {
      tree != null -> VisibleStringExtractor.extract(tree, deviceWidth, deviceHeight)
      legacyTree != null -> VisibleStringExtractor.extract(legacyTree)
      else -> null
    }
  }

  private fun TrailblazeLog.toCapture(): Capture? = when (this) {
    is TrailblazeLog.AgentDriverLog -> Capture(
      screenshotFile = screenshotFile ?: return null,
      tree = trailblazeNodeTree,
      legacyTree = viewHierarchy,
      deviceWidth = deviceWidth,
      deviceHeight = deviceHeight,
      logType = "AgentDriverLog",
      annotated = false,
      traceId = traceId?.traceId,
      action = action.type.name,
      partialCapture = captureCoverage?.looksTruncated,
      timestamp = timestamp,
    )

    is TrailblazeLog.TrailblazeSnapshotLog -> Capture(
      screenshotFile = screenshotFile,
      tree = trailblazeNodeTree,
      legacyTree = viewHierarchy,
      deviceWidth = deviceWidth,
      deviceHeight = deviceHeight,
      logType = "TrailblazeSnapshotLog",
      annotated = false,
      traceId = traceId?.traceId,
      action = displayName ?: "snapshot",
      partialCapture = captureCoverage?.looksTruncated,
      timestamp = timestamp,
    )

    is TrailblazeLog.TrailblazeLlmRequestLog -> Capture(
      screenshotFile = screenshotFile ?: return null,
      tree = trailblazeNodeTree,
      legacyTree = viewHierarchy,
      deviceWidth = deviceWidth,
      deviceHeight = deviceHeight,
      logType = "TrailblazeLlmRequestLog",
      // Absent means annotated: every screenshot on this log predating the flag was the
      // set-of-mark variant.
      annotated = screenshotIsAnnotated ?: true,
      traceId = traceId.traceId,
      action = llmRequestLabel,
      // This log type carries no coverage assessment at all, so completeness is unknowable.
      partialCapture = null,
      timestamp = timestamp,
    )

    else -> null
  }
}
