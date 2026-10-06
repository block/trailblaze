package xyz.block.trailblaze.capture.logcat

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.events.FileEventSink
import xyz.block.trailblaze.events.SessionEvent
import xyz.block.trailblaze.events.SessionEvents
import xyz.block.trailblaze.util.Console

/**
 * Builds a crash index from a completed device log. Parsing after capture keeps failures isolated
 * from trail execution while the full log remains the diagnostic source of truth.
 */
object CrashEventArtifactWriter {

  const val STREAM_NAME: String = "crash"

  private const val MAX_EVENTS = 100
  private const val MAX_SUMMARY_CHARS = 500

  // A crash fails its run, so the runtimes' own banners match exactly: the app's log lines that
  // merely mention one ("reported a non-fatal exception") must not.
  private val signatures = mapOf(
    TrailblazeDevicePlatform.ANDROID to listOf(
      CrashSignature("fatal_exception", Regex("(?<=\\bAndroidRuntime\\s{0,16}:\\s{0,16})FATAL EXCEPTION\\b")),
      CrashSignature("native_crash", Regex("\\bFatal signal \\d+\\b", RegexOption.IGNORE_CASE)),
    ),
    TrailblazeDevicePlatform.IOS to listOf(
      CrashSignature(
        "uncaught_exception",
        Regex("\\bTerminating app due to uncaught exception\\b", RegexOption.IGNORE_CASE),
      ),
      CrashSignature("fatal_error", Regex("\\bFatal error:")),
      CrashSignature(
        "terminated_by_signal",
        Regex("\\bterminated due to signal\\b", RegexOption.IGNORE_CASE),
      ),
    ),
  )

  internal fun write(sessionDir: File, deviceLog: File, platform: TrailblazeDevicePlatform) {
    val events = detect(deviceLog, platform)
    if (events.isEmpty()) return

    runCatching {
        FileEventSink(sessionDir, logLabel = "crash-event-capture").use { sink ->
          events.forEach { event ->
            sink.appendChecked(STREAM_NAME, event.timeMs, event.payload(platform, deviceLog.name))
          }
          sink.closeChecked()
        }
      }
      .onFailure { Console.log("[crash-events] could not write ${deviceLog.name}: ${it.message}") }
      .onSuccess {
        Console.log(
          "[crash-events] captured ${events.size} event(s) from ${deviceLog.name} into " +
            "events/$STREAM_NAME.ndjson",
        )
      }
  }

  private fun detect(deviceLog: File, platform: TrailblazeDevicePlatform): List<CrashEvent> {
    if (!deviceLog.isFile || deviceLog.length() == 0L) return emptyList()

    val events = mutableListOf<CrashEvent>()
    var lastTimestampMs: Long? = null
    var lineNumber = 0
    runCatching {
        deviceLog.bufferedReader(Charsets.UTF_8).useLines { lines ->
          for (line in lines) {
            lineNumber++
            val parsed = LogcatParser.parseLine(line)
            parsed.epochMs?.let { lastTimestampMs = it }
            val (kind, match) = signatureFor(line, platform) ?: continue
            events += CrashEvent(
              timeMs = parsed.epochMs ?: lastTimestampMs ?: 0L,
              kind = kind,
              // From the signature on: the prefix before it differs by tag padding and platform.
              summary = line.substring(match.range.first).trim().take(MAX_SUMMARY_CHARS),
              sourceLine = lineNumber,
            )
            if (events.size == MAX_EVENTS) break
          }
        }
      }
      .onFailure { Console.log("[crash-events] could not read ${deviceLog.absolutePath}: ${it.message}") }
    return events
  }

  /**
   * The failure for a run whose session recorded a crash, naming the first one; null when the
   * session recorded none. Reads the index [write] left, so it is only complete once capture stopped.
   */
  fun failure(sessionDir: File): CrashFailure? {
    val file = File(sessionDir, "${SessionEvents.DIR_NAME}/${SessionEvents.fileName(STREAM_NAME)}")
    val lines = runCatching { file.takeIf { it.isFile }?.readLines() }.getOrNull()
      ?.filter { it.isNotBlank() }
      .orEmpty()
    if (lines.isEmpty()) return null
    val first = runCatching { Json.decodeFromString(SessionEvent.serializer(), lines.first()).data.jsonObject }
      .getOrNull()
    val summary = first?.get("summary")?.jsonPrimitive?.contentOrNull ?: "unreadable crash event"
    val details = buildList {
      first?.get("source")?.jsonObject?.let { source ->
        add("${source["path"]?.jsonPrimitive?.contentOrNull} line ${source["line"]?.jsonPrimitive?.contentOrNull}")
      }
      if (lines.size > 1) add("${lines.size} crash events in total")
    }
    return CrashFailure(
      message = "App crashed: $summary" + if (details.isEmpty()) "" else " (${details.joinToString(", ")})",
      payload = JsonObject(
        buildMap {
          put("code", JsonPrimitive(FAILURE_CODE))
          put("crashCount", JsonPrimitive(lines.size))
          first?.let { put("firstCrash", it) }
        },
      ),
    )
  }

  /**
   * A run failed by its app crashing: [message] for the run's error, [payload] for the session's
   * `failurePayload` (`code` [FAILURE_CODE], `crashCount`, and `firstCrash`, the first record of
   * the crash stream). The session's `failureKind` is [FAILURE_KIND].
   */
  data class CrashFailure(val message: String, val payload: JsonObject)

  /** `SessionStatus.Ended.Failed.failureKind` of a run failed by its app crashing. */
  const val FAILURE_KIND: String = "APP_CRASHED"

  /** The failure payload's `code`, which reports surface as `failure_code`. */
  const val FAILURE_CODE: String = "app_crashed"

  private fun signatureFor(line: String, platform: TrailblazeDevicePlatform): Pair<String, MatchResult>? =
    signatures[platform]?.firstNotNullOfOrNull { signature ->
      signature.pattern.find(line)?.let { signature.kind to it }
    }

  private data class CrashSignature(val kind: String, val pattern: Regex)

  private data class CrashEvent(
    val timeMs: Long,
    val kind: String,
    val summary: String,
    val sourceLine: Int,
  ) {
    fun payload(platform: TrailblazeDevicePlatform, sourcePath: String): JsonObject = JsonObject(
      mapOf(
        "kind" to JsonPrimitive(kind),
        "platform" to JsonPrimitive(platform.name.lowercase()),
        "summary" to JsonPrimitive(summary),
        "source" to JsonObject(
          mapOf(
            "path" to JsonPrimitive(sourcePath),
            "line" to JsonPrimitive(sourceLine),
          ),
        ),
      ),
    )
  }
}
