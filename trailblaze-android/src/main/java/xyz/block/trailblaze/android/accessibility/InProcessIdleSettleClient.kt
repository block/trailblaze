package xyz.block.trailblaze.android.accessibility

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import xyz.block.trailblaze.util.Console

/**
 * EXPERIMENTAL settle primitive: asks an in-process "in-process idle" — a tiny bare-`Instrumentation`
 * APK attached to the app under test — whether the app's main looper + Choreographer have
 * quiesced, instead of watching the accessibility event stream from the outside.
 *
 * The idle detector listens on localhost inside the target app's process (same device, different
 * process from this runner), so a plain TCP connect reaches it. Protocol is line-based:
 * `AWAIT_IDLE <timeoutMs>` -> `IDLE <elapsedMs>` | `TIMEOUT <elapsedMs>`.
 *
 * Deterministic and floor-free: an already-idle app answers in ~10ms (vs the event-quiet
 * heuristic's fixed quiet-window + grace floor per action), and a busy app holds the reply
 * until true quiescence rather than until an event lull.
 *
 * Opt-in per device via sysprop (read per call, flippable on a live runner):
 * `adb shell setprop debug.trailblaze.settle.inProcessIdle 1`. Any failure (idle detector not attached,
 * connect refused, malformed reply) makes [awaitIdle] return null and the caller falls back
 * to the standard settle path.
 */
object InProcessIdleSettleClient {

  private const val IN_PROCESS_IDLE_PORT = 7777
  private const val CONNECT_TIMEOUT_MS = 250

  private val getSysprop: java.lang.reflect.Method? by lazy {
    try {
      Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java, String::class.java)
    } catch (t: Throwable) {
      null
    }
  }

  private fun sysprop(name: String): String =
    try {
      getSysprop?.invoke(null, name, "") as? String ?: ""
    } catch (t: Throwable) {
      ""
    }

  /** True when `debug.trailblaze.settle.inProcessIdle` is `1`/`true`. Read per call, never cached. */
  fun isEnabled(): Boolean = parseEnabled(sysprop("debug.trailblaze.settle.inProcessIdle"))

  /** Pure parse of the sysprop value — extracted so the accepted values are unit-testable. */
  internal fun parseEnabled(raw: String): Boolean = raw.lowercase() in setOf("1", "true")

  /**
   * Cap on the idle detector arm inside a race. Bounds BOTH sides of an abandoned probe: the client
   * thread's blocking read, and — because the idle detector takes its deadline from the request — the
   * server-side await loop inside the app (whose main-handler posts would otherwise keep
   * nudging the very looper the next probe is trying to observe idle).
   */
  private const val RACE_IN_PROCESS_IDLE_CAP_MS = 2_000L

  /**
   * Settles via whichever signal answers FIRST: the idle detector's true-idle verdict or the standard
   * event-quiet [heuristic]. Strictly `min(idle detector, heuristic)` wall-clock — an app doing long
   * main-thread work the heuristic would have raced past still settles at heuristic speed, and
   * an already-idle app settles at idle detector speed instead of paying the heuristic's quiet-window
   * floor.
   *
   * The losing arm is CANCELLED, not abandoned: the heuristic receives an `earlyExit` probe it
   * must poll (its wait loop returns as soon as the idle detector wins), and the idle detector arm is bounded
   * by [RACE_IN_PROCESS_IDLE_CAP_MS] so a lost probe stops loading the app's main looper promptly.
   * Leftover polling threads from lost races were measurably worse than no race at all.
   *
   * [heuristic] returns whether it actually settled ([TrailblazeAccessibilityService.waitForSettled]'s
   * verdict) — a heuristic that timed out or was early-exited does NOT claim the win, so a genuine
   * settle timeout surfaces as `"timeout (neither arm settled)"` instead of masquerading as a
   * heuristic win in the logs. A heuristic that THROWS (e.g. a wedged UiAutomation handle) has its
   * error rethrown to the caller when the idle detector didn't win — the same propagation the non-race
   * path has — rather than being swallowed into a silent stall.
   *
   * Returns a description of the winning arm, for the caller's `[settle]` log line. The return
   * value is a log label only — every caller proceeds after this returns regardless of which arm
   * won (settling is best-effort with a cap, exactly as the non-race path is), so a
   * `"timeout (neither arm settled)"` result is the same capped-settle outcome the standard
   * heuristic already produces on timeout, never an earlier proceed.
   *
   * [inProcessIdleProbe] is injected (defaulting to [awaitIdle]) so the race contract can be unit-tested
   * without a device or a live idle detector socket. It sits before [heuristic] so callers keep passing
   * the heuristic as a trailing lambda.
   */
  fun raceIdleAgainstHeuristic(
    timeoutMs: Long,
    inProcessIdleProbe: (timeoutMs: Long) -> String? = ::awaitIdle,
    heuristic: (earlyExit: () -> Boolean) -> Boolean,
  ): String {
    val latch = java.util.concurrent.CountDownLatch(1)
    val winner = java.util.concurrent.atomic.AtomicReference<String>()
    val heuristicError = java.util.concurrent.atomic.AtomicReference<Throwable>()
    Thread {
      val reply = inProcessIdleProbe(minOf(timeoutMs, RACE_IN_PROCESS_IDLE_CAP_MS))
      if (reply != null && reply.startsWith("IDLE")) {
        winner.compareAndSet(null, "inprocess-idle $reply")
        latch.countDown()
      }
    }.apply {
      name = "trailblaze-inprocess-idle-race"
      isDaemon = true
      start()
    }
    Thread {
      try {
        if (heuristic { winner.get() != null }) {
          winner.compareAndSet(null, "event-quiet heuristic")
        }
      } catch (t: Throwable) {
        heuristicError.set(t)
      } finally {
        // The heuristic arm finishing — settled, timed out, early-exited, or thrown — always
        // releases the caller: the race must never wait LONGER than the heuristic alone would.
        latch.countDown()
      }
    }.apply {
      name = "trailblaze-heuristic-race"
      isDaemon = true
      start()
    }
    // Both arms self-bound by timeoutMs; the slack covers thread scheduling.
    latch.await(timeoutMs + 1_000, java.util.concurrent.TimeUnit.MILLISECONDS)
    winner.get()?.let { return it }
    // Neither arm won. If the heuristic DIED (vs timed out), the non-race path would have
    // propagated that error — preserve it instead of masking a device wedge as a settle timeout.
    heuristicError.get()?.let { throw it }
    return "timeout (neither arm settled)"
  }

  /** Sysprop ON but no idle detector attached logs on every settle (2×/tap) — say it once per outage. */
  private val loggedUnavailable = java.util.concurrent.atomic.AtomicBoolean(false)

  /**
   * Asks the idle detector to block until the app is idle (bounded by [timeoutMs]).
   * Returns the idle detector's reply line (`IDLE <ms>` / `TIMEOUT <ms>`) or null on any failure.
   */
  fun awaitIdle(timeoutMs: Long): String? = try {
    Socket().use { socket ->
      socket.connect(InetSocketAddress("127.0.0.1", IN_PROCESS_IDLE_PORT), CONNECT_TIMEOUT_MS)
      // Reply arrives when the app goes idle; pad the read bound past the idle detector's own deadline.
      socket.soTimeout = (timeoutMs + 1_000).toInt()
      socket.getOutputStream().write("AWAIT_IDLE $timeoutMs\n".toByteArray())
      socket.getOutputStream().flush()
      BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
    }.also { loggedUnavailable.set(false) }
  } catch (t: Throwable) {
    if (loggedUnavailable.compareAndSet(false, true)) {
      Console.log(
        "[inprocess-idle] unavailable (${t.javaClass.simpleName}: ${t.message}) — falling back" +
          " (logged once per outage; every settle keeps racing and falling back regardless)",
      )
    }
    null
  }
}
