package xyz.block.trailblaze.host.recording.rpc

import xyz.block.trailblaze.host.rpc.DeviceInteraction
import xyz.block.trailblaze.host.rpc.DeviceInteractionRequest
import xyz.block.trailblaze.host.rpc.DeviceInteractionResponse
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult

/**
 * Forwards a single gesture or input event to the connected device. Maps each
 * [DeviceInteraction] subclass to the corresponding
 * [xyz.block.trailblaze.recording.DeviceScreenStream] method — no translation logic, just
 * dispatch.
 */
class DeviceInteractionHandler(
  private val sessionManager: HostDeviceSessionManager,
) : RpcHandler<DeviceInteractionRequest, DeviceInteractionResponse> {

  override suspend fun handle(
    request: DeviceInteractionRequest,
  ): RpcResult<DeviceInteractionResponse> {
    val stream = sessionManager.get(request.trailblazeDeviceId)
      ?: return RpcResult.Success(
        DeviceInteractionResponse(
          success = false,
          errorMessage = "Device not connected: ${request.trailblazeDeviceId.toFullyQualifiedDeviceId()}",
        ),
      )

    return try {
      when (val interaction = request.interaction) {
        is DeviceInteraction.Tap -> stream.tap(interaction.x, interaction.y)
        is DeviceInteraction.LongPress -> stream.longPress(interaction.x, interaction.y)
        is DeviceInteraction.Swipe -> stream.swipe(
          startX = interaction.startX,
          startY = interaction.startY,
          endX = interaction.endX,
          endY = interaction.endY,
          durationMs = interaction.durationMs,
        )
        is DeviceInteraction.InputText -> stream.inputText(interaction.text)
        is DeviceInteraction.PressKey -> stream.pressKey(interaction.key)
      }
      RpcResult.Success(DeviceInteractionResponse(success = true))
    } catch (e: Exception) {
      RpcResult.Success(
        DeviceInteractionResponse(
          success = false,
          errorMessage = e.message ?: e.javaClass.simpleName,
        ),
      )
    }
  }
}
