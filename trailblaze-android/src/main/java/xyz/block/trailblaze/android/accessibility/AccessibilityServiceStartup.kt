package xyz.block.trailblaze.android.accessibility

/** Roll back partial service enablement without replacing the setup failure. */
internal inline fun startAccessibilityServiceWithRollback(start: () -> Unit, rollback: () -> Unit) {
  try {
    start()
  } catch (failure: Throwable) {
    try {
      rollback()
    } catch (cleanupFailure: Throwable) {
      if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
    }
    throw failure
  }
}
