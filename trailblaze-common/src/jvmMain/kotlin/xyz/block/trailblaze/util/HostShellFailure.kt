package xyz.block.trailblaze.util

import xyz.block.trailblaze.exception.TrailblazeException

/**
 * Resolves a bounded host shell attempt into its output, or throws saying which of the three ways
 * it failed actually happened.
 *
 * `null` and `""` are different facts and must stay different: null means no answer came back,
 * while "" is the ordinary answer from a great many shell commands. Collapsing them would report a
 * wedged device as "the app is not in the foreground" or "this package has no dexopt state" — a
 * wrong answer delivered as a right one, and strictly worse than the hang the bound replaced.
 *
 * Separate and internal so the whole decision is testable without a device.
 */
internal fun hostShellOutputOrThrow(
  attempt: AndroidHostAdbUtils.ShellAttemptResult,
  deviceLabel: String,
  command: String,
  timeoutMs: Long,
): String {
  attempt.value?.let { return it }
  throw TrailblazeException(
    hostShellFailureMessage(attempt.outcome, deviceLabel, command, timeoutMs),
    // The worker's own exception, when it had one. Without it the stack trace stops at this line
    // and says nothing about whether the socket closed, the device vanished, or adbd refused.
    attempt.error,
  )
}

/**
 * What a failed host shell attempt reports, one wording per outcome.
 *
 * Two things every wording must get right: the command is REDACTED, because this lands in session
 * logs and `android_adbShell` carries LLM auth tokens and base64 file bodies; and it is TRUNCATED,
 * because a `writeFileAs` command line can be megabytes and an exception message is not a place to
 * put one.
 */
internal fun hostShellFailureMessage(
  outcome: AndroidHostAdbUtils.ShellAttemptOutcome,
  deviceLabel: String,
  command: String,
  timeoutMs: Long,
): String {
  val redacted = AndroidHostAdbUtils.redactSecretsForLog(command)
  val shown = if (redacted.length > HOST_SHELL_COMMAND_CHARS) {
    redacted.take(HOST_SHELL_COMMAND_CHARS) + "…"
  } else {
    redacted
  }
  val prefix = "adb shell on device '$deviceLabel': '$shown' — "
  return prefix + when (outcome) {
    AndroidHostAdbUtils.ShellAttemptOutcome.TIMED_OUT ->
      // No promise of recovery: every dadb command opens its own socket and `close()` on the
      // cached client does nothing, so a transport that is still wedged makes the next command
      // wait out its own bound too. What IS true, and worth knowing before retrying a `pm clear`,
      // is that the device may still run this one.
      "did not return within ${timeoutMs}ms. The device or its adb transport is wedged, and the " +
        "command may still complete on the device after this."
    AndroidHostAdbUtils.ShellAttemptOutcome.FAILED ->
      "the call failed or was interrupted before it could answer. See the cause, if any."
    // Reachable only if the transport starts returning a null body for a successful shell call,
    // which `dadb.shell` does not. Named rather than folded into the others so that if it ever
    // does happen, the message says so instead of blaming a device that answered fine.
    AndroidHostAdbUtils.ShellAttemptOutcome.SUCCESS ->
      "completed but produced no output object at all, which this transport is not supposed to do."
  }
}

private const val HOST_SHELL_COMMAND_CHARS = 200

/**
 * Keeps a transport failure from being laundered into an ordinary "condition not met" by a polling
 * loop.
 *
 * `PollingUtils` treats any throwing attempt as "not yet", which is right for a device that
 * answered "no" and wrong for one that never answered: the caller gets a confident `false` and
 * acts on it. For the foreground poll that means re-launching an app on a wedged device instead of
 * reporting the wedge.
 *
 * A later attempt that DOES reach the device clears the record. The transport recovered, so the
 * poll's own verdict is trustworthy again and a stale failure must not override it.
 */
internal class TransportFailureTracker {

  private var failure: TrailblazeException? = null

  /** Runs [block], remembering a transport failure on the way past and clearing it on success. */
  fun <T> record(block: () -> T): T = try {
    block().also { failure = null }
  } catch (e: TrailblazeException) {
    failure = e
    throw e
  }

  /**
   * Throws the remembered failure when the poll gave up, because a `false` produced by a device
   * that never answered is not an answer. A poll that [succeeded] is reported as the success it is.
   */
  fun rethrowIfUnresolved(succeeded: Boolean) {
    if (!succeeded) failure?.let { throw it }
  }
}
