package xyz.block.trailblaze.device

/**
 * The wall-clock bounds on a shell command, kept together because their *order* is the
 * load-bearing part and they are enforced from different source sets — the read bound by
 * `AdbCommandUtil` (androidMain, which is the only place that can hold the UiAutomation monitor),
 * the on-device dispatch bound by `AdbShellTrailblazeTool` (shared), and the host bound by
 * `AndroidDeviceCommandExecutor` (jvmMain). Independent numbers would drift back out of order
 * without anything failing.
 *
 * All of them are hang detection, not performance budgets. Nothing issued through these paths
 * should come close: the slowest real command on record is a `pm clear` that took 154s on an
 * Android tablet running turbo.
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

  /**
   * When a single shell command on the HOST transport (dadb → `adbd`) gives up.
   *
   * dadb's read has no deadline of its own, so without this a wedged device or a stale transport
   * blocks the calling thread until the session's ~13-minute inactivity watchdog fires — and the
   * failure then names the watchdog rather than the command that hung. The on-device transport has
   * been bounded since [ON_DEVICE_DISPATCH_TIMEOUT_MS]; this is the same guarantee for the host.
   *
   * Equal to [ON_DEVICE_DISPATCH_TIMEOUT_MS] rather than merely similar, so the same trail step
   * fails at the same wall clock whichever transport it ran on.
   *
   * What it bounds is the CALLER's wait, not the read. The read runs on a daemon worker that is
   * interrupted and abandoned, and an interrupt does not unblock a thread parked in a native socket
   * read, so that worker can outlive the bound until the socket faults. What makes this safe on the
   * host — and is why one bound suffices where the on-device path needs two — is that the abandoned
   * worker holds no process-wide monitor, unlike an on-device read holding UiAutomation. Nor does
   * the worker ever retry, so a command cannot land late. The bound does not make the NEXT call
   * any faster: every dadb command opens its own socket, so a transport that is still wedged makes
   * that one wait out its own bound too.
   *
   * It must also stay ABOVE the longest bound a caller of this transport enforces for itself,
   * which is [EnsureAppCompiled.COMPILE_TIMEOUT_MS] (300s) for `pm compile`. Equal numbers would
   * race, and the caller's message is the useful one: it names the app and the compiler filter,
   * where this one can only name the argv.
   */
  const val HOST_SHELL_TIMEOUT_MS: Long = ON_DEVICE_DISPATCH_TIMEOUT_MS

  /**
   * What is left of a [SHELL_READ_TIMEOUT_MS]-style budget after [elapsedMs] of it has been spent,
   * never negative.
   *
   * One `execShellCommand` can involve two reads — the command, then the liveness probe when it
   * answered nothing — and both hold the process-wide UiAutomation monitor. Giving the second read
   * a full bound of its own would let the call run for twice the deadline its caller sized, so the
   * second read is given this instead. Zero means the caller is out of time and the second read
   * must not be started at all, rather than being started with a bound of zero.
   */
  fun remainingAfter(budgetMs: Long, elapsedMs: Long): Long = (budgetMs - elapsedMs).coerceAtLeast(0L)
}
