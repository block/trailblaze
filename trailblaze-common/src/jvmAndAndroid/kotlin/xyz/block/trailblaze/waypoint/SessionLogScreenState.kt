package xyz.block.trailblaze.waypoint

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
import java.io.File

object SessionLogScreenState {

  fun loadStep(jsonFile: File): ScreenState {
    require(jsonFile.exists()) { "Session log file not found: $jsonFile" }
    val raw = jsonFile.readText()
    val parsed = TrailblazeJson.defaultWithoutToolsInstance
      .decodeFromString(LlmRequestLogProjection.serializer(), raw)
    val screenshotPath = parsed.screenshotFile?.let { File(jsonFile.parentFile, it) }
    val screenshotBytes = screenshotPath?.takeIf { it.exists() }?.readBytes()
    val platform = parsed.trailblazeDevicePlatform
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
    )
    // Replay migration captures: when the snapshot log was written with
    // `trailblaze.captureSecondaryTree=true`, it carries the accessibility-shape side
    // tree on the new field. Wrap with [MigrationScreenState] so callers that opt in
    // (`is MigrationScreenState`) can read it; everyone else sees the plain primary
    // screen state with no behavior change.
    return parsed.driverMigrationTreeNode?.let { MigrationScreenState.wrap(base, it) }
      ?: base
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
   * Sorted by the JSON `timestamp` field (ISO-8601 string compare is order-preserving) rather
   * than filename: ATF-produced logs use hex hashes (e.g. `7d50895f_AgentDriverLog.json`), so
   * alphabetical order doesn't match emit order. The numeric-prefix convention (`008_…`) used
   * by local CLI runs sorts correctly by either key, so timestamp-sort is uniformly correct.
   * Files whose timestamp can't be read fall back to filename ordering — a stable secondary
   * key avoids non-deterministic ordering even when the timestamp probe fails.
   *
   * Used by `waypoint capture-example` and `waypoint validate --session` when they need to
   * iterate per-step logs in chronological order.
   */
  fun listScreenStateLogs(sessionDir: File): List<File> {
    require(sessionDir.isDirectory) { "Not a session directory: $sessionDir" }
    return sessionDir.listFiles { f ->
      val n = f.name
      n.endsWith("_AgentDriverLog.json") ||
        n.endsWith("_TrailblazeSnapshotLog.json") ||
        n.endsWith("_TrailblazeLlmRequestLog.json")
    }?.sortedWith(compareBy({ readTimestamp(it) ?: "" }, { it.name })) ?: emptyList()
  }

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

  private val TIMESTAMP_REGEX = Regex("""\"timestamp\"\s*:\s*\"([^\"]*)\"""")

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
  )

  private class SessionLogScreenStateImpl(
    override val screenshotBytes: ByteArray?,
    override val deviceWidth: Int,
    override val deviceHeight: Int,
    override val viewHierarchy: ViewHierarchyTreeNode,
    override val trailblazeNodeTree: TrailblazeNode?,
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform,
    override val deviceClassifiers: List<TrailblazeDeviceClassifier>,
  ) : ScreenState

}
