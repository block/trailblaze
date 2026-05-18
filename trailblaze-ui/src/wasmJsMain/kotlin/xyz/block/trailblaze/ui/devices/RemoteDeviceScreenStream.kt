package xyz.block.trailblaze.ui.devices

import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.host.rpc.DeviceInteraction
import xyz.block.trailblaze.host.rpc.ws.FrameEvent
import xyz.block.trailblaze.recording.DeviceScreenStream
import xyz.block.trailblaze.util.Console
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * [DeviceScreenStream] backed by the daemon's host device RPC API.
 *
 * **Frame delivery.** [frames] tries the WebSocket push path first: it sends a
 * `SubscribeFramesRequest` and consumes [FrameEvent]s that the daemon emits on the
 * shared WS event flow ([HostRpcClient.events]). If the WS path is unavailable (older
 * daemon, proxy strips WS upgrades, …), it falls back to the legacy HTTP poll loop —
 * same behaviour the page had before WS was introduced.
 *
 * The WS path is the visible win: no more 200 ms `GetHostDeviceScreenRequest` poll —
 * frames arrive as fast as the daemon can capture them, with no per-frame HTTP overhead.
 *
 * **Interaction methods** still use `client.sendInteraction(...)`. Gestures are
 * request/response (one tap = one RPC), so they ride the same WS path transparently
 * via [HostRpcClient]'s WS-first dispatch.
 *
 * [deviceWidth] / [deviceHeight] reflect the physical device screen size at connect
 * time and stay stable for the lifetime of the connection.
 */
class RemoteDeviceScreenStream(
  private val client: HostRpcClient,
  private val trailblazeDeviceId: TrailblazeDeviceId,
  override val deviceWidth: Int,
  override val deviceHeight: Int,
  /**
   * Producer-side capture cadence hint passed through to [HostRpcClient.subscribeFrames].
   *
   * Was 200 ms (≈ 5 fps cap) when each daemon-side capture was paying ~150-300 ms for a
   * full accessibility-tree walk it didn't need. With the on-device `includeTree=false`
   * fast path (see `GetScreenStateRequest.includeTree`) per-capture cost drops to ~30-60 ms,
   * so an 80 ms cadence (≈ 12 fps) fits comfortably within the budget without the loop
   * lapping itself. The activity-driven idle clamp still backs off to 1000 ms when nothing
   * changes on screen, so static screens don't pay for this faster active rate.
   */
  private val frameIntervalMs: Long = 80,
) : DeviceScreenStream {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  override fun frames(): Flow<ByteArray> = channelFlow {
    // Try the WS subscribe path. On success, the daemon pushes [FrameEvent]s on
    // `client.events`; on failure (returns null), fall back to the HTTP poll loop.
    val subscribed = try {
      client.subscribeFrames(trailblazeDeviceId, frameIntervalMs)
    } catch (e: Exception) {
      Console.log("[RemoteDeviceScreenStream] subscribe threw: ${e.message}, falling back to polling")
      null
    }

    if (subscribed != null) {
      // WS push path with stall watchdog. The shared event flow ([HostRpcClient.events]) is
      // a [MutableSharedFlow] that never completes — so if the socket dies after subscribe
      // succeeds, this collector would sit forever with no frames arriving and no chance to
      // recover. Codex P1 review on PR #3014 caught this freeze.
      //
      // Mitigation: track time-since-last-frame; if it exceeds [stallTimeoutMs], cancel the
      // WS collect and fall through to the HTTP poll path. The same active stream then
      // continues emitting frames, so the user sees a brief pause instead of a permanent
      // freeze. The next page load re-attempts WS.
      val stallTimeoutMs = (frameIntervalMs * STALL_TIMEOUT_FACTOR).coerceAtLeast(MIN_STALL_TIMEOUT_MS)
      val wsAlive = streamWsFrames(stallTimeoutMs)
      if (!wsAlive) {
        Console.log("[RemoteDeviceScreenStream] WS frame stream stalled, falling back to HTTP polling")
      } else {
        // wsAlive == true means the collector exited because the consumer cancelled (page
        // navigation, recomposition), not a stall — nothing more to do.
        return@channelFlow
      }
    }

    // HTTP fallback path: identical to the pre-WS behaviour. Reached on initial subscribe
    // failure OR after the WS stall watchdog tripped.
    while (currentCoroutineContext().isActive) {
      val bytes = fetchFrameHttp()
      if (bytes != null && bytes.isNotEmpty()) {
        send(bytes)
      }
      delay(frameIntervalMs)
    }
  }

  /**
   * Collect WS frames into [this] producer, watching for a stall. Returns `true` if the
   * collector exited because *we* (the consumer) cancelled (no fallback needed), or `false`
   * if the watchdog tripped because no frame arrived within [stallTimeoutMs] (caller should
   * fall back to HTTP polling).
   */
  private suspend fun ProducerScope<ByteArray>.streamWsFrames(stallTimeoutMs: Long): Boolean {
    var lastFrameAtMillis = currentTimeMillisCompat()
    var stalled = false
    val collectJob = launch {
      try {
        wsFrameFlow()
          .onCompletion {
            // Best-effort: tell the daemon we're done pushing. If the unsubscribe RPC fails
            // (socket already dead), the server-side cancellation falls back to the
            // socket-close cleanup, so leaks are bounded by connection lifetime.
            runCatching { client.unsubscribeFrames(trailblazeDeviceId) }
          }
          .collect { bytes ->
            lastFrameAtMillis = currentTimeMillisCompat()
            send(bytes)
          }
      } catch (e: Exception) {
        Console.log("[RemoteDeviceScreenStream] WS collect ended: ${e.message}")
      }
    }
    // Watchdog loop: poll on a fraction of the stall budget so detection lag is bounded.
    val pollIntervalMs = (stallTimeoutMs / 2).coerceAtLeast(500L)
    while (collectJob.isActive) {
      delay(pollIntervalMs)
      if (currentTimeMillisCompat() - lastFrameAtMillis > stallTimeoutMs) {
        stalled = true
        collectJob.cancel()
        break
      }
    }
    return !stalled
  }

  private fun currentTimeMillisCompat(): Long = Clock.System.now().toEpochMilliseconds()

  /**
   * Convert the shared [HostRpcClient.events] flow into raw PNG byte arrays for *this*
   * stream's device. Other devices' events are filtered out by id.
   */
  @OptIn(ExperimentalEncodingApi::class)
  private fun wsFrameFlow(): Flow<ByteArray> = client.events
    .mapNotNull { event ->
      if (event.path != FrameEvent.EVENT_PATH) return@mapNotNull null
      val frame = try {
        json.decodeFromJsonElement(FrameEvent.serializer(), event.body)
      } catch (e: Exception) {
        Console.log("[RemoteDeviceScreenStream] failed to decode FrameEvent body: ${e.message}")
        return@mapNotNull null
      }
      if (frame.trailblazeDeviceId != trailblazeDeviceId) return@mapNotNull null
      try {
        Base64.decode(frame.screenshotBase64)
      } catch (e: Exception) {
        Console.log("[RemoteDeviceScreenStream] failed to decode screenshot base64: ${e.message}")
        null
      }
    }

  @OptIn(ExperimentalEncodingApi::class)
  private suspend fun fetchFrameHttp(): ByteArray? {
    val response = client.getHostDeviceScreen(trailblazeDeviceId) ?: return null
    val base64 = response.screenshotBase64 ?: return null
    return try {
      Base64.decode(base64)
    } catch (e: Exception) {
      Console.log("[RemoteDeviceScreenStream] HTTP frame base64 decode failed: ${e.message}")
      null
    }
  }

  override suspend fun tap(x: Int, y: Int) {
    client.sendInteraction(trailblazeDeviceId, DeviceInteraction.Tap(x, y))
  }

  override suspend fun longPress(x: Int, y: Int) {
    client.sendInteraction(trailblazeDeviceId, DeviceInteraction.LongPress(x, y))
  }

  override suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long?) {
    client.sendInteraction(
      trailblazeDeviceId,
      DeviceInteraction.Swipe(startX, startY, endX, endY, durationMs),
    )
  }

  override suspend fun inputText(text: String) {
    client.sendInteraction(trailblazeDeviceId, DeviceInteraction.InputText(text))
  }

  override suspend fun pressKey(key: String) {
    client.sendInteraction(trailblazeDeviceId, DeviceInteraction.PressKey(key))
  }

  override suspend fun getViewHierarchy(): ViewHierarchyTreeNode =
    ViewHierarchyTreeNode(nodeId = 0)

  /**
   * Returns the tree captured atomically with the most recent [getScreenshot] call. The
   * recorder's tap handler calls [getScreenshot] first (which goes through the atomic
   * full-state path on the daemon) and then [getTrailblazeNodeTree] — both reads pull from
   * the same on-device call so the (screenshot, tree) pair stays synchronous, which is
   * what selector generation requires.
   */
  override suspend fun getTrailblazeNodeTree(): TrailblazeNode? = lastAtomicTree

  /**
   * Backing cache for [getTrailblazeNodeTree]. Populated by [getScreenshot]'s atomic-path
   * RPC response so each (screenshot, tree) read pair comes from a single daemon-side
   * `getScreenState` call. The mirror stream loop ([frames]) never touches this cache —
   * its frames are tree-less and shouldn't pollute the recorder's atomic snapshot.
   */
  // wasmJs is single-threaded; no @Volatile needed (and the annotation isn't available on
  // the wasm target). Reads and writes happen on the Compose dispatcher.
  private var lastAtomicTree: TrailblazeNode? = null

  @OptIn(ExperimentalEncodingApi::class)
  override suspend fun getScreenshot(): ByteArray {
    // Recorder tap-time path. Ask the daemon for the screenshot AND the tree in a single
    // atomic call so the selector generator runs on a tree that's truly synchronous with
    // the bitmap the user saw at tap moment. The cached tree is read back by
    // [getTrailblazeNodeTree] immediately after this returns.
    val response = client.getHostDeviceScreen(trailblazeDeviceId, includeTree = true)
      ?: return ByteArray(0)
    lastAtomicTree = response.trailblazeNodeTree
    val base64 = response.screenshotBase64 ?: return ByteArray(0)
    return try {
      Base64.decode(base64)
    } catch (e: Exception) {
      Console.log("[RemoteDeviceScreenStream] atomic-frame base64 decode failed: ${e.message}")
      ByteArray(0)
    }
  }

  private companion object {
    /**
     * Multiplier on [frameIntervalMs] for the no-frame-arrived stall budget. 5x active-cadence
     * tolerates the daemon's normal jitter (occasional 400-600 ms gaps when the device is
     * idle and dedup suppresses identical frames) without thrashing into HTTP fallback on
     * every brief lull. PR #3016's idle cadence is 1 s; this keeps the stall budget above it.
     */
    private const val STALL_TIMEOUT_FACTOR: Long = 5L

    /**
     * Lower bound on the stall budget. With [frameIntervalMs] = 200 ms and factor 5, the
     * computed budget is 1 s — below the worst-case GC/network jitter we've observed. 3 s
     * is comfortably above PR #3016's 1 s idle cadence plus a safety margin.
     */
    private const val MIN_STALL_TIMEOUT_MS: Long = 3_000L
  }
}

