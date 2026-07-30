package xyz.block.trailblaze.host

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.agent.trail.toJsonArgs
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.unified.UnifiedTrail

/**
 * WARNING-only `trailblaze check` lint: flags a trail that carries recorded **Maestro-dialect**
 * selectors (`androidMaestro:` / `iosMaestro:` slots) while its `config.devices:` pins a **native**
 * driver for the same platform whose resolver has no Maestro-semantics bridge (today:
 * `ANDROID_ONDEVICE_ACCESSIBILITY`; see [NATIVE_DIALECT_DRIVERS] for why `IOS_AXE` is exempt).
 *
 * ## Why this exists
 *
 * A selector's dialect governs its matching semantics (see `TrailblazeNodeSelectorResolver`'s
 * `MatchDialect`): Maestro-authored shapes match case-INsensitively with Orchestra's lenient
 * regex options, while native shapes are case-sensitive strict regex. When a device config's
 * driver flips from an instrumentation/Maestro driver to a native one, the trail's recorded
 * Maestro-dialect selectors silently change semantics — `textRegex: All Items` stops matching a
 * node whose text is `All items` (the real triage cost behind PR #5064's
 * `ANDROID_ONDEVICE_INSTRUMENTATION` → `ANDROID_ONDEVICE_ACCESSIBILITY` flip). Nothing warned
 * when a trail crossed that boundary; this lint is that warning.
 *
 * ## Granularity — trail + platform, deliberately
 *
 * Which recording slot a device resolves is closest-wins over the device's *runtime* classifier
 * chain, which can include segments no static reader of the trail knows (irregular hardware
 * families fall back to broader platform slots through provider-emitted segments). Rather than
 * rebuild that resolution speculatively, the lint works at TRAIL + PLATFORM granularity: if ANY
 * `config.devices:` entry pins a native driver for a platform, EVERY Maestro-dialect selector of
 * that platform anywhere in the trail's recordings is counted — exactly the population at risk of
 * being resolved under the flipped semantics. A trail whose Android devices are all
 * Maestro/instrumentation-driven is never flagged, and a trail with no `devices:` pins at all is
 * skipped (nothing is statically determinable about its drivers).
 *
 * Non-fatal by design (shadow-then-promote): findings render as warnings and never change the
 * `check` exit code — see `CheckCommand.runSelectorDialectLintPhase`.
 */
object SelectorDialectLint {

  /** Serialized Maestro-dialect selector slot key → the platform it belongs to. */
  private val MAESTRO_DIALECT_KEY_PLATFORM: Map<String, TrailblazeDevicePlatform> = mapOf(
    "androidMaestro" to TrailblazeDevicePlatform.ANDROID,
    "iosMaestro" to TrailblazeDevicePlatform.IOS,
  )

  /**
   * Drivers that resolve Maestro-dialect selectors with native (case-sensitive, strict-regex)
   * semantics — i.e. where the recorded semantics actually flip and a warning is warranted.
   *
   * `IOS_AXE` is deliberately NOT here: `TrailblazeNodeSelectorResolver.matchesDriverDetail` has an
   * explicit cross-dialect bridge (`DriverNodeMatch.IosMaestro` vs `DriverNodeDetail.IosAxe` →
   * `matchesIosMaestroAgainstAxe`) that keeps `MatchDialect.MAESTRO` lenient/case-insensitive
   * semantics for `iosMaestro:` selectors under the AXe driver, failing closed only on
   * `focused`/`selected` and selectors with no bridgeable field. Android has no such bridge —
   * `DriverNodeMatch.AndroidMaestro` only matches `DriverNodeDetail.AndroidMaestro` — so
   * `androidMaestro:` selectors under the accessibility driver are the genuinely hazardous pair.
   */
  private val NATIVE_DIALECT_DRIVERS: Set<TrailblazeDriverType> = setOf(
    TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
  )

  private const val MAX_EXAMPLES = 3

  /** One Maestro-dialect selector occurrence, for the finding's example listing. */
  data class SelectorExample(
    /** Step index per [TrailTscValidator.forEachRecordedTool]: 0 is the trailhead, steps are 1-based. */
    val stepIndex: Int,
    /** The `recording:` classifier slot the selector sits in (`android`, `kiosk-t3`, …). */
    val classifier: String,
    val toolName: String,
    /** The offending selector slot key (`androidMaestro` / `iosMaestro`). */
    val dialectKey: String,
    /** Compact rendering of the selector's own fields (e.g. `textRegex: Checkout`). */
    val selectorSummary: String,
  )

  /** One warning per offending trail. */
  data class Finding(
    val trailRelPath: String,
    /**
     * The `config.devices:` entries (classifier → driver name) pinning a native driver on a
     * platform that also has Maestro-dialect selectors in this trail.
     */
    val nativeDevicePins: Map<String, String>,
    /** Total Maestro-dialect selector occurrences on the affected platform(s). */
    val selectorCount: Int,
    /** The first [MAX_EXAMPLES] occurrences. */
    val examples: List<SelectorExample>,
  )

  /**
   * PURE. Lint one parsed unified trail. Returns a [Finding] when the trail pins a native driver
   * for a platform AND carries at least one Maestro-dialect selector of that platform in its
   * recordings (trailhead + every step's every classifier slot); null otherwise.
   */
  fun lint(trailRelPath: String, trail: UnifiedTrail): Finding? {
    val pinnedDrivers = trail.config.devices.orEmpty().mapNotNull { (classifier, driverName) ->
      TrailblazeDriverType.fromString(driverName)?.let { driver -> Pin(classifier, driverName, driver) }
    }
    val nativePlatforms = pinnedDrivers
      .filter { it.driver in NATIVE_DIALECT_DRIVERS }
      .map { it.driver.platform }
      .toSet()
    if (nativePlatforms.isEmpty()) return null

    val occurrences = mutableListOf<SelectorExample>()
    TrailTscValidator.forEachRecordedTool(trail) { stepIndex, _, classifier, tool ->
      collectMaestroDialectSelectors(tool.toJsonArgs(), nativePlatforms).forEach { (key, selector) ->
        occurrences.add(
          SelectorExample(
            stepIndex = stepIndex,
            classifier = classifier,
            toolName = tool.name,
            dialectKey = key,
            selectorSummary = summarize(selector),
          ),
        )
      }
    }
    if (occurrences.isEmpty()) return null

    val affectedPlatforms = occurrences.map { MAESTRO_DIALECT_KEY_PLATFORM.getValue(it.dialectKey) }.toSet()
    val nativePins = pinnedDrivers
      .filter { it.driver in NATIVE_DIALECT_DRIVERS && it.driver.platform in affectedPlatforms }
      .associate { it.classifier to it.driverName }
    return Finding(
      trailRelPath = trailRelPath,
      nativeDevicePins = nativePins,
      selectorCount = occurrences.size,
      examples = occurrences.take(MAX_EXAMPLES),
    )
  }

  /** Render the findings as a human-readable warning block — one warning line per trail. */
  fun renderWarnings(findings: List<Finding>): String = buildString {
    appendLine("── selector-dialect lint (WARNING, non-fatal) ──────────────────")
    appendLine(
      "${findings.size} trail(s) carry Maestro-dialect selectors (androidMaestro) while " +
        "pinning a native driver for the same platform. These selectors were recorded under " +
        "Maestro's lenient matching (case-insensitive); a native driver resolves them " +
        "case-sensitively with strict regex, so they may silently stop matching. " +
        "Re-record on the native driver or rewrite the selector to its native slot " +
        "(androidAccessibility).",
    )
    findings.sortedBy { it.trailRelPath }.forEach { f ->
      val examples = f.examples.joinToString("; ") {
        "step ${it.stepIndex} [${it.classifier}] ${it.toolName} ${it.dialectKey}{${it.selectorSummary}}"
      }
      appendLine(
        "  WARNING ${f.trailRelPath}: ${f.selectorCount} Maestro-dialect selector(s) vs native driver " +
          "pin(s) ${f.nativeDevicePins} — e.g. $examples",
      )
    }
  }

  private data class Pin(val classifier: String, val driverName: String, val driver: TrailblazeDriverType)

  /**
   * Walk the recorded call's args JSON and collect every nested object keyed by a Maestro-dialect
   * selector slot whose platform is in [platforms]. Key-based (not tool-class-based) so any
   * recordable tool carrying a selector arg — including ones decoded as `OtherTrailblazeTool` —
   * is covered, and a dialect slot nested under a hierarchy/spatial relation (`containsChild:`,
   * `below:`, …) is counted too.
   */
  private fun collectMaestroDialectSelectors(
    args: JsonElement,
    platforms: Set<TrailblazeDevicePlatform>,
  ): List<Pair<String, JsonObject>> {
    val hits = mutableListOf<Pair<String, JsonObject>>()
    fun walk(element: JsonElement) {
      when (element) {
        is JsonObject -> element.forEach { (key, value) ->
          val platform = MAESTRO_DIALECT_KEY_PLATFORM[key]
          if (value is JsonObject && platform != null && platform in platforms) hits.add(key to value)
          walk(value)
        }
        is JsonArray -> element.forEach { walk(it) }
        else -> Unit
      }
    }
    walk(args)
    return hits
  }

  private fun summarize(selector: JsonObject): String =
    selector.entries
      .filter { it.value is JsonPrimitive }
      .take(2)
      .joinToString(", ") { (key, value) -> "$key: ${(value as JsonPrimitive).content.take(40)}" }
      .ifEmpty { selector.keys.take(2).joinToString(", ") }
}
