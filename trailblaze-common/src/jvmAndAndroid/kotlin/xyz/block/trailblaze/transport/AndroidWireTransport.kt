package xyz.block.trailblaze.transport

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.util.Console

enum class AndroidWireTransportMode {
  AUTO,
  PROTOBUF,
  JSON,
}

/** Shared rollback switch for host RPC and device-to-host log uploads. */
object AndroidWireTransport {
  const val ENVIRONMENT_VARIABLE = "TRAILBLAZE_ANDROID_WIRE_TRANSPORT"

  val mode: AndroidWireTransportMode by lazy {
    when (val value = System.getenv(ENVIRONMENT_VARIABLE)?.trim()?.lowercase()) {
      null, "", "auto" -> AndroidWireTransportMode.AUTO
      "protobuf", "proto", "websocket", "ws" -> AndroidWireTransportMode.PROTOBUF
      "json", "http" -> AndroidWireTransportMode.JSON
      else -> {
        Console.log(
          "[AndroidWireTransport] Ignoring invalid $ENVIRONMENT_VARIABLE=$value; using auto",
        )
        AndroidWireTransportMode.AUTO
      }
    }
  }

  /**
   * The wire a client driving [driverType] may use: [mode], except for drivers whose screen state
   * the binary codec cannot encode at all — see [TrailblazeDriverType.protoWireSafe]. Those are
   * pinned to JSON regardless of the environment switch, because for them protobuf is not merely
   * slower or newer, it fails outright.
   *
   * A null driver is treated as proto-safe, matching the environment-selected default.
   */
  fun modeFor(driverType: TrailblazeDriverType?): AndroidWireTransportMode =
    if (driverType != null && !driverType.protoWireSafe) {
      AndroidWireTransportMode.JSON
    } else {
      mode
    }
}
