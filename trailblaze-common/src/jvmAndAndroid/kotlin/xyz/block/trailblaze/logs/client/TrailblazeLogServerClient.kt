package xyz.block.trailblaze.logs.client

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.datetime.Clock
import okio.ByteString.Companion.toByteString
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.ondevice.rpc.proto.LogUploadEnvelope
import xyz.block.trailblaze.ondevice.rpc.proto.ScreenshotUpload
import xyz.block.trailblaze.ondevice.rpc.proto.TraceUpload
import xyz.block.trailblaze.transport.AndroidWireTransport
import xyz.block.trailblaze.transport.AndroidWireTransportMode
import xyz.block.trailblaze.util.Console

class TrailblazeLogServerClient(
  val httpClient: HttpClient,
  val baseUrl: String,
  private val useBinaryTransport: Boolean,
) {
  constructor(httpClient: HttpClient, baseUrl: String) : this(httpClient, baseUrl, false)

  private val webSocketClientDelegate = lazy { TrailblazeLogWebSocketClient(httpClient, baseUrl) }
  private val webSocketClient by webSocketClientDelegate

  private suspend fun ping(): HttpResponse = httpClient.get("$baseUrl/ping")

  suspend fun isServerRunning(): Boolean {
    val startTime = Clock.System.now()
    val isRunning = try {
      ping().status.value == HttpStatusCode.OK.value
    } catch (e: Exception) {
      false
    }
    Console.log("isServerRunning $isRunning in ${Clock.System.now() - startTime}ms")
    return isRunning
  }

  suspend fun postAgentLog(log: TrailblazeLog): HttpResponse {
    val logJson = TrailblazeJsonInstance.encodeToString<TrailblazeLog>(log)
    return httpClient.post("$baseUrl/agentlog") {
      contentType(ContentType.Application.Json)
      setBody(logJson)
    }
  }

  suspend fun sendAgentLog(log: TrailblazeLog): Boolean =
    sendWithPreferredTransport(
      protobuf = { id ->
        LogUploadEnvelope(
          upload_id = id,
          agent_log = TrailblazeLogProtoCodec.run { log.toProto() },
        )
      },
      jsonHttp = { postAgentLog(log).status == HttpStatusCode.OK },
    )

  suspend fun postScreenshot(
    screenshotFilename: String,
    sessionId: SessionId,
    screenshotBytes: ByteArray,
  ): HttpResponse = httpClient.post("$baseUrl/log/screenshot") {
    parameter(key = "filename", value = screenshotFilename)
    parameter(key = "session", value = sessionId.value)
    contentType(ContentType.Image.PNG)
    setBody(screenshotBytes)
  }

  suspend fun sendScreenshot(
    screenshotFilename: String,
    sessionId: SessionId,
    screenshotBytes: ByteArray,
  ): Boolean = sendWithPreferredTransport(
    protobuf = { id ->
      LogUploadEnvelope(
        upload_id = id,
        screenshot = ScreenshotUpload(
          filename = screenshotFilename,
          session_id = sessionId.value,
          image = screenshotBytes.toByteString(),
        ),
      )
    },
    jsonHttp = {
      postScreenshot(screenshotFilename, sessionId, screenshotBytes).status == HttpStatusCode.OK
    },
  )

  suspend fun postTrace(sessionId: SessionId, traceJson: String, onDeviceClock: Boolean = false): HttpResponse =
    httpClient.post("$baseUrl/log/trace") {
      parameter(key = "session", value = sessionId.value)
      parameter(key = CLOCK_PARAM, value = if (onDeviceClock) DEVICE_CLOCK else HOST_CLOCK)
      contentType(ContentType.Application.Json)
      setBody(traceJson)
    }

  /**
   * Uploads a recorded trace.
   *
   * [onDeviceClock] says the timestamps were stamped by a device's own wall clock, which drifts from
   * the host's by whole seconds. The receiver cannot work this out for itself — the host uploads its
   * own trace through this same route — and it matters both ways: an unmarked device batch stretches
   * the session window by the drift, and a host batch mistaken for a device one is taken off the
   * host timeline entirely.
   */
  suspend fun sendTrace(sessionId: SessionId, traceJson: String, onDeviceClock: Boolean = false): Boolean =
    sendWithPreferredTransport(
      protobuf = { id ->
        LogUploadEnvelope(
          upload_id = id,
          trace = TraceUpload(
            session_id = sessionId.value,
            trace_json = traceJson.encodeToByteArray().toByteString(),
            clock = if (onDeviceClock) DEVICE_CLOCK else HOST_CLOCK,
          ),
        )
      },
      jsonHttp = { postTrace(sessionId, traceJson, onDeviceClock).status == HttpStatusCode.OK },
    )

  private suspend fun sendWithPreferredTransport(
    protobuf: (Long) -> LogUploadEnvelope,
    jsonHttp: suspend () -> Boolean,
  ): Boolean {
    if (!useBinaryTransport) return jsonHttp()
    if (AndroidWireTransport.mode == AndroidWireTransportMode.JSON) return jsonHttp()
    return when (val attempt = webSocketClient.send(protobuf)) {
      TrailblazeLogWebSocketClient.Attempt.Success -> true
      TrailblazeLogWebSocketClient.Attempt.FallbackToHttp ->
        if (AndroidWireTransport.mode == AndroidWireTransportMode.AUTO) jsonHttp() else false
      is TrailblazeLogWebSocketClient.Attempt.Failure -> {
        Console.log("[TrailblazeLogWebSocket] ${attempt.message}")
        false
      }
    }
  }

  fun close() {
    if (webSocketClientDelegate.isInitialized()) webSocketClientDelegate.value.close()
    httpClient.close()
  }

  companion object {
    /**
     * Which clock stamped a trace upload's timestamps, shared with the endpoints that read it.
     *
     * Both values are always sent, so that an ABSENT marker means exactly one thing: an uploader
     * older than this field. The two routes read that absence differently, because they carry
     * different traffic — see `LogTracePostEndpoint` and `LogWebSocketEndpoint`.
     */
    const val CLOCK_PARAM: String = "clock"
    const val DEVICE_CLOCK: String = "device"
    const val HOST_CLOCK: String = "host"
  }
}
