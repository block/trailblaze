package xyz.block.trailblaze.agent.blaze

/**
 * Detects the "stale-ref hallucination loop" where the LLM repeatedly taps (or asserts on)
 * the same element ref that no longer exists on the current screen.
 *
 * **Failure mode.** [TapTrailblazeTool]/[AssertVisibleTrailblazeTool] validate the LLM's
 * `ref` argument against the live view hierarchy before any action dispatches. When the
 * ref isn't present (screen has changed), the tool throws
 * `"Element ref 'X' not found on current screen…"` and the error returns to the LLM as a
 * tool_result. Because validation rejects *before* dispatch, `callCount` (which counts
 * dispatches) stays at 0 even as the LLM burns its full LLM-call budget retrying the same
 * dead ref. Chat-history truncation hides older successful observations, so the LLM has
 * no signal that the refs in its "memory" are stale — and the existing
 * [detectActionCycleHint] / [detectDominantActionHint] are tool-agnostic and fire late.
 *
 * **What this detector adds.** A focused signal that fires after [STALE_REF_THRESHOLD]
 * consecutive `Element ref X not found` errors targeting the *same* ref. When it fires,
 * [TrailblazeRunner] latches a high-priority synthetic user message that names the dead
 * ref(s) and tells the LLM to re-evaluate from the freshly-appended view hierarchy. The
 * view hierarchy is already included on every chat request via
 * [TrailblazeKoogLlmClientHelper.createNextChatRequest], so the recovery message just
 * needs to call out *why* the LLM should ignore its memorized refs.
 *
 * **Why this is separate from the existing cycle detectors.** Cycle detection fingerprints
 * the LLM's tool *args* and looks for repeating sequences. This detector deliberately covers
 * only the **same-ref sub-case**: ≥ [STALE_REF_THRESHOLD] consecutive errors on the same dead
 * ref (e.g. the LLM pinning on "000" for dozens of iterations). The consecutive-same-ref
 * signal is sharper than a fingerprint match, and the recovery message can name the specific
 * dead ref. Rotation among multiple dead refs ("000" → "001" → "y778" …) is intentionally
 * left to [detectActionCycleHint] / [detectDominantActionHint] — extending this tracker to
 * count across a rotating set would muddle the "stale-ref hallucination" signal with a more
 * general anti-loop heuristic the cycle detectors already provide.
 */
internal const val STALE_REF_THRESHOLD: Int = 3

/**
 * State machine for the stale-ref loop. Stateful across iterations of one prompt step;
 * the runner owns one instance per step and resets it on success.
 *
 * Bookkeeping is intentionally minimal: the last-seen dead ref + a consecutive counter.
 * If the LLM picks a *different* dead ref the counter restarts — that's still useful
 * progress (the LLM is at least exploring), and we don't want to fire recovery the moment
 * two unrelated dead refs appear in sequence.
 *
 * Threading: not thread-safe. The runner loop is sequential; do not share across coroutines.
 */
internal class StaleRefTracker {
  private var lastRef: String? = null
  private var consecutiveCount: Int = 0
  // Set of every ref that has already fired recovery in the *current* streak window. A
  // single-slot `lastFiredRef` was not enough: with a sequence like fire("A") → fire("B")
  // → 3 consecutive A again with no non-stale reset in between, the single-slot guard
  // would falsely re-fire for A because the most-recently-fired slot now holds "B".
  // Tracking the whole fired-ref set within the current streak preserves the documented
  // "fire at most once per ref per step" property until a [resetStreak] explicitly
  // wipes the set on genuine progress.
  private val firedRefs: MutableSet<String> = mutableSetOf()

  val currentRef: String? get() = lastRef
  val currentCount: Int get() = consecutiveCount

  /**
   * Record a stale-ref error for [ref]. Returns true when the consecutive count for
   * [ref] has reached [STALE_REF_THRESHOLD] *and* recovery has not already fired for the
   * same ref in this run (prevents re-firing on every iteration once we're past the
   * threshold).
   */
  fun recordStaleRef(ref: String): Boolean {
    if (ref == lastRef) {
      consecutiveCount++
    } else {
      lastRef = ref
      consecutiveCount = 1
    }
    val shouldFire = consecutiveCount >= STALE_REF_THRESHOLD && ref !in firedRefs
    if (shouldFire) {
      firedRefs.add(ref)
    }
    return shouldFire
  }

  /**
   * Reset on any non-stale-ref tool outcome. A single successful (or different-error)
   * call breaks the streak — we only care about *consecutive* stale-ref hits, not
   * cumulative.
   *
   * Also clears [firedRefs] so that if the LLM later drifts back to a previously-fired
   * ref *after* genuine progress, the next loop on that ref is treated as a fresh
   * hallucination and re-fires recovery. (Without this, refs that get reused across
   * screens — "000", "001" are common — would silently suppress every subsequent
   * recovery for the rest of the step.)
   */
  fun resetStreak() {
    lastRef = null
    consecutiveCount = 0
    firedRefs.clear()
  }
}

/**
 * Per-iteration view of the tool_results just appended for one LLM response.
 *
 * Used by [TrailblazeRunner] to decide whether the iteration was *purely* stale-ref
 * failures or contained mixed outcomes. A `MultipleToolStrategy` step (e.g.
 * [VerificationStep]) can land both a successful assertion AND a stale-ref error from a
 * single response — in that case the success means the LLM made real progress and the
 * stale-ref streak should reset *before* recording the iteration's stale-ref hits, so
 * the counter never accumulates against the threshold during productive turns.
 */
internal data class IterationStaleRefSummary(
  val staleRefs: List<String>,
  val hadNonStaleRefResult: Boolean,
)

/**
 * Classify each tool-result output from one LLM iteration into stale-ref refs vs other
 * outcomes (success or different error). Returns:
 *  - [IterationStaleRefSummary.staleRefs] — refs parsed from "Element ref 'X' not found"
 *    errors, in tool_result order, possibly with duplicates if the LLM emitted multiple
 *    stale-ref hits in the same response.
 *  - [IterationStaleRefSummary.hadNonStaleRefResult] — true iff at least one result was NOT
 *    a stale-ref error. This is the "any progress this turn" signal the runner uses to
 *    [StaleRefTracker.resetStreak] before recording new stale-ref hits.
 *
 * Empty input list yields `(emptyList, hadNonStaleRefResult = false)` so the caller treats
 * a zero-tool-call iteration as "no signal either way" (don't reset, don't record).
 */
internal fun summarizeIterationStaleRefs(
  toolResultOutputs: List<String?>,
): IterationStaleRefSummary {
  if (toolResultOutputs.isEmpty()) {
    return IterationStaleRefSummary(emptyList(), hadNonStaleRefResult = false)
  }
  val staleRefs = mutableListOf<String>()
  var hadNonStale = false
  for (output in toolResultOutputs) {
    val ref = parseStaleRefFromError(output)
    if (ref != null) staleRefs.add(ref) else hadNonStale = true
  }
  return IterationStaleRefSummary(staleRefs, hadNonStaleRefResult = hadNonStale)
}

/**
 * Extracts a stale-ref name from a tool error message. Returns null when the message
 * isn't the stale-ref pattern (so the tracker's streak resets via [StaleRefTracker.resetStreak]).
 *
 * Both [TapTrailblazeTool] and [AssertVisibleTrailblazeTool] throw the same message shape:
 *   "<toolName>: Element ref '<ref>' not found on current screen…"
 * Other refs-related errors (e.g. "found but has no bounds") are *not* stale-ref
 * hallucinations — the ref does resolve, the element just lacks geometry — so we
 * deliberately do not match them.
 */
internal fun parseStaleRefFromError(errorMessage: String?): String? {
  if (errorMessage == null) return null
  val match = STALE_REF_REGEX.find(errorMessage) ?: return null
  return match.groupValues[1].takeIf { it.isNotBlank() }
}

private val STALE_REF_REGEX = Regex("""Element ref '([^']+)' not found on current screen""")

/**
 * Builds the synthetic user message that breaks the stale-ref loop. The current view
 * hierarchy is already appended to every chat request by
 * [TrailblazeKoogLlmClientHelper.createNextChatRequest], so this message only needs to
 * tell the LLM *why* its memorized refs are stale and to re-read from the live hierarchy.
 *
 * Wording is deliberately blunt: the LLM has been ignoring the standard error message
 * (which itself referenced a non-existent `snapshot` tool — see the docs on the error
 * sites). Repeating the failure mode in the prompt's own voice is what reliably breaks
 * the loop.
 */
internal fun buildStaleRefRecoveryMessage(ref: String, repeatCount: Int): String = buildString {
  appendLine("## STALE-REF RECOVERY")
  appendLine()
  appendLine(
    "Your last $repeatCount tool calls targeted ref '$ref' which is NOT present on the " +
      "current screen. The screen state has changed since you last observed visible refs.",
  )
  appendLine()
  appendLine(
    "The view hierarchy of the CURRENT screen is included below in this request. " +
      "Re-evaluate from the visible elements there ONLY. Do NOT pick ref '$ref' or any " +
      "other ref that is not present in the live hierarchy. If no visible element matches " +
      "the objective, call `objectiveStatus(FAILED)` and report what could not be found.",
  )
}
