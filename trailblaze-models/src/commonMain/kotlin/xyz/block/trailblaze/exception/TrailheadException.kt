package xyz.block.trailblaze.exception

/**
 * Thrown when a trail's `trailhead:` - the deterministic bootstrap that reaches the trail's
 * starting state - fails. A trailhead failure means the trail never began: every subsequent
 * step would run against the wrong device state, so runners abort immediately (no self-heal)
 * instead of letting a later step fail with a misleading, unrelated assertion error.
 *
 * The type owns its wire format: [message] is always "[MESSAGE_PREFIX]: [headline]" followed
 * by [detail]. CI summaries surface only the first line of a failure reason, so [headline]
 * must be self-contained (say which tool/step failed); [detail] carries the full report
 * (tool call, underlying failure, stack trace).
 */
class TrailheadException(
  headline: String,
  detail: String = "",
  cause: Throwable? = null,
) : TrailblazeException("$MESSAGE_PREFIX: $headline\n$detail", cause) {
  companion object {
    /**
     * First-line marker kept in [message] so a trailhead failure reads loudly in raw logs.
     * Structured consumers should key off the report JSON's `failure_kind` field (== [KIND])
     * instead of matching this string; the prefix remains the fallback for reports written
     * before `failure_kind` existed.
     */
    const val MESSAGE_PREFIX = "TRAILHEAD FAILED"

    /**
     * Value carried in `SessionStatus.Ended.Failed.failureKind` and the report JSON's
     * `failure_kind` field for a trailhead failure. This is the structured signal CI
     * renderers dispatch on; a second failure kind that needs special rendering adds
     * another kind value here, not another [MESSAGE_PREFIX]-style string prefix.
     */
    const val KIND = "TRAILHEAD"
  }
}
