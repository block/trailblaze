package xyz.block.trailblaze.waypoint

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import xyz.block.trailblaze.api.MigrationScreenState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.deviceClockOffsets
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

object SessionLogScreenState {

  /**
   * Loads one screen-state log. [sessionDevice] is the device the session recorded at start
   * (see [readSessionContext]); it supplies the platform when the log itself carries none, which
   * is the case for every `AgentDriverLog` / `TrailblazeSnapshotLog`. Without it an iOS session's
   * screens would be matched as ANDROID.
   *
   * Only the platform, not the classifiers: a custom hardware classifier registered without an
   * `android` parent would skip every `android:` block for hardware that runs the same Android
   * app, which those blocks match today.
   */
  fun loadStep(jsonFile: File, sessionDevice: TrailblazeDeviceInfo? = null): ScreenState {
    require(jsonFile.exists()) { "Session log file not found: $jsonFile" }
    return decode(jsonFile.readText(), logDir = jsonFile.parentFile, sessionDevice = sessionDevice)
  }

  /**
   * Encodes [screen] with the fields a screen-state log carries, for a screen that was never
   * written to one (`trailblaze selector --live` reads the connected device's screen this way).
   * [decode] reads it back. The screenshot is left out: nothing that reads this needs it.
   */
  fun encode(screen: ScreenState, trailblazeNodeTree: TrailblazeNode? = screen.trailblazeNodeTree): String =
    compactJson.encodeToString(
      LlmRequestLogProjection.serializer(),
      LlmRequestLogProjection(
        deviceWidth = screen.deviceWidth,
        deviceHeight = screen.deviceHeight,
        viewHierarchy = screen.viewHierarchy,
        trailblazeNodeTree = trailblazeNodeTree,
        driverMigrationTreeNode = (screen as? MigrationScreenState)?.driverMigrationTreeNode,
        trailblazeDevicePlatform = screen.trailblazeDevicePlatform,
        droppedNodeFetches = screen.droppedNodeFetches,
      ),
    )

  /**
   * Reads one screen-state log's JSON. [logDir] is where a `screenshotFile` it names is looked
   * up; null skips the screenshot.
   */
  fun decode(raw: String, logDir: File? = null, sessionDevice: TrailblazeDeviceInfo? = null): ScreenState {
    val parsed = TrailblazeJson.defaultWithoutToolsInstance
      .decodeFromString(LlmRequestLogProjection.serializer(), raw)
    val screenshotPath = logDir?.let { dir -> parsed.screenshotFile?.let { File(dir, it) } }
    val screenshotBytes = screenshotPath?.takeIf { it.exists() }?.readBytes()
    val platform = parsed.trailblazeDevicePlatform
      ?: parsed.trailblazeDeviceInfo?.platform
      ?: sessionDevice?.platform
      ?: TrailblazeDevicePlatform.ANDROID
    // Surface the example's device classifier so the waypoint matcher resolves the right
    // classifier block (e.g. an `android-tablet`-labelled example, or an iOS example, validates
    // against its own block rather than the broad platform default). Prefer the explicit
    // `deviceClassifier` label capture-example stamps; else the session's projected
    // TrailblazeDeviceInfo classifiers; else none (matcher falls back to the platform).
    val deviceClassifiers: List<TrailblazeDeviceClassifier> = when {
      !parsed.deviceClassifier.isNullOrBlank() -> listOf(TrailblazeDeviceClassifier(parsed.deviceClassifier))
      !parsed.trailblazeDeviceInfo?.classifiers.isNullOrEmpty() -> parsed.trailblazeDeviceInfo!!.classifiers
      else -> emptyList()
    }
    val base = SessionLogScreenStateImpl(
      screenshotBytes = screenshotBytes,
      deviceWidth = parsed.deviceWidth,
      deviceHeight = parsed.deviceHeight,
      viewHierarchy = parsed.viewHierarchy
        ?: ViewHierarchyTreeNode(),
      trailblazeNodeTree = parsed.trailblazeNodeTree,
      trailblazeDevicePlatform = platform,
      deviceClassifiers = deviceClassifiers,
      droppedNodeFetches = parsed.droppedNodeFetches,
    )
    // Replay migration captures: when the snapshot log was written with
    // `trailblaze.captureSecondaryTree=true`, it carries the accessibility-shape side
    // tree on the new field. Wrap with [MigrationScreenState] so callers that opt in
    // (`is MigrationScreenState`) can read it; everyone else sees the plain primary
    // screen state with no behavior change.
    return parsed.driverMigrationTreeNode?.let { MigrationScreenState.wrap(base, it) }
      ?: base
  }

  /** What a session recorded about itself at start: the target, the app under test, and the device. */
  data class SessionContext(
    /** The trail's `target:` id, e.g. `clock`. Its trailmap declares every app id the target can run as. */
    val targetId: String? = null,
    /**
     * The app id installed when the session started, e.g. `com.example.app`. Null for web, and when
     * the app wasn't installed yet at start.
     */
    val appId: String?,
    /** The start device. Null for a multi-device session, whose captures may come from any device. */
    val deviceInfo: TrailblazeDeviceInfo?,
  )

  /**
   * Reads the [SessionContext] from the session's start log (the `TrailblazeSessionStatusChangeLog`
   * whose status carries `trailConfig.target` / `trailblazeDeviceInfo` / `targetAppInfo`). Returns null when the directory
   * has no such log, e.g. a hand-assembled fixture. Unreadable logs are skipped, not fatal.
   *
   * In a multi-device session the start device is only one of the screens a capture can come
   * from (after a `switchDevice` handover it is another device entirely), so [SessionContext.deviceInfo]
   * is null there rather than wrong for half the run. A session is multi-device when any tool log
   * names its device; single-device sessions have no bindings and never do.
   */
  fun readSessionContext(sessionDir: File): SessionContext? {
    val statusLogs = sessionDir.listFiles { f -> f.name.endsWith("_TrailblazeSessionStatusChangeLog.json") }
      ?.sortedWith(compareBy({ readTimestamp(it) ?: "" }, { it.name }))
      .orEmpty()
    for (log in statusLogs) {
      val status = try {
        Json.parseToJsonElement(log.readText()).jsonObject["sessionStatus"] as? JsonObject
      } catch (_: Exception) {
        null
      } ?: continue
      val appId = ((status["targetAppInfo"] as? JsonObject)?.get("appId") as? JsonPrimitive)?.contentOrNull
      val targetId = ((status["trailConfig"] as? JsonObject)?.get("target") as? JsonPrimitive)?.contentOrNull
      val deviceInfo = status["trailblazeDeviceInfo"]?.let {
        try {
          TrailblazeJson.defaultWithoutToolsInstance.decodeFromJsonElement(TrailblazeDeviceInfo.serializer(), it)
        } catch (_: Exception) {
          null
        }
      }
      if (targetId != null || appId != null || deviceInfo != null) {
        return SessionContext(
          targetId = targetId,
          appId = appId,
          deviceInfo = deviceInfo?.takeUnless { isMultiDevice(sessionDir) },
        )
      }
    }
    return null
  }

  private fun isMultiDevice(sessionDir: File): Boolean =
    sessionDir.listFiles { f -> f.name.endsWith("_TrailblazeToolLog.json") }.orEmpty().any { log ->
      // Top-level key only: a tool's own args or result can carry a `deviceName` too.
      try {
        val name = (Json.parseToJsonElement(log.readText()).jsonObject["deviceName"] as? JsonPrimitive)?.contentOrNull
        !name.isNullOrEmpty()
      } catch (_: Exception) {
        false
      }
    }

  fun listLlmRequestLogs(sessionDir: File): List<File> {
    require(sessionDir.isDirectory) { "Not a session directory: $sessionDir" }
    return sessionDir.listFiles { f -> f.name.endsWith("_TrailblazeLlmRequestLog.json") }
      ?.sortedBy { it.name }
      ?: emptyList()
  }

  /**
   * Lists every screen-state log file in [sessionDir]: `_AgentDriverLog`, `_TrailblazeSnapshotLog`,
   * and `_TrailblazeLlmRequestLog`. All three share the same `viewHierarchy + trailblazeNodeTree
   * + screenshotFile` projection (see [LlmRequestLogProjection]) so [loadStep] consumes any of
   * them.
   *
   * Sorted by the JSON `timestamp` field, parsed as an instant (a string compare would put
   * `10:00:00.500Z` before `10:00:00Z`, since `.` sorts before `Z`) and read once per file, rather
   * than filename: device-farm logs use hex hashes (e.g. `7d50895f_AgentDriverLog.json`), so
   * alphabetical order doesn't match emit order. The numeric-prefix convention (`008_…`) used
   * by local CLI runs sorts correctly by either key, so timestamp-sort is uniformly correct.
   * Files whose timestamp can't be read fall back to filename ordering — a stable secondary
   * key avoids non-deterministic ordering even when the timestamp probe fails.
   *
   * A device-stamped log is placed on the host clock first (see [sortChronologically]), so a
   * device whose clock runs ahead of the host's doesn't sort its older screens after newer ones.
   *
   * Used by `waypoint capture-example` and `waypoint validate --session` when they need to
   * iterate per-step logs in chronological order.
   */
  fun listScreenStateLogs(sessionDir: File): List<File> = listScreenStateLogsOnHostClock(sessionDir).map { it.first }

  /**
   * [listScreenStateLogs] with each log's time on the host clock (null when unreadable), for a
   * caller that compares logs across sessions: each session's device clock has its own offset.
   */
  fun listScreenStateLogsOnHostClock(sessionDir: File): List<Pair<File, Instant?>> =
    onHostClock(
      sessionDir,
      screenStateLogFiles(sessionDir).map { file ->
        file to (runCatching { file.readText() }.getOrNull()?.let(::logTimeOf) ?: LogTime(timestamp = null))
      },
    )

  /** Every screen-state log file in [sessionDir], in no particular order. */
  fun screenStateLogFiles(sessionDir: File): List<File> {
    require(sessionDir.isDirectory) { "Not a session directory: $sessionDir" }
    return sessionDir.listFiles { f ->
      val n = f.name
      n.endsWith("_AgentDriverLog.json") ||
        n.endsWith("_TrailblazeSnapshotLog.json") ||
        n.endsWith("_TrailblazeLlmRequestLog.json")
    }.orEmpty().toList()
  }

  /**
   * When a screen-state log was stamped, and what it takes to move that stamp onto the host
   * clock: whether a device's clock stamped it, when the host received it, and its trace. Read
   * by [logTimeOf]; a caller only passes it back to [sortChronologically].
   */
  class LogTime internal constructor(
    val timestamp: Instant?,
    internal val deviceStamped: Boolean = false,
    internal val hostReceivedAtMs: Long? = null,
    internal val traceId: String? = null,
  )

  /**
   * [text]'s [LogTime], read from its top-level fields only: a tool's args or a request body can
   * carry a nested `clock` or `timestamp` of its own.
   */
  fun logTimeOf(text: String): LogTime {
    val fields = runCatching { TOP_LEVEL_JSON.decodeFromString(LogTimeFields.serializer(), text) }.getOrNull()
      ?: return LogTime(timestamp = null)
    return LogTime(
      timestamp = fields.timestamp?.let(::parseInstantOrNull),
      deviceStamped = fields.clock == "device",
      hostReceivedAtMs = fields.hostReceivedAt?.let(::parseInstantOrNull)?.toEpochMilliseconds(),
      traceId = fields.traceId,
    )
  }

  /**
   * Orders [logs] from [sessionDir] the way [listScreenStateLogs] does, given each one's
   * [logTimeOf]. For a caller that reads each log once for its own checks and must order it from
   * that same read.
   *
   * A session can mix clocks: host-stamped LLM requests beside device-stamped driver captures. A
   * device-stamped log's time is shifted onto the host clock by the offset
   * [deviceClockOffsets] derives from the session's tool logs, the same derivation the recording
   * generator and the session viewers order by. A session with no anchored tool log (one whose
   * device logs were pulled off the device after the run) keeps its stamps as written.
   */
  fun sortChronologically(sessionDir: File, logs: List<Pair<File, LogTime>>): List<File> =
    onHostClock(sessionDir, logs).map { it.first }

  private fun onHostClock(sessionDir: File, logs: List<Pair<File, LogTime>>): List<Pair<File, Instant?>> {
    val offsets = if (logs.any { it.second.deviceStamped }) toolLogs(sessionDir).deviceClockOffsets() else null
    fun hostTime(time: LogTime): Instant? {
      val stamp = time.timestamp ?: return null
      if (!time.deviceStamped || offsets == null) return stamp
      val offsetMs = offsets.offsetMsForRecord(
        isToolLog = false,
        deviceName = null,
        traceId = time.traceId,
        hostReceivedAtMs = time.hostReceivedAtMs,
      )
      return stamp + offsetMs.milliseconds
    }
    return logs.map { (file, time) -> file to hostTime(time) }
      .sortedWith(compareBy<Pair<File, Instant?>, Instant?>(nullsFirst()) { it.second }.thenBy { it.first.name })
  }

  /** [sessionDir]'s tool logs, which carry the ingestion anchors clock offsets are derived from. */
  private fun toolLogs(sessionDir: File): List<TrailblazeLog> =
    sessionDir.listFiles { f -> f.name.endsWith("_TrailblazeToolLog.json") }.orEmpty().mapNotNull { file ->
      runCatching {
        TrailblazeJson.defaultWithoutToolsInstance.decodeFromString(TrailblazeLog.serializer(), file.readText())
      }.getOrNull()
    }

  private fun parseInstantOrNull(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

  /**
   * Cheap pre-filter: returns whether [jsonFile] declares a usable top-level `screenshotFile` —
   * either a **remote URL** (CI / device-farm logs reference the image by URL; the bytes aren't in
   * the downloaded zip, and existence is validated when the example is captured) OR a **local
   * sibling file** that actually exists on disk and is non-empty. Used by `waypoint
   * capture-example`'s auto-search to skip per-step logs that have no usable screenshot before
   * paying the full [loadStep] cost.
   *
   * Local existence check matters: a log can carry `screenshotFile: "...png"` while the binary
   * write failed or was pruned — without this check, auto-search picks that step, the matcher
   * accepts it, and capture-example then errors with `Could not locate any screenshot` after
   * the work is wasted. Tightening here means the candidate set is exactly the steps we
   * could actually commit. Remote URLs are exempt from the local check — they're resolved by an
   * HTTP fetch at capture time, not a sibling-file lookup.
   *
   * Implemented via [Json.parseToJsonElement] + a single field lookup so the heavy
   * `viewHierarchy` / `trailblazeNodeTree` subtrees are NOT walked into typed objects — that's
   * the difference between this being a useful filter and being just-as-expensive-as-loadStep
   * on a multi-thousand-step sweep. Returns false on any parse error so callers can treat the
   * file as not-eligible without crashing the sweep.
   */
  fun hasScreenshot(jsonFile: File): Boolean = try {
    val element = Json.parseToJsonElement(jsonFile.readText()).jsonObject
    val ssf = element["screenshotFile"]
    if (ssf !is JsonPrimitive || !ssf.isString) {
      false
    } else {
      val name = ssf.contentOrNull
      if (name != null && (name.startsWith("http://") || name.startsWith("https://"))) {
        true
      } else {
        val sibling = name?.let { File(jsonFile.parentFile, it) }
        sibling != null && sibling.isFile && sibling.length() > 0
      }
    }
  } catch (_: Exception) {
    false
  }

  /**
   * Reads the JSON `timestamp` field from a screen-state log without deserializing the rest
   * of the document. Returns the raw ISO-8601 string for cheap chronological compare; null
   * if the field is missing or the file can't be read.
   *
   * Regex on raw text rather than a typed projection is intentional — cheap chronological
   * sorting shouldn't pay for a full deserialization pass.
   */
  fun readTimestamp(jsonFile: File): String? = try {
    val raw = jsonFile.readText()
    TIMESTAMP_REGEX.find(raw)?.groupValues?.get(1)
  } catch (_: Exception) {
    null
  }

  /** The shared instance pretty-prints; [encode]'s output crosses a socket and has no human reader. */
  private val compactJson: Json by lazy { Json(TrailblazeJson.defaultWithoutToolsInstance) { prettyPrint = false } }

  private val TIMESTAMP_REGEX = Regex("""\"timestamp\"\s*:\s*\"([^\"]*)\"""")

  /** Skips every other field and accepts any scalar where a string is expected. */
  private val TOP_LEVEL_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  @Serializable
  private data class LogTimeFields(
    val timestamp: String? = null,
    val clock: String? = null,
    val hostReceivedAt: String? = null,
    val traceId: String? = null,
  )

  @Serializable
  private data class LlmRequestLogProjection(
    val deviceWidth: Int = 0,
    val deviceHeight: Int = 0,
    val viewHierarchy: ViewHierarchyTreeNode? = null,
    val trailblazeNodeTree: TrailblazeNode? = null,
    /**
     * Side-channel tree from migration-mode snapshot logs. Only present on
     * `TrailblazeSnapshotLog` JSONs written with `trailblaze.captureSecondaryTree=true`;
     * always absent on `TrailblazeLlmRequestLog` and `AgentDriverLog`. Default null
     * keeps deserialization backward-compatible with logs written before the field
     * existed.
     */
    val driverMigrationTreeNode: TrailblazeNode? = null,
    val screenshotFile: String? = null,
    val trailblazeDevicePlatform: TrailblazeDevicePlatform? = null,
    /** Per-classifier example label stamped by `capture-example --device-classifier`. */
    val deviceClassifier: String? = null,
    /** Session device context projected into the example by `capture-example`. */
    val trailblazeDeviceInfo: TrailblazeDeviceInfo? = null,
    /**
     * [ScreenState.droppedNodeFetches], so a capture known to have lost nodes still says so when
     * read back. Null (unknown) on logs that never recorded it.
     */
    val droppedNodeFetches: Int? = null,
  )

  private class SessionLogScreenStateImpl(
    override val screenshotBytes: ByteArray?,
    override val deviceWidth: Int,
    override val deviceHeight: Int,
    override val viewHierarchy: ViewHierarchyTreeNode,
    override val trailblazeNodeTree: TrailblazeNode?,
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform,
    override val deviceClassifiers: List<TrailblazeDeviceClassifier>,
    override val droppedNodeFetches: Int?,
  ) : ScreenState

}
