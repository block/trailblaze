package xyz.block.trailblaze.android.accessibility

import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.util.Console

/**
 * Side-channel capture of the accessibility-shape [TrailblazeNode] tree used by the deterministic
 * Maestro→accessibility selector migration.
 *
 * The migration tool needs to take a legacy Maestro selector, resolve it against the
 * UiAutomator view hierarchy to get a coordinate, then hit-test that coordinate against the
 * REAL accessibility tree to choose an `androidAccessibility`-shape replacement selector.
 * Both trees must come from the same screen capture — capturing them in series risks the UI
 * shifting between the two reads.
 *
 * This helper is the producer side. It's deliberately decoupled from any [ScreenState]
 * implementation so neither the UiAutomator path nor the accessibility path needs to grow a
 * "migration mode" toggle. Callers (the on-device RPC handler and the in-process
 * `AndroidTrailblazeRule` snapshot path) invoke it explicitly when migration capture is
 * requested, and the result rides on the wire/log via dedicated fields rather than displacing
 * the driver's canonical `trailblazeNodeTree`.
 *
 * Returns null when [TrailblazeAccessibilityService] isn't bound or the query throws — the
 * caller is expected to log the snapshot anyway, just without the migration tree, so the
 * primary capture/log path is never blocked by a missing accessibility service.
 */
object MigrationTreeCapture {
  fun captureOrNull(): TrailblazeNode? {
    if (!TrailblazeAccessibilityService.isServiceRunning()) {
      Console.log(
        "[migration-tree] accessibility service not bound — driverMigrationTreeNode will be " +
          "null. Enable via OnDeviceAccessibilityServiceSetup.ensureAccessibilityServiceReady() " +
          "when running with trailblaze.captureSecondaryTree=true.",
      )
      return null
    }
    return try {
      val rootNodeInfo = TrailblazeAccessibilityService.getRootNodeInfo()
      val rawTree = rootNodeInfo?.toAccessibilityNode()?.toTrailblazeNode()
      // Filter to important-for-accessibility — same default as
      // AccessibilityServiceScreenState. The migration hit-test runs against this tree, and
      // unfiltered trees have lots of layout-only nodes that can shadow real targets.
      rawTree?.filterImportantForAccessibility().also {
        rootNodeInfo?.recycle()
      }
    } catch (e: Exception) {
      Console.log("[migration-tree] accessibility-tree capture failed: ${e.message}")
      null
    }
  }
}
