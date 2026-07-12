package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.KeyCode
import maestro.orchestra.Command
import maestro.orchestra.PressKeyCommand
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.yaml.serializers.CaseInsensitiveEnumSerializer

@Serializable
@TrailblazeToolClass("pressKey")
@LLMDescription(
  """
Press a special key that isn't used for regular text input. Examples:
- BACK: navigate to the previous page or state (Android only).
- ENTER: submit the current form or text input.
- HOME: go to the device's home screen / send the current app to the background.
- BACKSPACE: delete the character before the caret in the currently focused field.
- TAB: move focus to the next field.
- ESCAPE: dismiss the keyboard or current modal.
""",
)
data class PressKeyTrailblazeTool(
  val keyCode: PressKeyCode,
) : MapsToMaestroCommands() {

  @Serializable(with = PressKeyCode.Serializer::class)
  enum class PressKeyCode {
    BACK,
    ENTER,
    HOME,
    // Editing / navigation keys forwarded from the live device viewer's keyboard handler.
    // Adding these makes Backspace/Delete actually delete in form fields, Tab navigate
    // between fields, and Escape dismiss keyboards/modals. Maestro's KeyCode already
    // supports them — we were just not exposing them through this wrapper, so they fell
    // through `OnDeviceRpcDeviceScreenStream.pressKey`'s when-branch as silent no-ops.
    BACKSPACE,
    TAB,
    ESCAPE,
    ;

    object Serializer : CaseInsensitiveEnumSerializer<PressKeyCode>(PressKeyCode::class)
  }

  override fun toMaestroCommands(): List<Command> = listOf(
    PressKeyCommand(
      code = keyCode.let { code ->
        when (code) {
          PressKeyCode.BACK -> KeyCode.BACK
          PressKeyCode.ENTER -> KeyCode.ENTER
          PressKeyCode.HOME -> KeyCode.HOME
          PressKeyCode.BACKSPACE -> KeyCode.BACKSPACE
          PressKeyCode.TAB -> KeyCode.TAB
          PressKeyCode.ESCAPE -> KeyCode.ESCAPE
        }
      },
    ),
  )

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val result = super.execute(toolExecutionContext)
    if (result.isSuccess()) return TrailblazeToolResult.Success(message = "Pressed ${keyCode.name}")
    return result
  }
}
