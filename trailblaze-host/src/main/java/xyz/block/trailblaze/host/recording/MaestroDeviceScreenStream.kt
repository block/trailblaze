package xyz.block.trailblaze.host.recording

import io.ktor.util.decodeBase64Bytes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import maestro.Driver
import maestro.KeyCode
import maestro.Point
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.recording.DeviceScreenStream

/**
 * [DeviceScreenStream] backed by a Maestro [Driver] (today: iOS via XCUITest).
 * Screen-state queries are delegated to a [MaestroScreenStateProvider] so the resulting
 * shape matches what every other recording backend produces; tap/swipe/text input still
 * route directly to the Maestro driver because Maestro is the input channel for this
 * platform.
 *
 * **Concurrency contract.** Every call into the underlying [driver] funnels through a
 * shared [Mutex] — the same mutex the [provider] holds. iOS Maestro talks to XCUITest over
 * HTTP and that server cannot handle concurrent requests; a tap firing while the frame
 * loop is taking a screenshot reliably crashes the local XCUITest connection ("Failed to
 * connect to /127.0.0.1:55545"). Constructing the provider with the same mutex this
 * stream's input methods use serializes the four-or-five-deep RPC chain a single recorded
 * tap produces (contentDescriptor for the tree + takeScreenshot for the saved frame + tap,
 * with the frames() loop racing against all of those) into a clean queue.
 */
class MaestroDeviceScreenStream(
  private val driver: Driver,
  private val frameIntervalMs: Long = 200,
) : DeviceScreenStream {

  private val driverMutex = Mutex()

  // Provider holds the same mutex so its `contentDescriptor + takeScreenshot` pair
  // serializes against this stream's `tap` / `swipe` / `inputText` calls.
  private val provider = MaestroScreenStateProvider(
    driver = driver,
    driverMutex = driverMutex,
  )

  override val deviceWidth: Int get() = provider.deviceInfo.widthGrid
  override val deviceHeight: Int get() = provider.deviceInfo.heightGrid

  // Cache the most recent response so [getViewHierarchy] / [getTrailblazeNodeTree] /
  // [getScreenshot] don't each fire their own driver round-trip — the recorder issues
  // those back-to-back on every tap, and the provider already has a fresh capture from
  // the frames loop.
  @Volatile private var lastResponse: GetScreenStateResponse? = null

  override fun frames(): Flow<ByteArray> = flow {
    while (currentCoroutineContext().isActive) {
      val response = provider.getScreenState(includeScreenshot = true)
      if (response != null) {
        lastResponse = response
        response.screenshotBase64?.decodeBase64Bytes()?.let { emit(it) }
      }
      delay(frameIntervalMs)
    }
  }

  override suspend fun tap(x: Int, y: Int) = driverMutex.withLock {
    driver.tap(Point(x, y))
  }

  override suspend fun longPress(x: Int, y: Int) = driverMutex.withLock {
    driver.longPress(Point(x, y))
  }

  override suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long?) {
    // Caller-supplied duration reflects the actual user gesture on the host. Fall back to
    // a distance-scaled estimate when the caller doesn't know — keeps the previous
    // "fling = 100ms, long drag = 400ms" feel for any non-recording caller (snapshot CLI,
    // tests) that still uses the no-duration overload.
    val effectiveDurationMs = durationMs ?: run {
      val dx = (endX - startX).toFloat()
      val dy = (endY - startY).toFloat()
      val distance = kotlin.math.sqrt(dx * dx + dy * dy)
      val screenDiagonal = kotlin.math.sqrt(
        deviceWidth.toFloat() * deviceWidth + deviceHeight.toFloat() * deviceHeight,
      )
      (100 + (distance / screenDiagonal * 300)).toLong().coerceIn(100, 400)
    }

    driverMutex.withLock {
      driver.swipe(
        start = Point(startX, startY),
        end = Point(endX, endY),
        durationMs = effectiveDurationMs,
      )
    }
  }

  override suspend fun inputText(text: String) = driverMutex.withLock {
    driver.inputText(text)
  }

  override suspend fun pressKey(key: String) {
    val keyCode = when (key) {
      "Back" -> KeyCode.BACK
      "Enter" -> KeyCode.ENTER
      "Backspace" -> KeyCode.BACKSPACE
      "Tab" -> null // Maestro doesn't have a Tab keycode
      "Escape" -> null // Maestro doesn't have an Escape keycode
      else -> null
    }
    if (keyCode != null) {
      driverMutex.withLock { driver.pressKey(keyCode) }
    }
  }

  override suspend fun getViewHierarchy(): ViewHierarchyTreeNode {
    val cached = lastResponse?.viewHierarchy
    if (cached != null) return cached
    return refreshScreenState()?.viewHierarchy ?: ViewHierarchyTreeNode(nodeId = 0)
  }

  override suspend fun getTrailblazeNodeTree(): TrailblazeNode? {
    val cached = lastResponse?.trailblazeNodeTree
    if (cached != null) return cached
    return refreshScreenState()?.trailblazeNodeTree
  }

  override suspend fun getScreenshot(): ByteArray {
    val response = provider.getScreenState(includeScreenshot = true) ?: return ByteArray(0)
    lastResponse = response
    return response.screenshotBase64?.decodeBase64Bytes() ?: ByteArray(0)
  }

  private suspend fun refreshScreenState(): GetScreenStateResponse? {
    return provider.getScreenState(includeScreenshot = false)?.also { lastResponse = it }
  }
}
