package xyz.block.trailblaze.host.recording.rpc

import io.ktor.util.encodeBase64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.host.rpc.GetHostDeviceScreenResponse
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.rpc.GetHostDeviceScreenRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.recording.DeviceScreenStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [GetHostDeviceScreenHandler]'s two-regime dispatch.
 *
 * The handler branches on [GetHostDeviceScreenRequest.includeTree]:
 *
 *  - `includeTree = false` — mirror fast path. Calls [DeviceScreenStream.getMirrorScreenshot]
 *    (skips the accessibility-tree walk on Android, dropping per-frame cost from 100-300 ms to
 *    30-60 ms) and returns `trailblazeNodeTree = null`.
 *
 *  - `includeTree = true` — recorder tap-time path. Calls [DeviceScreenStream.getScreenshot]
 *    (atomic — populates the stream's internal cache) and then [getTrailblazeNodeTree] (returns
 *    the tree paired with the screenshot in the just-completed on-device call). Both fields
 *    populated on the response.
 *
 * A silent regression where the atomic pair drifts (tree fetched from a stale call vs. the
 * just-captured screenshot) would break wasm-side selector generation. The web `/devices`
 * recorder would emit selectors that resolve to nodes that no longer exist on the screen the
 * user saw — replays would silently drift. These tests guard the atomicity contract by
 * counting [getScreenshot] vs. [getMirrorScreenshot] calls per regime.
 */
class GetHostDeviceScreenHandlerTest {

  private val deviceId = TrailblazeDeviceId("emulator-test", TrailblazeDevicePlatform.ANDROID)

  /**
   * Stub stream that records which capture path was taken on each call AND in what order.
   * Mirrors the production contract: [getScreenshot] is the atomic capture (populates an
   * internal cache that [getTrailblazeNodeTree] reads), [getMirrorScreenshot] is the cheap
   * display-only capture with no tree.
   *
   * **Call order matters for atomicity.** The atomic path requires
   * `getScreenshot` → `getTrailblazeNodeTree` (in that order), so the tree returned was
   * captured during the same on-device call as the screenshot. A regression that fetches
   * the tree FIRST would still satisfy "exactly one of each", but the tree would be the
   * one paired with the PREVIOUS screenshot — drift the web's selector generator would
   * silently resolve against a stale tree. The [callSequence] list records each call in
   * order so tests can assert the screenshot-before-tree invariant. Copilot caught this on
   * PR #3038's review.
   */
  private class StubStream(
    override val deviceWidth: Int = 1080,
    override val deviceHeight: Int = 1920,
    /** Bytes returned by [getScreenshot]. Caller-injected so tests can verify which path ran. */
    private val atomicScreenshotBytes: ByteArray = byteArrayOf(0x01, 0x02, 0x03),
    /** Bytes returned by [getMirrorScreenshot]. Different from [atomicScreenshotBytes] so the
     *  test can prove the handler picked the right capture method, not just "got bytes back". */
    private val mirrorScreenshotBytes: ByteArray = byteArrayOf(0x09, 0x08, 0x07),
    private val tree: TrailblazeNode? = null,
  ) : DeviceScreenStream {
    val atomicCalls = AtomicInteger(0)
    val mirrorCalls = AtomicInteger(0)
    val treeCalls = AtomicInteger(0)

    /** Append-only log of every capture-related call, in observation order. */
    val callSequence: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    override fun frames(): Flow<ByteArray> = emptyFlow()
    override suspend fun tap(x: Int, y: Int) {}
    override suspend fun longPress(x: Int, y: Int) {}
    override suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long?) {}
    override suspend fun inputText(text: String) {}
    override suspend fun pressKey(key: String) {}
    override suspend fun getViewHierarchy(): ViewHierarchyTreeNode = error("not used in these tests")
    override suspend fun getTrailblazeNodeTree(): TrailblazeNode? {
      treeCalls.incrementAndGet()
      callSequence.add("getTrailblazeNodeTree")
      return tree
    }

    override suspend fun getScreenshot(): ByteArray {
      atomicCalls.incrementAndGet()
      callSequence.add("getScreenshot")
      return atomicScreenshotBytes
    }

    override suspend fun getMirrorScreenshot(): ByteArray {
      mirrorCalls.incrementAndGet()
      callSequence.add("getMirrorScreenshot")
      return mirrorScreenshotBytes
    }
  }

  private fun managerWith(stream: DeviceScreenStream): HostDeviceSessionManager =
    HostDeviceSessionManager().apply {
      runBlocking { connectIfAbsent(deviceId) { stream } }
    }

  /** Minimal stand-in node — driverDetail required by the data class, content otherwise irrelevant. */
  private fun stubTree(): TrailblazeNode = TrailblazeNode(
    driverDetail = DriverNodeDetail.AndroidAccessibility(
      className = "android.widget.FrameLayout",
    ),
  )

  @Test
  fun `includeTree=false uses mirror screenshot path and returns null tree`() {
    val stream = StubStream(tree = stubTree())
    val handler = GetHostDeviceScreenHandler(managerWith(stream))
    val result = runBlocking { handler.handle(GetHostDeviceScreenRequest(deviceId, includeTree = false)) }
    val success = assertIs<RpcResult.Success<GetHostDeviceScreenResponse>>(result)
    val response = success.data
    assertEquals(1, stream.mirrorCalls.get(), "should hit getMirrorScreenshot exactly once")
    assertEquals(0, stream.atomicCalls.get(), "must NOT hit getScreenshot when includeTree=false")
    assertEquals(0, stream.treeCalls.get(), "must NOT fetch the tree when includeTree=false")
    assertNull(response.trailblazeNodeTree, "tree must be null on mirror path")
    assertEquals(byteArrayOf(0x09, 0x08, 0x07).encodeBase64(), response.screenshotBase64)
    assertEquals(1080, response.deviceWidth)
    assertEquals(1920, response.deviceHeight)
  }

  @Test
  fun `includeTree=true uses atomic screenshot and returns the paired tree`() {
    val tree = TrailblazeNode(
      children = listOf(
        TrailblazeNode(
          driverDetail = DriverNodeDetail.AndroidAccessibility(
            className = "android.widget.Button",
            text = "Submit",
          ),
        ),
      ),
      driverDetail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.FrameLayout",
      ),
    )
    val stream = StubStream(tree = tree)
    val handler = GetHostDeviceScreenHandler(managerWith(stream))
    val result = runBlocking { handler.handle(GetHostDeviceScreenRequest(deviceId, includeTree = true)) }
    val success = assertIs<RpcResult.Success<GetHostDeviceScreenResponse>>(result)
    val response = success.data
    // Atomicity contract: getScreenshot called exactly once (populates the stream's internal
    // cache), getTrailblazeNodeTree called exactly once *after* the screenshot so the tree
    // returned reflects the same on-device call. The mirror path must NOT be touched. A
    // future refactor that fetches the tree FIRST (or interleaves both screenshot methods)
    // would still pass a counter-only check but would silently break the synchronous-pair
    // guarantee — that's why we also assert the exact call ORDER via [callSequence].
    assertEquals(1, stream.atomicCalls.get(), "should hit getScreenshot exactly once")
    assertEquals(1, stream.treeCalls.get(), "should hit getTrailblazeNodeTree exactly once")
    assertEquals(0, stream.mirrorCalls.get(), "must NOT hit getMirrorScreenshot when includeTree=true")
    assertEquals(
      listOf("getScreenshot", "getTrailblazeNodeTree"),
      stream.callSequence.toList(),
      "atomic path must capture screenshot BEFORE reading the tree — reversing the order " +
        "would pair the just-captured screenshot with the tree from a PREVIOUS on-device call",
    )
    assertNotNull(response.trailblazeNodeTree, "tree must be populated on atomic path")
    assertEquals(byteArrayOf(0x01, 0x02, 0x03).encodeBase64(), response.screenshotBase64)
  }

  @Test
  fun `unknown device returns http_error`() {
    val handler = GetHostDeviceScreenHandler(HostDeviceSessionManager())
    val result = runBlocking {
      handler.handle(GetHostDeviceScreenRequest(deviceId, includeTree = false))
    }
    val failure = assertIs<RpcResult.Failure>(result)
    assertEquals(RpcResult.ErrorType.HTTP_ERROR, failure.errorType)
    assertTrue(
      failure.message.contains("Device not connected"),
      "error should explain the device wasn't connected (got: ${failure.message})",
    )
  }

  @Test
  fun `empty screenshot bytes return null screenshotBase64 not empty string`() {
    // Frame capture can transiently return zero bytes (Android `screenrecord` between
    // frames, iOS XCUITest socket flap). The handler converts those to a null base64
    // field so the wire response is "no frame this poll, retry" rather than
    // "successfully captured an empty image". The web client distinguishes the two
    // (it logs/retries on null, but would silently render an empty <img> on empty
    // base64). Pin the contract here.
    val stream = StubStream(
      atomicScreenshotBytes = ByteArray(0),
      mirrorScreenshotBytes = ByteArray(0),
    )
    val handler = GetHostDeviceScreenHandler(managerWith(stream))
    val mirror = runBlocking { handler.handle(GetHostDeviceScreenRequest(deviceId, includeTree = false)) }
    val atomic = runBlocking { handler.handle(GetHostDeviceScreenRequest(deviceId, includeTree = true)) }
    assertNull(assertIs<RpcResult.Success<GetHostDeviceScreenResponse>>(mirror).data.screenshotBase64)
    assertNull(assertIs<RpcResult.Success<GetHostDeviceScreenResponse>>(atomic).data.screenshotBase64)
  }
}
