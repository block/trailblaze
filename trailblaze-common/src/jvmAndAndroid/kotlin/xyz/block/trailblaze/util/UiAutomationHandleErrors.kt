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

  // Distinctive phrases from the terminal errors `InstrumentationUtil` throws when it cannot
  // clear the cached handle or its in-process reconnect retry fails. Keeping the emitted text
  // and host-side classifier in sync lets both failures trigger the existing runner restart.
  const val NON_RECOVERABLE_RETRY_FAILED_PHRASE = "UiAutomation reconnect retry also failed"
  const val NON_RECOVERABLE_STATE_PHRASE = "non-recoverable state"
  const val NON_RECOVERABLE_CACHE_CLEAR_FAILED_PHRASE =
    "cached handle could not be cleared via reflection"

  // Matches both the platform's own "UiAutomation not connected!" error and our
  // [silentShellWedgeMessage], which starts with it on purpose so one check covers both.
  const val STALE_HANDLE_NOT_CONNECTED_PHRASE = "UiAutomation not connected"

  /**
   * Error for the silent-shell wedge: a dead UiAutomation connection makes every shell command
   * return `""` while appearing to succeed. Detected by a liveness probe in
   * `AdbCommandUtil.execShellCommand`. Starts with [STALE_HANDLE_NOT_CONNECTED_PHRASE] so
   * [isStaleHandleSignature] treats it as recoverable and the normal reconnect-and-retry runs.
   */
  fun silentShellWedgeMessage(command: String): String =
    "$STALE_HANDLE_NOT_CONNECTED_PHRASE (silent-shell wedge): the shell liveness probe returned " +
      "no output after '$command' — every shell command is silently returning nothing."

  /**
   * @return true if [message] indicates that in-process UiAutomation recovery is impossible:
   *   either Android blocked clearing the cached handle, or reconnecting after the cache reset
   *   also failed. Both errors require the host to restart the on-device instrumentation.
   *   Ordinary recoverable stale handles and unrelated reflection failures do not match.
   */
  fun isNonRecoverableStaleHandleSignature(message: String?): Boolean {
    val msg = message.orEmpty()
    val reconnectRetryFailed =
      msg.contains(NON_RECOVERABLE_RETRY_FAILED_PHRASE, ignoreCase = true) &&
      msg.contains(NON_RECOVERABLE_STATE_PHRASE, ignoreCase = true)
    val reflectiveRecoveryBlocked =
      msg.contains("UiAutomation", ignoreCase = true) &&
        msg.contains(NON_RECOVERABLE_CACHE_CLEAR_FAILED_PHRASE, ignoreCase = true)
    return reconnectRetryFailed || reflectiveRecoveryBlocked
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
    val msg = message.orEmpty()
    return msg.contains(STALE_HANDLE_NOT_CONNECTED_PHRASE, ignoreCase = true) ||
      msg.contains("cannot call disconnect()", ignoreCase = true) ||
      msg.contains("already connected", ignoreCase = true) ||
      msg.contains("error while disconnecting UiAutomation", ignoreCase = true)
  }
}
