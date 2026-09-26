package xyz.block.trailblaze.host.devices

import maestro.Driver
import maestro.MaestroException
import maestro.TreeNode
import kotlin.math.min

/**
 * Makes an iOS launch complete only once XCTest can produce the snapshot the next tool needs.
 *
 * XCTest can acknowledge `launchApp` while SpringBoard is still handing the app off. During that
 * short window `contentDescriptor` reports `Application <bundle id> is not running`, which Maestro
 * promotes to [MaestroException.AppCrash]. Maestro drops the XCTest response body while doing so,
 * leaving only the generic AppCrash type and message. The next automatic screen capture then fails
 * a launch that is visibly still in progress.
 *
 * AppCrash is tolerated only inside this launch-scoped, bounded readiness window. A persistent
 * AppCrash is rethrown unchanged rather than converted into success, and every other descriptor
 * failure is rethrown immediately.
 */
internal class IosLaunchReadinessDriver(
  private val delegate: Driver,
) : Driver by delegate {

  override fun launchApp(appId: String, launchArguments: Map<String, Any>) {
    delegate.launchApp(appId, launchArguments)
    awaitIosAppSnapshotReady(
      contentDescriptor = { delegate.contentDescriptor(false) },
    )
  }
}

/**
 * Polls the same XCTest hierarchy operation the post-tool screen capture will use.
 *
 * The clock and sleeper are injected for deterministic tests; production callers use a monotonic
 * clock so a simulator synchronizing its wall clock cannot shorten or extend this wait.
 */
internal fun awaitIosAppSnapshotReady(
  timeoutMs: Long = IOS_LAUNCH_READINESS_TIMEOUT_MS,
  pollIntervalMs: Long = IOS_LAUNCH_READINESS_POLL_INTERVAL_MS,
  nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
  sleepMs: (Long) -> Unit = { Thread.sleep(it) },
  contentDescriptor: () -> TreeNode,
) {
  require(timeoutMs >= 0) { "timeoutMs must not be negative" }
  require(pollIntervalMs > 0) { "pollIntervalMs must be positive" }

  val deadlineMs = nowMs() + timeoutMs
  var lastCrash: MaestroException.AppCrash? = null

  while (true) {
    try {
      // A successful snapshot is the contract the next automatic screen capture needs. Do not
      // impose a bundle-id check here: iOS can legitimately foreground a system permission sheet
      // over the app immediately after launch, and that UI must remain available to the trail.
      contentDescriptor()
      return
    } catch (failure: MaestroException.AppCrash) {
      lastCrash = failure
    }

    val remainingMs = deadlineMs - nowMs()
    if (remainingMs <= 0) break
    sleepMs(min(pollIntervalMs, remainingMs))
  }

  // Preserve Maestro's typed crash when XCTest kept failing throughout the bounded window.
  // Callers and reports already understand this error, and replacing it would hide the evidence.
  throw checkNotNull(lastCrash) { "Launch readiness exhausted without an XCTest AppCrash" }
}

internal const val IOS_LAUNCH_READINESS_TIMEOUT_MS = 15_000L
internal const val IOS_LAUNCH_READINESS_POLL_INTERVAL_MS = 250L
