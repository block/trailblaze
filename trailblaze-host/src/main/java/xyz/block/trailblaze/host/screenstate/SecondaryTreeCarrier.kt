package xyz.block.trailblaze.host.screenstate

import xyz.block.trailblaze.api.TrailblazeNode

/**
 * Outcome of one driver-migration side capture: the tree, or why there isn't one.
 *
 * The reason exists so a run with a broken `axe` says what is wrong once, instead of leaving
 * every snapshot silently missing its second tree.
 */
data class SecondaryTreeResult(
  val tree: TrailblazeNode?,
  /** Null when [tree] is present; a short human-readable cause otherwise. */
  val failureReason: String? = null,
  /**
   * True only when the cause cannot heal inside this run: the `axe` binary is missing, or older
   * than the minimum version. The producer stops calling the capture after one of these.
   *
   * False for every other failure — a non-zero exit, a describe-ui timeout, a subprocess or parse
   * throw. Those are specific to one screen at one moment, and the next capture routinely
   * succeeds, so treating them as terminal would turn a single slow frame into a whole session
   * with no migration data.
   */
  val permanent: Boolean = false,
) {
  companion object {
    /** No tree and no explanation — what a capture seam that simply returned null gives us. */
    val UNEXPLAINED: SecondaryTreeResult = SecondaryTreeResult(null, "capture returned no tree")

    fun of(tree: TrailblazeNode?): SecondaryTreeResult =
      if (tree == null) UNEXPLAINED else SecondaryTreeResult(tree, null)

    /** A failure the next capture may well not hit — see [permanent]. */
    fun failed(reason: String): SecondaryTreeResult = SecondaryTreeResult(null, reason)

    /** A failure every later capture in this run would hit too — see [permanent]. */
    fun unavailable(reason: String): SecondaryTreeResult =
      SecondaryTreeResult(null, reason, permanent = true)
  }
}

/**
 * A host screen state that ran the driver-migration side capture while it was being built.
 *
 * The two trees a migration compares have to describe the same instant, so the secondary capture
 * cannot be bolted on after the screen state exists — by then the screenshot (and, in stream mode,
 * a frame wait) has already let the screen move. Implementors run the capture inside their own
 * build and expose the result here.
 */
interface SecondaryTreeCarrier {
  /** True when a secondary capture was configured and ran (its result may still be null). */
  val secondaryTreeCaptureRan: Boolean

  /**
   * The secondary tree, captured right after the primary tree and before the screenshot.
   * Null = this snapshot is unusable for migration; [secondaryTreeFailure] says why.
   *
   * Named for the log field it ends up in (`TrailblazeSnapshotLog.driverMigrationTreeNode`) so
   * the producer and the reader share one word.
   */
  val driverMigrationTreeNode: TrailblazeNode?

  /** Why [driverMigrationTreeNode] is null, or null when it isn't (or no capture ran). */
  val secondaryTreeFailure: String?

  /**
   * Host wall-clock time stamped the moment the PRIMARY tree read returned, before the secondary
   * capture and before the screenshot. Stream-mode frame matching has to compare against this,
   * not against "now" after the build, or every frame looks stale by the cost of both captures.
   */
  val treeCapturedAtHostMs: Long
}
