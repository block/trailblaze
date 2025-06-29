package xyz.block.trailblaze.mcp.models

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

sealed interface DeviceConnectionStatus {

  val deviceId: String?
  val statusText: String

  class StartingConnection(
    override val deviceId: String?,
  ) : DeviceConnectionStatus {
    override val statusText: String = """
        Starting connection to Device $deviceId.
    """.trimIndent()
  }

  class ConnectionFailure(
    val errorMessage: String,
    override val deviceId: String? = null,
  ) : DeviceConnectionStatus {

    override val statusText: String =
      "Connection failed $errorMessage"
  }

  data class ThereIsAlreadyAnActiveConnection(
    override val deviceId: String,
  ) : DeviceConnectionStatus {
    override val statusText: String = "There is already an active connection with device $deviceId."
  }

  data class NoConnection(
    override val deviceId: String? = null,
  ) : DeviceConnectionStatus {
    override val statusText: String = "No active connections to any devices."
  }

  data class TrailblazeInstrumentationRunning(
    override val deviceId: String?,
  ) : DeviceConnectionStatus {
    val startTime: Instant = Clock.System.now()
    override val statusText: String = "Trailblaze Running inside Instrumentation"
  }
}
