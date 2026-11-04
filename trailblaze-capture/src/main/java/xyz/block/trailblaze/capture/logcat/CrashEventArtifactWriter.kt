package xyz.block.trailblaze.capture.logcat

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.events.FileEventSink
import xyz.block.trailblaze.events.SessionCaptureHealth
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

  /** Enough of an Android crash's record for its exception line and first frames. */
  private const val MAX_DETAIL_LINES = 8

  // Only the runtimes' own banners count as a crash: the app's log lines that
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
   * counts only when that process logged it, so another process crashing beside the app is not
   * recorded as the app's. A crash with no process to attribute it to still counts. On Android, with
   * or without [appProcessName], a crash in a shell-launched tool (see [androidShellToolOf]) never
   * counts: such as Trailblaze's own screen recorder failing to start its encoder.
   *
   * iOS: a continuation line belongs to the record above it. Android: the process is named by a
   * later record — `AndroidRuntime`'s `Process: <name>, PID: <pid>` or the tombstone's
   * `pid: <pid>, … >>> <name> <<<` — so a match is held until one arrives or the log ends. The
   * app's `<name>:<suffix>` processes are the app's. An Android crash also keeps, as its `detail`,
   * the next few records its process logs under the same tag: the summary is only the banner
   * (`FATAL EXCEPTION: main`), and the exception that names the crash follows it.
   *
   * A crash in the first [bytesBeforeSessionStart] bytes was logged before the session took the
   * device over (while it connected), so it is left out of the index and stays only in the log.
   */
  internal fun write(
    sessionDir: File,
    deviceLog: File,
    platform: TrailblazeDevicePlatform,
    appProcessName: String? = null,
    bytesBeforeSessionStart: Long = 0,
  ) {
    val linesBeforeSession = completeLinesIn(deviceLog, bytesBeforeSessionStart)
    val events = detect(sessionDir, deviceLog, platform, appProcessName, linesBeforeSession)
    if (events.isEmpty()) return

    runCatching {
        FileEventSink(sessionDir, logLabel = "crash-event-capture").use { sink ->
          events.forEach { event ->
            sink.appendChecked(STREAM_NAME, event.timeMs, event.payload(platform, deviceLog.name))
          }
          sink.closeChecked()
        }
      }
      .onFailure {
        Console.log("[crash-events] could not write ${deviceLog.name}: ${it.message}")
        SessionCaptureHealth.markIncomplete(sessionDir, "crash-event-capture: could not write the crash index", STREAM_NAME)
      }
      .onSuccess {
        Console.log(
          "[crash-events] captured ${events.size} event(s) from ${deviceLog.name} into " +
            "events/$STREAM_NAME.ndjson",
        )
      }
  }

  private fun detect(
    sessionDir: File,
    deviceLog: File,
    platform: TrailblazeDevicePlatform,
    appProcessName: String?,
    linesBeforeSession: Int,
  ): List<CrashEvent> {
    if (!deviceLog.isFile || deviceLog.length() == 0L) return emptyList()

    val events = mutableListOf<CrashEvent>()
    val otherProcesses = mutableMapOf<String, Int>()
    // Android crashes waiting for the record that names their process, by pid.
    val unattributed = LinkedHashMap<String, MutableList<CrashEvent>>()
    // Each Android pid's latest crash, still taking its detail lines, with the tag they come under.
    val takingDetail = HashMap<String, Pair<String, CrashEvent>>()
    // Android pids running a shell-launched tool, by the tool's main class, until the pid is reused.
    val shellTools = HashMap<String, String>()
    // Crashes in those pids, by pid with their tool: the tool's unless a later record names the pid.
    val heldForTools = LinkedHashMap<String, MutableList<Pair<String, CrashEvent>>>()
    val shellToolCrashes = mutableMapOf<String, Int>()
    var lastTimestampMs: Long? = null
    // The process column of the record the current line belongs to; its continuation lines inherit it.
    var recordColumn: String? = null
    var lineNumber = 0
    var beforeSession = 0

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
            val androidRecord = if (platform == TrailblazeDevicePlatform.ANDROID) androidRecordOf(line) else null
            if (androidRecord != null) {
              androidShellToolOf(androidRecord)?.let { shellTools[androidRecord.pid] = it }
              androidProcessStartOf(androidRecord)?.let { shellTools.remove(it) }
              // A tool's own crash names no process, so a name means the pid is an app's, reused.
              androidAttributionOf(androidRecord)
                ?.takeUnless { (_, process) -> androidProcessIsShellTool(process) }
                ?.let { (pid, process) ->
                  shellTools.remove(pid)
                  heldForTools.remove(pid)?.forEach { (_, event) -> settle(event, process) }
                }
              if (events.size >= MAX_EVENTS) break
            }
            val record = androidRecord?.takeIf { appProcessName != null }
            val attribution = record?.let(::androidAttributionOf)
            if (attribution != null) {
              val (pid, process) = attribution
              unattributed.remove(pid)?.forEach { settle(it, process) }
              if (events.size >= MAX_EVENTS) break
            }
            val signature = signatureFor(line, platform)
            if (signature == null) {
              androidRecord?.let { next ->
                val (tag, crash) = takingDetail[next.pid] ?: return@let
                if (next.tag == tag && crash.detail.size < MAX_DETAIL_LINES) {
                  crash.detail += next.message.trim().take(MAX_SUMMARY_CHARS)
                }
              }
              continue
            }
            // Skipped here, not after, so they never use up the session's [MAX_EVENTS].
            if (lineNumber <= linesBeforeSession) {
              beforeSession++
              continue
            }
            val (kind, match) = signature
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
            androidRecord?.let { takingDetail[it.pid] = it.tag to event }
            // Known, not a doubt, so it holds with no app name to compare against too.
            val shellTool = androidRecord?.let { shellTools[it.pid] }
            if (shellTool != null) {
              heldForTools.getOrPut(androidRecord.pid) { mutableListOf() } += shellTool to event
            } else if (record == null) {
              events += event
            } else {
              unattributed.getOrPut(record.pid) { mutableListOf() } += event
            }
            if (events.size >= MAX_EVENTS) break
          }
        }
      }
      .onFailure {
        Console.log("[crash-events] could not read ${deviceLog.absolutePath}: ${it.message}")
        // Crashes past the failed read went unscanned, so the index can't prove they were expected.
        SessionCaptureHealth.markIncomplete(sessionDir, "crash-event-capture: could not read ${deviceLog.name}", STREAM_NAME)
      }
    // Never named: a doubt keeps the crash.
    unattributed.values.flatten().forEach { settle(it, null) }
    heldForTools.values.flatten().forEach { (tool, _) -> shellToolCrashes[tool] = (shellToolCrashes[tool] ?: 0) + 1 }
    if (beforeSession > 0) {
      Console.log(
        "[crash-events] not counting $beforeSession crash line(s) in ${deviceLog.name} logged before the session started",
      )
    }
    if (shellToolCrashes.isNotEmpty()) {
      Console.log(
        "[crash-events] not counting crash line(s) from shell-launched tools: " +
          shellToolCrashes.entries.joinToString { (tool, count) -> "$tool ($count)" },
      )
    }
    if (otherProcesses.isNotEmpty()) {
      Console.log(
        "[crash-events] not counting crash line(s) from processes other than '$appProcessName': " +
          otherProcesses.entries.joinToString { (process, count) -> "$process ($count)" },
      )
    }
    return events.sortedBy { it.sourceLine }.take(MAX_EVENTS)
  }

  /**
   * The lines [file]'s first [bytes] bytes end; a line cut by the boundary is not one of them, so
   * it counts as the session's.
   */
  private fun completeLinesIn(file: File, bytes: Long): Int {
    if (bytes <= 0) return 0
    var lines = 0
    var remaining = bytes
    runCatching {
      file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
          val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
          if (read < 0) break
          for (i in 0 until read) if (buffer[i] == '\n'.code.toByte()) lines++
          remaining -= read
        }
      }
    }.onFailure { Console.log("[crash-events] could not read ${file.name} up to the session start: ${it.message}") }
    return lines
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

  /**
   * The main class of the shell-launched Java tool [record] starts, or null. `app_process` logs
   * `Calling main entry <class>` when the shell starts a tool such as `am`, `uiautomator` or a
   * screen recorder's server; an app is forked from zygote and never logs it, so that pid is never
   * the app's.
   */
  internal fun androidShellToolOf(record: AndroidRecord): String? =
    if (record.tag == "AndroidRuntime") ANDROID_SHELL_TOOL_MAIN.find(record.message)?.groupValues?.get(1) else null

  /** The pid ActivityManager's `Start proc <pid>:<name>/…` [record] starts an app in, or null. */
  internal fun androidProcessStartOf(record: AndroidRecord): String? =
    if (record.tag == "ActivityManager") ANDROID_PROCESS_START.find(record.message)?.groupValues?.get(1) else null

  /** A tombstone names a shell-launched tool by its binary, `app_process` or `app_process64`. */
  private fun androidProcessIsShellTool(process: String): Boolean =
    ANDROID_APP_PROCESS_BINARY.matches(process.substringAfterLast('/'))

  /** The app's processes are its package and `package:<suffix>`. */
  internal fun androidProcessIsApp(process: String, appProcessName: String): Boolean =
    process == appProcessName || process.startsWith("$appProcessName:")

  /** `1772846522.234  5432  5432 E AndroidRuntime: FATAL EXCEPTION: main` → pid, tag, message. */
  private val ANDROID_RECORD = Regex("^\\s*\\d{10}\\.\\d{3}\\s+(\\d+)\\s+\\d+\\s+[VDIWEFS]\\s+(.*?):\\s(.*)$")
  private val ANDROID_RUNTIME_PROCESS = Regex("^Process: (\\S+), PID: (\\d+)")
  private val ANDROID_SHELL_TOOL_MAIN = Regex("^Calling main entry (\\S+)")
  private val ANDROID_PROCESS_START = Regex("^Start proc (\\d+):")
  private val ANDROID_APP_PROCESS_BINARY = Regex("app_process(32|64)?")
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
    /** The records that followed the crash line from its process, under its tag (Android only). */
    val detail: MutableList<String> = mutableListOf(),
  ) {
    fun payload(platform: TrailblazeDevicePlatform, sourcePath: String): JsonObject = JsonObject(
      buildMap {
        put("kind", JsonPrimitive(kind))
        put("platform", JsonPrimitive(platform.name.lowercase()))
        put("summary", JsonPrimitive(summary))
        if (detail.isNotEmpty()) put("detail", JsonArray(detail.map(::JsonPrimitive)))
        put(
          "source",
          JsonObject(
            mapOf(
              "path" to JsonPrimitive(sourcePath),
              "line" to JsonPrimitive(sourceLine),
            ),
          ),
        )
      },
    )
  }
}
