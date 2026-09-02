package xyz.block.trailblaze.host

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.agent.trail.toJsonArgs
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.commands.SwitchDeviceTrailblazeTool
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailAdapter
import xyz.block.trailblaze.yaml.unified.UnifiedTrailTargets

/**
 * `trailblaze check` gate: fails a trail whose **resolved** recording leg for a device carries
 * selectors in a dialect that device's **resolved** driver cannot match, or whose recorded
 * `switchDevice` names a device the trail cannot bind (see [lintHandovers]).
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
 * ## Scope: one same-platform pair, plus any cross-platform one
 *
 * Within a platform this gate is specifically `androidMaestro:` reached by
 * `ANDROID_ONDEVICE_ACCESSIBILITY`. Across platforms — only in a multi-device configuration leg,
 * where a `switchDevice` decides which surface a step drives — ANY dialect belonging to a platform
 * other than the active device's is flagged; see [DIALECT_KEY_PLATFORM]. The
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
   * Every selector dialect slot key → the platform whose driver produces that tree shape.
   *
   * Used only by the multi-device pass, to catch a dialect belonging to a DIFFERENT platform than
   * the active member's driver — a `web:` selector while the phone is active, or an
   * `androidAccessibility:` one while the browser is. No cross-platform bridge exists in the
   * resolver (the only bridge at all is iOS Maestro → AXe, within one platform), so such a selector
   * resolves to `NoMatch` on every run, exactly like the same-platform Maestro pair above.
   *
   * A single-device leg can't produce this pairing — the trail would have to declare a selector for
   * a platform it never runs on — so the gate spends the check where the mistake is reachable:
   * getting a `switchDevice` wrong, or recording a step against the surface that was active a
   * moment ago.
   *
   * `compose:` is deliberately absent. It describes a Compose semantics tree, which is not a
   * platform claim (the same dialect serves Android and Compose Multiplatform hosts), so mapping it
   * to one platform would mint false positives.
   */
  private val DIALECT_KEY_PLATFORM: Map<String, TrailblazeDevicePlatform> = mapOf(
    "androidAccessibility" to TrailblazeDevicePlatform.ANDROID,
    "androidView" to TrailblazeDevicePlatform.ANDROID,
    "androidMaestro" to TrailblazeDevicePlatform.ANDROID,
    "iosMaestro" to TrailblazeDevicePlatform.IOS,
    "iosAxe" to TrailblazeDevicePlatform.IOS,
    "web" to TrailblazeDevicePlatform.WEB,
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
    /**
     * True when the dialect belongs to a different PLATFORM than the device driving this step —
     * the multi-device mistake (a `web:` selector while the phone is active). False for the
     * same-platform Maestro-vs-accessibility pairing.
     */
    val crossPlatform: Boolean = false,
  )

  /**
   * One recorded `switchDevice` naming a device no configuration in the trail declares.
   *
   * Reported rather than logged because it is breakage in its own right — the run fails on that
   * step at the session-start guard — AND, when it sits in a configuration leg, because it blinds
   * the rest of the dialect pass: the static replay can no longer say which device is active, so
   * every selector after it goes unlinted. Silently dropping that coverage while the gate reports
   * green is the worse of the two failures.
   */
  data class UndeclaredHandover(
    /** The configuration this leg IS, or null when the leg is keyed by anything else. */
    val configurationName: String?,
    /** The recording leg key the `switchDevice` was recorded under. */
    val legKey: String,
    /** 0-based index into `trail:`, or `null` for the trailhead. */
    val stepIndex: Int?,
    /** The name the recorded `switchDevice` targets. */
    val target: String,
    /**
     * The member names available to this handover: the configuration's own members for a
     * configuration leg, else every member declared by any configuration in the trail. Empty when
     * the trail declares no configuration at all, in which case no `switchDevice` can ever resolve.
     */
    val declaredMembers: List<String>,
  )

  /** One finding per offending trail. */
  data class Finding(
    val trailRelPath: String,
    val occurrences: List<Occurrence>,
    val undeclaredHandovers: List<UndeclaredHandover> = emptyList(),
  ) {
    val selectorCount: Int get() = occurrences.size

    /** Affected device identity → the driver it resolved, for the warning header. */
    val affectedDevices: Map<String, String>
      get() = occurrences.associate { it.deviceClassifier to it.driverName }

    val examples: List<Occurrence> get() = occurrences.take(MAX_EXAMPLES)
  }

  /**
   * PURE. Lint one parsed unified trail. Returns a [Finding] when some device resolves a recording
   * leg carrying a dialect its resolved driver cannot match, or when a recorded `switchDevice`
   * names a device the trail cannot bind; null otherwise.
   */
  fun lint(trailRelPath: String, trail: UnifiedTrail): Finding? {
    val occurrences = mutableListOf<Occurrence>()
    val undeclaredHandovers = mutableListOf<UndeclaredHandover>()
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
    lintConfigurationLegs(trailRelPath, trail, occurrences)
    lintHandovers(trail, undeclaredHandovers)
    return if (occurrences.isEmpty() && undeclaredHandovers.isEmpty()) {
      null
    } else {
      Finding(trailRelPath, occurrences, undeclaredHandovers)
    }
  }

  /**
   * The multi-device counterpart of the per-device loop above: a leg keyed by a configuration
   * NAME is excluded from [candidateDeviceIdentities] (splitting the name on `-` would mint a
   * nonexistent device), but its tools still dispatch for real — against whichever member device
   * is ACTIVE at that point in the replay. So this pass replays each configuration leg
   * statically: it starts on the first declared member (the start device), flips on every
   * recorded `switchDevice` (the tool's `name:` arg), and lints each other tool against the
   * active member's driver — the member's own `driver:` pin, else the driver its `classifier:`
   * chain resolves from `config.devices:`. A member with neither is skipped, same as a candidate
   * whose driver doesn't resolve.
   */
  private fun lintConfigurationLegs(
    trailRelPath: String,
    trail: UnifiedTrail,
    occurrences: MutableList<Occurrence>,
  ) {
    val configurations = trail.config.devices.orEmpty().filterValues { it.devices != null }
    for ((configurationName, configuration) in configurations) {
      val members = configuration.devices.orEmpty()
      if (members.isEmpty()) continue
      val driverByMember: Map<String, String?> = members.mapValues { (_, member) ->
        member.driver?.name ?: member.classifier
          ?.split("-")?.filter { it.isNotBlank() }?.map { TrailblazeDeviceClassifier(it) }
          ?.takeIf { it.isNotEmpty() }
          ?.let { UnifiedTrailAdapter.resolveDriver(trail.config, it) }
      }
      // The cross-platform rule needs only the member's PLATFORM, which a bare `classifier:` gives
      // even when no driver is pinned anywhere — the same fold MultiDeviceConfigurationResolver
      // uses to stamp each companion. Without it a cast whose members carry classifiers and no
      // driver pins (the usual shape) would go entirely unlinted, since no driver resolves for
      // them from `config.devices:`.
      val platformByMember: Map<String, TrailblazeDevicePlatform?> = members.mapValues { (_, member) ->
        member.driver?.platform ?: member.classifier?.let { UnifiedTrailTargets.platformFor(it) }
      }
      var activeMember = members.keys.first()
      val legs: List<Pair<Int?, List<TrailblazeToolYamlWrapper>>> = buildList {
        trail.trailhead?.recordings?.get(configurationName)?.let { add(null to it) }
        trail.trail.forEachIndexed { index, step ->
          step.recordings[configurationName]?.let { add(index to it) }
        }
      }
      // Judge one selector-bearing scope against whichever member is active at this point in the
      // replay. Takes a scope rather than the whole tool so a wrapper that hands off inside a
      // branch can still have its CONDITION judged — see the call before `break@legLoop`.
      fun judgeScope(scope: JsonObject, toolName: String, stepIndex: Int?) {
        val driverName = driverByMember[activeMember]
        val driver = driverName?.let { TrailblazeDriverType.fromString(it) }
        val platform = driver?.platform ?: platformByMember[activeMember]
        // Two disjoint unmatchable pairings: any dialect belonging to another platform entirely
        // (the one a misplaced `switchDevice` produces in a heterogeneous cast), and a Maestro
        // dialect the member's own-platform driver can't read.
        val unmatchable =
          platform?.let { collectForeignPlatformSelectors(scope, it).map { hit -> hit to true } }
            .orEmpty() +
            if (driver != null && driver in NATIVE_DIALECT_DRIVERS) {
              collectMaestroDialectSelectors(scope, driver.platform).map { it to false }
            } else {
              emptyList()
            }
        unmatchable.forEach { (hit, crossPlatform) ->
          val (key, selector) = hit
          occurrences.add(
            Occurrence(
              deviceClassifier = "$configurationName/$activeMember",
              driverName = driverName ?: "$platform (no driver pin)",
              stepIndex = stepIndex,
              resolvedClassifier = configurationName,
              toolName = toolName,
              dialectKey = key,
              selectorSummary = summarize(selector),
              crossPlatform = crossPlatform,
            ),
          )
        }
      }

      legLoop@ for ((stepIndex, tools) in legs) {
        for (tool in tools) {
          if (tool.name == SwitchDeviceTrailblazeTool.ADVERTISED_TOOL_NAME) {
            val target = MultiDeviceHandoverGuard.readTargetName(tool)
            if (target != null && target in members.keys) {
              activeMember = target
              continue
            }
            // Either way the static replay no longer knows which device is active, so linting on
            // past this point would report the stale member's findings or miss real ones. A
            // literal undeclared name is real breakage, but [lintHandovers] reports it — that pass
            // sees every leg and every offending switch, where this one stops at the first.
            if (target == null) {
              // Unreadable or memory-interpolated: not statically knowable, so not a finding.
              Console.log(
                "[selector-dialect-lint] $trailRelPath: configuration `$configurationName` hands " +
                  "off at ${stepLabel(stepIndex)} to a name this gate can't resolve statically — " +
                  "abandoning the rest of this leg, the active device is no longer determinable.",
              )
            }
            break@legLoop
          }
          // A handover nested in a wrapper's branch dispatches for real, but whether the branch
          // runs is a runtime question — so past one, the active device is genuinely undecidable
          // and every later finding would be attributed to a guess. Stop, same as above.
          // `lintHandovers` still judges the nested name itself.
          //
          // Asked structurally, NOT off the target list: an interpolated nested name yields no
          // target by design, and continuing there would attribute the rest of the leg to a member
          // the branch may already have switched away from. What it IS asked against is the set of
          // bindable names, so a predicate switch that can only throw — caught, false verdict, same
          // device — does not cost the rest of the leg its coverage.
          if (MultiDeviceHandoverGuard.canMoveSession(tool, members.keys)) {
            // The wrapper's `condition:` — its predicate selector and any tool that predicate
            // invokes — is evaluated BEFORE either branch, on the device that is active right now.
            // That much stays decidable even though the rest of the leg does not, so judge it
            // rather than losing it to the stop below.
            // ...but only when the predicate cannot itself move the session. A conditional nested
            // in the predicate can switch devices and then select on the NEW member, and judging
            // that selector against the pre-predicate member would be a fatal false positive.
            (tool.toJsonArgs()["condition"] as? JsonObject)
              ?.takeUnless { MultiDeviceHandoverGuard.canMoveSession(it, members.keys) }
              ?.let { judgeScope(it, tool.name, stepIndex) }
            Console.log(
              "[selector-dialect-lint] $trailRelPath: configuration `$configurationName` hands off " +
                "inside a conditional at ${stepLabel(stepIndex)} — abandoning the rest of this leg, " +
                "the active device depends on which branch runs.",
            )
            break@legLoop
          }
          if (tool.name in SAFE_FALLBACK_TOOL_NAMES) continue
          judgeScope(tool.toJsonArgs(), tool.name, stepIndex)
        }
      }
    }
  }

  /**
   * The authoring-time half of [MultiDeviceHandoverGuard]: every recorded `switchDevice` whose
   * target names no device the trail could ever bind.
   *
   * The runtime guard answers the same question at session start, but only for the legs that one
   * session resolves and only once a session exists at all. This pass runs over the whole file, so
   * it covers the two shapes the guard structurally cannot reach:
   *
   * - A `switchDevice` in a leg NOT keyed by a configuration name — a classifier-keyed leg on a
   *   trail that also declares a configuration. The lowering resolves that leg for real, so the
   *   switch dispatches; a member name declared by a DIFFERENT configuration is the mistake this
   *   catches, which is why a non-configuration leg is checked against the union of every declared
   *   member rather than against nothing.
   * - A `switchDevice` in a trail declaring no configuration at all. No session binds a cast, so
   *   the guard never runs — but the switch can never resolve either, which makes it statically
   *   decidable with no false positives.
   *
   * Unlike the dialect replay in [lintConfigurationLegs], this reports EVERY offending switch
   * rather than stopping at the first. That pass has to stop — past an unresolvable handover it no
   * longer knows the active device — but a handover check needs no active device, and reporting one
   * defect per run would make fixing a mis-recorded cast a repeated check→fix cycle.
   *
   * A handover nested in a recorded conditional's branch counts — see
   * [MultiDeviceHandoverGuard.handoverTargets]. Whether the branch runs is a runtime question, but
   * whether the name can bind is not.
   *
   * A target [MultiDeviceHandoverGuard.readTargetName] can't read (absent, non-string, blank, or
   * memory-interpolated) is not a finding, matching the runtime guard exactly: its value isn't
   * knowable until the run produces it.
   */
  private fun lintHandovers(trail: UnifiedTrail, undeclaredHandovers: MutableList<UndeclaredHandover>) {
    val configurations = trail.config.devices.orEmpty().filterValues { it.devices != null }
    val everyDeclaredMember = configurations.values.flatMap { it.devices.orEmpty().keys }.distinct()
    val legs: List<Triple<Int?, String, List<TrailblazeToolYamlWrapper>>> = buildList {
      trail.trailhead?.recordings?.forEach { (legKey, tools) -> add(Triple(null, legKey, tools)) }
      trail.trail.forEachIndexed { index, step ->
        step.recordings.forEach { (legKey, tools) -> add(Triple(index, legKey, tools)) }
      }
    }
    legs.forEach { (stepIndex, legKey, tools) ->
      val configuration = configurations[legKey]
      val declaredMembers = if (configuration != null) {
        configuration.devices.orEmpty().keys.toList()
      } else {
        everyDeclaredMember
      }
      tools.forEach { tool ->
        MultiDeviceHandoverGuard.handoverTargets(tool).forEach { target ->
          if (target in declaredMembers) return@forEach
          undeclaredHandovers.add(
            UndeclaredHandover(
              configurationName = legKey.takeIf { configuration != null },
              legKey = legKey,
              stepIndex = stepIndex,
              target = target,
              declaredMembers = declaredMembers,
            ),
          )
        }
      }
    }
  }

  /**
   * Every device identity worth resolving: the declared `config.devices:` keys plus every recording
   * leg key declared anywhere in the trail. The union matters in both directions — a trail can pin
   * `android:` while keying legs `android-phone:`, or pin `android-phone:` while sharing an
   * `android:` leg. Sorted for deterministic finding order.
   *
   * Multi-device configuration NAMES are not device identities — splitting one on `-` would mint a
   * nonexistent device — so they're excluded wherever they appear (a configuration entry's key, or
   * a leg keyed by the configuration name). The configuration's member devices contribute their
   * `classifier:` values here for member-keyed legs, and the configuration-name legs themselves
   * are covered by the switch-aware [lintConfigurationLegs] pass.
   */
  private fun candidateDeviceIdentities(trail: UnifiedTrail): List<String> = buildSet {
    val configurationNames = trail.config.multiDeviceConfigurationNames
    trail.config.devices?.forEach { (key, definition) ->
      val members = definition.devices
      if (members == null) {
        add(key)
      } else {
        members.values.forEach { member -> member.classifier?.let { add(it) } }
      }
    }
    trail.trailhead?.recordings?.keys?.let { keys -> addAll(keys - configurationNames) }
    trail.trail.forEach { addAll(it.recordings.keys - configurationNames) }
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
    val crossPlatformTrails = findings.count { f -> f.occurrences.any { it.crossPlatform } }
    if (crossPlatformTrails > 0) {
      appendLine(
        "$crossPlatformTrails trail(s) select in a dialect belonging to a different platform than " +
          "the device driving that step — e.g. a web: selector while the phone is active. No " +
          "cross-platform bridge exists in the resolver, so those selectors never match. Fix: " +
          "check the step's `switchDevice` handover, or re-record the step against the surface it " +
          "is meant to drive.",
      )
    }
    val dialectTrails = findings.count { f -> f.occurrences.any { !it.crossPlatform } }
    if (dialectTrails > 0) {
      appendLine(
        "$dialectTrails trail(s) resolve a recording leg whose selector dialect the device's " +
          "driver cannot match. An androidMaestro: selector under ANDROID_ONDEVICE_ACCESSIBILITY " +
          "never matches — the resolver dispatches on the tree shape the driver produced, and " +
          "Android has no cross-dialect bridge — so these steps fail on every run. Fix: give the " +
          "device its own recording leg carrying androidAccessibility: selectors, instead of " +
          "sharing a leg whose dialect belongs to the other driver.",
      )
    }
    val handoverTrails = findings.count { it.undeclaredHandovers.isNotEmpty() }
    if (handoverTrails > 0) {
      appendLine(
        "$handoverTrails trail(s) record a `switchDevice` naming a device the trail cannot bind. " +
          "That step fails the run at the session-start guard, and in a configuration leg it also " +
          "stops this gate from tracking which device is active — so every selector after it goes " +
          "unchecked. Fix: name a device the leg's own configuration declares, re-record the step, " +
          "or drop the switchDevice if the trail drives a single device.",
      )
    }
    findings.sortedBy { it.trailRelPath }.forEach { f ->
      if (f.selectorCount > 0) {
        appendLine(
          "  FAIL ${f.trailRelPath}: ${f.selectorCount} unmatchable selector(s); " +
            "device→driver ${f.affectedDevices}",
        )
        f.examples.forEach {
          appendLine(
            "        ${stepLabel(it.stepIndex)} resolves leg '${it.resolvedClassifier}' for '${it.deviceClassifier}' — " +
              "${it.toolName} ${it.dialectKey}{${it.selectorSummary}}",
          )
        }
        if (f.selectorCount > f.examples.size) {
          appendLine("        … and ${f.selectorCount - f.examples.size} more")
        }
      }
      if (f.undeclaredHandovers.isNotEmpty()) {
        appendLine(
          "  FAIL ${f.trailRelPath}: ${f.undeclaredHandovers.size} undeclared handover(s)",
        )
        f.undeclaredHandovers.forEach {
          val where = it.configurationName
            ?.let { name -> "in configuration '$name'" }
            ?: "in leg '${it.legKey}'"
          // Empty members means two different files: a configuration leg whose own `devices:` map
          // is empty, or a trail with no configuration at all. Keyed on the leg, not on emptiness,
          // so the line can't tell a reader the opposite of what their trail says.
          val known = when {
            it.declaredMembers.isNotEmpty() -> " (declared members: ${it.declaredMembers.joinToString()})"
            it.configurationName != null -> " — that configuration declares no devices"
            else -> " — this trail declares no multi-device configuration"
          }
          appendLine("        ${stepLabel(it.stepIndex)} $where switches to '${it.target}'$known")
        }
      }
    }
  }

  /**
   * Human-facing step label. 1-based: the stored index is 0-based, but a reader counting entries
   * under `trail:` starts at one, and [MultiDeviceHandoverGuard] already labels the same handover
   * `step 3`. Rendering the raw index would make the two gates name different steps for one defect.
   */
  private fun stepLabel(stepIndex: Int?): String = stepIndex?.let { "step ${it + 1}" } ?: "trailhead"

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
  ): List<Pair<String, JsonObject>> =
    collectDialectSelectors(args) { key -> MAESTRO_DIALECT_KEY_PLATFORM[key] == platform }

  /** As [collectMaestroDialectSelectors], for dialect slots belonging to any platform but [platform]. */
  private fun collectForeignPlatformSelectors(
    args: JsonElement,
    platform: TrailblazeDevicePlatform,
  ): List<Pair<String, JsonObject>> =
    collectDialectSelectors(args) { key ->
      DIALECT_KEY_PLATFORM[key]?.let { it != platform } == true
    }

  /** The shared walk; [matchesDialectKey] decides which slot keys count as hits. */
  private fun collectDialectSelectors(
    args: JsonElement,
    matchesDialectKey: (String) -> Boolean,
  ): List<Pair<String, JsonObject>> {
    val hits = mutableListOf<Pair<String, JsonObject>>()
    fun walk(element: JsonElement) {
      when (element) {
        is JsonObject -> element.forEach { (key, value) ->
          if (key in SAFE_FALLBACK_TOOL_NAMES) return@forEach
          if (value is JsonObject && matchesDialectKey(key)) hits.add(key to value)
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
