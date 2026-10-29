package xyz.block.trailblaze.devices

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.util.Console

/**
 * A scripted tool's `trailblaze/requiredClassifiers` declaration: the device-classifier segments a
 * session's device must all carry for the tool to register (`[rig]` registers on
 * `android-phone-rig`, not on `android-phone`).
 *
 * A type rather than a `List<String>` for the same reason as
 * [xyz.block.trailblaze.toolcalls.DeclaredSensitiveArgs]: the declaration can arrive from a source
 * that skipped descriptor validation (an MCP server's `_meta`, a bundle's own `spec._meta`), so it
 * has a **present but unreadable** state. Degrading that to "no restriction" would register a tool
 * meant for specially equipped devices on every device, so [Unreadable] is never satisfied by a
 * known device.
 *
 * Shared by both scripted runtimes' `_meta` parsers so the two can't disagree on which devices a
 * tool loads on.
 */
sealed interface RequiredDeviceClassifiers {

  /**
   * Whether a device with [classifiers] may register the tool. Segments match case-insensitively.
   *
   * `null` [classifiers] means the caller has no device to judge — a static report or a tool listing
   * with nothing connected — and returns `true`, so those surfaces still show a tool that may apply.
   * An empty list is a known device with no classifiers, and satisfies only [None].
   */
  fun isSatisfiedBy(classifiers: List<TrailblazeDeviceClassifier>?): Boolean

  /** No declaration — the tool registers on any device. */
  data object None : RequiredDeviceClassifiers {
    override fun isSatisfiedBy(classifiers: List<TrailblazeDeviceClassifier>?): Boolean = true
  }

  /** A well-formed declaration. The device must carry every one of [segments]. */
  data class Segments(val segments: List<String>) : RequiredDeviceClassifiers {
    override fun isSatisfiedBy(classifiers: List<TrailblazeDeviceClassifier>?): Boolean {
      if (classifiers == null) return true
      val present = classifiers.mapTo(HashSet()) { it.classifier.lowercase() }
      return segments.all { it.lowercase() in present }
    }
  }

  /** The key was present but not a list of strings. Never satisfied by a known device. */
  data object Unreadable : RequiredDeviceClassifiers {
    override fun isSatisfiedBy(classifiers: List<TrailblazeDeviceClassifier>?): Boolean = classifiers == null
  }

  companion object {
    /** The namespaced `_meta` key every authoring surface lowers the declaration to. */
    const val META_KEY: String = "trailblaze/requiredClassifiers"

    /** Absent or empty → [None]; a list of strings → [Segments]; anything else → [Unreadable]. */
    fun fromMeta(meta: JsonObject?): RequiredDeviceClassifiers {
      val raw = meta?.get(META_KEY) ?: return None
      val array = raw as? JsonArray ?: return unreadable(raw)
      if (array.isEmpty()) return None
      val segments = array.map { element ->
        val primitive = element as? JsonPrimitive ?: return unreadable(raw)
        if (!primitive.isString) return unreadable(raw)
        primitive.content
      }
      return Segments(segments)
    }

    /** Logged once at the read, so a tool that never registers points at its declaration. */
    private fun unreadable(raw: JsonElement): RequiredDeviceClassifiers {
      Console.error(
        "[required-classifiers] `_meta.$META_KEY` must be a list of classifier segments (e.g. " +
          "`[rig]`) but is a ${raw::class.simpleName}. The tool will not register on any device " +
          "until the declaration is fixed.",
      )
      return Unreadable
    }
  }
}
