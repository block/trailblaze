package xyz.block.trailblaze.logs.model

/**
 * Groups sessions by test name and identifies the best result per test.
 *
 * When a test is retried, multiple sessions share the same [SessionInfo.displayName].
 * This groups them and picks the best result (preferring passed, then latest),
 * similar to the CI report dedup in GenerateTestResultsCliCommand.
 *
 * Usage:
 * ```
 * val groups = sessions.groupByTest()
 * val uniqueTests = groups.size
 * val retriedTests = groups.count { it.wasRetried }
 * val fallbackTests = groups.count { it.usedFallback }
 * ```
 */
data class SessionGroup(
  /** The best session result for this test (prefers passed, then latest) */
  val best: SessionInfo,
  /** All attempts in chronological order */
  val allAttempts: List<SessionInfo>,
  /** Whether any attempt of this test used AI fallback */
  val usedFallback: Boolean,
) {
  /** Whether this test was retried (more than one attempt) */
  val wasRetried: Boolean get() = allAttempts.size > 1

  /** Total number of attempts */
  val totalAttempts: Int get() = allAttempts.size

  /** Attempts that were superseded by the best result */
  val replacedAttempts: List<SessionInfo>
    get() = allAttempts.filter { it.sessionId != best.sessionId }

  /** Whether the best result is a pass (Succeeded or SucceededWithFallback) */
  val isPassed: Boolean
    get() = best.latestStatus is SessionStatus.Ended.Succeeded ||
      best.latestStatus is SessionStatus.Ended.SucceededWithFallback
}

/**
 * Groups sessions by [SessionInfo.displayName] and picks the best result per test.
 *
 * This provides a deduplicated view of sessions where retried tests appear once
 * (with the best outcome), while still preserving access to all attempts.
 */
fun List<SessionInfo>.groupByTest(): List<SessionGroup> {
  return groupBy { it.displayName }
    .values
    .map { attempts ->
      val sorted = attempts.sortedBy { it.timestamp }

      // Prefer the latest passed result; if none passed, take the last attempt
      val best = sorted.lastOrNull { session ->
        session.latestStatus is SessionStatus.Ended.Succeeded ||
          session.latestStatus is SessionStatus.Ended.SucceededWithFallback
      } ?: sorted.last()

      SessionGroup(
        best = best,
        allAttempts = sorted,
        usedFallback = sorted.any {
          it.latestStatus is SessionStatus.Ended.SucceededWithFallback ||
            it.latestStatus is SessionStatus.Ended.FailedWithFallback
        },
      )
    }
}

/**
 * Summary statistics computed from grouped sessions.
 *
 * Unlike raw session counts, these stats represent unique tests after deduplication,
 * with additional breakdowns for fallback usage and retries.
 */
data class GroupedSessionStats(
  /** Number of unique tests */
  val uniqueTests: Int,
  /** Total sessions (including retry attempts) */
  val totalSessions: Int,
  /** Tests whose best result is passed */
  val passed: Int,
  /** Tests whose best result is failed */
  val failed: Int,
  /** Tests that used AI fallback (subset of passed or failed) */
  val fallback: Int,
  /** Tests that were retried at least once */
  val retried: Int,
  /** Tests currently in progress */
  val inProgress: Int,
  /** Tests that timed out */
  val timeout: Int,
  /** Tests that hit max calls limit */
  val maxCalls: Int,
) {
  val completed get() = passed + failed + timeout + maxCalls
  val passRate: Float
    get() = if (completed > 0) passed.toFloat() / completed else 0f
}

/** Computes grouped statistics from session groups. */
fun List<SessionGroup>.computeGroupedStats(): GroupedSessionStats {
  var passed = 0
  var failed = 0
  var fallback = 0
  var retried = 0
  var inProgress = 0
  var timeout = 0
  var maxCalls = 0

  for (group in this) {
    if (group.wasRetried) retried++
    if (group.usedFallback) fallback++

    when (group.best.latestStatus) {
      is SessionStatus.Started -> inProgress++
      is SessionStatus.Ended.Succeeded,
      is SessionStatus.Ended.SucceededWithFallback -> passed++
      is SessionStatus.Ended.Failed,
      is SessionStatus.Ended.FailedWithFallback -> failed++
      is SessionStatus.Ended.TimeoutReached -> timeout++
      is SessionStatus.Ended.MaxCallsLimitReached -> maxCalls++
      is SessionStatus.Ended.Cancelled -> {} // not counted as a distinct category
      is SessionStatus.Unknown -> {}
    }
  }

  return GroupedSessionStats(
    uniqueTests = size,
    totalSessions = sumOf { it.allAttempts.size },
    passed = passed,
    failed = failed,
    fallback = fallback,
    retried = retried,
    inProgress = inProgress,
    timeout = timeout,
    maxCalls = maxCalls,
  )
}
