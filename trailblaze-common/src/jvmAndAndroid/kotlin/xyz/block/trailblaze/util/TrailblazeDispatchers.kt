package xyz.block.trailblaze.util

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Threads Trailblaze owns, for its own blocking work: log and screenshot uploads, its HTTP client,
 * its on-device RPC handlers, and the shell and file tools.
 *
 * Use this instead of `Dispatchers.IO` anywhere the code can run on a device. The in-process driver
 * runs inside the app under test, and there `Dispatchers.IO` is the APP's pool: one process, one
 * kotlinx-coroutines, one pool of 64 workers, shared by whatever the app's SDKs park on it. An SDK
 * that parks long-running work there can hold nearly all of them for minutes, so every Trailblaze
 * upload queued behind it and a single log write took 60–130 s. Nothing Trailblaze does can make
 * the app give those workers back, so Trailblaze's own work has to run somewhere the app cannot
 * reach.
 *
 * Unbounded on purpose. A cap is a second shared queue, and the work here is mostly blocked on a
 * socket, so a cap would let one slow upload hold up the next. Threads are created on demand,
 * reused, and retired after a minute idle, so a quiet process holds none.
 *
 * Daemon threads, so a finished test or CLI never waits on them to exit.
 */
object TrailblazeDispatchers {

  private val threadSeq = AtomicInteger(0)

  /** Trailblaze-owned replacement for `Dispatchers.IO`. See [TrailblazeDispatchers]. */
  val IO: CoroutineDispatcher by lazy {
    Executors.newCachedThreadPool { runnable ->
      Thread(runnable, "trailblaze-io-${threadSeq.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()
  }
}
