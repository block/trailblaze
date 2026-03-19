package xyz.block.trailblaze.util

import kotlinx.datetime.Clock

/**
 * Polling utilities for waiting on conditions with a timeout.
 * Shared across JVM and Android source sets.
 */
object PollingUtils {

  /**
   * Polls [condition] every [intervalMs] milliseconds until it returns true or [maxWaitMs] elapses.
   *
   * @return true if the condition was met within the timeout, false otherwise
   */
  fun tryUntilSuccessOrTimeout(
    maxWaitMs: Long,
    intervalMs: Long,
    conditionDescription: String,
    condition: () -> Boolean,
  ): Boolean {
    val startTime = Clock.System.now()
    var elapsedTime = 0L
    while (elapsedTime < maxWaitMs) {
      val conditionResult: Boolean =
        try {
          condition()
        } catch (e: Exception) {
          Console.log(
            "Ignored Exception while computing Condition [$conditionDescription], Exception [${e.message}]"
          )
          false
        }
      if (conditionResult) {
        Console.log("Condition [$conditionDescription] met after ${elapsedTime}ms")
        return true
      } else {
        Console.log(
          "Condition [$conditionDescription] not yet met after ${elapsedTime}ms with timeout of ${maxWaitMs}ms"
        )
        Thread.sleep(intervalMs)
        elapsedTime = Clock.System.now().toEpochMilliseconds() - startTime.toEpochMilliseconds()
      }
    }
    Console.log(
      "Timed out (${maxWaitMs}ms limit) met [$conditionDescription] after ${elapsedTime}ms"
    )
    return false
  }

  /**
   * Like [tryUntilSuccessOrTimeout], but throws an [IllegalStateException] if the timeout elapses.
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
