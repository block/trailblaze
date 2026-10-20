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
      CrashSignature("native_crash", Regex("(?<=\\blibc\\s{0,16}:\\s{0,16})Fatal signal \\d+\\b")),
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

  /**
   * Indexes [deviceLog]'s crashes into the session's crash stream. With [appProcessName], a crash
   * counts only when that process logged it, so another process crashing beside the app does not
   * fail the run. A crash with no process to attribute it to still counts.
   *
   * iOS: a continuation line belongs to the record above it. Android: the process is named by a
   * later record — `AndroidRuntime`'s `Process: <name>, PID: <pid>` or the tombstone's
   * `pid: <pid>, … >>> <name> <<<` — so a match is held until one arrives or the log ends. The
   * app's `<name>:<suffix>` processes are the app's.
   */
  internal fun write(
    sessionDir: File,
    deviceLog: File,
    platform: TrailblazeDevicePlatform,
    appProcessName: String? = null,
  ) {
    val events = detect(deviceLog, platform, appProcessName)
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

  private fun detect(
    deviceLog: File,
    platform: TrailblazeDevicePlatform,
    appProcessName: String?,
  ): List<CrashEvent> {
    if (!deviceLog.isFile || deviceLog.length() == 0L) return emptyList()

    val events = mutableListOf<CrashEvent>()
    val otherProcesses = mutableMapOf<String, Int>()
    // Android crashes waiting for the record that names their process, by pid.
    val unattributed = LinkedHashMap<String, MutableList<CrashEvent>>()
    var lastTimestampMs: Long? = null
    // The process column of the record the current line belongs to; its continuation lines inherit it.
    var recordColumn: String? = null
    var lineNumber = 0

    fun countOther(process: String) {
      otherProcesses[process] = (otherProcesses[process] ?: 0) + 1
    }

    fun settle(event: CrashEvent, process: String?) {
      // No name on either side is a doubt, and a doubt keeps the crash.
      if (process == null || appProcessName == null) {
        events += event
        return
      }
      if (androidProcessIsApp(process = process, appProcessName = appProcessName)) {
        events += event
      } else {
        countOther(process)
      }
    }

    runCatching {
        deviceLog.bufferedReader(Charsets.UTF_8).useLines { lines ->
          for (line in lines) {
            lineNumber++
            val parsed = LogcatParser.parseLine(line)
            parsed.epochMs?.let { lastTimestampMs = it }
            if (platform == TrailblazeDevicePlatform.IOS && IOS_RECORD_START.containsMatchIn(line)) {
              recordColumn = iosProcessColumnOf(line)
            }
            val record = if (platform == TrailblazeDevicePlatform.ANDROID && appProcessName != null) androidRecordOf(line) else null
            val attribution = record?.let(::androidAttributionOf)
            if (attribution != null) {
              val (pid, process) = attribution
              unattributed.remove(pid)?.forEach { settle(it, process) }
              if (events.size >= MAX_EVENTS) break
            }
            val (kind, match) = signatureFor(line, platform) ?: continue
            if (platform == TrailblazeDevicePlatform.IOS && appProcessName != null) {
              val column = recordColumn
              if (column != null && !iosColumnNames(column = column, process = appProcessName)) {
                countOther(column.replaceFirst(IOS_LOG_TYPE, ""))
                continue
              }
            }
            val event = CrashEvent(
              timeMs = parsed.epochMs ?: lastTimestampMs ?: 0L,
              kind = kind,
              // From the signature on: the prefix before it differs by tag padding and platform.
              summary = line.substring(match.range.first).trim().take(MAX_SUMMARY_CHARS),
              sourceLine = lineNumber,
            )
            if (record == null) {
              events += event
            } else {
              unattributed.getOrPut(record.pid) { mutableListOf() } += event
            }
            if (events.size >= MAX_EVENTS) break
          }
        }
      }
      .onFailure { Console.log("[crash-events] could not read ${deviceLog.absolutePath}: ${it.message}") }
    // Never named: a doubt keeps the crash.
    unattributed.values.flatten().forEach { settle(it, null) }
    if (otherProcesses.isNotEmpty()) {
      Console.log(
        "[crash-events] not counting crash line(s) from processes other than '$appProcessName': " +
          otherProcesses.entries.joinToString { (process, count) -> "$process ($count)" },
      )
    }
    return events.sortedBy { it.sourceLine }.take(MAX_EVENTS)
  }

  /** A `logcat -v epoch` record. */
  internal data class AndroidRecord(val pid: String, val tag: String, val message: String)

  internal fun androidRecordOf(line: String): AndroidRecord? =
    ANDROID_RECORD.find(line)?.let { AndroidRecord(it.groupValues[1], it.groupValues[2].trim(), it.groupValues[3]) }

  /**
   * The crash [record] attributes, as the crashing pid and its full process name, or null when it
   * attributes none: the crashing process's own `AndroidRuntime` `Process: <name>, PID: <pid>`, or
   * crash_dump's tombstone `pid: <pid>, … >>> <name> <<<`. libc's truncated comm is not a name.
   */
  internal fun androidAttributionOf(record: AndroidRecord): Pair<String, String>? {
    ANDROID_RUNTIME_PROCESS.find(record.message)?.let { match ->
      if (match.groupValues[2] == record.pid) return record.pid to match.groupValues[1]
    }
    ANDROID_TOMBSTONE_PROCESS.find(record.message)?.let { match ->
      return match.groupValues[1] to match.groupValues[2]
    }
    return null
  }

  /** The app's processes are its package and `package:<suffix>`. */
  internal fun androidProcessIsApp(process: String, appProcessName: String): Boolean =
    process == appProcessName || process.startsWith("$appProcessName:")

  /** `1772846522.234  5432  5432 E AndroidRuntime: FATAL EXCEPTION: main` → pid, tag, message. */
  private val ANDROID_RECORD = Regex("^\\s*\\d{10}\\.\\d{3}\\s+(\\d+)\\s+\\d+\\s+[VDIWEFS]\\s+(.*?):\\s(.*)$")
  private val ANDROID_RUNTIME_PROCESS = Regex("^Process: (\\S+), PID: (\\d+)")
  private val ANDROID_TOMBSTONE_PROCESS = Regex("^pid: (\\d+), tid: \\d+, name: .*>>> (\\S+) <<<")

  /**
   * The process column of an iOS `log stream` line, type token included: `Df Name` in the compact
   * style's `<date> <time> Df Name[pid:tid]`, `Name` in `<date> <time>  Name[pid]`. Null for a line
   * without one, such as a multi-line message's continuation.
   */
  internal fun iosProcessColumnOf(line: String): String? =
    IOS_PROCESS_COLUMN.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

  /**
   * Whether an iOS process [column] names [process]. A column may or may not lead with a type token,
   * and a name's first word can look like one (`My App`), so either reading naming [process] counts:
   * a doubt keeps the crash rather than dropping the app's own.
   */
  internal fun iosColumnNames(column: String, process: String): Boolean =
    column.equals(process, ignoreCase = true) ||
      column.replaceFirst(IOS_TYPE_TOKEN_SHAPE, "").equals(process, ignoreCase = true)

  private val IOS_PROCESS_COLUMN =
    Regex("^\\d{4}-\\d{2}-\\d{2}\\s+\\S+\\s+(.+?)\\[\\d+(?::[0-9a-fA-Fx]+)?\\]")

  /** The compact style's type tokens (Default, Debug, Info, Error, Fault, Activity), for display. */
  private val IOS_LOG_TYPE = Regex("^(?:Df|Db|I|E|F|A)\\s+")

  /** Anything shaped like a type token, so one [IOS_LOG_TYPE] doesn't list can't hide a crash. */
  private val IOS_TYPE_TOKEN_SHAPE = Regex("^\\S{1,2}\\s+")

  /** A `log stream` record starts with its date; any other line continues the record above it. */
  private val IOS_RECORD_START = Regex("^\\d{4}-\\d{2}-\\d{2}\\s")

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

  /** The crash signature [line] carries on [platform], as its kind and match, or null for an ordinary line. */
  internal fun signatureFor(line: String, platform: TrailblazeDevicePlatform): Pair<String, MatchResult>? =
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
