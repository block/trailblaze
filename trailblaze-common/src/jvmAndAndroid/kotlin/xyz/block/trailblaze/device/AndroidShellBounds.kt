package xyz.block.trailblaze.device

/**
 * The two wall-clock bounds on an on-device shell command, kept together because their *order*
 * is the load-bearing part and they are enforced from different source sets — the read bound by
 * `AdbCommandUtil` (androidMain, which is the only place that can hold the UiAutomation monitor)
 * and the dispatch bound by `AdbShellTrailblazeTool` (shared). Two independent numbers would drift
 * back out of order without anything failing.
 *
 * Both are hang detection, not performance budgets. Nothing issued through this path should come
 * close: the slowest real command on record is a `pm clear` that took 154s on an Android tablet
 * running turbo.
 */
object AndroidShellBounds {

  /**
   * When a single shell command's output stream is treated as never coming back, at which point
   * the read is cancelled by closing the stream underneath it.
   *
   * This is the bound that matters, because it is the only one that ends the read. The read runs
   * inside `withUiAutomation`, holding the process-wide UiAutomation monitor, so until it unwinds
   * every device action in the process is queued behind it.
   *
   * Raise this before tightening it — a bound anywhere near the 154s above turns a slow pass into
   * a red build rather than catching a hang.
   */
  const val SHELL_READ_TIMEOUT_MS: Long = 300_000L

  /**
   * When a single `android_adbShell` dispatch on the on-device transport gives up.
   *
   * Derived from [SHELL_READ_TIMEOUT_MS] rather than chosen, and it must stay ABOVE it. Reporting
   * first would be a false economy: this bound abandons the reader instead of ending it, so the
   * monitor stays held and the agent's very next device action blocks anyway — it just blocks
   * without anything saying why, having already been told this call failed. Letting the read bound
   * land first means the failure names the command that hung and the device is usable again when
   * the agent gets it.
   *
   * The slack only covers the read bound's own cancel-and-unwind, so this stays a backstop for a
   * dispatch that wedges somewhere other than the read. It is still far inside the session's
   * ~13-minute inactivity watchdog, which is the outermost bound of all.
   */
  const val ON_DEVICE_DISPATCH_TIMEOUT_MS: Long = SHELL_READ_TIMEOUT_MS + 30_000L
}
