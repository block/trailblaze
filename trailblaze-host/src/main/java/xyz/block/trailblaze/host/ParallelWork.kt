package xyz.block.trailblaze.host

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Bounded fan-out for `trailblaze check`'s independent per-item work: one `tsc` per trailmap, one
 * parse per trail. Results keep input order, so output and findings read exactly as a serial run's.
 */
internal object ParallelWork {

  /**
   * Upper bound on concurrent items. A trailmap's `tsc` peaks around 300 MB, and each phase's wall
   * clock is bounded by its longest item, so more workers stop paying off quickly.
   */
  const val MAX_PARALLELISM: Int = 4

  fun defaultParallelism(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_PARALLELISM)

  /**
   * Runs [work] over [items] on up to [parallelism] threads and returns the results in input order.
   * [onResult] runs on the calling thread, in input order, as soon as an item and every item before
   * it have finished, so a caller can print each item's block while later items still run. An
   * exception from [work] is rethrown as-is once the items before it have been delivered.
   */
  fun <T, R> map(
    items: List<T>,
    parallelism: Int = defaultParallelism(),
    onResult: (T, R) -> Unit = { _, _ -> },
    work: (T) -> R,
  ): List<R> {
    if (items.size <= 1 || parallelism <= 1) {
      return items.map { item -> work(item).also { onResult(item, it) } }
    }
    val pool = Executors.newFixedThreadPool(minOf(parallelism, items.size)) { runnable ->
      Thread(runnable, "trailblaze-check-worker").apply { isDaemon = true }
    }
    try {
      val futures = items.map { item -> pool.submit(Callable { work(item) }) }
      return items.mapIndexed { index, item ->
        val result = try {
          futures[index].get()
        } catch (e: ExecutionException) {
          throw e.cause ?: e
        }
        onResult(item, result)
        result
      }
    } finally {
      pool.shutdownNow()
    }
  }
}
