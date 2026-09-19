package xyz.block.trailblaze.capture.logcat

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.events.FileEventSink
import xyz.block.trailblaze.util.Console

/**
 * Builds a crash index from a completed device log. Parsing after capture keeps failures isolated
 * from trail execution while the full log remains the diagnostic source of truth.
 */
internal object CrashEventArtifactWriter {

  const val STREAM_NAME: String = "crash"

  private const val MAX_EVENTS = 100
  private const val MAX_SUMMARY_CHARS = 500

  private val signatures = mapOf(
    TrailblazeDevicePlatform.ANDROID to listOf(
      CrashSignature("fatal_exception", Regex("\\bFATAL EXCEPTION\\b", RegexOption.IGNORE_CASE)),
      CrashSignature("native_crash", Regex("\\bFatal signal \\d+\\b", RegexOption.IGNORE_CASE)),
    ),
    TrailblazeDevicePlatform.IOS to listOf(
      CrashSignature(
        "uncaught_exception",
        Regex("\\bTerminating app due to uncaught exception\\b", RegexOption.IGNORE_CASE),
      ),
      CrashSignature("fatal_error", Regex("\\bFatal error:", RegexOption.IGNORE_CASE)),
      CrashSignature(
        "terminated_by_signal",
        Regex("\\bterminated due to signal\\b", RegexOption.IGNORE_CASE),
      ),
    ),
  )

  fun write(sessionDir: File, deviceLog: File, platform: TrailblazeDevicePlatform) {
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
            val kind = signatureFor(line, platform) ?: continue
            events += CrashEvent(
              timeMs = parsed.epochMs ?: lastTimestampMs ?: 0L,
              kind = kind,
              summary = line.substringAfter(" : ", line).trim().take(MAX_SUMMARY_CHARS),
              sourceLine = lineNumber,
            )
            if (events.size == MAX_EVENTS) break
          }
        }
      }
      .onFailure { Console.log("[crash-events] could not read ${deviceLog.absolutePath}: ${it.message}") }
    return events
  }

  private fun signatureFor(line: String, platform: TrailblazeDevicePlatform): String? =
    signatures[platform]?.firstOrNull { it.pattern.containsMatchIn(line) }?.kind

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
