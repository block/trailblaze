package xyz.block.trailblaze.host.recording

import io.ktor.util.encodeBase64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import maestro.Driver
import maestro.filterOutOfBounds
import okio.Buffer
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.recording.ScreenStateProvider
import xyz.block.trailblaze.utils.Ext.toViewHierarchyTreeNode
import xyz.block.trailblaze.viewmatcher.matching.toTrailblazeNode

/**
 * [ScreenStateProvider] for Maestro-backed devices (today: iOS via XCUITest). Wraps a Maestro
 * [Driver] and packages the same calls the legacy `MaestroDeviceScreenStream` was making —
 * `contentDescriptor`, `takeScreenshot`, `deviceInfo` — into a [GetScreenStateResponse] that
 * matches what the on-device Android server returns. That's the whole point of this layer:
 * after this, the recording surface (and the future WASM client) doesn't care whether the
 * device is iOS or Android.
 *
 * **Concurrency.** The [driverMutex] funnel is essential — Maestro's iOS driver talks to a
 * local XCUITest HTTP server that cannot handle concurrent requests; a tap firing while the
 * frame loop is taking a screenshot reliably crashes the connection ("Failed to connect to
 * /127.0.0.1:55545"). The mutex serializes the contentDescriptor + takeScreenshot pair this
 * provider issues so the frame loop and any external Maestro caller (e.g. a dispatched tap)
 * don't trample each other. Pass the SAME mutex instance to any other component that drives
 * the same Maestro driver — the screen stream's tap handler does this.
 */
class MaestroScreenStateProvider(
  private val driver: Driver,
  /**
   * Shared with the screen stream so its `tap` / `swipe` calls serialize through the same
   * mutex this provider uses for its `contentDescriptor + takeScreenshot` pair. Defaulting to
   * a fresh mutex would let taps race screenshots, which the iOS XCUITest server treats as
   * fatal.
   */
  internal val driverMutex: Mutex,
) : ScreenStateProvider {

  /** Cached at construction — Maestro's deviceInfo is stable for the driver's lifetime. */
  internal val deviceInfo = driver.deviceInfo()

  override suspend fun getScreenState(includeScreenshot: Boolean): GetScreenStateResponse? {
    return try {
      driverMutex.withLock {
        val rawTree = driver.contentDescriptor(false)
        val viewHierarchy = rawTree
          .filterOutOfBounds(width = deviceInfo.widthGrid, height = deviceInfo.heightGrid)
          ?.toViewHierarchyTreeNode()
          ?: ViewHierarchyTreeNode(nodeId = 0)
        val trailblazeNodeTree = rawTree.toTrailblazeNode(deviceInfo.platform)
        val screenshotBase64: String? = if (includeScreenshot) {
          Buffer().use { sink ->
            driver.takeScreenshot(sink, compressed = false)
            sink.readByteArray().encodeBase64()
          }
        } else {
          null
        }
        GetScreenStateResponse(
          viewHierarchy = viewHierarchy,
          screenshotBase64 = screenshotBase64,
          deviceWidth = deviceInfo.widthGrid,
          deviceHeight = deviceInfo.heightGrid,
          trailblazeNodeTree = trailblazeNodeTree,
        )
      }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      // Device may be transitioning (orientation change, app backgrounding). Caller in the
      // poll loop will retry on the next tick.
      null
    }
  }
}
