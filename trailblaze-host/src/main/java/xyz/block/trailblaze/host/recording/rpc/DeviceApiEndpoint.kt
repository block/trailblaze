package xyz.block.trailblaze.host.recording.rpc

import io.ktor.server.routing.Routing
import io.ktor.util.encodeBase64
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.compose.driver.rpc.RpcWsHandlerRegistry
import xyz.block.trailblaze.compose.driver.rpc.WsSessionContext
import xyz.block.trailblaze.compose.driver.rpc.registerRpcHandler
import xyz.block.trailblaze.compose.driver.rpc.registerRpcWebSocket
import xyz.block.trailblaze.host.rpc.ConnectToDeviceRequest
import xyz.block.trailblaze.host.rpc.ConnectToDeviceResponse
import xyz.block.trailblaze.host.rpc.DeviceInteractionRequest
import xyz.block.trailblaze.host.rpc.DeviceInteractionResponse
import xyz.block.trailblaze.host.rpc.DisconnectDeviceRequest
import xyz.block.trailblaze.host.rpc.DisconnectDeviceResponse
import xyz.block.trailblaze.host.rpc.GetConnectedDevicesRequest
import xyz.block.trailblaze.host.rpc.GetConnectedDevicesResponse
import xyz.block.trailblaze.host.rpc.GetHostDeviceScreenRequest
import xyz.block.trailblaze.host.rpc.GetHostDeviceScreenResponse
import xyz.block.trailblaze.host.rpc.GetTargetAppsRequest
import xyz.block.trailblaze.host.rpc.GetTargetAppsResponse
import xyz.block.trailblaze.host.rpc.GetToolCatalogRequest
import xyz.block.trailblaze.host.rpc.GetToolCatalogResponse
import xyz.block.trailblaze.host.rpc.NavigateWebUrlRequest
import xyz.block.trailblaze.host.rpc.NavigateWebUrlResponse
import xyz.block.trailblaze.host.rpc.RunTrailYamlRequest
import xyz.block.trailblaze.host.rpc.RunTrailYamlResponse
import xyz.block.trailblaze.host.rpc.SetCurrentTargetAppRequest
import xyz.block.trailblaze.host.rpc.SetCurrentTargetAppResponse
import xyz.block.trailblaze.host.rpc.ws.FrameEvent
import xyz.block.trailblaze.host.rpc.ws.RpcWsEnvelope
import xyz.block.trailblaze.host.rpc.ws.SubscribeFramesRequest
import xyz.block.trailblaze.host.rpc.ws.SubscribeFramesResponse
import xyz.block.trailblaze.host.rpc.ws.UnsubscribeFramesRequest
import xyz.block.trailblaze.host.rpc.ws.UnsubscribeFramesResponse
import xyz.block.trailblaze.capture.video.AndroidVideoCapture
import xyz.block.trailblaze.capture.video.H264Tee
import xyz.block.trailblaze.capture.video.LiveFrameConsumer
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.Console

/**
 * Registers the host-side device API on the daemon's Ktor server. Mirrors the pattern
 * used by [xyz.block.trailblaze.graph.WaypointGraphEndpoint] — the server doesn't know
 * about host types, so registration is inverted into a callback
 * (`additionalRouteRegistration`) that fires inside the server's routing block.
 *
 * **Two transports, same handlers.** Each `RpcHandler` is registered against both the HTTP
 * POST route (e.g. `/rpc/GetConnectedDevicesRequest`) and the multiplexed WebSocket
 * (`/rpc-ws`). Both transports accept the same `TRequest` JSON body and emit the same
 * `TResponse` JSON. The web `/devices` viewer prefers WS because:
 *   - it eliminates the 200 ms `GetHostDeviceScreenRequest` poll loop in favor of
 *     server-pushed [FrameEvent]s ([SubscribeFramesRequest] starts the stream), and
 *   - it sets up the Phase 3+ session-log subscription on a transport that already
 *     supports push.
 *
 * The HTTP routes stay registered so MCP, the CLI, and curl smoke-tests keep working
 * unchanged. Don't remove them.
 *
 * Routes (HTTP POST, path derived from request class name via [toRpcPath]):
 *   /rpc/GetConnectedDevicesRequest — list devices visible to the daemon
 *   /rpc/ConnectToDeviceRequest     — open a live connection, hold stream in session manager
 *   /rpc/GetHostDeviceScreenRequest — poll a single frame from an active connection
 *   /rpc/DeviceInteractionRequest   — dispatch tap/swipe/text/key to an active connection
 *   /rpc/DisconnectDeviceRequest    — release a connection from the session manager
 *   /rpc/GetTargetAppsRequest       — list configured target apps
 *   /rpc/SetCurrentTargetAppRequest — switch the active target app
 *   /rpc/NavigateWebUrlRequest      — Playwright URL navigation
 *   /rpc/GetToolCatalogRequest      — Tool palette catalog
 *   /rpc/RunTrailYamlRequest        — execute a recording-tab trail YAML
 *
 * WebSocket (single connection, dispatches all the above + the streaming RPCs):
 *   /rpc-ws — multiplexed RPC channel. See [RpcWsEnvelope] for the wire format.
 *   /rpc/SubscribeFramesRequest     — start server-pushed frames (WS-only — no HTTP route).
 *   /rpc/UnsubscribeFramesRequest   — stop server-pushed frames (WS-only — no HTTP route).
 */
object DeviceApiEndpoint {

  fun register(
    routing: Routing,
    deviceManager: TrailblazeDeviceManager,
    sessionManager: HostDeviceSessionManager,
  ) {
    val getConnectedDevicesHandler = GetConnectedDevicesHandler(deviceManager)
    val connectToDeviceHandler = ConnectToDeviceHandler(
      deviceManager = deviceManager,
      connectionService = deviceManager.connectionService,
      sessionManager = sessionManager,
    )
    val getHostDeviceScreenHandler = GetHostDeviceScreenHandler(sessionManager)
    val deviceInteractionHandler = DeviceInteractionHandler(sessionManager)
    val disconnectDeviceHandler = DisconnectDeviceHandler(sessionManager)
    val getTargetAppsHandler = GetTargetAppsHandler(deviceManager)
    val setCurrentTargetAppHandler = SetCurrentTargetAppHandler(deviceManager)
    val navigateWebUrlHandler = NavigateWebUrlHandler(sessionManager)
    val getToolCatalogHandler = GetToolCatalogHandler(deviceManager)
    val runTrailYamlHandler = RunTrailYamlHandler(deviceManager, sessionManager)

    routing.apply {
      // ---------- HTTP transport (unchanged for MCP / CLI / curl callers) ----------
      registerRpcHandler<GetConnectedDevicesResponse, GetConnectedDevicesRequest>(getConnectedDevicesHandler)
      registerRpcHandler<ConnectToDeviceResponse, ConnectToDeviceRequest>(connectToDeviceHandler)
      registerRpcHandler<GetHostDeviceScreenResponse, GetHostDeviceScreenRequest>(getHostDeviceScreenHandler)
      registerRpcHandler<DeviceInteractionResponse, DeviceInteractionRequest>(deviceInteractionHandler)
      registerRpcHandler<DisconnectDeviceResponse, DisconnectDeviceRequest>(disconnectDeviceHandler)
      registerRpcHandler<GetTargetAppsResponse, GetTargetAppsRequest>(getTargetAppsHandler)
      registerRpcHandler<SetCurrentTargetAppResponse, SetCurrentTargetAppRequest>(setCurrentTargetAppHandler)
      registerRpcHandler<NavigateWebUrlResponse, NavigateWebUrlRequest>(navigateWebUrlHandler)
      registerRpcHandler<GetToolCatalogResponse, GetToolCatalogRequest>(getToolCatalogHandler)
      registerRpcHandler<RunTrailYamlResponse, RunTrailYamlRequest>(runTrailYamlHandler)

      // ---------- WebSocket transport (preferred by the web `/devices` viewer) ----------
      val wsRegistry = RpcWsHandlerRegistry().apply {
        register<GetConnectedDevicesResponse, GetConnectedDevicesRequest>(getConnectedDevicesHandler)
        register<ConnectToDeviceResponse, ConnectToDeviceRequest>(connectToDeviceHandler)
        register<GetHostDeviceScreenResponse, GetHostDeviceScreenRequest>(getHostDeviceScreenHandler)
        register<DeviceInteractionResponse, DeviceInteractionRequest>(deviceInteractionHandler)
        register<DisconnectDeviceResponse, DisconnectDeviceRequest>(disconnectDeviceHandler)
        register<GetTargetAppsResponse, GetTargetAppsRequest>(getTargetAppsHandler)
        register<SetCurrentTargetAppResponse, SetCurrentTargetAppRequest>(setCurrentTargetAppHandler)
        register<NavigateWebUrlResponse, NavigateWebUrlRequest>(navigateWebUrlHandler)
        register<GetToolCatalogResponse, GetToolCatalogRequest>(getToolCatalogHandler)
        register<RunTrailYamlResponse, RunTrailYamlRequest>(runTrailYamlHandler)

        // ----- Streaming RPCs (WS-only) — start/stop server-pushed frame events -----
        registerWithContext<SubscribeFramesResponse, SubscribeFramesRequest>(
          handler = { req, ctx -> startFrameSubscription(req, ctx, sessionManager, deviceManager) },
        )

        registerWithContext<UnsubscribeFramesResponse, UnsubscribeFramesRequest>(
          handler = { req, ctx ->
            val key = req.trailblazeDeviceId.toFullyQualifiedDeviceId()
            ctx.cancelPushTask("frames:$key")
            RpcResult.Success(UnsubscribeFramesResponse(req.trailblazeDeviceId))
          },
        )
      }

      registerRpcWebSocket(
        path = "/rpc-ws",
        registry = wsRegistry,
        json = RPC_WS_JSON,
      )
    }
  }

  /**
   * JSON used by the WS dispatcher. `ignoreUnknownKeys = true` and `isLenient = true` match
   * the wasmJs client's [HostRpcClient.json] so both sides stay forgiving of additive schema
   * changes. The default sealed-class discriminator (`"type"`) keeps the wire envelope
   * `{"type":"request"|"response"|"event", ...}` consistent with the wasmJs client.
   */
  private val RPC_WS_JSON: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }
}

/**
 * Handler for the [SubscribeFramesRequest] streaming RPC.
 *
 * Validates the device is connected, then launches a long-lived producer coroutine on
 * [ctx.pushScope][xyz.block.trailblaze.compose.driver.rpc.WsSessionContext.pushScope] that
 * captures frames and emits them as [RpcWsEnvelope.Event] messages keyed by [FrameEvent.EVENT_PATH].
 *
 * **Platform dispatch.**
 *  - **Android** attaches a [LiveFrameConsumer] to the per-device [H264Tee]. One
 *    `adb exec-out screenrecord --output-format=h264` invocation per device feeds both this
 *    live consumer and any concurrent MP4 capture, so there's no encoder contention. Frames
 *    arrive at encoder rate (30 fps typical) with no polling cadence — bytes flow continuously.
 *  - **iOS** and **Playwright** keep the legacy poll loop: call `stream.getScreenshot()` at
 *    [SubscribeFramesRequest.intervalMs] cadence, dedup on SHA-256, back off to
 *    [IDLE_INTERVAL_MS] after [IDLE_THRESHOLD] consecutive duplicates. iOS could move to
 *    `xcrun simctl io recordVideo` and Playwright to `Page.startScreencast` to match the
 *    Android shape; not done in this change.
 *
 * Common to both branches: content-hash dedup against the last hash sent to *this* subscriber,
 * so a still screen produces no wire traffic.
 *
 * The producer is registered with the session context under `"frames:<fqDeviceId>"`, so:
 *  - re-subscribing the same device replaces the prior producer (no leak), and
 *  - socket close auto-cancels all producers via the context.
 *
 * The pre-existing legacy polling implementation (iOS/Playwright branch) preserves the
 * full content-hash dedup + adaptive cadence + log-driven activity signal behavior — see
 * [launchLegacyPollingSubscription] for the detailed kdoc on those mechanics. The Android
 * H.264 branch replaces all of that with a continuous encoder stream, so the dedup is the
 * only piece carried over there (still helps suppress identical re-encoded JPEGs).
 */
private fun startFrameSubscription(
  req: SubscribeFramesRequest,
  ctx: WsSessionContext,
  sessionManager: HostDeviceSessionManager,
  deviceManager: TrailblazeDeviceManager,
): RpcResult<SubscribeFramesResponse> {
  val stream = sessionManager.get(req.trailblazeDeviceId)
    ?: return RpcResult.Failure(
      errorType = RpcResult.ErrorType.HTTP_ERROR,
      message = "Device not connected: ${req.trailblazeDeviceId.toFullyQualifiedDeviceId()}. " +
        "Call ConnectToDeviceRequest first.",
    )

  val deviceWidth = stream.deviceWidth
  val deviceHeight = stream.deviceHeight
  val key = req.trailblazeDeviceId.toFullyQualifiedDeviceId()

  // All platforms currently use the polling subscription path. The H.264 streaming
  // alternative (see [launchAndroidH264JpegSubscription]) is structurally blocked by
  // ffmpeg-as-subprocess output buffering on live pipes — frames never flush from the
  // mjpeg muxer until clean EOF, so JPEGs accumulate in ffmpeg's mux buffer indefinitely
  // and the wasm watchdog gives up before any frame arrives. The polling path with the
  // recent `getMirrorScreenshot` (tree-less) fast path delivers ~15-25 fps on Android
  // without any of the H.264 subprocess complexity. We keep [launchAndroidH264JpegSubscription]
  // around as a reference for when a JCodec or on-device-server approach replaces ffmpeg.
  val job = launchLegacyPollingSubscription(
    stream = stream,
    req = req,
    ctx = ctx,
    deviceManager = deviceManager,
    deviceWidth = deviceWidth,
    deviceHeight = deviceHeight,
    key = key,
  )
  ctx.replacePushTask("frames:$key", job)

  return RpcResult.Success(
    SubscribeFramesResponse(
      trailblazeDeviceId = req.trailblazeDeviceId,
      deviceWidth = deviceWidth,
      deviceHeight = deviceHeight,
    ),
  )
}

/**
 * Android subscription: bridge the per-device [H264Tee] through a [LiveFrameConsumer] (which
 * spawns a sidecar ffmpeg that decodes H.264 → JPEG), forward each JPEG frame as a [FrameEvent].
 *
 * No polling cadence on this path — the screenrecord encoder produces frames at its own rate
 * and we emit them as they arrive. The activity-driven idle clamp (PR #3018) is moot here
 * because there's no poll loop to clamp; dedup-via-SHA-256 in [LiveFrameConsumer] already
 * suppresses identical frames so a still screen produces no wire traffic.
 */
private fun launchAndroidH264JpegSubscription(
  req: SubscribeFramesRequest,
  ctx: WsSessionContext,
  deviceWidth: Int,
  deviceHeight: Int,
  key: String,
): kotlinx.coroutines.Job {
  // Use the same encoder params AndroidVideoCapture uses, so a concurrent MP4 capture shares
  // the same tee instance. If sizes diverge between callers, H264Tee.forDevice logs and uses
  // whichever attached first.
  val videoSize = AndroidVideoCapture.scaleToRecordingSize(deviceWidth, deviceHeight)
  val tee = H264Tee.forDevice(req.trailblazeDeviceId, videoSize = videoSize, bitRate = "4000000")
  return ctx.pushScope.launch {
    var sentFrames = 0L
    var lastLogMillis = 0L
    // Single bounded queue between the decoder's drain thread (producer) and the WS sender
    // coroutine (consumer). Capacity 1 + DROP_OLDEST: at most one frame waits to go on the
    // wire; if a new frame arrives while the wire is still busy, we drop the older queued
    // one. The user sees the freshest frame the device produced, not a backlog.
    //
    // Replaces a previous `ctx.pushScope.launch { ctx.emit(event) }` per frame, which would
    // spawn unbounded coroutines (each holding ~base64-of-1080p bytes) when the socket
    // writer fell behind, and orphan stale frames after a subscription replace because
    // those launches weren't children of the subscription job. Review feedback on PR #3021
    // caught both. Channel + sender below is owned by *this* job, so cancel propagates.
    val outbound = Channel<RpcWsEnvelope.Event>(
      capacity = 1,
      onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val senderJob = launch {
      try {
        for (event in outbound) {
          ctx.emit(event)
        }
      } catch (_: CancellationException) {
        // normal teardown
      }
    }
    val consumer = LiveFrameConsumer(
      tee = tee,
      onFrame = { jpegBytes ->
        val cycleStart = System.currentTimeMillis()
        val event = RpcWsEnvelope.Event(
          path = FrameEvent.EVENT_PATH,
          body = SUBSCRIPTION_FRAME_JSON.encodeToJsonElement(
            FrameEvent.serializer(),
            FrameEvent(
              trailblazeDeviceId = req.trailblazeDeviceId,
              screenshotBase64 = jpegBytes.encodeBase64(),
              deviceWidth = deviceWidth,
              deviceHeight = deviceHeight,
              capturedAtMillis = cycleStart,
            ),
          ),
        )
        // trySend never suspends — drops oldest queued frame if the channel is full. The
        // drain thread is non-coroutine, so we couldn't suspend here even if we wanted to.
        outbound.trySend(event)
        sentFrames++
        if (cycleStart - lastLogMillis >= 1000L) {
          Console.log("[FrameProducer/android-h264] device=$key sent=$sentFrames")
          lastLogMillis = cycleStart
        }
      },
    )
    consumer.start()
    try {
      // Park until the coroutine is cancelled (socket close, re-subscribe).
      while (isActive) {
        delay(1_000)
      }
    } catch (_: CancellationException) {
      // normal teardown
    } finally {
      withContext(NonCancellable) {
        runCatching { consumer.stop() }
        outbound.close()
        senderJob.cancel()
      }
    }
  }
}

/**
 * Legacy polling subscription used for non-Android platforms (iOS, Playwright). Identical to
 * the loop that lived inline in [startFrameSubscription] before the Android H.264 path was
 * introduced — preserved verbatim so iOS/Playwright behaviour is unchanged.
 */
private fun launchLegacyPollingSubscription(
  stream: xyz.block.trailblaze.recording.DeviceScreenStream,
  req: SubscribeFramesRequest,
  ctx: WsSessionContext,
  deviceManager: xyz.block.trailblaze.ui.TrailblazeDeviceManager,
  deviceWidth: Int,
  deviceHeight: Int,
  key: String,
): kotlinx.coroutines.Job {
  // Active cadence honors the request hint (default 200 ms) so existing callers behave
  // unchanged on a busy screen. Idle cadence is hardcoded ([IDLE_INTERVAL_MS]) and
  // intentionally not configurable until we see real usage warrant it.
  val activeIntervalMs = req.intervalMs.coerceAtLeast(1L)
  return ctx.pushScope.launch {
    var lastSentHash: ByteArray? = null
    // [idleCount] is now written by two coroutines on the same [pushScope]: the frame loop
    // (increments on hash match, resets on hash change) and the activity listener (resets
    // on new log emission). Both are launched on the WS session scope without an explicit
    // dispatcher, so in practice they share a thread — but to keep the cross-coroutine
    // write safe under reordering we use [AtomicInteger]. Plain JDK atomic avoids pulling
    // kotlinx-atomicfu into a JVM-only module.
    val idleCount = AtomicInteger(0)
    var sentFrames = 0L
    var totalFrames = 0L
    var lastLogMillis = 0L

    // Wake-up signal for the idle-cadence sleep. The frame loop's `delay(sleep)` would
    // otherwise sit out the full idle interval after activity arrives — resetting
    // [idleCount] only takes effect on the *next* loop iteration. With this channel, the
    // activity listener can interrupt the idle sleep so the next capture happens
    // immediately. Capacity 1 + DROP_OLDEST means rapid-fire activity coalesces into a
    // single wake-up (no need to drain the channel). Review feedback on PR #3018.
    val activityWake = Channel<Unit>(capacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    // Activity listener: watches the device's active session for new log emissions and
    // resets [idleCount] so the next frame loop iteration polls at the active cadence.
    // Launched as a child of the same [pushScope] so it's auto-cancelled when the socket
    // closes or the subscription is replaced via [replacePushTask].
    val activityListenerJob = launch {
      try {
        // The very first session map snapshot ([activeDeviceSessionsFlow] is a [StateFlow])
        // is the listener's "what's already true" baseline — it's not new activity, so we
        // skip the reset on the first emission for any device→session mapping we observe.
        // Every subsequent change (new session, session id change, log appended) is a real
        // activity signal.
        var seenBaseline = false
        deviceManager.activeDeviceSessionsFlow
          .map { it[req.trailblazeDeviceId] }
          .distinctUntilChangedBy { it }
          .collectLatest { sessionId ->
            // New session appearing for this device IS activity — the previous frame loop's
            // pixel-diff is irrelevant because we now have explicit server-side proof the
            // device is being driven. Reset immediately, before we even start the log
            // subscription (which races with the session-creation logs that may already
            // be in [getSessionLogsFlow]'s current value).
            if (seenBaseline && sessionId != null) {
              idleCount.set(0)
              activityWake.trySend(Unit)
            }
            seenBaseline = true
            if (sessionId == null) return@collectLatest

            // Force a file watcher to be running for this session before subscribing —
            // [LogsRepo.getSessionLogsFlow] would otherwise return a cached, never-updated
            // empty flow for sessions that haven't been opened via the Sessions tab. The
            // most common offender is the Android on-device recording session which is
            // created with `sendSessionStartLog = false` and never trips other watcher
            // paths. Review feedback on PR #3018.
            deviceManager.logsRepo.ensureWatchingSession(sessionId)

            // Track the log count from THIS subscription's point of view. Use the current
            // StateFlow value as the baseline so we only count genuine growth that happens
            // *after* we tune in — historical logs (e.g. a session that ran before this
            // subscriber arrived) have already been signalled via the session-appeared
            // reset above and don't need to double-count.
            var lastLogCount = deviceManager.logsRepo.getSessionLogsFlow(sessionId).value.size
            deviceManager.logsRepo.getSessionLogsFlow(sessionId)
              .distinctUntilChangedBy { it.size }
              .collect { logs ->
                if (logs.size > lastLogCount) {
                  lastLogCount = logs.size
                  idleCount.set(0)
                  // Wake the idle sleep below so the next capture happens NOW, not after
                  // the remaining idle-interval timeout. See [activityWake] kdoc.
                  activityWake.trySend(Unit)
                }
              }
          }
      } catch (_: CancellationException) {
        // socket closing or subscription replaced; normal teardown
      } catch (e: Exception) {
        Console.log("[FrameProducer] activity listener failed for $key: ${e.message}")
      }
    }

    try {
      while (isActive) {
        val cycleStart = System.currentTimeMillis()
        val bytes = try {
          // Mirror-only fast path. This loop only feeds the live viewer's display — the
          // recorder's tap-time captures go through [stream.getScreenshot] directly and get
          // the atomic (screenshot, tree) pair. See [DeviceScreenStream.getMirrorScreenshot]
          // for the perf trade-off (Android skips ~100-300 ms of tree work per frame).
          stream.getMirrorScreenshot().takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Console.log("[SubscribeFrames] capture failed for $key: ${e.message}")
          null
        }
        if (bytes != null) {
          totalFrames++
          val hash = sha256(bytes)
          if (hash.contentEquals(lastSentHash)) {
            idleCount.incrementAndGet()
          } else {
            idleCount.set(0)
            lastSentHash = hash
            val event = RpcWsEnvelope.Event(
              path = FrameEvent.EVENT_PATH,
              body = SUBSCRIPTION_FRAME_JSON.encodeToJsonElement(
                FrameEvent.serializer(),
                FrameEvent(
                  trailblazeDeviceId = req.trailblazeDeviceId,
                  screenshotBase64 = bytes.encodeBase64(),
                  deviceWidth = deviceWidth,
                  deviceHeight = deviceHeight,
                  capturedAtMillis = cycleStart,
                ),
              ),
            )
            ctx.emit(event)
            sentFrames++
          }
          if (cycleStart - lastLogMillis >= 1000L) {
            Console.log(
              "[FrameProducer] device=$key sent=$sentFrames/$totalFrames idle=${idleCount.get()}",
            )
            lastLogMillis = cycleStart
          }
        }
        val elapsed = System.currentTimeMillis() - cycleStart
        // Idle clamp: never poll *faster* than the active cadence. If the caller passes
        // `intervalMs > 1000` (e.g. 5000 ms), the hardcoded [IDLE_INTERVAL_MS] would
        // *speed up* polling in idle mode, which is the opposite of what "idle" means.
        // `maxOf(IDLE_INTERVAL_MS, activeIntervalMs)` makes idle slower-or-equal to active
        // regardless of caller intent. Review feedback on PR #3016 caught this.
        val targetInterval = if (idleCount.get() >= IDLE_THRESHOLD) {
          maxOf(IDLE_INTERVAL_MS, activeIntervalMs)
        } else {
          activeIntervalMs
        }
        val sleep = (targetInterval - elapsed).coerceAtLeast(0L)
        if (sleep > 0L) {
          // Race the sleep against the activity wake channel: log activity arriving
          // mid-sleep cancels the wait so the next capture happens immediately. Without
          // this, the loop would sit out the full idle interval (up to 1 s) even after
          // [idleCount] was reset. Review feedback on PR #3018.
          select<Unit> {
            onTimeout(sleep) { /* normal cadence */ }
            activityWake.onReceive { /* activity interrupted the idle wait */ }
          }
        }
      }
    } catch (_: CancellationException) {
      // socket closing or subscription replaced; normal teardown
    } catch (e: Exception) {
      Console.log("[SubscribeFrames] producer loop crashed for $key: ${e.message}")
    } finally {
      activityListenerJob.cancel()
      // Best-effort: nothing to clean up server-side (no per-device resources held
      // outside the existing DeviceScreenStream which the session manager owns).
      // NonCancellable lets any future cleanup complete even during scope shutdown.
      withContext(NonCancellable) { /* placeholder for future cleanup */ }
    }
  }
}

/**
 * Idle capture cadence — used after [IDLE_THRESHOLD] consecutive duplicate hashes. The
 * active cadence is taken from [SubscribeFramesRequest.intervalMs] (default 200 ms).
 * 1000 ms gives a still screen a steady ~1 fps heartbeat capture (still suppressed on the
 * wire) without blowing CPU; the loop snaps back to the active cadence as soon as bytes change.
 */
private const val IDLE_INTERVAL_MS = 1000L

/**
 * Consecutive duplicate hashes before the loop slows down. 5 dedups at the 200 ms active
 * cadence is ~1 s of stillness — long enough to skip transient pauses (e.g. an animation
 * finishing) without flickering between cadences.
 */
private const val IDLE_THRESHOLD = 5

/** Per-loop SHA-256 — JVM-only server, so [MessageDigest] is the obvious choice. */
private fun sha256(bytes: ByteArray): ByteArray =
  MessageDigest.getInstance("SHA-256").digest(bytes)

/**
 * JSON used to encode outbound [FrameEvent]s. Matches the envelope JSON's defaults so the
 * wire stream is consistent (one [Json] instance per concern keeps unrelated configs from
 * leaking into each other).
 */
private val SUBSCRIPTION_FRAME_JSON: Json = Json {
  ignoreUnknownKeys = true
  isLenient = true
}
