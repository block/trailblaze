package xyz.block.trailblaze.android.test

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import xyz.block.trailblaze.util.Console

/**
 * Logs the pre-tool snapshots off the test thread, one at a time, in the order they were captured.
 *
 * The capture stays on the test thread: a frame is only evidence if it is the screen the next tool
 * acts on. Encoding it, uploading it and emitting its log do not change what it shows, so the tool
 * no longer waits for them. [drain] is the barrier. A run calls it before it returns, so every
 * frame reaches the host before the session's end status does.
 *
 * At most [maxPending] snapshots wait at once, because each holds a full-resolution bitmap. A
 * capture past that waits for the oldest one first, and is dropped if the oldest still has not
 * finished, so a stalled upload cannot grow the queue.
 *
 * A drain that runs out abandons what is still queued and replaces the worker, so one upload that
 * never returns cannot hold up the snapshots of every later run this rule serves. The worker's
 * thread exits once idle, so a rule that is done with costs no thread.
 */
internal class StepSnapshotUploads(
  private val maxPending: Int = MAX_PENDING,
  private val drainTimeoutMs: Long = DRAIN_TIMEOUT_MS,
) {
  /** Guarded by `this`. */
  private var executor: ThreadPoolExecutor = newWorker()

  /** Guarded by `this`. */
  private val pending = ArrayDeque<Future<*>>()

  /** Queues [upload] behind the snapshots already waiting. A failed upload is logged, not thrown. */
  fun submit(upload: () -> Unit) {
    val oldest = synchronized(this) {
      pending.removeAll { it.isDone }
      if (pending.size >= maxPending) pending.first() else null
    }
    if (oldest != null && !awaitQuietly(oldest, drainTimeoutMs)) {
      Console.log("[Trailblaze] Step snapshot dropped: $maxPending uploads still pending after ${drainTimeoutMs}ms")
      return
    }
    synchronized(this) {
      val future = executor.submit {
        runCatching(upload).onFailure { e ->
          // Observational only: a missing frame must not fail the step it was documenting.
          Console.log("[Trailblaze] Could not log step snapshot: ${e.message}")
        }
      }
      pending.addLast(future)
    }
  }

  /** Waits until every snapshot submitted so far has been logged, or [drainTimeoutMs] runs out. */
  fun drain() {
    val waiting = synchronized(this) { pending.toList().also { pending.clear() } }
    val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(drainTimeoutMs)
    for (future in waiting) {
      val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime())
      if (!awaitQuietly(future, remainingMs)) {
        Console.log("[Trailblaze] Step snapshots still uploading after ${drainTimeoutMs}ms; abandoning them")
        abandon(waiting)
        return
      }
    }
  }

  private fun abandon(waiting: List<Future<*>>) {
    waiting.forEach { it.cancel(true) }
    val stalled = synchronized(this) { executor.also { executor = newWorker() } }
    stalled.shutdownNow()
  }

  /** False when [future] did not finish within [timeoutMs]. */
  private fun awaitQuietly(future: Future<*>, timeoutMs: Long): Boolean = try {
    future.get(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
    true
  } catch (_: TimeoutException) {
    false
  } catch (_: ExecutionException) {
    true
  } catch (_: CancellationException) {
    true
  }

  companion object {
    const val MAX_PENDING: Int = 3
    const val DRAIN_TIMEOUT_MS: Long = 30_000

    /** How long the worker thread stays alive with nothing to upload. */
    private const val IDLE_THREAD_KEEP_ALIVE_MS: Long = 10_000

    private fun newWorker(): ThreadPoolExecutor = ThreadPoolExecutor(
      1,
      1,
      IDLE_THREAD_KEEP_ALIVE_MS,
      TimeUnit.MILLISECONDS,
      LinkedBlockingQueue(),
    ) { runnable ->
      Thread(runnable, "trailblaze-step-snapshots").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
  }
}
