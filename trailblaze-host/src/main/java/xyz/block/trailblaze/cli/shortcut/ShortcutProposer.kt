package xyz.block.trailblaze.cli.shortcut

import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.waypoint.WaypointMatcher
import java.security.MessageDigest

/**
 * Pure-synthesis core for `trailblaze waypoint shortcut propose`. Walks a session set,
 * extracts every (A→B) transition observed across distinct sessions, and synthesizes a
 * draft shortcut YAML body from the recorded `AgentDriverAction`.
 *
 * Deterministic v1 — see `docs/internal/devlog/2026-05-19-waypoint-pack-shortcuts.md`
 * for the algorithm, confidence floor, and rationale for the single-action-per-transition
 * scope. Multi-step transitions are a v2 follow-up — the YAML schema already supports
 * `tools:` as a list so the extension is non-breaking when it lands.
 *
 * No I/O — the CLI handles session loading, sibling-collision lookup, and sidecar
 * emission.
 */
object ShortcutProposer {

  const val DEFAULT_MIN_SUPPORT: Int = 3
  const val DEFAULT_FINGERPRINT_AGREEMENT: Double = 2.0 / 3.0

  /**
   * One session step (chronologically ordered within its session) carrying the captured
   * screen state plus the optional [AgentDriverAction] that this step recorded. Only
   * `*_AgentDriverLog.json` steps carry an action; other step types contribute screens
   * but no action. The proposer skips action-less steps when extracting transitions.
   */
  data class SessionStepWithAction(
    val sessionId: String,
    val stepIndex: Int,
    val stepId: String,
    val screen: ScreenState,
    val action: AgentDriverAction?,
  )

  /**
   * One synthesized shortcut proposal, ready for sibling-collision + idempotence checks.
   * The CLI joins this onto the on-disk metadata (target id, destination path) before
   * writing the sidecar.
   */
  data class Proposal(
    val fromWaypointId: String,
    val toWaypointId: String,
    val toolBody: ToolBody,
    val supportSessions: Int,
    val supportSteps: Int,
    val actionFingerprint: String,
    val proposalKey: String,
    val rationale: String,
  )

  /**
   * A normalized representation of one tool-list entry as it should appear in the
   * generated `*.shortcut.yaml`. Kept as a tagged value rather than a raw `JsonObject`
   * so the YAML emitter, the action fingerprint, and the renderer all stay aligned.
   */
  sealed class ToolBody {
    abstract val toolName: String

    /**
     * `tapOnElementBySelector` with the resolved selector. The selector's stable
     * description is what's hashed for the fingerprint and what reviewers see in the
     * generated YAML.
     */
    data class TapOnElementBySelector(
      val selector: TrailblazeNodeSelector,
      val selectorDescription: String,
    ) : ToolBody() {
      override val toolName = "tapOnElementBySelector"
    }

    data class Scroll(val forward: Boolean) : ToolBody() {
      override val toolName = "scroll"
    }

    data class Swipe(val direction: String) : ToolBody() {
      override val toolName = "swipe"
    }

    object PressBack : ToolBody() {
      override val toolName = "pressBackButton"
    }

    data class InputText(val text: String) : ToolBody() {
      override val toolName = "inputText"
    }

    object HideKeyboard : ToolBody() {
      override val toolName = "hideKeyboard"
    }
  }

  /** Typed skip reason — emitted to `rejected.json` so the next run / human reviewer
   * can see why a transition didn't make it. */
  data class Skipped(
    val fromWaypointId: String,
    val toWaypointId: String,
    val reason: String,
    val observedSessions: Int,
  )

  data class Analysis(
    val proposals: List<Proposal>,
    val skipped: List<Skipped>,
  )

  /**
   * Walks [sessions] (each is one session's chronologically-ordered steps) and emits one
   * proposal per (A→B) transition that:
   *
   *  - is observed in at least [minSupport] distinct sessions,
   *  - has a single dominant action fingerprint (≥ [fingerprintAgreement] of supporting
   *    sessions agree on the action shape at the transition), and
   *  - synthesizes to a non-null [ToolBody] (no `tapOnPoint` fallback — see kdoc on
   *    [synthesizeToolBody]).
   *
   * The from/to ids are resolved by running each step's screen through [WaypointMatcher]
   * against [waypoints]; a step that matches exactly one waypoint is "labeled" with that
   * waypoint id, anything else is "unlabeled" and contributes neither a from nor a to.
   */
  fun analyze(
    sessions: List<List<SessionStepWithAction>>,
    waypoints: List<WaypointDefinition>,
    target: TargetTemplateContext? = null,
    minSupport: Int = DEFAULT_MIN_SUPPORT,
    fingerprintAgreement: Double = DEFAULT_FINGERPRINT_AGREEMENT,
  ): Analysis {
    require(minSupport >= 1) { "minSupport must be >= 1, got $minSupport" }
    require(fingerprintAgreement in 0.0..1.0) {
      "fingerprintAgreement must be in [0.0, 1.0], got $fingerprintAgreement"
    }

    // Pair (fromId, toId) → list of (fingerprint, sessionId).
    val observations = mutableMapOf<Pair<String, String>, MutableList<Observation>>()

    for (session in sessions) {
      val labels = session.map { step -> labelStep(step, waypoints, target) }
      // Walk pairs of (step N, step N+1) where step N has a label, step N has an action,
      // step N+1 has a label, and the labels differ. Single-action v1 — we deliberately
      // do not chain unlabeled intermediate steps into a multi-tool sequence (see devlog).
      for (i in 0 until session.size - 1) {
        val from = labels[i] ?: continue
        val to = labels[i + 1] ?: continue
        if (from == to) continue
        val action = session[i].action ?: continue
        val body = synthesizeToolBody(action, session[i].screen) ?: continue
        val fp = fingerprint(body)
        observations.getOrPut(from to to) { mutableListOf() } += Observation(
          sessionId = session[i].sessionId,
          fingerprint = fp,
          body = body,
        )
      }
    }

    val proposals = mutableListOf<Proposal>()
    val skipped = mutableListOf<Skipped>()
    for ((edge, obs) in observations) {
      val (from, to) = edge
      val sessions = obs.map { it.sessionId }.distinct().size
      // Defensive: `observations.entries` only yields edges with ≥1 observation, but a
      // future refactor that filters observations earlier could expose a divide-by-zero
      // in the agreement ratio below. Mirrors the refinement analyzer's invariant guard.
      require(sessions > 0) { "internal invariant: edge $from -> $to has zero sessions" }
      if (sessions < minSupport) {
        skipped += Skipped(
          fromWaypointId = from,
          toWaypointId = to,
          reason = "support=$sessions distinct session(s) < min-support=$minSupport",
          observedSessions = sessions,
        )
        continue
      }
      // Pick the dominant action fingerprint and check agreement. Group by fingerprint
      // then sort descending by support to make the choice deterministic.
      val byFp = obs.groupBy { it.fingerprint }
      val dominant = byFp.entries.maxByOrNull { it.value.size } ?: continue
      val dominantSessions = dominant.value.map { it.sessionId }.distinct().size
      val agreement = dominantSessions.toDouble() / sessions.toDouble()
      if (agreement < fingerprintAgreement) {
        skipped += Skipped(
          fromWaypointId = from,
          toWaypointId = to,
          reason = "dominant action fingerprint agreement=${"%.2f".format(agreement)} " +
            "below floor=${"%.2f".format(fingerprintAgreement)} (no single procedure dominates)",
          observedSessions = sessions,
        )
        continue
      }
      val sampleBody = dominant.value.first().body
      val key = proposalKey(from, to, dominant.key)
      proposals += Proposal(
        fromWaypointId = from,
        toWaypointId = to,
        toolBody = sampleBody,
        supportSessions = sessions,
        supportSteps = obs.size,
        actionFingerprint = dominant.key,
        proposalKey = key,
        rationale = buildRationale(from, to, sampleBody, sessions, obs.size, agreement),
      )
    }
    return Analysis(
      proposals = proposals.sortedByDescending { it.supportSessions },
      skipped = skipped,
    )
  }

  /**
   * Returns the single waypoint id that uniquely matches [step]'s screen, or null if
   * zero or two-plus waypoints match. The runtime contract assumes the matched-waypoint
   * set has at most one entry at any point in time (see `WaypointDefinition` kdoc); two
   * matches is an authoring bug for the pack, but it shouldn't surface here as a false
   * (A→B) transition.
   */
  private fun labelStep(
    step: SessionStepWithAction,
    waypoints: List<WaypointDefinition>,
    target: TargetTemplateContext?,
  ): String? {
    val matched = waypoints.asSequence()
      .map { it to WaypointMatcher.match(it, step.screen, target) }
      .filter { (_, r) -> r.matched }
      .map { it.first.id }
      .take(2)
      .toList()
    return matched.singleOrNull()
  }

  /**
   * Maps an [AgentDriverAction] to a [ToolBody]. Returns null on:
   *  - any action type v1 doesn't yet synthesize (the unhandled enum cases),
   *  - a `TapPoint` whose hit-test in the captured tree fails to produce a stable
   *    selector (the same node-resolve `WaypointSuggestSelectorCommand` uses).
   *
   * The strict node-resolve is load-bearing: emitting a `tapOnPoint(x, y)` fallback
   * would land brittle coordinate-only shortcuts that the migration loop is actively
   * trying to eliminate. Skipping is the right call — the cluster surfaces as a
   * `rejected.json` entry the next iteration / human author can pick up.
   */
  internal fun synthesizeToolBody(action: AgentDriverAction, screen: ScreenState): ToolBody? = when (action) {
    is AgentDriverAction.TapPoint -> synthesizeTap(action.x, action.y, screen)
    // LongPressPoint deliberately drops to null in v1 — `synthesizeTap` would route it
    // through `tapOnElementBySelector`, which loses the long-press semantic. The
    // generated YAML would emit a plain tap and the replay would silently behave
    // differently than the recorded session. Threading a `longPress: Boolean` through
    // ToolBody + the YAML emitter + the fingerprint is the right path, but it's an
    // expansion past v1's "single deterministic atom per action type" scope; the
    // shortcut surfaces as a rejected.json entry the next iteration / author can
    // pick up.
    is AgentDriverAction.LongPressPoint -> null
    is AgentDriverAction.Scroll -> ToolBody.Scroll(forward = action.forward)
    is AgentDriverAction.Swipe -> ToolBody.Swipe(direction = action.direction)
    is AgentDriverAction.BackPress -> ToolBody.PressBack
    is AgentDriverAction.EnterText -> ToolBody.InputText(text = action.text)
    is AgentDriverAction.HideKeyboard -> ToolBody.HideKeyboard
    // v1 drops everything else: launch / kill / clear-state / airplane / permissions are
    // session-lifecycle actions that aren't valid shortcut atoms. `AssertCondition` is a
    // verification, not navigation. `WaitForSettle` is a no-op for navigation purposes.
    // `OtherAction` is the catch-all the producer emits when a new action type ships
    // without an extractor wired here — fail closed.
    else -> null
  }

  private fun synthesizeTap(x: Int, y: Int, screen: ScreenState): ToolBody? {
    val tree = screen.trailblazeNodeTree ?: return null
    val target = tree.hitTest(x, y) ?: return null
    val candidates = TrailblazeNodeSelectorGenerator.findAllValidSelectors(
      root = tree,
      target = target,
      maxResults = 5,
    )
    val best = candidates.firstOrNull { it.isBest } ?: candidates.firstOrNull() ?: return null
    // Reject index-only fallback selectors. The generator returns these (named "Index
    // fallback", "Global index fallback", or "Structural: global index fallback") when
    // no stable identifying property exists for the target — the selector body is just
    // a positional `index = N` with no driver matcher, no hierarchy / spatial children.
    // A shortcut whose first tap is implicitly "tap the Nth node" doesn't survive any
    // layout change. Better to skip and surface in rejected.json than land a brittle
    // PR. Match on the selector's *structure* rather than the strategy name string so
    // a generator rename doesn't silently regress this gate.
    if (isIndexOnlyFallback(best.selector)) return null
    return ToolBody.TapOnElementBySelector(
      selector = best.selector,
      selectorDescription = best.strategy,
    )
  }

  /**
   * A selector is "index-only" when it carries an `index` value with NO other
   * predicate — no driver matcher, no hierarchy / spatial children. The
   * `TrailblazeNodeSelectorGenerator` emits this shape as its strategies-of-last-resort
   * when nothing about the target node uniquely identifies it. Synthesizing a shortcut
   * from such a selector is a guaranteed near-term breakage.
   *
   * NOTE: keep this field list in sync with [TrailblazeNodeSelector] — if a future
   * field is added to that type, a selector using only the new field plus `index`
   * would silently be misclassified as "index-only" and rejected here. The
   * `canonicalSelector covers every TrailblazeNodeSelector field` test in
   * ShortcutProposerTest enforces the round-trip; failing it means this list also
   * needs an update.
   *
   * Visible as `internal` so the test in the same module can pin the
   * mixed-selector boundary (index + real matcher → NOT classified as fallback).
   */
  internal fun isIndexOnlyFallback(selector: TrailblazeNodeSelector): Boolean =
    selector.index != null &&
      selector.androidAccessibility == null &&
      selector.androidMaestro == null &&
      selector.iosMaestro == null &&
      selector.iosAxe == null &&
      selector.web == null &&
      selector.compose == null &&
      selector.containsChild == null &&
      selector.childOf == null &&
      selector.containsDescendants.isNullOrEmpty() &&
      selector.above == null &&
      selector.below == null &&
      selector.leftOf == null &&
      selector.rightOf == null

  /**
   * Stable SHA-1 over the tool body's salient fields. Two transitions with the same
   * action shape produce the same fingerprint; two transitions whose tap-selectors
   * differ (different resource id, different content description) produce different
   * fingerprints. The fingerprint is what dominates the cross-week dedupe key — if the
   * app's UI moves and the synthesized selector changes, the old PR's body documents
   * an action the agent no longer takes and a fresh PR is the right outcome.
   */
  internal fun fingerprint(body: ToolBody): String {
    val canonical = when (body) {
      is ToolBody.TapOnElementBySelector ->
        "tap|" + canonicalSelector(body.selector)
      is ToolBody.Scroll -> "scroll|${body.forward}"
      is ToolBody.Swipe -> "swipe|${body.direction}"
      ToolBody.PressBack -> "back"
      is ToolBody.InputText -> "input|${body.text}"
      ToolBody.HideKeyboard -> "hideKeyboard"
    }
    return sha1(canonical)
  }

  /**
   * Recursively canonicalize the full [TrailblazeNodeSelector] shape — driver-match
   * type + all driver fields, plus the spatial/hierarchy children that compose the
   * selector tree. Each level's parts are sorted so two selectors that only differ in
   * generator-emission order produce the same fingerprint; selectors that genuinely
   * differ on any path produce distinct fingerprints.
   *
   * Earlier versions only hashed a subset of `androidAccessibility` fields, which
   * collapsed distinct selectors into identical fingerprints — that weakened the
   * cross-session agreement check (different procedures looked identical) and broke
   * the cross-week dedupe key (real changes wouldn't surface as new proposals).
   */
  private fun canonicalSelector(selector: TrailblazeNodeSelector): String {
    val parts = mutableListOf<String>()
    selector.androidAccessibility?.let { m ->
      parts += "aa[" + listOfNotNull(
        m.resourceIdRegex?.let { "rid=$it" },
        m.textRegex?.let { "text=$it" },
        m.contentDescriptionRegex?.let { "desc=$it" },
        m.classNameRegex?.let { "cls=$it" },
        m.composeTestTagRegex?.let { "tag=$it" },
        m.uniqueId?.let { "uid=$it" },
        m.hintTextRegex?.let { "hint=$it" },
        m.labeledByTextRegex?.let { "labeledBy=$it" },
        m.stateDescriptionRegex?.let { "state=$it" },
        m.paneTitleRegex?.let { "pane=$it" },
        m.roleDescriptionRegex?.let { "role=$it" },
        m.isSelected?.let { "sel=$it" },
        m.isHeading?.let { "head=$it" },
        m.isClickable?.let { "click=$it" },
        m.isCheckable?.let { "checkable=$it" },
        m.isChecked?.let { "checked=$it" },
        m.isEditable?.let { "edit=$it" },
        m.isPassword?.let { "pwd=$it" },
        m.isScrollable?.let { "scroll=$it" },
        m.isEnabled?.let { "enabled=$it" },
        m.isFocused?.let { "focused=$it" },
        m.isMultiLine?.let { "multiline=$it" },
        m.inputType?.takeIf { it != 0 }?.let { "inputType=$it" },
        m.collectionItemRowIndex?.let { "row=$it" },
        m.collectionItemColumnIndex?.let { "col=$it" },
      ).sorted().joinToString(";") + "]"
    }
    // Non-android driver matchers: include their type identity so an iOS-vs-Android
    // selector with otherwise-identical structure still differentiates. v1 only
    // synthesizes android matchers in `synthesizeTap`, so these branches are
    // forward-compat insurance for when iOS / web synthesis lands.
    selector.androidMaestro?.let { parts += "am[" + it.toString() + "]" }
    selector.iosMaestro?.let { parts += "im[" + it.toString() + "]" }
    selector.iosAxe?.let { parts += "iax[" + it.toString() + "]" }
    selector.web?.let { parts += "w[" + it.toString() + "]" }
    selector.compose?.let { parts += "co[" + it.toString() + "]" }
    // Hierarchy / spatial children — recurse so a selector with a `containsChild` of
    // `text=Foo` doesn't fingerprint identically to a selector without it.
    selector.containsChild?.let { parts += "cc{" + canonicalSelector(it) + "}" }
    selector.childOf?.let { parts += "of{" + canonicalSelector(it) + "}" }
    selector.containsDescendants
      ?.takeIf { it.isNotEmpty() }
      ?.let { ds ->
        parts += "cd{" + ds.map { canonicalSelector(it) }.sorted().joinToString("|") + "}"
      }
    selector.above?.let { parts += "ab{" + canonicalSelector(it) + "}" }
    selector.below?.let { parts += "bel{" + canonicalSelector(it) + "}" }
    selector.leftOf?.let { parts += "lo{" + canonicalSelector(it) + "}" }
    selector.rightOf?.let { parts += "ro{" + canonicalSelector(it) + "}" }
    selector.index?.let { parts += "idx=$it" }
    return parts.sorted().joinToString(",")
  }

  /** `shortcut|<from>|<to>|<sha1-of-action>` — the cross-week dedupe key. */
  fun proposalKey(from: String, to: String, actionFingerprint: String): String =
    "shortcut|$from|$to|$actionFingerprint"

  /**
   * Slug-only id generation. The waypoint ids are already namespaced under the pack;
   * the shortcut id encodes both endpoints so reviewers can read the file name and see
   * what edge it covers without opening the file.
   */
  fun generateShortcutId(from: String, to: String): String {
    val fromSlug = lastSegment(from)
    val toSlug = lastSegment(to)
    return "auto-${fromSlug}__to__${toSlug}"
  }

  private fun lastSegment(waypointId: String): String =
    waypointId.split('/').last().replace(Regex("[^a-zA-Z0-9_-]"), "_")

  private fun buildRationale(
    from: String,
    to: String,
    body: ToolBody,
    supportSessions: Int,
    supportSteps: Int,
    agreement: Double,
  ): String {
    val action = when (body) {
      is ToolBody.TapOnElementBySelector -> "tap (${body.selectorDescription})"
      is ToolBody.Scroll -> "scroll forward=${body.forward}"
      is ToolBody.Swipe -> "swipe ${body.direction}"
      ToolBody.PressBack -> "press back"
      is ToolBody.InputText -> "input text"
      ToolBody.HideKeyboard -> "hide keyboard"
    }
    return "AUTO-PROPOSED. Observed $supportSessions distinct session(s) " +
      "transition from `$from` to `$to` via $action " +
      "(${supportSteps} total step(s); ${"%.0f".format(agreement * 100)}% action agreement). " +
      "Reviewer: confirm the selector is stable across app versions and rename the `auto-` id " +
      "before merge. See docs/internal/devlog/2026-05-19-waypoint-pack-shortcuts.md."
  }

  private fun sha1(s: String): String {
    val md = MessageDigest.getInstance("SHA-1")
    val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
    return buildString(bytes.size * 2) {
      for (b in bytes) {
        val v = b.toInt() and 0xff
        if (v < 16) append('0')
        append(v.toString(16))
      }
    }
  }

  private data class Observation(
    val sessionId: String,
    val fingerprint: String,
    val body: ToolBody,
  )
}
