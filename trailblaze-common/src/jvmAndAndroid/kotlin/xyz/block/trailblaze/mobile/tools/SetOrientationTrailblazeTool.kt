package xyz.block.trailblaze.mobile.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.device.DeviceOrientation
import maestro.orchestra.Command
import maestro.orchestra.SetOrientationCommand
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.yaml.serializers.CaseInsensitiveEnumSerializer

/**
 * Rotates the device to a fixed orientation and keeps it there until the next `mobile_setOrientation`.
 *
 * ```yaml
 * - mobile_setOrientation:
 *     orientation: LANDSCAPE_LEFT
 * ```
 *
 * Lowers to Maestro's `setOrientation`, which Android (accessibility) and iOS host Maestro
 * service. The AXe iOS driver has no way to rotate a simulator, so the tool fails there rather
 * than reporting a rotation that never happened. Mobile-only: on web, resize the viewport with
 * `web_resize`.
 */
@Serializable
@TrailblazeToolClass("mobile_setOrientation")
@LLMDescription(
  "Rotate the device to PORTRAIT, LANDSCAPE_LEFT, LANDSCAPE_RIGHT or UPSIDE_DOWN. The device " +
    "stays in that orientation until rotated again, so rotate back when the step that needed it is done.",
)
data class SetOrientationTrailblazeTool(
  val orientation: Orientation,
) : MapsToMaestroCommands() {

  /** The orientations Maestro's `setOrientation` accepts. */
  @Serializable(with = Orientation.Serializer::class)
  enum class Orientation(val maestro: DeviceOrientation) {
    PORTRAIT(DeviceOrientation.PORTRAIT),
    LANDSCAPE_LEFT(DeviceOrientation.LANDSCAPE_LEFT),
    LANDSCAPE_RIGHT(DeviceOrientation.LANDSCAPE_RIGHT),
    UPSIDE_DOWN(DeviceOrientation.UPSIDE_DOWN),
    ;

    object Serializer : CaseInsensitiveEnumSerializer<Orientation>(Orientation::class, Orientation.entries)
  }

  override fun toMaestroCommands(): List<Command> = listOf(
    SetOrientationCommand(orientation = orientation.maestro),
  )

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (toolExecutionContext.trailblazeDeviceInfo.trailblazeDriverType == TrailblazeDriverType.IOS_AXE) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "The AXe iOS driver cannot rotate the simulator. Run this step on the " +
          "IOS_HOST driver, which rotates through Maestro.",
        command = this,
      )
    }
    val result = super.execute(toolExecutionContext)
    if (result.isSuccess()) return TrailblazeToolResult.Success(message = "Rotated to ${orientation.name}")
    return result
  }
}
