package xyz.block.trailblaze.trailhead

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.TrailYamlItem

/**
 * Everything that must be identical for one trailhead run's hand-off to reach the next.
 *
 * Args are kept only as per-arg HMAC-SHA256 digests under a key generated when this process starts
 * and never written or logged: a plain hash would let a short secret (a 4-digit PIN) be recovered
 * by enumeration. A new process means a new key, so nothing matches and the next trail does a full
 * setup.
 */
data class TrailheadMatchKey(
  val toolName: String,
  val codeVersion: String,
  val argDigests: Map<String, String>,
  val deviceId: String,
  val deviceSettings: String,
) {

  /**
   * Why [next] can't receive a hand-off recorded under this key, or null on an exact match. Names
   * differing args, never their values.
   */
  fun mismatchReason(next: TrailheadMatchKey): String? = when {
    next.toolName != toolName -> "a different trailhead tool ($toolName before, ${next.toolName} now)"
    next.codeVersion != codeVersion -> "the trailhead's code changed"
    next.deviceId != deviceId -> "a different device"
    next.deviceSettings != deviceSettings -> "device settings changed"
    next.argDigests != argDigests -> {
      val names = (argDigests.keys + next.argDigests.keys)
        .filter { argDigests[it] != next.argDigests[it] }
        .sorted()
      "args differed: ${names.joinToString()}"
    }
    else -> null
  }

  companion object {
    private val processKey: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun digestArgs(args: JsonObject): Map<String, String> =
      args.entries.associate { (name, value) -> name to hmac(canonicalJson(value)) }

    private fun hmac(text: String): String {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(processKey, "HmacSHA256"))
      return mac.doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Key-sorted JSON, so two equal objects digest the same whatever their key order. */
    private fun canonicalJson(element: JsonElement): String = when (element) {
      is JsonObject -> element.entries.sortedBy { it.key }
        .joinToString(",", "{", "}") { (k, v) -> "${JsonPrimitive(k)}:${canonicalJson(v)}" }
      is JsonArray -> element.joinToString(",", "[", "]") { canonicalJson(it) }
      else -> element.toString()
    }
  }
}

/** One stored hand-off: what `teardown` returned, and the key it was recorded under. */
data class StoredHandoff(
  val key: TrailheadMatchKey,
  val result: TeardownResult,
  val sessionId: SessionId,
)

/**
 * The hand-offs from one trail's `teardown` to the next trail's trailhead, one per device and
 * trailhead tool. Runner-process memory only, never written out.
 *
 * An entry lives for exactly one following trail. The next trailhead invocation of the same tool
 * on the device consumes it before any of its code runs, match or not. The next trail's teardown
 * then replaces every entry for each device it touched, and a trail with no capable trailhead
 * invocation drops every entry in the process, since the runner can't tell which device it
 * changed. So a setup is never handed past a trail that may have changed the device, and the store
 * holds at most one trail's worth per device. The cost of a drop is a full setup.
 */
object TrailheadHandoffStore {
  private val entries = ConcurrentHashMap<Slot, StoredHandoff>()

  private data class Slot(val deviceId: String, val toolName: String)

  fun put(handoff: StoredHandoff) {
    entries[Slot(handoff.key.deviceId, handoff.key.toolName)] = handoff
  }

  fun consume(deviceId: String, toolName: String): StoredHandoff? = entries.remove(Slot(deviceId, toolName))

  /** Drop every hand-off for [deviceId], before its trail's teardown stores the new ones. */
  fun dropDevice(deviceId: String) {
    entries.keys.removeIf { it.deviceId == deviceId }
  }

  /** Drop every hand-off: a trail ran that no trailhead teardown followed. */
  fun dropAll() = entries.clear()

  /**
   * Whether the trail about to run on [deviceId] will receive a reusable setup: the last trail
   * there handed one on for a tool in [trailItems]' trailhead. Runners check this before their
   * usual pre-trail force-stop of the app, which would discard that setup. A trail whose trailhead
   * names none of those tools keeps its clean start.
   */
  fun holdsReusableFor(deviceId: TrailblazeDeviceId, trailItems: List<TrailYamlItem>): Boolean {
    val toolNames = trailheadToolNames(trailItems)
    val device = keyOf(deviceId)
    return toolNames.any { entries[Slot(device, it)]?.result?.reusable == true }
  }

  /**
   * Whether [deviceId] holds any reusable setup. For a pre-trail force-stop that runs before the
   * trail is read; the runner's own check, [holdsReusableFor], still decides once it is.
   */
  fun holdsReusable(deviceId: TrailblazeDeviceId): Boolean {
    val device = keyOf(deviceId)
    return entries.any { (slot, handoff) -> slot.deviceId == device && handoff.result.reusable }
  }

  /** The tools a trail's trailhead steps name, top-level and lowered alike. */
  internal fun trailheadToolNames(trailItems: List<TrailYamlItem>): Set<String> = trailItems.flatMap { item ->
    when (item) {
      is TrailYamlItem.TrailheadTrailItem -> item.trailhead.tools.orEmpty().map { it.name }
      is TrailYamlItem.PromptsTrailItem -> item.promptSteps
        .filter { it is DirectionStep && it.isTrailhead }
        .flatMap { step -> step.recording?.tools.orEmpty().map { it.name } }
      else -> emptyList()
    }
  }.toSet()

  internal fun keyOf(deviceId: TrailblazeDeviceId): String =
    "${deviceId.trailblazeDevicePlatform.name}:${deviceId.instanceId}"

  /** Test seam. */
  internal fun clear() = entries.clear()
}
