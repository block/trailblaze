package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.server.routing.Routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.annotations.TestOnly
import xyz.block.trailblaze.logs.client.TrailblazeLogProtoCodec
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.ondevice.rpc.proto.LogUploadAck
import xyz.block.trailblaze.ondevice.rpc.proto.OnDeviceRpcProtoCodec
import xyz.block.trailblaze.report.utils.LogsRepo

/** Persistent protobuf endpoint for device-to-host logs, screenshots, and traces. */
internal object LogWebSocketEndpoint {
  @TestOnly private var connectionListener: () -> Unit = {}

  @TestOnly
  fun setServerConnectionListener(listener: () -> Unit) {
    connectionListener = listener
  }

  fun register(routing: Routing, logsRepo: LogsRepo) = with(routing) {
    webSocket("/logs-ws") {
      connectionListener()
      val sendMutex = Mutex()
      for (frame in incoming) {
        if (frame !is Frame.Binary) continue
        val frameBytes = frame.readBytes()
        var uploadId = 0L
        val ack = try {
          val upload = OnDeviceRpcProtoCodec.decodeLogUpload(frameBytes)
          uploadId = upload.upload_id
          val agentLog = upload.agent_log
          val screenshot = upload.screenshot
          val trace = upload.trace
          when {
            agentLog != null -> {
              val log = TrailblazeLogProtoCodec.run { agentLog.toModel() }
              AgentLogEndpoint.accept(log, logsRepo)
            }
            screenshot != null -> saveScreenshot(
              logsRepo = logsRepo,
              session = screenshot.session_id,
              filename = screenshot.filename,
              bytes = screenshot.image.toByteArray(),
            )
            trace != null -> saveTrace(
              logsRepo = logsRepo,
              session = trace.session_id,
              json = trace.trace_json.utf8(),
            )
            else -> error("Protobuf log upload omitted its payload")
          }
          LogUploadAck(upload_id = upload.upload_id, success = true)
        } catch (e: Exception) {
          LogUploadAck(
            upload_id = uploadId,
            success = false,
            error_message = e.message ?: e::class.simpleName,
          )
        }
        sendMutex.withLock {
          send(
            Frame.Binary(
              fin = true,
              data = OnDeviceRpcProtoCodec.encode(ack),
            ),
          )
        }
      }
    }
  }

  private fun saveScreenshot(
    logsRepo: LogsRepo,
    session: String,
    filename: String,
    bytes: ByteArray,
  ) {
    val sessionDir = validatedSessionDir(logsRepo, session)
    requireSafeSegment(filename, "filename")
    val destination = File(sessionDir, filename)
    require(destination.canonicalPath.startsWith(sessionDir.canonicalPath + File.separator)) {
      "Invalid filename"
    }
    destination.writeBytes(bytes)
  }

  private fun saveTrace(logsRepo: LogsRepo, session: String, json: String) {
    validatedSessionDir(logsRepo, session).resolve("trace.json").writeText(json)
  }

  private fun validatedSessionDir(logsRepo: LogsRepo, session: String): File {
    requireSafeSegment(session, "session ID")
    val candidate = File(logsRepo.logsDir, session)
    require(candidate.canonicalPath.startsWith(logsRepo.logsDir.canonicalPath + File.separator)) {
      "Invalid session ID"
    }
    return logsRepo.getSessionDir(SessionId(session))
  }

  private fun requireSafeSegment(value: String, label: String) {
    require(
      value.isNotBlank() &&
        !value.contains("..") &&
        !value.contains('/') &&
        !value.contains('\\') &&
        !value.contains('\u0000'),
    ) { "Invalid $label" }
  }
}
