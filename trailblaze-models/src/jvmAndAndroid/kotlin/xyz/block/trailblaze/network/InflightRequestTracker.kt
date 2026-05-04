package xyz.block.trailblaze.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Engine-agnostic foundation for "is the network quiet?" introspection.
 *
 * Both the Playwright web capture engine and on-device mobile capture engines
 * update the same tracker so cross-platform idling tools (`wait_for_network_idle`,
 * `wait_for_request`) can be authored once against this contract instead of
 * branching per engine.
 *
 * Engines call [onRequestStart] when they observe a new request and
 * [onRequestEnd] when that request completes (success OR failure). The id is
 * whatever the engine already mints for its own logging (e.g. the
 * [NetworkEvent.id] for Playwright); the tracker doesn't care about origin or
 * format — only that start/end IDs match.
 *
 * ### Module placement
 * Lives in `jvmAndAndroid` rather than `commonMain` because the thread-safe
 * primitives ([ConcurrentHashMap], [AtomicLong]) it needs are JVM/Android-only.
 * The roadmap targets here are Playwright (JVM) and on-device Android;
 * iOS capture is expected to ship as Swift code that writes NDJSON, so a
 * Kotlin commonMain footprint isn't needed.
 *
 * ### Thread safety
 * All public methods are safe to call from any thread without external
 * synchronization. Playwright's listener thread is single, but the on-device
 * mobile callback thread varies and cross-platform consumers may run on still
 * other threads — non-suspending thread-safe primitives keep the contract
 * simple for callers.
 */
class InflightRequestTracker {

  private val inflight: ConcurrentHashMap<String, String> = ConcurrentHashMap()
  private val lastActivityMs: AtomicLong = AtomicLong(System.currentTimeMillis())

  /**
   * Records that the request identified by [id] (with target [url]) is now in
   * flight. If [id] was already known, the new [url] replaces the old — useful
   * for engines that re-issue under the same id (rare, but defensive).
   */
  fun onRequestStart(id: String, url: String) {
    inflight[id] = url
    lastActivityMs.set(System.currentTimeMillis())
  }

  /**
   * Records that the request identified by [id] is no longer in flight (it
   * completed, errored, or was cancelled — the tracker treats them all the
   * same). No-op when [id] is unknown so engines can call this defensively
   * without coordinating with start.
   */
  fun onRequestEnd(id: String) {
    if (inflight.remove(id) != null) {
      lastActivityMs.set(System.currentTimeMillis())
    }
  }

  /**
   * Number of currently in-flight requests across all engines that update
   * this tracker. Primarily useful for diagnostics; consumers should prefer
   * [isIdle] for control flow.
   */
  fun inflightCount(): Int = inflight.size

  /**
   * True iff there are no in-flight requests AND the most recent start/end
   * happened at least [quietPeriodMs] ago. A [quietPeriodMs] of 0 means
   * "quiet right now"; positive values let idling callers wait past the
   * cliff between two requests in a chatty session before treating things as
   * settled.
   */
  fun isIdle(quietPeriodMs: Long = 0L): Boolean {
    if (inflight.isNotEmpty()) return false
    if (quietPeriodMs <= 0L) return true
    return System.currentTimeMillis() - lastActivityMs.get() >= quietPeriodMs
  }

  /**
   * Returns the IDs of in-flight requests whose URL matches [urlRegex].
   * Snapshots the live map at call time; the returned list is immutable and
   * won't reflect subsequent start/end activity.
   */
  fun inflightMatching(urlRegex: Regex): List<String> =
    inflight.entries
      .filter { urlRegex.containsMatchIn(it.value) }
      .map { it.key }

  /**
   * Drops all tracked state. Engines should call this on session boundary so
   * a stale request that crashed mid-flight in the previous session can't
   * pin idle false forever.
   */
  fun reset() {
    inflight.clear()
    lastActivityMs.set(System.currentTimeMillis())
  }
}
