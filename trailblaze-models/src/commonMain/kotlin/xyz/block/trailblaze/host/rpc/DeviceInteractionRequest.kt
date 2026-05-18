package xyz.block.trailblaze.host.rpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest

/**
 * Primitive gesture / input interactions the web recording UI can dispatch to a connected
 * device. Maps 1-to-1 with [xyz.block.trailblaze.recording.DeviceScreenStream] methods so
 * the server handler is a thin bridge with no translation logic.
 */
@Serializable
sealed interface DeviceInteraction {
  @Serializable
  @SerialName("tap")
  data class Tap(val x: Int, val y: Int) : DeviceInteraction

  @Serializable
  @SerialName("longPress")
  data class LongPress(val x: Int, val y: Int) : DeviceInteraction

  @Serializable
  @SerialName("swipe")
  data class Swipe(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Long? = null,
  ) : DeviceInteraction

  @Serializable
  @SerialName("inputText")
  data class InputText(val text: String) : DeviceInteraction

  @Serializable
  @SerialName("pressKey")
  data class PressKey(val key: String) : DeviceInteraction
}

/**
 * Dispatches a single [interaction] to the device identified by [trailblazeDeviceId].
 * The device must be connected via [ConnectToDeviceRequest] before this is called.
 */
@Serializable
data class DeviceInteractionRequest(
  val trailblazeDeviceId: TrailblazeDeviceId,
  val interaction: DeviceInteraction,
) : RpcRequest<DeviceInteractionResponse>

@Serializable
data class DeviceInteractionResponse(
  val success: Boolean,
  val errorMessage: String? = null,
)
