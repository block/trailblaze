package xyz.block.trailblaze.ui.recording

import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.hasSemanticIdentifier
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Monotonic per-recording-session id used as a stable Compose key for action cards and
 * as the replay-status map key. Was [WebGesture.timestamp] (a wall-clock epoch millis)
 * but collided on rapid same-millisecond gestures — two taps within 1ms produced
 * duplicate keys that cross-wired LazyColumn state. Renaming to `id` and sourcing it
 * from a per-session counter (`WebDevicesPage.nextGestureId`) guarantees uniqueness.
 *
 * Typealiased to [Long] so the underlying primitive doesn't change and existing
 * serialized trails / curl smoke tests continue to round-trip.
 */
typealias GestureId = Long

/**
 * Lightweight gesture representation used by the web `/devices` viewer's client-side
 * recording. Mirrors the gestures [InteractiveDeviceComposable] surfaces (tap, long press,
 * swipe, text input, key press) without requiring construction of a typed
 * [xyz.block.trailblaze.toolcalls.TrailblazeTool] — those live in `jvmAndAndroid` and reach
 * into Maestro / Koog dependencies that don't exist on wasmJs.
 *
 * Each gesture emits a YAML block that is **wire-compatible** with what the JVM recorder
 * produces (same `tapOnPoint:`, `inputText:`, `pressKey:`, `swipeWithRelativeCoordinates:`
 * shapes) — so the daemon's `runYaml` path accepts these on replay without a special branch.
 *
 * When the basic tool classes eventually move to commonMain (Phase 3e+), this can be
 * migrated to construct real `TrailblazeTool` instances and produce `RecordedInteraction`s
 * directly, sharing fully with the desktop recorder.
 */
sealed interface WebGesture {

  /**
   * Unique, monotonically-increasing per-session id. Used as the LazyColumn item key and as
   * the replay-status map key. Not a wall-clock — sourced from a per-session counter at
   * emit time so two gestures emitted in the same millisecond still collide-free.
   */
  val id: GestureId

  /** Human-readable label for the action card header (e.g. "Tap", "Swipe", "inputText"). */
  val displayLabel: String

  /** Single-step trail YAML block for this gesture (e.g. `- tapOnPoint:\n    x: 100\n    y: 200`). */
  fun toYaml(): String

  data class Tap(
    val x: Int,
    val y: Int,
    val longPress: Boolean = false,
    override val id: GestureId,
  ) : WebGesture {
    override val displayLabel: String get() = if (longPress) "Long press" else "Tap"
    override fun toYaml(): String = buildString {
      appendLine("- tapOnPoint:")
      appendLine("    x: $x")
      append("    y: $y")
      if (longPress) {
        appendLine()
        append("    longPress: true")
      }
    }
  }

  /**
   * Tap recorded with a generated [TrailblazeNodeSelector] — emits `tapOnElementBySelector`
   * YAML so replay resolves the same UI element regardless of layout shifts. Produced by
   * [WebGestureSelectorTapBuilder.buildOrNull] which runs
   * [xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator.resolveFromTap] against the
   * tree captured atomically with the screenshot.
   *
   * When [TrailblazeNodeSelectorGenerator.TapResolution.roundTripValid] is `false` (a child
   * element would intercept the tap at replay), the builder returns `null` and the recorder
   * falls back to [Tap] (raw `tapOnPoint`) so the recorded selector doesn't drift from what
   * the user actually tapped. Same fallback semantics as the desktop recorder.
   *
   * [nodeSelectorYaml] is pre-rendered (TrailblazeYaml-formatted) at construction time so
   * [toYaml] is a pure string operation — no kotlinx-serialization at YAML-emission time.
   */
  data class SelectorTap(
    /** Original tap coordinates in device space — preserved for diagnostics and as a
     *  fallback hint if selector resolution ever drifts. Not emitted in YAML. */
    val deviceX: Int,
    val deviceY: Int,
    val longPress: Boolean,
    /**
     * Pre-rendered YAML for the `nodeSelector:` block. Indented zero — the [toYaml]
     * wrapper indents this under the outer envelope.
     */
    val nodeSelectorYaml: String,
    /** Short human description of what the selector matches, e.g. `text="Coffee"`. Used
     *  by the action-card display label so the user can read "Tap Coffee" instead of
     *  "Tap element". */
    val description: String,
    override val id: GestureId,
  ) : WebGesture {
    override val displayLabel: String get() = buildString {
      append(if (longPress) "Long press" else "Tap")
      if (description.isNotEmpty()) {
        append(" ")
        append(description)
      }
    }

    override fun toYaml(): String = buildString {
      appendLine("- tapOnElementBySelector:")
      appendLine("    nodeSelector:")
      // Re-indent the selector YAML to sit four spaces under `nodeSelector:`.
      nodeSelectorYaml.lines().forEach { line ->
        if (line.isBlank()) {
          appendLine()
        } else {
          append("      ")
          appendLine(line)
        }
      }
      if (longPress) {
        append("    longPress: true")
      } else {
        // Strip the trailing newline `appendLine` left so we match the other variants'
        // "ends with last meaningful line, no trailing newline" convention.
        if (last() == '\n') deleteAt(length - 1)
      }
    }
  }

  /**
   * Swipe coordinates are device-space. [toYaml] converts to the percent-based
   * `swipeWithRelativeCoordinates` shape that the typed tool produces, scaling against
   * [deviceWidth] / [deviceHeight] so replay is resolution-independent.
   *
   * Percentages are **rounded**, not truncated — a 3-pixel offset on a 720-wide device
   * was rounding to 0% before (real value 0.4%). [roundToInt] matches the desktop's
   * `SwipeWithRelativeCoordinatesTool` quantization at integer-percent precision.
   */
  data class Swipe(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Long,
    val deviceWidth: Int,
    val deviceHeight: Int,
    override val id: GestureId,
  ) : WebGesture {
    override val displayLabel: String = "Swipe"
    override fun toYaml(): String {
      // Guard against zero/negative dimensions — `ConnectToDeviceResponse` doesn't
      // guarantee a positive width/height (e.g., a stream that reports "unknown" as 0).
      // Without the guard we'd divide-by-zero and emit `NaN%` / `Infinity%` into the
      // trail YAML, which the daemon parser then chokes on. Falling back to 0% on bad
      // dimensions preserves the gesture's place in the recording — the user can edit
      // the YAML after — without producing unparseable output.
      val safeW = if (deviceWidth > 0) deviceWidth else 1
      val safeH = if (deviceHeight > 0) deviceHeight else 1
      val sxPct = (startX.toFloat() / safeW * 100).roundToInt().coerceIn(0, 100)
      val syPct = (startY.toFloat() / safeH * 100).roundToInt().coerceIn(0, 100)
      val exPct = (endX.toFloat() / safeW * 100).roundToInt().coerceIn(0, 100)
      val eyPct = (endY.toFloat() / safeH * 100).roundToInt().coerceIn(0, 100)
      return buildString {
        appendLine("- swipeWithRelativeCoordinates:")
        appendLine("    startRelative: \"$sxPct%, $syPct%\"")
        appendLine("    endRelative: \"$exPct%, $eyPct%\"")
        append("    durationMs: $durationMs")
      }
    }
  }

  data class InputText(
    val text: String,
    override val id: GestureId,
  ) : WebGesture {
    override val displayLabel: String = "Input text"
    override fun toYaml(): String = buildString {
      appendLine("- inputText:")
      // Quote the text — handles spaces, special chars. Escape sequence covers backslash,
      // double-quote, and control chars (\n \t \r) which would otherwise emit literally
      // inside the quoted YAML string and break the daemon's parser. The same failure mode
      // as the Custom envelope bug we already shipped a fix for — server accepts the
      // malformed trail and runs zero tools, with no client-side error to surface.
      val escaped = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
      append("    text: \"$escaped\"")
    }
  }

  data class PressKey(
    val key: String,
    override val id: GestureId,
  ) : WebGesture {
    override val displayLabel: String get() = "Press $key"
    override fun toYaml(): String = buildString {
      appendLine("- pressKey:")
      append("    keyCode: ${key.uppercase()}")
    }
  }

  /**
   * Catch-all for tool-palette-inserted steps. The dialog's `buildSingleToolYaml` produces
   * a bare `toolName:\n  field: value` block — a map, not a list item — so this variant
   * normalizes it into the same `- toolName:\n    field: value` list-item shape the
   * gesture-emitting variants produce. Without normalization, splicing into the `- tools:`
   * envelope would generate malformed YAML the daemon's `runYaml` parser silently rejects
   * (the bug Sam hit when clicking "Run on Device" on iOS — server returned success on the
   * empty trail, but no tool actually ran).
   *
   * The transform handles three input shapes safely:
   * - Multi-line bare map (`toolName:\n  field: value`) → list-item form
   * - Single-line bare map (`toolName: {}`) → list-item form
   * - Pre-formatted list-item (`- toolName: ...`) → passed through unchanged (idempotent)
   *
   * Idempotency prevents the double-dash bug if a future caller passes already-listified
   * YAML — without that guard, `- ` would be prepended a second time and the outer
   * `toTrailYaml` envelope would produce invalid `- - toolName:` nested-list YAML.
   */
  data class Custom(
    val toolName: String,
    val yaml: String,
    override val id: GestureId,
  ) : WebGesture {
    override val displayLabel: String get() = toolName
    override fun toYaml(): String {
      val trimmed = yaml.trimEnd()
      if (trimmed.isBlank()) return ""
      // Idempotent: if the input already starts with `- ` (e.g. a future caller pre-formats)
      // pass it through unchanged. Without this guard we'd produce `- - toolName:`.
      if (trimmed.trimStart().startsWith("- ")) return trimmed
      val lines = trimmed.lines()
      return buildString {
        append("- ").append(lines.first())
        for (line in lines.drop(1)) {
          appendLine()
          if (line.isBlank()) append(line) else append("  ").append(line)
        }
      }
    }
  }
}

/**
 * Builder for [WebGesture.SelectorTap]. Runs the (commonMain) selector generator over the
 * tap's `(tree, x, y)` and returns either a [WebGesture.SelectorTap] with a stable selector
 * or `null` when the tree is absent / no node was hit / the generated selector fails the
 * round-trip check. Caller falls back to [WebGesture.Tap] (raw `tapOnPoint`) on null.
 *
 * Mirrors the desktop pipeline's logic:
 *   [InteractionEventBuffer.onTap] →
 *   [MaestroInteractionToolFactory.createSelectorTapOrPoint] →
 *   [TrailblazeNodeSelectorGenerator.resolveFromTap]
 * — but without depending on Maestro/Koog so the codepath stays wasmJs-friendly.
 */
object WebGestureSelectorTapBuilder {

  // Plain JSON used purely as an intermediate format for the selector → JsonElement →
  // TrailblazeYaml.jsonToYaml chain. `encodeDefaults = false` keeps the YAML lean (no null
  // fields ride along for driver variants we didn't populate); `explicitNulls = false`
  // matches what TrailblazeYaml expects.
  private val selectorJson: Json = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = true
  }

  /**
   * Compute a [WebGesture.SelectorTap] for the given tap if a stable, round-trip-valid
   * selector exists. Returns `null` when:
   *  - [trailblazeTree] is null (driver doesn't expose one; tree fetch failed)
   *  - The tap point didn't hit any node
   *  - The generated selector's `roundTripValid == false` (a child element would intercept
   *    at replay time — recording it as an element-tap would playback wrong)
   *
   * Callers should fall back to [WebGesture.Tap] when this returns null so the recording
   * preserves the user's literal tap rather than encoding a selector that drifts.
   */
  fun buildOrNull(
    trailblazeTree: xyz.block.trailblaze.api.TrailblazeNode?,
    deviceX: Int,
    deviceY: Int,
    longPress: Boolean,
    id: GestureId,
  ): WebGesture.SelectorTap? {
    val tree = trailblazeTree ?: return null
    val resolution = xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
      .resolveFromTap(tree, deviceX, deviceY) ?: return null
    if (!resolution.roundTripValid) return null
    val selector: TrailblazeNodeSelector = resolution.selector
    // Reject selectors that have no *semantic* identifier (text, id, content-description,
    // hint, test-tag, etc.) and fall back to raw `tapOnPoint`. The selector generator's
    // 22-level cascade walks down to `classNameRegex`-only matches when nothing more
    // specific exists — e.g. tapping on the on-screen Android keyboard lands on the app's
    // ScrollView underneath (the keyboard runs in a separate AccessibilityWindow we don't
    // fetch), and a `classNameRegex: "android.widget.ScrollView"` selector wouldn't reliably
    // re-find what the user actually tapped at replay time. Reject those so the recording
    // stays faithful — the user can always replay-edit a `tapOnPoint` later if needed, but
    // a wrong selector is a silent footgun. Same principle as the round-trip check above.
    if (!selector.hasSemanticIdentifier()) return null
    val jsonElement: JsonElement = selectorJson.encodeToJsonElement(
      TrailblazeNodeSelector.serializer(),
      selector,
    )
    val yaml = TrailblazeYaml.jsonToYaml(jsonElement)
    // Human-readable description for the action card (e.g. `text="Coffee"`).
    val description = resolution.selector.description()
    return WebGesture.SelectorTap(
      deviceX = deviceX,
      deviceY = deviceY,
      longPress = longPress,
      nodeSelectorYaml = yaml,
      description = description,
      id = id,
    )
  }

}

/**
 * Wraps [gestures] in the canonical trail YAML envelope `- tools:\n    - <gesture>:\n        ...`
 * that the desktop's [xyz.block.trailblaze.recording.RecordingYamlCodec.interactionsToTrailYaml]
 * emits. Matching the format byte-for-byte means the daemon's `runYaml` parser path is the
 * same regardless of which surface produced the trail, and the file can be dropped into
 * `trails/` and run via `./trailblaze trail run` without edits.
 *
 * `- tools:` is the outer list item; each gesture's [toYaml] block is itself a `- tool:` list
 * item and gets indented 4 spaces under `tools:`.
 */
fun List<WebGesture>.toTrailYaml(): String {
  val gestures = this
  return buildString {
    appendLine("- tools:")
    for (gesture in gestures) {
      for (line in gesture.toYaml().lines()) {
        appendLine("    $line")
      }
    }
  }
}

/**
 * Wrap a single gesture in the same `- tools:` trail envelope. Used for the *Replay* button
 * on each action card — same wire format as a saved trail's single-tool slice.
 */
fun WebGesture.toSingleStepTrailYaml(): String = listOf(this).toTrailYaml()
