package xyz.block.trailblaze.android.accessibility

import xyz.block.trailblaze.android.InstrumentationArgUtil

/**
 * Single source of truth for the on-device prerequisites of the deterministic
 * Maestro→accessibility selector migration. Both the JUnit-rule path
 * ([xyz.block.trailblaze.android.AndroidTrailblazeRule.beforeTestExecution] and any
 * downstream subclass override) and the on-device RPC server path
 * ([AndroidStandaloneServerTest.handleRunRequest]) call into this object so the migration
 * mode's setup requirements live in exactly one file.
 *
 * Today that prerequisite is just "accessibility service must be bound when
 * `trailblaze.captureSecondaryTree=true` is set" — without the bind,
 * [MigrationTreeCapture.captureOrNull] returns null and `driverMigrationTreeNode` never lands
 * on the snapshot/agent-driver/llm-request logs that `migrate-trail` consumes. Future
 * migration-mode requirements (timing tweaks, additional service-config knobs) should be
 * added here rather than re-introducing per-call-site copies.
 *
 * **Ordering caveat callers must respect:** UiDevice/shell operations that trigger
 * UiAutomation reconnections will tear down a running accessibility service. Call this
 * function *after* any such operations, not before — otherwise the binding is established
 * and then immediately destroyed. See the existing comment in
 * `AndroidTrailblazeRule.beforeTestExecution` for the underlying constraint.
 */
object MigrationCaptureSetup {
  /**
   * If migration mode is on (`trailblaze.captureSecondaryTree=true`), bind
   * [TrailblazeAccessibilityService] so the side-channel capture in [MigrationTreeCapture]
   * can read an accessibility-shape tree alongside the driver's primary screen state.
   * No-op when migration mode is off — the service stays in whatever state the active
   * driver left it.
   */
  fun ensureAccessibilityBoundIfMigrationModeOn() {
    if (InstrumentationArgUtil.shouldCaptureSecondaryTree()) {
      OnDeviceAccessibilityServiceSetup.ensureAccessibilityServiceReady()
    }
  }
}
