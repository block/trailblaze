package xyz.block.trailblaze.host

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.agent.trail.toJsonArgs
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailAdapter

/**
 * `trailblaze check` gate: fails a trail whose **resolved** recording leg for a device carries
 * selectors in a dialect that device's **resolved** driver cannot match.
 *
 * ## The failure this catches
 *
 * `TrailblazeNodeSelectorResolver.matchesDriverDetail` dispatches on the node-detail type the
 * driver produced, not on the running driver: `DriverNodeMatch.AndroidMaestro` matches only
 * `DriverNodeDetail.AndroidMaestro`, and `DriverNodeMatch.AndroidAccessibility` only
 * `DriverNodeDetail.AndroidAccessibility`. Android has no cross-dialect bridge (the sole bridge in
 * the resolver is iOS Maestro → AXe, `matchesIosMaestroAgainstAxe`; see [NATIVE_DIALECT_DRIVERS]).
 * So an `androidMaestro:` selector under `ANDROID_ONDEVICE_ACCESSIBILITY` resolves to `NoMatch`
 * every time, regardless of its text — and `assertVisibleBySelector` / `tapOnElementBySelector`
 * have no fallback, so the step hard-fails.
 *
 * The shape that produces it: a trail whose android devices ran one driver shared a single
 * `android:` recording leg. Migrating ONE of them (say `android-phone`) to the accessibility driver
 * obligates splitting that leg, because the other device still resolves the shared leg's now-wrong
 * dialect. Miss the split and the *unmigrated* device breaks — on steps the migration never touched.
 *
 * ## Leg-aware, deliberately
 *
 * This lint used to work at TRAIL + PLATFORM granularity: any native driver pin on a platform plus
 * any Maestro-dialect selector of that platform anywhere in the trail. That cannot distinguish a
 * correctly-split mixed-driver trail (phone leg accessibility, tablet leg Maestro — every device
 * matches its own dialect) from a broken one, so it flagged both. Measured over the internal
 * corpus, every one of its findings was that false positive, which is why it could only ever be a
 * warning.
 *
 * It now resolves per device, reusing the executor's own primitives — [UnifiedTrailAdapter.resolveDriver]
 * and [UnifiedTrailAdapter.describeRecordingResolution], the same closest-wins walk
 * `lowerToTrailItems` performs — so a finding means the runtime really will hand that leg to that
 * driver. That precision is what lets it be fatal rather than advisory.
 *
 * Candidate device identities are the `config.devices:` keys UNION every declared recording-leg
 * key, so a trail pinning `android:` while keying legs `android-phone:` is examined too. A
 * candidate whose driver doesn't resolve from its own chain is skipped — nothing is statically
 * determinable about it.
 *
 * A consequence worth naming: a leg key stays a candidate even after the device it was recorded for
 * stops being scheduled. A stale `android-tablet:` leg under a live broad `android:` pin still
 * resolves a driver through the chain and can produce a finding on content nothing currently runs.
 * That is fail-closed on purpose — dead-but-broken content is worth surfacing, and the alternative
 * (requiring a driver pin at matching specificity) would drop genuine findings on trails that pin
 * broadly. If it ever becomes noise, downgrading leg-only findings to advisory is the smaller
 * change; deleting the dead leg is usually the right fix.
 *
 * ## Scope: one direction, one pair
 *
 * This gate is specifically `androidMaestro:` reached by `ANDROID_ONDEVICE_ACCESSIBILITY`. The
 * inverse — an `androidAccessibility:` selector on `ANDROID_ONDEVICE_INSTRUMENTATION` — is NOT
 * the same failure and is deliberately not gated here: the instrumentation agent doesn't resolve
 * nodeSelectors natively at all (every `executeNodeSelector*` on the base `MaestroTrailblazeAgent`
 * returns null), so those tools lower to Maestro via `lowerToMaestroSelector` and match against
 * the live UiAutomator hierarchy. That lowering succeeds for any selector carrying a `textRegex`
 * or `resourceIdRegex`; it fails loudly (a thrown `IllegalStateException` naming the fix) only for
 * a selector whose predicates are all driver-only fields. Neither outcome is the silent
 * every-run `NoMatch` this gate exists to catch, and the loud one already reports itself.
 *
 * ## Known false negative: multi-segment device identities
 *
 * Candidates are synthesized from declared keys, so the classifier list is only as good as the
 * key. Providers emit two-part identities: for the hyphen-joined platform families
 * (`[android, phone]`, `[ios, ipad]`) splitting the key reproduces the real list exactly. It does
 * NOT reproduce an identity that pairs a platform with an unrelated provider segment — e.g. the
 * `[android, revyl-cloud]` a cloud-device session reports (`TrailblazeHostYamlRunner`). Such a
 * device resolves its driver from an `android:` pin and its leg from a `revyl-cloud:` key, whereas
 * the synthetic `revyl-cloud` candidate has no `android` in its chain (so no driver resolves and it
 * is skipped) and the synthetic `android` candidate never sees the `revyl-cloud` leg. That pairing
 * goes unreported.
 *
 * Closing it would mean synthesizing composite identities, and there is no static signal that
 * separates a plausible composite (a platform plus a provider segment) from an impossible one (a
 * platform-family key plus a different hardware-family key — no such device exists). Synthesizing
 * both re-introduces exactly the false positives that kept the old lint advisory, on a gate whose
 * license to be fatal is that it has none. Under-reporting is the safe direction: the miss still
 * fails loudly at run time, which is the failure this gate pre-empts rather than masks. Revisit if
 * a device-identity registry ever makes composites decidable.
 */
object SelectorDialectLint {

  /** Env kill-switch: `1`/`true` (case-insensitive) skips the gate entirely. */
  const val DISABLE_ENV_VAR: String = "TRAILBLAZE_DISABLE_SELECTOR_DIALECT_GATE"

  /** Serialized Maestro-dialect selector slot key → the platform it belongs to. */
  private val MAESTRO_DIALECT_KEY_PLATFORM: Map<String, TrailblazeDevicePlatform> = mapOf(
    "androidMaestro" to TrailblazeDevicePlatform.ANDROID,
    "iosMaestro" to TrailblazeDevicePlatform.IOS,
  )

  /**
   * Drivers that CANNOT match a Maestro-dialect selector of their own platform at all.
   *
   * `IOS_AXE` is deliberately NOT here: the resolver has an explicit cross-dialect bridge
   * (`DriverNodeMatch.IosMaestro` vs `DriverNodeDetail.IosAxe` → `matchesIosMaestroAgainstAxe`)
   * that keeps `iosMaestro:` selectors resolving under the AXe driver, failing closed only on
   * `focused`/`selected` and selectors with no bridgeable field. Android has no such bridge, so
   * `androidMaestro:` under the accessibility driver is the genuinely unmatchable pair.
   */
  private val NATIVE_DIALECT_DRIVERS: Set<TrailblazeDriverType> = setOf(
    TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
  )

  /**
   * Tools whose dispatch survives a dialect it can't resolve natively, so a wrong-dialect selector
   * there is NOT a runtime failure and must not fail the build.
   *
   * `assertNotVisibleBySelector` is the only one. `AccessibilityTrailblazeAgent`'s override returns
   * null the moment the selector carries a non-accessibility branch, and
   * `AssertNotVisibleBySelectorTrailblazeTool` then falls back to Maestro lowering, which matches
   * against the live UI. That guard exists because a no-match on a NOT-visible assertion would
   * falsely *pass* — the opposite of the visible/tap case, where a no-match is already a failure
   * and so needs no fallback.
   */
  private val SAFE_FALLBACK_TOOL_NAMES: Set<String> = setOf("assertNotVisibleBySelector")

  private const val MAX_EXAMPLES = 3

  /** One unmatchable selector: this device resolves this leg, whose dialect its driver can't match. */
  data class Occurrence(
    /** The candidate device identity whose chain resolved both the driver and the leg. */
    val deviceClassifier: String,
    /** The driver that device resolved to, as written in `config.devices:`. */
    val driverName: String,
    /** 0-based index into `trail:`, or `null` for the trailhead. */
    val stepIndex: Int?,
    /** The `recording:` classifier slot that WON for this device (`android`, `android-tablet`, …). */
    val resolvedClassifier: String,
    val toolName: String,
    /** The unmatchable selector slot key (`androidMaestro` / `iosMaestro`). */
    val dialectKey: String,
    /** Compact rendering of the selector's own fields (e.g. `textRegex: Checkout`). */
    val selectorSummary: String,
  )

  /** One finding per offending trail. */
  data class Finding(
    val trailRelPath: String,
    val occurrences: List<Occurrence>,
  ) {
    val selectorCount: Int get() = occurrences.size

    /** Affected device identity → the driver it resolved, for the warning header. */
    val affectedDevices: Map<String, String>
      get() = occurrences.associate { it.deviceClassifier to it.driverName }

    val examples: List<Occurrence> get() = occurrences.take(MAX_EXAMPLES)
  }

  /**
   * PURE. Lint one parsed unified trail. Returns a [Finding] when some device resolves a recording
   * leg carrying a dialect its resolved driver cannot match; null otherwise.
   */
  fun lint(trailRelPath: String, trail: UnifiedTrail): Finding? {
    val occurrences = mutableListOf<Occurrence>()
    for (device in candidateDeviceIdentities(trail)) {
      val classifiers = device.split("-").filter { it.isNotBlank() }.map { TrailblazeDeviceClassifier(it) }
      if (classifiers.isEmpty()) continue
      // Resolve BOTH the driver and each leg through the executor's own closest-wins walk, so a
      // finding reflects what the runtime will actually pair rather than a second opinion about it.
      val driverName = UnifiedTrailAdapter.resolveDriver(trail.config, classifiers) ?: continue
      val driver = TrailblazeDriverType.fromString(driverName) ?: continue
      if (driver !in NATIVE_DIALECT_DRIVERS) continue
      UnifiedTrailAdapter.describeRecordingResolution(trail, classifiers).steps.forEach { stepResolution ->
        val legKey = stepResolution.resolvedClassifier ?: return@forEach
        toolsFor(trail, stepResolution.stepIndex, legKey).forEach { tool ->
          if (tool.name in SAFE_FALLBACK_TOOL_NAMES) return@forEach
          collectMaestroDialectSelectors(tool.toJsonArgs(), driver.platform).forEach { (key, selector) ->
            occurrences.add(
              Occurrence(
                deviceClassifier = device,
                driverName = driverName,
                stepIndex = stepResolution.stepIndex,
                resolvedClassifier = legKey,
                toolName = tool.name,
                dialectKey = key,
                selectorSummary = summarize(selector),
              ),
            )
          }
        }
      }
    }
    return if (occurrences.isEmpty()) null else Finding(trailRelPath, occurrences)
  }

  /**
   * Every device identity worth resolving: the declared `config.devices:` keys plus every recording
   * leg key declared anywhere in the trail. The union matters in both directions — a trail can pin
   * `android:` while keying legs `android-phone:`, or pin `android-phone:` while sharing an
   * `android:` leg. Sorted for deterministic finding order.
   */
  private fun candidateDeviceIdentities(trail: UnifiedTrail): List<String> = buildSet {
    trail.config.devices?.keys?.let { addAll(it) }
    trail.trailhead?.recordings?.keys?.let { addAll(it) }
    trail.trail.forEach { addAll(it.recordings.keys) }
  }.sorted()

  /** The recorded tools of the winning leg — trailhead when [stepIndex] is null, else `trail:[i]`. */
  private fun toolsFor(
    trail: UnifiedTrail,
    stepIndex: Int?,
    legKey: String,
  ): List<TrailblazeToolYamlWrapper> = if (stepIndex == null) {
    trail.trailhead?.recordings?.get(legKey)
  } else {
    trail.trail.getOrNull(stepIndex)?.recordings?.get(legKey)
  }.orEmpty()

  /** Render the findings as a human-readable failure block — one block per trail. */
  fun renderFailures(findings: List<Finding>): String = buildString {
    appendLine("── selector-dialect gate (FATAL) ───────────────────────────────")
    appendLine(
      "${findings.size} trail(s) resolve a recording leg whose selector dialect the device's " +
        "driver cannot match. An androidMaestro: selector under ANDROID_ONDEVICE_ACCESSIBILITY " +
        "never matches — the resolver dispatches on the tree shape the driver produced, and " +
        "Android has no cross-dialect bridge — so these steps fail on every run. Fix: give the " +
        "device its own recording leg carrying androidAccessibility: selectors, instead of " +
        "sharing a leg whose dialect belongs to the other driver.",
    )
    findings.sortedBy { it.trailRelPath }.forEach { f ->
      appendLine(
        "  FAIL ${f.trailRelPath}: ${f.selectorCount} unmatchable selector(s); " +
          "device→driver ${f.affectedDevices}",
      )
      f.examples.forEach {
        val where = it.stepIndex?.let { i -> "step $i" } ?: "trailhead"
        appendLine(
          "        $where resolves leg '${it.resolvedClassifier}' for '${it.deviceClassifier}' — " +
            "${it.toolName} ${it.dialectKey}{${it.selectorSummary}}",
        )
      }
      if (f.selectorCount > f.examples.size) {
        appendLine("        … and ${f.selectorCount - f.examples.size} more")
      }
    }
  }

  /**
   * Walk the recorded call's args JSON and collect every nested object keyed by a Maestro-dialect
   * selector slot belonging to [platform]. Key-based (not tool-class-based) so any recordable tool
   * carrying a selector arg — including ones decoded as `OtherTrailblazeTool` — is covered, and a
   * dialect slot nested under a hierarchy/spatial relation (`containsChild:`, `below:`, …) counts
   * too.
   *
   * Recursing into nested tool-calls is deliberate, not incidental. A wrapper tool records
   * VERBATIM, inner calls and all — `block_runIf` keeps its `condition.tool` and `then:`/`else:`
   * entries in its own args as `{ <toolName>: <args> }` — and those inner selectors are dispatched
   * for real. A wrong dialect in a `condition.selector:` makes the conditional silently
   * always-false (`findMatches` dispatches on the tree shape the driver produced, so it never
   * matches), and a nested tap hard-fails whenever its branch runs. Both belong in the findings.
   *
   * The one exception is a subtree keyed by a [SAFE_FALLBACK_TOOL_NAMES] tool: nested dispatch
   * reaches the same agent override as a top-level call, so it falls back exactly the same way and
   * is no more a failure nested than it is at the top level. Skipping the whole subtree (rather
   * than only the top-level tool name) is what keeps the fatal gate free of false positives on
   * wrapper recordings.
   */
  private fun collectMaestroDialectSelectors(
    args: JsonElement,
    platform: TrailblazeDevicePlatform,
  ): List<Pair<String, JsonObject>> {
    val hits = mutableListOf<Pair<String, JsonObject>>()
    fun walk(element: JsonElement) {
      when (element) {
        is JsonObject -> element.forEach { (key, value) ->
          if (key in SAFE_FALLBACK_TOOL_NAMES) return@forEach
          if (value is JsonObject && MAESTRO_DIALECT_KEY_PLATFORM[key] == platform) hits.add(key to value)
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
