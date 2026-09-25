package xyz.block.trailblaze.util

/**
 * Polling utilities for waiting on conditions with a timeout.
 * Shared across JVM and Android source sets.
 */
object PollingUtils {

  /**
   * Milliseconds from an arbitrary origin, meaningful only as a difference between two readings.
   *
   * [System.nanoTime] and not a wall clock: a device that corrects its clock mid-wait — and a test
   * device syncing time after boot is the normal case, not an exotic one — would otherwise either
   * wait far past the caller's budget or give up early, failing a trail for a reason that has
   * nothing to do with the condition being polled. Same bug and same reasoning as
   * `InProcessIdleAttacher.PONG_DEADLINE_MS`, which measures against `SystemClock.elapsedRealtime`.
   *
   * Unlike `elapsedRealtime` this does NOT advance while the device is suspended in deep sleep.
   * That is deliberate and fine here rather than an oversight to clean up later: these loops only
   * run while a device is being actively driven, so it cannot suspend underneath them. It is also
   * the only option — this file compiles for the JVM too, where `elapsedRealtime` does not exist.
   */
  internal fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000

  /**
   * Polls [condition] every [intervalMs] milliseconds until it returns true or [maxWaitMs] elapses.
   * A condition that throws counts as "not yet met". Runs at least one attempt for any positive
   * [maxWaitMs].
   *
   * [maxWaitMs] bounds when the *last* attempt may START, not how long this call takes. An attempt
   * already in flight is never abandoned, so the real ceiling is `maxWaitMs` plus the duration of
   * one attempt — and for a condition that shells out to the device that second term dominates:
   * each on-device shell command is only bounded at
   * [AndroidShellBounds.SHELL_READ_TIMEOUT_MS][xyz.block.trailblaze.device.AndroidShellBounds.SHELL_READ_TIMEOUT_MS]
   * (300s) and each host one at
   * [AndroidShellBounds.HOST_SHELL_TIMEOUT_MS][xyz.block.trailblaze.device.AndroidShellBounds.HOST_SHELL_TIMEOUT_MS]
   * (330s), so a `maxWaitMs = 30_000` condition issuing two wedged commands can take ~630s to
   * return. Truncating a shell read mid-flight is worse than waiting for it — it leaves the
   * UiAutomation monitor held — so the guarantee here is deliberately the weaker one: no attempt
   * starts after the deadline, and no sleep is issued that the remaining budget cannot cover.
   *
   * @return true if the condition was met by an attempt that STARTED before [maxWaitMs] elapsed.
   *   Such an attempt may well have finished after the deadline — a 1ms budget still gets its one
   *   attempt, and that attempt returning true is a success.
   */
  fun tryUntilSuccessOrTimeout(
    maxWaitMs: Long,
    intervalMs: Long,
    conditionDescription: String,
    condition: () -> Boolean,
  ): Boolean = tryUntilSuccessOrTimeout(
    maxWaitMs = maxWaitMs,
    intervalMs = intervalMs,
    conditionDescription = conditionDescription,
    nowMs = ::monotonicNowMs,
    sleepMs = { Thread.sleep(it) },
    condition = condition,
  )

  /**
   * [tryUntilSuccessOrTimeout] with its clock and its sleep injected, so a test can pin the
   * deadline arithmetic — including the case of a single attempt that outlasts the whole budget —
   * without betting on real elapsed time.
   */
  internal fun tryUntilSuccessOrTimeout(
    maxWaitMs: Long,
    intervalMs: Long,
    conditionDescription: String,
    nowMs: () -> Long,
    sleepMs: (Long) -> Unit,
    condition: () -> Boolean,
  ): Boolean {
    val startMs = nowMs()
    var elapsedMs = 0L
    // Checked before every attempt: the budget decides whether another attempt may start.
    while (elapsedMs < maxWaitMs) {
      val conditionResult: Boolean =
        try {
          condition()
        } catch (e: Exception) {
          Console.log(
            "Ignored Exception while computing Condition [$conditionDescription], Exception [${e.message}]"
          )
          false
        }
      // Read the clock before logging or sleeping, so a slow attempt is counted against the
      // budget rather than reported at its pre-attempt elapsed time.
      elapsedMs = nowMs() - startMs
      if (conditionResult) {
        Console.log("Condition [$conditionDescription] met after ${elapsedMs}ms")
        return true
      }
      Console.log(
        "Condition [$conditionDescription] not yet met after ${elapsedMs}ms with timeout of ${maxWaitMs}ms"
      )
      // Stop rather than sleep out an interval no attempt could follow: waking at or past the
      // deadline would only exit the loop, and sleeping it out pushes the call past maxWaitMs for
      // nothing. Re-read the clock so the logging above cannot stale the decision.
      elapsedMs = nowMs() - startMs
      if (elapsedMs + intervalMs >= maxWaitMs) break
      sleepMs(intervalMs)
      elapsedMs = nowMs() - startMs
    }
    Console.log(
      "Timed out (${maxWaitMs}ms limit) met [$conditionDescription] after ${elapsedMs}ms"
    )
    return false
  }

  /**
   * Like [tryUntilSuccessOrTimeout], but throws an [IllegalStateException] if the timeout elapses.
   * The same ceiling caveat applies: [maxWaitMs] bounds when the last attempt may start, not how
   * long this call takes.
   */
  fun tryUntilSuccessOrThrowException(
    maxWaitMs: Long,
    intervalMs: Long,
    conditionDescription: String,
    condition: () -> Boolean,
  ) {
    val successful =
      tryUntilSuccessOrTimeout(
        maxWaitMs = maxWaitMs,
        intervalMs = intervalMs,
        conditionDescription = conditionDescription,
        condition = condition,
      )
    if (!successful) {
      error("Timed out (${maxWaitMs}ms limit) met [$conditionDescription]")
    }
  }
}
