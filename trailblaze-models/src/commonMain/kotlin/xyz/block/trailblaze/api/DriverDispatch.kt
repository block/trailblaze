package xyz.block.trailblaze.api

import kotlin.coroutines.cancellation.CancellationException
import xyz.block.trailblaze.util.Console

/**
 * Cross-driver contract for "dispatch a content-changing action, then wait until the driver's UI
 * is settled enough to safely read state."
 *
 * Every driver-specific manager (Android Accessibility, Playwright, Compose Desktop, ...) routes
 * its content-changing methods (tap, swipe, type, click, ...) through [dispatchAndAwaitSettle]
 * so the post-action wait is impossible to forget when a new gesture is added. The settle signal
 * is driver-specific:
 *
 * - Android Accessibility: `UiDevice.waitForIdle()` — platform-level accessibility-event quiet.
 * - Playwright: outstanding HTTP request drain + navigation `load` state.
 * - Compose Desktop: `waitForIdle()` — recomposition queue drain.
 *
 * ## Do NOT route reads, verifications, or explicit waits through this
 *
 * **Only content-changing gestures belong here.** Reads (`getScreenState`), verifications
 * (`assertVisible`, `assertNotVisible`), and explicit "wait N seconds" tools each have their
 * own model — verifications use poll-until-target-state loops, reads call the settle primitive
 * directly (`target.waitForIdle()` on Compose, etc.), and explicit waits bracket their own
 * sleeps. Wrapping any of these in `dispatchAndAwaitSettle` either does redundant settle work
 * (reads) or, worse, silently masks the wrong semantics (the verify loop wants "wait until
 * target state OR timeout", not "wait until any motion stops"). See
 * `AccessibilityDeviceManager.executeAssertVisible` for the reference verify-loop shape.
 *
 * ## Exception contract
 *
 * The settle wait runs whether [action] completes normally or throws. Rationale: if [action]
 * threw partway through a gesture (tap dispatched, then a follow-up step in the lambda threw),
 * the UI is in motion regardless of the exception — skipping the settle would leave the next
 * caller reading stale state.
 *
 * A settle that TIMES OUT after [action] returned does not fail the dispatch. The action already
 * changed the app, and an idle wait that expires (an endless animation keeps the app busy forever)
 * says nothing about whether it worked. Reporting it as a failure would mark a tool call failed
 * after its tap had landed, and failed calls are never recorded, so replay would skip a real step
 * and run the rest of the trail from the wrong screen. The timeout is logged and the action's
 * result returned.
 *
 * Any other settle failure still fails the dispatch. Compose and Espresso rethrow exceptions the
 * app raised on its UI thread from their idle waits; that is the app breaking, and swallowing it
 * would let a run pass with a crashed app. When [action] and the settle both throw, the action's
 * exception propagates with the settle's attached as suppressed, unless the settle was cancelled,
 * in which case cancellation wins.
 *
 * [dispatchThenSettle] implements all of this, and every implementer whose settle can throw goes
 * through it. The Accessibility and Playwright settles report a timeout by returning rather than
 * throwing, so they keep their own `try { action() } finally { /* settle */ }`.
 *
 * ## Why suspend
 *
 * The method is `suspend` so it can express the union of all drivers' natural shapes: Playwright's
 * settle does real coroutine I/O (request tracking, `delay`, navigation waits), while Android and
 * Compose can implement it as a suspend body that never actually suspends (just invokes the
 * action and calls the blocking settle primitive inline). Drivers whose existing call sites are
 * non-coroutine (e.g. Android's per-gesture `fun tap()`) keep a small private blocking helper
 * inside the class to avoid forcing every caller into a coroutine context — see
 * `AccessibilityDeviceManager.dispatchAndAwaitSettleBlocking`.
 *
 * ## Current implementers
 *
 * - [xyz.block.trailblaze.android.accessibility.AccessibilityDeviceManager]
 * - [xyz.block.trailblaze.playwright.PlaywrightPageManager]
 * - [xyz.block.trailblaze.compose.target.ComposeTestTarget]
 *
 * ## Pending
 *
 * iOS Maestro and iOS Axe do not implement this yet. iOS Maestro relies on Maestro's implicit
 * settle inside `viewHierarchy()`; iOS Axe has no settle at all today. Both are blocked on a
 * reactive on-device settle observer (subscribing to `UIAccessibility.layoutChanged` /
 * `screenChanged` + `CFRunLoopObserver`) — the iOS analog of Android's accessibility-event quiet
 * detector. Once that on-device observer lands, both iOS paths can implement this interface and
 * rip out Maestro's implicit-settle reliance in the process.
 */
interface DriverDispatch {
  /**
   * Runs [action], then does not return until the driver's UI is settled enough to safely read
   * state. The settle signal is driver-specific (see interface kdoc). The settle wait runs
   * whether [action] completes normally or throws. A settle timeout after [action] returned does
   * not fail the dispatch (see interface kdoc).
   */
  suspend fun <R> dispatchAndAwaitSettle(action: suspend () -> R): R
}

/**
 * Runs [action], then [settle], under the [DriverDispatch] exception contract. The settle runs on
 * both paths. A settle failure that [isSettleTimeout] recognizes after [action] returned is logged,
 * and [action]'s result is returned; any other settle failure propagates. When both throw, the
 * action's exception wins unless the settle was cancelled. Cancellation always propagates.
 */
suspend fun <R> dispatchThenSettle(
  action: suspend () -> R,
  settle: () -> Unit,
  isSettleTimeout: (Throwable) -> Boolean,
): R {
  val result = try {
    action()
  } catch (actionFailure: Throwable) {
    try {
      settle()
    } catch (settleFailure: Throwable) {
      if (settleFailure is CancellationException && actionFailure !is CancellationException) {
        settleFailure.addSuppressed(actionFailure)
        throw settleFailure
      }
      actionFailure.addSuppressed(settleFailure)
    }
    throw actionFailure
  }
  try {
    settle()
  } catch (e: Throwable) {
    if (e is CancellationException || !isSettleTimeout(e)) throw e
    Console.log("[settle] action completed, but the UI never went idle after it; reporting the action as done: $e")
  }
  return result
}
