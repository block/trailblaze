package xyz.block.trailblaze.trailhead

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import xyz.block.trailblaze.events.EventStreamScanner
import xyz.block.trailblaze.events.SessionEvents
import xyz.block.trailblaze.events.SessionEventsDir
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.model.DeviceClockSyncFile
import xyz.block.trailblaze.logs.model.DeviceClockTrack
import xyz.block.trailblaze.logs.model.TrailblazeClockDomain
import xyz.block.trailblaze.scripting.host.TrailblazeHostFunctionException
import xyz.block.trailblaze.toolcalls.SessionDeviceBindings
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

/**
 * The framework host functions behind `trailRun.events` (`list`, `query`). They are not trailmap
 * host functions: the dispatcher answers them before the registry, and only while a trail's
 * `teardown` runs — the window a query reads is fixed by then.
 *
 * Filtering happens here, line by line, so a large stream (network capture) never loads into
 * JavaScript. A record is matched on its `data` when it is a [xyz.block.trailblaze.events.SessionEvent]
 * envelope and on the whole line otherwise (a stream that writes a rich schema directly).
 *
 * In a multi-device session a stream written per device (`<stream>.<device>`) resolves to the
 * active device's file. A shared stream is read without the records that name another device in
 * their `device` field, as every `trailhead` record does.
 *
 * A network capture can be written at the session root rather than under `events/`:
 * `network.ndjson` for one device, `network.<device>.ndjson` per device; `network` resolves to it there.
 */
object TrailEventsHostFunctions {

  const val DEFAULT_LIMIT: Int = 100
  const val MAX_LIMIT: Int = 1000

  fun handles(name: String): Boolean =
    name == TeardownWire.HOST_FUNCTION_TRAIL_EVENTS_LIST ||
      name == TeardownWire.HOST_FUNCTION_TRAIL_EVENTS_QUERY

  /** Runs [name] with [args]; throws [TrailblazeHostFunctionException] with a script-safe message. */
  fun call(name: String, args: JsonObject, context: TrailblazeToolExecutionContext): JsonElement {
    val run = TrailheadRuns.current(sessionIdOf(context))
      ?.takeIf { it.phase == TrailheadPhase.TEARDOWN }
      ?: throw TrailblazeHostFunctionException("$name is only available inside a trailhead's teardown.")
    val sessionDir = SessionEventsDir.resolve(context)
    return when (name) {
      TeardownWire.HOST_FUNCTION_TRAIL_EVENTS_LIST -> JsonArray(list(sessionDir, context).map(::JsonPrimitive))
      else -> query(sessionDir, run, args, context)
    }
  }

  /** The streams [list] reports, for the `ctx.events` host functions. */
  internal fun listStreams(sessionDir: File?, context: TrailblazeToolExecutionContext): List<String> = list(sessionDir, context)

  /** The file [stream] resolves to for the active device, or null while it doesn't exist. */
  internal fun streamFileFor(sessionDir: File, stream: String, context: TrailblazeToolExecutionContext): File? =
    streamFile(sessionDir, stream, context)

  /** One stream line as a record: its order key, and what a match runs against. */
  internal class ParsedRecord(val timeMs: Long, val data: JsonElement)

  /**
   * Parses one stream [line]: `data` for a [xyz.block.trailblaze.events.SessionEvent] envelope, the
   * whole line otherwise. Null for a line that isn't a JSON object, or that names another bound
   * device in a multi-device session.
   */
  /** The clock [file]'s device-stamped records are on, or null when the session measured none. */
  internal fun clockTrackFor(sessionDir: File, file: File): DeviceClockTrack? =
    SessionEvents.parseFileName(file.name)?.let { name -> DeviceClockSyncFile.read(sessionDir)?.trackForStream(name) }

  /** [line] as a record whose time is on the host clock, moved there by [clockTrack] when device-stamped. */
  internal fun parseRecord(
    line: String,
    context: TrailblazeToolExecutionContext,
    clockTrack: DeviceClockTrack? = null,
  ): ParsedRecord? {
    val record = runCatching { TrailblazeJsonInstance.parseToJsonElement(line) }.getOrNull() as? JsonObject
      ?: return null
    val data = if (record.containsKey("data") && record.containsKey(SessionEvents.TIME_FIELD)) record["data"]!! else record
    val bindings = context.deviceBindings?.takeIf { it.names.size > 1 }
    if (bindings != null && isAnotherDevices(data, bindings)) return null
    return ParsedRecord(hostTimeOf(record, clockTrack), data)
  }

  private fun list(sessionDir: File?, context: TrailblazeToolExecutionContext): List<String> {
    val eventsDir = sessionDir?.let { File(it, SessionEvents.DIR_NAME) }
    val streams = eventsDir?.listFiles()?.mapNotNull { SessionEvents.parseFileName(it.name) }.orEmpty()
    val bindings = context.deviceBindings?.takeIf { it.names.size > 1 }
      ?: return (streams + ROOT_STREAMS.filter { rootStreamFile(sessionDir, it) != null }).distinct().sorted()
    val activeSuffix = ".${SessionEvents.sanitizeName(bindings.activeName)}"
    val rootForActive = ROOT_STREAMS.filter { rootDeviceStreamFile(sessionDir, it, bindings.activeName) != null }
    return streams
      .filter { ownerOf(it, bindings).let { owner -> owner == null || owner == bindings.activeName } }
      .map { if (ownerOf(it, bindings) == bindings.activeName) it.removeSuffix(activeSuffix) else it }
      .plus(rootForActive)
      .distinct()
      .sorted()
  }

  /**
   * The bound device whose per-device file [stream] names, or null for a shared stream. Compared
   * as file names, the way both are written, and by the longest match, so `network.kiosk.buyer`
   * belongs to `kiosk.buyer`, not `buyer`.
   */
  private fun ownerOf(stream: String, bindings: SessionDeviceBindings): String? {
    val file = SessionEvents.sanitizeName(stream)
    return bindings.names
      .filter { file.endsWith(".${SessionEvents.sanitizeName(it)}") }
      .maxByOrNull { SessionEvents.sanitizeName(it).length }
  }

  private fun query(
    sessionDir: File?,
    run: TrailheadRun,
    args: JsonObject,
    context: TrailblazeToolExecutionContext,
  ): JsonElement {
    val stream = (args["stream"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
      ?: throw TrailblazeHostFunctionException("trailRun.events.query needs a stream name.")
    val match = when (val raw = args["match"]) {
      null -> null
      is JsonObject -> try {
        EventMatch.parse(raw)
      } catch (e: IllegalArgumentException) {
        throw TrailblazeHostFunctionException("trailRun.events.query: ${e.message}")
      }
      else -> throw TrailblazeHostFunctionException("trailRun.events.query: `match` must be an object.")
    }
    val window = (args["window"] as? JsonPrimitive)?.contentOrNull ?: WINDOW_TRAIL
    val fromMs = when (window) {
      WINDOW_TRAIL -> run.trailStartMs ?: run.trailEndMs ?: run.trailheadStartMs
      WINDOW_TRAILHEAD_AND_TRAIL -> run.trailheadStartMs
      else -> throw TrailblazeHostFunctionException(
        "trailRun.events.query: unknown window '$window' (use '$WINDOW_TRAIL' or '$WINDOW_TRAILHEAD_AND_TRAIL').",
      )
    }
    val toMs = run.trailEndMs ?: Long.MAX_VALUE
    val limit = ((args["limit"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    val file = sessionDir?.let { streamFile(it, stream, context) } ?: return JsonArray(emptyList())
    // The window is on the host clock; a device-stamped record is moved onto it first, on the clock
    // of the device whose file this is.
    val clockTrack = clockTrackFor(sessionDir, file)
    val out = mutableListOf<JsonElement>()
    // Lines without the match's literals are skipped as bytes, before any decode. A finished run's
    // stream is read whole, with no line-size cap and including a last record with no newline.
    val scanner = EventStreamScanner(
      EventStreamScanner.needlesFor(args["match"] as? JsonObject),
      maxLineBytes = Int.MAX_VALUE,
      finishedStream = true,
    )
    scanner.forward(file, 0L, EventStreamScanner.Stats()) { line ->
      val record = parseRecord(line, context, clockTrack) ?: return@forward true
      if (record.timeMs < fromMs || record.timeMs > toMs) return@forward true
      if (match != null && !match.matches(record.data)) return@forward true
      out += buildJsonObject {
        put("timeMs", record.timeMs)
        put("data", record.data)
      }
      out.size < limit
    }
    return JsonArray(out)
  }

  private fun streamFile(sessionDir: File, stream: String, context: TrailblazeToolExecutionContext): File? {
    val eventsDir = File(sessionDir, SessionEvents.DIR_NAME)
    val bindings = context.deviceBindings?.takeIf { it.names.size > 1 }
    // Another device's capture can lack a device field, so its file is refused by name.
    bindings?.let { ownerOf(stream, it) }?.takeIf { it != bindings.activeName }?.let { other ->
      throw TrailblazeHostFunctionException(
        "trailRun.events.query: '$stream' is device $other's stream; a query reads only its own device's.",
      )
    }
    val perDevice = bindings?.let { File(eventsDir, SessionEvents.fileName("$stream.${it.activeName}")) }
    if (perDevice != null && perDevice.isFile) return perDevice
    if (bindings != null && stream in ROOT_STREAMS) {
      rootDeviceStreamFile(sessionDir, stream, bindings.activeName)?.let { return it }
    }
    File(eventsDir, SessionEvents.fileName(stream)).takeIf { it.isFile }?.let { return it }
    return if (bindings == null && stream in ROOT_STREAMS) rootStreamFile(sessionDir, stream) else null
  }

  private fun rootStreamFile(sessionDir: File?, stream: String): File? =
    sessionDir?.let { File(it, SessionEvents.fileName(stream)) }?.takeIf { it.isFile }

  /**
   * [device]'s root capture of [stream]. Named with the device's label as given, the way the
   * capture writes it: sanitizing could turn `buyer tablet` into its sibling `buyer_tablet`.
   */
  private fun rootDeviceStreamFile(sessionDir: File?, stream: String, device: String): File? {
    if (sessionDir == null || device.contains('/') || device.contains('\\')) return null
    return File(sessionDir, "$stream.$device.${SessionEvents.EXTENSION}").takeIf { it.isFile }
  }

  /** Whether [data] names a bound device other than the active one. */
  private fun isAnotherDevices(data: JsonElement, bindings: SessionDeviceBindings): Boolean {
    val device = ((data as? JsonObject)?.get(TrailheadEventLog.DEVICE_FIELD) as? JsonPrimitive)?.contentOrNull
    return device != null && device != bindings.activeName && device in bindings.names
  }

  private fun timeOf(record: JsonObject): Long =
    (record[SessionEvents.TIME_FIELD] as? JsonPrimitive)?.longOrNull
      ?: (record[SessionEvents.LEGACY_TIME_FIELD] as? JsonPrimitive)?.longOrNull
      ?: 0L

  /**
   * [timeOf] on the host clock: a `clock: "device"` record converted through [clockTrack], or left
   * raw when there is no measurement to convert with, which is what every record read as before.
   */
  private fun hostTimeOf(record: JsonObject, clockTrack: DeviceClockTrack?): Long {
    val timeMs = timeOf(record)
    val clock = (record[SessionEvents.CLOCK_FIELD] as? JsonPrimitive)?.contentOrNull
    if (clock != TrailblazeClockDomain.DEVICE.wireName) return timeMs
    return clockTrack?.toHostMs(timeMs) ?: timeMs
  }

  private fun sessionIdOf(context: TrailblazeToolExecutionContext) = context.sessionProvider.invoke().sessionId

  /** Streams a capture writes at the session root instead of under `events/`, suffixed per device in a multi-device session. */
  private val ROOT_STREAMS = setOf("network")

  const val WINDOW_TRAIL = "trail"
  const val WINDOW_TRAILHEAD_AND_TRAIL = "trailhead-and-trail"
}
