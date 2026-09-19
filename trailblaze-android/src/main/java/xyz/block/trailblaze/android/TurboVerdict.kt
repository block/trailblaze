package xyz.block.trailblaze.android

/**
 * How a trail's own outcome and the end-of-trail turbo check combine — see
 * [AndroidTrailblazeRule.afterTestExecution].
 *
 * [sessionResult] is what the session is ended with, and therefore what the report reports.
 * [failure] is the same throwable when there is one, kept separately because JUnit learns about it
 * by being thrown at, not by reading a result.
 */
internal data class TurboVerdict(
  val sessionResult: Result<Nothing?>,
  val failure: Throwable?,
)

/**
 * Runs [verifyTurbo] on a passing trail and folds its failure into the result the session is ended
 * with.
 *
 * The fold is the point. A turbo failure discovered here has to reach the session-end log, because
 * that log is what the report derives PASSED/FAILED from; a caller that ended the session with the
 * original success and only threw afterwards would produce a run that fails JUnit and reports green.
 *
 * [verifyTurbo] does not run on a trail that already failed: that failure is the real cause and
 * stays the one reported, and a trail that never finished says nothing about whether turbo held.
 */
internal fun turboVerdict(result: Result<Nothing?>, verifyTurbo: () -> Unit): TurboVerdict {
  val failure = if (result.isSuccess) runCatching { verifyTurbo() }.exceptionOrNull() else null
  return TurboVerdict(
    sessionResult = failure?.let { Result.failure(it) } ?: result,
    failure = failure,
  )
}
