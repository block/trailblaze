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
    val base = SessionLogScreenStateImpl(
      screenshotBytes = screenshotBytes,
      deviceWidth = parsed.deviceWidth,
      deviceHeight = parsed.deviceHeight,
      viewHierarchy = parsed.viewHierarchy
        ?: ViewHierarchyTreeNode(),
      trailblazeNodeTree = parsed.trailblazeNodeTree,
      trailblazeDevicePlatform = platform,
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
   * Cheap pre-filter: returns whether [jsonFile] declares a non-null top-level `screenshotFile`
   * field AND the referenced image file actually exists on disk (and is non-empty). Used by
   * `waypoint capture-example`'s auto-search to skip over per-step logs that have no usable
   * screenshot before paying the full [loadStep] cost.
   *
   * Existence check matters: a log can carry `screenshotFile: "...png"` while the binary
   * write failed or was pruned — without this check, auto-search picks that step, the matcher
   * accepts it, and capture-example then errors with `Could not locate any screenshot` after
   * the work is wasted. Tightening here means the candidate set is exactly the steps we
   * could actually commit.
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
      val sibling = name?.let { File(jsonFile.parentFile, it) }
      sibling != null && sibling.isFile && sibling.length() > 0
    }
  } catch (_: Exception) {
    false
  }

  /**
   * Reads the JSON `timestamp` field from a screen-state log without deserializing the rest
   * of the document. Returns the raw ISO-8601 string for cheap chronological compare; null
   * if the field is missing or the file can't be read.
   *
   * Regex on raw text rather than a typed projection is intentional — same approach used in
   * `WaypointMigrateTrailCommand.readTimestamp`, kept consistent so a shared sort key does
   * the same thing across both call sites.
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
  )

  private class SessionLogScreenStateImpl(
    override val screenshotBytes: ByteArray?,
    override val deviceWidth: Int,
    override val deviceHeight: Int,
    override val viewHierarchy: ViewHierarchyTreeNode,
    override val trailblazeNodeTree: TrailblazeNode?,
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform,
  ) : ScreenState {
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }
}
