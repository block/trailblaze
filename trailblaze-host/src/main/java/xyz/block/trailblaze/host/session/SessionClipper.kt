package xyz.block.trailblaze.host.session

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import xyz.block.trailblaze.capture.CaptureMetadata
import xyz.block.trailblaze.capture.logcat.CrashEventArtifactWriter
import xyz.block.trailblaze.capture.logcat.LogcatParser
import xyz.block.trailblaze.capture.model.CaptureFilenames
import xyz.block.trailblaze.capture.video.VideoSlice
import xyz.block.trailblaze.events.AttachmentRef
import xyz.block.trailblaze.events.SessionEvents
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeDeviceClockOffsets
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.deviceClockOffsets
import xyz.block.trailblaze.logs.model.DeviceClockSync
import xyz.block.trailblaze.logs.model.DeviceClockSyncFile
import xyz.block.trailblaze.report.trace.SessionTraceFile
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Copies a session directory keeping only what happened in one window of it, so a report of the
 * copy shows that window alone: one failed pass of an hours-long `trailblaze loop` session rather
 * than every pass before it.
 *
 * The window is on the host clock, in epoch millis. Everything is put on that clock before it is
 * compared: device-stamped logs through the session's own log anchors, device-stamped events and
 * trace spans and Android's logcat through `session_device_clock.json`.
 *
 * - Logs are kept from the window's start to [paddingMs] past its end, the session status logs
 *   always, of its ends the one it reads as. Those are restamped to the window's start and end,
 *   because a report reads the session's span off its first and last logs, and the Started log
 *   drops the trail's plan: a loop's repeats every pass, and a report lists the steps of it that
 *   didn't run.
 * - A tool catalog, logged once when its tools are first offered, is kept when a kept log offered it.
 * - A screenshot is kept when a kept log names it.
 * - Event, network and trace lines, device log lines and video are kept [paddingMs] either side,
 *   with the files the lines refer to, and each event stream's last line before the window. Video
 *   is re-encoded to that window and `capture_metadata.json` rewritten to match, at the same
 *   frame-to-clock scale as the original. A recording that can't be cut is left out.
 * - Metadata with no time in it (the device clock samples, scripted tool bundles) is copied whole.
 *   Files derived from the whole session (`recording.trail.yaml`, `visible-strings.ndjson`,
 *   `capture_debug.txt`, `session_result.json`) and anything else unknown are left out.
 */
class SessionClipper(
  private val sliceVideo: (source: File, offsetMs: Long, durationMs: Long, output: File) -> Boolean =
    { source, offsetMs, durationMs, output -> VideoSlice.slice(source, offsetMs, durationMs, output) },
  private val videoDurationMs: (File) -> Long? = { VideoSlice.durationMs(it) },
  private val paddingMs: Long = 1_000,
) {

  /** Writes the part of [sessionDir] in [startMs]..[endMs] into [outDir], which must not exist yet. */
  fun clip(sessionDir: File, startMs: Long, endMs: Long, outDir: File) {
    require(endMs >= startMs) { "window ends before it starts: $startMs..$endMs" }
    require(!outDir.exists()) { "$outDir already exists" }
    outDir.mkdirs()
    val clocks = Clocks(DeviceClockSyncFile.read(sessionDir), null)
    val logs = readLogs(sessionDir)
    val logClocks = clocks.copy(logOffsetMs = logs.offsetMs, deviceLogOffsetsMs = logs.deviceOffsetsMs)
    val references = clipLogs(sessionDir, logs, startMs, endMs, outDir)
    // A browser's console starts with its session, give or take a moment, and no zone repeats less than half an hour.
    val sessionStartMs = logs.files.mapNotNull { it.hostMs }.minOrNull()?.minus(60_000) ?: Long.MIN_VALUE
    references.screenshots.forEach { copy(sessionDir, it, outDir) }

    val from = startMs - paddingMs
    val to = endMs + paddingMs
    val blobs = mutableSetOf<String>()
    sessionFiles(sessionDir, File(sessionDir, SessionEvents.DIR_NAME)).forEach { file ->
      val stream = SessionEvents.parseFileName(file.name) ?: return@forEach
      // A crash is an incident, not state: carried to the window's start it would read as one in the pass.
      val carryLast = stream != CrashEventArtifactWriter.STREAM_NAME
      filterNdjson(file, File(outDir, "${SessionEvents.DIR_NAME}/${file.name}"), from, to, blobs, carryLast) { deviceMs ->
        logClocks.toHost(deviceMs, stream)
      }
    }
    val metadata = captureMetadata(sessionDir)
    // A file's device is the one its metadata names: a name bound twice takes a `-2` filename.
    val owners = (metadata?.get("artifacts") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
      .mapNotNull { a -> a.string("filename")?.let { it to (a.string("deviceName") to a.string("deviceId")) } }.toMap()
    var deviceLogWindows = emptyMap<String, Pair<Long, Long>>()
    sessionFiles(sessionDir).forEach { file ->
      val name = file.name
      when {
        file.isDirectory -> if (name in WHOLE_DIRS) file.copyRecursively(File(outDir, name))
        name in WHOLE_FILES -> copy(sessionDir, name, outDir)
        name == SessionTraceFile.FILE_NAME -> clipTrace(file, File(outDir, name), from, to) { logClocks.toHost(it, null) }
        name.endsWith(".${SessionEvents.EXTENSION}") && name !in DERIVED_FILES ->
          filterNdjson(file, File(outDir, name), from, to, blobs) { logClocks.toHost(it, SessionEvents.parseFileName(name)) }
        LogcatParser.isDeviceLogFile(name) || CaptureFilenames.isDeviceLog(name) -> sessionFile(sessionDir, name)?.let { source ->
          // Android's logcat is stamped by the device; iOS's system log by the host.
          val window = if (logs.android) {
            val (device, deviceId) = owners[name] ?: (DEVICE_LOG_NAME.matchEntire(name)?.groupValues?.get(1) to null)
            logClocks.toDevice(from, device, deviceId) to logClocks.toDevice(to, device, deviceId)
          } else {
            from to to
          }
          clipDeviceLog(source, File(outDir, name), window.first, window.second, sessionStartMs)
          deviceLogWindows = deviceLogWindows + (name to window)
        }
      }
    }
    val ownedByClip = (sessionFiles(sessionDir).filter { isLogFile(it) || it.extension == SessionEvents.EXTENSION || it.name in DERIVED_FILES + SessionTraceFile.FILE_NAME } +
      sessionFiles(sessionDir, File(sessionDir, SessionEvents.DIR_NAME)).filter { SessionEvents.parseFileName(it.name) != null }).mapTo(HashSet(), File::getCanonicalFile)
    blobs.filter { sessionFile(sessionDir, it) !in ownedByClip }.forEach { copy(sessionDir, it, outDir) }
    if (metadata != null) clipCaptureMetadata(sessionDir, metadata, outDir, from, to, deviceLogWindows)
  }

  /**
   * What clipping reads of one log. Only the status and catalog logs keep their JSON, since a clip
   * rewrites them; every other log is copied by file, so a long session's view hierarchies and LLM
   * messages aren't all held at once.
   */
  private class LogFile(
    val name: String,
    val hostMs: Long?,
    val isStatus: Boolean,
    val isCatalog: Boolean,
    val toolCatalogId: String?,
    val screenshots: List<String>,
    val json: JsonObject?,
    /** When this host tool log made its named device the active one, as the report reads it. */
    val handover: Pair<Long, String>? = null,
    /** When the host got this device-stamped tool log that names no device. */
    val unnamedReceivedAtMs: Long? = null,
  )

  /** [deviceOffsetsMs] are the offsets of the devices the logs name, keyed as their streams' suffixes. */
  private class Logs(val files: List<LogFile>, val offsetMs: Long?, val deviceOffsetsMs: Map<String, Long>, val android: Boolean)

  private class References(val screenshots: Set<String>)

  /**
   * Every log's host-clock time. Only tool logs are decoded: they carry the anchors that put
   * device-stamped logs on the host clock, and the rest are read as plain JSON.
   */
  private fun readLogs(sessionDir: File): Logs {
    val toolLogs = mutableListOf<TrailblazeLog>()
    var android = false
    // Each log is read once, and becomes a LogFile once the tool logs' clock offsets are known.
    val pending = sessionFiles(sessionDir).filter(::isLogFile).mapNotNull { file ->
      val json = runCatching { TrailblazeJsonInstance.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
        ?: return@mapNotNull null
      val cls = json.string("class").orEmpty()
      val isToolLog = cls.endsWith(".TrailblazeToolLog")
      if (isToolLog) {
        runCatching { TrailblazeJsonInstance.decodeFromJsonElement(TrailblazeLog.serializer(), json) }.getOrNull()?.let(toolLogs::add)
      }
      val stampMs = json.string("timestamp")?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
      val deviceStamped = json.string("clock") == DEVICE
      val deviceName = json.string("deviceName")
      val traceId = (json["traceId"] as? JsonPrimitive)?.contentOrNull ?: (json["traceId"] as? JsonObject)?.string("traceId")
      val hostReceivedAtMs = json.string("hostReceivedAt")?.let { s -> runCatching { Instant.parse(s).toEpochMilliseconds() }.getOrNull() }
      val isStatus = cls.endsWith(".TrailblazeSessionStatusChangeLog")
      val isCatalog = cls.endsWith(".TrailblazeToolCatalogLog")
      val toolCatalogId = json.string("toolCatalogId")
      val screenshots = listOfNotNull(json.string("screenshotFile"), json.string("captureId")?.let { "$it.webp" }).filter { it.isSafeName() }
      val status = json["sessionStatus"] as? JsonObject
      // `platform` is a computed property of the device info that session logs don't write; the id's platform is.
      val deviceId = (status?.get("trailblazeDeviceInfo") as? JsonObject)?.get("trailblazeDeviceId") as? JsonObject
      if (isStatus && deviceId?.string("trailblazeDevicePlatform") == "ANDROID") android = true
      val failedSwitch = json.string("toolName") == "switchDevice" && (json["successful"] as? JsonPrimitive)?.content == "false"
      val handover = deviceName?.takeIf { isToolLog && !deviceStamped && !failedSwitch }?.let { name ->
        stampMs?.let { it + ((json["durationMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0) to name }
      }
      val unnamedReceivedAtMs = hostReceivedAtMs?.takeIf { isToolLog && deviceStamped && deviceName == null }
      val kept = json.takeIf { isStatus || isCatalog || unnamedReceivedAtMs != null }
      val logFile = { offsets: TrailblazeDeviceClockOffsets? ->
        val hostMs = stampMs?.let {
          if (!deviceStamped || offsets == null) it else it + offsets.offsetMsForRecord(isToolLog, deviceName, traceId, hostReceivedAtMs)
        }
        LogFile(file.name, hostMs, isStatus, isCatalog, toolCatalogId, screenshots, kept, handover, unnamedReceivedAtMs)
      }
      logFile
    }
    val offsets = toolLogs.deviceClockOffsets()
    val devices = toolLogs.mapNotNull { (it as? TrailblazeLog.TrailblazeToolLog)?.deviceName }.distinct()
    val deviceOffsetsMs = offsets?.let { o -> devices.associate { SessionEvents.sanitizeName(it) to o.offsetMsForRecord(false, it, null, null) } }
    return Logs(pending.map { it(offsets) }, offsets?.sessionWideOffsetMs, deviceOffsetsMs.orEmpty(), android)
  }

  private fun clipLogs(sessionDir: File, logs: Logs, startMs: Long, endMs: Long, outDir: File): References {
    fun inWindow(log: LogFile) = log.hostMs != null && log.hostMs >= startMs && log.hostMs <= endMs + paddingMs
    val offered = logs.files.filter { !it.isCatalog && inWindow(it) }.mapNotNullTo(HashSet()) { it.toolCatalogId }
    val screenshots = mutableSetOf<String>()
    // A failure written over a succeeded end is the session's end only by being later, which
    // restamping both to the window's end would lose; so the clip keeps the end the session reads as.
    val ends = logs.files.filter { it.isStatus && (it.json!!["sessionStatus"] as? JsonObject)?.containsKey("durationMs") == true }
    val end = ends.maxByOrNull { it.hostMs ?: Long.MIN_VALUE }
    val handovers = logs.files.mapNotNull { it.handover }.sortedBy { it.first }
    for (log in logs.files) {
      when {
        log.isStatus -> if (log === end || log !in ends) {
          File(outDir, log.name).writeText(restampStatus(log, startMs, endMs).toString())
        }
        inWindow(log) -> {
          // The report finds an unnamed device's tool by the handover before it, which may be before the window.
          val device = log.unnamedReceivedAtMs?.let { at -> handovers.lastOrNull { it.first <= at }?.second }
          if (device == null) {
            File(sessionDir, log.name).copyTo(File(outDir, log.name))
          } else {
            File(outDir, log.name).writeText(JsonObject(log.json!! + ("deviceName" to JsonPrimitive(device))).toString())
          }
          screenshots += log.screenshots
        }
        log.isCatalog && log.toolCatalogId in offered ->
          File(outDir, log.name).writeText(restamped(log.json!!, startMs).toString())
      }
    }
    return References(screenshots)
  }

  /** The Started log moved to the window's start without the trail's plan, and an end moved to the window's end. */
  private fun restampStatus(log: LogFile, startMs: Long, endMs: Long): JsonObject {
    val json = log.json!!
    val status = json["sessionStatus"] as? JsonObject ?: return json
    val started = status.string("class")?.endsWith(".SessionStatus.Started") == true
    return when {
      started -> JsonObject(restamped(json, startMs) + ("sessionStatus" to JsonObject(status - "rawYaml")))
      status.containsKey("durationMs") && log.hostMs != null -> {
        // Past the Started log's stamp: of two statuses stamped alike, the session reads as the first.
        val endedMs = maxOf(minOf(log.hostMs, endMs), startMs + 1)
        val ended = JsonObject(status + ("durationMs" to JsonPrimitive(maxOf(0L, endedMs - startMs))))
        JsonObject(restamped(json, endedMs) + ("sessionStatus" to ended))
      }
      else -> json
    }
  }

  /**
   * Copies the lines of [source] stamped in [from]..[to], their `timeMs` (or legacy `timestampMs`)
   * put on the host clock by [deviceToHost] when the line is device-stamped, and the lines with no
   * time. With [carryLast], the stream's last envelope (`timeMs`) line before [from] is kept too,
   * stamped at [from], the padded window's start: a stream that logs its state once, at launch, still
   * has it in the clip. A line in a producer's own schema, a network exchange's, is never carried,
   * since an old response at the window's start would read as one in the pass. Records
   * the body and attachment files the kept lines refer to into [blobs]. Writes nothing when no line
   * is kept.
   */
  private fun filterNdjson(
    source: File,
    output: File,
    from: Long,
    to: Long,
    blobs: MutableSet<String>,
    carryLast: Boolean = false,
    deviceToHost: (Long) -> Long,
  ) {
    if (!source.isFile) return
    var writer: java.io.BufferedWriter? = null
    fun write(json: JsonObject, line: String) {
      collectFileRefs(json, blobs)
      val out = writer ?: output.also { it.parentFile.mkdirs() }.bufferedWriter().also { writer = it }
      out.write(line)
      out.newLine()
    }
    var last: JsonObject? = null
    try {
      source.bufferedReader().useLines { lines ->
        for (line in lines) {
          val json = runCatching { TrailblazeJsonInstance.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
          // As the report reads a line: by its envelope time when that is a number, else its legacy one.
          val field = listOf(SessionEvents.TIME_FIELD, SessionEvents.LEGACY_TIME_FIELD).firstOrNull { json.long(it) != null }
          val hostMs = field?.let { json.long(it) }?.let { if (json.string(SessionEvents.CLOCK_FIELD) == DEVICE) deviceToHost(it) else it }
          when {
            hostMs == null -> write(json, line)
            hostMs < from -> if (carryLast && field == SessionEvents.TIME_FIELD) last = JsonObject(json - SessionEvents.CLOCK_FIELD + (field to JsonPrimitive(from)))
            hostMs <= to -> {
              last?.let { write(it, it.toString()) }
              last = null
              write(json, line)
            }
          }
        }
      }
      last?.let { write(it, it.toString()) }
    } finally {
      writer?.close()
    }
  }

  /** Network bodies (`blobPath`) and event attachments (`{"$attachment": true, "path": ...}`). */
  private fun collectFileRefs(element: JsonElement, into: MutableSet<String>) {
    when (element) {
      is JsonObject -> {
        element.string("blobPath")?.let { into += it }
        if ((element[AttachmentRef.MARKER_FIELD] as? JsonPrimitive)?.booleanOrNull == true) {
          element.string("path")?.let { into += it }
        }
        element.values.forEach { collectFileRefs(it, into) }
      }
      is JsonArray -> element.forEach { collectFileRefs(it, into) }
      else -> Unit
    }
  }

  /**
   * Trace spans that overlap [from]..[to] (`ts` and `dur` are microseconds), cut to it on their own
   * clock, so a span that began before the window still parents the spans inside it without
   * stretching the profile past the clip; and every event with no time.
   */
  private fun clipTrace(source: File, output: File, from: Long, to: Long, deviceToHost: (Long) -> Long) {
    val events = runCatching { TrailblazeJsonInstance.parseToJsonElement(source.readText()) as? JsonArray }.getOrNull() ?: return
    val kept = events.mapNotNull { event ->
      val json = event as? JsonObject ?: return@mapNotNull null
      val micros = (json["ts"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull json
      val stampMs = (micros / 1000).toLong()
      val hostMs = if (json.string("clock") == DEVICE) deviceToHost(stampMs) else stampMs
      val endMs = hostMs + ((json["dur"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) / 1000
      when {
        hostMs > to || endMs < from -> null
        hostMs >= from && endMs <= to -> json
        else -> {
          val startMs = maxOf(hostMs, from)
          // A device span keeps the clock that marks it device work, so its new start is corrected
          // for the offset there, which a clock step between the two starts would change.
          val guessMs = stampMs + startMs - hostMs
          val tsMs = if (json.string("clock") == DEVICE) guessMs + startMs - deviceToHost(guessMs) else startMs
          JsonObject(json + ("ts" to JsonPrimitive(tsMs * 1000)) + ("dur" to JsonPrimitive(((minOf(endMs, to.toDouble()) - startMs) * 1000).toLong())))
        }
      }
    }
    output.writeText(JsonArray(kept).toString())
  }

  /**
   * The time a browser's console line starts with, which `WebConsoleCapture` writes in the host's
   * local time with no offset: read in this host's zone, so it holds for a clip taken where the
   * session ran. In the hour a fall-back repeats, the time names two moments; a log runs forward, so
   * the line was written at the first not before [afterMs], the time of the line before it or the session's start.
   */
  private fun webConsoleMs(line: String, afterMs: Long): Long? = WEB_CONSOLE_TIME.find(line)?.let { match ->
    val local = runCatching { LocalDateTime.parse("${match.groupValues[1]}T${match.groupValues[2]}") }.getOrNull() ?: return null
    val moments = ZoneId.systemDefault().rules.getValidOffsets(local).map { local.toInstant(it).toEpochMilli() }
    moments.firstOrNull { it >= afterMs } ?: moments.firstOrNull()
  }

  /** Device log lines in [from]..[to] on the log's own clock; an unstamped line goes with the line before it. */
  private fun clipDeviceLog(source: File, output: File, from: Long, to: Long, sessionStartMs: Long) {
    output.bufferedWriter().use { out ->
      var keep = false
      var lastMs = sessionStartMs
      source.bufferedReader().useLines { lines ->
        for (line in lines) {
          (LogcatParser.parseLine(line).epochMs ?: webConsoleMs(line, lastMs))?.let {
            lastMs = it
            keep = it in from..to
          }
          if (keep) {
            out.write(line)
            out.newLine()
          }
        }
      }
    }
  }

  /**
   * The recordings cut to [from]..[to] and every other artifact whose file the clip kept. A cut
   * recording keeps the original's frame-to-clock scale, so a moment lands on the same frame.
   */
  private fun clipCaptureMetadata(
    sessionDir: File,
    metadata: JsonObject,
    outDir: File,
    from: Long,
    to: Long,
    deviceLogWindows: Map<String, Pair<Long, Long>>,
  ) {
    val artifacts = (metadata["artifacts"] as? JsonArray).orEmpty().mapNotNull { element ->
      val artifact = element as? JsonObject ?: return@mapNotNull null
      val name = artifact.string("filename")?.takeIf { it.isSafeName() } ?: return@mapNotNull null
      val startMs = artifact.long("startTimestampMs") ?: return@mapNotNull null
      when {
        artifact.string("type") in VIDEO_TYPES ->
          sessionFile(sessionDir, name)?.let { clipVideo(it, File(outDir, name), artifact, startMs, from, to) }
        name in deviceLogWindows -> {
          val (logFrom, logTo) = deviceLogWindows.getValue(name)
          val endMs = artifact.long("endTimestampMs")
          JsonObject(
            artifact +
              ("startTimestampMs" to JsonPrimitive(maxOf(startMs, logFrom))) +
              ("endTimestampMs" to JsonPrimitive(minOf(endMs ?: logTo, logTo))),
          )
        }
        File(outDir, name).exists() -> artifact
        else -> null
      }
    }
    if (artifacts.isEmpty()) return
    File(outDir, CaptureMetadata.FILENAME).writeText(JsonObject(metadata + ("artifacts" to JsonArray(artifacts))).toString())
  }

  private fun captureMetadata(sessionDir: File): JsonObject? {
    val source = sessionFile(sessionDir, CaptureMetadata.FILENAME) ?: return null
    return runCatching { TrailblazeJsonInstance.parseToJsonElement(source.readText()).jsonObject }.getOrNull()
  }

  private fun clipVideo(source: File, output: File, artifact: JsonObject, startMs: Long, from: Long, to: Long): JsonObject? {
    // Without the file's own length, a cut can't be put on the file's time.
    val fileMs = videoDurationMs(source) ?: return null
    val endMs = artifact.long("endTimestampMs") ?: (startMs + fileMs)
    if (endMs <= startMs) return null
    // File time per clock time. A recorder's bookends can differ from its frames' span, and the
    // report scales one onto the other, so the cut maps through the same scale.
    val scale = fileMs.toDouble() / (endMs - startMs)
    val clipStart = maxOf(startMs, from)
    val clipEnd = minOf(endMs, to)
    if (clipEnd <= clipStart) return null
    val offset = ((clipStart - startMs) * scale).toLong()
    val duration = ((clipEnd - clipStart) * scale).toLong()
    if (!sliceVideo(source, offset, duration, output)) return null
    val clippedMs = videoDurationMs(output) ?: return null.also { output.delete() }
    return JsonObject(
      artifact +
        ("startTimestampMs" to JsonPrimitive(clipStart)) +
        ("endTimestampMs" to JsonPrimitive(clipStart + (clippedMs / scale).toLong())),
    )
  }

  /**
   * Puts device-clock stamps on the host clock: the measured samples in `session_device_clock.json`
   * when the session has them, else the offset the session's logs were anchored at.
   */
  private data class Clocks(val sync: DeviceClockSync?, val logOffsetMs: Long?, val deviceLogOffsetsMs: Map<String, Long> = emptyMap()) {
    fun toHost(deviceMs: Long, stream: String?): Long =
      (if (stream != null) sync?.toHostMsForStream(deviceMs, stream) else sync?.toHostMs(deviceMs))
        ?: (stream?.let(::ownerOffsetMs) ?: logOffsetMs)?.let { deviceMs + it }
        ?: deviceMs

    /**
     * A host-clock moment on the clock of the device [deviceId], else [deviceName], names: the
     * session's start device when neither names one. As `DeviceClockSync.trackForStream` does, a
     * named device has its own clock or none, and with none falls back to its own log anchors.
     */
    fun toDevice(hostMs: Long, deviceName: String?, deviceId: String?): Long {
      val devices = sync?.devices.orEmpty()
      val own = devices.filter { it.deviceId == deviceId }.ifEmpty { devices.filter { deviceName != null && it.deviceName?.let(SessionEvents::sanitizeName) == SessionEvents.sanitizeName(deviceName) } }
      val track = if (own.isEmpty()) sync?.track() else own.firstOrNull { it.samples.isNotEmpty() }
      track?.deviceAheadMsAt(hostMs)?.let { return hostMs + it }
      val offsetMs = deviceName?.let { deviceLogOffsetsMs[SessionEvents.sanitizeName(it)] } ?: logOffsetMs
      return offsetMs?.let { hostMs - it } ?: hostMs
    }

    /** The log offset of the device whose name [stream] ends in, matched as `DeviceClockSync.trackForStream` does. */
    private fun ownerOffsetMs(stream: String): Long? =
      deviceLogOffsetsMs.filterKeys { SessionEvents.sanitizeName(stream).endsWith(".$it") }.maxByOrNull { it.key.length }?.value
  }

  /** [json] stamped at [hostMs] on the host clock. */
  private fun restamped(json: JsonObject, hostMs: Long) = JsonObject(
    json - "clock" - "hostReceivedAt" + ("timestamp" to JsonPrimitive(Instant.fromEpochMilliseconds(hostMs).toString())),
  )

  /**
   * Copies [relativePath] to the same path in [outDir] when it resolves, links followed, to a file
   * inside [sessionDir]: event lines name these paths.
   */
  private fun copy(sessionDir: File, relativePath: String, outDir: File) {
    val source = sessionFile(sessionDir, relativePath) ?: return
    val target = File(outDir, relativePath).toPath().normalize().takeIf { it.startsWith(outDir.toPath().normalize()) } ?: return
    if (!target.toFile().exists()) source.copyTo(target.toFile())
  }

  /** The entries of [dir], by name, but those that resolve, through a link, to outside [sessionDir]. */
  private fun sessionFiles(sessionDir: File, dir: File = sessionDir): List<File> {
    val root = sessionDir.canonicalFile
    return dir.listFiles().orEmpty().filter { it.canonicalFile.startsWith(root) }.sortedBy { it.name }
  }

  /** The file [relativePath] names in [sessionDir], unless it resolves, through a link, to outside the session. */
  private fun sessionFile(sessionDir: File, relativePath: String): File? {
    val root = sessionDir.canonicalFile
    // A path no file system can name, one with a NUL in it, throws rather than naming no file.
    return runCatching { File(root, relativePath).canonicalFile }.getOrNull()?.takeIf { it.isFile && it.startsWith(root) }
  }

  private companion object {
    const val DEVICE = "device"
    val VIDEO_TYPES = setOf("VIDEO", "VIDEO_WEBM")
    val WHOLE_FILES = setOf(DeviceClockSync.FILE_NAME, ".imported", ".trailrunner-trail-id")
    val WHOLE_DIRS = setOf("in-process-scripted-tools", "inline-script-tools")
    val DERIVED_FILES = setOf("visible-strings.ndjson", "recording.trail.yaml", "capture_debug.txt", "session_result.json")
    val DEVICE_LOG_NAME = Regex("^device-(.+)\\.log$")
    val WEB_CONSOLE_TIME = Regex("""^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}\.\d{3}) """)

    /** As `LogsRepo` reads a session: a `.json` file whose name starts with a hex digit. */
    fun isLogFile(file: File): Boolean =
      file.isFile && file.name.endsWith(".json") && file.name.first().isHexDigit() && file.name != CaptureMetadata.FILENAME

    fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    fun String.isSafeName() = isNotEmpty() && '/' !in this && '\\' !in this && this != "." && this != ".."

    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
  }
}
