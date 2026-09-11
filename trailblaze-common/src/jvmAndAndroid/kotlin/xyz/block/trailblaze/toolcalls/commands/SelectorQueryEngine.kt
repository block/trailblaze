package xyz.block.trailblaze.toolcalls.commands

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.SnapshotCache
import xyz.block.trailblaze.util.Console

/**
 * The capture/retry/wait policy the read-only selector query tools share: WHEN to capture, when a
 * capture may be trusted, when to re-capture, and when to refuse to answer.
 *
 * [FindMatchesTrailblazeTool] and [FindSelectorMatchesTrailblazeTool] differ in exactly one
 * respect — one selector versus N — and that difference belongs entirely in the [Resolution] each
 * one produces from a captured tree. Everything else was duplicated line-for-line, which is the
 * worst place to duplicate: the rules here are subtle (a completeness flag that must describe the
 * capture actually being returned, a transient null tree that is not an error, a retry budget) and a
 * copy that drifts produces a tool that silently answers "absent" where its sibling refuses to. The
 * engine makes the two agree by construction rather than by review.
 *
 * ## The trust rule
 *
 * On Android every node in a capture is a live accessibility fetch, and a blocked app answers some
 * of them with null — so a capture can be missing subtrees and say so
 * ([ScreenState.isCaptureKnownPartial]). Reporting "no matches" out of such a capture is the one
 * genuinely dangerous answer these tools can give, because a scripted caller reads an empty result
 * as "not on screen" and takes its absent branch. So:
 *
 * - A result that **claims absence** ([Resolution.claimsAbsence]) is returned only from a capture
 *   that was complete. Otherwise the capture is dropped and retaken, then the call fails rather than
 *   answering.
 * - A result that claims no absence is returned immediately, complete capture or not — a node that
 *   IS in a partial tree really was on screen, so presence needs no confirmation.
 * - Drivers that cannot measure completeness report unknown, which reads as complete. They behave
 *   exactly as they did before any of this existed.
 */
internal object SelectorQueryEngine {

  /**
   * What one selector resolve against one captured tree yielded, in the two dimensions the engine
   * needs — deliberately independent, because for N selectors they come apart.
   *
   * @property result the tool's own answer shape (`List<MatchDescriptor>`, `List<List<…>>`, …).
   * @property matched at least one selector matched, so a WAIT may end here. Meaningless to the
   *   point-in-time path, which never waits.
   * @property claimsAbsence some part of [result] asserts an element is NOT on screen (an empty
   *   match list), so returning it requires a capture known complete. For one selector this is
   *   simply `!matched`; for N it is "any selector came back empty", which can be true at the same
   *   time as [matched] — that is the whole reason these are two fields and not one.
   */
  data class Resolution<out R>(
    val result: R,
    val matched: Boolean,
    val claimsAbsence: Boolean,
  )

  /**
   * How a query ended. [NoTreeEverSeen] is the driver/platform mismatch (this driver produces no
   * [TrailblazeNode] tree at all, so node selectors are unanswerable); [UntrustworthyAbsence]
   * is the trust rule refusing to report absence, and carries the last result so the caller can name
   * WHICH selectors went unanswered in its error.
   */
  sealed interface Outcome<out R> {
    /** @property captures how many device captures it took — what a caller reports in its summary. */
    data class Resolved<out R>(val result: R, val captures: Int) : Outcome<R>

    data class NoTreeEverSeen(val platform: TrailblazeDevicePlatform) : Outcome<Nothing>

    /**
     * The trust rule refused: the result claims some element is absent, and no capture this call is
     * willing to stand behind said so.
     *
     * @property sawCompleteCapture whether a complete capture appeared ANYWHERE in the window. It
     *   distinguishes two different device conditions that need different advice, so a caller's
     *   error message must not assume either: `false` means nothing readable ever came back (the
     *   app is wedged), while `true` means the app answers intermittently and this call merely ended
     *   on a holey capture — which is worth retrying and is unreachable for a single selector.
     */
    data class UntrustworthyAbsence<out R>(
      val captures: Int,
      val lastResult: R,
      val sawCompleteCapture: Boolean,
    ) : Outcome<R>
  }

  /**
   * A single point-in-time query, routed through [SnapshotCache] so repeated queries inside one
   * tool invocation share the multi-second capture — plus the trust rule's re-captures, up to
   * [PARTIAL_CAPTURE_RECAPTURES] extra attempts.
   *
   * A null tree here is immediately [Outcome.NoTreeEverSeen]: with no wait budget there is no later
   * poll that could produce one, so a null tree can only mean this driver has none.
   *
   * @param describeAbsence renders the unanswered part of a result for the give-up log, e.g.
   *   `"selector=…"` or `"unmatched=[…]"`.
   */
  suspend fun <R> resolvePointInTime(
    provider: () -> ScreenState,
    traceTag: String?,
    logTag: String,
    describeAbsence: (R) -> String,
    resolve: (TrailblazeNode) -> Resolution<R>,
  ): Outcome<R> {
    var captures = 0
    while (true) {
      captures++
      val screenState = SnapshotCache.snapshot(provider, traceTag)
      val tree = screenState.trailblazeNodeTree
        ?: return Outcome.NoTreeEverSeen(screenState.trailblazeDevicePlatform)
      val resolution = resolve(tree)
      if (!resolution.claimsAbsence || !screenState.isCaptureKnownPartial) {
        return Outcome.Resolved(resolution.result, captures)
      }
      if (captures > PARTIAL_CAPTURE_RECAPTURES) {
        Console.log(
          "[$logTag] gave up after $captures capture(s) that each dropped node fetches; refusing " +
            "to report absence — ${describeAbsence(resolution.result)}",
        )
        return Outcome.UntrustworthyAbsence(captures, resolution.result, sawCompleteCapture = false)
      }
      // Drop the frame's partial tree so the re-capture is live, and so a later query in the same
      // batch reads the fresh tree rather than the one we just declined to trust.
      SnapshotCache.invalidateCurrent(traceTag)
      delay(pollIntervalMs)
    }
  }

  /**
   * Polls the LIVE hierarchy — via [provider] directly, NOT [SnapshotCache], so every iteration
   * sees the current screen — until a resolve both matched and can be trusted, or [timeoutMs]
   * elapses.
   *
   * Three rules that are easy to get wrong and are therefore only written once, here:
   *
   * - **A null tree on SOME polls is not an error**, just "no match yet" mid-transition. Only a tree
   *   never seen across the whole window is [Outcome.NoTreeEverSeen].
   * - **Completeness is asked TWICE, of different things, and that is deliberate.** Whether to
   *   return a frame EARLY is a question about that frame. Whether an all-empty window may be
   *   reported as absence at all is a question about the WINDOW, and one complete capture anywhere
   *   in it settles that ([sawCompleteCapture]). Collapsing them into one flag breaks whichever
   *   question it then answers badly: sticky-only would return a holey frame carrying an unanswered
   *   selector because some earlier poll happened to be complete, and per-frame-only would fail a
   *   long wait on one unlucky partial final poll after a hundred complete ones agreed on absence.
   *   **Both exits apply the rule** — the early return and the timeout tail. Guarding only the early
   *   return leaves the hole the sticky flag was never meant to open: a mixed frame (some selectors
   *   matched, some didn't) escaping at timeout out of a partial capture, on the strength of an
   *   earlier complete poll that was looking at a screen the new match proves has since changed.
   * - **A match out of a partial capture does not end the wait if the same frame also claims
   *   absence.** The budget is already there, so spending more of it to get a frame worth trusting is
   *   strictly better than returning one that isn't. For a single selector this can never fire (a
   *   match claims no absence), which is why `findMatches` behaves exactly as it did; for N it is
   *   what keeps the polling path as strict as the point-in-time path.
   *
   * The per-iteration sleep is capped to the remaining budget, so a small [timeoutMs] returns at
   * ~`timeoutMs` rather than rounding up to a whole poll interval.
   *
   * **Drops the frame's cached snapshot on the way out**, in a `finally` rather than after the
   * loop — the early return on a match is exactly the case where the cached tree is most stale,
   * since it predates the screen the caller just waited for. Without this, a point-in-time query
   * later in the same batch would read the pre-wait screen, and neither caller can invalidate for
   * itself: both are [xyz.block.trailblaze.toolcalls.ReadOnlyTrailblazeTool], which is precisely
   * the marker that tells the dispatcher not to.
   *
   * The re-capture loop is intentionally simple; the real fix is a driver-native event-driven wait
   * (the accessibility / Maestro side already has one in `executeNodeSelectorAssertVisible`) so a
   * poll doesn't cost a full-tree capture.
   */
  suspend fun <R> poll(
    provider: () -> ScreenState,
    timeoutMs: Long,
    traceTag: String?,
    logTag: String,
    describeAbsence: (R) -> String,
    resolve: (TrailblazeNode) -> Resolution<R>,
  ): Outcome<R> = try {
    pollLive(provider, timeoutMs, logTag, describeAbsence, resolve)
  } finally {
    SnapshotCache.invalidateCurrent(traceTag)
  }

  private suspend fun <R> pollLive(
    provider: () -> ScreenState,
    timeoutMs: Long,
    logTag: String,
    describeAbsence: (R) -> String,
    resolve: (TrailblazeNode) -> Resolution<R>,
  ): Outcome<R> {
    val start = TimeSource.Monotonic.markNow()
    val budget = timeoutMs.milliseconds
    var lastResolution: Resolution<R>? = null
    var lastResolveWasComplete = false
    var sawCompleteCapture = false
    var lastPlatform = TrailblazeDevicePlatform.ANDROID
    var pollCount = 0
    while (true) {
      pollCount++
      val screenState = provider()
      lastPlatform = screenState.trailblazeDevicePlatform
      val tree = screenState.trailblazeNodeTree
      if (tree != null) {
        // Unknown completeness counts as complete, so drivers that can't measure it keep behaving
        // exactly as before. `lastResolveWasComplete` is overwritten every resolvable poll, so it
        // always describes the capture that produced `lastResolution`; `sawCompleteCapture` is
        // sticky. See the kdoc for why both.
        lastResolveWasComplete = !screenState.isCaptureKnownPartial
        if (lastResolveWasComplete) sawCompleteCapture = true
        val resolution = resolve(tree)
        lastResolution = resolution
        if (resolution.matched && (!resolution.claimsAbsence || lastResolveWasComplete)) {
          return Outcome.Resolved(resolution.result, pollCount)
        }
      }
      val remaining = budget - start.elapsedNow()
      if (remaining <= Duration.ZERO) break
      delay(minOf(pollIntervalMs.milliseconds, remaining))
    }
    // Non-null exactly when a tree was seen on some poll, which is the "did this driver produce a
    // hierarchy at all" question the outcome turns on.
    val finalResolution = lastResolution ?: return Outcome.NoTreeEverSeen(lastPlatform)
    // Which absence claims this frame is allowed to carry out of the wait. The sticky flag settles
    // an ALL-EMPTY window only: there, every look agreed on absence and nothing suggests the screen
    // moved, so an unlucky partial final poll shouldn't fail the call. It cannot settle a MIXED
    // frame (something matched, something didn't) out of a partial capture — the new match is
    // evidence the screen changed since the last trustworthy look, which is exactly when the holey
    // frame's empty slots stop being safe to read as absence. A single selector can never be mixed,
    // so this arm is unreachable for `findMatches`.
    val mayReportAbsence = !finalResolution.claimsAbsence ||
      lastResolveWasComplete ||
      (!finalResolution.matched && sawCompleteCapture)
    if (!mayReportAbsence) {
      Console.log(
        "[$logTag] timeoutMs=$timeoutMs elapsed after $pollCount poll(s); refusing to report " +
          "absence out of a capture that dropped node fetches " +
          (if (sawCompleteCapture) "and also matched something " else "") +
          "— ${describeAbsence(finalResolution.result)}",
      )
      return Outcome.UntrustworthyAbsence(pollCount, finalResolution.result, sawCompleteCapture)
    }
    // Polled the whole budget against resolvable trees and nothing ever matched — a normal
    // "didn't appear within the timeout" outcome, not an error. Logged so an author debugging a
    // flaky wait can see the poll actually ran rather than short-circuiting.
    Console.log(
      "[$logTag] timeoutMs=$timeoutMs elapsed after $pollCount poll(s); nothing matched, " +
        "returning empty — ${describeAbsence(finalResolution.result)}",
    )
    return Outcome.Resolved(finalResolution.result, pollCount)
  }

  /**
   * Production delay between live re-captures on the polling path.
   *
   * Deliberately a fixed constant with no `TRAILBLAZE_*` env override: this client-side poll is a
   * stopgap (see [poll] — the real fix is a driver-native event-driven wait), so it isn't worth a
   * tunable knob plus the CLAUDE.md doc surface that would outlive the mechanism. Tests adjust the
   * sibling [pollIntervalMs] seam directly.
   */
  const val DEFAULT_POLL_INTERVAL_MS = 300L

  /**
   * Delay between live re-captures. A mutable seam (not a `const`) so tests can shrink it to avoid
   * real-time sleeps; production uses [DEFAULT_POLL_INTERVAL_MS]. Lives here, on the shared engine,
   * rather than on either tool: when it sat on `FindMatchesTrailblazeTool` the sibling tool read
   * it across a class boundary, so a test shrinking it for one tool silently reconfigured the
   * other. The per-iteration sleep is additionally capped to the remaining budget in [poll], so it
   * never overshoots `timeoutMs`.
   */
  var pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS

  /**
   * Extra live re-captures [resolvePointInTime] takes when a capture that lost nodes produced a
   * result claiming absence. Small on purpose: these are the cheap query tools scripted authors
   * call on every branch, and the alternative to a couple of retries is failing the tool on one
   * unlucky capture. An author who wants a real wait passes `timeoutMs`.
   */
  const val PARTIAL_CAPTURE_RECAPTURES = 2
}
