package xyz.block.trailblaze.api

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
 * caller reading stale state. Implementations MUST wrap in `try { action() } finally { /* settle */ }`
 * (or equivalent) so the settle is on the throw path too.
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
   * whether [action] completes normally or throws.
   */
  suspend fun <R> dispatchAndAwaitSettle(action: suspend () -> R): R
}
