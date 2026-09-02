package xyz.block.trailblaze.ui

import xyz.block.trailblaze.devices.TrailblazePortRangeConflictException

/**
 * How long a process that failed the daemon-port bind waits for the rival to answer `/ping`
 * before classifying the failure. A rival that bound the socket but hasn't brought its routes
 * up within this window is conservatively classified as [PortBindFailureAction.ExitAsStartupFailure]
 * — the process still exits either way, only the exit code and message differ.
 */
internal const val RIVAL_DAEMON_WAIT_MS = 5_000L

/**
 * How long a losing non-headless duplicate keeps retrying the window handoff to the winner.
 * Longer than [RIVAL_DAEMON_WAIT_MS] because the winner answers `/ping` well before its Compose
 * UI installs the show-window callback — the handoff waits out that UI boot, not just the bind.
 */
internal const val WINNER_SHOW_WINDOW_WAIT_MS = 10_000L

/** Poll interval for the window-handoff retry loop. */
internal const val WINNER_SHOW_WINDOW_POLL_MS = 500L

/** What a Trailblaze app process should do after failing to bind its daemon port. */
sealed interface PortBindFailureAction {
  /**
   * Another daemon answered on the port — this process is a duplicate and must exit so the user
   * never sees two tray icons advertising the same port. When [requestShowWindow] is true
   * (non-headless launch), the duplicate hands the user's intent to the winner by asking it to
   * show its window before exiting.
   */
  data class ExitAsDuplicate(val requestShowWindow: Boolean) : PortBindFailureAction

  /** Nothing answers on the port — the bind failure is a genuine startup error. */
  data object ExitAsStartupFailure : PortBindFailureAction

  /**
   * The port itself is unusable by configuration, so no bind on it could ever have won. Reported
   * as the configuration error it is, without consulting the port — see [classifyPortBindFailure]
   * for why probing would actively mislead here.
   */
  data object ExitAsConfigError : PortBindFailureAction
}

/**
 * Decides how a process that failed to bind the daemon port should exit.
 *
 * The port bind is the single atomic arbiter for "one daemon (and one tray icon) per port": the
 * pre-launch `isRunning` checks race against the multi-second daemon boot window, so several
 * spawned instances can all reach the bind. Exactly one wins; every loser lands here and must
 * exit rather than linger as a server-less tray icon.
 *
 * A [TrailblazePortRangeConflictException] is decided *before* [probeForRivalDaemon] is called, and
 * that ordering is the point: the configured port is one a device can be allocated, so a device's
 * own `adb forward` can be what answers the probe. Probing would report "another daemon owns this
 * port", exit 0, and hide a configuration error the user has to fix.
 *
 * @param cause the failure thrown out of the server start.
 * @param headless whether this instance was launched without a visible window.
 * @param probeForRivalDaemon whether a daemon responds on the contested port. Called at most once,
 *   and not at all for a configuration error.
 */
internal fun classifyPortBindFailure(
  cause: Throwable,
  headless: Boolean,
  probeForRivalDaemon: () -> Boolean,
): PortBindFailureAction = when {
  cause is TrailblazePortRangeConflictException -> PortBindFailureAction.ExitAsConfigError
  probeForRivalDaemon() -> PortBindFailureAction.ExitAsDuplicate(requestShowWindow = !headless)
  else -> PortBindFailureAction.ExitAsStartupFailure
}
