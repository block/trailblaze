package xyz.block.trailblaze.util

/**
 * Pure classification of the platform error messages that indicate Android's cached
 * `android.app.UiAutomation` handle (held on `android.app.Instrumentation`) is in a
 * non-functional transitional state and must be cleared + reconnected.
 *
 * Kept in the shared (JVM + Android) source set with no Android dependencies so the exact
 * signatures we recover from are unit-testable on the JVM. The recovery *action* itself lives in
 * `InstrumentationUtil` (androidMain) because it touches the live `Instrumentation`; this object is
 * only the "is this one of the recoverable signatures?" decision.
 *
 * The signatures, and why each one is the same root cause (a stale or half-connected handle):
 * - **"UiAutomation not connected"** — a stale handle (`id=-1`) left by a torn-down prior
 *   instrumentation cycle; surfaces from `UiDevice.waitForIdle` / `dumpWindowHierarchy`.
 * - **"Cannot call disconnect()"** — `Instrumentation.getUiAutomation` tried to disconnect the
 *   cached handle to swap UiAutomation flags while it was still mid-handshake (state CONNECTING).
 *   This is the transient race that flaked on-device Android startup in CI ("Run Eval Android
 *   Trails" / CLI smoke tests).
 * - **"Already connected"** — the platform refusing to re-`connect()` a handle whose local state
 *   disagrees with the remote object.
 * - **"Error while disconnecting UiAutomation"** — a bare `RuntimeException` the platform throws
 *   when the inner remote object is already gone but the local handle hasn't been cleared.
 */
object UiAutomationHandleErrors {

  // Distinctive phrases from the NON-recoverable message that `InstrumentationUtil`'s
  // `runWithStaleUiAutomationRecovery` throws after its in-process retry also fails. They are the
  // source of truth for both that throw site and [isNonRecoverableStaleHandleSignature], so the
  // matcher can't drift from the emitted text. Neither phrase appears in a recoverable signature.
  const val NON_RECOVERABLE_RETRY_FAILED_PHRASE = "UiAutomation reconnect retry also failed"
  const val NON_RECOVERABLE_STATE_PHRASE = "non-recoverable state"

  /**
   * @return true if [message] is the terminal NON-recoverable signature that surfaces only after
   *   the in-process [isStaleHandleSignature] retry has already failed (see
   *   `InstrumentationUtil.runWithStaleUiAutomationRecovery`). This is what reaches the on-disk
   *   `SessionStatus.Ended.Failed.exceptionMessage`, so the host harness keys its server-relaunch
   *   decision on it. Both distinctive phrases must be present, so an ordinary recoverable
   *   signature (already absorbed in-process) or an unrelated failure does not match.
   */
  fun isNonRecoverableStaleHandleSignature(message: String?): Boolean {
    val msg = message.orEmpty().lowercase()
    return msg.contains(NON_RECOVERABLE_RETRY_FAILED_PHRASE.lowercase()) &&
      msg.contains(NON_RECOVERABLE_STATE_PHRASE.lowercase())
  }

  /**
   * @return true if [message] matches one of the recoverable stale / half-connected
   *   UiAutomation-handle signatures. A null message is treated as non-matching.
   *
   * Matching is case-insensitive and tolerant of trailing punctuation/wording because the exact
   * text varies across Android versions and OEM forks — e.g. AOSP throws
   * `"Cannot call disconnect() while connecting!"` and `"UiAutomation already connected!"`, while
   * the build that flaked our CI reported `"Cannot call disconnect() while connecting UiAutomation"`.
   * Matching on the stable substrings (lower-cased) covers every observed variant without enumerating
   * each one.
   *
   * The breadth is deliberate. This is only consulted from the recovery `catch` around live
   * UiAutomation / UiDevice calls, so the message universe is already narrow, and a false positive
   * costs only one bounded cache-clear + retry — it does not permanently swallow an error (a
   * genuinely different failure recurs on the retry and propagates). The opposite mistake — failing
   * to recognize a recoverable signature — re-introduces the flake, so when in doubt we match.
   */
  fun isStaleHandleSignature(message: String?): Boolean {
    val msg = message.orEmpty().lowercase()
    return msg.contains("uiautomation not connected") ||
      msg.contains("cannot call disconnect()") ||
      msg.contains("already connected") ||
      msg.contains("error while disconnecting uiautomation")
  }
}
