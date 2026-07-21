package xyz.block.trailblaze.host.recording

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import xyz.block.trailblaze.capture.video.BaguetteAvccStreamParser
import xyz.block.trailblaze.capture.video.H264AccessUnit
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.util.Console

/**
 * Streams complete Annex-B H.264 access units from a booted iOS Simulator, without decoding or
 * re-encoding on the host — the iOS counterpart to [streamAndroidLiveH264AccessUnits].
 *
 * The frames come from the shared `baguette serve` process ([IosBaguetteServer]) over a WebSocket
 * (`/simulators/<udid>/stream?format=avcc`). Each binary message is one avcc record, converted to
 * the same [H264AccessUnit] Annex-B shape the Android tee produces (see [BaguetteAvccStreamParser])
 * and pushed to the browser over the shared `/devices/api/stream` endpoint, where WebCodecs decodes
 * it. The wire and the browser decode path are byte-for-byte the same as Android's.
 *
 * baguette forces an IDR at stream start, so a fresh WebSocket always begins with a decodable
 * keyframe — no mid-stream keyframe cache is needed (unlike the shared Android tee, whose
 * screenrecord encoder emits an IDR essentially only once). The bounded channel applies backpressure
 * (OkHttp stops reading the socket) rather than dropping predictive frames, which would otherwise
 * make the decoder wait for the next IDR.
 *
 * Returns when the WebSocket closes or fails (baguette exit, network error) as well as on
 * cancellation, so a died producer lets the caller close the browser WebSocket and fall back to JPEG
 * polling — rather than parking here forever while heartbeats mask a frozen frame.
 *
 * `baguette serve` fans one capture out to many clients, so several viewers of one simulator each
 * open their own WebSocket here against the same shared server (no per-viewer capture subprocess).
 */
internal suspend fun streamIosLiveH264AccessUnits(
  deviceId: TrailblazeDeviceId,
  onAccessUnit: suspend (H264AccessUnit) -> Unit,
) = coroutineScope {
  val authority =
    IosBaguetteServer.ensureServing()
      ?: run {
        Console.log(
          "[IosBaguetteServer] no baguette serve available for ${deviceId.instanceId}; " +
            "nothing to stream (browser will JPEG-poll)",
        )
        return@coroutineScope
      }
  // OkHttp takes an http(s) URL and performs the WebSocket upgrade itself; it sends no Origin
  // header by default, which is exactly what baguette serve's trust gate requires.
  val url = "http://$authority/simulators/${deviceId.instanceId}/stream?format=avcc&version=v2"

  val outbound = Channel<H264AccessUnit>(capacity = IOS_OUTBOUND_ACCESS_UNITS)
  val ended = CompletableDeferred<Unit>()
  var sender: Job? = null
  var webSocket: WebSocket? = null
  try {
    sender = launch {
      for (accessUnit in outbound) {
        onAccessUnit(accessUnit)
      }
    }

    val parser = BaguetteAvccStreamParser()
    val listener =
      object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
          // Each binary WS message is one complete [type][payload] avcc record.
          runCatching {
            parser.feed(bytes.toByteArray()) { accessUnit ->
              // runCatching: teardown closes `outbound` before this returns; a send racing the
              // close throws and the trailing frame simply has nowhere to go.
              runCatching { runBlocking { outbound.send(accessUnit) } }
            }
          }
        }

        // Text frames (describe-ui results / server errors) piggyback on the same socket. The live
        // viewer only wants video, so ignore them here.
        override fun onMessage(webSocket: WebSocket, text: String) = Unit

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
          webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
          outbound.close()
          ended.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
          Console.log(
            "[IosBaguetteServer] baguette WS ended for ${deviceId.instanceId}: ${t.message}",
          )
          outbound.close()
          ended.complete(Unit)
        }
      }
    webSocket = iosStreamHttpClient.newWebSocket(Request.Builder().url(url).build(), listener)

    // Parks while frames flow. Completes when the socket closes/fails on a died producer (draining
    // `sender`), or throws on cancellation when the client disconnects. Either way we fall through
    // to teardown and the caller closes the browser WebSocket.
    ended.await()
  } finally {
    withContext(NonCancellable) {
      outbound.close()
      runCatching { webSocket?.cancel() }
      sender?.cancel()
    }
  }
}

/** Outbound buffer of converted access units bridging the OkHttp reader thread to the sender. */
private const val IOS_OUTBOUND_ACCESS_UNITS = 30

private const val NORMAL_CLOSURE = 1000

/**
 * Shared client for the baguette WebSocket. No read timeout — the stream is long-lived and
 * continuous; a ping keeps an idle socket from being reaped by an intermediary.
 */
private val iosStreamHttpClient: OkHttpClient by lazy {
  OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .pingInterval(20, TimeUnit.SECONDS)
    .build()
}
