package xyz.block.trailblaze.events

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps a capture delivering while an event query waits. Some drivers hand their events to
 * listeners only while something is calling into them (Playwright for Java has no event thread of
 * its own), so a wait that only sleeps would never see a request the page makes meanwhile. Such a
 * driver registers a pump for its session: a blocking call that lets the driver process its events
 * for the given time. A wait then runs the session's pumps instead of sleeping.
 */
object SessionEventsWaitPump {

  /** Longest one pump call blocks, so other work bridged onto the driver's thread can interleave. */
  private const val MAX_PUMP_MS = 250L

  private val bySession = ConcurrentHashMap<String, CopyOnWriteArrayList<(Long) -> Unit>>()

  fun register(sessionId: String, pump: (Long) -> Unit) {
    // Inside the map's update, so an unregister emptying the session cannot drop the list between
    // finding it and adding to it.
    bySession.compute(sessionId) { _, pumps -> (pumps ?: CopyOnWriteArrayList()).apply { addIfAbsent(pump) } }
  }

  fun unregister(sessionId: String, pump: (Long) -> Unit) {
    bySession.computeIfPresent(sessionId) { _, pumps -> pumps.apply { remove(pump) }.takeIf { it.isNotEmpty() } }
  }

  /** Waits [ms], running [sessionId]'s pumps meanwhile, or sleeping when it has none. */
  internal suspend fun waitFor(sessionId: String, ms: Long) {
    val pumps = bySession[sessionId]?.toList().orEmpty()
    if (pumps.isEmpty()) {
      delay(ms)
      return
    }
    var left = ms
    while (left > 0) {
      val slice = minOf(left, MAX_PUMP_MS)
      val started = System.nanoTime()
      coroutineScope {
        pumps.forEach { pump ->
          // Interruptible and timed out at the slice, so neither cancelling the query nor a pump
          // queued behind long work on the driver's thread can hold the wait past its deadline.
          launch { withTimeoutOrNull(slice) { runInterruptible(Dispatchers.IO) { runCatching { pump(slice) } } } }
        }
      }
      // A pump that failed or returned early still leaves the wait its full length.
      val elapsed = (System.nanoTime() - started) / 1_000_000
      if (elapsed < slice) delay(slice - elapsed)
      // Real time, not the slice: a pump queued behind other work on its thread can overrun.
      left -= maxOf(elapsed, slice)
    }
  }

  /**
   * Has [sessionId]'s drivers hand their listeners every event they already hold, so a listener
   * that reads session state on delivery (the trail phase) reads the state the event happened in.
   * Called before that state changes. Blocks for at most one pump slice; a driver that cannot
   * answer in time keeps its backlog for its next call.
   */
  fun deliverPending(sessionId: String) {
    val pumps = bySession[sessionId]?.toList().orEmpty()
    if (pumps.isEmpty()) return
    runBlocking {
      pumps.forEach { pump ->
        launch { withTimeoutOrNull(MAX_PUMP_MS) { runInterruptible(Dispatchers.IO) { runCatching { pump(0) } } } }
      }
    }
  }

  internal fun clear() = bySession.clear()
}
