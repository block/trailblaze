package xyz.block.trailblaze.report.strings

import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ExtractedString
import xyz.block.trailblaze.api.ScreenTextReader
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.VisibleStringExtractor
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.visibleStringsOrExtracted
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console
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

/**
 * One screen capture and everything a person could read on it.
 *
 * Keyed by [captureId] alone — no log class, no step counter. Within a file each capture appears
 * once; across files (two locales of one trail) screens pair up by their position in the file,
 * because every string and every filename differs between them.
 *
 * [captureId] only names the capture. Where its image is lives in separate fields — [screenshot],
 * or [frame] for a capture that saved none — so a capture with no screenshot is still a line.
 */
@Serializable
data class VisibleStringsScreenLine(
  val v: Int = VisibleStringsLog.FORMAT_VERSION,
  val kind: String = "screen",
  /**
   * Names this capture, unique within a session; treat it as opaque. It is the [screenshot] name
   * when the capture has one — which a local and a farm run of one trail agree on — and otherwise
   * the id the log was stamped with as it was emitted (`captureId` on the log).
   */
  val captureId: String,
  /**
   * The screenshot's filename, the durable link back to the image. A farm run records a signed
   * URL built as `<baseArtifactUrl><filename>`, and [VisibleStringsLog.captureIdFrom] pulls the
   * same name back out of either form. Absent when the capture has no screenshot.
   */
  val screenshot: String? = null,
  /** The full reference the log recorded, when that was more than a bare filename: a signed farm
   *  URL. Absent on a local run. It expires, which is why it is not [screenshot]. */
  val captureUrl: String? = null,
  /**
   * For a capture with no [screenshot]: the filename of the frame the session's recording shows at
   * its instant, saved beside the screenshots (see [CaptureVideoFrames]). Absent when the capture
   * has a screenshot, or when no single recording covers it.
   */
  val frame: String? = null,
  /** True when [screenshot] is the set-of-mark overlay rather than the clean screenshot. */
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
  /** Hash of the source and text of [strings], so the same words at a moved position share one.
   *  Useless across locales. Not the repeat key: see [repeatOf]. */
  @OptIn(ExperimentalSerializationApi::class)
  @JsonNames("screenId")
  val screenContentHash: String,
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
  /**
   * True when OCR read this capture's screenshot and checked its iOS labels, so a label it could
   * judge that is filed as `text` here was seen drawn in one of its boxes (see
   * `VisibleStringExtractor.confirmDrawnLabels`). Labels it cannot judge keep the element-type
   * rule even here: under 3 letters or digits, in a script other than Latin or Cyrillic, or with
   * no box on screen. Absent when no OCR read ran (any other driver, a non-macOS host, or OCR
   * failed or timed out). A reader that corrects old captures by geometry must leave the labels a
   * checked capture judged alone, or it re-demotes labels OCR saw drawn.
   */
  val ocrChecked: Boolean? = null,
  /** The [captureId] of the earlier capture whose strings are identical to this one's in every
   *  field — text, source, ref, bounds and visibility — and whose line holds them; this one's are
   *  omitted. A reader copies them back verbatim, so anything less than equal would be wrong. */
  val repeatOf: String? = null,
  val strings: List<ExtractedString> = emptyList(),
)

/**
 * Writes a session's strings out as `visible-strings.ndjson`, for diffs and other tools that want
 * one file rather than a session's logs.
 *
 * An export, not the record: every screen-capture log already carries its strings, filled in as it
 * was emitted (see `withVisibleStrings`). A log from before that field existed is read from its
 * tree instead, so this still covers sessions recorded long before either existed.
 *
 * The export adds one thing the record lacks: on macOS, each iOS AXe capture's labels are checked
 * against its screenshot ([VisibleStringsScreenLine.ocrChecked]). That read takes up to a second a
 * screenshot, and far longer while Vision loads, which a run cannot afford as each log is written.
 */
object VisibleStringsLog {

  const val FILE_NAME: String = "visible-strings.ndjson"
  /**
   * 2 keys screens by screenshot and writes `bounds` as corners; 1 keyed them by step number and
   * wrote `[x, y, width, height]`. Both are four integers, which is why the version has to say.
   */
  const val FORMAT_VERSION: Int = 2

  private val IMAGE_EXTENSION = Regex("\\.(png|webp|jpe?g|gif)$", RegexOption.IGNORE_CASE)

  private val JSON = Json {
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
  }

  /**
   * Writes the file into the session's own directory and returns it, or null with no captures.
   *
   * First saves a frame from the session's recording for each capture that has no screenshot, so
   * its line can name one ([VisibleStringsScreenLine.frame]).
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
    val sessionDir = logsRepo.getSessionDir(sessionId)
    val filled = CaptureVideoFrames.fill(sessionDir, logs)
    // A capture no recording covers is the usual case for a run that recorded nothing; any other
    // miss is a frame that should have been there, and this line is what says why it isn't.
    if (filled.missed.values.any { it != CaptureVideoFrames.Miss.NOT_RECORDED }) {
      val counts = filled.missed.values.groupingBy { it }.eachCount().entries.joinToString { "${it.key}=${it.value}" }
      Console.log("[VisibleStringsLog] ${sessionId.value}: ${filled.missed.size} captures got no video frame ($counts)")
    }
    val lines = render(
      sessionId = sessionId,
      sessionInfo = logsRepo.sessionInfoFrom(logs),
      logs = logs,
      collapseRepeats = collapseRepeats,
      frames = filled.frames,
      readerFor = { screenshot, width, height -> VisionScreenTextReader(File(sessionDir, screenshot), width, height) },
    ) ?: return null
    return File(sessionDir, FILE_NAME).apply { writeText(lines) }
  }

  /**
   * Returns the file's contents, or null when the session holds no readable capture.
   *
   * @param frames the frame file of each screenshot-less capture that has one, by capture id.
   * @param readerFor the OCR reader for one screenshot, given its name and the device's
   *   dimensions; null checks no iOS labels.
   */
  fun render(
    sessionId: SessionId,
    sessionInfo: SessionInfo?,
    logs: List<TrailblazeLog>,
    collapseRepeats: Boolean = true,
    frames: Map<String, String> = emptyMap(),
    readerFor: ((screenshot: String, deviceWidth: Int, deviceHeight: Int) -> ScreenTextReader)? = null,
  ): String? {
    // One line per capture. A driver log and the LLM request made on the same screen can name
    // the same image; they are one capture, and the first to name it speaks for it. A capture with
    // no screenshot is named by the id it was stamped with, so it never merges with another.
    val captures = logs.sortedBy { it.timestamp }
      .mapNotNull { it.toCapture()?.takeIf { capture -> capture.captureId != null } }
      .distinctBy { it.captureId }
    if (captures.isEmpty()) return null

    // Keyed on the strings themselves, not [screenContentHash]: a reader restores a repeat by
    // copying the first capture's strings, so a scroll or a string going offscreen must not count.
    val seenScreens = mutableMapOf<List<ExtractedString>, String>()
    val screenLines = captures.map { capture ->
      val confirmed = readerFor?.let { capture.drawnLabelsConfirmed(it) }
      val strings = confirmed ?: capture.strings
      val screenContentHash = screenContentHash(strings.orEmpty())
      VisibleStringsScreenLine(
        captureId = capture.captureId!!,
        screenshot = capture.screenshot,
        captureUrl = capture.screenshotFile?.takeIf { it.contains('/') },
        frame = if (capture.screenshot == null) frames[capture.captureId] else null,
        screenshotIsAnnotated = capture.annotated,
        timestamp = capture.timestamp.toString(),
        traceId = capture.traceId,
        action = capture.action,
        deviceWidth = capture.deviceWidth,
        deviceHeight = capture.deviceHeight,
        screenContentHash = screenContentHash,
        // A capture with no tree lost all of it, which is the strongest form of this claim and
        // true on every platform, so it does not wait on the Android-only coverage assessment.
        partialCapture = if (strings == null) true else capture.partialCapture,
        ocrChecked = true.takeIf { confirmed != null },
        // `putIfAbsent`, so a repeat always names the FIRST capture that carried these strings.
        // With `put`, the third showing of a screen would point at the second — itself emitted
        // with `strings` emptied below — and the pointer would dead-end on a blank line.
        //
        // A hierarchy-less capture sits out entirely, neither claiming nor becoming a repeat: it
        // hashes to the empty-list id, so left in it would say two failed captures showed the
        // same screen, or that a failure repeated a screen that genuinely had no text.
        repeatOf = strings
          ?.let { seenScreens.putIfAbsent(it, capture.captureId!!) }
          .takeIf { collapseRepeats },
        strings = strings.orEmpty(),
      )
    }

    return buildString {
      appendLine(JSON.encodeToString(runLine(sessionId, sessionInfo)))
      screenLines.forEach { line ->
        // A repeat keeps its place in the sequence but not its payload; the step it points at
        // already holds the strings, and a trail re-reads the same screen after every action.
        val emitted = if (line.repeatOf == null) line else line.copy(strings = emptyList())
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

  private fun screenContentHash(strings: List<ExtractedString>): String {
    val canonical = strings.joinToString("\n") { "${it.source}\t${it.text}" }
    return MessageDigest.getInstance("SHA-256")
      .digest(canonical.encodeToByteArray())
      .joinToString("") { byte -> byte.toInt().and(0xFF).toString(16).padStart(2, '0') }
      .take(16)
  }

  private class Capture(
    /** Null for a capture with no screenshot, which is then named by [stampedId]. */
    val screenshotFile: String?,
    /** The id the log was stamped with as it was emitted, which it gets only when it has no screenshot. */
    val stampedId: String?,
    /** Null when the capture carried no tree at all, which is not the same as a screen with no
     *  text: iOS degrades to a hierarchy-less log when `describe-ui` fails, keeping the
     *  screenshot, and a diff would otherwise report every string on it as deleted. */
    val strings: List<ExtractedString>?,
    val deviceWidth: Int,
    val deviceHeight: Int,
    val annotated: Boolean,
    val traceId: String?,
    val action: String?,
    val partialCapture: Boolean?,
    val tree: TrailblazeNode?,
    val timestamp: Instant,
  ) {
    val screenshot: String? = screenshotFile?.let(::captureIdFrom)
    /** Null when there is neither — a capture recorded before ids existed — which then sits out. */
    val captureId: String? = screenshot ?: stampedId
  }

  private fun TrailblazeLog.toCapture(): Capture? = when (this) {
    is TrailblazeLog.AgentDriverLog -> Capture(
      screenshotFile = screenshotFile?.takeIf { it.isNotBlank() },
      stampedId = captureId,
      strings = visibleStringsOrExtracted,
      deviceWidth = deviceWidth,
      deviceHeight = deviceHeight,
      annotated = false,
      traceId = traceId?.traceId,
      action = action.type.name,
      partialCapture = captureCoverage?.looksTruncated,
      tree = trailblazeNodeTree,
      timestamp = timestamp,
    )

    is TrailblazeLog.TrailblazeSnapshotLog -> Capture(
      screenshotFile = screenshotFile,
      stampedId = null,
      strings = visibleStringsOrExtracted,
      deviceWidth = deviceWidth,
      deviceHeight = deviceHeight,
      annotated = false,
      traceId = traceId?.traceId,
      action = displayName ?: "snapshot",
      partialCapture = captureCoverage?.looksTruncated,
      tree = trailblazeNodeTree,
      timestamp = timestamp,
    )

    is TrailblazeLog.TrailblazeLlmRequestLog -> Capture(
      screenshotFile = screenshotFile?.takeIf { it.isNotBlank() },
      stampedId = captureId,
      strings = visibleStringsOrExtracted,
      deviceWidth = deviceWidth,
      deviceHeight = deviceHeight,
      // Absent means annotated: every screenshot on this log predating the flag was the
      // set-of-mark variant.
      annotated = !screenshotFile.isNullOrBlank() && (screenshotIsAnnotated ?: true),
      traceId = traceId.traceId,
      action = llmRequestLabel,
      // This log type carries no coverage assessment at all, so completeness is unknowable.
      partialCapture = null,
      tree = trailblazeNodeTree,
      timestamp = timestamp,
    )

    else -> null
  }

  /** Its strings with the iOS labels checked against its own screenshot, or null when no OCR read
   *  ran: an annotated screenshot has marks drawn over the very text being looked for. */
  private fun Capture.drawnLabelsConfirmed(
    readerFor: (screenshot: String, deviceWidth: Int, deviceHeight: Int) -> ScreenTextReader,
  ): List<ExtractedString>? {
    val axeTree = tree?.takeIf { it.driverDetail is DriverNodeDetail.IosAxe } ?: return null
    if (strings.isNullOrEmpty() || screenshot == null || annotated) return null
    return VisibleStringExtractor.confirmDrawnLabels(strings, axeTree, readerFor(screenshot, deviceWidth, deviceHeight))
  }
}
