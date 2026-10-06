package xyz.block.trailblaze.ui.recording

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.recording.DeviceScreenStream
import xyz.block.trailblaze.recording.InteractionToolFactory

/**
 * Handle to a live recording connection. [stream] and [toolFactory] are the recorder's
 * platform-specific wiring (Maestro vs Playwright vs future drivers); [trailblazeDeviceId] +
 * [trailblazeDriverType] are surfaced so downstream code can route replays and Tool Palette
 * discovery without re-deriving them.
 */
data class RecordingDeviceConnection(
  val stream: DeviceScreenStream,
  val toolFactory: InteractionToolFactory,
  val deviceLabel: String,
  /** ID for routing single-step replays through `TrailblazeDeviceManager.runYaml`. */
  val trailblazeDeviceId: TrailblazeDeviceId,
  /** Driver type, surfaced for Tool Palette filtering. */
  val trailblazeDriverType: TrailblazeDriverType,
)

/**
 * Connection lifecycle state. Idle → Connecting → Connected | Error. The Connecting/Error
 * leaves are terminal until the user connects again or picks a different device.
 */
sealed interface ConnectionState {
  data object Idle : ConnectionState
  data object Connecting : ConnectionState
  data class Connected(val connection: RecordingDeviceConnection) : ConnectionState
  data class Error(val message: String) : ConnectionState
}

/**
 * The canonical fully-qualified device id (e.g. `android/emulator-5554`, `web/playwright-native`) —
 * the same string `trailblaze --device <id>` accepts and `trailblaze device list` prints.
 */
fun formatDeviceLabel(device: TrailblazeConnectedDeviceSummary): String =
  device.trailblazeDeviceId.toFullyQualifiedDeviceId()
