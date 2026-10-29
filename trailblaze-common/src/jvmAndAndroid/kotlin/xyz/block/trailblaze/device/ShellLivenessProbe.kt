package xyz.block.trailblaze.device

import xyz.block.trailblaze.util.UiAutomationHandleErrors
import xyz.block.trailblaze.util.UiAutomationHandleErrors.SilentShellOutcome

/** Printed by the liveness probe; a live shell always echoes it back. */
internal const val SHELL_LIVENESS_TOKEN = "trailblaze-shell-liveness"

/**
 * Runs one shell command through [read] and tells an empty answer apart from a dead UiAutomation
 * connection.
 *
 * A dead connection makes every shell command return `""` instead of throwing, so every command
 * looks successful while doing nothing. Empty output is also normal for many commands (`cp`,
 * `input keyevent`), so an empty answer is followed by a probe that always prints. If even that
 * comes back empty, the connection is dead:
 * - [dropHandle] drops the cached handle and the probe runs again on a fresh connection.
 * - The command itself is never run again. The connection may have died after the command ran
 *   (BACK pressed, then the probe went silent), so a replay could repeat it. The call fails
 *   with [UiAutomationHandleErrors.silentShellMessage] instead, saying the command may not have run.
 * - If the fresh connection answers, that failure is ordinary and the runner carries on. If the
 *   handle cannot be dropped, or the fresh connection is silent too, the failure carries the
 *   signature the host relaunches the runner on: nothing in-process can bring the shell back.
 *
 * Every read draws on one [timeoutMs] budget, never a fresh one each: the reads hold the
 * process-wide UiAutomation monitor, so a second full bound would let one call hold it for twice
 * the deadline its caller sized. The clock starts here, so call this with the monitor already held:
 * time spent queued for the monitor must not come out of the command's budget. A spent budget skips
 * a probe: before the reconnect, the empty answer is returned (the honest reading for a caller out
 * of time); after it, the call fails as an ordinary handle drop, and the next command probes again.
 *
 * @param read runs a command with a read bound, on the current cached handle:
 *   `(command, loggableCommand, budgetMs) -> output`.
 * @param dropHandle drops the cached UiAutomation handle; false if it could not be dropped.
 * @param loggableCommand [shellCommand] already redacted for logging.
 */
fun readShellCheckingLiveness(
  shellCommand: String,
  loggableCommand: String,
  timeoutMs: Long,
  nowMs: () -> Long,
  read: (command: String, loggableCommand: String, budgetMs: Long) -> String,
  dropHandle: () -> Boolean,
  log: (String) -> Unit,
): String {
  val startedAtMs = nowMs()
  val remainingMs = { AndroidShellBounds.remainingAfter(timeoutMs, nowMs() - startedAtMs) }
  val livenessProbe = "echo $SHELL_LIVENESS_TOKEN"
  /** True if the shell answered, false if it was silent, null if no budget was left to ask. */
  fun probe(): Boolean? {
    val budgetMs = remainingMs()
    if (budgetMs <= 0) return null
    return read(livenessProbe, livenessProbe, budgetMs).contains(SHELL_LIVENESS_TOKEN)
  }

  val output = read(shellCommand, loggableCommand, remainingMs())
  if (output.isNotEmpty()) return output
  when (probe()) {
    true -> return output
    null -> {
      log("shell read budget (${timeoutMs}ms) spent by `$loggableCommand`; skipping the liveness probe")
      return output
    }
    false -> Unit
  }

  log("shell went silent after `$loggableCommand`; dropping the UiAutomation handle")
  if (!dropHandle()) {
    throw IllegalStateException(
      UiAutomationHandleErrors.silentShellMessage(loggableCommand, SilentShellOutcome.CACHE_CLEAR_FAILED),
    )
  }
  val freshAnswered = try {
    probe()
  } catch (e: RuntimeException) {
    throw IllegalStateException(
      UiAutomationHandleErrors.silentShellMessage(loggableCommand, SilentShellOutcome.RECONNECT_FAILED) +
        " Reconnecting threw: ${e.message}",
      e,
    )
  }
  val outcome =
    if (freshAnswered == false) SilentShellOutcome.RECONNECT_FAILED else SilentShellOutcome.HANDLE_DROPPED
  throw IllegalStateException(UiAutomationHandleErrors.silentShellMessage(loggableCommand, outcome))
}
