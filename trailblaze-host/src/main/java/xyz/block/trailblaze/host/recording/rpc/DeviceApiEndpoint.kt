package xyz.block.trailblaze.host.recording.rpc

import io.ktor.server.routing.Routing
import io.ktor.server.websocket.webSocket
import io.ktor.util.encodeBase64
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.compose.driver.rpc.RpcWsHandlerRegistry
import xyz.block.trailblaze.compose.driver.rpc.WsSessionContext
import xyz.block.trailblaze.compose.driver.rpc.registerRpcWebSocket
import xyz.block.trailblaze.rpc.registerRpcHandler
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
import xyz.block.trailblaze.capture.video.H264AccessUnit
import xyz.block.trailblaze.capture.video.H264Tee
import xyz.block.trailblaze.capture.video.LiveFrameConsumer
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.recording.IosBaguetteServer
import xyz.block.trailblaze.host.recording.streamAndroidLiveJpegFrames
import xyz.block.trailblaze.host.recording.streamAndroidLiveH264AccessUnits
import xyz.block.trailblaze.host.recording.streamIosLiveH264AccessUnits
import xyz.block.trailblaze.playwright.recording.PlaywrightDeviceScreenStream
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
 *   /devices/api/stream — fast binary frames: Android Annex-B H.264 (browser WebCodecs) or
 *                         Web JPEG (Chromium CDP screencast). Falls back to SubscribeFrames.
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

      // Fast mirror path, Trailblaze-owned end to end. Live streaming is the default for every
      // primary platform over this one socket; no ws-scrcpy process, external proxy, or second
      // service port is required. Three encoders feed it:
      //   - Android: the shared `screenrecord` H.264 encoder → binary Annex-B access units.
      //   - iOS: a `baguette` subprocess whose avcc output is converted to the identical Annex-B wire
      //     (see streamIosLiveH264AccessUnits), so it shares Android's exact browser WebCodecs path.
      //   - Web (Playwright/Chromium): binary JPEG frames from a CDP screencast.
      // The generic RPC socket above stays available for controls and the JPEG-poll fallback
      // (SubscribeFrames). iOS streaming is optional: when baguette isn't installed the endpoint
      // declines here and the browser falls back to JPEG polling.
      webSocket("/devices/api/stream") {
        val instanceId = call.request.queryParameters["instanceId"]?.takeIf { it.isNotBlank() }
        val platform =
          call.request.queryParameters["platform"]
            // Locale.ROOT: the platform param is a protocol enum, not user-facing text; a
            // Turkish-locale default would lowercase-fold "I" to a dotless "ı" and break valueOf.
            ?.uppercase(java.util.Locale.ROOT)
            ?.let { runCatching { TrailblazeDevicePlatform.valueOf(it) }.getOrNull() }
        if (instanceId == null || platform == null) {
          close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "instanceId and platform are required"))
          return@webSocket
        }
        when (platform) {
          TrailblazeDevicePlatform.ANDROID,
          TrailblazeDevicePlatform.WEB -> Unit
          TrailblazeDevicePlatform.IOS ->
            if (!IosBaguetteServer.isAvailable()) {
              // No baguette binary: decline so the browser degrades to the JPEG poll rather than
              // hang. `brew install baguette` (macOS + Apple Silicon) enables the live H.264 path.
              Console.log(
                "[devices-stream] iOS live stream declined for $instanceId; baguette not installed — " +
                  "browser will fall back to JPEG polling",
              )
              close(
                CloseReason(
                  CloseReason.Codes.CANNOT_ACCEPT,
                  "iOS H.264 streaming requires baguette (brew install baguette)",
                ),
              )
              return@webSocket
            }
          else -> {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "live stream not supported for $platform"))
            return@webSocket
          }
        }
        val deviceId = xyz.block.trailblaze.devices.TrailblazeDeviceId(instanceId, platform)
        val stream = sessionManager.get(deviceId)
        if (stream == null) {
          close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "device is not connected"))
          return@webSocket
        }
        // The web fast path needs a Playwright-backed page to drive the CDP screencast; any
        // other web stream shape has to use the JPEG-poll fallback, so refuse here cleanly.
        val webStream = if (platform == TrailblazeDevicePlatform.WEB) {
          stream as? PlaywrightDeviceScreenStream
            ?: run {
              close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "web stream is not Playwright-backed"))
              return@webSocket
            }
        } else {
          null
        }

        val format = if (platform == TrailblazeDevicePlatform.WEB) "jpeg" else "annexb"
        outgoing.send(
          Frame.Text(
            buildJsonObject {
                put("type", "configuration")
                put("deviceWidth", stream.deviceWidth)
                put("deviceHeight", stream.deviceHeight)
                put("format", format)
              }
              .toString(),
          ),
        )
        val heartbeat = launch {
          while (isActive) {
            delay(H264_HEARTBEAT_INTERVAL_MS)
            outgoing.send(Frame.Text("{\"type\":\"heartbeat\"}"))
          }
        }
        val onAccessUnit: suspend (H264AccessUnit) -> Unit = { accessUnit ->
          outgoing.send(Frame.Binary(fin = true, data = accessUnit.bytes))
        }
        try {
          when {
            webStream != null ->
              webStream.streamScreencastJpegFrames { jpegBytes ->
                outgoing.send(Frame.Binary(fin = true, data = jpegBytes))
              }
            platform == TrailblazeDevicePlatform.IOS ->
              streamIosLiveH264AccessUnits(deviceId = deviceId, onAccessUnit = onAccessUnit)
            else ->
              streamAndroidLiveH264AccessUnits(
                deviceId = deviceId,
                deviceWidth = stream.deviceWidth,
                deviceHeight = stream.deviceHeight,
                onAccessUnit = onAccessUnit,
              )
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Console.log("[devices-stream] $format stream failed for ${deviceId.toFullyQualifiedDeviceId()}: ${e.message}")
          close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "device stream failed"))
        } finally {
          heartbeat.cancel()
        }
      }
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
 * Both branches content-deduplicate unchanged frames. The Android stream emits a lightweight
 * periodic repeat so the browser can distinguish a still screen from a stalled decoder; the
 * polling branch relies on its existing activity-aware cadence and browser fallback.
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

  val job =
    if (req.trailblazeDeviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.ANDROID) {
      launchAndroidH264JpegSubscription(
        req = req,
        ctx = ctx,
        deviceWidth = deviceWidth,
        deviceHeight = deviceHeight,
        key = key,
      )
    } else {
      launchLegacyPollingSubscription(
        stream = stream,
        req = req,
        ctx = ctx,
        deviceManager = deviceManager,
        deviceWidth = deviceWidth,
        deviceHeight = deviceHeight,
        key = key,
      )
    }
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
 * because there's no poll loop to clamp; dedup-via-SHA-256 in [LiveFrameConsumer] suppresses
 * identical frames between periodic proof-of-life repeats.
 */
private fun launchAndroidH264JpegSubscription(
  req: SubscribeFramesRequest,
  ctx: WsSessionContext,
  deviceWidth: Int,
  deviceHeight: Int,
  key: String,
): kotlinx.coroutines.Job {
  return ctx.pushScope.launch {
    var sentFrames = 0L
    var lastLogMillis = 0L
    try {
      streamAndroidLiveJpegFrames(
        deviceId = req.trailblazeDeviceId,
        deviceWidth = deviceWidth,
        deviceHeight = deviceHeight,
      ) { jpegBytes ->
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
        ctx.emit(event)
        sentFrames++
        if (cycleStart - lastLogMillis >= 1000L) {
          Console.log("[FrameProducer/android-h264] device=$key sent=$sentFrames")
          lastLogMillis = cycleStart
        }
      }
    } catch (_: CancellationException) {
      // normal teardown
    } catch (e: Exception) {
      // Keep the multiplexed RPC socket alive if screenrecord or ffmpeg cannot start. The
      // browser's frame-stall watchdog will fall back to snapshot polling, while controls
      // and unrelated RPCs continue using the existing WebSocket.
      Console.log("[FrameProducer/android-h264] stream failed for $key: ${e.message}")
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

/** Keeps the browser's stall watchdog alive while Android suppresses unchanged video frames. */
private const val H264_HEARTBEAT_INTERVAL_MS = 1_000L

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
