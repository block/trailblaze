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
 * val selfHealTests = groups.count { it.usedSelfHeal }
 * ```
 */
data class SessionGroup(
  /**
   * The "best" session result for this test (prefers passed, then latest).
   *
   * Use [latest] when reporting what the user sees today (a Succeeded → Failed rerun should
   * read as Failed). Use [best] when reporting whether a test eventually passed (CI-style
   * "did this test ship green?"). They diverge whenever a test passed in an earlier run and
   * later failed.
   */
  val best: SessionInfo,
  /** All attempts in chronological order (oldest first). */
  val allAttempts: List<SessionInfo>,
  /** Whether any attempt of this test used self-heal */
  val usedSelfHeal: Boolean,
) {
  /** The most recent attempt for this test (chronologically last). */
  val latest: SessionInfo get() = allAttempts.last()

  /** Whether this test was retried (more than one attempt) */
  val wasRetried: Boolean get() = allAttempts.size > 1

  /** Total number of attempts */
  val totalAttempts: Int get() = allAttempts.size

  /** Attempts that were superseded by the best result */
  val replacedAttempts: List<SessionInfo>
    get() = allAttempts.filter { it.sessionId != best.sessionId }

  /** Whether the latest attempt is a pass (Succeeded or SucceededWithSelfHeal). */
  val isPassed: Boolean
    get() = latest.latestStatus is SessionStatus.Ended.Succeeded ||
      latest.latestStatus is SessionStatus.Ended.SucceededWithSelfHeal
}

/**
 * Groups sessions by [SessionInfo.stableTestKey] and device classifiers, then picks the best
 * result per group.
 *
 * This provides a deduplicated view of sessions where retried tests appear once
 * (with the best outcome), while still preserving access to all attempts.
 * Sessions on different devices are kept separate so that per-device pass/fail is accurate.
 */
fun List<SessionInfo>.groupByTest(): List<SessionGroup> {
  return groupBy { session ->
    val classifierKey = session.trailblazeDeviceInfo?.classifiers
      ?.joinToString(",") { it.classifier } ?: ""
    session.stableTestKey to classifierKey
  }
    .values
    .map { attempts ->
      val sorted = attempts.sortedBy { it.timestamp }

      // Prefer the latest passed result; if none passed, take the last attempt
      val best = sorted.lastOrNull { session ->
        session.latestStatus is SessionStatus.Ended.Succeeded ||
          session.latestStatus is SessionStatus.Ended.SucceededWithSelfHeal
      } ?: sorted.last()

      SessionGroup(
        best = best,
        allAttempts = sorted,
        usedSelfHeal = sorted.any {
          it.latestStatus is SessionStatus.Ended.SucceededWithSelfHeal ||
            it.latestStatus is SessionStatus.Ended.FailedWithSelfHeal
        },
      )
    }
}

/**
 * Summary statistics computed from grouped sessions.
 *
 * Unlike raw session counts, these stats represent unique tests after deduplication,
 * with additional breakdowns for self-heal usage and retries.
 */
data class GroupedSessionStats(
  /** Number of unique tests */
  val uniqueTests: Int,
  /** Total sessions (including retry attempts) */
  val totalSessions: Int,
  /** Tests whose most recent attempt passed */
  val passed: Int,
  /** Tests whose most recent attempt failed */
  val failed: Int,
  /** Tests that used self-heal (subset of passed or failed) */
  val selfHeal: Int,
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
  var selfHeal = 0
  var retried = 0
  var inProgress = 0
  var timeout = 0
  var maxCalls = 0

  for (group in this) {
    if (group.wasRetried) retried++
    if (group.usedSelfHeal) selfHeal++

    // Stats reflect what the user sees today — i.e. the most recent attempt — so a
    // Succeeded → Failed rerun counts as Failed. Use [SessionGroup.best] explicitly if you
    // need "did the test eventually pass" semantics.
    when (group.latest.latestStatus) {
      is SessionStatus.Started -> inProgress++
      is SessionStatus.Ended.Succeeded,
      is SessionStatus.Ended.SucceededWithSelfHeal -> passed++
      is SessionStatus.Ended.Failed,
      is SessionStatus.Ended.FailedWithSelfHeal -> failed++
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
    selfHeal = selfHeal,
    retried = retried,
    inProgress = inProgress,
    timeout = timeout,
    maxCalls = maxCalls,
  )
}
