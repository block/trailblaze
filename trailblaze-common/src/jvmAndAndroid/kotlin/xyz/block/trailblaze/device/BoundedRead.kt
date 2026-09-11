package xyz.block.trailblaze.device

import java.util.concurrent.atomic.AtomicBoolean
import xyz.block.trailblaze.util.Console

/**
 * Thrown when a read guarded by [readWithDeadline] did not finish inside its budget.
 *
 * [description] names the work that hung, because the whole point of the bound is that the caller
 * can say *which* call stopped rather than reporting a run that was mysteriously slow.
 *
 * Not API for consumers to build on, despite being public: this module is published, but the
 * declaration is reachable from `:trailblaze-android`'s device test, which asserts the one thing a
 * JVM double cannot — that closing the stream really does unblock a real Android pipe read. Kotlin
 * `internal` is module-scoped, so narrowing it would put that platform fact beyond any test that
 * runs on a device. Callers outside this module should keep reading whatever the bounded call site
 * raises — on the shell path, a stale-handle error that drives UiAutomation recovery.
 */
class BoundedReadTimeoutException(
  val description: String,
  val timeoutMs: Long,
  cause: Throwable? = null,
) : RuntimeException(
  "Timed out after ${timeoutMs}ms waiting for: $description",
  cause,
)

/**
 * Runs [read] with a wall-clock bound, using [cancel] to break the read out of its block.
 *
 * This exists for reads that cannot be interrupted from the outside — a blocking read on a pipe
 * fed by another process ignores `Thread.interrupt()`, so the only way to end it is to close the
 * stream underneath it. Hence the [cancel] parameter: the deadline thread does not kill the
 * reader, it removes what the reader is waiting on and lets the read fail normally.
 *
 * Abandoning the blocked thread instead would be worse than the hang it replaces. The reader
 * typically holds a shared lock (on Android, the UiAutomation monitor); a caller that gives up
 * while the reader stays parked leaves that lock held forever, so every later call queues behind
 * a call that has already been reported as failed. Unblocking the reader in place keeps the
 * failure local to the one call that hung.
 *
 * The bound is only as hard as [cancel] is: on Android, closing a `ParcelFileDescriptor` stream
 * does unblock a thread parked reading it, but a plain JVM pipe read is not guaranteed to wake on
 * close. A JVM caller whose `cancel` does not actually unblock the read gets a reported timeout no
 * sooner than the read ends on its own, so pair this with a [cancel] that is known to break the
 * specific block.
 *
 * @param description what is being waited on, quoted in the timeout message. Must already be safe
 *   to log — callers passing a shell command line should redact it first, and should pass a string
 *   they already had rather than redacting a second copy just for this.
 * @param timeoutMs the bound. Size it as hang detection, not as a performance budget.
 * @param cancel closes whatever [read] is blocked on. Runs at most once, from a daemon thread, and
 *   only when the deadline wins the claim — a read that finishes first is never cancelled, so the
 *   caller's resource is left alone. A failure from it is logged and does not replace the timeout.
 * @param read the blocking work.
 * @throws BoundedReadTimeoutException if the deadline passes before [read] returns.
 */
fun <T> readWithDeadline(
  description: String,
  timeoutMs: Long,
  cancel: () -> Unit,
  read: () -> T,
): T {
  require(timeoutMs > 0) { "timeoutMs must be positive, was $timeoutMs" }

  // A single atomic claim decides the call, so the read and the deadline can never both act.
  // Whoever wins the compare-and-set owns the outcome: the reader then knows `cancel` will never
  // run, and the deadline knows it may cancel and that the caller will be told the read timed out.
  //
  // A plain "expired" flag checked after the read is not enough. Interrupting the deadline thread
  // does not establish that it stopped: if its sleep has already expired but it has not set the
  // flag yet, the reader reads false, returns a value that may have been cut short, and the
  // deadline then cancels a resource the caller has already moved on from or reused.
  val claimed = AtomicBoolean(false)
  val deadlineThread = Thread {
    try {
      Thread.sleep(timeoutMs)
    } catch (interrupted: InterruptedException) {
      return@Thread
    }
    if (claimed.compareAndSet(false, true)) {
      runCatching { cancel() }.onFailure {
        // Not fatal — the timeout is reported either way — but worth saying, because a `cancel`
        // that throws before it frees the fd leaves the read parked and the bound stops being hard.
        Console.log("[boundedRead] cancelling '$description' failed: $it")
      }
    }
  }.apply {
    isDaemon = true
    name = "trailblaze-bounded-read"
    start()
  }

  val outcome = runCatching { read() }

  // Cancelling is how the deadline lands, so a timed-out read usually fails rather than returning.
  // But it can also RETURN, with whatever had already arrived, and a truncated result is
  // indistinguishable from a real one to every caller — so losing the claim fails the call either
  // way, keeping any exception as the cause so a genuine reason stays readable.
  if (!claimed.compareAndSet(false, true)) {
    throw timedOut(description, timeoutMs, deadlineThread, outcome.exceptionOrNull())
  }

  // Only the winning reader interrupts, and this is why it is not in a `finally`: interrupting on
  // the losing path would abort a `cancel` that is already running its own cleanup, and the join
  // inside [timedOut] would then return on a thread that never finished. Winning the claim is what
  // makes both lines below safe — `cancel` can no longer run, so the deadline thread has nothing
  // left to do but return, so this join cannot park on a blocked `cancel` and needs no bound. No
  // deadline thread outlives the call that started it.
  deadlineThread.interrupt()
  joinRestoringInterrupt(deadlineThread, budgetMs = 0)
  return outcome.getOrThrow()
}

/**
 * Waits for [thread] even when this thread is or becomes interrupted, restoring the interrupt flag
 * on the way out rather than reporting someone else's interruption as a failure of the read.
 * [budgetMs] of 0 waits indefinitely, per [Thread.join].
 *
 * Clearing the flag up front is the load-bearing part. `Thread.join` throws immediately when the
 * *calling* thread is already interrupted, so simply catching [InterruptedException] would skip
 * this wait entirely rather than perform it — and skip it precisely in the case it exists for. The
 * shell reader arrives here interrupted as a matter of course: its caller bounds the whole dispatch
 * and interrupts on the way out, while the pipe read ignores that interrupt and keeps going (the
 * reason [readWithDeadline] cancels by closing the stream at all). So a reader that reaches its
 * deadline in that state would report the timeout while `cancel` was still mid-close.
 */
private fun joinRestoringInterrupt(thread: Thread, budgetMs: Long) {
  var interrupted = Thread.interrupted()
  val deadlineNanos = System.nanoTime() + budgetMs * NANOS_PER_MS
  try {
    while (thread.isAlive) {
      val remainingMs = if (budgetMs == 0L) {
        0L
      } else {
        val remaining = (deadlineNanos - System.nanoTime()) / NANOS_PER_MS
        if (remaining <= 0) return
        remaining
      }
      try {
        thread.join(remainingMs)
      } catch (interruption: InterruptedException) {
        interrupted = true
      }
    }
  } finally {
    if (interrupted) Thread.currentThread().interrupt()
  }
}

private const val NANOS_PER_MS = 1_000_000L

/**
 * How long to wait for a winning deadline thread's [readWithDeadline] `cancel` to finish.
 *
 * Only reached when the deadline already won the claim, so this is not part of any read's budget.
 * It is bounded rather than an unqualified `join` so that a `cancel` which itself hangs cannot turn
 * a reported timeout into a second, longer hang.
 */
private const val CANCEL_SETTLE_MS = 5_000L

/**
 * Builds the timeout to throw once the deadline has won, waiting for its `cancel` to finish first.
 *
 * Without that wait a caller could catch the timeout and reuse the resource while `cancel` is still
 * touching it — the contract is that by the time this call returns or throws, `cancel` has either
 * already run to completion or will never run at all.
 */
private fun timedOut(
  description: String,
  timeoutMs: Long,
  deadlineThread: Thread,
  cause: Throwable?,
): BoundedReadTimeoutException {
  // Never interrupts the thread it is waiting on: it is running the `cancel` this wait exists for.
  joinRestoringInterrupt(deadlineThread, CANCEL_SETTLE_MS)
  return BoundedReadTimeoutException(description, timeoutMs, cause)
}
