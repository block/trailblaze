package xyz.block.trailblaze.api

/**
 * [ScreenState] decorator that piggybacks an extra accessibility-shape [TrailblazeNode]
 * tree onto the driver's primary capture, used as the data source for the deterministic
 * Maestro→accessibility selector migration.
 *
 * The migration tool takes a legacy Maestro selector, resolves it against the primary
 * `viewHierarchy` (UiAutomator on the Maestro driver, accessibility-projected on the
 * accessibility driver) to get an on-screen coordinate, then hit-tests that coordinate
 * against [driverMigrationTreeNode] to pick a real `androidAccessibility`-shape selector.
 *
 * Why a decorator instead of a field on [ScreenState]: the primary [trailblazeNodeTree]
 * is consumed by every runtime tool, set-of-mark annotation, and report on this driver,
 * and changing its shape (or adding a parallel field on the interface) ripples through
 * call sites that have nothing to do with migration. The decorator pattern keeps the
 * migration tree strictly additive — runtime tools see the same `ScreenState` they always
 * have, and the migration capture path checks `is MigrationScreenState` to extract the
 * extra tree at log time.
 *
 * Construct via [wrap] when you have a primary [ScreenState] from any driver and a
 * separately-captured migration tree (e.g. from [MigrationTreeCapture] on Android, or the
 * same screen state's own tree on the accessibility driver where the primary IS the
 * migration target shape).
 */
class MigrationScreenState private constructor(
  val primary: ScreenState,
  /**
   * The accessibility-shape tree captured alongside [primary], or null if capture failed
   * (service not bound, query threw, etc.). Null is a valid wire/log value — it means the
   * snapshot exists but isn't usable for migration; migrate-trail will skip such snapshots
   * rather than fall back to the primary tree (which would defeat the migration purpose).
   */
  val driverMigrationTreeNode: TrailblazeNode?,
) : ScreenState by primary {

  companion object {
    /**
     * Wraps [primary] with a [MigrationScreenState] carrying [driverMigrationTreeNode].
     * If [primary] is already a [MigrationScreenState], unwraps and re-wraps with the new
     * migration tree to avoid stacking decorators.
     */
    fun wrap(primary: ScreenState, driverMigrationTreeNode: TrailblazeNode?): MigrationScreenState {
      val unwrapped = if (primary is MigrationScreenState) primary.primary else primary
      return MigrationScreenState(unwrapped, driverMigrationTreeNode)
    }
  }
}
