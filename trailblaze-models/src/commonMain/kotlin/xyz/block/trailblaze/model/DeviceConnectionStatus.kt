package xyz.block.trailblaze.model

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import xyz.block.trailblaze.devices.TrailblazeDeviceId

sealed interface DeviceConnectionStatus {

  val statusText: String

  sealed interface WithTargetDevice : DeviceConnectionStatus {
    val trailblazeDeviceId: TrailblazeDeviceId

    class StartingConnection(
      override val trailblazeDeviceId: TrailblazeDeviceId,
    ) : WithTargetDevice {
      override val statusText: String = """
        Starting connection to Device $trailblazeDeviceId.
    """.trimIndent()
    }

    data class TrailblazeInstrumentationRunning(
      override val trailblazeDeviceId: TrailblazeDeviceId,
    ) : WithTargetDevice {
      val startTime: Instant = Clock.System.now()
      override val statusText: String = "Trailblaze Running inside Instrumentation"
    }
  }

  sealed interface DeviceConnectionError : DeviceConnectionStatus {

    class ConnectionFailure(
      val errorMessage: String,
    ) : DeviceConnectionError {

      override val statusText: String =
        "Connection failed $errorMessage"
    }

    data class ThereIsAlreadyAnActiveConnection(
      val deviceId: TrailblazeDeviceId,
    ) : DeviceConnectionError {
      override val statusText: String = "There is already an active connection with device $deviceId."
    }
    data class NoConnection(
      val deviceId: TrailblazeDeviceId
    ) : DeviceConnectionError {
      override val statusText: String = "No active connections to any devices."
    }
  }
}
