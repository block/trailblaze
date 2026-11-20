package xyz.block.trailblaze.android.accessibility

/**
 * Observations must reach the UI before diagnostic work can consume a transient element's
 * lifetime. Inputs still need their pre-action snapshot so their coordinates describe the screen
 * they touched. [execute] returns the action outcome, including failures, so either path retains
 * a diagnostic snapshot.
 */
internal inline fun <Snapshot, Outcome> runWithDiagnosticSnapshot(
  observeFirst: Boolean,
  capture: () -> Snapshot,
  execute: () -> Outcome,
): Pair<Snapshot, Outcome> {
  if (observeFirst) {
    val outcome = execute()
    return capture() to outcome
  }
  val snapshot = capture()
  return snapshot to execute()
}
